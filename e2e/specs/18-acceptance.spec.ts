import type { Locator } from '@playwright/test'
import { deleteAllMyData, expect, expectReloadAfterLeaving, shot, test } from '../fixtures.ts'
import type { Session } from '../fixtures.ts'
import { createTheBudget, inviteSomeoneNew, joinThroughTheLink, leave } from '../family-steps.ts'

// Family acceptance (HIST-3 part 5, D-1185), two accounts, only where the server has acceptance on:
// 1. A creates "Acceptance check" and invites B, who joins. The spec detects acceptance as the app does: a budget's
//    pending summary answers 200; with 404 it deletes what it made and skips.
// 2. A adds an expense. B's Overview shows "1 change by others is waiting for you" with Accept, and B's Activity marks
//    the record "Waiting for you". B's notifications list the change with an Accept button. B's books have no entry of
//    A's record yet.
// 3. B adds an expense and is refused with the server's message and "Accept 1 change" (D-1027). One click accepts, says
//    so, and the form keeps its values (D-1184); nothing was saved. B presses the same button again and it is saved.
// 4. A, now behind B's expense, adds one too and is refused; A accepts and does not save (D-1184). Each side's own
//    books now hold an entry of the other's expense, and not before.
// 5. B leaves, then A, which deletes the budget; Delete all my data for both.

const BUDGET = 'Acceptance check'

test.beforeEach(({ target }) => {
  // D-1149: the spec creates a budget and records in the accounts; production's run never does (it also has acceptance off).
  test.skip(target.name === 'prod', 'The acceptance spec creates a family budget and records; production runs it never (D-1149).')
})

/** The reader's entries that are shares or payments of the family record. */
async function entriesOf({ context }: Session, recordId: string): Promise<string[]> {
  const response = await context.request.get('/api/entries?size=200')
  expect(response.status()).toBe(200)
  const page = await response.json() as { content: { family?: { recordId: number | null; link: string } | null }[] }
  return page.content.filter((e) => String(e.family?.recordId) === recordId).map((e) => e.family!.link).sort()
}

/** Fills the new expense form of a family budget as `comment` says and leaves it unsaved. */
async function fillExpense({ page }: Session, budget: string, amount: string, comment: string) {
  await page.goto(`${budget}/expenses/new`)
  await expect(page.getByRole('heading', { name: 'Add an expense' })).toBeVisible()
  await page.getByLabel('Category').selectOption({ label: 'Groceries' })
  await page.getByLabel('Amount (EUR)').fill(amount)
  await page.getByLabel('Paid from').selectOption({ label: 'Current account' })
  await page.getByLabel('Comment (optional)').fill(comment)
}

const refusal = (page: Session['page']) => page.getByRole('alert').filter({ hasText: 'Accept the 1 pending change first.' })

test('accepting the changes of others in a family budget', async ({ as, family, watch }) => {
  test.skip(family === 'off', 'The family budget is switched off (E2E_FAMILY=off).')
  const a = await as('A')
  const b = await as('B')
  await deleteAllMyData(a)
  await deleteAllMyData(b)
  const nameA = (await (await a.context.request.get('/api/me')).json() as { name: string }).name

  // 1. The budget, then the detection the app uses, then the invite. The 409 is expected only once the spec runs
  // (a skipped test must not leave an expectation unmet).
  const ledgerId = await createTheBudget(a, BUDGET, 'accept')
  const pending = await a.context.request.get(`/api/family-ledgers/${ledgerId}/pending`)
  if (pending.status() !== 200) {
    await deleteAllMyData(a)
    test.skip(true, `Family acceptance is off (the pending summary answers ${pending.status()}): start the stack with FAMILY_ACCEPTANCE_ENABLED=true.`)
  }
  watch.expect({
    method: 'POST', path: /^\/api\/family-ledgers\/\d+\/records$/, status: 409,
    reason: 'a member with changes to accept is refused until they accept (the author rule)',
  })
  const link = await inviteSomeoneNew(a, 'accept')
  await joinThroughTheLink(b, link, BUDGET, ledgerId, b.account!.name, 'accept')
  const budget = `/family/${ledgerId}`

  // 2. A's expense; B's banner, mark, notification and books.
  await fillExpense(a, budget, '12.00', 'A pays')
  await a.page.getByRole('button', { name: 'Add the expense' }).click()
  await expect(a.page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()
  const recordA = /\/expenses\/(\d+)$/.exec(new URL(a.page.url()).pathname)![1]
  // A is caught up: no banner on A's pages.
  await a.page.goto(budget)
  await expect(a.page.getByRole('heading', { name: 'Latest activity' })).toBeVisible()
  await expect(a.page.getByText(/waiting for you/)).toHaveCount(0)

  await b.page.goto(budget)
  const banner = b.page.locator('.pending-banner')
  await expect(banner).toHaveText('1 change by others is waiting for you Accept')
  await shot(b.page, 'accept-01-banner')
  await b.page.goto(`${budget}/expenses`)
  const mark = b.page.getByRole('row').filter({ hasText: 'Groceries' }).getByText('Waiting for you', { exact: true })
  await expect(mark).toBeVisible()
  await expect(mark).toHaveAttribute('title', /latest version.*own books follow once you accept/)
  await b.page.goto('/notifications')
  const changed = b.page.locator('.notification-list li').filter({ hasText: new RegExp(`${nameA} made (a change|\\d+ changes) in the family budget`) })
  await expect(changed).toHaveCount(1)
  await expect(changed.getByRole('button', { name: 'Accept', exact: true })).toBeVisible()
  await shot(b.page, 'accept-02-notifications')
  expect(await entriesOf(b, recordA), 'B has no entry of A’s record before accepting').toEqual([])

  // 3. B is refused, accepts in the same place, the form keeps what B typed, and B presses the same button again.
  await fillExpense(b, budget, '7.00', 'B pays')
  const add = b.page.getByRole('button', { name: 'Add the expense' })
  await add.click()
  const offer = refusal(b.page)
  await expect(offer).toBeVisible()
  await shot(b.page, 'accept-03-refused')
  await offer.getByRole('button', { name: 'Accept 1 change', exact: true }).click()
  await expect(b.page.getByText('Accepted 1 change. Nothing was saved yet. Press the same button again.')).toBeVisible()
  await expect(b.page.locator('.pending-banner')).toHaveCount(0)
  await expect(b.page).toHaveURL(new RegExp(`${budget}/expenses/new$`))
  await expect(b.page.getByLabel('Amount (EUR)')).toHaveValue('7.00')
  await expect(b.page.getByLabel('Comment (optional)')).toHaveValue('B pays')
  await expect(b.page.getByLabel('Category').locator('option:checked')).toHaveText('Groceries')
  expect(await entriesOf(b, recordA), 'B’s books hold A’s record after accepting').toContain('SHARE')
  await add.click()
  await expect(b.page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()
  const recordB = /\/expenses\/(\d+)$/.exec(new URL(b.page.url()).pathname)![1]

  // 4. A is behind B's expense: refused, accepts, saves nothing; each side's books now show the other's expense.
  expect(await entriesOf(a, recordB), 'A has no entry of B’s record before accepting').toEqual([])
  await fillExpense(a, budget, '3.00', 'A again')
  await a.page.getByRole('button', { name: 'Add the expense' }).click()
  const aOffer: Locator = refusal(a.page)
  await expect(aOffer).toBeVisible()
  await aOffer.getByRole('button', { name: 'Accept 1 change', exact: true }).click()
  await expect(a.page.getByText('Accepted 1 change. Nothing was saved yet. Press the same button again.')).toBeVisible()
  await expect(a.page.getByLabel('Amount (EUR)')).toHaveValue('3.00')
  expect(await entriesOf(a, recordB), 'A’s books hold B’s record after accepting').toContain('SHARE')
  expect(await entriesOf(a, recordA)).toContain('PAYMENT')
  expect(await entriesOf(b, recordB)).toContain('PAYMENT')
  await shot(a.page, 'accept-04-accepted')

  // 5. B leaves, then A, which deletes the budget; Delete all my data for both.
  expectReloadAfterLeaving(watch, ledgerId)
  await leave(b, budget, BUDGET, 'Leave the family budget', null, 'accept')
  await leave(a, budget, BUDGET, 'Leave and delete the family budget', `the family budget “${BUDGET}” and its records will be deleted`, 'accept')
  await deleteAllMyData(a)
  await deleteAllMyData(b)
})
