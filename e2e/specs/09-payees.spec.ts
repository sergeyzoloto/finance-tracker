import type { Page } from '@playwright/test'
import { deleteAllMyData, expect, expectReloadAfterLeaving, shot, test, wholeNumber } from '../fixtures.ts'
import { createTheBudget, fact, inviteSomeoneNew, joinThroughTheLink, leave } from '../family-steps.ts'

// UX-9's check with two accounts (D-477 to D-482), the family budget switched on:
// 1. A creates "UX-9 check" and invites B, who joins.
// 2. A records a family expense of €10.00 on New entry, with a payee typed for the first time; B sees no payee on
//    Activity or on the record's page.
// 3. A switches "Show the other members where I paid" on in the budget's settings and accepts the confirmation: B sees
//    the payee on Activity ("Paid by A · <payee>") and on the record ("Shown by A.").
// 4. A dismisses the confirmation to switch it off: still on, and B still sees it; A switches off and accepts: B
//    doesn't.
//    Since UX-11 (D-523), in steps 2 to 4 B's Entries also shows the payee on B's share while A shows payees, and "—"
//    otherwise; the text search finds the share by it, then doesn't.
// 5. A's Entries shows the payment with its record's category, the kind and the budget under it (D-477).
// 6. At 375 px (local-narrow): the Settings section and that Entries row, with nothing scrolling sideways.
// 7. B leaves, then A, which deletes the budget; Delete all my data for both.

const BUDGET = 'UX-9 check'
const PAYEE = 'Odido UX9'
const LABEL = 'Show the other members where I paid'

test('payees a member chooses to show', async ({ as, family, target, watch }) => {
  test.skip(family === 'off', 'The family budget is switched off (E2E_FAMILY=off).')
  const a = await as('A')
  const b = await as('B')
  const narrow = a.page.viewportSize()?.width === 375
  await deleteAllMyData(a)
  await deleteAllMyData(b)
  const nameA = target.roles.A.name

  // 1. The budget, the invite, B joining.
  const ledgerId = await createTheBudget(a, BUDGET, 'ux9')
  const link = await inviteSomeoneNew(a, 'ux9')
  await joinThroughTheLink(b, link, BUDGET, ledgerId, target.roles.B.name, 'ux9')
  const budget = `/family/${ledgerId}`

  // 2. A's family expense with a payee; B sees none.
  await a.page.goto('/entries/new')
  await a.page.getByRole('switch', { name: 'Family expense' }).check()
  await expect(a.page.getByLabel(/^Payee \(only you see it\)/)).toBeVisible()
  await a.page.getByLabel(/^Family category/).selectOption({ label: 'Groceries' })
  await a.page.getByLabel(/^Paid from/).selectOption({ label: 'Current account' })
  await a.page.getByLabel('Amount', { exact: true }).fill('10.00')
  await a.page.getByLabel(/^Payee/).fill(PAYEE)
  await a.page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(a.page).toHaveURL(/\/entries$/)

  await a.page.goto(`${budget}/expenses`)
  const aRow = a.page.getByRole('row').filter({ hasText: wholeNumber('10.00') })
  await expect(aRow).toContainText(`Paid by you · ${PAYEE}`)
  await aRow.getByRole('link', { name: 'Groceries' }).click()
  await expect(a.page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()
  await expect(fact(a.page, 'Payee')).toContainText('Only you see it.')
  const record = new URL(a.page.url()).pathname
  await bSees(b.page, budget, record, null)

  // 3. Switched on after the confirmation: B sees the payee in both places.
  await a.page.goto(`${budget}/settings`)
  await expect(a.page.getByRole('heading', { name: 'Your payees' })).toBeVisible()
  const box = a.page.getByRole('checkbox', { name: LABEL })
  await expect(box).not.toBeChecked()
  expect(await answer(a.page, true, () => box.click())).toBe(`Show your payees to the other members of “${BUDGET}”? `
    + 'They will see where you paid for every family expense and income you paid or received in this budget, past ones included.')
  await expect(a.page.getByText('Saved.')).toBeVisible()
  await expect(box).toBeChecked()
  await expect(a.page.getByRole('listitem').filter({ hasText: `${nameA} (you)` })).toHaveText(`${nameA} (you): Shown`)
  await shot(a.page, 'ux9-01-settings-shown')
  await expectNoSidewaysScroll(a.page, `${budget}/settings`, narrow)
  await bSees(b.page, budget, record, nameA)
  await a.page.goto(record)
  await expect(fact(a.page, 'Payee')).toContainText('The other members see it.')

  // 4. Dismissed: still on, and B still sees it; accepted: off, and B doesn't.
  await a.page.goto(`${budget}/settings`)
  await expect(box).toBeChecked()
  expect(await answer(a.page, false, () => box.click())).toBe(`Hide your payees from the other members of “${BUDGET}”? `
    + 'They will no longer see them, past records included.')
  await expect(box).toBeChecked()
  await bSees(b.page, budget, record, nameA)
  await a.page.goto(`${budget}/settings`)
  await answer(a.page, true, () => box.click())
  await expect(a.page.getByText('Saved.')).toBeVisible()
  await expect(box).not.toBeChecked()
  await bSees(b.page, budget, record, null)

  // 5. A's Entries: the payment with its record's category, not muted, and the kind and the budget under it.
  await a.page.goto('/entries')
  const payment = a.page.locator('tr').filter({ hasText: wholeNumber('10.00') }).filter({ hasText: 'Family payment' })
  await expect(payment).toHaveCount(1)
  await expect(payment.locator('.category-name')).toHaveText('Groceries')
  await expect(payment.locator('.category-name .muted')).toHaveCount(0)
  // The kind, then the shared icon (its title is text too) and the budget's name (D-442).
  await expect(payment.locator('.family-line')).toHaveText(`Family payment · Shared: ${BUDGET}${BUDGET}`)
  await expect(payment.locator('.family-line').getByRole('img', { name: `Shared: ${BUDGET}` })).toBeVisible()
  await shot(a.page, 'ux9-02-entries')
  await expectNoSidewaysScroll(a.page, '/entries', narrow)
  // The family category finds the payment and the share (D-478).
  await a.page.goto('/categories')
  await a.page.getByRole('row').filter({ hasText: BUDGET }).getByRole('link', { name: 'Groceries', exact: true }).click()
  await expect(a.page).toHaveURL(/\/entries\?categoryId=\d+$/)
  await expect(a.page.locator('tr').filter({ hasText: 'Family payment' })).toHaveCount(1)
  await expect(a.page.locator('tr').filter({ hasText: 'Family share' })).toHaveCount(1)

  // 7. B leaves, then A, which deletes the budget; Delete all my data for both.
  expectReloadAfterLeaving(watch, ledgerId)
  await leave(b, budget, BUDGET, 'Leave the family budget', null, 'ux9')
  await leave(a, budget, BUDGET, 'Leave and delete the family budget', `the family budget “${BUDGET}” and its records will be deleted`, 'ux9')
  await deleteAllMyData(a)
  await deleteAllMyData(b)
})

/** B's Activity row and record page: the payee shown by `by`, or no payee at all. */
async function bSees(page: Page, budget: string, record: string, by: string | null) {
  await page.goto(`${budget}/expenses`)
  const row = page.getByRole('row').filter({ hasText: wholeNumber('10.00') })
  await expect(row).toHaveCount(1)
  if (by) await expect(row).toContainText(`Paid by ${by} · ${PAYEE}`)
  else await expect(row).not.toContainText(PAYEE)
  await page.goto(record)
  await expect(page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()
  if (by) {
    await expect(fact(page, 'Payee')).toHaveText(`${PAYEE}Shown by ${by}.`)
  } else {
    await expect(fact(page, 'Payee')).toHaveCount(0)
    await expect(page.locator('main')).not.toContainText(PAYEE)
  }
  await bsShare(page, by !== null)
}

/**
 * UX-11 (D-523): B's share on Entries names its record's payee in the Payee cell only while it is shown, "—" otherwise,
 * and the text search finds the share by it only then.
 */
async function bsShare(page: Page, shown: boolean) {
  const rows = page.locator('table.entry-list > tbody:not(.entry-summary) > tr')
  await page.goto('/entries')
  const share = rows.filter({ hasText: 'Family share' })
  await expect(share).toHaveCount(1)
  await expect(share.locator('td.payee')).toHaveText(shown ? PAYEE : '—')
  await page.goto('/entries?q=Odido')
  if (shown) {
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText('Family share')
  } else {
    await expect(page.getByText('No entries match these filters.')).toBeVisible()
  }
}

/** Clicks, answers the confirmation that follows, and gives its question. */
async function answer(page: Page, ok: boolean, click: () => Promise<void>): Promise<string> {
  const asked = new Promise<string>((resolve) => page.once('dialog', (dialog) => {
    resolve(dialog.message())
    void (ok ? dialog.accept() : dialog.dismiss())
  }))
  await click()
  return asked
}

/** At 375 px, the page is no wider than the window. */
async function expectNoSidewaysScroll(page: Page, what: string, narrow: boolean) {
  if (!narrow) return
  const [scroll, client] = await page.evaluate(() => [document.documentElement.scrollWidth, document.documentElement.clientWidth])
  expect(scroll, `${what}: no sideways scroll at 375 px`).toBeLessThanOrEqual(client)
}

