import { expect, type Session } from './fixtures.ts'

// D4a's export, read only (D-235 to D-243): an account's transactions file and its summary, through the API of its
// session, checked as the import reads the file and against the balances report; and D4b's round trip, the file
// imported back as a dry run, which rolls everything back (D-238, D-256, D-259). Nothing here writes.

export const HEADER = ['ID', 'Date', 'Exp. Item', 'Sum', 'Currency', 'Counteragent', 'Comment', 'Debit', 'Credit',
  'Family', 'CUR_DEBIT', 'SUM_DEBIT', 'CUR_CREDIT', 'SUM_CREDIT', 'LOAN_CA', 'CODE_ITEM', 'TYPE', 'CODE_DEBIT',
  'CODE_CREDIT', 'TYPE_DEBIT', 'TYPE_CREDIT', 'COUNT_DEBIT', 'COUNT_CREDIT', 'DEBIT_CHANGE', 'CREDIT_CHANGE',
  'CUR_FAMILY', 'FAMILY_EXP', 'FAMILY_INC', 'CHECK_CORR', 'LOAN_SUM', 'LOAN_CUR', 'EXP_ITEM_CODE']

/** The columns that hold a number: as stored, a point, no thousands separators, no exponent, no trailing zeros. */
const NUMBERS = ['Sum', 'SUM_DEBIT', 'SUM_CREDIT', 'DEBIT_CHANGE', 'CREDIT_CHANGE', 'FAMILY_EXP', 'FAMILY_INC',
  'CHECK_CORR', 'LOAN_SUM'].map((name) => HEADER.indexOf(name))

export interface Summary {
  from: string | null
  to: string | null
  first: string | null
  last: string | null
  rows: number
  skipped: { kind: string; count: number }[]
  fileName: string
  check: {
    ok: boolean
    accounts: { account: string; currency: string; exported: string; others: string; skipped: string; ok: boolean }[]
  }
}

export interface Exported {
  /** The data lines, the header left out, by their values. */
  rows: string[][]
  summary: Summary
  /** The file as downloaded. */
  bytes: Buffer
}

/** The period's query, or none for the whole period. */
const query = (period?: { from: string; to: string }) => period ? `?from=${period.from}&to=${period.to}` : ''

/**
 * The account's file of the period (the whole period without one) and its summary, with the file's format checked:
 * the byte order mark, CRLF, the header, 32 values a line, the dates and the numbers (D-240), and its name (D-241).
 */
export async function exported({ context, account }: Session, period?: { from: string; to: string }): Promise<Exported> {
  const what = `${account?.label}'s export${period ? ` of ${period.from} to ${period.to}` : ''}`
  const file = await context.request.get(`/api/export/transactions${query(period)}`)
  expect(file.status(), what).toBe(200)
  const answer = await context.request.get(`/api/export/summary${query(period)}`)
  expect(answer.status(), `${what}: the summary`).toBe(200)
  const summary = await answer.json() as Summary
  expect(file.headers()['content-type'], what).toBe('text/csv;charset=UTF-8')
  expect(file.headers()['content-disposition'], what).toBe(`attachment; filename="${summary.fileName}"`)

  const bytes = await file.body()
  expect([...bytes.subarray(0, 3)], `${what}: UTF-8's byte order mark`).toEqual([0xef, 0xbb, 0xbf])
  const text = bytes.subarray(3).toString('utf8')
  const lines = parse(text)
  expect(lines.crlf, `${what}: every line ends in CRLF`).toBe(true)
  expect(lines.values[0], `${what}: the header`).toEqual(HEADER)
  const rows = lines.values.slice(1)
  expect(rows.length, `${what}: the summary's rows`).toBe(summary.rows)
  for (const row of rows) {
    expect(row.length, `${what}: row ${row[0]}'s columns`).toBe(HEADER.length)
    expect(/^\d\d\.\d\d\.\d{4}$/.test(row[1]), `${what}: row ${row[0]}'s date`).toBe(true)
    for (const column of NUMBERS) {
      if (row[column] !== '') {
        expect(/^-?\d+(\.\d*[1-9])?$/.test(row[column]), `${what}: row ${row[0]}'s ${HEADER[column]}`).toBe(true)
      }
    }
  }
  expect(new Set(rows.map((row) => row[0])).size, `${what}: one row per ID`).toBe(rows.length)
  if (period) {
    for (const row of rows) {
      const [day, month, year] = row[1].split('.')
      const date = `${year}-${month}-${day}`
      expect(date >= period.from && date <= period.to, `${what}: row ${row[0]} dated in the period`).toBe(true)
    }
  }
  return { rows, summary, bytes }
}

/**
 * D-242 against the balances report: for every account and currency, its balance at the period's end less the day
 * before it starts is what the exported rows, the other members' records and the skipped records move.
 */
export async function expectTheBalancesMove(session: Session, exportedFile: Exported, period?: { from: string; to: string }) {
  const { summary } = exportedFile
  const what = `${session.account?.label}'s export${period ? ` of ${period.from} to ${period.to}` : ''}`
  expect(summary.check.ok, `${what}: D-242's check`).toBe(true)
  const end = period?.to ?? summary.last
  if (!end) return
  const after = await balances(session, end)
  const before = period ? await balances(session, dayBefore(period.from)) : new Map<string, number>()
  for (const account of summary.check.accounts) {
    const key = `${account.account} ${account.currency}`
    const change = (after.get(key) ?? 0) - (before.get(key) ?? 0)
    const parts = Number(account.exported) + Number(account.others) + Number(account.skipped)
    expect(Math.abs(change - parts), `${what}: ${key} moves by its rows, the others' and the skipped`).toBeLessThan(1e-6)
  }
}

/** The export of the whole period and of the month, each checked (D-240 to D-242): read only. */
export async function expectExport(session: Session, month: { from: string; to: string }) {
  const whole = await exported(session)
  await expectTheBalancesMove(session, whole)
  const part = await exported(session, month)
  await expectTheBalancesMove(session, part, month)
  // The month's rows are the whole period's rows of the month, as they are.
  const byId = new Map(whole.rows.map((row) => [row[0], row.join(';')]))
  for (const row of part.rows) expect(byId.get(row[0]), `${session.account?.label}'s row ${row[0]}`).toBe(row.join(';'))
  await expectRoundTrip(session, whole)
  await expectRoundTrip(session, part, month)
  return whole
}

interface ImportReport {
  outcome: string
  errors: unknown[]
  totals: { created: number; updated: number; conflicts: number; missing: number }
  sync: { appRowsUnchanged: number; appRows: unknown[] }
  reconciliation: { passed: boolean; rows: { failed: number } } | null
}

/**
 * D4b's round trip: the account's own file imported back as a dry run, with no family budget named (its family rows
 * are the app's, compared, never written: D-259), creates, updates and conflicts with nothing, misses nothing, and
 * the check against the file passes (D-238, D-256). A dry run rolls everything back.
 */
export async function expectRoundTrip(session: Session, file: Exported, period?: { from: string; to: string }) {
  const { context, account } = session
  const what = `${account?.label}'s export${period ? ` of ${period.from} to ${period.to}` : ''} imported back`
  // A period without rows has nothing to bring back or to check.
  if (file.rows.length === 0) return
  const cookie = (await context.cookies()).find((c) => c.name === 'XSRF-TOKEN')
  const answer = await context.request.post('/api/import?dryRun=true', {
    headers: { 'X-XSRF-TOKEN': decodeURIComponent(cookie?.value ?? '') },
    multipart: { transactions: { name: file.summary.fileName, mimeType: 'text/csv', buffer: file.bytes } },
  })
  expect(answer.status(), what).toBe(200)
  const report = await answer.json() as ImportReport
  expect(report.outcome, what).toBe('DRY_RUN')
  expect(report.errors, `${what}: errors`).toEqual([])
  expect({ created: report.totals.created, updated: report.totals.updated, conflicts: report.totals.conflicts,
    missing: report.totals.missing }, `${what}: 0 created, 0 updated, 0 conflicts, 0 missing`)
    .toEqual({ created: 0, updated: 0, conflicts: 0, missing: 0 })
  expect(report.sync.appRows, `${what}: every row the app made as the ledger holds it`).toEqual([])
  expect(report.sync.appRowsUnchanged, `${what}: the app's rows`).toBe(file.rows.filter((r) => r[0].startsWith('app-')).length)
  expect(report.reconciliation?.rows.failed, `${what}: rows that fail the check`).toBe(0)
  expect(report.reconciliation?.passed, `${what}: the check against the file`).toBe(true)
}

/** The rows of family records in a file: the app's (`app-f…`) and those with the Family flag. */
export const familyRows = (file: Exported) => file.rows.filter((row) => row[0].startsWith('app-f') || row[9] === 'да')

async function balances({ context }: Session, asOf: string): Promise<Map<string, number>> {
  const answer = await context.request.get(`/api/reports/balances?asOf=${asOf}`)
  expect(answer.status()).toBe(200)
  const rows = await answer.json() as { accountCode: string; currency: string; balance: string }[]
  return new Map(rows.map((b) => [`${b.accountCode} ${b.currency}`, Number(b.balance)]))
}

function dayBefore(date: string) {
  const day = new Date(`${date}T00:00:00Z`)
  day.setUTCDate(day.getUTCDate() - 1)
  return day.toISOString().slice(0, 10)
}

/**
 * The file's lines and their values, separated by semicolons, a value quoted when it holds a semicolon, a quote or a
 * line break (its quotes doubled); and whether every line ends in CRLF, outside quotes.
 */
export function parse(text: string): { values: string[][]; crlf: boolean } {
  const values: string[][] = []
  let row: string[] = []
  let value = ''
  let quoted = false
  let crlf = text.endsWith('\r\n')
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { value += '"'; i++ } else if (c === '"') quoted = false
      else value += c
    } else if (c === '"') {
      quoted = true
    } else if (c === ';') {
      row.push(value); value = ''
    } else if (c === '\r' || c === '\n') {
      if (c === '\n' || text[i + 1] !== '\n') crlf = false
      if (c === '\r') i++
      row.push(value); values.push(row); row = []; value = ''
    } else {
      value += c
    }
  }
  if (value !== '' || row.length > 0) { row.push(value); values.push(row) }
  return { values, crlf }
}
