import { useState } from 'react'
import { Link } from 'react-router'
import {
  api, useApi, useMutation, type FamilyLedger, type FamilyMember, type ImportMemberSummary, type ImportMessageCount,
  type ImportReconciliation, type ImportReport, type ImportRowRef, type ImportSync,
} from './api'
import { Errors } from './components'
import {
  fileFacts, filterPartnerRows, importForm, unallocatedName, type FileFacts, type PartnerUpload,
} from './importFilter'

const SOURCES: Record<string, string> = {
  OWNER_PERSONAL: 'Your personal rows', OWNER_FAMILY: 'Your family rows', OWNER_SETTLEMENT: 'Your settlements',
  OWNER_SEAT: 'Your rows on the family debt', PARTNER: 'The partner’s family rows', OWNER: 'Your rows',
  MEMBER_PERSONAL: 'Your personal rows', MEMBER_FAMILY: 'Your family rows', MEMBER_SETTLEMENT: 'Your settlements',
  MEMBER_BENEFIT: 'Your rows on the family debt', MEMBER: 'Your rows', APP: 'Rows the app made',
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

interface Choice { file?: File; ledgerId: string; seatId: string; partner?: File; upTo: string }

/**
 * Imports your transactions file (D3b, D4b): the Excel ledger's or this app's export, its 32 columns (D-235), its
 * accounts and categories taken from its rows (D-228). Your personal rows go into your ledger, and, with a family
 * budget and the partner’s seat (D-141), the family rows into the budget. For a place without an account the partner’s
 * file is filtered here, in the browser: only his family rows, and only what the import needs, are uploaded (D-140). A
 * dry run first, which writes everything, checks the ledger against the file, reports and rolls back; then, if no row
 * has an error, the same file for real. Running it again recognises the rows by their IDs: nothing is written twice.
 * “Import up to” leaves out the rows dated after it, and the check stops there (D-165); Commit is only for the files,
 * the budget and the date its dry run read, and once they are committed, not again until a file or the date changes
 * (D3e). Your file's rows, highest ID and last date are shown as soon as it is chosen, before any check (D-221, D-257).
 */
export default function Import({ families }: { families?: FamilyLedger[] }) {
  const [choice, setChoice] = useState<Choice>({ ledgerId: '', seatId: '', upTo: '' })
  // The choice the report is about: Commit only for exactly what the dry run read.
  const [reported, setReported] = useState<Choice>()
  const [upload, setUpload] = useState<PartnerUpload>()
  const [filterError, setFilterError] = useState<string>()
  const [report, setReport] = useState<ImportReport>()
  // The choice last committed: Commit stays off for it until a file or the date changes (D3e).
  const [committed, setCommitted] = useState<Choice>()
  // D-221, D-257: the file's rows as the import counts them (every line with a value, less the header), its highest
  // ID and its last row date.
  const [facts, setFacts] = useState<FileFacts>()
  const { run, error, pending } = useMutation()
  const members = useApi<FamilyMember[]>(choice.ledgerId ? `/family-ledgers/${choice.ledgerId}/members` : null)
  const ledger = families?.find((f) => String(f.id) === choice.ledgerId)
  const role = importRole(ledger, members.data)
  const seats = ledger && members.data ? partnerPlaces(ledger, members.data) : []
  const seat = seats.find((m) => String(m.id) === choice.seatId)
  // A place with an account imports its own file: the owner's import takes no partner's file then (D-175).
  const partnerFile = role === 'OWNER' && seat !== undefined && !seat.hasAccount

  const family = choice.ledgerId !== ''
  const ownerReady = choice.file !== undefined
  const familyReady = !family || role === 'MEMBER'
    || (role === 'OWNER' && seat !== undefined && (!partnerFile || upload !== undefined))
  const ready = ownerReady && familyReady && !filterError
  const checked = report?.outcome === 'DRY_RUN' && reported === choice
  const committedAlready = committed === choice
  const canCommit = checked && report.errors.length === 0 && !pending && !committedAlready

  function change(next: Choice) {
    setChoice(next)
    setReport(undefined) // a check is only good for the files and the budget it read
  }

  async function filter(next: Choice) {
    setUpload(undefined)
    setFilterError(undefined)
    if (!next.partner || !next.file) return
    try {
      const unallocated = unallocatedName(await next.file.text())
      if (!unallocated) throw new Error('Your transactions file has no row on UNALLOCATED, whose name the filter needs.')
      setUpload(filterPartnerRows(await next.partner.text(), unallocated))
    } catch (e) {
      setFilterError(e instanceof Error ? e.message : String(e))
    }
  }

  function chooseFile(file: File | undefined) {
    const next = { ...choice, file }
    change(next)
    void filter(next)
    void readFacts(file)
  }

  async function readFacts(file: File | undefined) {
    setFacts(undefined)
    if (!file) return
    setFacts(fileFacts(await file.text()))
  }

  function choosePartner(file: File | undefined) {
    const next = { ...choice, partner: file }
    change(next)
    void filter(next)
  }

  const send = (dryRun: boolean) => run(async () => {
    const sent = choice
    const file = choice.file as File
    const body = importForm(file, file.name, partnerFile ? upload : undefined)
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
    if (answer.outcome === 'COMMITTED') setCommitted(sent)
  })

  return (
    <>
      <h2>Import</h2>
      <p className="muted">
        Import your transactions file: the Excel ledger’s CSV export, or this app’s own (<Link to="/export">Export</Link>).
        {' '}<strong>Check</strong> runs the whole import, checks your ledger against the file, and then undoes it, so you
        can see what it would do. <strong>Commit</strong> saves it, all or nothing. Rows imported before are recognised by
        their IDs and never written twice.
      </p>
      <form className="import" onSubmit={(e) => { e.preventDefault(); void send(true) }}>
        <h3>Your file</h3>
        <div className="fields">
          <label className="field">
            <span className="label">Transactions</span>
            <input type="file" accept=".csv,text/csv" required name="transactions"
              onChange={(e) => chooseFile(e.target.files?.[0])} />
            <small className="hint">
              Your journal with its 32 columns, ID to EXP_ITEM_CODE: its accounts and categories come from its rows.
            </small>
          </label>
        </div>
        {facts !== undefined && (
          <p className="muted" data-testid="file-rows">
            Your transactions file: {rows(facts.rows)}, counted as the import counts them
            {facts.highestId !== null && <>; highest ID {facts.highestId}</>}
            {facts.lastDate !== null && <>; last row dated {facts.lastDate}</>}.
          </p>
        )}
        {facts !== undefined && !facts.header && (
          <p className="notice" data-testid="not-transactions">
            This file’s header isn’t a transactions file’s: the import will refuse it.
          </p>
        )}
        <div className="fields">
          <label className="field">
            <span className="label">Import up to</span>
            <input type="date" name="upTo" value={choice.upTo}
              onChange={(e) => change({ ...choice, upTo: e.target.value })} />
            <small className="hint">
              Rows dated after it are left out, and the check stops there. Empty: every row. A later import with a later
              date adds the newer rows.
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
            {partnerFile && !choice.file && choice.partner && (
              <p className="muted">Choose your transactions file too: it names UNALLOCATED, which the filter needs.</p>
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
          {committedAlready && (
            <span className="muted" data-testid="committed-already">
              These files are committed. Commit is off until a file or the date changes.
            </span>
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
      <h3 data-testid="report-kind" className={report.outcome === 'COMMITTED' ? 'success' : ''}>
        {report.outcome === 'DRY_RUN' ? 'Check (dry run): nothing was saved'
          : report.outcome === 'COMMITTED' ? 'Commit: the import was saved'
            : 'Commit refused: nothing was saved'}
      </h3>
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
        Accounts: {refs.accountsCreated} new, {refs.accountsUpdated} renamed. Categories: {refs.categoriesCreated} new,
        {' '}{refs.categoriesUpdated} renamed; family categories: {refs.familyCategoriesCreated} new,
        {' '}{refs.categoriesMerged} of yours merged into them. Payees and people: {refs.counterpartiesCreated} new.
      </p>
      <Chart chart={report.chart} />
      {report.idGaps?.length > 0 && (
        <p className="notice" data-testid="id-gaps">
          Gaps in the file’s IDs, between its lowest and highest: {report.idGaps.join(', ')}.
        </p>
      )}

      {report.member && <MemberRows member={report.member} />}
      <Messages title="Errors" messages={report.errorSummary} className="error" open />
      <Messages title="Warnings" messages={report.warningSummary} />
      <Messages title="Skipped and not imported" messages={report.skippedSummary} />
      <RowRefs title="Conflicts: changed or deleted in the app, left as they are" refs={report.conflicts} />
      <RowRefs title="No longer in the files: reported, nothing deleted" refs={report.missing} />
      {report.sync && <SyncGroups sync={report.sync} />}
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
  if (!r) return <p className="notice">No check: the file has no rows to compare.</p>
  const failing = r.lines.filter((l) => l.failed > 0)
  return (
    <section className="reconciliation">
      <h3>Your ledger against the file</h3>
      <p className={r.passed ? 'success' : 'error-box'} role={r.passed ? 'status' : 'alert'}>
        {r.passed ? 'Passed' : 'Failed'}: {r.pointsCompared} month-end balances compared, from {r.from ?? '–'} to
        {' '}{r.cutoff ?? '–'}, as movements since the day before the file’s first row; {r.rows.rows} rows checked,
        {' '}{r.rows.failed} failing.
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
      {r.rows.failures.length > 0 && (
        <details open data-testid="row-failures">
          <summary className="error">Rows that don’t add up ({r.rows.failed})</summary>
          <table>
            <thead><tr><th>ID</th><th>What</th><th className="amount">File</th><th className="amount">Import</th>
              <th className="amount">Unexplained</th></tr></thead>
            <tbody>
              {r.rows.failures.map((d) => (
                <tr key={`${d.excelId} ${d.what}`}>
                  <td>{d.excelId}</td><td>{d.what}</td><td className="amount">{d.file ?? ''}</td>
                  <td className="amount">{d.computed ?? ''}</td><td className="amount">{d.unexplained ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      )}
      <p className="muted">
        The family debt is compared together with the equity, per currency: another member’s records move both by your
        share, the other way round.
      </p>
      <details>
        <summary>Every account and currency ({r.lines.length})</summary>
        <table>
          <thead>
            <tr><th>Account</th><th className="amount">File</th><th className="amount">App</th>
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
    </section>
  )
}

/** D-228, D-254: the accounts and categories the import creates, and those it renames to the file's names. */
function Chart({ chart }: { chart?: ImportReport['chart'] }) {
  if (!chart || (chart.created.length === 0 && chart.renamed.length === 0)) return null
  return (
    <details data-testid="chart">
      <summary>Accounts and categories: {chart.created.length} new, {chart.renamed.length} renamed</summary>
      <ul>
        {chart.created.map((c) => <li key={`new ${c.kind} ${c.code}`}>New {kind(c.kind)} {c.code} “{c.name}” ({c.type})</li>)}
        {chart.renamed.map((r) => (
          <li key={`renamed ${r.kind} ${r.code}`}>Renamed {kind(r.kind)} {r.code}: “{r.from}” to “{r.to}”</li>
        ))}
      </ul>
    </details>
  )
}

const kind = (k: string) => (k === 'ACCOUNT' ? 'account' : k === 'FAMILY_CATEGORY' ? 'family category' : 'category')

const rows = (n: number) => (n === 1 ? '1 row' : `${n} rows`)

/** D-219, D-223 (D3e): rows an earlier import wrote that are neither plainly unchanged nor updated. None stops a commit. */
function SyncGroups({ sync }: { sync: ImportSync }) {
  return (
    <section className="sync-groups" data-testid="sync-groups">
      <RowRefs title="Changed in the app, the file unchanged: left as they are" refs={sync.appEdited} />
      {sync.appEdits.length > 0 && (
        <table data-testid="app-edits">
          <thead><tr><th>Excel ID</th><th>Date</th><th>Account</th><th className="amount">Explained as APP_EDITED</th></tr></thead>
          <tbody>
            {sync.appEdits.map((e) => (
              <tr key={`${e.excelId} ${e.account} ${e.currency}`}>
                <td>{e.excelId}</td><td>{e.date}</td><td>{e.account} {e.currency}</td><td className="amount">{e.amount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <RowRefs title="Re-planned: the current code maps them differently, written over" refs={sync.replanned} />
      <RowRefs title="As the ledger holds them, under an old hash: the hash stored again" refs={sync.restamped} />
      <RowRefs title="Can’t be re-planned after the claim: listed, never written" refs={sync.notReplannable} />
      {(sync.appRowsUnchanged ?? 0) > 0 && (
        <p className="muted" data-testid="app-rows-unchanged">
          Rows the app made, as your ledger holds them: {sync.appRowsUnchanged}.
        </p>
      )}
      <RowRefs title="Rows the app made that aren’t as your ledger holds them: listed, never written"
        refs={sync.appRows ?? []} />
      <p className="muted" data-testid="categories-merged">
        Personal categories merged into the family budget’s: {sync.categoriesMerged.length === 0 ? 'none'
          : sync.categoriesMerged.join(', ')}.
      </p>
    </section>
  )
}

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
          <tr><td>Of these, matched by their date as a whole</td><td className="amount">{m.byDate}</td></tr>
          <tr><td>Your rows ambiguous on their date</td><td className="amount">{m.ambiguous}</td></tr>
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
