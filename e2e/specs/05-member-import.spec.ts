import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { claimThroughTheLink, inviteToTakeThePlace } from '../family-steps.ts'
import { deleteAllMyData, expect, expectIntegrity, ledgerIdOf, shot, test } from '../fixtures.ts'

// D3c with A and B, on the synthetic export of backend/src/test/resources/import/d3c/ (invented names and amounts, the
// owner's layout for both, D-177), never a real one:
// 1. A creates "Member import check" from 1 January 2024 with Sam, a place without an account, and imports A's files
//    with Sam's family rows (D-151), as D3b-run did.
// 2. A invites someone to take Sam's place from 1 February 2024, a past join date (D-174, D-183); B opens the link, sees
//    the date and the 15 records the claim attaches, and takes the place (D-182).
// 3. B's import of B's own files (D-175), without the two rows that are refused (D-178, D-179): the dry run attaches
//    B's payment side to the 4 records of Sam's rows and B's side to 7 settlements and the conversion, each tier of
//    D-178, and reconciles; its commit; the same files again change nothing.
// 4. A's own import: no partner's file any more, nothing to change. The integrity checks pass. Delete all my data for A
//    keeps the budget, B's (D-186); B's then takes it (D-36), so the run leaves nothing behind, in production too.

const BUDGET = 'Member import check'
const D3C = fileURLToPath(new URL('../../backend/src/test/resources/import/d3c/', import.meta.url))
const file = (name: string) => `${D3C}${name}`

/** B's transactions without the two rows the import refuses (D-178's 111, D-179's 112). */
function bsTransactions() {
  const lines = readFileSync(file('partner-transactions.csv'), 'utf8').split('\r\n')
  const kept = lines.filter((line) => !/^(111|112);/.test(line))
  return { name: 'transactions.csv', mimeType: 'text/csv', buffer: Buffer.from(kept.join('\r\n'), 'utf8') }
}

test('member import', async ({ as, family }) => {
  test.skip(family === 'off', 'The family budget is switched off (E2E_FAMILY=off).')
  const a = await as('A')
  const b = await as('B')
  await deleteAllMyData(a)
  await deleteAllMyData(b)

  // 1. The budget, Sam's place, and A's first import with Sam's rows.
  await a.page.goto('/family/new')
  await a.page.getByLabel('Name', { exact: true }).fill(BUDGET)
  await a.page.getByLabel('Start date').fill('2024-01-01')
  await a.page.getByLabel('Name of a member').fill('Sam')
  await a.page.getByRole('button', { name: 'Add member' }).click()
  await a.page.getByRole('button', { name: 'Create family budget' }).click()
  await expect(a.page.getByRole('heading', { level: 2, name: BUDGET })).toBeVisible()
  const ledgerId = ledgerIdOf(a.page)

  await a.page.goto('/import')
  await a.page.locator('input[name="accounts"]').setInputFiles(file('owner-accounts.csv'))
  await a.page.locator('input[name="categories"]').setInputFiles(file('categories.csv'))
  await a.page.locator('input[name="transactions"]').setInputFiles(file('owner-transactions.csv'))
  await a.page.locator('input[name="dailyBalance"]').setInputFiles(file('owner-daily-balance.csv'))
  await a.page.locator('input[name="dailyChange"]').setInputFiles(file('owner-daily-change.csv'))
  await a.page.getByLabel('Family budget').selectOption({ label: BUDGET })
  await a.page.getByLabel('The partner’s place').selectOption({ label: 'Sam' })
  await a.page.locator('input[name="partnerTransactions"]').setInputFiles(file('partner-transactions.csv'))
  await expect(a.page.getByText('4 of his 16 rows are family rows and will be uploaded.')).toBeVisible()
  const verdict = (page: typeof a.page) => page.locator('section.report > [role="status"]')
  await a.page.getByRole('button', { name: 'Check (dry run)' }).click()
  await expect(verdict(a.page)).toHaveText(/^Dry run: nothing was saved\. No errors:/, { timeout: 60_000 })
  a.page.once('dialog', (dialog) => void dialog.accept())
  await a.page.getByRole('button', { name: 'Commit import' }).click()
  await expect(verdict(a.page)).toHaveText(/^Imported: \d+ created, 0 updated\./, { timeout: 60_000 })
  await shot(a.page, 'member-import-01-owner')

  // 2. The place from 1 February 2024, taken by B.
  await a.page.goto(`/family/${ledgerId}`)
  const link = await inviteToTakeThePlace(a, 'Sam', '2024-02-01', 'member-import-02')
  await claimThroughTheLink(b, link, BUDGET, ledgerId, 'Feb 1, 2024', 15, 'Sam B', 'member-import-03')

  // 3. B's own files: no place to choose and no partner's file; the dry run, its commit, the same again.
  await b.page.goto('/import')
  await b.page.locator('input[name="accounts"]').setInputFiles(file('partner-accounts.csv'))
  await b.page.locator('input[name="categories"]').setInputFiles(file('categories.csv'))
  await b.page.locator('input[name="transactions"]').setInputFiles(bsTransactions())
  await b.page.locator('input[name="dailyBalance"]').setInputFiles(file('partner-daily-balance.csv'))
  await b.page.locator('input[name="dailyChange"]').setInputFiles(file('partner-daily-change.csv'))
  await b.page.getByLabel('Family budget').selectOption({ label: BUDGET })
  await expect(b.page.getByTestId('member-import')).toBeVisible()
  await expect(b.page.getByLabel('The partner’s place')).toHaveCount(0)
  await expect(b.page.locator('input[name="partnerTransactions"]')).toHaveCount(0)
  await b.page.getByRole('button', { name: 'Check (dry run)' }).click()
  await expect(verdict(b.page)).toHaveText(/^Dry run: nothing was saved\. No errors:/, { timeout: 60_000 })
  const summary = b.page.getByTestId('member-summary')
  const line = (term: string) => summary.locator('tr').filter({ has: b.page.locator('td', { hasText: term }) })
    .locator('td.amount')
  await expect(line('Family records as imported before')).toHaveText('4')
  await expect(line('Your payment side attached')).toHaveText('4')
  await expect(line('Settlements matched: same date and amount')).toHaveText('3')
  await expect(line('Settlements matched: several on the date')).toHaveText('1')
  await expect(line('Settlements matched: within 7 days')).toHaveText('2')
  await expect(line('Conversions matched')).toHaveText('1 of 1')
  await expect(summary).toContainText('none differs')
  await expect(b.page.locator('.reconciliation [role="status"]')).toHaveText(/^Passed:/)
  await shot(b.page, 'member-import-04-dry-run')

  b.page.once('dialog', (dialog) => void dialog.accept())
  await b.page.getByRole('button', { name: 'Commit import' }).click()
  await expect(verdict(b.page)).toHaveText(/^Imported: /, { timeout: 60_000 })
  await expect(b.page.locator('.reconciliation [role="status"]')).toHaveText(/^Passed:/)
  await b.page.getByRole('button', { name: 'Check (dry run)' }).click()
  await expect(verdict(b.page)).toHaveText(
    'Dry run: nothing was saved. No errors: committing would create 0 and update 0.', { timeout: 60_000 })
  await shot(b.page, 'member-import-05-again')

  // 4. A's own files only, now: nothing to change.
  await a.page.goto('/import')
  await a.page.locator('input[name="accounts"]').setInputFiles(file('owner-accounts.csv'))
  await a.page.locator('input[name="categories"]').setInputFiles(file('categories.csv'))
  await a.page.locator('input[name="transactions"]').setInputFiles(file('owner-transactions.csv'))
  await a.page.locator('input[name="dailyBalance"]').setInputFiles(file('owner-daily-balance.csv'))
  await a.page.locator('input[name="dailyChange"]').setInputFiles(file('owner-daily-change.csv'))
  await a.page.getByLabel('Family budget').selectOption({ label: BUDGET })
  await a.page.getByLabel('The partner’s place').selectOption({ label: 'Sam B' })
  await expect(a.page.getByTestId('own-file')).toBeVisible()
  await a.page.getByRole('button', { name: 'Check (dry run)' }).click()
  await expect(verdict(a.page)).toHaveText(
    'Dry run: nothing was saved. No errors: committing would create 0 and update 0.', { timeout: 60_000 })
  await expect(a.page.locator('.reconciliation [role="status"]')).toHaveText(/^Passed:/)

  await expectIntegrity(a)
  await expectIntegrity(b)
  // D-186: A's deletion keeps the budget, whose records B's ledger posts; B's takes it (D-36).
  await deleteAllMyData(a)
  await b.page.goto(`/family/${ledgerId}`)
  await expect(b.page.getByRole('heading', { level: 2, name: BUDGET })).toBeVisible()
  await deleteAllMyData(b)
})
