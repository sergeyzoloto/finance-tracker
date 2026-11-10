import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { fillDate, shownDay } from '../dates.ts'
import { deleteAllMyData, expect, expectIntegrity, ledgerIdOf, shot, test } from '../fixtures.ts'

// D3b's import with A alone, on the synthetic transactions files of backend/src/test/resources/import/ (invented names
// and amounts, the real export's format with D-235's 32 columns), never a real one:
// 1. A creates the family budget "Import check" from 1 January 2024 with Sam, a member without an account (D-141).
// 2. The import page: A's one transactions file (D4b), the budget and Sam's place, and the partner's transactions file,
//    which the page filters in the browser: 7 of his 9 rows are family rows and leave it (D-140). A's file is counted
//    as soon as it is chosen, as the import counts it: 25 rows, the highest ID 25, the last row of 2024-03-05 (D-221,
//    D-257).
// 3. D-336 first: A's file with row 21's Sum changed by hand, its computed columns left as they were: the dry run's
//    check fails and Commit stays off, with the line saying why. Then the right file.
// 4. A dry run "up to" 2024-02-29 (D-165): no error, 28 rows to create, one row of each file after the date, and the
//    ledger checks out against the file up to it (D-231, D-255, D-256); its commit; then the date cleared: a dry run
//    creates the 2 rows after it, whose commit completes the import, and the same files again create and update
//    nothing (D-139). The report's header names a check or a commit, and once the files are committed, Commit stays
//    off for them, also after another check (D3e).
// 5. The budget holds Sam's records; the integrity check passes. Delete all my data takes the budget with it (D-36), so
//    the run leaves nothing behind, in production too.

const BUDGET = 'Import check'
const FIXTURES = fileURLToPath(new URL('../../backend/src/test/resources/import/', import.meta.url))
const file = (name: string) => `${FIXTURES}${name}`

/** The transactions file with row 21's Sum typed over by hand, as ImportFixtures.withField does (D-336). */
function withSumChangedByHand(): Buffer {
  const lines = readFileSync(file('transactions.csv'), 'utf8').split('\r\n')
  const changed = lines.map((line) => {
    const fields = line.split(';')
    if (fields[0] !== '21') return line
    fields[3] = '2.5'
    return fields.join(';')
  })
  return Buffer.from(changed.join('\r\n'), 'utf8')
}

test('import', async ({ as, family }) => {
  test.skip(family === 'off', 'The family budget is switched off (E2E_FAMILY=off).')
  const a = await as('A')
  await deleteAllMyData(a)
  const { page } = a

  // 1. The budget from 1 January 2024, and Sam's place without an account.
  await page.goto('/family/new')
  await page.getByLabel('Name', { exact: true }).fill(BUDGET)
  await fillDate(page.getByLabel('Start date'), '2024-01-01')
  await page.getByLabel('Name of a member').fill('Sam')
  await page.getByRole('button', { name: 'Add member' }).click()
  await page.getByRole('button', { name: 'Create family budget' }).click()
  await expect(page.getByRole('heading', { level: 2, name: BUDGET })).toBeVisible()
  const ledgerId = ledgerIdOf(page)

  // 2. The file, the budget, the place, and the partner's file filtered here.
  await page.goto('/settings/import')
  await expect(page.getByRole('heading', { name: 'Import', exact: true })).toBeVisible()
  await page.locator('input[name="transactions"]').setInputFiles(file('transactions.csv'))
  await expect(page.getByTestId('file-rows')).toHaveText('Your transactions file: 25 rows, counted as the import '
    + `counts them; highest ID 25; last row dated ${shownDay('2024-03-05', null)}.`)
  await page.getByLabel('Family budget').selectOption({ label: BUDGET })
  await page.getByLabel('The partner’s place').selectOption({ label: 'Sam' })
  await page.locator('input[name="partnerTransactions"]').setInputFiles(file('partner-transactions.csv'))
  await expect(page.getByText('7 of his 9 rows are family rows and will be uploaded.')).toBeVisible()
  await shot(page, 'import-01-files')
  // The report's own verdict (the filter's line above the buttons is a status too).
  const verdict = page.locator('section.report > [role="status"]')
  const commit = page.getByRole('button', { name: 'Commit import' })

  // 3. A row's Sum changed by hand: the check fails, Commit stays off (D-336).
  await page.locator('input[name="transactions"]').setInputFiles({ name: 'transactions.csv', mimeType: 'text/csv',
    buffer: withSumChangedByHand() })
  await expect(page.getByText('7 of his 9 rows are family rows and will be uploaded.')).toBeVisible()
  await page.getByRole('button', { name: 'Check (dry run)' }).click()
  await expect(verdict).toHaveText('Dry run: nothing was saved. No errors, but the check against your file fails; a '
    + 'commit would save nothing.', { timeout: 60_000 })
  await expect(page.locator('.reconciliation [role="alert"]')).toHaveText(/^Failed:/)
  await expect(page.getByTestId('check-fails')).toHaveText('The check against your file doesn’t pass, so nothing can be '
    + 'committed. Fix what it lists, in Excel or in the app, export again and check again.')
  await expect(commit).toBeDisabled()
  await shot(page, 'import-01b-check-fails')
  // The right file.
  await page.locator('input[name="transactions"]').setInputFiles(file('transactions.csv'))
  await expect(page.getByText('7 of his 9 rows are family rows and will be uploaded.')).toBeVisible()
  await expect(page.getByTestId('check-fails')).toHaveCount(0)

  // 4. The dry run up to a date, its commit, the rest, and the same files again.
  const upTo = page.getByLabel(/^Import up to/)
  await fillDate(upTo, '2024-02-29')
  const sent = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/import')
  await page.getByRole('button', { name: 'Check (dry run)' }).click()
  // The upload holds the partner's filtered rows only: none of his comments, shops or personal rows.
  const body = (await sent).postDataBuffer()?.toString('utf8') ?? ''
  expect(body).not.toContain('his note')
  expect(body).not.toContain('Кафе')
  expect(body).not.toContain('Восток')
  await expect(verdict).toHaveText('Dry run: nothing was saved. No errors: committing would create 28 and update 0.',
    { timeout: 60_000 })
  await expect(commit).toBeEnabled()
  await expect(page.getByTestId('report-kind')).toHaveText('Check (dry run): nothing was saved')
  await expect(page.getByTestId('after-cutoff')).toHaveText(`Imported up to ${shownDay('2024-02-29', null)}. After it, neither imported `
    + 'nor missing: 1 row of 25 of yours, 1 row of 7 of the partner’s.')
  await expect(page.locator('.reconciliation [role="status"]')).toHaveText(/^Passed:/)
  await shot(page, 'import-02-dry-run')

  page.once('dialog', (dialog) => void dialog.accept())
  await page.getByRole('button', { name: 'Commit import' }).click()
  await expect(verdict).toHaveText(/^Imported: 28 created, 0 updated\./, { timeout: 60_000 })
  await expect(page.getByTestId('report-kind')).toHaveText('Commit: the import was saved')
  await shot(page, 'import-03-committed')

  // A new date asks for a check again before a commit.
  await fillDate(upTo, '')
  await expect(page.getByRole('button', { name: 'Commit import' })).toBeDisabled()
  await page.getByRole('button', { name: 'Check (dry run)' }).click()
  await expect(verdict).toHaveText('Dry run: nothing was saved. No errors: committing would create 2 and update 0.',
    { timeout: 60_000 })
  await expect(page.locator('.reconciliation [role="status"]')).toHaveText(/^Passed:/)
  page.once('dialog', (dialog) => void dialog.accept())
  await page.getByRole('button', { name: 'Commit import' }).click()
  await expect(verdict).toHaveText(/^Imported: 2 created, 0 updated\./, { timeout: 60_000 })

  await page.getByRole('button', { name: 'Check (dry run)' }).click()
  await expect(verdict).toHaveText('Dry run: nothing was saved. No errors: committing would create 0 and update 0.',
    { timeout: 60_000 })
  // These files are committed: no Commit for them again.
  await expect(page.getByRole('button', { name: 'Commit import' })).toBeDisabled()
  await expect(page.getByTestId('committed-already')).toBeVisible()

  // 5. Sam's records in the budget; the ledger adds up.
  await page.goto(`/family/${ledgerId}/expenses`)
  await expect(page.getByRole('row').filter({ hasText: 'Транспорт' }).first()).toBeVisible()
  await expectIntegrity(a)
  await deleteAllMyData(a)
})
