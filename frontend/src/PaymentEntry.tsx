import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api, ApiError, formatDate, sentence, useApi, type Entry, type FamilyRecord } from './api'
import { AccountSelect, Errors, Field } from './components'
import { paymentAccounts } from './expenseForm'
import { accountById, type Ledger } from './ledger'
import { fromMinor, parseMinor, toMinor } from './minorUnits'
import { formatMoney, signOf } from './money'

const LATER = 'later'

/** A change of the payment as PATCH /api/entries/{id}/family-payment takes it: only what changed. */
export interface PaymentPatch { date?: string; amount?: string; accountId?: number; later?: true; memo?: string | null }

/** Where the server's objections to a payment go. */
type Problems = Record<'date' | 'amount' | 'payment' | 'memo' | 'other', string[]>

/**
 * The server's objections by field: a 400's fields, a 422's violationDetails by code, a 409's message on its own. A new
 * amount of an expense split by amounts needs the new amounts, which only the expense's page takes.
 */
export function paymentProblems(failure: Error | undefined): Problems {
  const problems: Problems = { date: [], amount: [], payment: [], memo: [], other: [] }
  if (!failure) return problems
  if (!(failure instanceof ApiError) || failure.status === 409) {
    problems.other.push(sentence(failure.message))
    return problems
  }
  const fields: Record<string, keyof Problems> = { date: 'date', amount: 'amount', accountId: 'payment', later: 'payment', memo: 'memo' }
  for (const { field, message } of failure.errors) problems[fields[field] ?? 'other'].push(sentence(`${fields[field] ? '' : `${field} `}${message}`))
  for (const { code, message } of failure.violationDetails) {
    if (code === 'AMOUNTS_NEEDED') {
      problems.amount.push('This expense is split by amounts: change its amount on the expense’s page, with the new amounts.')
    } else {
      const field: keyof Problems = code === 'AMOUNT' ? 'amount' : code === 'PAYMENT' ? 'payment' : code === 'JOINED_AFTER' ? 'date' : 'other'
      problems[field].push(sentence(message))
    }
  }
  if (failure.errors.length === 0 && failure.violationDetails.length === 0) problems.other.push(sentence(failure.message))
  return problems
}

/**
 * The user's own payment for a family expense (F4c; D-14): its date, amount, account and private note change here, as
 * the payer's change of the expense, and the other members' shares follow on the server. Deleting it deletes the
 * expense, after a confirmation that names the family budget and says the other members' shares go too.
 */
export default function PaymentEntry({ entry, ledger, onSaved, onDeleted }: {
  entry: Entry
  ledger: Ledger
  onSaved: () => void
  onDeleted: () => void
}) {
  const family = entry.family!
  const record = useApi<FamilyRecord>(family.recordId === null ? null : `/family-ledgers/${family.ledgerId}/records/${family.recordId}`)
  const paid = entry.postings.find((p) => signOf(p.amount) < 0) ?? entry.postings[0]
  const currency = paid.currency
  const account = accountById(ledger, paid.accountId)
  const initialPayment = account?.system && account.code === 'UNSPECIFIED_PAYMENTS' ? LATER : String(paid.accountId)
  const initialAmount = fromMinor(-(toMinor(paid.amount, currency) ?? 0n), currency)

  const [date, setDate] = useState(entry.entryDate)
  const [amountText, setAmount] = useState(initialAmount)
  const [payment, setPayment] = useState(initialPayment)
  const [memo, setMemo] = useState(entry.memo ?? '')
  const [failure, setFailure] = useState<Error>()
  const [busy, setBusy] = useState(false)

  const parsed = parseMinor(amountText, currency)
  const patch: PaymentPatch = {
    ...(date !== entry.entryDate ? { date } : {}),
    ...('minor' in parsed && fromMinor(parsed.minor, currency) !== initialAmount ? { amount: fromMinor(parsed.minor, currency) } : {}),
    ...(payment !== initialPayment ? (payment === LATER ? { later: true as const } : { accountId: Number(payment) }) : {}),
    ...(memo.trim() !== (entry.memo ?? '') ? { memo: memo.trim() || null } : {}),
  }
  const changed = Object.keys(patch).length > 0
  const ready = changed && date !== '' && 'minor' in parsed && !busy
  // The accounts the backend takes for a payment, and the one it has, archived or not.
  const choices = paymentAccounts(ledger.accounts)
  if (initialPayment !== LATER && account && !choices.includes(account)) choices.push(account)
  const problems = paymentProblems(failure)
  const budget = <Link to={`/family/${family.ledgerId}`}>{family.ledgerName}</Link>
  const expense = family.recordId === null ? null : `/family/${family.ledgerId}/expenses/${family.recordId}`

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setFailure(undefined)
    try {
      await action()
    } catch (e) {
      setFailure(e instanceof Error ? e : new Error('Saving failed.'))
    } finally {
      setBusy(false)
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!ready) return
    void run(async () => {
      await api(`/entries/${entry.id}/family-payment?version=${entry.version}`, 'PATCH', patch)
      onSaved()
    })
  }

  function remove() {
    const what = record.data
      ? `the expense “${record.data.category.name}” of ${formatDate(record.data.date)} in the family budget “${family.ledgerName}”`
      : `its expense in the family budget “${family.ledgerName}”`
    if (!confirm(`Delete your payment and ${what}? The other members’ shares of it go too.`)) return
    void run(async () => {
      await api(`/entries/${entry.id}?version=${entry.version}`, 'DELETE')
      onDeleted()
    })
  }

  return (
    <>
      <h2>Family payment</h2>
      <p className="notice">
        Your payment for an expense of the family budget {budget}. Changing its date or amount changes the expense, and
        every member’s share follows.
        {expense && <> <Link to={expense}>Open the expense</Link></>}
      </p>
      {record.data && (
        <p className="muted small">
          {record.data.category.name}, {formatMoney(record.data.amount, record.data.currency)}, shared by{' '}
          {record.data.shares.filter((s) => signOf(s.amount) > 0).map((s) => s.member.displayName).join(', ')}.
        </p>
      )}
      <form className="entry-form payment-entry" onSubmit={submit} noValidate>
        <div className="fields">
          <Field label="Date" errors={problems.date}>
            <input type="date" value={date} required onChange={(e) => setDate(e.target.value)} />
          </Field>
          <Field label={`Amount (${currency})`} errors={[...(amountText.trim() !== '' && 'problem' in parsed ? [parsed.problem] : []), ...problems.amount]}>
            <input className="amount" inputMode="decimal" value={amountText} autoComplete="off" onChange={(e) => setAmount(e.target.value)} />
          </Field>
          <Field label="Paid from" errors={problems.payment}
            hint="“Specify later” keeps it under “Payments without a specified account”.">
            <AccountSelect accounts={choices} value={payment} onChange={setPayment}>
              <option value={LATER}>Specify later</option>
            </AccountSelect>
          </Field>
          <Field label="Note, only you see it" errors={problems.memo} className="wide"
            hint="It stays on your payment in your own ledger; the family budget never sees it.">
            <input value={memo} maxLength={500} onChange={(e) => setMemo(e.target.value)} />
          </Field>
        </div>
        <Errors messages={problems.other} />
        <div className="actions">
          <button type="submit" className="primary" disabled={!ready}>Save</button>
          <button type="button" className="danger" disabled={busy} onClick={remove}>Delete</button>
          {busy && <span className="muted">Saving…</span>}
        </div>
      </form>
    </>
  )
}
