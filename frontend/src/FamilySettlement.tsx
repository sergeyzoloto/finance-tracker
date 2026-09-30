import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { api, formatDate, isoDate, useApi, type Account, type FamilyRecord } from './api'
import { AccountSelect, Errors, Field } from './components'
import { expenseProblems, paymentAccounts } from './expenseForm'
import { maySettle } from './family'
import { useFamilyMutation, type FamilyData } from './familyData'
import { fromMinor, parseMinor } from './minorUnits'

/** The side of a settlement the user last recorded in this budget, to preselect it: an account's id, or "later". */
const LAST_SIDE = (ledgerId: number) => `finance-tracker:family-settlement:${ledgerId}`
export const LATER = 'later'

function lastSide(ledgerId: number) {
  try {
    return localStorage.getItem(LAST_SIDE(ledgerId)) ?? ''
  } catch {
    return ''
  }
}

function rememberSide(ledgerId: number, side: string) {
  try {
    localStorage.setItem(LAST_SIDE(ledgerId), side)
  } catch {
    // Only a convenience: the next settlement starts without a preselected account.
  }
}

/** Where a settlement's side went from or into, as the reader's side of it: "Paid from" or "Received into". */
export const sideLabel = (pays: boolean) => (pays ? 'Paid from' : 'Received into')

/**
 * A new settlement (D2, D-24) at `/family/{ledgerId}/settle`: who paid, who received, the amount, the date, the reader's
 * own account or "Specify later" when they pay or receive it, and a comment. "Settle up" on the balances opens it with
 * `payer`, `payee` and `amount` in the URL, from who owes whom. A member records a settlement they pay or receive; an
 * owner also one between two members without an account. The other side's part goes to their "Payments without a
 * specified account", for them to put on an account of theirs.
 */
export default function NewSettlement({ family }: { family: FamilyData }) {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const { ledger, members } = family
  const me = ledger.memberId
  const currency = ledger.baseCurrency
  const active = members.filter((m) => m.status === 'ACTIVE')
  const given = (key: string) => {
    const value = params.get(key) ?? ''
    return active.some((m) => String(m.id) === value) ? value : undefined
  }
  const accounts = useApi<Account[]>('/accounts')

  const [payer, setPayer] = useState(given('payer') ?? String(me))
  const [payee, setPayee] = useState(given('payee') ?? '')
  const [amountText, setAmountText] = useState(() => {
    const amount = params.get('amount') ?? ''
    return 'minor' in parseMinor(amount, currency) ? amount : ''
  })
  const [date, setDate] = useState(() => {
    const today = isoDate(new Date())
    return today < ledger.startDate ? ledger.startDate : today
  })
  const [chosenSide, setSide] = useState<string>()
  const [comment, setComment] = useState('')
  const save = useFamilyMutation(family)

  const payerMember = active.find((m) => String(m.id) === payer)
  const payeeMember = active.find((m) => String(m.id) === payee)
  const pays = payer === String(me)
  const mine = pays || payee === String(me)
  const allowed = payerMember !== undefined && payeeMember !== undefined
    && maySettle({ memberId: payerMember.id, hasAccount: payerMember.hasAccount },
      { memberId: payeeMember.id, hasAccount: payeeMember.hasAccount }, me, family.owner)
  const other = pays ? payeeMember : payee === String(me) ? payerMember : undefined
  const eligible = paymentAccounts(accounts.data ?? [])
  const remembered = lastSide(ledger.id)
  const side = chosenSide ?? (remembered === LATER || eligible.some((a) => String(a.id) === remembered) ? remembered : '')

  const parsed = parseMinor(amountText, currency)
  const amount = 'minor' in parsed ? parsed.minor : undefined
  const dateProblem = date === '' ? 'Enter a date.'
    : date < ledger.startDate ? `The family budget starts on ${formatDate(ledger.startDate)}; a settlement can’t be earlier.` : undefined
  const sameMember = payer !== '' && payer === payee
  const ready = !dateProblem && amount !== undefined && payerMember !== undefined && payeeMember !== undefined
    && !sameMember && allowed && (!mine || side !== '')
  const problems = expenseProblems(save.failure, [], Number(payer), 'date', Number(payee))

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!ready) return
    const how = mine ? (side === LATER ? { paymentLater: true } : { paymentAccountId: Number(side) }) : {}
    void save.run(async () => {
      const created = await api<FamilyRecord>(`${family.path}/settlements`, 'POST', {
        date, amount: fromMinor(amount!, currency), payerMemberId: Number(payer), payeeMemberId: Number(payee),
        comment: comment.trim() || null, ...how,
      })
      if (mine) rememberSide(ledger.id, side)
      navigate(`${family.page}/expenses/${created.id}`)
    })
  }

  const name = (id: number) => (id === me ? `${members.find((m) => m.id === id)?.displayName} (you)` : members.find((m) => m.id === id)?.displayName)
  return (
    <section>
      <h3>Record a settlement</h3>
      <p className="muted">
        One member pays another to settle their balances. Each side with an account gets it in their personal ledger.
      </p>
      <form className="family-form settlement-form" onSubmit={submit}>
        <div className="fields">
          <Field label="Paid by" errors={problems.payer}>
            <select value={payer} onChange={(e) => setPayer(e.target.value)}>
              {active.map((m) => <option key={m.id} value={m.id}>{name(m.id)}</option>)}
            </select>
          </Field>
          <Field label="Received by" errors={[...(sameMember ? ['Choose someone other than who paid.'] : []), ...problems.payee]}>
            <select value={payee} onChange={(e) => setPayee(e.target.value)}>
              <option value="">Choose a member</option>
              {active.map((m) => <option key={m.id} value={m.id}>{name(m.id)}</option>)}
            </select>
          </Field>
          <Field label={`Amount (${currency})`}
            errors={[...(amountText.trim() !== '' && 'problem' in parsed ? [parsed.problem] : []), ...problems.amount]}>
            <input className="amount" inputMode="decimal" value={amountText} autoComplete="off" placeholder="0.00"
              onChange={(e) => setAmountText(e.target.value)} />
          </Field>
          <Field label="Date" errors={[...(dateProblem ? [dateProblem] : []), ...problems.date]}
            hint={`The family budget starts on ${formatDate(ledger.startDate)}.`}>
            <input type="date" value={date} min={ledger.startDate} required onChange={(e) => setDate(e.target.value)} />
          </Field>
          {mine && (
            <Field label={sideLabel(pays)} errors={problems.payment}
              hint="Only you see it. “Specify later” keeps it under “Payments without a specified account” in your ledger.">
              <AccountSelect accounts={eligible} value={side} onChange={setSide}>
                <option value={LATER}>Specify later</option>
              </AccountSelect>
            </Field>
          )}
          <Field label="Comment (optional)" errors={problems.comment} className="wide"
            hint="Every member of the family budget sees it.">
            <input value={comment} maxLength={500} onChange={(e) => setComment(e.target.value)} />
          </Field>
        </div>
        {other?.hasAccount && (
          <p className="muted small">
            {other.displayName}’s part goes to their “Payments without a specified account” until they put it on an
            account of theirs.
          </p>
        )}
        {payerMember && payeeMember && !sameMember && !allowed && (
          <p className="error small" role="alert">
            You record a settlement you pay or receive. Between two members without an account, an owner of the family
            budget records it.
          </p>
        )}
        <div className="actions">
          <button className="primary" disabled={!ready || save.pending}>{save.pending ? 'Saving…' : 'Record the settlement'}</button>
          <Link className="button" to={`${family.page}/balances`}>Cancel</Link>
        </div>
        <Errors messages={[...problems.other, ...problems.split, accounts.error]} />
      </form>
    </section>
  )
}
