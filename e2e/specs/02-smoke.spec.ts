import {
  deleteAllMyData, expect, expectIntegrity, expectReloadAfterLeaving, ledgerIdOf, shot, test, wholeNumber, type Session,
  type Watch,
} from '../fixtures.ts'

// The deploy checklists' smoke test, with account A: signed in, the switch as E2E_FAMILY says, an expense of the
// personal budget created, changed and deleted, the demo (with "Demo household" and Sam when the family budget is on:
// its balance and report, then left, which deletes it, D-36), the integrity check; Delete all my data at both ends.

test('smoke', async ({ as, family, watch }) => {
  const a = await as('A')
  const { page, context } = a
  await deleteAllMyData(a)

  // Signed in; the family switch as expected (as() checked /api/me's familyLedgers too).
  const me = await (await context.request.get('/api/me')).json() as { features: { familyLedgers: boolean } }
  expect(me.features.familyLedgers).toBe(family === 'on')
  await expect(page.getByRole('combobox', { name: 'Budget' })).toHaveCount(family === 'on' ? 1 : 0)
  await expect(page.getByText('Your ledger is empty. How would you like to start?')).toBeVisible()
  await shot(page, 'smoke-01-empty-dashboard')

  await anExpenseCreatedChangedAndDeleted(a)
  if (family === 'on') await theDemoWithItsFamilyBudget(a, watch)
  else await theDemoAlone(a)

  await expectIntegrity(a)
  await deleteAllMyData(a)
})

async function anExpenseCreatedChangedAndDeleted({ page }: Session) {
  await page.goto('/entries/new')
  await expect(page.getByRole('tab', { name: 'Expense' })).toHaveAttribute('aria-selected', 'true')
  await page.getByLabel('Paid from').selectOption({ label: 'Current account' })
  await page.getByLabel('Amount', { exact: true }).fill('12.34')
  await page.getByLabel('Category').selectOption({ label: 'Groceries' })
  await page.getByLabel('Memo').fill('E2E smoke')
  await shot(page, 'smoke-02-new-expense')
  await page.getByRole('button', { name: 'Save', exact: true }).click()

  await expect(page).toHaveURL(/\/entries$/)
  const row = page.getByRole('row').filter({ hasText: 'E2E smoke' })
  await expect(row).toHaveCount(1)
  await expect(row).toContainText('Groceries')
  await expect(row).toContainText(wholeNumber('12.34'))
  await shot(page, 'smoke-03-entries')

  await row.getByRole('link').click()
  await expect(page.getByRole('heading', { name: 'Edit entry' })).toBeVisible()
  await expect(page.getByLabel('Amount', { exact: true })).toHaveValue('12.34')
  await page.getByLabel('Amount', { exact: true }).fill('23.45')
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page).toHaveURL(/\/entries$/)
  await expect(row).toContainText(wholeNumber('23.45'))
  await expect(row).not.toContainText(wholeNumber('12.34'))

  await row.getByRole('link').click()
  page.once('dialog', (dialog) => void dialog.accept())
  await page.getByRole('button', { name: 'Delete' }).click()
  await expect(page).toHaveURL(/\/entries$/)
  await expect(page.getByText('No entries yet.')).toBeVisible()
}

async function loadTheDemo({ page }: Session, withFamily: boolean) {
  await page.goto('/')
  await expect(page.getByText('It also creates the family budget “Demo household”')).toHaveCount(withFamily ? 1 : 0)
  await page.getByRole('button', { name: 'Load demo data' }).click()
  await expect(page.getByText('Your ledger is empty. How would you like to start?')).toHaveCount(0, { timeout: 30_000 })
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()
  await expect(page.getByRole('heading', { name: /^Net worth on / })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Cash flow by category' })).toBeVisible()
  // The chart is a chunk of its own, loaded lazily (chunkReload.lazyWithReload): it loads, and draws.
  await expect(page.getByText('Loading chart…')).toHaveCount(0)
  await expect(page.locator('.recharts-surface').first()).toBeVisible()
  await shot(page, 'smoke-04-demo-dashboard')
}

async function theDemoAlone(session: Session) {
  await loadTheDemo(session, false)
}

async function theDemoWithItsFamilyBudget(session: Session, watch: Watch) {
  const { page } = session
  await loadTheDemo(session, true)

  const switcher = page.getByRole('combobox', { name: 'Budget' })
  await expect(switcher.getByRole('option', { name: 'Demo household' })).toHaveCount(1)
  await switcher.selectOption({ label: 'Demo household' })
  await expect(page.getByRole('heading', { level: 2, name: 'Demo household' })).toBeVisible()
  const subnav = page.getByRole('navigation', { name: 'Family budget' })
  await expect(page.getByText('Latest activity')).toBeVisible()
  await shot(page, 'smoke-05-family-overview')

  await subnav.getByRole('link', { name: 'Members' }).click()
  await expect(page.getByRole('row').filter({ hasText: 'Sam' })).toHaveCount(1)
  await shot(page, 'smoke-06-family-members')

  await subnav.getByRole('link', { name: 'Balances' }).click()
  await expect(page.getByRole('heading', { name: 'Balances' })).toBeVisible()
  // The reader's balance in words, then everyone's: in each currency together they are zero (D-45; the demo's
  // weekend is in dollars).
  await expect(page.locator('.settlement .sentence').first()).toContainText(/Sam|settled/)
  await expect(page.getByRole('row').filter({ hasText: 'Sam' })).toContainText(/owes|is owed|is settled/)
  await expect(page.getByTestId('balances-sum-EUR')).toHaveText('€0.00')
  await expect(page.getByTestId('balances-sum-USD')).toHaveText('$0.00')
  await shot(page, 'smoke-07-family-balances')

  await subnav.getByRole('link', { name: 'Report' }).click()
  await expect(page.getByRole('heading', { name: 'Report' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'By member' })).toBeVisible()
  await expect(page.locator('.report-month').first()).toContainText('Expenses €')
  await shot(page, 'smoke-08-family-report')

  // The demo family is removed as the screens allow: its only member with an account leaves, which deletes it.
  await subnav.getByRole('link', { name: 'Members' }).click()
  expectReloadAfterLeaving(watch, ledgerIdOf(page))
  await page.getByRole('button', { name: 'Leave', exact: true }).click()
  const confirmation = page.getByRole('region', { name: 'Leave the family budget' })
  await expect(confirmation).toContainText('the family budget “Demo household” and its records will be deleted')
  await shot(page, 'smoke-09-leave-and-delete')
  await confirmation.getByRole('button', { name: 'Leave and delete the family budget' }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(switcher.getByRole('option', { name: 'Demo household' })).toHaveCount(0)
}
