import type { Page } from '@playwright/test'
import { createTheBudget } from '../family-steps.ts'
import { deleteAllMyData, expect, shot, test, type Session } from '../fixtures.ts'

// UX-8, with account A (D-453 to D-464):
// 1. Two expenses saved through New entry: "Save and add another" keeps every field (D-462), Amount focused with its
//    text selected, and saving it again unchanged asks first ("Save the same entry again?", dismissed: nothing sent).
// 2. On New entry, a payee typed in part finds both (D-453, D-458), an amount narrows the list to one (D-459), and
//    Copy fills the form from it, keeping the form's date (D-461); the list asks nothing new after the copy.
// 3. With E2E_FAMILY=on: a family budget, a family expense saved from New entry, found among the similar entries with
//    the shared icon, and copied: Family expense on, its family category and its note.
// 4. At 375 px (local-narrow): New entry with the list, the Entries page with a long payee's name in its filter, and
//    the header's user button, a person icon in place of the name (D-464): nothing scrolls sideways. At 1280 px the
//    header shows the name.
// Delete all my data at both ends.

const PAYEE = 'E2E Albert Heijn'
const LONG_PAYEE = 'E2E A payee with a name long enough to widen a select far past the width of a phone screen'
const BUDGET = 'New entry check'

test('new entry', async ({ as, family }) => {
  const a = await as('A')
  const { page } = a
  await deleteAllMyData(a)
  const narrow = page.viewportSize()?.width === 375

  // 1. Two expenses, the form kept between them.
  await page.goto('/entries/new')
  await page.getByLabel('Payee', { exact: true }).fill(PAYEE)
  await page.getByLabel('Paid from').selectOption({ label: 'Current account' })
  await page.getByLabel('Amount', { exact: true }).fill('11.35')
  await page.getByLabel('Category').selectOption({ label: 'Eating out' })
  await page.getByLabel('Memo').fill('E2E weekly')
  const saved = page.getByRole('status').filter({ hasText: 'Saved. The form keeps its values: change what differs.' })
  await savedAndKept(page, saved)
  await expect(page.getByLabel('Payee', { exact: true })).toHaveValue(PAYEE)
  await expect(page.getByLabel('Memo')).toHaveValue('E2E weekly')
  await expect(page.getByLabel('Category').locator('option:checked')).toHaveText('Eating out')
  const amount = page.getByLabel('Amount', { exact: true })
  await expect(amount).toBeFocused()
  await expect(amount).toHaveValue('11.35')
  expect(await amount.evaluate((input: HTMLInputElement) => [input.selectionStart, input.selectionEnd])).toEqual([0, 5])
  await amount.fill('17.99')
  await page.getByLabel('Memo').fill('E2E second')
  await savedAndKept(page, saved)

  // Unchanged, it asks; dismissed, nothing is sent.
  let posted = 0
  page.on('request', (request) => { if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/entries') posted++ })
  const asked = new Promise<string>((resolve) => page.once('dialog', (dialog) => {
    resolve(dialog.message())
    void dialog.dismiss()
  }))
  await page.getByRole('button', { name: 'Save and add another' }).click()
  expect(await asked).toBe('Save the same entry again?')
  await page.waitForTimeout(500)
  expect(posted).toBe(0)
  // A third, with a payee whose name is long, for the Entries page at 375 px.
  await page.getByLabel('Payee', { exact: true }).fill(LONG_PAYEE)
  await amount.fill('2.50')
  await savedAndKept(page, saved)

  // 2. Found by part of the payee, narrowed by an amount, copied.
  await page.goto('/entries/new')
  const heading = page.getByRole('heading', { name: /^Similar entries/ })
  await expect(heading).toHaveCount(0)
  await page.getByLabel('Payee', { exact: true }).fill('e2e alb')
  await expect(heading).toHaveText('Similar entries · 2')
  const list = page.locator('section.similar')
  await expect(list.getByRole('listitem')).toHaveCount(2)
  await expect(list.getByRole('listitem').first()).toContainText('E2E second')
  await page.getByLabel('Amount', { exact: true }).fill('17')
  await expect(heading).toHaveText('Similar entries · 1')
  await expect(list.getByRole('listitem')).toHaveCount(1)
  await shot(page, 'new-entry-01-similar')
  const cleared = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/entries/matches'
    && !new URL(r.url()).searchParams.has('amount'))
  await page.getByLabel('Amount', { exact: true }).fill('')
  expect((await cleared).status()).toBe(200)
  await expect(heading).toHaveText('Similar entries · 2')
  const matches: string[] = []
  page.on('request', (request) => { if (new URL(request.url()).pathname === '/api/entries/matches') matches.push(request.url()) })
  await list.getByRole('button', { name: /^Copy the entry of .*, E2E Albert Heijn, €17\.99$/ }).click()
  await expect(page.getByRole('status').filter({ hasText: /^Copied from the entry of .*\. Check the date and the amount\.$/ })).toBeVisible()
  await expect(page.getByLabel('Payee', { exact: true })).toHaveValue(PAYEE)
  await expect(page.getByLabel('Paid from').locator('option:checked')).toHaveText('Current account')
  await expect(page.getByLabel('Category').locator('option:checked')).toHaveText('Eating out')
  await expect(page.getByLabel('Amount', { exact: true })).toHaveValue('17.99')
  await expect(page.getByLabel('Memo')).toHaveValue('E2E second')
  await expect(page.getByLabel('Amount', { exact: true })).toBeFocused()
  await page.waitForTimeout(1000)
  expect(matches).toEqual([])
  await shot(page, 'new-entry-02-copied')

  // 3. A family expense, found and copied.
  if (family === 'on') await aFamilyExpenseFoundAndCopied(a)

  // 4. At 375 px, nothing scrolls sideways; the header's user button is an icon there.
  await expectNoSidewaysScroll(page, '/entries/new with the list', narrow)
  await page.goto('/entries')
  await expect(page.getByRole('row').filter({ hasText: 'E2E weekly' })).toHaveCount(1)
  await shot(page, 'new-entry-03-entries')
  await expectNoSidewaysScroll(page, '/entries', narrow)
  const user = page.locator('header .user-menu')
  if (narrow) {
    await expect(user.locator('svg.person')).toBeVisible()
    await expect(user.locator('.user-name')).toBeHidden()
    await user.locator('button.menu-button').click()
    await expect(user.locator('.menu-user-name')).toBeVisible()
    await shot(page, 'new-entry-04-header')
    await page.keyboard.press('Escape')
  } else {
    await expect(user.locator('.user-name')).toBeVisible()
    await expect(user.locator('svg.person')).toBeHidden()
  }

  await deleteAllMyData(a)
})

/** "Save and add another", its status, and the form kept. */
async function savedAndKept(page: Page, saved: ReturnType<Page['getByRole']>) {
  const response = page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/entries')
  await page.getByRole('button', { name: 'Save and add another' }).click()
  expect((await response).status()).toBe(201)
  await expect(saved).toBeVisible()
  await expect(page.getByRole('heading', { name: 'New entry' })).toBeVisible()
}

async function aFamilyExpenseFoundAndCopied(session: Session) {
  const { page } = session
  await createTheBudget(session, BUDGET, 'new-entry')
  await page.goto('/entries/new')
  await page.getByRole('switch', { name: 'Family expense' }).check()
  await page.getByLabel('Family category').selectOption({ label: 'Groceries' })
  await page.getByLabel(/^Payee/).fill(PAYEE)
  await page.getByLabel('Paid from').selectOption({ label: 'Current account' })
  await page.getByLabel('Amount', { exact: true }).fill('30.00')
  await page.getByLabel('Comment for the family budget').fill('E2E market')
  await page.getByLabel('Note, only you see it').fill('E2E family note')
  const created = page.waitForResponse((r) => r.request().method() === 'POST' && /\/records$/.test(new URL(r.url()).pathname))
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  expect((await created).status()).toBe(201)
  await expect(page).toHaveURL(/\/entries$/)

  await page.goto('/entries/new')
  await page.getByLabel('Payee', { exact: true }).fill('e2e alb')
  const heading = page.getByRole('heading', { name: /^Similar entries/ })
  await expect(heading).toHaveText('Similar entries · 3')
  const row = page.locator('section.similar').getByRole('listitem').filter({ hasText: 'Family payment' })
  await expect(row).toHaveCount(1)
  await expect(row.getByRole('img', { name: `Shared: ${BUDGET}` })).toBeVisible()
  await expect(row).toContainText('Groceries')
  await expect(row).toContainText('E2E family note')
  await row.getByRole('button', { name: /^Copy the entry of / }).click()
  await expect(page.getByRole('status').filter({ hasText: /^Copied from the entry of / })).toBeVisible()
  await expect(page.getByRole('switch', { name: 'Family expense' })).toBeChecked()
  await expect(page.getByLabel('Family category').locator('option:checked')).toHaveText('Groceries')
  await expect(page.getByLabel('Comment for the family budget')).toHaveValue('E2E market')
  await expect(page.getByLabel('Note, only you see it')).toHaveValue('E2E family note')
  await expect(page.getByLabel(/^Payee/)).toHaveValue(PAYEE)
  await expect(page.getByLabel('Amount', { exact: true })).toHaveValue('30.00')
  await shot(page, 'new-entry-05-family-copied')
}

/** At 375 px, the page is no wider than the window. */
async function expectNoSidewaysScroll(page: Page, what: string, narrow: boolean) {
  if (!narrow) return
  const [scroll, client] = await page.evaluate(() => [document.documentElement.scrollWidth, document.documentElement.clientWidth])
  expect(scroll, `${what}: no sideways scroll at 375 px`).toBeLessThanOrEqual(client)
}
