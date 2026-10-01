import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api, ApiError, formatDate, sentence, useApi, type Entry, type FamilyRecord } from './api'
import { AccountSelect, Errors, Field } from './components'
import { paymentAccounts } from './expenseForm'
import { RECORD_NOUNS } from './family'
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
 * amount of a record split by amounts needs the new amounts, which only the record's page takes.
 */
export function paymentProblems(failure: Error | undefined, noun = 'expense'): Problems {
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
      problems.amount.push(`This ${noun} is split by amounts: change its amount on the ${noun}’s page, with the new amounts.`)
    } else {
      const field: keyof Problems = code === 'AMOUNT' ? 'amount' : code === 'PAYMENT' ? 'payment' : code === 'JOINED_AFTER' ? 'date' : 'other'
      problems[field].push(sentence(message))
    }
  }
  if (failure.errors.length === 0 && failure.violationDetails.length === 0) problems.other.push(sentence(failure.message))
  return problems
}

/**
 * The user's own side of a family record, as an entry of their personal ledger: their payment for a family expense
 * (F4c; D-14), what they received of a family income, or their side of a settlement (F4d; D-24). Its account, or
 * "Specify later", changes here, and for a payment or a receipt also its date, amount and private note, as the user's
 * change of the record: the other members' shares follow on the server. Deleting it deletes the record, after a
 * confirmation that names the family budget. A settlement's date and amount change here only for the side who recorded
 * it, who alone deletes it; a settlement takes no note.
 */
export default function PaymentEntry({ entry, ledger, onSaved, onDeleted }: {
  entry: Entry
  ledger: Ledger
  onSaved: () => void
  onDeleted: () => void
}) {
  const family = entry.family!
  const record = useApi<FamilyRecord>(family.recordId === null ? null : `/family-ledgers/${family.ledgerId}/records/${family.recordId}`)
  const settlement = family.link === 'SETTLEMENT'
  const type = settlement ? 'SETTLEMENT' : family.recordType === 'INCOME' ? 'INCOME' : 'EXPENSE'
  const noun = RECORD_NOUNS[type]
  // The side's own account is the entry's first line; the other is the family budget's debt account.
  const side = entry.postings[0]
  const out = signOf(side.amount) < 0
  const currency = side.currency
  const account = accountById(ledger, side.accountId)
  const initialPayment = account?.system && account.code === 'UNSPECIFIED_PAYMENTS' ? LATER : String(side.accountId)
  const minor = toMinor(side.amount, currency) ?? 0n
  const initialAmount = fromMinor(minor < 0n ? -minor : minor, currency)
  // A settlement's date and amount are its recorder's; everything else here is the user's own.
  const mayChangeRecord = !settlement || record.data?.canEditPayment === true
  const mayDelete = !settlement || record.data?.canDelete === true

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
    ...(!settlement && memo.trim() !== (entry.memo ?? '') ? { memo: memo.trim() || null } : {}),
  }
  const changed = Object.keys(patch).length > 0
  const ready = changed && date !== '' && 'minor' in parsed && !busy
  // The accounts the backend takes, and the one it has, archived or not.
  const choices = paymentAccounts(ledger.accounts)
  if (initialPayment !== LATER && account && !choices.includes(account)) choices.push(account)
  const problems = paymentProblems(failure, noun)
  const budget = <Link to={`/family/${family.ledgerId}`}>{family.ledgerName}</Link>
  const page = family.recordId === null ? null : `/family/${family.ledgerId}/expenses/${family.recordId}`
  const accountLabel = type === 'INCOME' || (settlement && !out) ? 'Received into' : 'Paid from'

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
    const r = record.data
    const named = r && r.category ? `“${r.category.name}” of ${formatDate(r.date)}` : r ? `of ${formatDate(r.date)}` : ''
    const question = settlement
      ? `Delete the settlement ${named} in the family budget “${family.ledgerName}”? Both sides go from the personal ledgers.`
      : `Delete ${type === 'INCOME' ? 'what you received' : 'your payment'} and the ${noun} ${named} in the family budget “${family.ledgerName}”? The other members’ shares of it go too.`
    if (!confirm(question.replace('  ', ' '))) return
    void run(async () => {
      await api(`/entries/${entry.id}?version=${entry.version}`, 'DELETE')
      onDeleted()
    })
  }

  const heading = settlement ? 'Your side of a family settlement' : type === 'INCOME' ? 'Family income received' : 'Family payment'
  return (
    <>
      <h2>{heading}</h2>
      <p className="notice">
        {settlement
          ? <>Your side of a settlement in the family budget {budget}: you {out ? 'paid' : 'received'} it. You choose the account it went {out ? 'from' : 'into'}.</>
          : type === 'INCOME'
            ? <>What you received for an income of the family budget {budget}. Changing its date or amount changes the income, and every member’s share follows.</>
            : <>Your payment for an expense of the family budget {budget}. Changing its date or amount changes the expense, and every member’s share follows.</>}
        {page && <> <Link to={page}>Open the {noun}</Link></>}
      </p>
      {record.data && !settlement && (
        <p className="muted small">
          {record.data.category?.name}, {formatMoney(record.data.amount, record.data.currency)}, shared by{' '}
          {record.data.shares.filter((s) => signOf(s.amount) > 0).map((s) => s.member.displayName).join(', ')}.
        </p>
      )}
      {settlement && record.data?.lockedBy && (
        <p className="muted small">
          {record.data.lockedBy.displayName} has put their side of this settlement on an account of theirs, so its date
          and amount can’t change and it can’t be deleted. {record.data.lockedBy.displayName} can move it back to
          “Specify later” to allow it.
        </p>
      )}
      {settlement && record.data && !record.data.lockedBy && !mayChangeRecord && (
        <p className="muted small">
          Only {record.data.author.displayName}, who recorded it, changes its date and amount.
          {initialPayment !== LATER && <> While your side is on an account of yours, {record.data.author.displayName} can’t
            change them or delete the settlement; choose “Specify later” to let them.</>}
        </p>
      )}
      <form className="entry-form payment-entry" onSubmit={submit} noValidate>
        <div className="fields">
          <Field label="Date" errors={problems.date}>
            <input type="date" value={date} required disabled={!mayChangeRecord} onChange={(e) => setDate(e.target.value)} />
          </Field>
          <Field label={`Amount (${currency})`} errors={[...(amountText.trim() !== '' && 'problem' in parsed ? [parsed.problem] : []), ...problems.amount]}>
            <input className="amount" inputMode="decimal" value={amountText} autoComplete="off" disabled={!mayChangeRecord}
              onChange={(e) => setAmount(e.target.value)} />
          </Field>
          <Field label={accountLabel} errors={problems.payment}
            hint="“Specify later” keeps it under “Payments without a specified account”.">
            <AccountSelect accounts={choices} value={payment} onChange={setPayment}>
              <option value={LATER}>Specify later</option>
            </AccountSelect>
          </Field>
          {!settlement && (
            <Field label="Note, only you see it" errors={problems.memo} className="wide"
              hint="It stays on this entry in your own ledger; the family budget never sees it.">
              <input value={memo} maxLength={500} onChange={(e) => setMemo(e.target.value)} />
            </Field>
          )}
        </div>
        <Errors messages={problems.other} />
        <div className="actions">
          <button type="submit" className="primary" disabled={!ready}>Save</button>
          {mayDelete && <button type="button" className="danger" disabled={busy} onClick={remove}>Delete</button>}
          {busy && <span className="muted">Saving…</span>}
        </div>
      </form>
    </>
  )
}
