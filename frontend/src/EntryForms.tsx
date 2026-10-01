import { useState, type FormEvent, type ReactNode } from 'react'
import { useApi, type Category, type FamilyLedger, type FamilyMember } from './api'
import { AccountSelect, CategorySelect, CounterpartyInput, CurrencyInput, Errors, Field } from './components'
import {
  accountChoices, blankPosting, isFamilyRecord, postingBalances, postingsBalance, postingWithAccount, sharePreview,
  switchTab, TABS, transferNeedsCounterparty, validate, withAccount, withPayee, type EntryForm, type FieldErrors,
  type PostingDraft,
} from './entryForm'
import { newSplit, previewSplit, type SplitContext, type SplitPreview } from './expenseForm'
import {
  entryAmount, familiesFor, familyProblems, familyRequest, familyServerErrors, memberField, splitContext, unavailable,
} from './familyEntry'
import type { BaseAmount } from './currency'
import { BaseAmountField, useBaseAmount } from './FamilyCurrency'
import { SplitEditor } from './FamilySplit'
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
  /**
   * The user's family budgets, for a new expense or income that can be a family one (C2, F4d); left out while the
   * family budget is switched off, and for an entry that exists.
   */
  families?: FamilyLedger[]
  /** Creates a family expense or income in the budget with the request; rejects with the server's answer. */
  onSaveFamily?: (ledgerId: number, request: ReturnType<typeof familyRequest>, andNew: boolean) => Promise<void>
}

/** A family expense's or income's budget as the form has chosen it, with what its split needs. */
interface FamilyExpense {
  ledger: FamilyLedger
  /** The entry's amount in the budget's base currency (F4e). */
  base: BaseAmount
  categories?: Category[]
  context?: SplitContext
  preview?: SplitPreview
  error?: string
}

/** The chosen family budget's members and categories, while the form is a family expense or income. */
function useFamilyExpense(form: EntryForm, families?: FamilyLedger[]): FamilyExpense | undefined {
  const chosen = isFamilyRecord(form) ? families?.find((f) => String(f.id) === form.familyId) : undefined
  const members = useApi<FamilyMember[]>(chosen ? `/family-ledgers/${chosen.id}/members` : null)
  const categories = useApi<Category[]>(chosen ? `/family-ledgers/${chosen.id}/categories` : null)
  const currency = form.currency.trim().toUpperCase()
  const base = useBaseAmount(`/family-ledgers/${chosen?.id}`, currency, chosen?.baseCurrency ?? currency,
    chosen ? entryAmount(form) : undefined, form.date, form.familyBaseAmount === '' ? undefined : form.familyBaseAmount)
  if (!chosen) return undefined
  const context = members.data ? splitContext(form, chosen, members.data, base) : undefined
  return {
    ledger: chosen,
    base,
    categories: categories.data,
    context,
    preview: context ? previewSplit(form.familySplit, context, chosen.baseCurrency) : undefined,
    error: members.error ?? categories.error,
  }
}

/** The entry form with its tabs. It checks what it can before saving, and shows the server's messages at the fields. */
export function EntryFormView({ ledger, initial, onSave, onDelete, onCancel, notice, families, onSaveFamily }: Props) {
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
  const family = useFamilyExpense(form, families)

  async function save(andNew: boolean) {
    const problems = validate(form, ledger)
    if (isFamilyRecord(form)) {
      if (!family?.preview) (problems[''] ??= []).push(family?.error ?? 'The family budget is still loading.')
      else {
        for (const [field, messages] of Object.entries(familyProblems(form, family.ledger, family.preview, family.base))) {
          (problems[field] ??= []).push(...messages)
        }
      }
    }
    setErrors(problems)
    if (Object.keys(problems).length > 0) return
    setBusy(true)
    const failed = isFamilyRecord(form) && family?.preview
      ? await saveFamily(family.ledger, family.preview, family.base, andNew)
      : await run(() => onSave(form, andNew))
    setBusy(false)
    if (failed) {
      setErrors(failed)
    } else if (andNew) {
      // The next entry is often like this one: same day, account and currency.
      setForm({ ...form, payee: '', memo: '', amount: '', toAmount: '', categoryId: '', refund: false, counterparty: '',
        postings: [blankPosting(form.currency), blankPosting(form.currency)], familyCategoryId: '', familyComment: '',
        familyBaseAmount: '' })
      setSaved(true)
    }
  }

  /** Creates the family expense or income (C2) through the family budget's endpoint; the messages if it failed. */
  async function saveFamily(chosen: FamilyLedger, preview: SplitPreview, base: BaseAmount, andNew: boolean): Promise<FieldErrors | undefined> {
    try {
      await onSaveFamily!(chosen.id, familyRequest(form, chosen, preview, base), andNew)
      return undefined
    } catch (e) {
      return familyServerErrors(e, preview, chosen.memberId)
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
  const familyOn = isFamilyRecord(form)
  const byMember = new Map(Object.entries(errors).filter(([field]) => field.startsWith('familyMember.'))
    .map(([field, messages]) => [Number(field.slice('familyMember.'.length)), messages]))
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
        {form.tab === 'expense' && <ExpenseFields {...fields} families={onSaveFamily ? families : undefined} family={family} />}
        {form.tab === 'income' && <IncomeFields {...fields} families={onSaveFamily ? families : undefined} family={family} />}
        {form.tab === 'transfer' && <TransferFields {...fields} />}
        {form.tab === 'loan' && <LoanFields {...fields} />}
        {form.tab === 'exchange' && <ExchangeFields {...fields} />}
        {form.tab === 'advanced' && <PayeeField {...fields} label="Payee (optional)" />}
        <Field label={familyOn ? 'Note, only you see it' : 'Memo'} errors={errors.memo} className="wide"
          hint={familyOn ? `It stays on your ${form.tab === 'income' ? 'receipt' : 'payment'} in your own ledger; the family budget never sees it.` : undefined}>
          <input value={form.memo} maxLength={500} onChange={(e) => set({ memo: e.target.value })} />
        </Field>
      </div>
      {familyOn && family?.context && family.preview && (
        <SplitEditor form={form.familySplit} preview={family.preview} context={family.context}
          currency={family.ledger.baseCurrency} byMember={byMember} problems={errors.familySplit ?? []}
          you={family.ledger.memberId}
          onChange={(familySplit) => change({ ...form, familySplit }, ['familySplit', 'familyMember'])} />
      )}
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

function ExpenseFields(props: FieldsProps & { families?: FamilyLedger[]; family?: FamilyExpense }) {
  const { form, ledger, errors, set, families, family } = props
  const parts = sharePreview(form)
  const shared = sharedAccount(ledger)
  const familyOn = isFamilyRecord(form)
  const reason = families && families.length > 0 ? unavailable(families, form.currency, family?.ledger) : undefined
  return (
    <>
      {!familyOn && <PayeeField {...props} />}
      <AccountField {...props} field="accountId" label="Paid from" />
      <AmountFields {...props} label={form.split ? 'Total paid' : 'Amount'} />
      {!familyOn && (
        <Field label="Category" errors={errors.categoryId}>
          <CategorySelect categories={ledger.categories} type="EXPENSE" value={form.categoryId}
            onChange={(categoryId) => set({ categoryId })} />
        </Field>
      )}
      <div className="wide options">
        {!familyOn && (
          <>
            <label className="check">
              <input type="checkbox" checked={form.refund} onChange={(e) => set({ refund: e.target.checked })} />
              Refund: the money came back
            </label>
            <label className="check">
              <input type="checkbox" role="switch" checked={form.split} disabled={!shared}
                onChange={(e) => set({ split: e.target.checked })} />
              Split with family
            </label>
          </>
        )}
        {families && families.length > 0 && <FamilyOption {...props} families={families} reason={reason} />}
      </div>
      {reason && <p className="wide muted small" role="note">{reason}</p>}
      {familyOn && families && family && <FamilyRecordFields {...props} families={families} family={family} />}
      {!familyOn && form.split && (
        <div className="wide split">
          <Field label="Family's share, %" errors={errors.sharePercent} className="narrow">
            <input inputMode="decimal" value={form.sharePercent} onChange={(e) => set({ sharePercent: e.target.value })} />
          </Field>
          <p className="share" aria-live="polite">
            {parts ? (
              <>
                Your share: <strong data-testid="own-share">{formatMoney(parts.own, form.currency)}</strong>
                {' · '}{shared?.name ?? 'Family'}: <strong>{formatMoney(parts.other, form.currency)}</strong>
              </>
            ) : 'Enter the total and the share to see your part.'}
          </p>
        </div>
      )}
    </>
  )
}

/**
 * The switch that makes a new expense a family expense, or a new income a family income (C2, F4d): the payee, a
 * refund or reversal and the old "Split with family" go while it is on. Disabled, with the reason beside it, while no
 * family budget keeps the entry's currency.
 */
function FamilyOption({ form, set, families, reason }: FieldsProps & { families: FamilyLedger[]; reason?: string }) {
  const familyOn = isFamilyRecord(form)
  return (
    <label className="check">
      <input type="checkbox" role="switch" checked={familyOn} disabled={!familyOn && reason !== undefined}
        onChange={(e) => set(e.target.checked
          ? { familyId: String((familiesFor(families, form.currency)[0] ?? families[0]).id), refund: false, split: false, payee: '' }
          : { familyId: '' })} />
      {form.tab === 'income' ? 'Family income' : 'Family expense'}
    </label>
  )
}

/**
 * A family expense's or income's own fields (C2, F4d): the family budget, only when the user has more than one (D-5),
 * a family category of the record's type, and the comment every member sees. The split follows the fields, and the
 * memo becomes the user's note.
 */
function FamilyRecordFields({ form, errors, change, families, family }: FieldsProps & {
  families: FamilyLedger[]
  family: FamilyExpense
}) {
  const type = form.tab === 'income' ? 'INCOME' : 'EXPENSE'
  const ofType = (family.categories ?? []).filter((c) => c.type === type)
  return (
    <>
      {families.length > 1 && (
        <Field label="Family budget">
          <select value={form.familyId} onChange={(e) => change(
            { ...form, familyId: e.target.value, familyCategoryId: '', familySplit: newSplit() },
            ['familyId', 'familyCategoryId', 'familySplit', 'familyMember', ''])}>
            {families.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
          </select>
        </Field>
      )}
      <Field label="Family category" errors={errors.familyCategoryId}>
        <CategorySelect categories={ofType} type={type} value={form.familyCategoryId}
          onChange={(familyCategoryId) => change({ ...form, familyCategoryId }, ['familyCategoryId'])} />
      </Field>
      <BaseAmountField base={family.base} currency={form.currency.trim().toUpperCase()}
        baseCurrency={family.ledger.baseCurrency} date={form.date}
        entered={form.familyBaseAmount === '' ? undefined : form.familyBaseAmount}
        onEntered={(text) => change({ ...form, familyBaseAmount: text ?? '' }, ['familyBaseAmount'])}
        errors={errors.familyBaseAmount ?? []} />
      <Field label="Comment for the family budget" errors={errors.familyComment} className="wide"
        hint={`Every member of ${family.ledger.name} sees it.`}>
        <input value={form.familyComment} maxLength={500}
          onChange={(e) => change({ ...form, familyComment: e.target.value }, ['familyComment'])} />
      </Field>
    </>
  )
}

function IncomeFields(props: FieldsProps & { families?: FamilyLedger[]; family?: FamilyExpense }) {
  const { form, ledger, errors, set, families, family } = props
  const familyOn = isFamilyRecord(form)
  const reason = families && families.length > 0 ? unavailable(families, form.currency, family?.ledger, 'income') : undefined
  return (
    <>
      {!familyOn && <PayeeField {...props} label="Payer" />}
      <AccountField {...props} field="accountId" label="Received into" />
      <AmountFields {...props} />
      {!familyOn && (
        <Field label="Category" errors={errors.categoryId}>
          <CategorySelect categories={ledger.categories} type="INCOME" value={form.categoryId}
            onChange={(categoryId) => set({ categoryId })} />
        </Field>
      )}
      <div className="wide options">
        {!familyOn && (
          <label className="check">
            <input type="checkbox" checked={form.refund} onChange={(e) => set({ refund: e.target.checked })} />
            Reversal: the money went back
          </label>
        )}
        {families && families.length > 0 && <FamilyOption {...props} families={families} reason={reason} />}
      </div>
      {reason && <p className="wide muted small" role="note">{reason}</p>}
      {familyOn && families && family && <FamilyRecordFields {...props} families={families} family={family} />}
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
