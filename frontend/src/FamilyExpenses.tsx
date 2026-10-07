import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import {
  api, ApiError, formatDate, formatInstant, useApi, type Account, type Category, type Counterparty, type FamilyJournalPage,
  type FamilyRecord, type FamilyRecordPage, type YourPayment,
} from './api'
import { basisPointsToPercent } from './basisPoints'
import { AccountSelect, CategorySelect, Errors, Field, Loading } from './components'
import {
  expenseProblems, formFromRecord, inOpeningBalance, paymentAccounts, previewSplit, sharers, splitRequest,
  type SplitContext, type SplitForm,
} from './expenseForm'
import { newPayingSide, recordAmount, sidePatch, type PayingSideForm } from './currency'
import { CounterpartyFields } from './FamilyCounterparties'
import { CurrencyField, currencySuggestions, PayingSideFields } from './FamilyCurrency'
import { OpeningBalanceNote } from './FamilyExpenseForm'
import { RECORD_NOUNS, recordTitle, recordWho, settlementSentence, shownAmount } from './family'
import { useFamilyApi, useFamilyMutation, type FamilyData } from './familyData'
import { isRecordPage } from './guards'
import { JournalList } from './FamilyJournal'
import { LATER, sideLabel } from './FamilySettlement'
import { SplitEditor } from './FamilySplit'
import { fromMinor, parseMinor, toMinor } from './minorUnits'
import { formatMoney } from './money'
import { basisPointsOf } from './shareSplit'

const PAGE_SIZE = 20

/**
 * Expenses, incomes and settlements in a table that fits a phone: the date; what it is (the category, or
 * "Settlement"), who paid or received it, "Sam paid you €36.20" for a settlement, and a frozen mark; the amount and
 * the reader's share.
 */
export function RecordTable({ records, family }: { records: FamilyRecord[]; family: FamilyData }) {
  const navigate = useNavigate()
  const me = family.ledger.memberId
  return (
    <table className="entries expenses">
      <thead>
        <tr><th scope="col">Date</th><th scope="col">What</th><th scope="col" className="amount">Amount</th></tr>
      </thead>
      <tbody>
        {records.map((r) => {
          const yours = r.shares.find((s) => s.member.memberId === me)
          const to = `${family.page}/expenses/${r.id}`
          return (
            <tr key={r.id} className="clickable" onClick={() => navigate(to)}>
              <td className="nowrap">{formatDate(r.date)}</td>
              <td>
                <Link to={to} onClick={(e) => e.stopPropagation()}>{recordTitle(r)}</Link>
                {r.type === 'INCOME' && <span className="badge">Income</span>}
                {r.frozen && <span className="badge" title="A member it involves has left; nobody can change it">Frozen</span>}
                <div className="small muted">{recordWho(r, me)}</div>
              </td>
              <td className="amount nowrap">
                {recordAmount(r)}
                {r.type !== 'SETTLEMENT' && (
                  <div className="small muted">Yours {yours ? formatMoney(shownAmount(r, yours.amount), r.currency) : '—'}</div>
                )}
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

/** The actions that record something new in the family budget: an expense, an income, a settlement. */
export function AddButtons({ family }: { family: FamilyData }) {
  return (
    <div className="actions add-records">
      <Link className="button primary" to={`${family.page}/expenses/new`}>Add an expense</Link>
      <Link className="button" to={`${family.page}/incomes/new`}>Add an income</Link>
      <Link className="button" to={`${family.page}/settle`}>Record a settlement</Link>
    </div>
  )
}

/** The family budget's expenses, incomes and settlements, newest first, page by page (the page in the URL). */
export function FamilyRecords({ family }: { family: FamilyData }) {
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const records = useFamilyApi<FamilyRecordPage>(family, `${family.path}/records?page=${page}&size=${PAGE_SIZE}`, isRecordPage)
  const data = records.data
  const setPage = (p: number) => setParams(p === 0 ? {} : { page: String(p) })
  return (
    <section>
      <h3>Activity</h3>
      <p className="muted">Expenses, incomes and settlements, newest first.</p>
      <AddButtons family={family} />
      <Errors messages={[records.error]} />
      {!data && !records.error && <Loading what="the activity" />}
      {data && data.totalElements === 0 && <p className="empty">Nothing recorded yet.</p>}
      {data && data.content.length > 0 && (
        <>
          <RecordTable records={data.content} family={family} />
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
 * One expense, income or settlement at `/family/{ledgerId}/expenses/{recordId}`: every field, the shares with their
 * amounts and percentages, and its journal. For an expense or an income, its author and the owners change the
 * category, the split and the comment; its payer or receiver with an account changes the date, the amount, who paid
 * or received it and their own account, and deletes it, or for one without an account its author or an owner (D-14,
 * F4c). A settlement's recorder changes its date, amount and comment and deletes it, and each side with an account puts
 * its own side on an account (D-24). A stale version shows the server's message and the record as it is now.
 */
export function RecordDetail({ family }: { family: FamilyData }) {
  const { recordId = '' } = useParams()
  const navigate = useNavigate()
  const valid = /^[1-9]\d{0,17}$/.test(recordId)
  const record = useFamilyApi<FamilyRecord>(family, valid ? `${family.path}/records/${recordId}` : null)
  const journal = useApi<FamilyJournalPage>(valid ? `${family.path}/journal?recordId=${recordId}&size=200` : null)
  const [saved, setSaved] = useState(false)
  // The reader's own counterparty and payee, named on their record page by their own list (D-80, D-81).
  const own = record.data?.yourPayment
  const counterparties = useApi<Counterparty[]>(own?.counterpartyId !== undefined || own?.payeeId !== undefined ? '/counterparties' : null)
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

  const back = <p><Link to={`${family.page}/expenses`}>← All activity</Link></p>
  if (!valid || record.status === 404) {
    return (
      <section>
        {back}
        <h3>Not found</h3>
        <p>This expense, income or settlement doesn’t exist, or it was deleted. The journal still shows what happened to it.</p>
      </section>
    )
  }
  if (!record.data) return <>{back}{record.error ? <Errors messages={[record.error]} /> : <Loading what="the record" />}</>
  const r = record.data
  const me = family.ledger.memberId
  const noun = RECORD_NOUNS[r.type]
  const settlement = r.type === 'SETTLEMENT'
  const payerHasAccount = family.members.some((m) => m.id === r.payer.memberId && m.hasAccount)

  function remove() {
    const what = settlement
      ? `the settlement of ${formatDate(r.date)} (${settlementSentence(r, me)})? Both sides go from the personal ledgers.`
      : `“${r.category?.name}” of ${formatDate(r.date)}? Its shares and ${r.type === 'INCOME' ? 'what was received' : 'payment'} go with it.`
    if (!confirm(`Delete ${what}`)) return
    setSaved(false)
    change.clear()
    void removal.run(async () => {
      await api(`${family.path}/records/${r.id}?version=${r.version}`, 'DELETE')
      navigate(`${family.page}/expenses`)
    })
  }

  function save(patch: object) {
    setSaved(false)
    removal.clear()
    void change.run(async () => {
      await api(`${family.path}/records/${r.id}?version=${r.version}`, 'PATCH', patch)
      setSaved(true)
    })
  }

  const amount = toMinor(r.amount, r.currency) ?? 0n
  // The members the split editor may show: who shares the date, and who has a share in a custom rule.
  const rowIds = [...sharers(family.members, r.date), ...family.members.filter((m) => m.status === 'ACTIVE' && m.share !== null)]
    .map((m) => m.id)
  const problems = expenseProblems(failure, rowIds, r.payer.memberId, 'other', r.payee?.memberId ?? null)
  const editKey = `${r.version}:${r.yourPayment?.accountId}:${r.yourPayment?.later}`
  return (
    <section>
      {back}
      <div className="page-title">
        <h3>{settlement ? 'Settlement' : r.category?.name}, {formatDate(r.date)}</h3>
        {r.type === 'INCOME' && <span className="badge">Income</span>}
        {r.refund && <span className="badge">Refund</span>}
        {r.frozen && <span className="badge">Frozen</span>}
      </div>
      {r.frozen && (
        <p className="notice">A member this {noun} involves has left the family budget or deleted their data, so nobody can change it.</p>
      )}
      {settlement && <p className="sentence">{settlementSentence(r, me)}.</p>}
      <dl className="facts">
        <dt>Date</dt><dd>{formatDate(r.date)}</dd>
        {r.category && <><dt>Category</dt><dd>{r.category.name}{r.category.archived && <span className="badge">Archived</span>}</dd></>}
        <dt>Amount</dt>
        <dd>
          {recordAmount(r)}
        </dd>
        <dt>{r.type === 'INCOME' || r.refund ? 'Received by' : 'Paid by'}</dt>
        <dd>{r.payer.memberId === me ? `${r.payer.displayName} (you)` : r.payer.displayName}</dd>
        {r.payee && <><dt>Received by</dt><dd>{r.payee.memberId === me ? `${r.payee.displayName} (you)` : r.payee.displayName}</dd></>}
        {r.yourPayment && (
          <YourSide payment={r.yourPayment} currency={r.currency} counterparties={counterparties.data}
            label={settlement ? sideLabel(r.payer.memberId === me) : r.type === 'INCOME' || r.refund ? 'Received into' : 'Paid from'} />
        )}
        <dt>Comment</dt><dd>{r.comment ?? <span className="muted">None</span>}</dd>
        <dt>{settlement ? 'Recorded' : 'Added'}</dt><dd>by {r.author.displayName}, {formatInstant(r.createdAt)}</dd>
        {r.updatedAt !== r.createdAt && <><dt>Last changed</dt><dd>by {r.updatedBy.displayName}, {formatInstant(r.updatedAt)}</dd></>}
      </dl>

      {!settlement && (
        <>
          <h4>Shares</h4>
          <table className="shares">
            <thead><tr><th scope="col">Member</th><th scope="col" className="amount">Share</th><th scope="col" className="amount">Percent</th></tr></thead>
            <tbody>
              {r.shares.map((s) => (
                <tr key={s.member.memberId}>
                  <th scope="row">
                    {s.member.displayName}
                    {s.member.memberId === me && <span className="badge">You</span>}
                    <small className="muted block">changed by {s.updatedBy.displayName}, {formatInstant(s.updatedAt)}</small>
                  </th>
                  <td className="amount nowrap">{formatMoney(shownAmount(r, s.amount), r.currency)}</td>
                  <td className="amount nowrap">
                    {basisPointsToPercent(s.basisPoints ?? basisPointsOf(toMinor(s.amount, r.currency) ?? 0n, amount))} %
                  </td>
                </tr>
              ))}
            </tbody>
            <tfoot><tr><th scope="row">Total</th><td className="amount nowrap">{recordAmount(r)}</td><td /></tr></tfoot>
          </table>
        </>
      )}

      {settlement && (r.canEdit || r.canEditPayment || r.yourPayment) && !r.frozen && (
        <EditSettlement key={editKey} record={r} family={family} problems={problems} pending={change.pending} onSave={save} />
      )}
      {!settlement && (r.canEdit || r.canEditPayment) && (
        <EditRecord key={editKey} record={r} family={family} problems={problems} pending={change.pending} onSave={save} />
      )}
      {!settlement && !r.canEdit && !r.frozen && (
        <p className="muted small">
          Only the {noun}’s author, {r.author.displayName}, or an owner of the family budget changes its category,
          split and comment.
        </p>
      )}
      {!settlement && !r.canEditPayment && !r.frozen && payerHasAccount && (
        <p className="muted small">
          {r.type === 'INCOME'
            ? `Only ${r.payer.displayName}, who received it, changes its date, amount and receiver.`
            : `Only ${r.payer.displayName}, who paid it, changes its date, amount and payer.`}
        </p>
      )}
      {settlement && r.lockedBy && !r.frozen && (
        <p className="notice" role="note">
          {r.lockedBy.displayName} has put their side of this settlement on an account of theirs, so its date and amount
          can’t change and it can’t be deleted. {r.lockedBy.displayName} can move it back to “Specify later” to allow it.
        </p>
      )}
      {settlement && !r.canEdit && !r.frozen && (
        <p className="muted small">
          Only {r.author.displayName}, who recorded it, changes its date, amount and comment
          {r.yourPayment ? '; you put your own side on an account' : ''}.
        </p>
      )}
      {saved && <p className="success" role="status">Saved.</p>}
      <Errors messages={problems.other} />
      {r.canDelete && (
        <div className="actions">
          <button type="button" className="danger" disabled={change.pending || removal.pending} onClick={remove}>
            Delete the {noun}
          </button>
        </div>
      )}

      <h4>Changes</h4>
      <Errors messages={[journal.error]} />
      {journal.data && <JournalList changes={journal.data.content} family={family} links={false} />}
    </section>
  )
}

/**
 * The account of the reader's own side, for their eyes only: the other members never see it (D-16). The record's
 * answer carries it for them alone (`yourPayment`), with their entry in their own ledger.
 */
function YourSide({ payment, label, currency, counterparties }: {
  payment: YourPayment; label: string; currency: string; counterparties: Counterparty[] | undefined
}) {
  const named = (id: number | undefined) => id === undefined ? undefined
    : counterparties?.find((c) => c.id === id)?.name ?? 'a counterparty of yours'
  const counterparty = named(payment.counterpartyId)
  const payee = named(payment.payeeId)
  return (
    <>
      <dt>{label}</dt>
      <dd>
        <Link to={`/entries/${payment.entryId}`}>{payment.later ? 'Specify later' : payment.accountName}</Link>
        {payment.currency && payment.currency !== currency && <>, {formatMoney(payment.amount, payment.currency)}</>}
        {counterparty && <>, with {counterparty}</>}
        <small className="muted block">
          {payment.later ? 'Kept under “Payments without a specified account” until you choose the account. ' : ''}
          Only you see which account it is.
        </small>
      </dd>
      {payee && <><dt>Payee</dt><dd>{payee}<small className="muted block">Only you see it.</small></dd></>}
    </>
  )
}

/** A PATCH of a record: only what changed. */
type RecordPatch = {
  categoryId?: number; comment?: string | null; split?: unknown
  date?: string; amount?: string; payerMemberId?: number; paymentAccountId?: number; paymentLater?: true
  currency?: string; accountCurrency?: string; accountAmount?: string
  /** The counterparty of the account's line, when it requires one (D-80); the payee, or null to remove it (D-81). */
  paymentCounterpartyId?: number; payeeId?: number | null
}

interface EditProps {
  record: FamilyRecord
  family: FamilyData
  problems: ReturnType<typeof expenseProblems>
  pending: boolean
  onSave: (patch: RecordPatch) => void
}

/** How the reader names their own account, or "Specify later", as a patch: only when it changed. */
function accountPatch(payment: string, initial: string): RecordPatch {
  if (payment === '' || payment === initial) return {}
  return payment === LATER ? { paymentLater: true } : { paymentAccountId: Number(payment) }
}

/**
 * What the reader may change of an expense or an income (D-14), with the version it was read at: the payment fields
 * (the date, the amount and its currency, who paid or received it, and the reader's own account, or "Specify later",
 * with the currency it paid or received in, D-89) when `canEditPayment`, and its category, split and comment when
 * `canEdit`. Only what changed is sent. A new amount, date or payer is split again by the record's stored split on the
 * server, which the preview follows: equal shares among its own members (KEEP), its percentages, its member; one split
 * by amounts needs the new amounts with a new amount. The amount is the record's, in its own currency (D-45); what
 * went from or into the reader's account in another currency is asked again when the amount, the currency, the account
 * or the paying currency changes, never for a new date or comment (D-89).
 */
function EditRecord({ record, family, problems, pending, onSave }: EditProps) {
  const categories = useApi<Category[]>(record.canEdit ? `${family.path}/categories` : null)
  const accounts = useApi<Account[]>(record.canEditPayment ? '/accounts' : null)
  const counterparties = useApi<Counterparty[]>(record.canEditPayment ? '/counterparties' : null)
  const { ledger, members } = family
  const me = ledger.memberId
  const income = record.type === 'INCOME'
  // Money in, as an income's is: a refund's payer received it (D-79).
  const receiving = income || record.refund === true
  const noun = RECORD_NOUNS[record.type]
  const type = income ? 'INCOME' : 'EXPENSE'
  const initial = formFromRecord(record, members)
  const initialPayment = record.yourPayment ? (record.yourPayment.later ? LATER : String(record.yourPayment.accountId)) : ''
  const initialCategory = String(record.category?.id ?? '')
  const [date, setDate] = useState(record.date)
  const [amountText, setAmountText] = useState(record.amount)
  const [currency, setCurrency] = useState(record.currency)
  const [payer, setPayer] = useState(String(record.payer.memberId))
  const [payment, setPayment] = useState(initialPayment)
  const [side, setSide] = useState<PayingSideForm>(newPayingSide)
  const initialCounterparty = String(record.yourPayment?.counterpartyId ?? '')
  const initialPayee = String(record.yourPayment?.payeeId ?? '')
  const [counterpartyId, setCounterpartyId] = useState(initialCounterparty)
  const [payeeId, setPayeeId] = useState(initialPayee)
  const [categoryId, setCategoryId] = useState(initialCategory)
  const [split, setSplit] = useState<SplitForm>(initial)
  const [comment, setComment] = useState(record.comment ?? '')

  const recordAmount = toMinor(record.amount, record.currency)
  const payerId = Number(payer)
  const payerChanged = payerId !== record.payer.memberId
  const payerIsMe = payerId === me
  // A claimed seat's own record dated before the claim's date is in their opening balance: no account (D-32, D-35).
  const reader = members.find((m) => m.id === me)
  const opening = payerIsMe && inOpeningBalance(reader, date)
  const choices = paymentAccounts(accounts.data ?? [], !income)
  const account = payerIsMe && !opening && payment !== LATER ? choices.find((a) => String(a.id) === payment) : undefined
  const accountChanged = payerChanged || payment !== initialPayment
  const needsCounterparty = account?.requiresCounterparty === true
  const counterpartyChanged = counterpartyId !== initialCounterparty
  const ownSide = payerIsMe && !opening && !payerChanged
  // The side's paying currency while it stays on the same account (D-89).
  const kept = !accountChanged ? record.yourPayment?.currency : undefined
  const currencyValid = /^[A-Z]{3}$/.test(currency)
  const parsed = parseMinor(amountText, currencyValid ? currency : record.currency)
  const amount = 'minor' in parsed ? parsed.minor : undefined
  const currencyChanged = currency !== record.currency
  const amountChanged = currencyChanged || (amount !== undefined && amount !== recordAmount)
  const paid = sidePatch({ account, currency, side, kept, moves: amountChanged, accountChanged })
  const context: SplitContext = { ledger, members, date, amount, payerId, noun }
  const preview = previewSplit(split, context, currencyValid ? currency : record.currency)
  const initialContext: SplitContext = { ledger, members, date: record.date, amount: recordAmount, payerId: record.payer.memberId, noun }
  const request = splitRequest(split, preview, currencyValid ? currency : record.currency)
  // The stored equal split isn't sent: the server splits again by it.
  const splitChanged = request !== null
    && JSON.stringify(request) !== JSON.stringify(splitRequest(initial, previewSplit(initial, initialContext, record.currency), record.currency))
  // A new amount of a record split by amounts needs the new amounts; any other split follows on the server.
  const needsAmounts = record.splitMethod === 'AMOUNT' && amountChanged && !splitChanged
  const patch: RecordPatch = {
    ...(date !== record.date ? { date } : {}),
    ...(amountChanged && amount !== undefined ? { amount: fromMinor(amount, currency) } : {}),
    ...(currencyChanged ? { currency } : {}),
    ...(payerChanged ? { payerMemberId: payerId } : {}),
    ...(payerIsMe && !opening ? accountPatch(payment, payerChanged ? '' : initialPayment) : {}),
    ...('patch' in paid ? paid.patch : {}),
    // The counterparty goes with an account that requires one, new or changed (D-80); the payee alone (D-81).
    ...(needsCounterparty && (accountChanged || counterpartyChanged || payerChanged) && counterpartyId !== ''
      ? { paymentCounterpartyId: Number(counterpartyId) } : {}),
    ...(payerIsMe && !opening && (payeeId !== initialPayee || payerChanged && payeeId !== '')
      ? { payeeId: payeeId === '' ? null : Number(payeeId) } : {}),
    ...(categoryId !== initialCategory ? { categoryId: Number(categoryId) } : {}),
    ...(comment.trim() !== (record.comment ?? '') ? { comment: comment.trim() || null } : {}),
    ...(splitChanged ? { split: request } : {}),
  }
  const changed = Object.keys(patch).length > 0
  const dateProblem = date === '' ? 'Enter a date.'
    : date < ledger.startDate ? `The family budget starts on ${formatDate(ledger.startDate)}; an ${noun} can’t be earlier.` : undefined
  const splitProblems = splitChanged || needsAmounts || split.mode === 'KEEP' ? preview.problems : []
  const ready = changed && !dateProblem && amount !== undefined && currencyValid && (!payerIsMe || opening || payment !== '')
    && (!needsCounterparty || counterpartyId !== '') && !('problem' in paid) && splitProblems.length === 0 && !needsAmounts
  const followsSplit = !splitChanged && (amountChanged || date !== record.date || payerChanged)

  function submit(event: FormEvent) {
    event.preventDefault()
    if (ready) onSave(patch)
  }

  function undo() {
    setDate(record.date); setAmountText(record.amount); setCurrency(record.currency); setSide(newPayingSide())
    setPayer(String(record.payer.memberId)); setPayment(initialPayment)
    setCounterpartyId(initialCounterparty); setPayeeId(initialPayee)
    setCategoryId(initialCategory); setSplit(initial); setComment(record.comment ?? '')
  }

  const current: Category[] = record.category ? [{ ...record.category, type }] : []
  const shown = (categories.data ?? current).filter((c) => c.type === type)
  // Who may pay or receive it: the reader, or a member without an account (D-14); and whoever does now.
  const payers = members.filter((m) => (m.status === 'ACTIVE' && (m.id === me || !m.hasAccount)) || m.id === record.payer.memberId)
  return (
    <form className="family-form expense-form" onSubmit={submit}>
      <h4>Change this {noun}</h4>
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
            <CurrencyField value={currency} errors={problems.currency}
              suggestions={currencySuggestions(ledger.baseCurrency, choices.map((a) => a.defaultCurrency))}
              onChange={setCurrency} />
            <Field label={receiving ? 'Received by' : 'Paid by'} errors={problems.payer}>
              <select value={payer} onChange={(e) => setPayer(e.target.value)}>
                {payers.map((m) => <option key={m.id} value={m.id}>{m.id === me ? `${m.displayName} (you)` : m.displayName}</option>)}
              </select>
            </Field>
            {opening && reader && <OpeningBalanceNote joinDate={reader.joinDate} noun={noun} />}
            {payerIsMe && !opening && (
              <Field label={receiving ? 'Received into' : 'Paid from'} errors={problems.payment}
                hint={`Only you see it. “Specify later” keeps ${receiving ? 'it' : 'the payment'} under “Payments without a specified account”.`}>
                <AccountSelect accounts={choices} value={payment}
                  onChange={(value) => { setPayment(value); setSide(newPayingSide()); setCounterpartyId('') }}>
                  <option value={LATER}>Specify later</option>
                </AccountSelect>
              </Field>
            )}
            {payerIsMe && !opening && (record.yourPayment || ownSide || payerChanged) && (
              <CounterpartyFields account={account} counterparties={counterparties.data} counterpartyId={counterpartyId}
                payeeId={payeeId} onCounterparty={setCounterpartyId} onPayee={setPayeeId} showPayee
                errors={problems.counterparty} />
            )}
            {account && (
              <PayingSideFields account={account} recordCurrency={currency} side={side} onChange={setSide}
                way={receiving ? 'received' : 'paid'} kept={kept}
                keptAmount={kept ? record.yourPayment?.amount : undefined}
                suggestions={choices.map((a) => a.defaultCurrency).filter((c): c is string => c !== null)}
                errors={{ currency: [], amount: [...('problem' in paid && (side.amountText !== '' || amountChanged || accountChanged) ? [paid.problem] : []), ...problems.accountAmount] }} />
            )}
          </>
        )}
        {record.canEdit && (
          <>
            <Field label="Category" errors={problems.category}>
              <CategorySelect categories={shown} type={type} value={categoryId} onChange={setCategoryId} />
            </Field>
            <Field label="Comment (optional)" errors={problems.comment} className="wide">
              <input value={comment} maxLength={500} onChange={(e) => setComment(e.target.value)} />
            </Field>
          </>
        )}
      </div>
      {(record.canEdit || record.splitMethod === 'AMOUNT') && (
        <SplitEditor form={split} preview={{ ...preview, problems: splitProblems }} context={context}
          currency={currencyValid ? currency : record.currency}
          onChange={setSplit} byMember={problems.byMember} problems={problems.split} you={me} />
      )}
      {needsAmounts && <p className="error small" role="alert">This {noun} is split by amounts: enter the new amounts with the new amount.</p>}
      {followsSplit && <p className="muted small">When you save, the shares are split again as the {noun} is split now.</p>}
      <div className="actions">
        <button className="primary" disabled={!ready || pending}>Save the changes</button>
        {changed && <button type="button" onClick={undo}>Undo</button>}
      </div>
    </form>
  )
}

/**
 * What the reader may change of a settlement (D-24): its date, amount and currency when they recorded it
 * (`canEditPayment`), unless the other side has put their part on an account of theirs (`lockedBy`, D-28); its comment
 * when they recorded it (`canEdit`); and the account of their own side when they pay or receive it with an account
 * (`yourPayment`), with the currency it paid or received in and, where that isn't the settlement's, what went from or
 * into it (D-89), which only they see (D-88). Only what changed is sent; the account alone changes nothing the other
 * members see.
 */
function EditSettlement({ record, family, problems, pending, onSave }: EditProps) {
  const accounts = useApi<Account[]>(record.yourPayment || record.canEditPayment ? '/accounts' : null)
  const { ledger } = family
  const me = ledger.memberId
  const initialSide = record.yourPayment ? (record.yourPayment.later ? LATER : String(record.yourPayment.accountId)) : ''
  const [date, setDate] = useState(record.date)
  const [amountText, setAmountText] = useState(record.amount)
  const [currency, setCurrency] = useState(record.currency)
  const [comment, setComment] = useState(record.comment ?? '')
  const [side, setSide] = useState(initialSide)
  const [paying, setPaying] = useState<PayingSideForm>(newPayingSide)

  const recorder = record.author.memberId === me
  const choices = paymentAccounts(accounts.data ?? [])
  const sideAccount = record.yourPayment && side !== LATER ? choices.find((a) => String(a.id) === side) : undefined
  const accountChanged = side !== initialSide
  const kept = !accountChanged ? record.yourPayment?.currency : undefined
  const currencyValid = /^[A-Z]{3}$/.test(currency)
  const parsed = parseMinor(amountText, currencyValid ? currency : record.currency)
  const amount = 'minor' in parsed ? parsed.minor : undefined
  const currencyChanged = currency !== record.currency
  const amountChanged = currencyChanged || (amount !== undefined && amount !== toMinor(record.amount, record.currency))
  // The recorder's side follows the settlement's amount; the other side names its own amount whenever it names an
  // account in another currency (F4e, D-89).
  const paid = sidePatch({ account: sideAccount, currency, side: paying, kept, moves: recorder ? amountChanged : accountChanged,
    accountChanged })
  const patch: RecordPatch = {
    ...(date !== record.date ? { date } : {}),
    ...(amountChanged && amount !== undefined ? { amount: fromMinor(amount, currency) } : {}),
    ...(currencyChanged ? { currency } : {}),
    ...(comment.trim() !== (record.comment ?? '') ? { comment: comment.trim() || null } : {}),
    ...(record.yourPayment ? accountPatch(side, initialSide) : {}),
    ...('patch' in paid ? paid.patch : {}),
  }
  const changed = Object.keys(patch).length > 0
  const dateProblem = date === '' ? 'Enter a date.'
    : date < ledger.startDate ? `The family budget starts on ${formatDate(ledger.startDate)}; a settlement can’t be earlier.` : undefined
  const ready = changed && !dateProblem && amount !== undefined && currencyValid && !('problem' in paid)
  const other = record.payer.memberId === me ? record.payee : record.payee?.memberId === me ? record.payer : undefined
  // The other side of a settlement someone else recorded: while the reader's side is on an account, it is locked (D-28).
  const recordedByOther = record.author.memberId !== me
  const holdsLock = recordedByOther && record.yourPayment !== undefined && !record.yourPayment.later
  const pays = record.payer.memberId === me

  function submit(event: FormEvent) {
    event.preventDefault()
    if (ready) onSave(patch)
  }

  return (
    <form className="family-form settlement-form" onSubmit={submit}>
      <h4>{record.canEdit ? 'Change this settlement' : 'Your side of this settlement'}</h4>
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
            <CurrencyField value={currency} errors={problems.currency}
              suggestions={currencySuggestions(ledger.baseCurrency, choices.map((a) => a.defaultCurrency))}
              onChange={setCurrency} />
          </>
        )}
        {record.canEdit && (
          <Field label="Comment (optional)" errors={problems.comment} className="wide">
            <input value={comment} maxLength={500} onChange={(e) => setComment(e.target.value)} />
          </Field>
        )}
        {record.yourPayment && (
          <Field label={sideLabel(pays)} errors={problems.payment}
            hint="Only you see it. “Specify later” keeps it under “Payments without a specified account”.">
            <AccountSelect accounts={choices} value={side} onChange={(value) => { setSide(value); setPaying(newPayingSide()) }}>
              <option value={LATER}>Specify later</option>
            </AccountSelect>
          </Field>
        )}
        {sideAccount && (
          <PayingSideFields account={sideAccount} recordCurrency={currency} side={paying} onChange={setPaying}
            way={pays ? 'paid' : 'received'} kept={kept} keptAmount={kept ? record.yourPayment?.amount : undefined}
            suggestions={choices.map((a) => a.defaultCurrency).filter((c): c is string => c !== null)}
            errors={{ currency: [], amount: [...('problem' in paid && (paying.amountText !== '' || accountChanged || amountChanged) ? [paid.problem] : []), ...problems.accountAmount] }} />
        )}
      </div>
      {!sideAccount && problems.accountAmount.length > 0 && <Errors messages={problems.accountAmount} />}
      {!record.canEditPayment && record.yourPayment?.later && other && (
        <p className="muted small">
          {other.displayName} recorded it; put your side on the account the money went {pays ? 'from' : 'into'}.
        </p>
      )}
      {holdsLock && (
        <p className="muted small">
          While your side is on an account of yours, {record.author.displayName} can’t change the settlement’s date or
          amount, or delete it. Choose “Specify later” to let them.
        </p>
      )}
      <div className="actions">
        <button className="primary" disabled={!ready || pending}>Save the changes</button>
      </div>
    </form>
  )
}
