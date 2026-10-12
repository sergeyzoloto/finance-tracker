import { useState } from 'react'
import { Link } from 'react-router'
import {
  api, useApi, useMutation, type FamilyLedger, type FamilyMember, type ImportMessageCount, type ImportReconciliation,
  type ImportReport, type ImportRowRef,
} from './api'
import { Errors } from './components'
import { filterPartnerRows, importForm, unallocatedName, type OwnerFiles, type PartnerUpload } from './importFilter'

/**
 * The owner's export, by the name of each file's part in POST /api/import. The hints describe each file's role; the
 * export's own file names stay out of the code (D-64, D-75).
 */
const OWNER_FILES = [
  { part: 'accounts', label: 'Accounts', hint: 'the balance sheet items: name, ASSET or LIABILITIES, code' },
  { part: 'categories', label: 'Categories', hint: 'the cash flow items: name, INCOME or EXPENSE, code' },
  { part: 'transactions', label: 'Transactions', hint: 'your journal, with its ID column' },
  { part: 'dailyBalance', label: 'Daily balances', hint: 'each account’s balance per day: opening balances and the check' },
  { part: 'dailyChange', label: 'Daily changes', hint: 'each day’s change, with the monthly family lumps' },
] as const
type Part = (typeof OWNER_FILES)[number]['part']

const SOURCES: Record<string, string> = {
  OWNER_PERSONAL: 'Your personal rows', OWNER_FAMILY: 'Your family rows', OWNER_SETTLEMENT: 'Your settlements',
  OWNER_SEAT: 'Your rows on the family debt', OWNER_LUMP: 'Monthly lumps before the partner’s ledger',
  PARTNER: 'The partner’s family rows', OWNER: 'Your rows',
}

interface Choice { files: Partial<Record<Part, File>>; ledgerId: string; seatId: string; partner?: File }

/**
 * Imports the Excel export (D3b): your personal rows into your ledger, and, with a family budget and the partner’s
 * seat (D-141), the family rows of both of you into the budget. The partner’s file is filtered here, in the browser:
 * only his family rows, and only what the import needs, are uploaded (D-140). A dry run first, which writes
 * everything, reconciles your balances with your daily balance file, reports and rolls back; then, if no row has an
 * error, the same files for real. Running it again recognises the rows by their IDs: nothing is written twice.
 */
export default function Import({ families }: { families?: FamilyLedger[] }) {
  const [choice, setChoice] = useState<Choice>({ files: {}, ledgerId: '', seatId: '' })
  const [upload, setUpload] = useState<PartnerUpload>()
  const [filterError, setFilterError] = useState<string>()
  const [report, setReport] = useState<ImportReport>()
  const { run, error, pending } = useMutation()
  const members = useApi<FamilyMember[]>(choice.ledgerId ? `/family-ledgers/${choice.ledgerId}/members` : null)
  const seats = (members.data ?? []).filter((m) => m.status === 'ACTIVE' && !m.hasAccount)

  const family = choice.ledgerId !== ''
  const ownerReady = OWNER_FILES.every((f) => choice.files[f.part])
  const familyReady = !family || (choice.seatId !== '' && upload !== undefined)
  const ready = ownerReady && familyReady && !filterError
  const checked = report?.outcome === 'DRY_RUN'
  const canCommit = checked && report.errors.length === 0 && !pending

  function change(next: Choice) {
    setChoice(next)
    setReport(undefined) // a check is only good for the files and the budget it read
  }

  async function filter(next: Choice) {
    setUpload(undefined)
    setFilterError(undefined)
    if (!next.partner || !next.files.accounts) return
    try {
      const unallocated = unallocatedName(await next.files.accounts.text())
      if (!unallocated) throw new Error('Your accounts file has no UNALLOCATED account.')
      setUpload(filterPartnerRows(await next.partner.text(), unallocated))
    } catch (e) {
      setFilterError(e instanceof Error ? e.message : String(e))
    }
  }

  function chooseFile(part: Part, file: File | undefined) {
    const next = { ...choice, files: { ...choice.files, [part]: file } }
    change(next)
    if (part === 'accounts') void filter(next)
  }

  function choosePartner(file: File | undefined) {
    const next = { ...choice, partner: file }
    change(next)
    void filter(next)
  }

  const send = (dryRun: boolean) => run(async () => {
    const files = choice.files as Record<Part, File>
    const names = Object.fromEntries(OWNER_FILES.map(({ part }) => [part, files[part].name])) as Record<Part, string>
    const body = importForm(files as OwnerFiles, names, family ? upload : undefined)
    const query = new URLSearchParams({ dryRun: String(dryRun) })
    if (family) {
      query.set('familyLedgerId', choice.ledgerId)
      query.set('seatMemberId', choice.seatId)
    }
    setReport(await api<ImportReport>(`/import?${query}`, 'POST', body))
  })

  return (
    <>
      <h2>Import</h2>
      <p className="muted">
        Import the Excel ledger’s CSV export. <strong>Check</strong> runs the whole import, compares your balances with
        your daily balance file, and then undoes it, so you can see what it would do. <strong>Commit</strong> saves it,
        all or nothing. Rows imported before are recognised by their IDs and never written twice.
      </p>
      <form className="import" onSubmit={(e) => { e.preventDefault(); void send(true) }}>
        <h3>Your export</h3>
        <div className="fields">
          {OWNER_FILES.map(({ part, label, hint }) => (
            <label key={part} className="field">
              <span className="label">{label}</span>
              <input type="file" accept=".csv,text/csv" required name={part}
                onChange={(e) => chooseFile(part, e.target.files?.[0])} />
              <small className="hint">{hint}</small>
            </label>
          ))}
        </div>
        {families && (
          <>
            <h3>Family budget</h3>
            <p className="muted">
              Your family rows, and the partner’s, go into a family budget, his into the place of a member without an
              account. Create the budget and that place first; then choose them here.
            </p>
            <div className="fields">
              <label className="field">
                <span className="label">Family budget</span>
                <select value={choice.ledgerId} name="familyLedgerId"
                  onChange={(e) => change({ ...choice, ledgerId: e.target.value, seatId: '' })}>
                  <option value="">None: my personal rows only</option>
                  {families.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
                </select>
              </label>
              {family && (
                <label className="field">
                  <span className="label">The partner’s place</span>
                  <select value={choice.seatId} name="seatMemberId" required
                    onChange={(e) => change({ ...choice, seatId: e.target.value })}>
                    <option value="">Choose…</option>
                    {seats.map((m) => <option key={m.id} value={m.id}>{m.displayName}</option>)}
                  </select>
                  {members.data && seats.length === 0 && (
                    <small className="hint">This budget has no member without an account: add one on its members page.</small>
                  )}
                </label>
              )}
              {family && (
                <label className="field">
                  <span className="label">The partner’s transactions</span>
                  <input type="file" accept=".csv,text/csv" required name="partnerTransactions"
                    onChange={(e) => choosePartner(e.target.files?.[0])} />
                  <small className="hint">
                    Filtered here: only his family rows, with their ID, date, item, amount and currency, leave this
                    browser; his other rows, accounts and comments, and his daily files, never do.
                  </small>
                </label>
              )}
            </div>
            {family && upload && (
              <p className="muted" role="status">
                {upload.kept} of his {upload.read} rows are family rows and will be uploaded.
                {upload.withoutUnallocated > 0 && ` ${upload.withoutUnallocated} of them have UNALLOCATED on neither side.`}
              </p>
            )}
            {family && !choice.files.accounts && choice.partner && (
              <p className="muted">Choose your accounts file too: it names UNALLOCATED, which the filter needs.</p>
            )}
          </>
        )}
        <div className="actions">
          <button className={checked ? '' : 'primary'} disabled={!ready || pending}>Check (dry run)</button>
          <button type="button" className={canCommit ? 'primary' : ''} disabled={!canCommit}
            onClick={() => confirm('Save the import?') && void send(false)}>Commit import</button>
          {pending && <span className="muted">Working… a large ledger takes several minutes.</span>}
          {checked && report.errors.length > 0 && (
            <span className="error">Fix the {rows(report.errors.length)} with errors and check again to commit.</span>
          )}
        </div>
      </form>
      <Errors messages={[error, filterError]} />
      {report && <Report report={report} />}
    </>
  )
}

function Report({ report }: { report: ImportReport }) {
  const t = report.totals
  const refs = report.referenceData
  return (
    <section className="report">
      {report.outcome === 'DRY_RUN' && (
        <p className={report.errors.length === 0 ? 'success' : 'notice'} role="status">
          Dry run: nothing was saved. {report.errors.length === 0
            ? `No errors: committing would create ${t.created} and update ${t.updated}.`
            : `${rows(report.errors.length)} with errors; a commit would save nothing.`}
        </p>
      )}
      {report.outcome === 'COMMITTED' && (
        <p className="success" role="status">
          Imported: {t.created} created, {t.updated} updated. <Link to="/entries">See the entries</Link>
          {report.family && <> or <Link to={`/family/${report.family.ledgerId}/expenses`}>the family budget</Link></>}.
        </p>
      )}
      {report.outcome === 'ABORTED' && (
        <p className="error-box" role="alert">Nothing was saved: {rows(report.errors.length)} with errors.</p>
      )}

      <table className="import-totals">
        <tbody>
          <tr><td>Created</td><td className="amount">{t.created}</td></tr>
          <tr><td>Updated</td><td className="amount">{t.updated}</td></tr>
          <tr><td>Unchanged</td><td className="amount">{t.unchanged}</td></tr>
          <tr><td>No longer in the files</td><td className="amount">{t.missing}</td></tr>
          <tr><td>Conflicts</td><td className="amount">{t.conflicts}</td></tr>
          <tr><td>Not imported</td><td className="amount">{t.notImported}</td></tr>
          <tr><td>Skipped</td><td className="amount">{t.skipped}</td></tr>
          <tr><td>Errors</td><td className="amount">{report.errors.length}</td></tr>
        </tbody>
      </table>
      {report.period && (
        <p className="muted">
          Rows from {report.period.from} to {report.period.to}, in {report.currencies.join(', ')}.
          {report.family && ` Family budget “${report.family.ledgerName}”, the partner as “${report.family.seatName}”.`}
        </p>
      )}

      <details>
        <summary>By source and kind</summary>
        <table>
          <thead>
            <tr><th>Rows</th><th>Kind</th><th className="amount">Created</th><th className="amount">Updated</th>
              <th className="amount">Unchanged</th><th className="amount">Conflicts</th><th className="amount">Errors</th>
              <th className="amount">Skipped</th><th className="amount">Not imported</th></tr>
          </thead>
          <tbody>
            {report.counts.map((c) => (
              <tr key={`${c.source} ${c.kind}`}>
                <td>{SOURCES[c.source] ?? c.source}</td><td>{c.kind}</td><td className="amount">{c.created}</td>
                <td className="amount">{c.updated}</td><td className="amount">{c.unchanged}</td>
                <td className="amount">{c.conflicts}</td><td className="amount">{c.failed}</td>
                <td className="amount">{c.skipped}</td><td className="amount">{c.notImported}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
      <p className="muted">
        Accounts: {refs.accountsCreated} new, {refs.accountsUpdated} updated. Categories: {refs.categoriesCreated} new,
        {' '}{refs.categoriesUpdated} updated; family categories: {refs.familyCategoriesCreated} new, {refs.categoriesMerged}
        {' '}of yours merged into them. Payees and people: {refs.counterpartiesCreated} new.
      </p>

      <Messages title="Errors" messages={report.errorSummary} className="error" open />
      <Messages title="Warnings" messages={report.warningSummary} />
      <Messages title="Skipped and not imported" messages={report.skippedSummary} />
      <RowRefs title="Conflicts: changed or deleted in the app, left as they are" refs={report.conflicts} />
      <RowRefs title="No longer in the files: reported, nothing deleted" refs={report.missing} />
      <Reconciliation reconciliation={report.reconciliation} />
      {report.integrityViolations.length > 0 && (
        <div className="error-box" role="alert">
          <p>The ledger wouldn’t add up in: {report.integrityViolations.map((v) => v.currency).join(', ')}.</p>
        </div>
      )}
    </section>
  )
}

function Reconciliation({ reconciliation: r }: { reconciliation: ImportReconciliation | null }) {
  if (!r) return <p className="notice">No reconciliation: the daily balance file couldn’t be read.</p>
  const failing = r.lines.filter((l) => l.failed > 0)
  return (
    <section className="reconciliation">
      <h3>Your balances against the daily balance file</h3>
      <p className={r.passed ? 'success' : 'error-box'} role={r.passed ? 'status' : 'alert'}>
        {r.passed ? 'Passed' : 'Failed'}: {r.pointsCompared} month-end balances compared, up to {r.lastCompared ?? '–'}.
        From {r.tailFrom} on they are shown but don’t count; the files are read up to {r.cutoff}.
      </p>
      {failing.length > 0 && (
        <table>
          <thead><tr><th>Account</th><th>Failing month-ends</th><th>From</th><th className="amount">Worst</th></tr></thead>
          <tbody>
            {failing.map((l) => (
              <tr key={`${l.account} ${l.currency}`}>
                <td>{l.account} {l.currency}</td><td>{l.failed} of {l.compared}</td><td>{l.firstFailure}</td>
                <td className="amount">{l.worstUnexplained}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {r.withoutFamilyRows.length > 0 && (
        <p className="muted">
          Without family rows, compared as one sum with the equity: {r.withoutFamilyRows.map((c) =>
            `${c.currency} (the family debt here ${c.ok ? 'is 0' : `is ${c.appFamilyDebt}, not 0`})`).join(', ')}.
        </p>
      )}
      <details>
        <summary>Every account and currency ({r.lines.length})</summary>
        <table>
          <thead>
            <tr><th>Account</th><th className="amount">Excel</th><th className="amount">App</th>
              <th className="amount">Explained</th></tr>
          </thead>
          <tbody>
            {r.lines.map((l) => (
              <tr key={`${l.account} ${l.currency}`}>
                <td>{l.account} {l.currency}</td><td className="amount">{l.lastExcel}</td>
                <td className="amount">{l.lastApp}</td><td className="amount">{l.lastExplained}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
      {r.tail.length > 0 && (
        <details>
          <summary>The last month, shown only ({r.tail.length})</summary>
          <ul>{r.tail.map((p) => <li key={`${p.date} ${p.account} ${p.currency}`}>
            {p.date} {p.account} {p.currency}: Excel {p.excel}, app {p.app}</li>)}</ul>
        </details>
      )}
    </section>
  )
}

const rows = (n: number) => (n === 1 ? '1 row' : `${n} rows`)

function Messages({ title, messages, className, open = false }: {
  title: string
  messages: ImportMessageCount[]
  className?: string
  open?: boolean
}) {
  if (messages.length === 0) return <p className="muted">{title}: none.</p>
  return (
    <details open={open}>
      <summary className={className}>{title} ({messages.reduce((n, m) => n + m.count, 0)})</summary>
      <table>
        <thead><tr><th>Message</th><th className="amount">Rows</th><th>Excel IDs</th></tr></thead>
        <tbody>
          {messages.map((m) => (
            <tr key={m.message}><td>{m.message}</td><td className="amount">{m.count}</td><td>{m.rows.join(', ')}</td></tr>
          ))}
        </tbody>
      </table>
    </details>
  )
}

function RowRefs({ title, refs }: { title: string; refs: ImportRowRef[] }) {
  if (refs.length === 0) return null
  return (
    <details open>
      <summary className="notice">{title} ({refs.length})</summary>
      <table>
        <thead><tr><th>Rows</th><th>Excel ID</th><th>Why</th></tr></thead>
        <tbody>
          {refs.map((r) => (
            <tr key={`${r.source} ${r.excelId}`}><td>{SOURCES[r.source] ?? r.source}</td><td>{r.excelId}</td><td>{r.reason}</td></tr>
          ))}
        </tbody>
      </table>
    </details>
  )
}
