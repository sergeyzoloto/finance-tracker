import { useState } from 'react'
import { Link } from 'react-router'
import { api, useMutation, type EntryKind, type ImportProblem, type ImportReport } from './api'
import { Errors } from './components'
import { formatMoney } from './money'

/** The files of the Excel ledger's CSV export, by the name of their part in POST /api/import. */
const FILES = [
  { part: 'accounts', label: 'Accounts', hint: 'BalanceSheetItems.csv', required: true },
  { part: 'categories', label: 'Categories', hint: 'CashFlowItems.csv', required: true },
  { part: 'transactions', label: 'Transactions', hint: 'Transactions.csv', required: true },
  { part: 'openingBalances', label: 'Opening balances', hint: 'optional: line, currency, amount, date', required: false },
] as const
type Part = (typeof FILES)[number]['part']

const KIND_LABELS: Record<EntryKind, string> = {
  EXPENSE: 'Expenses', INCOME: 'Income', TRANSFER: 'Transfers', SHARED_EXPENSE: 'Shared expenses',
  LOAN_GIVEN: 'Loans given', LOAN_REPAID: 'Loans repaid', CURRENCY_EXCHANGE: 'Currency exchanges',
  OPENING_BALANCE: 'Opening balances', MANUAL: 'Other entries',
}

/**
 * Imports the Excel ledger: a dry run first, which writes everything, reports and rolls back; then, if no row has an
 * error, the same files for real. Rows imported before are skipped, so running it again is safe.
 */
export default function Import() {
  const [files, setFiles] = useState<Partial<Record<Part, File>>>({})
  const [report, setReport] = useState<ImportReport>()
  const { run, error, pending } = useMutation()

  const ready = FILES.every((f) => !f.required || files[f.part])
  const checked = report?.outcome === 'DRY_RUN'
  const canCommit = checked && report.errors.length === 0 && !pending

  function choose(part: Part, file: File | undefined) {
    setFiles({ ...files, [part]: file })
    setReport(undefined) // a check is only good for the files it read
  }

  const upload = (dryRun: boolean) => run(async () => {
    const body = new FormData()
    FILES.forEach(({ part }) => files[part] && body.append(part, files[part]))
    setReport(await api<ImportReport>(`/import?dryRun=${dryRun}`, 'POST', body))
  })

  return (
    <>
      <h2>Import</h2>
      <p className="muted">
        Import the Excel ledger’s CSV export. <strong>Check</strong> runs the whole import and then undoes it, so you can
        see what it would do. <strong>Commit</strong> saves it, all or nothing. Rows imported before are skipped.
      </p>
      <form className="import" onSubmit={(e) => { e.preventDefault(); void upload(true) }}>
        <div className="fields">
          {FILES.map(({ part, label, hint, required }) => (
            <label key={part} className="field">
              <span className="label">{label}</span>
              <input type="file" accept=".csv,text/csv" required={required}
                onChange={(e) => choose(part, e.target.files?.[0])} />
              <small className="hint">{hint}</small>
            </label>
          ))}
        </div>
        <div className="actions">
          <button className={checked ? '' : 'primary'} disabled={!ready || pending}>Check (dry run)</button>
          <button type="button" className={canCommit ? 'primary' : ''} disabled={!canCommit}
            onClick={() => confirm('Save the import to your ledger?') && void upload(false)}>Commit import</button>
          {pending && <span className="muted">Working… a large ledger takes a moment.</span>}
          {checked && report.errors.length > 0 && (
            <span className="error">Fix the {rows(report.errors.length)} with errors and check again to commit.</span>
          )}
        </div>
      </form>
      <Errors messages={[error]} />
      {report && <Report report={report} />}
    </>
  )
}

function Report({ report }: { report: ImportReport }) {
  const kinds = Object.entries(report.entriesByKind).filter(([, n]) => n) as [EntryKind, number][]
  const entries = kinds.reduce((total, [, n]) => total + n, 0)
  const refs = report.referenceData
  return (
    <section className="report">
      {report.outcome === 'DRY_RUN' && (
        <p className={report.errors.length === 0 ? 'success' : 'notice'} role="status">
          Dry run: nothing was saved. {report.errors.length === 0
            ? `No errors: committing would import ${entries} entries.`
            : `${rows(report.errors.length)} with errors; a commit would save nothing.`}
        </p>
      )}
      {report.outcome === 'COMMITTED' && (
        <p className="success" role="status">
          Imported {entries} entries. <Link to="/entries">See the entries</Link> or <Link to="/accounts">the balances</Link>.
        </p>
      )}
      {report.outcome === 'ABORTED' && (
        <p className="error-box" role="alert">Nothing was saved: {rows(report.errors.length)} with errors.</p>
      )}

      <h3>Files</h3>
      <table>
        <tbody>
          {report.files.map((f) => <tr key={f.role}><td>{f.role}</td><td>{f.name}</td><td className="amount">{f.rows} rows</td></tr>)}
        </tbody>
      </table>

      <h3>Entries</h3>
      {kinds.length === 0 ? <p className="empty">No new entries.</p> : (
        <table>
          <tbody>
            {kinds.map(([kind, n]) => <tr key={kind}><td>{KIND_LABELS[kind]}</td><td className="amount">{n}</td></tr>)}
            <tr><th>Total</th><th className="amount">{entries}</th></tr>
          </tbody>
        </table>
      )}
      <p className="muted">
        Accounts: {refs.accountsCreated} new, {refs.accountsUpdated} updated. Categories: {refs.categoriesCreated} new,
        {' '}{refs.categoriesUpdated} updated. Payees and people: {refs.counterpartiesCreated} new.
      </p>

      <Problems title="Errors" problems={report.errors} className="error" open />
      <Problems title="Warnings" problems={report.warnings} open={report.warnings.length <= 20} />
      <Problems title="Skipped rows" problems={report.skipped} />
      {report.fxRowsToReview.length > 0 && (
        <details>
          <summary>Exchange gains imported as income, to review ({report.fxRowsToReview.length})</summary>
          <table>
            <thead><tr><th>Row</th><th>Date</th><th className="amount">Sum</th><th>From</th><th>To</th><th>Comment</th></tr></thead>
            <tbody>
              {report.fxRowsToReview.map((r) => (
                <tr key={r.row}>
                  <td>{r.row}</td><td className="nowrap">{r.date}</td>
                  <td className="amount nowrap">{formatMoney(r.sum, r.currency)}</td>
                  <td>{r.credit}</td><td>{r.debit}</td><td>{r.comment}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      )}

      <h3>Balances {report.outcome === 'COMMITTED' ? 'now' : 'after the import'}</h3>
      {report.balances.length === 0 ? <p className="empty">No balances.</p> : (
        <table>
          <thead><tr><th>Account</th><th className="amount">Balance</th></tr></thead>
          <tbody>
            {report.balances.map((b) => (
              <tr key={`${b.accountId}:${b.currency}`}>
                <td>{b.accountName}</td>
                <td className="amount nowrap">{formatMoney(b.balance, b.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {report.integrityViolations.length > 0 && (
        <div className="error-box" role="alert">
          <p>The ledger wouldn’t add up in these currencies:</p>
          <ul>
            {report.integrityViolations.map((v) => (
              <li key={v.currency}>
                {v.currency}: postings sum to {formatMoney(v.postingSum, v.currency)}, balance sheet gap
                {' '}{formatMoney(v.balanceSheetGap, v.currency)}
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}

const rows = (n: number) => (n === 1 ? '1 row' : `${n} rows`)

function Problems({ title, problems, className, open = false }: {
  title: string
  problems: ImportProblem[]
  className?: string
  open?: boolean
}) {
  if (problems.length === 0) return <p className="muted">{title}: none.</p>
  return (
    <details open={open}>
      <summary className={className}>{title} ({problems.length})</summary>
      <table>
        <thead><tr><th>File</th><th>Row</th><th>Message</th></tr></thead>
        <tbody>
          {problems.map((p, i) => (
            <tr key={i}><td>{p.file}</td><td>{p.row ?? '—'}</td><td>{p.message}</td></tr>
          ))}
        </tbody>
      </table>
    </details>
  )
}
