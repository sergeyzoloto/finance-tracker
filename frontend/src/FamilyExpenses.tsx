import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import {
  api, ApiError, formatDate, formatInstant, useApi, type Account, type Category, type FamilyJournalPage,
  type FamilyRecord, type FamilyRecordPage, type YourPayment,
} from './api'
import { basisPointsToPercent } from './basisPoints'
import { AccountSelect, CategorySelect, Errors, Field, Loading } from './components'
import {
  expenseProblems, formFromRecord, paymentAccounts, previewSplit, sharers, splitRequest, type SplitContext,
  type SplitForm,
} from './expenseForm'
import { useFamilyApi, useFamilyMutation, type FamilyData } from './familyData'
import { JournalList } from './FamilyJournal'
import { SplitEditor } from './FamilySplit'
import { fromMinor, parseMinor, toMinor } from './minorUnits'
import { formatMoney } from './money'
import { basisPointsOf } from './shareSplit'

const PAGE_SIZE = 20

/**
 * Expenses in a table that fits a phone: the date; the category, who paid and a frozen mark; the amount and the
 * reader's share.
 */
export function ExpenseTable({ records, family }: { records: FamilyRecord[]; family: FamilyData }) {
  const navigate = useNavigate()
  return (
    <table className="entries expenses">
      <thead>
        <tr><th scope="col">Date</th><th scope="col">Expense</th><th scope="col" className="amount">Amount</th></tr>
      </thead>
      <tbody>
        {records.map((r) => {
          const yours = r.shares.find((s) => s.member.memberId === family.ledger.memberId)
          const to = `${family.page}/expenses/${r.id}`
          return (
            <tr key={r.id} className="clickable" onClick={() => navigate(to)}>
              <td className="nowrap">{formatDate(r.date)}</td>
              <td>
                <Link to={to} onClick={(e) => e.stopPropagation()}>{r.category.name}</Link>
                {r.frozen && <span className="badge" title="A member it involves has left; nobody can change it">Frozen</span>}
                <div className="small muted">Paid by {r.payer.memberId === family.ledger.memberId ? 'you' : r.payer.displayName}</div>
              </td>
              <td className="amount nowrap">
                {formatMoney(r.amount, r.currency)}
                <div className="small muted">Yours {yours ? formatMoney(yours.amount, r.currency) : '—'}</div>
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

/** The family budget's expenses, newest first, page by page (the page in the URL). */
export function FamilyExpenses({ family }: { family: FamilyData }) {
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const records = useFamilyApi<FamilyRecordPage>(family, `${family.path}/records?page=${page}&size=${PAGE_SIZE}`)
  const data = records.data
  const setPage = (p: number) => setParams(p === 0 ? {} : { page: String(p) })
  return (
    <section>
      <div className="page-title">
        <h3>Expenses</h3>
        <Link className="button primary" to={`${family.page}/expenses/new`}>Add an expense</Link>
      </div>
      <Errors messages={[records.error]} />
      {!data && !records.error && <Loading what="expenses" />}
      {data && data.totalElements === 0 && <p className="empty">No expenses yet.</p>}
      {data && data.content.length > 0 && (
        <>
          <ExpenseTable records={data.content} family={family} />
          {data.totalPages > 1 && (
            <nav className="pager" aria-label="Pages">
              <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>← Newer</button>
              <span>{data.page * data.size + 1}–{data.page * data.size + data.content.length} of {data.totalElements}</span>
              <button type="button" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Older →</button>
            </nav>
          )}
        </>
      )}
      {data && data.content.length === 0 && page > 0 && (
        <button type="button" onClick={() => setPage(0)}>Back to the first page</button>
      )}
    </section>
  )
}

/**
 * One expense at `/family/{ledgerId}/expenses/{recordId}`: every field, the shares with their amounts and
 * percentages, and its journal. Its author and the owners change the category, the split and the comment; the payer
 * with an account changes the date, the amount, the payer and the account they paid with, and deletes it, or for a
 * payer without an account its author or an owner (D-14, F4c). A stale version shows the server's message and the
 * expense as it is now.
 */
export function ExpenseDetail({ family }: { family: FamilyData }) {
  const { recordId = '' } = useParams()
  const navigate = useNavigate()
  const valid = /^[1-9]\d{0,17}$/.test(recordId)
  const record = useFamilyApi<FamilyRecord>(family, valid ? `${family.path}/records/${recordId}` : null)
  const journal = useApi<FamilyJournalPage>(valid ? `${family.path}/journal?recordId=${recordId}&size=200` : null)
  const [saved, setSaved] = useState(false)
  const change = useFamilyMutation(family, () => { record.reload(); journal.reload() })
  // Deleting leaves the page; nothing is loaded again after it.
  const removal = useFamilyMutation(family)

  // A stale version, or a record that changed in some other way meanwhile: show it as it is now.
  const failure = change.failure ?? removal.failure
  useEffect(() => {
    if (failure instanceof ApiError && failure.status === 409) {
      record.reload()
      journal.reload()
    }
  }, [failure]) // only when a new failure arrives

  const back = <p><Link to={`${family.page}/expenses`}>← All expenses</Link></p>
  if (!valid || record.status === 404) {
    return (
      <section>
        {back}
        <h3>Expense not found</h3>
        <p>This expense doesn’t exist, or it was deleted. The journal still shows what happened to it.</p>
      </section>
    )
  }
  if (!record.data) return <>{back}{record.error ? <Errors messages={[record.error]} /> : <Loading what="the expense" />}</>
  const r = record.data
  const mine = r.payer.memberId === family.ledger.memberId
  const payerHasAccount = family.members.some((m) => m.id === r.payer.memberId && m.hasAccount)

  function remove() {
    if (!confirm(`Delete “${r.category.name}” of ${formatDate(r.date)}? Its shares and payment go with it.`)) return
    setSaved(false)
    change.clear()
    void removal.run(async () => {
      await api(`${family.path}/records/${r.id}?version=${r.version}`, 'DELETE')
      navigate(`${family.page}/expenses`)
    })
  }

  const amount = toMinor(r.amount, r.currency) ?? 0n
  // The members the split editor may show: who shares the date, and who has a share in a custom rule.
  const rowIds = [...sharers(family.members, r.date), ...family.members.filter((m) => m.status === 'ACTIVE' && m.share !== null)]
    .map((m) => m.id)
  const problems = expenseProblems(failure, rowIds, r.payer.memberId)
  return (
    <section>
      {back}
      <div className="page-title">
        <h3>{r.category.name}, {formatDate(r.date)}</h3>
        {r.frozen && <span className="badge">Frozen</span>}
      </div>
      {r.frozen && (
        <p className="notice">A member this expense involves has left the family budget or deleted their data, so nobody can change it.</p>
      )}
      <dl className="facts">
        <dt>Date</dt><dd>{formatDate(r.date)}</dd>
        <dt>Category</dt><dd>{r.category.name}{r.category.archived && <span className="badge">Archived</span>}</dd>
        <dt>Amount</dt><dd>{formatMoney(r.amount, r.currency)}</dd>
        <dt>Paid by</dt><dd>{mine ? `${r.payer.displayName} (you)` : r.payer.displayName}</dd>
        {r.yourPayment && <PaidFrom payment={r.yourPayment} />}
        <dt>Comment</dt><dd>{r.comment ?? <span className="muted">None</span>}</dd>
        <dt>Added</dt><dd>by {r.author.displayName}, {formatInstant(r.createdAt)}</dd>
        {r.updatedAt !== r.createdAt && <><dt>Last changed</dt><dd>by {r.updatedBy.displayName}, {formatInstant(r.updatedAt)}</dd></>}
      </dl>

      <h4>Shares</h4>
      <table className="shares">
        <thead><tr><th scope="col">Member</th><th scope="col" className="amount">Share</th><th scope="col" className="amount">Percent</th></tr></thead>
        <tbody>
          {r.shares.map((s) => (
            <tr key={s.member.memberId}>
              <th scope="row">
                {s.member.displayName}
                {s.member.memberId === family.ledger.memberId && <span className="badge">You</span>}
                <small className="muted block">changed by {s.updatedBy.displayName}, {formatInstant(s.updatedAt)}</small>
              </th>
              <td className="amount nowrap">{formatMoney(s.amount, r.currency)}</td>
              <td className="amount nowrap">
                {basisPointsToPercent(s.basisPoints ?? basisPointsOf(toMinor(s.amount, r.currency) ?? 0n, amount))} %
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot><tr><th scope="row">Total</th><td className="amount nowrap">{formatMoney(r.amount, r.currency)}</td><td /></tr></tfoot>
      </table>

      {(r.canEdit || r.canEditPayment) && (
        <EditExpense key={`${r.version}:${r.yourPayment?.accountId}:${r.yourPayment?.later}`} record={r} family={family}
          problems={problems} pending={change.pending}
          onSave={(patch) => {
            setSaved(false)
            removal.clear()
            void change.run(async () => {
              await api(`${family.path}/records/${r.id}?version=${r.version}`, 'PATCH', patch)
              setSaved(true)
            })
          }} />
      )}
      {!r.canEdit && !r.frozen && (
        <p className="muted small">
          Only the expense’s author, {r.author.displayName}, or an owner of the family budget changes its category,
          split and comment.
        </p>
      )}
      {!r.canEditPayment && !r.frozen && payerHasAccount && (
        <p className="muted small">Only {r.payer.displayName}, who paid it, changes its date, amount and payer.</p>
      )}
      {saved && <p className="success" role="status">Saved.</p>}
      <Errors messages={problems.other} />
      {r.canDelete && (
        <div className="actions">
          <button type="button" className="danger" disabled={change.pending || removal.pending} onClick={remove}>Delete the expense</button>
        </div>
      )}

      <h4>Changes</h4>
      <Errors messages={[journal.error]} />
      {journal.data && <JournalList changes={journal.data.content} family={family} links={false} />}
    </section>
  )
}

/**
 * The account the user paid with, for the payer's eyes only: the other members never see it (D-16). The record's
 * answer carries it for the payer alone (`yourPayment`), with their payment entry in their own ledger.
 */
function PaidFrom({ payment }: { payment: YourPayment }) {
  return (
    <>
      <dt>Paid from</dt>
      <dd>
        <Link to={`/entries/${payment.entryId}`}>{payment.later ? 'Specify later' : payment.accountName}</Link>
        <small className="muted block">
          {payment.later ? 'Kept under “Payments without a specified account” until you choose the account. ' : ''}
          Only you see which account you paid with.
        </small>
      </dd>
    </>
  )
}

/** A PATCH of a record: only what changed. */
type RecordPatch = {
  categoryId?: number; comment?: string | null; split?: unknown
  date?: string; amount?: string; payerMemberId?: number; paymentAccountId?: number; paymentLater?: true
}

const LATER = 'later'

/**
 * What the reader may change of an expense (D-14), with the version it was read at: the payment fields (the date, the
 * amount, the payer, and the account the reader paid with, or "Specify later") when `canEditPayment`, and its category,
 * split and comment when `canEdit`. Only what changed is sent. A new amount, date or payer is split again by the
 * expense's split on the server; one split by amounts needs the new amounts with a new amount.
 */
function EditExpense({ record, family, problems, pending, onSave }: {
  record: FamilyRecord
  family: FamilyData
  problems: ReturnType<typeof expenseProblems>
  pending: boolean
  onSave: (patch: RecordPatch) => void
}) {
  const categories = useApi<Category[]>(record.canEdit ? `${family.path}/categories` : null)
  const accounts = useApi<Account[]>(record.canEditPayment ? '/accounts' : null)
  const { ledger, members } = family
  const currency = record.currency
  const me = ledger.memberId
  const initial = formFromRecord(record, ledger, members)
  const initialPayment = record.yourPayment ? (record.yourPayment.later ? LATER : String(record.yourPayment.accountId)) : ''
  const [date, setDate] = useState(record.date)
  const [amountText, setAmountText] = useState(record.amount)
  const [payer, setPayer] = useState(String(record.payer.memberId))
  const [payment, setPayment] = useState(initialPayment)
  const [categoryId, setCategoryId] = useState(String(record.category.id))
  const [split, setSplit] = useState<SplitForm>(initial)
  const [comment, setComment] = useState(record.comment ?? '')

  const recordAmount = toMinor(record.amount, currency)
  const parsed = parseMinor(amountText, currency)
  const amount = 'minor' in parsed ? parsed.minor : undefined
  const payerId = Number(payer)
  const context: SplitContext = { ledger, members, date, amount, payerId }
  const preview = previewSplit(split, context, currency)
  const initialContext: SplitContext = { ledger, members, date: record.date, amount: recordAmount, payerId: record.payer.memberId }
  const splitChanged = JSON.stringify(splitRequest(split, preview, currency))
    !== JSON.stringify(splitRequest(initial, previewSplit(initial, initialContext, currency), currency))
  const amountChanged = amount !== undefined && amount !== recordAmount
  const payerChanged = payerId !== record.payer.memberId
  const payerIsMe = payerId === me
  // A new amount of an expense split by amounts needs the new amounts; any other split follows on the server.
  const needsAmounts = record.splitMethod === 'AMOUNT' && amountChanged && !splitChanged
  const patch: RecordPatch = {
    ...(date !== record.date ? { date } : {}),
    ...(amountChanged ? { amount: fromMinor(amount, currency) } : {}),
    ...(payerChanged ? { payerMemberId: payerId } : {}),
    ...(payerIsMe && payment !== '' && (payerChanged || payment !== initialPayment)
      ? (payment === LATER ? { paymentLater: true as const } : { paymentAccountId: Number(payment) }) : {}),
    ...(categoryId !== String(record.category.id) ? { categoryId: Number(categoryId) } : {}),
    ...(comment.trim() !== (record.comment ?? '') ? { comment: comment.trim() || null } : {}),
    ...(splitChanged ? { split: splitRequest(split, preview, currency) } : {}),
  }
  const changed = Object.keys(patch).length > 0
  const dateProblem = date === '' ? 'Enter a date.'
    : date < ledger.startDate ? `The family budget starts on ${formatDate(ledger.startDate)}; an expense can’t be earlier.` : undefined
  const splitProblems = splitChanged || needsAmounts ? preview.problems : []
  const ready = changed && !dateProblem && amount !== undefined && (!payerIsMe || payment !== '')
    && splitProblems.length === 0 && !needsAmounts
  const followsSplit = !splitChanged && (amountChanged || date !== record.date || payerChanged)

  function submit(event: FormEvent) {
    event.preventDefault()
    if (ready) onSave(patch)
  }

  function undo() {
    setDate(record.date); setAmountText(record.amount); setPayer(String(record.payer.memberId)); setPayment(initialPayment)
    setCategoryId(String(record.category.id)); setSplit(initial); setComment(record.comment ?? '')
  }

  const current: Category = { ...record.category, type: 'EXPENSE' }
  const shown = (categories.data ?? [current]).filter((c) => c.type === 'EXPENSE')
  // Who may pay: the reader, or a member without an account (D-14); and whoever pays it now.
  const payers = members.filter((m) => (m.status === 'ACTIVE' && (m.id === me || !m.hasAccount)) || m.id === record.payer.memberId)
  const choices = paymentAccounts(accounts.data ?? [])
  return (
    <form className="family-form expense-form" onSubmit={submit}>
      <h4>Change this expense</h4>
      <div className="fields">
        {record.canEditPayment && (
          <>
            <Field label="Date" errors={[...(dateProblem ? [dateProblem] : []), ...problems.date]}>
              <input type="date" value={date} min={ledger.startDate} required onChange={(e) => setDate(e.target.value)} />
            </Field>
            <Field label={`Amount (${currency})`}
              errors={[...(amountText.trim() !== '' && 'problem' in parsed ? [parsed.problem] : []), ...problems.amount]}>
              <input className="amount" inputMode="decimal" value={amountText} autoComplete="off"
                onChange={(e) => setAmountText(e.target.value)} />
            </Field>
            <Field label="Paid by" errors={problems.payer}>
              <select value={payer} onChange={(e) => setPayer(e.target.value)}>
                {payers.map((m) => <option key={m.id} value={m.id}>{m.id === me ? `${m.displayName} (you)` : m.displayName}</option>)}
              </select>
            </Field>
            {payerIsMe && (
              <Field label="Paid from" errors={problems.payment}
                hint="Only you see it. “Specify later” keeps the payment under “Payments without a specified account”.">
                <AccountSelect accounts={choices} value={payment} onChange={setPayment}>
                  <option value={LATER}>Specify later</option>
                </AccountSelect>
              </Field>
            )}
          </>
        )}
        {record.canEdit && (
          <>
            <Field label="Category" errors={problems.category}>
              <CategorySelect categories={shown} type="EXPENSE" value={categoryId} onChange={setCategoryId} />
            </Field>
            <Field label="Comment (optional)" errors={problems.comment} className="wide">
              <input value={comment} maxLength={500} onChange={(e) => setComment(e.target.value)} />
            </Field>
          </>
        )}
      </div>
      {(record.canEdit || record.splitMethod === 'AMOUNT') && (
        <SplitEditor form={split} preview={{ ...preview, problems: splitProblems }} context={context} currency={currency}
          onChange={setSplit} byMember={problems.byMember} problems={problems.split} you={me} />
      )}
      {needsAmounts && <p className="error small" role="alert">This expense is split by amounts: enter the new amounts with the new amount.</p>}
      {followsSplit && <p className="muted small">When you save, the shares are split again as the expense is split now.</p>}
      <div className="actions">
        <button className="primary" disabled={!ready || pending}>Save the changes</button>
        {changed && <button type="button" onClick={undo}>Undo</button>}
      </div>
    </form>
  )
}
