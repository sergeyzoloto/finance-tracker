import { readFileSync } from 'node:fs'
import { deleteAllMyData, expect, shot, test, today, type Session } from '../fixtures.ts'
import { HEADER, parse } from '../export-steps.ts'

// D4a's export page (/export, D-243), with account A: the summary of the whole period and of this month, and the files
// that Download saves, named for the period (D-241), as the import reads them (D-240). Locally, on the demo's ledger
// that this spec loads first and deletes at the end; in production read only, on whatever A's ledger holds (the specs
// before it delete their data at the end): the page and the file, nothing written. The export's own checks against the
// balances run in the smoke test and in family F8, on the ledgers they write (export-steps.ts).

test('export page', async ({ as, target }) => {
  const a = await as('A')
  const { page } = a
  const local = target.name === 'local'
  if (local) {
    await deleteAllMyData(a)
    await page.goto('/')
    await page.getByRole('button', { name: 'Load demo data' }).click()
    await expect(page.getByText('Your ledger is empty. How would you like to start?')).toHaveCount(0, { timeout: 30_000 })
    await expect(page.getByRole('heading', { name: /^Net worth on / })).toBeVisible()
  }

  await page.goto('/')
  await page.getByRole('navigation').getByRole('link', { name: 'Export' }).click()
  await expect(page).toHaveURL(/\/export$/)
  await expect(page.getByRole('heading', { name: 'Export' })).toBeVisible()
  const summary = page.getByTestId('export-summary')
  await expect(summary).toBeVisible()
  await shot(page, 'export-01-whole-period')
  const whole = await download(a)
  if (local) {
    expect(whole.rows.length, 'the demo has rows').toBeGreaterThan(0)
    await expect(summary).toContainText(`${whole.rows.length.toLocaleString('en-US')} rows`)
  } else if (whole.rows.length === 0) {
    await expect(summary).toHaveText('No rows in this period.')
    expect(whole.name).toBe('Transactions.csv')
  }

  // This month, both days included.
  const month = today().slice(0, 7)
  const last = new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0)).toISOString().slice(0, 10)
  await page.getByLabel('From–to').check()
  await page.getByLabel('From', { exact: true }).fill(`${month}-01`)
  await page.getByLabel('To', { exact: true }).fill(last)
  await expect(page.getByRole('link', { name: 'Download' })).toHaveAttribute('href', `/api/export/transactions?from=${month}-01&to=${last}`)
  await shot(page, 'export-02-this-month')
  const part = await download(a)
  expect(part.name).toBe(`Transactions_${month}-01_${last}.csv`)
  const wholeById = new Map(whole.rows.map((row) => [row[0], row.join(';')]))
  for (const row of part.rows) expect(wholeById.get(row[0]), `row ${row[0]} as in the whole period`).toBe(row.join(';'))
  if (local) expect(part.rows.length, 'the demo has rows this month').toBeGreaterThan(0)

  if (local) await deleteAllMyData(a)
})

/** Download's file, as the page saves it: its name, its header and its rows. */
async function download({ page }: Session) {
  const saved = page.waitForEvent('download')
  await page.getByRole('link', { name: 'Download' }).click()
  const file = await saved
  const bytes = readFileSync((await file.path())!)
  expect([...bytes.subarray(0, 3)], 'UTF-8 with a byte order mark').toEqual([0xef, 0xbb, 0xbf])
  const { values, crlf } = parse(bytes.subarray(3).toString('utf8'))
  expect(crlf, 'every line ends in CRLF').toBe(true)
  expect(values[0]).toEqual(HEADER)
  return { name: file.suggestedFilename(), rows: values.slice(1) }
}
