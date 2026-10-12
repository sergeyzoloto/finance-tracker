import { fileURLToPath } from 'node:url'
import { deleteAllMyData, expect, expectIntegrity, ledgerIdOf, shot, test } from '../fixtures.ts'

// D3b's import with A alone, on the synthetic export of backend/src/test/resources/import/ (invented names and amounts,
// the real export's format), never a real one:
// 1. A creates the family budget "Import check" from 1 January 2024 with Sam, a member without an account (D-141).
// 2. The import page: A's five files, the budget and Sam's place, and the partner's transactions file, which the page
//    filters in the browser: 7 of his 9 rows are family rows and leave it (D-140).
// 3. A dry run: no error, 33 rows to create, and the balances reconcile with the daily balance file (D-63 to D-69,
//    D-146, D-147); then the commit; then the same files again: nothing to create or update (D-139).
// 4. The budget holds Sam's records; the integrity check passes. Delete all my data takes the budget with it (D-36), so
//    the run leaves nothing behind, in production too.

const BUDGET = 'Import check'
const FIXTURES = fileURLToPath(new URL('../../backend/src/test/resources/import/', import.meta.url))
const file = (name: string) => `${FIXTURES}${name}`

test('import', async ({ as, family }) => {
  test.skip(family === 'off', 'The family budget is switched off (E2E_FAMILY=off).')
  const a = await as('A')
  await deleteAllMyData(a)
  const { page } = a

  // 1. The budget from 1 January 2024, and Sam's place without an account.
  await page.goto('/family/new')
  await page.getByLabel('Name', { exact: true }).fill(BUDGET)
  await page.getByLabel('Start date').fill('2024-01-01')
  await page.getByLabel('Name of a member').fill('Sam')
  await page.getByRole('button', { name: 'Add member' }).click()
  await page.getByRole('button', { name: 'Create family budget' }).click()
  await expect(page.getByRole('heading', { level: 2, name: BUDGET })).toBeVisible()
  const ledgerId = ledgerIdOf(page)

  // 2. The files, the budget, the place, and the partner's file filtered here.
  await page.goto('/import')
  await expect(page.getByRole('heading', { name: 'Import', exact: true })).toBeVisible()
  await page.locator('input[name="accounts"]').setInputFiles(file('accounts.csv'))
  await page.locator('input[name="categories"]').setInputFiles(file('categories.csv'))
  await page.locator('input[name="transactions"]').setInputFiles(file('transactions.csv'))
  await page.locator('input[name="dailyBalance"]').setInputFiles(file('daily-balance.csv'))
  await page.locator('input[name="dailyChange"]').setInputFiles(file('daily-change.csv'))
  await page.getByLabel('Family budget').selectOption({ label: BUDGET })
  await page.getByLabel('The partner’s place').selectOption({ label: 'Sam' })
  await page.locator('input[name="partnerTransactions"]').setInputFiles(file('partner-transactions.csv'))
  await expect(page.getByText('7 of his 9 rows are family rows and will be uploaded.')).toBeVisible()
  await shot(page, 'import-01-files')

  // 3. The dry run, the commit, and the same files again.
  const sent = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/import')
  await page.getByRole('button', { name: 'Check (dry run)' }).click()
  // The upload holds the partner's filtered rows only: none of his comments, shops or personal rows.
  const body = (await sent).postDataBuffer()?.toString('utf8') ?? ''
  expect(body).not.toContain('his note')
  expect(body).not.toContain('Кафе')
  expect(body).not.toContain('Восток')
  // The report's own verdict (the filter's line above the buttons is a status too).
  const verdict = page.locator('section.report > [role="status"]')
  await expect(verdict).toHaveText('Dry run: nothing was saved. No errors: committing would create 33 and update 0.',
    { timeout: 60_000 })
  await expect(page.locator('.reconciliation [role="status"]')).toHaveText(/^Passed:/)
  await shot(page, 'import-02-dry-run')

  page.once('dialog', (dialog) => void dialog.accept())
  await page.getByRole('button', { name: 'Commit import' }).click()
  await expect(verdict).toHaveText(/^Imported: 33 created, 0 updated\./, { timeout: 60_000 })
  await shot(page, 'import-03-committed')

  await page.getByRole('button', { name: 'Check (dry run)' }).click()
  await expect(verdict).toHaveText('Dry run: nothing was saved. No errors: committing would create 0 and update 0.',
    { timeout: 60_000 })

  // 4. Sam's records in the budget; the ledger adds up.
  await page.goto(`/family/${ledgerId}/expenses`)
  await expect(page.getByRole('row').filter({ hasText: 'Транспорт' }).first()).toBeVisible()
  await expectIntegrity(a)
  await deleteAllMyData(a)
})
