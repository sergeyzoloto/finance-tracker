import { useState, type FormEvent, type ReactNode } from 'react'
import { AccountSelect, CategorySelect, CounterpartyInput, CurrencyInput, Errors, Field } from './components'
import {
  accountChoices, blankPosting, postingBalances, postingsBalance, postingWithAccount, sharePreview, switchTab, TABS,
  transferNeedsCounterparty, validate, withAccount, withPayee, type EntryForm, type FieldErrors, type PostingDraft,
} from './entryForm'
import { accountById, counterpartyNamed, knownCurrencies, sharedAccount, type Ledger } from './ledger'
import { amountProblem, formatMoney, parseAmount, rate } from './money'

export type SaveResult = FieldErrors | void

interface Props {
  ledger: Ledger
  initial: EntryForm
  /** Saves the entry; resolves to the messages to show if it failed. `andNew` keeps the form open for the next one. */
  onSave: (form: EntryForm, andNew: boolean) => Promise<SaveResult>
  onDelete?: () => Promise<SaveResult>
  onCancel?: () => void
  /** Shown above the fields, such as why an entry opened in Advanced. */
  notice?: ReactNode
}

/** The entry form with its tabs. It checks what it can before saving, and shows the server's messages at the fields. */
export function EntryFormView({ ledger, initial, onSave, onDelete, onCancel, notice }: Props) {
  const [form, setForm] = useState(initial)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(false)

  /** Takes the changed form and drops the messages about the fields that changed. */
  const change = (next: EntryForm, fields: string[]) => {
    setForm(next)
    setSaved(false)
    setErrors((current) => Object.fromEntries(Object.entries(current).filter(([field]) =>
      !fields.some((f) => field === f || field.startsWith(`${f}.`)))))
  }
  const set = (patch: Partial<EntryForm>) => change({ ...form, ...patch }, Object.keys(patch))
  const balanced = form.tab !== 'advanced' || postingsBalance(form.postings)

  async function save(andNew: boolean) {
    const problems = validate(form, ledger)
    setErrors(problems)
    if (Object.keys(problems).length > 0) return
    setBusy(true)
    const failed = await run(() => onSave(form, andNew))
    setBusy(false)
    if (failed) {
      setErrors(failed)
    } else if (andNew) {
      // The next entry is often like this one: same day, account and currency.
      setForm({ ...form, payee: '', memo: '', amount: '', toAmount: '', categoryId: '', refund: false, counterparty: '',
        postings: [blankPosting(form.currency), blankPosting(form.currency)] })
      setSaved(true)
    }
  }

  async function remove() {
    if (!onDelete || !confirm('Delete this entry?')) return
    setBusy(true)
    const failed = await run(onDelete)
    setBusy(false)
    if (failed) setErrors(failed)
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    void save(false)
  }

  const fields = { form, ledger, errors, set, change }
  return (
    <form className="entry-form" onSubmit={submit} noValidate>
      <div className="tabs" role="tablist" aria-label="Kind of entry">
        {TABS.map(({ tab, label }) => (
          <button key={tab} type="button" role="tab" aria-selected={form.tab === tab}
            onClick={() => change(switchTab(form, tab, ledger), Object.keys(errors))}>{label}</button>
        ))}
      </div>
      {notice}
      <Errors messages={errors['']} />
      {saved && <p className="success" role="status">Saved. Enter the next one.</p>}
      <div className="fields">
        <Field label="Date" errors={errors.date}>
          <input type="date" value={form.date} required onChange={(e) => set({ date: e.target.value })} />
        </Field>
        {form.tab === 'expense' && <ExpenseFields {...fields} />}
        {form.tab === 'income' && <IncomeFields {...fields} />}
        {form.tab === 'transfer' && <TransferFields {...fields} />}
        {form.tab === 'loan' && <LoanFields {...fields} />}
        {form.tab === 'exchange' && <ExchangeFields {...fields} />}
        {form.tab === 'advanced' && <PayeeField {...fields} label="Payee (optional)" />}
        <Field label="Memo" errors={errors.memo} className="wide">
          <input value={form.memo} maxLength={500} onChange={(e) => set({ memo: e.target.value })} />
        </Field>
      </div>
      {form.tab === 'advanced' && <AdvancedFields {...fields} />}
      <div className="actions">
        <button type="submit" className="primary" disabled={busy || !balanced}>Save</button>
        {!onDelete && (
          <button type="button" disabled={busy || !balanced} onClick={() => void save(true)}>Save and add another</button>
        )}
        {onCancel && <button type="button" onClick={onCancel} disabled={busy}>Cancel</button>}
        {onDelete && <button type="button" className="danger" onClick={() => void remove()} disabled={busy}>Delete</button>}
        {busy && <span className="muted">Saving…</span>}
      </div>
    </form>
  )
}

/** Runs a save and turns an unexpected failure into a message, so that the form never gets stuck. */
async function run(action: () => Promise<SaveResult>): Promise<FieldErrors | undefined> {
  try {
    return (await action()) || undefined
  } catch (e) {
    return { '': [e instanceof Error ? e.message : 'Saving failed.'] }
  }
}

interface FieldsProps {
  form: EntryForm
  ledger: Ledger
  errors: FieldErrors
  set: (patch: Partial<EntryForm>) => void
  change: (next: EntryForm, fields: string[]) => void
}

function PayeeField({ form, ledger, errors, change, label = 'Payee' }: FieldsProps & { label?: string }) {
  const known = form.payee.trim() === '' || counterpartyNamed(ledger, form.payee)
  return (
    <Field label={label} errors={errors.payee} hint={known ? undefined : 'New payee: it is added when you save.'}>
      <CounterpartyInput counterparties={ledger.counterparties} value={form.payee}
        onChange={(name) => change(withPayee(form, name, ledger), ['payee', 'categoryId'])} />
    </Field>
  )
}

function AccountField({ form, ledger, errors, change, field, label }: FieldsProps & {
  field: 'accountId' | 'toAccountId'
  label: string
}) {
  return (
    <Field label={label} errors={errors[field]}>
      <AccountSelect accounts={accountChoices(ledger, form, field)} value={form[field]}
        onChange={(id) => change(withAccount(form, field, id, ledger), [field, 'currency', 'toCurrency'])} />
    </Field>
  )
}

function AmountFields({ form, ledger, errors, set, amount = 'amount', currency = 'currency', label = 'Amount' }: FieldsProps & {
  amount?: 'amount' | 'toAmount'
  currency?: 'currency' | 'toCurrency'
  label?: string
}) {
  return (
    <>
      <Field label={label} errors={errors[amount]}>
        <input inputMode="decimal" className="amount" value={form[amount]} placeholder="0.00"
          onChange={(e) => set({ [amount]: e.target.value })} />
      </Field>
      <Field label="Currency" errors={errors[currency]} className="narrow">
        <CurrencyInput currencies={knownCurrencies(ledger)} value={form[currency]}
          onChange={(code) => set({ [currency]: code })} />
      </Field>
    </>
  )
}

function ExpenseFields(props: FieldsProps) {
  const { form, ledger, errors, set } = props
  const parts = sharePreview(form)
  const family = sharedAccount(ledger)
  return (
    <>
      <PayeeField {...props} />
      <AccountField {...props} field="accountId" label="Paid from" />
      <AmountFields {...props} label={form.split ? 'Total paid' : 'Amount'} />
      <Field label="Category" errors={errors.categoryId}>
        <CategorySelect categories={ledger.categories} type="EXPENSE" value={form.categoryId}
          onChange={(categoryId) => set({ categoryId })} />
      </Field>
      <div className="wide options">
        <label className="check">
          <input type="checkbox" checked={form.refund} onChange={(e) => set({ refund: e.target.checked })} />
          Refund: the money came back
        </label>
        <label className="check">
          <input type="checkbox" role="switch" checked={form.split} disabled={!family}
            onChange={(e) => set({ split: e.target.checked })} />
          Split with family
        </label>
      </div>
      {form.split && (
        <div className="wide split">
          <Field label="Family's share, %" errors={errors.sharePercent} className="narrow">
            <input inputMode="decimal" value={form.sharePercent} onChange={(e) => set({ sharePercent: e.target.value })} />
          </Field>
          <p className="share" aria-live="polite">
            {parts ? (
              <>
                Your share: <strong data-testid="own-share">{formatMoney(parts.own, form.currency)}</strong>
                {' · '}{family?.name ?? 'Family'}: <strong>{formatMoney(parts.other, form.currency)}</strong>
              </>
            ) : 'Enter the total and the share to see your part.'}
          </p>
        </div>
      )}
    </>
  )
}

function IncomeFields(props: FieldsProps) {
  const { form, ledger, errors, set } = props
  return (
    <>
      <PayeeField {...props} label="Payer" />
      <AccountField {...props} field="accountId" label="Received into" />
      <AmountFields {...props} />
      <Field label="Category" errors={errors.categoryId}>
        <CategorySelect categories={ledger.categories} type="INCOME" value={form.categoryId}
          onChange={(categoryId) => set({ categoryId })} />
      </Field>
      <div className="wide options">
        <label className="check">
          <input type="checkbox" checked={form.refund} onChange={(e) => set({ refund: e.target.checked })} />
          Reversal: the money went back
        </label>
      </div>
    </>
  )
}

function TransferFields(props: FieldsProps) {
  const { form, ledger, errors, set } = props
  return (
    <>
      <AccountField {...props} field="accountId" label="From" />
      <AccountField {...props} field="toAccountId" label="To" />
      <AmountFields {...props} />
      {transferNeedsCounterparty(form, ledger) && (
        <Field label="With (person or company)" errors={errors.counterparty}
          hint="This account keeps its balance per person.">
          <CounterpartyInput counterparties={ledger.counterparties} value={form.counterparty}
            onChange={(counterparty) => set({ counterparty })} />
        </Field>
      )}
      <PayeeField {...props} label="Payee (optional)" />
    </>
  )
}

function LoanFields(props: FieldsProps) {
  const { form, ledger, errors, set } = props
  const given = form.loan === 'given'
  return (
    <>
      <fieldset className="wide choice">
        <legend>Direction</legend>
        <label className="check">
          <input type="radio" name="loan" checked={given} onChange={() => set({ loan: 'given' })} /> I lent money
        </label>
        <label className="check">
          <input type="radio" name="loan" checked={!given} onChange={() => set({ loan: 'repaid' })} /> I got money back
        </label>
      </fieldset>
      <Field label={given ? 'Borrower' : 'Paid back by'} errors={errors.counterparty}
        hint={form.counterparty.trim() && !counterpartyNamed(ledger, form.counterparty) ? 'New person: added when you save.' : undefined}>
        <CounterpartyInput counterparties={ledger.counterparties} value={form.counterparty}
          onChange={(counterparty) => set({ counterparty })} />
      </Field>
      <AccountField {...props} field="accountId" label={given ? 'Paid from' : 'Received into'} />
      <AmountFields {...props} />
    </>
  )
}

function ExchangeFields(props: FieldsProps) {
  const { form } = props
  const from = parseAmount(form.amount)
  const to = parseAmount(form.toAmount)
  const showRate = from && to && !amountProblem(form.amount) && !amountProblem(form.toAmount)
    && form.currency.length === 3 && form.toCurrency.length === 3
  return (
    <>
      <AccountField {...props} field="accountId" label="From" />
      <AmountFields {...props} label="Amount sold" />
      <AccountField {...props} field="toAccountId" label="To" />
      <AmountFields {...props} amount="toAmount" currency="toCurrency" label="Amount bought" />
      <p className="wide muted" aria-live="polite">
        {showRate ? `Rate: 1 ${form.currency} = ${rate(from, to)} ${form.toCurrency}` : ' '}
      </p>
      <PayeeField {...props} label="Exchange office (optional)" />
    </>
  )
}

/** Raw postings, with what each currency adds up to; saving waits until every currency adds up to zero. */
function AdvancedFields({ form, ledger, errors, change }: FieldsProps) {
  const balances = postingBalances(form.postings)
  const currencies = knownCurrencies(ledger)
  const update = (index: number, posting: PostingDraft, fields: string[]) => change(
    { ...form, postings: form.postings.map((p, i) => (i === index ? posting : p)) },
    ['postings', ...fields.map((f) => `postings.${index}.${f}`)])
  const shownAccounts = ledger.accounts.filter((a) => !a.archived || form.postings.some((p) => p.accountId === String(a.id)))
  return (
    <section className="postings">
      <h3>Postings</h3>
      <p className="muted">
        Each posting adds its amount to an account; use a minus sign to take money out. In every currency the amounts
        must add up to zero.
      </p>
      <table>
        <thead>
          <tr><th>Account</th><th>Currency</th><th className="amount">Amount</th><th>Category</th><th>Counterparty</th><th /></tr>
        </thead>
        <tbody>
          {form.postings.map((p, i) => {
            const account = accountById(ledger, p.accountId === '' ? null : Number(p.accountId))
            const error = (field: string) => errors[`postings.${i}.${field}`]
            return (
              <tr key={p.key}>
                <td>
                  <AccountSelect accounts={shownAccounts} value={p.accountId} aria-label={`Account ${i + 1}`}
                    onChange={(id) => update(i, postingWithAccount(p, id, ledger), ['accountId', 'currency', 'categoryId'])} />
                  <FieldMessages messages={error('accountId')} />
                </td>
                <td>
                  <CurrencyInput currencies={currencies} value={p.currency} aria-label={`Currency ${i + 1}`}
                    onChange={(currency) => update(i, { ...p, currency }, ['currency'])} />
                  <FieldMessages messages={error('currency')} />
                </td>
                <td className="amount">
                  <input inputMode="decimal" className="amount" value={p.amount} aria-label={`Amount ${i + 1}`} placeholder="0.00"
                    onChange={(e) => update(i, { ...p, amount: e.target.value }, ['amount'])} />
                  <FieldMessages messages={error('amount')} />
                </td>
                <td>
                  <CategorySelect categories={ledger.categories} value={p.categoryId} aria-label={`Category ${i + 1}`}
                    placeholder={account?.type === 'EQUITY' ? 'No category' : '—'} disabled={account?.type !== 'EQUITY'}
                    onChange={(categoryId) => update(i, { ...p, categoryId }, ['categoryId'])} />
                  <FieldMessages messages={error('categoryId')} />
                </td>
                <td>
                  <CounterpartyInput counterparties={ledger.counterparties} value={p.counterparty} aria-label={`Counterparty ${i + 1}`}
                    placeholder={account?.requiresCounterparty ? 'Required' : 'Optional'}
                    onChange={(counterparty) => update(i, { ...p, counterparty }, ['counterparty'])} />
                  <FieldMessages messages={error('counterparty')} />
                </td>
                <td>
                  <button type="button" aria-label={`Remove posting ${i + 1}`} disabled={form.postings.length <= 2}
                    onClick={() => change({ ...form, postings: form.postings.filter((_, j) => j !== i) }, ['postings'])}>✕</button>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      <button type="button" onClick={() => change({
        ...form, postings: [...form.postings, blankPosting(form.postings.at(-1)?.currency ?? ledger.settings.baseCurrency)],
      }, [])}>Add posting</button>
      <ul className="balances" role="status" aria-label="Balance per currency">
        {balances.length === 0 && <li className="muted">Enter amounts to see what they add up to.</li>}
        {balances.map((b) => (
          <li key={b.currency} className={b.balanced ? 'balanced' : 'unbalanced'}>
            {b.balanced
              ? `${b.currency}: balanced ✓`
              : `${b.currency}: off by ${formatMoney(b.sum, b.currency, { signed: true })}`}
          </li>
        ))}
      </ul>
      {!postingsBalance(form.postings) && balances.length > 0 && (
        <p className="muted">You can save once every currency adds up to zero.</p>
      )}
      <FieldMessages messages={errors.postings} />
    </section>
  )
}

function FieldMessages({ messages }: { messages?: string[] }) {
  return <>{messages?.map((m) => <small key={m} className="error" role="alert">{m}</small>)}</>
}
