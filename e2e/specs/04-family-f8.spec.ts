import { deleteAllMyData, expect, expectIntegrity, expectReloadAfterLeaving, shot, test, today, type Session } from '../fixtures.ts'
import { createTheBudget, fact, inviteSomeoneNew, joinThroughTheLink, leave, money, share } from '../family-steps.ts'

// F8's check with two accounts (D-45 to D-47, D-49, D-87 to D-89), every record dated today:
// 1. A creates "F8 check" in EUR with equal shares and invites B, who joins.
// 2. Records in three currencies: A pays €90.00 from an account in euros; B pays $40.00 ("Specify later"); A pays
//    RUB 3,000.00 from the same euro account, naming the €32.10 that went from it (D-89, through A's FX_EXCHANGE).
// 3. The paying side is A's alone (D-88): A's page of the rouble record names the euros, B's names only the record.
// 4. Balances per currency: B owes A €45.00 and RUB 1,500.00, A owes B $20.00. The total in EUR: none for A while
//    nobody has a RUB rate, "No RUB rate" with its link to the rates page.
// 5. A enters "1 EUR = 95,50 RUB" there; A's total appears, "≈", labelled manual, with each rate's date and source;
//    B, who has no rate of their own, still sees "No RUB rate" (D-49: each member's own rates).
// 6. The month's report per currency, with the total for A.
// 7. Settle up per currency (D-46): B pays A €45.00 and RUB 1,500.00, A pays B $20.00, all "Specify later".
// 8. Everyone settled in every currency; the integrity check passes for both.
// 9. B leaves, then A, which deletes the budget (D-36); Delete all my data for both, which takes A's manual rate.

const BUDGET = 'F8 check'

test('family F8', async ({ as, family, target, watch }) => {
  test.skip(family === 'off', 'The family budget is switched off (E2E_FAMILY=off).')
  const a = await as('A')
  const b = await as('B')
  await deleteAllMyData(a)
  await deleteAllMyData(b)
  const nameA = target.roles.A.name
  const nameB = target.roles.B.name

  // 1. The budget, the invite, B joining.
  const ledgerId = await createTheBudget(a, BUDGET, 'f8')
  const link = await inviteSomeoneNew(a, 'f8')
  await joinThroughTheLink(b, link, BUDGET, ledgerId, nameB, 'f8')
  const budget = `/family/${ledgerId}`

  // 2. Records in euros, dollars and roubles.
  await addExpense(a, budget, { amount: '90.00', currency: 'EUR', account: 'Current account' })
  await expect(share(a.page, nameA)).toHaveText(money('45', 'EUR'))
  await addExpense(b, budget, { amount: '40.00', currency: 'USD', account: 'Specify later' })
  await expect(fact(b.page, 'Amount')).toHaveText(money('40', 'USD'))
  await expect(share(b.page, nameB)).toHaveText(money('20', 'USD'))
  await addExpense(a, budget, { amount: '3000.00', currency: 'RUB', account: 'Current account', paid: '32.10' })
  await expect(fact(a.page, 'Amount')).toHaveText(money('3000', 'RUB'))
  await expect(share(a.page, nameB)).toHaveText(money('1500', 'RUB'))
  const roubles = new URL(a.page.url()).pathname

  // 3. The paying side is A's alone (D-88).
  await expect(fact(a.page, 'Paid from')).toContainText(`Current account, ${money('32.10', 'EUR')}`)
  await b.page.goto(roubles)
  await expect(fact(b.page, 'Amount')).toHaveText(money('3000', 'RUB'))
  await expect(fact(b.page, 'Paid from')).toHaveCount(0)
  await expect(b.page.locator('main')).not.toContainText('32.10')
  await shot(b.page, 'f8-04-rouble-record-for-b')
  await b.page.goto(`${budget}/journal`)
  await expect(b.page.getByRole('heading', { name: 'Journal' })).toBeVisible()
  await expect(b.page.locator('main')).not.toContainText('32.10')

  // 4. Balances per currency, and no total without a RUB rate.
  // The main currency first, then the others alphabetically.
  await expectBalances(a, budget, [`${nameB} owes you ${money('45', 'EUR')}.`, `${nameB} owes you ${money('1500', 'RUB')}.`,
    `You owe ${nameB} ${money('20', 'USD')}.`])
  const totalA = a.page.locator('.balances-table tbody tr').filter({ has: a.page.locator('.badge', { hasText: 'You' }) })
    .locator('.no-rate, .total-amount')
  await expect(totalA).toHaveText('No RUB rate: enter a rate')
  await shot(a.page, 'f8-05-balances-no-rub-rate')

  // 5. A's own RUB rate, entered as "1 EUR = 95,50 RUB".
  await totalA.getByRole('link', { name: 'enter a rate' }).click()
  await expect(a.page).toHaveURL(/\/rates$/)
  await a.page.getByLabel('Rate', { exact: true }).fill('95,50')
  await a.page.getByLabel('Currency', { exact: true }).fill('RUB')
  await a.page.getByRole('button', { name: 'Save rate' }).click()
  await expect(a.page.getByTestId('rate-RUB')).toContainText('95.5')
  await expect(a.page.getByTestId('rate-RUB')).toContainText('manual')
  await shot(a.page, 'f8-06-rates')
  await a.page.goto(`${budget}/balances`)
  await expect(totalA).toHaveText(/^≈ -?€[\d,]+\.\d\dmanual$/)
  await expect(a.page.locator('.rate-notes')).toContainText(`1 EUR = 95.5 RUB, manual rate of ${new Date(`${today()}T00:00:00Z`)
    .toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' })}`)
  await expect(a.page.locator('.rate-notes')).toContainText(/USD, ECB rate of /)
  await shot(a.page, 'f8-07-balances-total')
  await b.page.goto(`${budget}/balances`)
  await expect(b.page.locator('.balances-table tbody tr').filter({ has: b.page.locator('.badge', { hasText: 'You' }) })
    .locator('.no-rate')).toHaveText('No RUB rate: enter a rate')

  // 6. The month's report, per currency, with A's total.
  const month = today().slice(0, 7)
  const last = new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0)).toISOString().slice(0, 10)
  await a.page.goto(`${budget}/report?from=${month}-01&to=${last}`)
  for (const [currency, amount] of [['EUR', '90'], ['USD', '40'], ['RUB', '3000']]) {
    await expect(a.page.getByRole('heading', { name: `In ${currency}` })).toBeVisible()
    await expect(a.page.locator('.report-month').filter({ hasText: `· ${currency}` }))
      .toContainText(`Expenses ${money(amount, currency)}`)
  }
  await expect(a.page.locator('.report-totals')).toContainText(/Together in EUR: .*≈ €[\d,]+\.\d\dmanual/)
  await shot(a.page, 'f8-08-report')

  // 7. Settle up in each currency (D-46).
  await settleUp(b, budget, `You owe ${nameA} ${money('45', 'EUR')}`, 'EUR', '45.00')
  await settleUp(b, budget, `You owe ${nameA} ${money('1500', 'RUB')}`, 'RUB', '1500.00')
  await settleUp(a, budget, `You owe ${nameB} ${money('20', 'USD')}`, 'USD', '20.00')

  // 8. Settled, and nothing differs anywhere.
  await expectBalances(a, budget, ['You are settled.'])
  await expectBalances(b, budget, ['You are settled.'])
  await expectIntegrity(a)
  await expectIntegrity(b)

  // 9. B leaves, then A, which deletes the budget (D-36); Delete all my data takes A's manual rate with it (D-20).
  expectReloadAfterLeaving(watch, ledgerId)
  await leave(b, budget, BUDGET, 'Leave the family budget', null, 'f8')
  await leave(a, budget, BUDGET, 'Leave and delete the family budget', `the family budget “${BUDGET}” and its records will be deleted`, 'f8')
  await expectIntegrity(a)
  await expectIntegrity(b)
  await deleteAllMyData(a)
  await deleteAllMyData(b)
  const rates = await a.context.request.get('/api/rates/manual')
  expect(rates.status()).toBe(200)
  expect(await rates.json(), "A's manual rates after Delete all my data").toEqual([])
})

/**
 * A family expense of today in Groceries, in its own currency (D-45), paid from the account named or "Specify later";
 * `paid` is what went from an account whose currency is another than the record's (D-89).
 */
async function addExpense({ page }: Session, budget: string, expense: { amount: string; currency: string; account: string; paid?: string }) {
  await page.goto(`${budget}/expenses/new`)
  await expect(page.getByRole('heading', { name: 'Add an expense' })).toBeVisible()
  await page.getByLabel('Category').selectOption({ label: 'Groceries' })
  await page.getByLabel(/^Currency/).fill(expense.currency)
  await page.getByLabel(`Amount (${expense.currency})`).fill(expense.amount)
  await page.getByLabel('Paid from').selectOption({ label: expense.account })
  if (expense.paid) {
    await expect(page.getByLabel(/^Paid in/)).toHaveValue('EUR')
    await page.getByLabel(/^Amount paid in EUR/).fill(expense.paid)
  } else if (expense.account !== 'Specify later') {
    await expect(page.getByLabel(/^Amount paid in/)).toHaveCount(0)
  }
  await shot(page, `f8-03-expense-${expense.currency}`)
  await page.getByRole('button', { name: 'Add the expense' }).click()
  await expect(page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()
}

/** The reader's balance in words, one line per member and currency, and every currency's balances adding up to zero. */
async function expectBalances({ page }: Session, budget: string, sentences: string[]) {
  await page.goto(`${budget}/balances`)
  await expect(page.locator('.settlement .sentence')).toHaveText(sentences)
  for (const currency of ['EUR', 'USD', 'RUB']) {
    await expect(page.getByTestId(`balances-sum-${currency}`)).toHaveText(money('0', currency))
  }
}

/** "Settle up" on the reader's debt in one currency, from "Specify later". */
async function settleUp({ page }: Session, budget: string, debt: string, currency: string, amount: string) {
  await page.goto(`${budget}/balances`)
  await page.getByRole('listitem').filter({ hasText: debt }).getByRole('link', { name: 'Settle up' }).click()
  await expect(page.getByRole('heading', { name: 'Record a settlement' })).toBeVisible()
  await expect(page.getByLabel(`Amount (${currency})`)).toHaveValue(amount)
  await page.getByLabel('Paid from').selectOption({ label: 'Specify later' })
  await shot(page, `f8-09-settle-up-${currency}`)
  await page.getByRole('button', { name: 'Record the settlement' }).click()
  await expect(page.getByRole('heading', { name: /^Settlement, / })).toBeVisible()
}
