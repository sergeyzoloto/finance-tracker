import type { Page } from '@playwright/test'
import {
  deleteAllMyData, expect, expectIntegrity, expectReloadAfterLeaving, ledgerIdOf, shot, test, today, type Session,
} from '../fixtures.ts'

// F7's check with two accounts (D-51), A and B each in a browser context of their own, every record dated today and
// the report read for the current month:
// 1. A creates "F7 check" in EUR with equal shares and invites B, who opens the link and joins.
// 2. A pays €100.00; B pays $54.00, entering €50.00 as the amount in EUR; B receives an income of €60.00.
// 3. A is owed €55.00 and B owes €55.00; the month's report: expenses €150.00, incomes €60.00.
// 4. B pays A €55.00; A moves its part from "Specify later" to an account.
// 5. The settlement lock (D-28): B's change of the amount answers 409 naming A; A moves its part back to "Specify
//    later"; B's change goes through, and the settlement is €55.00 again; A moves its part to an account again.
// 6. Both settled, and the integrity check passes for both.
// 7. B leaves, then A, whose confirmation says the budget will be deleted (D-36); Delete all my data for both.

const BUDGET = 'F7 check'

test('family F7', async ({ as, family, target, watch }) => {
  test.skip(family === 'off', 'The family budget is switched off (E2E_FAMILY=off).')
  const a = await as('A')
  const b = await as('B')
  await deleteAllMyData(a)
  await deleteAllMyData(b)
  const nameA = target.roles.A.name
  const nameB = target.roles.B.name

  // 1. The budget, the invite, B joining.
  const ledgerId = await createTheBudget(a)
  const link = await inviteSomeoneNew(a)
  expect(link.startsWith(`${target.app}/invite#`), 'the invite link points to the app').toBe(true)
  await joinThroughTheLink(b, link, ledgerId, nameB)
  const budget = `/family/${ledgerId}`

  // 2. Expenses and an income.
  await addExpense(a, budget, { amount: '100.00', account: 'Current account' })
  await expect(share(a.page, nameA)).toHaveText('€50.00')
  await expect(share(a.page, nameB)).toHaveText('€50.00')
  await addExpense(b, budget, { amount: '54.00', currency: 'USD', inEur: '50.00' })
  await expect(fact(b.page, 'Amount')).toContainText('$54.00')
  await expect(fact(b.page, 'Amount')).toContainText('€50.00')
  await expect(share(b.page, nameA)).toHaveText('€25.00')
  await expect(share(b.page, nameB)).toHaveText('€25.00')
  await addIncome(b, budget, { amount: '60.00', account: 'Current account' })
  await expect(share(b.page, nameA)).toHaveText('€30.00')
  await expect(share(b.page, nameB)).toHaveText('€30.00')

  // 3. The balances and the month's report.
  await expectBalances(a, budget, 'is owed €55.00', `${nameB} owes you €55.00.`)
  await expectBalances(b, budget, 'owes €55.00', `You owe ${nameA} €55.00.`)
  const month = today().slice(0, 7)
  const last = new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0)).toISOString().slice(0, 10)
  await a.page.goto(`${budget}/report?from=${month}-01&to=${last}`)
  const thisMonth = a.page.locator('.report-month').filter({
    has: a.page.getByRole('heading', { name: new Date(`${month}-01T00:00:00Z`).toLocaleString('en-US', { month: 'long', year: 'numeric', timeZone: 'UTC' }) }),
  })
  await expect(thisMonth).toContainText('Expenses €150.00 · Incomes €60.00')
  await shot(a.page, 'f7-08-report')
  await a.page.goto(`${budget}/expenses`)
  await expect(a.page.getByRole('row')).toHaveCount(4)
  await shot(a.page, 'f7-09-activity')
  await a.page.goto(`${budget}/journal`)
  await expect(a.page.getByRole('heading', { name: 'Journal' })).toBeVisible()
  await shot(a.page, 'f7-10-journal')

  // 4. B settles up; A puts its part on an account.
  await b.page.goto(`${budget}/balances`)
  await b.page.getByRole('listitem').filter({ hasText: `You owe ${nameA} €55.00` }).getByRole('link', { name: 'Settle up' }).click()
  await expect(b.page.getByRole('heading', { name: 'Record a settlement' })).toBeVisible()
  await expect(b.page.getByLabel('Amount (EUR)')).toHaveValue('55.00')
  await b.page.getByLabel('Paid from').selectOption({ label: 'Current account' })
  await shot(b.page, 'f7-11-settle-up')
  await b.page.getByRole('button', { name: 'Record the settlement' }).click()
  await expect(b.page.getByRole('heading', { name: /^Settlement, / })).toBeVisible()
  await expect(b.page.locator('p.sentence')).toHaveText(`You paid ${nameA} €55.00.`)
  const settlement = new URL(b.page.url()).pathname
  // B keeps this page open: it was read before A's part went on an account.
  await moveOwnSide(a, settlement, 'Current account')

  // 5. The settlement lock (D-28).
  watch.expect({
    method: 'PATCH', path: new RegExp(`^/api/family-ledgers/${ledgerId}/records/\\d+\\?version=\\d+$`), status: 409,
    reason: "D-28: B changes the settlement's amount while A's part is on an account of A's",
  })
  await b.page.getByLabel('Amount (EUR)').fill('50.00')
  await b.page.getByRole('button', { name: 'Save the changes' }).click()
  await expect(b.page.getByRole('alert').filter({ hasText: `${nameA} has put their side of this settlement on an account of theirs` }))
    .toBeVisible()
  // The record, loaded again after the 409, says the same, and the amount is no longer B's to change.
  await expect(b.page.getByRole('note')).toContainText(`${nameA} has put their side of this settlement on an account of theirs, so its date and amount can’t change`)
  await expect(b.page.getByLabel('Amount (EUR)')).toHaveCount(0)
  await expect(fact(b.page, 'Amount')).toHaveText('€55.00')
  await shot(b.page, 'f7-13-settlement-locked')
  await moveOwnSide(a, settlement, 'Specify later')
  await b.page.reload()
  await saveSettlementAmount(b, '50.00')
  await saveSettlementAmount(b, '55.00')
  await expect(fact(b.page, 'Amount')).toHaveText('€55.00')
  await moveOwnSide(a, settlement, 'Current account')

  // 6. Settled, and nothing differs anywhere.
  await expectBalances(a, budget, 'is settled', 'You are settled.')
  await expectBalances(b, budget, 'is settled', 'You are settled.')
  await expectIntegrity(a)
  await expectIntegrity(b)

  // 7. B leaves, then A, which deletes the budget (D-36).
  expectReloadAfterLeaving(watch, ledgerId)
  await leave(b, budget, 'Leave the family budget', null)
  await leave(a, budget, 'Leave and delete the family budget', `the family budget “${BUDGET}” and its records will be deleted`)
  await expectIntegrity(a)
  await expectIntegrity(b)
  await deleteAllMyData(a)
  await deleteAllMyData(b)
})

async function createTheBudget({ page }: Session): Promise<string> {
  await page.goto('/family/new')
  await page.getByLabel('Name', { exact: true }).fill(BUDGET)
  await expect(page.getByLabel('Base currency')).toHaveValue('EUR')
  await expect(page.getByLabel('Start date')).toHaveValue(today())
  await page.getByRole('checkbox', { name: 'Groceries', exact: true }).check()
  await page.getByRole('checkbox', { name: 'Other income', exact: true }).check()
  await expect(page.getByRole('radio', { name: 'Equal shares' })).toBeChecked()
  await shot(page, 'f7-01-new-budget')
  await page.getByRole('button', { name: 'Create family budget' }).click()
  await expect(page.getByRole('heading', { level: 2, name: BUDGET })).toBeVisible()
  await expect(page.getByText('Family budget · EUR · Owner')).toBeVisible()
  return ledgerIdOf(page)
}

async function inviteSomeoneNew({ page }: Session): Promise<string> {
  await page.getByRole('navigation', { name: 'Family budget' }).getByRole('link', { name: 'Members' }).click()
  await page.getByRole('button', { name: 'Invite someone new' }).click()
  const link = page.getByRole('textbox', { name: 'Invite link' })
  await expect(link).toHaveValue(/\/invite#[A-Za-z0-9_-]{43}$/)
  await shot(page, 'f7-02-members-invite')
  return link.inputValue()
}

async function joinThroughTheLink({ page }: Session, link: string, ledgerId: string, name: string) {
  await page.goto(link)
  await expect(page).toHaveURL(/\/invite$/)
  await expect(page.getByRole('heading', { name: `Join the family budget “${BUDGET}”` })).toBeVisible()
  await expect(page.getByLabel('Your name in this budget')).toHaveValue(name)
  await shot(page, 'f7-03-invite')
  await page.getByRole('button', { name: 'Accept' }).click()
  await expect(page).toHaveURL(new RegExp(`/family/${ledgerId}$`))
  await expect(page.getByText('Family budget · EUR · Member')).toBeVisible()
}

async function addExpense({ page }: Session, budget: string, expense: { amount: string; account?: string; currency?: string; inEur?: string }) {
  await page.goto(`${budget}/expenses/new`)
  await expect(page.getByRole('heading', { name: 'Add an expense' })).toBeVisible()
  await page.getByLabel('Category').selectOption({ label: 'Groceries' })
  await page.getByLabel('Paid from').selectOption({ label: expense.account ?? 'Specify later' })
  if (expense.currency) await page.getByLabel('Currency').fill(expense.currency)
  await page.getByLabel(`Amount (${expense.currency ?? 'EUR'})`).fill(expense.amount)
  if (expense.inEur) {
    // Whatever rate the form suggests, the amount in EUR is the one entered.
    await page.getByLabel('Amount in EUR').fill(expense.inEur)
    await expect(page.getByText('Entered by you; the family budget counts it in EUR.')).toBeVisible()
  }
  await expect(page.getByRole('radio', { name: /^The budget’s rule \(equal shares\)$/ })).toBeChecked()
  await shot(page, `f7-04-expense-${expense.currency ?? 'EUR'}`)
  await page.getByRole('button', { name: 'Add the expense' }).click()
  await expect(page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()
  await shot(page, `f7-05-expense-${expense.currency ?? 'EUR'}-page`)
}

async function addIncome({ page }: Session, budget: string, income: { amount: string; account: string }) {
  await page.goto(`${budget}/incomes/new`)
  await expect(page.getByRole('heading', { name: 'Add an income' })).toBeVisible()
  await page.getByLabel('Category').selectOption({ label: 'Other income' })
  await page.getByLabel('Received into').selectOption({ label: income.account })
  await page.getByLabel('Amount (EUR)').fill(income.amount)
  await page.getByRole('button', { name: 'Add the income' }).click()
  await expect(page.getByRole('heading', { name: /^Other income, / })).toBeVisible()
  await shot(page, 'f7-06-income-page')
}

/** The reader's own row ("You") of the balances, and their balance in words above it. */
async function expectBalances({ page }: Session, budget: string, own: string, sentence: string) {
  await page.goto(`${budget}/balances`)
  await expect(page.getByRole('row').filter({ has: page.locator('.badge', { hasText: 'You' }) })).toContainText(own)
  await expect(page.locator('.settlement .sentence')).toHaveText(sentence)
  await expect(page.getByTestId('balances-sum')).toHaveText('€0.00')
  await shot(page, `f7-07-balances-${own.replace(/[^a-z0-9]+/gi, '-')}`)
}

/** The reader's own side of the settlement, its account or "Specify later", saved from the settlement's page. */
async function moveOwnSide({ page }: Session, settlement: string, to: string) {
  await page.goto(settlement)
  await expect(page.getByRole('heading', { name: 'Your side of this settlement' })).toBeVisible()
  await page.getByLabel('Received into').selectOption({ label: to })
  const saved = page.waitForResponse((r) => r.request().method() === 'PATCH' && /\/records\/\d+/.test(r.url()))
  await page.getByRole('button', { name: 'Save the changes' }).click()
  expect((await saved).status()).toBe(200)
  await expect(page.getByRole('status')).toHaveText('Saved.')
  await expect(fact(page, 'Received into').getByRole('link')).toHaveText(to)
  await shot(page, `f7-12-own-side-${to.replace(/[^a-z0-9]+/gi, '-')}`)
}

/** The recorder changes the settlement's amount, which answers 200 now that the lock is gone. */
async function saveSettlementAmount({ page }: Session, amount: string) {
  await expect(page.getByRole('heading', { name: 'Change this settlement' })).toBeVisible()
  await page.getByLabel('Amount (EUR)').fill(amount)
  const saved = page.waitForResponse((r) => r.request().method() === 'PATCH' && /\/records\/\d+/.test(r.url()))
  await page.getByRole('button', { name: 'Save the changes' }).click()
  expect((await saved).status()).toBe(200)
  await expect(fact(page, 'Amount')).toHaveText(`€${amount}`)
}

async function leave({ page }: Session, budget: string, button: string, deletion: string | null) {
  await page.goto(`${budget}/members`)
  await page.getByRole('button', { name: 'Leave', exact: true }).click()
  const confirmation = page.getByRole('region', { name: 'Leave the family budget' })
  await expect(confirmation.getByRole('heading', { name: `Leave “${BUDGET}”?` })).toBeVisible()
  if (deletion) await expect(confirmation).toContainText(deletion)
  else await expect(confirmation).not.toContainText('will be deleted')
  await shot(page, `f7-14-${button.replace(/[^a-z0-9]+/gi, '-')}`)
  await confirmation.getByRole('button', { name: button }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('combobox', { name: 'Budget' }).getByRole('option', { name: BUDGET })).toHaveCount(0)
}

/** A member's share on a record's page: the row whose header starts with their name (it also says who changed it). */
const share = (page: Page, name: string) => page.locator('table.shares tbody tr')
  .filter({ has: page.locator('th', { hasText: new RegExp(`^${name}`) }) }).locator('td').first()

/** A fact of a record's page: the value after its term ("Amount", "Received into"). */
const fact = (page: Page, term: string) => page.locator(`dl.facts dt:text-is("${term}") + dd`)
