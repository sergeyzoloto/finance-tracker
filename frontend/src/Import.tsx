import { useState } from 'react'
import { Link } from 'react-router'
import {
  api, useApi, useMutation, type FamilyLedger, type FamilyMember, type ImportMemberSummary, type ImportMessageCount,
  type ImportReconciliation, type ImportReport, type ImportRowRef,
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
  MEMBER_PERSONAL: 'Your personal rows', MEMBER_FAMILY: 'Your family rows', MEMBER_SETTLEMENT: 'Your settlements',
  MEMBER_BENEFIT: 'Your rows on the family debt', MEMBER: 'Your rows',
}

/**
 * Whose rows a family budget's import takes (D-175): the owner's with the partner's place, whose rows he uploads while
 * it has no account; the owner's own once someone took that place; or, for a member who took a place, his own files
 * into his own place. Null when the reader may not import into the budget.
 */
export type ImportRole = 'OWNER' | 'MEMBER' | null

export function importRole(ledger: FamilyLedger | undefined, members: FamilyMember[] | undefined): ImportRole {
  if (!ledger || !members) return null
  const me = members.find((m) => m.id === ledger.memberId)
  if (me?.claimedSeat) return 'MEMBER'
  return ledger.role === 'OWNER' ? 'OWNER' : null
}

/** The places an owner's import may name: a member without an account, or one who took a place since (D-175). */
export function partnerPlaces(ledger: FamilyLedger, members: FamilyMember[]): FamilyMember[] {
  return members.filter((m) => m.id !== ledger.memberId && m.status === 'ACTIVE' && (!m.hasAccount || m.claimedSeat))
}

interface Choice { files: Partial<Record<Part, File>>; ledgerId: string; seatId: string; partner?: File; upTo: string }

/**
 * Imports the Excel export (D3b): your personal rows into your ledger, and, with a family budget and the partner’s
 * seat (D-141), the family rows of both of you into the budget. The partner’s file is filtered here, in the browser:
 * only his family rows, and only what the import needs, are uploaded (D-140). A dry run first, which writes
 * everything, reconciles your balances with your daily balance file, reports and rolls back; then, if no row has an
 * error, the same files for real. Running it again recognises the rows by their IDs: nothing is written twice.
 * “Import up to” leaves out the rows of either file dated after it, and the check stops there (D-165); Commit is only
 * for the files, the budget and the date its dry run read.
 */
export default function Import({ families }: { families?: FamilyLedger[] }) {
  const [choice, setChoice] = useState<Choice>({ files: {}, ledgerId: '', seatId: '', upTo: '' })
  // The choice the report is about: Commit only for exactly what the dry run read.
  const [reported, setReported] = useState<Choice>()
  const [upload, setUpload] = useState<PartnerUpload>()
  const [filterError, setFilterError] = useState<string>()
  const [report, setReport] = useState<ImportReport>()
  const { run, error, pending } = useMutation()
  const members = useApi<FamilyMember[]>(choice.ledgerId ? `/family-ledgers/${choice.ledgerId}/members` : null)
  const ledger = families?.find((f) => String(f.id) === choice.ledgerId)
  const role = importRole(ledger, members.data)
  const seats = ledger && members.data ? partnerPlaces(ledger, members.data) : []
  const seat = seats.find((m) => String(m.id) === choice.seatId)
  // A place with an account imports its own file: the owner's import takes no partner's file then (D-175).
  const partnerFile = role === 'OWNER' && seat !== undefined && !seat.hasAccount

  const family = choice.ledgerId !== ''
  const ownerReady = OWNER_FILES.every((f) => choice.files[f.part])
  const familyReady = !family || role === 'MEMBER'
    || (role === 'OWNER' && seat !== undefined && (!partnerFile || upload !== undefined))
  const ready = ownerReady && familyReady && !filterError
  const checked = report?.outcome === 'DRY_RUN' && reported === choice
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
    const sent = choice
    const files = choice.files as Record<Part, File>
    const names = Object.fromEntries(OWNER_FILES.map(({ part }) => [part, files[part].name])) as Record<Part, string>
    const body = importForm(files as OwnerFiles, names, partnerFile ? upload : undefined)
    const query = new URLSearchParams({ dryRun: String(dryRun) })
    if (family && ledger) {
      query.set('familyLedgerId', choice.ledgerId)
      // A member's own import names his own place (D-175).
      query.set('seatMemberId', role === 'MEMBER' ? String(ledger.memberId) : choice.seatId)
    }
    if (choice.upTo) query.set('upTo', choice.upTo)
    const answer = await api<ImportReport>(`/import?${query}`, 'POST', body)
    setReported(sent)
    setReport(answer)
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
        <div className="fields">
          <label className="field">
            <span className="label">Import up to</span>
            <input type="date" name="upTo" value={choice.upTo}
              onChange={(e) => change({ ...choice, upTo: e.target.value })} />
            <small className="hint">
              Rows of either transactions file dated after it are left out, and the check stops there. Empty: every row.
              A later import with a later date adds the newer rows.
            </small>
          </label>
        </div>
        {families && (
          <>
            <h3>Family budget</h3>
            <p className="muted">
              Your family rows go into a family budget. Its owner imports his rows with the partner’s, into the place of a
              member without an account, until someone takes that place; then each of you imports his own files.
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
              {family && role === 'OWNER' && (
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
                  {seat?.hasAccount && (
                    <small className="hint" data-testid="own-file">
                      {seat.displayName} took this place and imports their own files: only yours are uploaded.
                    </small>
                  )}
                </label>
              )}
              {family && role === 'MEMBER' && (
                <p className="muted" data-testid="member-import">
                  Your own files, into the place you took: your personal rows into your ledger, and your side of the
                  family records and settlements that came from your rows. Nobody else’s file is needed.
                </p>
              )}
              {family && members.data && role === null && (
                <p className="notice">
                  Only the budget’s owner, or a member who took a place in it, imports into this budget.
                </p>
              )}
              {partnerFile && (
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
            {partnerFile && upload && (
              <p className="muted" role="status">
                {upload.kept} of his {upload.read} rows are family rows and will be uploaded.
                {upload.withoutUnallocated > 0 && ` ${upload.withoutUnallocated} of them have UNALLOCATED on neither side.`}
              </p>
            )}
            {partnerFile && !choice.files.accounts && choice.partner && (
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
      {report.upTo && (
        <p className="muted" data-testid="after-cutoff">
          Imported up to {report.upTo}. After it, neither imported nor missing: {report.afterCutoff.map((c) =>
            `${rows(c.afterCutoff)} of ${c.rows} ${c.source === 'PARTNER' ? 'of the partner’s' : 'of yours'}`).join(', ')}.
        </p>
      )}
      {report.period && (
        <p className="muted">
          Rows from {report.period.from} to {report.period.to}, in {report.currencies.join(', ')}.
          {report.family && (report.family.mode === 'MEMBER'
            ? ` Family budget “${report.family.ledgerName}”, your own place.`
            : ` Family budget “${report.family.ledgerName}”, the partner as “${report.family.seatName}”.`)}
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

      {report.member && <MemberRows member={report.member} />}
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
      {r.familyDebtUpTo && (
        <p className="muted" data-testid="waiting">
          The family debt is compared up to {r.familyDebtUpTo}, the other member’s latest import; after it, waiting for
          the other member’s import ({r.waiting?.length ?? 0} month-ends differ for now), not failed.
        </p>
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

/** A member's own import: his family rows, settlements and benefit rows (D-178 to D-181, D-188). */
function MemberRows({ member: m }: { member: ImportMemberSummary }) {
  const differing = m.benefitMonths.filter((b) => b.differs)
  return (
    <section className="member-import" data-testid="member-summary">
      <h3>Your family rows</h3>
      <table className="import-totals">
        <tbody>
          <tr><td>Family records as imported before</td><td className="amount">{m.recordsUnchanged}</td></tr>
          <tr><td>Family records changed</td><td className="amount">{m.recordsEdited.length}</td></tr>
          <tr><td>Family records new</td><td className="amount">{m.recordsCreated}</td></tr>
          <tr><td>Your payment side attached</td><td className="amount">{m.sidesAttached}</td></tr>
          <tr><td>Your side as imported before</td><td className="amount">{m.sidesUnchanged}</td></tr>
          <tr><td>Settlements matched: same date and amount</td><td className="amount">{m.tier1}</td></tr>
          <tr><td>Settlements matched: several on the date</td><td className="amount">{m.tier2}</td></tr>
          <tr><td>Settlements matched: within 7 days</td><td className="amount">{m.tier3}</td></tr>
          <tr><td>Settlements matched: several of your rows on the date</td><td className="amount">{m.tier2Many}</td></tr>
          <tr><td>Conversions matched</td><td className="amount">{m.conversionsMatched} of {m.conversions}</td></tr>
          <tr><td>Your rows without a counterpart</td><td className="amount">{m.unmatched}</td></tr>
          <tr><td>The other member’s settlements without your side</td><td className="amount">{m.ownerSettlementsWithoutSide}</td></tr>
          <tr><td>Benefit rows checked</td><td className="amount">{m.benefitRows}</td></tr>
        </tbody>
      </table>
      {m.recordsEdited.length > 0 && <p className="muted">Changed, by Excel ID: {m.recordsEdited.join(', ')}.</p>}
      <p className={differing.length === 0 ? 'muted' : 'notice'}>
        Benefit months: {m.benefitMonths.length} compared with your shares of the incomes the others received,
        {' '}{differing.length === 0 ? 'none differs' : `${differing.length} differ`}.
      </p>
      {m.straddling.length > 0 && (
        <p className="muted">
          {m.straddling.length} matched settlement{m.straddling.length === 1 ? '' : 's'} on either side of a month-end:
          explained in the check.
        </p>
      )}
    </section>
  )
}

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
