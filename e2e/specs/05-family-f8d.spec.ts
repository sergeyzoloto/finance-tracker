import { deleteAllMyData, expect, expectIntegrity, expectReloadAfterLeaving, shot, test, today, type Session } from '../fixtures.ts'
import { createTheBudget, fact, inviteSomeoneNew, joinThroughTheLink, leave, money, share } from '../family-steps.ts'

// F8d's check with two accounts (D-79 to D-81), every record dated today, in euros, equal shares:
// 1. A creates "F8d check" and invites B, who joins.
// 2. A's payee (D-81): A marks a new personal expense as a family expense and types a payee for the first time ("Corner
//    shop", created with it) and, in another, "The bank". The payee is on A's own payment entry and A's page of the
//    record; B's page of the same record has neither the payee nor its name anywhere.
// 3. A refund (D-79): A's "Refund" toggle on the expense form gives "Add a refund"; a refund of €10.00 of Groceries
//    shows with its minus on the record's page, in the list and in the journal, and B's share of it is -€5.00.
// 4. An account kept per counterparty (D-80): A pays €30.00 from "Debts to creditors", which asks for its counterparty
//    ("The bank"); B never sees the account or the counterparty.
// 5. Balances: B owes A €33.00, which both read in words; the report's Groceries total of the month is €66.00 (the
//    expenses less the refund); the integrity check passes for both.
// 6. B leaves, then A, which deletes the budget (D-36); Delete all my data for both.

const BUDGET = 'F8d check'

test('family F8d', async ({ as, family, target, watch }) => {
  test.skip(family === 'off', 'The family budget is switched off (E2E_FAMILY=off).')
  const a = await as('A')
  const b = await as('B')
  await deleteAllMyData(a)
  await deleteAllMyData(b)
  const nameA = target.roles.A.name
  const nameB = target.roles.B.name

  // 1. The budget, the invite, B joining.
  const ledgerId = await createTheBudget(a, BUDGET, 'f8d')
  const link = await inviteSomeoneNew(a, 'f8d')
  await joinThroughTheLink(b, link, BUDGET, ledgerId, nameB, 'f8d')
  const budget = `/family/${ledgerId}`

  // 2. The payee, typed for the first time on a personal entry marked as a family expense.
  await personalFamilyExpense(a, { amount: '40.00', payee: 'Corner shop', account: 'Current account' })
  await personalFamilyExpense(a, { amount: '6.00', payee: 'The bank', account: 'Cash' })
  await a.page.goto(`${budget}/expenses`)
  await a.page.getByRole('row').filter({ hasText: '€40.00' }).getByRole('link', { name: 'Groceries' }).click()
  await expect(a.page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()
  await expect(fact(a.page, 'Payee')).toContainText('Corner shop')
  await expect(fact(a.page, 'Payee')).toContainText('Only you see it')
  await shot(a.page, 'f8d-04-record-with-payee')
  const shop = new URL(a.page.url()).pathname
  // B's page of the same record: the amount, the category and the shares, never the payee (D-81).
  await b.page.goto(shop)
  await expect(fact(b.page, 'Amount')).toHaveText(money('40', 'EUR'))
  await expect(share(b.page, nameB)).toHaveText(money('20', 'EUR'))
  await expect(fact(b.page, 'Payee')).toHaveCount(0)
  await expect(fact(b.page, 'Paid from')).toHaveCount(0)
  await expect(b.page.locator('main')).not.toContainText('Corner shop')
  await shot(b.page, 'f8d-05-record-for-b')
  // A's own payment entry has it as its payee.
  await a.page.goto('/entries')
  await expect(a.page.getByRole('row').filter({ hasText: '€40.00' })).toContainText('Corner shop')

  // 3. A refund of €10.00: the same category, a minus, and each member getting their share back.
  await a.page.goto(`${budget}/expenses/new`)
  await expect(a.page.getByRole('heading', { name: 'Add an expense' })).toBeVisible()
  await a.page.getByRole('checkbox', { name: /^Refund/ }).check()
  await expect(a.page.getByRole('heading', { name: 'Add a refund' })).toBeVisible()
  await a.page.getByLabel('Category').selectOption({ label: 'Groceries' })
  await a.page.getByLabel('Amount (EUR)').fill('10.00')
  await a.page.getByLabel('Received into').selectOption({ label: 'Current account' })
  await a.page.getByLabel(/^Payee/).selectOption({ label: 'Corner shop' })
  await shot(a.page, 'f8d-06-new-refund')
  await a.page.getByRole('button', { name: 'Add the refund' }).click()
  await expect(a.page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()
  await expect(a.page.locator('.badge', { hasText: 'Refund' })).toBeVisible()
  await expect(fact(a.page, 'Amount')).toHaveText(`-${money('10', 'EUR')}`)
  await expect(fact(a.page, 'Received by')).toContainText(nameA)
  await expect(share(a.page, nameA)).toHaveText(`-${money('5', 'EUR')}`)
  await expect(share(a.page, nameB)).toHaveText(`-${money('5', 'EUR')}`)
  await expect(fact(a.page, 'Payee')).toContainText('Corner shop')
  await shot(a.page, 'f8d-07-refund')
  const refund = new URL(a.page.url()).pathname
  await b.page.goto(refund)
  await expect(b.page.locator('.badge', { hasText: 'Refund' })).toBeVisible()
  await expect(share(b.page, nameB)).toHaveText(`-${money('5', 'EUR')}`)
  await expect(b.page.locator('main')).not.toContainText('Corner shop')
  // The list and the journal show the minus; the journal never names the payee.
  await b.page.goto(`${budget}/expenses`)
  await expect(b.page.getByRole('row').filter({ hasText: 'Groceries (refund)' })).toContainText(`-${money('10', 'EUR')}`)
  await expect(b.page.getByRole('row').filter({ hasText: 'Groceries (refund)' })).toContainText(`Yours -${money('5', 'EUR')}`)
  await b.page.goto(`${budget}/journal`)
  await expect(b.page.getByText(`added the refund Groceries`).first()).toContainText(`-${money('10', 'EUR')}`)
  await expect(b.page.locator('main')).not.toContainText('Corner shop')
  await shot(b.page, 'f8d-08-journal-for-b')

  // 4. An account kept per counterparty, with its counterparty.
  await a.page.goto(`${budget}/expenses/new`)
  await a.page.getByLabel('Category').selectOption({ label: 'Groceries' })
  await a.page.getByLabel('Amount (EUR)').fill('30.00')
  await a.page.getByLabel('Paid from').selectOption({ label: 'Debts to creditors' })
  await expect(a.page.getByRole('button', { name: 'Add the expense' })).toBeDisabled()
  await a.page.getByLabel(/^Counterparty/).selectOption({ label: 'The bank' })
  await shot(a.page, 'f8d-09-credit-expense')
  await a.page.getByRole('button', { name: 'Add the expense' }).click()
  await expect(a.page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()
  await expect(fact(a.page, 'Paid from')).toContainText('Debts to creditors, with The bank')
  const credit = new URL(a.page.url()).pathname
  await b.page.goto(credit)
  await expect(fact(b.page, 'Amount')).toHaveText(money('30', 'EUR'))
  await expect(fact(b.page, 'Paid from')).toHaveCount(0)
  await expect(b.page.locator('main')).not.toContainText('Debts to creditors')
  await expect(b.page.locator('main')).not.toContainText('The bank')
  // A's liability, kept per counterparty, shows the bank: €30.00 owed.
  const owed = await a.context.request.get('/api/reports/counterparty-balances?accountCode=CREDITOR_DEBT')
  expect(owed.status()).toBe(200)
  expect(await owed.json()).toEqual([expect.objectContaining({ counterpartyName: 'The bank', currency: 'EUR', balance: '30.00' })])

  // 5. The balances, the report and the integrity check.
  for (const [session, sentence] of [[a, `${nameB} owes you ${money('33', 'EUR')}.`], [b, `You owe ${nameA} ${money('33', 'EUR')}.`]] as const) {
    await session.page.goto(`${budget}/balances`)
    await expect(session.page.locator('.settlement .sentence')).toHaveText([sentence])
    await expect(session.page.getByTestId('balances-sum-EUR')).toHaveText(money('0', 'EUR'))
  }
  await shot(b.page, 'f8d-10-balances')
  const month = today().slice(0, 7)
  const last = new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0)).toISOString().slice(0, 10)
  await a.page.goto(`${budget}/report?from=${month}-01&to=${last}`)
  // One currency: the month's block has no currency in its heading.
  await expect(a.page.locator('.report-month')).toHaveCount(1)
  await expect(a.page.locator('.report-month')).toContainText(`Expenses ${money('66', 'EUR')}`)
  await shot(a.page, 'f8d-11-report')
  await expectIntegrity(a)
  await expectIntegrity(b)

  // 6. B leaves, then A, which deletes the budget (D-36); Delete all my data for both.
  expectReloadAfterLeaving(watch, ledgerId)
  await leave(b, budget, BUDGET, 'Leave the family budget', null, 'f8d')
  await leave(a, budget, BUDGET, 'Leave and delete the family budget', `the family budget “${BUDGET}” and its records will be deleted`, 'f8d')
  await expectIntegrity(a)
  await expectIntegrity(b)
  await deleteAllMyData(a)
  await deleteAllMyData(b)
})

/**
 * A new personal expense of Groceries, marked as a family expense of the budget (C2), with a payee typed for the first
 * time (D-81): it is created with it, and is the payee of the user's own payment.
 */
async function personalFamilyExpense({ page }: Session, expense: { amount: string; payee: string; account: string }) {
  await page.goto('/entries/new')
  await expect(page.getByRole('tab', { name: 'Expense' })).toHaveAttribute('aria-selected', 'true')
  await page.getByRole('switch', { name: 'Family expense' }).check()
  await page.getByLabel(/^Family category/).selectOption({ label: 'Groceries' })
  await page.getByLabel(/^Paid from/).selectOption({ label: expense.account })
  await page.getByLabel('Amount', { exact: true }).fill(expense.amount)
  await page.getByLabel(/^Payee/).fill(expense.payee)
  await expect(page.getByText('New payee: it is added when you save.')).toBeVisible()
  await expect(page.getByTestId(/^share-/).first()).toBeVisible()
  await shot(page, `f8d-02-personal-family-expense`)
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page).toHaveURL(/\/entries$/)
}
