import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { FamilyLedger, FamilyMember, ImportReport } from './api'
import Import from './Import'
import { parseCsv, PARTNER_COLUMNS, PARTNER_FILE_NAME, TRANSACTIONS_COLUMNS } from './importFilter'

// The import page (D3b, D4b): the user's one transactions file, the family budget and the partner's seat (D-141), the
// partner's file filtered in the browser before the upload (D-140), and Commit only after a dry run without errors.

const home: FamilyLedger = { id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 71,
  createdAt: '2026-10-01T10:00:00Z', startDate: '2017-11-01' }
const members: FamilyMember[] = [
  { id: 71, displayName: 'Owner', role: 'OWNER', status: 'ACTIVE', joinDate: '2017-11-01', hasAccount: true, share: null },
  { id: 72, displayName: 'Sam', role: 'MEMBER', status: 'ACTIVE', joinDate: '2017-11-01', hasAccount: false, share: null },
]
/** The owner's own file (D-235): one row on UNALLOCATED, whose name the partner's filter takes from it (D-260). */
const OWN = `${TRANSACTIONS_COLUMNS.join(';')}\r\n1;02.01.2024;Оклад;1000;EUR;;;Евро-счёт;Свободный остаток;;;;;;;`
  + 'PAYCHECK;INCOME;EURO_ACCOUNT;UNALLOCATED;ASSET;LIABILITIES;EURO_ACCOUNT_EUR;UNALLOCATED_EUR;1000;1000;EUR;0;0;0;0;;'
  + 'PAYCHECK\r\n'
const PARTNER = 'ID;Date;Exp. Item;Sum;Currency;Counteragent;Comment;Debit;Credit;Family;CUR_DEBIT;SUM_DEBIT;CUR_CREDIT;SUM_CREDIT;LOAN_CA\r\n'
  + '101;01.02.2024;Продукты;40.02;EUR;Лавка;his note;Свободный остаток;Карта;да;;;;;\r\n'
  + '106;06.02.2024;Продукты;12;EUR;Кафе;private;Свободный остаток;Карта;;;;;;\r\n'

function report(errors: number, outcome: ImportReport['outcome'] = 'DRY_RUN'): ImportReport {
  return {
    commit: outcome !== 'DRY_RUN', outcome, files: [], family: null, period: null, upTo: null, afterCutoff: [],
    currencies: ['EUR'], counts: [],
    totals: { created: 3, updated: 0, unchanged: 0, missing: 0, conflicts: 0, failed: errors, skipped: 0, notImported: 0 },
    missing: [], conflicts: [],
    errors: Array.from({ length: errors }, (_, i) => ({ file: 't.csv', row: i + 2, excelId: String(i + 1), message: 'x' })),
    warnings: [], errorSummary: errors ? [{ message: 'x', count: errors, rows: ['1'] }] : [], warningSummary: [],
    skippedSummary: [],
    referenceData: { accountsCreated: 0, accountsUpdated: 0, categoriesCreated: 0, categoriesUpdated: 0,
      familyCategoriesCreated: 0, categoriesMerged: 0, counterpartiesCreated: 0 },
    chart: { created: [], renamed: [] }, idGaps: [],
    zeroFxRowsSkipped: 0, fxRowsToReview: [], reconciliation: null, integrityViolations: [],
  }
}

interface Sent { url: string; body: FormData }

function stubApi(answers: ImportReport[], budgetMembers: FamilyMember[] = members): Sent[] {
  const sent: Sent[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/family-ledgers/7/members') return new Response(JSON.stringify(budgetMembers), { status: 200 })
    sent.push({ url, body: init?.body as FormData })
    return new Response(JSON.stringify(answers.shift()), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
  return sent
}

function choose(name: string, content: string, fileName = `${name}.csv`) {
  const input = document.querySelector(`input[name="${name}"]`) as HTMLInputElement
  fireEvent.change(input, { target: { files: [new File([content], fileName, { type: 'text/csv' })] } })
}

/** The check: the form's submit (jsdom's validation doesn't see files that a change event sets). */
function check() {
  expect((screen.getByRole('button', { name: 'Check (dry run)' }) as HTMLButtonElement).disabled).toBe(false)
  fireEvent.submit(document.querySelector('form.import')!)
}

function chooseOwnFile() {
  choose('transactions', OWN)
}

beforeEach(() => vi.stubGlobal('confirm', () => true))
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the import page', () => {
  it('uploads the owner’s file and only the partner’s family rows, under a name of their own, with the budget and the seat', async () => {
    const sent = stubApi([report(0), report(0, 'COMMITTED')])
    render(<MemoryRouter><Import families={[home]} /></MemoryRouter>)
    chooseOwnFile()
    fireEvent.change(screen.getByRole('combobox', { name: 'Family budget' }), { target: { value: '7' } })
    await screen.findByRole('option', { name: 'Sam' })
    // The owner's own place has an account: only Sam is offered.
    expect(screen.queryByRole('option', { name: 'Owner' })).toBeNull()
    fireEvent.change(screen.getByRole('combobox', { name: 'The partner’s place' }), { target: { value: '72' } })
    choose('partnerTransactions', PARTNER, 'Transactions-of-the-partner.csv')
    expect(await screen.findByText(/1 of his 2 rows are family rows and will be uploaded/)).toBeTruthy()

    check()
    await screen.findByText(/Dry run: nothing was saved/)
    expect(sent).toHaveLength(1)
    expect(sent[0].url).toBe('/api/import?dryRun=true&familyLedgerId=7&seatMemberId=72')
    const body = sent[0].body
    expect([...body.keys()]).toEqual(['transactions', 'partnerTransactions'])
    expect((body.get('transactions') as File).name).toBe('transactions.csv')
    const partner = body.get('partnerTransactions') as File
    expect(partner.name).toBe(PARTNER_FILE_NAME)
    const rows = parseCsv(await partner.text())
    expect(rows).toEqual([[...PARTNER_COLUMNS], ['101', '01.02.2024', 'Продукты', 'да', 'Debit', 'EUR', '40.02']])
    const text = await partner.text()
    for (const secret of ['Лавка', 'his note', 'Кафе', 'private', 'Карта', '106']) expect(text).not.toContain(secret)

    const commit = screen.getByRole('button', { name: 'Commit import' }) as HTMLButtonElement
    expect(commit.disabled).toBe(false)
    fireEvent.click(commit)
    await screen.findByText(/Imported: 3 created/)
    expect(sent[1].url).toBe('/api/import?dryRun=false&familyLedgerId=7&seatMemberId=72')
  })

  it('commits only after a dry run without errors, and a new file asks for a check again', async () => {
    stubApi([report(2)])
    render(<MemoryRouter><Import /></MemoryRouter>)
    const commit = () => screen.getByRole('button', { name: 'Commit import' }) as HTMLButtonElement
    expect(commit().disabled).toBe(true)
    chooseOwnFile()
    check()
    await screen.findByText(/2 rows with errors; a commit would save nothing/)
    expect(commit().disabled).toBe(true)
    // A check is only good for the files it read.
    choose('transactions', 'fixed;x\r\n')
    expect(screen.queryByText(/2 rows with errors/)).toBeNull()
    expect(commit().disabled).toBe(true)
  })

  it('without family budgets asks for no budget and uploads no partner part', async () => {
    const sent = stubApi([report(0)])
    render(<MemoryRouter><Import /></MemoryRouter>)
    expect(screen.queryByRole('combobox', { name: 'Family budget' })).toBeNull()
    chooseOwnFile()
    check()
    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0].url).toBe('/api/import?dryRun=true')
    expect(sent[0].body.has('partnerTransactions')).toBe(false)
  })

  it('sends “Import up to” with the dry run and the commit, and a new date asks for a check again (D-165)', async () => {
    const upTo = { ...report(0), upTo: '2026-08-31',
      afterCutoff: [{ source: 'OWNER', rows: 25, afterCutoff: 3 }, { source: 'PARTNER', rows: 7, afterCutoff: 1 }] }
    const sent = stubApi([upTo, report(0), upTo, { ...upTo, outcome: 'COMMITTED', commit: true }])
    render(<MemoryRouter><Import /></MemoryRouter>)
    const commit = () => screen.getByRole('button', { name: 'Commit import' }) as HTMLButtonElement
    const date = screen.getByLabelText(/^Import up to/) as HTMLInputElement
    expect(date.value).toBe('')
    chooseOwnFile()
    fireEvent.change(date, { target: { value: '2026-08-31' } })
    check()
    expect((await screen.findByTestId('after-cutoff')).textContent).toBe('Imported up to 2026-08-31. After it, '
      + 'neither imported nor missing: 3 rows of 25 of yours, 1 row of 7 of the partner’s.')
    expect(sent[0].url).toBe('/api/import?dryRun=true&upTo=2026-08-31')
    expect(commit().disabled).toBe(false)

    // A check is only good for the date it read.
    fireEvent.change(date, { target: { value: '2026-09-30' } })
    expect(commit().disabled).toBe(true)
    fireEvent.change(date, { target: { value: '' } })
    check()
    await waitFor(() => expect(sent).toHaveLength(2))
    expect(sent[1].url).toBe('/api/import?dryRun=true')
    await waitFor(() => expect(commit().disabled).toBe(false))
    fireEvent.change(date, { target: { value: '2026-08-31' } })
    expect(commit().disabled).toBe(true)
    check()
    await waitFor(() => expect(commit().disabled).toBe(false))
    fireEvent.click(commit())
    await screen.findByText(/Imported: 3 created/)
    expect(sent[3].url).toBe('/api/import?dryRun=false&upTo=2026-08-31')
  })

  // D-175: once the partner took his place, each member imports only his own files.
  const claimed: FamilyMember[] = [
    members[0],
    { id: 72, displayName: 'Sam B', role: 'MEMBER', status: 'ACTIVE', joinDate: '2019-12-01', hasAccount: true, share: null,
      claimedSeat: true },
  ]

  it('lets a member who took a place import his own files into it, with no partner’s file (D-175)', async () => {
    const memberReport: ImportReport = {
      ...report(0), period: { from: '2024-02-01', to: '2024-03-08' },
      family: { ledgerId: 7, ledgerName: 'Home', ownerMemberId: 72, seatMemberId: 71, seatName: 'Owner',
        mode: 'MEMBER' },
      member: { familyRows: 4, recordsUnchanged: 3, recordsEdited: ['113'], recordsCreated: 0, recordConflicts: 0,
        sidesAttached: 4, sidesUnchanged: 0, sidesEdited: 0, sideConflicts: 0, settlementRows: 6, tier1: 3, tier2: 1,
        tier3: 2, tier2Many: 1, byDate: 0, ambiguous: 0, settlementsKnown: 0, conversions: 1, conversionsMatched: 1, unmatched: 0,
        ownerSettlementsWithoutSide: 0, benefitRows: 1,
        benefitMonths: [{ month: '2024-02-01', currency: 'EUR', rows: 1, benefitRows: '50.00', shares: '50.00', differs: false }],
        straddling: [{ excelId: '109', rowDate: '2024-03-02', settlementDate: '2024-02-28', currency: 'EUR', amount: '12' }],
        shareWarnings: 0 },
    }
    const sent = stubApi([memberReport], claimed)
    render(<MemoryRouter><Import families={[{ ...home, role: 'MEMBER', memberId: 72 }]} /></MemoryRouter>)
    chooseOwnFile()
    fireEvent.change(screen.getByRole('combobox', { name: 'Family budget' }), { target: { value: '7' } })
    await screen.findByTestId('member-import')
    expect(screen.queryByRole('combobox', { name: 'The partner’s place' })).toBeNull()
    expect(document.querySelector('input[name="partnerTransactions"]')).toBeNull()

    check()
    await screen.findByText(/Dry run: nothing was saved/)
    expect(sent[0].url).toBe('/api/import?dryRun=true&familyLedgerId=7&seatMemberId=72')
    expect([...sent[0].body.keys()]).toEqual(['transactions'])
    const summary = screen.getByTestId('member-summary')
    expect(summary.textContent).toContain('Your payment side attached4')
    expect(summary.textContent).toContain('Settlements matched: several on the date1')
    expect(summary.textContent).toContain('Settlements matched: several of your rows on the date1')
    expect(summary.textContent).toContain('Changed, by Excel ID: 113.')
    expect(summary.textContent).toContain('none differs')
    expect(screen.getByText(/Family budget “Home”, your own place\./)).toBeTruthy()
  })

  it('takes only the owner’s own files once the partner took his place (D-175)', async () => {
    const sent = stubApi([report(0)], claimed)
    render(<MemoryRouter><Import families={[home]} /></MemoryRouter>)
    chooseOwnFile()
    fireEvent.change(screen.getByRole('combobox', { name: 'Family budget' }), { target: { value: '7' } })
    await screen.findByRole('option', { name: 'Sam B' })
    fireEvent.change(screen.getByRole('combobox', { name: 'The partner’s place' }), { target: { value: '72' } })
    expect((await screen.findByTestId('own-file')).textContent).toContain('Sam B took this place')
    expect(document.querySelector('input[name="partnerTransactions"]')).toBeNull()

    check()
    await screen.findByText(/Dry run: nothing was saved/)
    expect(sent[0].url).toBe('/api/import?dryRun=true&familyLedgerId=7&seatMemberId=72')
    expect([...sent[0].body.keys()]).not.toContain('partnerTransactions')
  })

  it('tells a member who took no place that only the owner imports into the budget', async () => {
    stubApi([], [members[0], { ...members[1], id: 73, hasAccount: true }])
    render(<MemoryRouter><Import families={[{ ...home, role: 'MEMBER', memberId: 73 }]} /></MemoryRouter>)
    chooseOwnFile()
    fireEvent.change(screen.getByRole('combobox', { name: 'Family budget' }), { target: { value: '7' } })
    expect(await screen.findByText(/Only the budget’s owner, or a member who took a place in it/)).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Check (dry run)' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('shows the file’s rows, its highest ID and its last row date before any check (D-221, D-257)', async () => {
    const sent = stubApi([])
    render(<MemoryRouter><Import /></MemoryRouter>)
    expect(screen.queryByTestId('file-rows')).toBeNull()
    // The header, three rows (one of them the app's), a blank line and a line of blanks: 3 rows.
    const header = TRANSACTIONS_COLUMNS.join(';')
    choose('transactions', `\ufeff${header}\r\n12;01.09.2026;-;5\r\n\r\n;; \r\n9;"30.09.2026";-;"1;5"\r\n`
      + 'app-77;02.10.2026;-;1\r\n')
    expect((await screen.findByTestId('file-rows')).textContent).toBe('Your transactions file: 3 rows, counted as the '
      + 'import counts them; highest ID 12; last row dated 2026-10-02.')
    expect(screen.queryByTestId('not-transactions')).toBeNull()
    // Another file's header is named before the check.
    choose('transactions', 'Статьи баланса;Актив/пассив;Line\r\nКошелёк;ASSET;CASH\r\n')
    await waitFor(() => expect(screen.getByTestId('file-rows').textContent).toBe('Your transactions file: 1 row, '
      + 'counted as the import counts them.'))
    expect(screen.getByTestId('not-transactions').textContent).toContain('isn’t a transactions file’s')
    expect(sent).toHaveLength(0)
  })

  it('keeps Commit off after a commit until a file or the date changes, and says which it was (D3e)', async () => {
    const sent = stubApi([report(0), { ...report(0, 'COMMITTED'), totals: { ...report(0).totals, created: 3 } },
      { ...report(0), totals: { ...report(0).totals, created: 0 } }, report(0), report(0, 'COMMITTED')])
    render(<MemoryRouter><Import /></MemoryRouter>)
    const commit = () => screen.getByRole('button', { name: 'Commit import' }) as HTMLButtonElement
    chooseOwnFile()
    check()
    expect((await screen.findByTestId('report-kind')).textContent).toBe('Check (dry run): nothing was saved')
    fireEvent.click(commit())
    await waitFor(() => expect(screen.getByTestId('report-kind').textContent).toBe('Commit: the import was saved'))
    expect(commit().disabled).toBe(true)

    // The check again: Commit stays off for the files just committed.
    check()
    await waitFor(() => expect(sent).toHaveLength(3))
    await screen.findByText(/committing would create 0/)
    expect(screen.getByTestId('report-kind').textContent).toBe('Check (dry run): nothing was saved')
    expect(commit().disabled).toBe(true)
    expect(screen.getByTestId('committed-already').textContent).toContain('Commit is off until a file or the date changes')

    // Another date (or file) and a check: Commit again.
    fireEvent.change(screen.getByLabelText(/^Import up to/), { target: { value: '2026-10-31' } })
    expect(screen.queryByTestId('committed-already')).toBeNull()
    check()
    await waitFor(() => expect(commit().disabled).toBe(false))
    fireEvent.click(commit())
    await waitFor(() => expect(sent).toHaveLength(5))
    expect(sent.map((s) => s.url.split('&')[0])).toEqual(['/api/import?dryRun=true', '/api/import?dryRun=false',
      '/api/import?dryRun=true', '/api/import?dryRun=true', '/api/import?dryRun=false'])
  })

  it('names a refused commit as one', async () => {
    stubApi([report(0), report(1, 'ABORTED')])
    render(<MemoryRouter><Import /></MemoryRouter>)
    chooseOwnFile()
    check()
    await screen.findByTestId('report-kind')
    fireEvent.click(screen.getByRole('button', { name: 'Commit import' }))
    await waitFor(() => expect(screen.getByTestId('report-kind').textContent).toBe('Commit refused: nothing was saved'))
  })

  it('lists D-219’s groups and D-223’s amounts (D3e)', async () => {
    const ref = (excelId: string, reason: string) => ({ source: 'OWNER_PERSONAL', excelId, reason })
    stubApi([{ ...report(0), sync: {
      appEdited: [ref('211', 'changed in the app, the file unchanged: left as it is (D-219)'),
        ref('2839', 'changed in the app, the file unchanged: left as it is (D-219)')],
      appEdits: [{ source: 'OWNER_PERSONAL', excelId: '211', date: '2018-01-23', account: 'EQUITY', currency: 'RUB',
        amount: '-2.50' }],
      replanned: [], restamped: [ref('9', 'the ledger holds what the row maps to; its hash is stored again (D-219)')],
      notReplannable: [{ source: 'OWNER_SEAT', excelId: '4117', reason: 'never written' }],
      categoriesMerged: ['CASHBACK', 'CHARITY'], appRowsUnchanged: 25,
      appRows: [{ source: 'APP', excelId: 'app-f9', reason: 'a row the app made, changed in the file: listed, never '
        + 'written (D-259)' }],
    } }])
    render(<MemoryRouter><Import /></MemoryRouter>)
    chooseOwnFile()
    check()
    const groups = await screen.findByTestId('sync-groups')
    expect(groups.textContent).toContain('Changed in the app, the file unchanged: left as they are (2)')
    expect(screen.getByTestId('app-edits').textContent).toContain('2112018-01-23EQUITY RUB-2.50')
    expect(groups.textContent).toContain('As the ledger holds them, under an old hash: the hash stored again (1)')
    expect(groups.textContent).toContain('Can’t be re-planned after the claim: listed, never written (1)')
    expect(groups.textContent).not.toContain('Re-planned')
    expect(screen.getByTestId('categories-merged').textContent)
      .toBe('Personal categories merged into the family budget’s: CASHBACK, CHARITY.')
    // D-259: the app's rows.
    expect(screen.getByTestId('app-rows-unchanged').textContent).toBe('Rows the app made, as your ledger holds them: 25.')
    expect(groups.textContent).toContain('Rows the app made that aren’t as your ledger holds them: listed, never '
      + 'written (1)')
    expect(groups.textContent).toContain('Rows the app madeapp-f9')
  })

  it('shows the check against the file: the month-ends, the rows that fail, what is created and renamed (D4b)', async () => {
    stubApi([{ ...report(0),
      chart: { created: [{ kind: 'ACCOUNT', code: 'NORTH_CARD', name: 'Карта', type: 'ASSET' }],
        renamed: [{ kind: 'CATEGORY', code: 'COMMISSION', from: 'Комиссия', to: 'Комиссии' }] },
      idGaps: ['4', '11-13'],
      reconciliation: {
        passed: false, from: '2024-01-02', cutoff: '2024-03-05', pointsCompared: 6, lastCompared: '2024-03-05',
        lines: [{ account: 'EQUITY+FAMILY_DEBT', currency: 'EUR', compared: 3, failed: 0, firstFailure: null,
          worstUnexplained: '0.00', lastExcel: '-10.00', lastApp: '-10.00', lastExplained: '0.00' },
        { account: 'CASH', currency: 'EUR', compared: 3, failed: 1, firstFailure: '2024-03-05',
          worstUnexplained: '2.00', lastExcel: '5.00', lastApp: '7.00', lastExplained: '0.00' }],
        failures: [], explained: [], appOnly: [],
        rows: { rows: 25, built: 24, explainedRows: 3, failed: 1, explained: [],
          failures: [{ excelId: '21', row: 22, what: 'DEBIT_CHANGE', file: '-1.50', computed: '-2.50',
            explained: '0.00', unexplained: '1.00' }] },
      } }])
    render(<MemoryRouter><Import /></MemoryRouter>)
    chooseOwnFile()
    check()
    const failed = await screen.findByRole('alert')
    expect(failed.textContent).toBe('Failed: 6 month-end balances compared, from 2024-01-02 to 2024-03-05, as '
      + 'movements since the day before the file’s first row; 25 rows checked, 1 failing.')
    expect(screen.getByTestId('row-failures').textContent).toContain('21DEBIT_CHANGE-1.50-2.501.00')
    expect(screen.getByTestId('chart').textContent).toContain('New account NORTH_CARD “Карта” (ASSET)')
    expect(screen.getByTestId('chart').textContent).toContain('Renamed category COMMISSION: “Комиссия” to “Комиссии”')
    expect(screen.getByTestId('id-gaps').textContent).toContain('4, 11-13')
  })
})
