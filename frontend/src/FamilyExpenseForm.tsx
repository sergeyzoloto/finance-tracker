import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { api, formatDate, isoDate, useApi, type Account, type Category, type FamilyRecord } from './api'
import { AccountSelect, CategorySelect, Errors, Field, Loading } from './components'
import {
  expenseProblems, newSplit, paymentAccounts, previewSplit, splitRequest, type SplitContext, type SplitForm,
} from './expenseForm'
import { useFamilyApi, useFamilyMutation, type FamilyData } from './familyData'
import { SplitEditor } from './FamilySplit'
import { fromMinor, parseMinor } from './minorUnits'

/**
 * How the user paid their last family expense in this budget, or received their last income, to preselect it (D-14):
 * an account's id, or "later".
 */
const LAST_PAYMENT = (ledgerId: number, type: RecordType) =>
  `finance-tracker:family-${type === 'INCOME' ? 'receipt' : 'payment'}:${ledgerId}`
const LATER = 'later'

/** The records with a category and a split: an expense, or an income (F4d), which mirrors it. */
export type RecordType = 'EXPENSE' | 'INCOME'

/** The words of the form of each type. */
const WORDS: Record<RecordType, { title: string; noun: string; by: string; from: string; add: string; none: string; later: string }> = {
  EXPENSE: {
    title: 'Add an expense', noun: 'expense', by: 'Paid by', from: 'Paid from', add: 'Add the expense',
    none: 'No expense categories yet', later: '“Specify later” keeps the payment under “Payments without a specified account” in your ledger.',
  },
  INCOME: {
    title: 'Add an income', noun: 'income', by: 'Received by', from: 'Received into', add: 'Add the income',
    none: 'No income categories yet', later: '“Specify later” keeps it under “Payments without a specified account” in your ledger.',
  },
}

function lastPayment(ledgerId: number, type: RecordType) {
  try {
    return localStorage.getItem(LAST_PAYMENT(ledgerId, type)) ?? ''
  } catch {
    return ''
  }
}

function rememberPayment(ledgerId: number, type: RecordType, payment: string) {
  try {
    localStorage.setItem(LAST_PAYMENT(ledgerId, type), payment)
  } catch {
    // Only a convenience: the next one starts without a preselected account.
  }
}

/**
 * A new family expense (C1) at `/family/{ledgerId}/expenses/new`, or a new family income (C5, F4d) at
 * `/family/{ledgerId}/incomes/new`, which mirrors it: the date, a family category of its type, the amount in the base
 * currency, who paid or received it, the user's account if it was them (D-14), the split with every member's amount
 * before saving (D-12, the tie to the payer or receiver), and a comment. The server's objections appear next to the
 * field or member they name.
 */
export default function NewRecord({ family, type = 'EXPENSE' }: { family: FamilyData; type?: RecordType }) {
  const navigate = useNavigate()
  const { ledger, members } = family
  const currency = ledger.baseCurrency
  const words = WORDS[type]
  const categories = useFamilyApi<Category[]>(family, `${family.path}/categories`)
  const accounts = useApi<Account[]>('/accounts')

  const [date, setDate] = useState(() => {
    const today = isoDate(new Date())
    return today < ledger.startDate ? ledger.startDate : today
  })
  const [categoryId, setCategoryId] = useState('')
  const [amountText, setAmountText] = useState('')
  const [payer, setPayer] = useState(String(ledger.memberId))
  const [chosenPayment, setPayment] = useState<string>()
  const [split, setSplit] = useState<SplitForm>(newSplit)
  const [comment, setComment] = useState('')
  const save = useFamilyMutation(family)

  const payers = members.filter((m) => m.status === 'ACTIVE' && (m.id === ledger.memberId || !m.hasAccount))
  const payerIsMe = payer === String(ledger.memberId)
  const eligible = paymentAccounts(accounts.data ?? [])
  // The last way of paying, while it is still one the backend takes.
  const remembered = lastPayment(ledger.id, type)
  const payment = chosenPayment
    ?? (remembered === LATER || eligible.some((a) => String(a.id) === remembered) ? remembered : '')

  const parsed = parseMinor(amountText, currency)
  const amount = 'minor' in parsed ? parsed.minor : undefined
  const context: SplitContext = { ledger, members, date, amount, payerId: Number(payer), noun: words.noun }
  const preview = previewSplit(split, context, currency)
  const dateProblem = date === '' ? 'Enter a date.'
    : date < ledger.startDate ? `The family budget starts on ${formatDate(ledger.startDate)}; an ${words.noun} can’t be earlier.` : undefined
  const ready = !dateProblem && categoryId !== '' && amount !== undefined && payer !== ''
    && (!payerIsMe || payment !== '') && preview.problems.length === 0

  const problems = expenseProblems(save.failure, preview.rows.map((r) => r.member.id), Number(payer), 'date')

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!ready) return
    const how = payerIsMe ? (payment === LATER ? { paymentLater: true } : { paymentAccountId: Number(payment) }) : {}
    void save.run(async () => {
      const created = await api<FamilyRecord>(`${family.path}/records`, 'POST', {
        type, date, categoryId: Number(categoryId), amount: fromMinor(amount!, currency), comment: comment.trim() || null,
        payerMemberId: Number(payer), ...how, split: splitRequest(split, preview, currency),
      })
      if (payerIsMe) rememberPayment(ledger.id, type, payment)
      navigate(`${family.page}/expenses/${created.id}`)
    })
  }

  if (!categories.data) return categories.error ? <Errors messages={[categories.error]} /> : <Loading what="categories" />
  const ofType = categories.data.filter((c) => c.type === type)
  return (
    <section>
      <h3>{words.title}</h3>
      <form className="family-form expense-form" onSubmit={submit}>
        <div className="fields">
          <Field label="Date" errors={[...(dateProblem ? [dateProblem] : []), ...problems.date]}
            hint={`The family budget starts on ${formatDate(ledger.startDate)}.`}>
            <input type="date" value={date} min={ledger.startDate} required onChange={(e) => setDate(e.target.value)} />
          </Field>
          <Field label="Category" errors={problems.category}
            hint={ofType.every((c) => c.archived)
              ? <>{words.none}: <Link to={`${family.page}/categories`}>add one</Link>.</> : undefined}>
            <CategorySelect categories={ofType} type={type} value={categoryId} onChange={setCategoryId} />
          </Field>
          <Field label={`Amount (${currency})`} errors={[...(amountText.trim() !== '' && 'problem' in parsed ? [parsed.problem] : []), ...problems.amount]}>
            <input className="amount" inputMode="decimal" value={amountText} autoComplete="off" placeholder="0.00"
              onChange={(e) => setAmountText(e.target.value)} />
          </Field>
          <Field label={words.by} errors={problems.payer}>
            <select value={payer} onChange={(e) => setPayer(e.target.value)}>
              {payers.map((m) => (
                <option key={m.id} value={m.id}>{m.id === ledger.memberId ? `${m.displayName} (you)` : m.displayName}</option>
              ))}
            </select>
          </Field>
          {payerIsMe && (
            <Field label={words.from} errors={problems.payment} hint={words.later}>
              <AccountSelect accounts={eligible} value={payment} onChange={setPayment}>
                <option value={LATER}>Specify later</option>
              </AccountSelect>
            </Field>
          )}
          <Field label="Comment (optional)" errors={problems.comment} className="wide"
            hint="Every member of the family budget sees it.">
            <input value={comment} maxLength={500} onChange={(e) => setComment(e.target.value)} />
          </Field>
        </div>
        <SplitEditor form={split} preview={preview} context={context} currency={currency} onChange={setSplit}
          byMember={problems.byMember} problems={problems.split} you={ledger.memberId} />
        <div className="actions">
          <button className="primary" disabled={!ready || save.pending}>{save.pending ? 'Saving…' : words.add}</button>
          <Link className="button" to={`${family.page}/expenses`}>Cancel</Link>
        </div>
        <Errors messages={[...problems.other, accounts.error]} />
      </form>
    </section>
  )
}
