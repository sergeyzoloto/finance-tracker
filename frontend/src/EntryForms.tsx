import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { api, ApiError, formatDate, useApi, type Category, type FamilyLedger, type FamilyMember, type FamilyRecord } from './api'
import { AccountSelect, CategorySelect, CounterpartyInput, CurrencyInput, Errors, Field } from './components'
import DateInput from './DateInput'
import {
  accountChoices, blankPosting, isFamilyRecord, postingBalances, postingsBalance, postingWithAccount, sharePreview,
  switchTab, TABS, tabOf, transferNeedsCounterparty, validate, withAccount, withPayee, type EntryForm, type FieldErrors,
  type PostingDraft,
} from './entryForm'
import { newSplit, previewSplit, type SplitContext, type SplitPreview } from './expenseForm'
import {
  entryAmount, familiesFor, familyProblems, familyRequest, familyServerErrors, memberField, splitContext, unavailable,
} from './familyEntry'
import { SplitEditor } from './FamilySplit'
import { accountById, counterpartyNamed, knownCurrencies, sharedAccount, type Ledger } from './ledger'
import { amountProblem, formatMoney, parseAmount, rate } from './money'
import SimilarEntries from './SimilarEntries'
import { payeeLabel } from './payees'
import { copyFamily, copyPersonal, criteriaOf, type Copied, type CopyNotes, type EntryMatch } from './similar'

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
  /**
   * Creates a family expense or income in the budget with the request; rejects with the server's answer. `payee` is the
   * typed payee's name, '' for none: the caller finds it among the counterparties, those it created included, or
   * creates it (D-81, D-462).
   */
  onSaveFamily?: (ledgerId: number, request: ReturnType<typeof familyRequest>, andNew: boolean,
    payee: string) => Promise<void>
  /**
   * Whether the family budget is switched on (D-25). Then the form no longer creates the old shared expense (H4, F6c):
   * "Split with family" stays only for an entry that is one already, which opens and saves as before (D-21).
   */
  familyBudgets?: boolean
  /** Whether the form shows "Similar entries" under its buttons: only on New entry (UX-8, D-456). */
  similar?: boolean
}

/** A family expense's or income's budget as the form has chosen it, with what its split needs. */
interface FamilyExpense {
  ledger: FamilyLedger
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
  if (!chosen) return undefined
  // A record in the entry's own currency, paid in it (D-45, D-89): no rate, no conversion.
  const currency = form.currency.trim().toUpperCase()
  const context = members.data ? splitContext(form, chosen, members.data) : undefined
  return {
    ledger: chosen,
    categories: categories.data,
    context,
    preview: context ? previewSplit(form.familySplit, context, /^[A-Z]{3}$/.test(currency) ? currency : chosen.baseCurrency) : undefined,
    error: members.error ?? categories.error,
  }
}

/** The entry form with its tabs. It checks what it can before saving, and shows the server's messages at the fields. */
export function EntryFormView({ ledger, initial, onSave, onDelete, onCancel, notice, families, onSaveFamily, familyBudgets = false, similar = false }: Props) {
  const [form, setForm] = useState(initial)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [busy, setBusy] = useState(false)
  /** The line under the tabs after "Save and add another" or a copy (D-461, D-462). */
  const [status, setStatus] = useState<string>()
  /** Whether the category is the user's choice, not the payee's last one that `withPayee` put in (D-456). */
  const [categoryPicked, setCategoryPicked] = useState(initial.categoryId !== '')
  /** The form as "Save and add another" last saved it: saving it again unchanged asks first (D-462). */
  const [savedForm, setSavedForm] = useState<string>()
  /** After a copy: the criteria it put in, which start no new search (D-456), and why a field stayed empty. */
  const [hold, setHold] = useState<string>()
  const [notes, setNotes] = useState<CopyNotes>({})
  const [copiedAdvanced, setCopiedAdvanced] = useState<string>()
  /** Each save of "Save and add another" asks for the similar entries again (D-460). */
  const [refresh, setRefresh] = useState(0)
  /** Moves the focus to Amount, its text selected, after a copy and after "Save and add another". */
  const [focusAmount, setFocusAmount] = useState(0)
  const formElement = useRef<HTMLFormElement>(null)
  useEffect(() => {
    if (focusAmount === 0) return
    const amount = formElement.current?.querySelector<HTMLInputElement>('.fields input.amount')
    amount?.focus()
    amount?.select()
  }, [focusAmount])

  /** Takes the changed form and drops the messages about the fields that changed. */
  const change = (next: EntryForm, fields: string[]) => {
    setForm(next)
    setStatus(undefined)
    setErrors((current) => Object.fromEntries(Object.entries(current).filter(([field]) =>
      !fields.some((f) => field === f || field.startsWith(`${f}.`)))))
    setNotes((current) => Object.fromEntries(Object.entries(current).filter(([field]) => !fields.includes(field))))
    if (next.tab !== form.tab) setCopiedAdvanced(undefined)
    // The payee's last category, put in by withPayee, is no criterion until the user picks one (D-456).
    if (fields.includes('payee')) {
      if (next.categoryId !== form.categoryId) setCategoryPicked(false)
    } else if (fields.includes('categoryId')) {
      setCategoryPicked(next.categoryId !== '')
    }
  }
  const set = (patch: Partial<EntryForm>) => change({ ...form, ...patch }, Object.keys(patch))
  const balanced = form.tab !== 'advanced' || postingsBalance(form.postings)
  const family = useFamilyExpense(form, families)

  async function save(andNew: boolean) {
    // A second click, or Enter again, doesn't make the same entry twice unasked (D-462).
    if (savedForm === JSON.stringify(form) && !confirm('Save the same entry again?')) return
    const problems = validate(form, ledger)
    if (isFamilyRecord(form)) {
      if (!family?.preview) (problems[''] ??= []).push(family?.error ?? 'The family budget is still loading.')
      else {
        for (const [field, messages] of Object.entries(familyProblems(form, family.ledger, family.preview))) {
          (problems[field] ??= []).push(...messages)
        }
      }
    }
    setErrors(problems)
    if (Object.keys(problems).length > 0) return
    setBusy(true)
    const failed = isFamilyRecord(form) && family?.preview
      ? await saveFamily(family.ledger, family.preview, andNew)
      : await run(() => onSave(form, andNew))
    setBusy(false)
    if (failed) {
      setErrors(failed)
    } else if (andNew) {
      // Every field stays as saved, the tab too: the next entry needs only what differs (D-462).
      setSavedForm(JSON.stringify(form))
      setStatus('Saved. The form keeps its values: change what differs.')
      setHold(undefined)
      setRefresh((n) => n + 1)
      if (form.tab !== 'advanced') setFocusAmount((n) => n + 1)
    }
  }

  /**
   * Copy (D-461): fills the form from a similar entry, keeping its date, and its amount and currency when Amount holds a
   * valid amount. A family row reads its record and the budget's members first. What it puts in starts no new search.
   */
  async function copy(match: EntryMatch) {
    let copied: Copied
    const link = match.entry.family
    try {
      const budget = link?.recordId != null ? families?.find((f) => f.id === link.ledgerId) : undefined
      if (link && link.recordId != null && budget) {
        const [record, members] = await Promise.all([
          api<FamilyRecord>(`/family-ledgers/${budget.id}/records/${link.recordId}`),
          api<FamilyMember[]>(`/family-ledgers/${budget.id}/members`),
        ])
        copied = copyFamily(form, match, record, budget, members, ledger)
      } else {
        copied = copyPersonal(form, match.entry, ledger)
      }
    } catch (e) {
      setErrors({ '': [`Couldn’t copy the entry: ${e instanceof ApiError || e instanceof Error ? e.message : 'unknown error'}`] })
      return
    }
    const picked = copied.form.categoryId !== ''
    setForm(copied.form)
    setErrors({})
    setNotes(copied.notes)
    setCategoryPicked(picked)
    const criteria = criteriaOf(copied.form, picked)
    setHold(criteria ? JSON.stringify(criteria) : undefined)
    setCopiedAdvanced(copied.simple ? undefined : TABS.find((t) => t.tab === tabOf(match.entry.kind))?.label)
    setStatus(`Copied from the entry of ${formatDate(match.entry.entryDate)}. Check the date and the amount.`)
    if (copied.form.tab !== 'advanced') setFocusAmount((n) => n + 1)
  }

  /** Creates the family expense or income (C2) through the family budget's endpoint; the messages if it failed. */
  async function saveFamily(chosen: FamilyLedger, preview: SplitPreview, andNew: boolean): Promise<FieldErrors | undefined> {
    try {
      await onSaveFamily!(chosen.id, familyRequest(form, chosen, preview,
        accountById(ledger, Number(form.accountId))?.defaultCurrency ?? null), andNew, form.payee.trim())
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

  const fields = { form, ledger, errors, set, change, notes }
  const familyOn = isFamilyRecord(form)
  const byMember = new Map(Object.entries(errors).filter(([field]) => field.startsWith('familyMember.'))
    .map(([field, messages]) => [Number(field.slice('familyMember.'.length)), messages]))
  return (
    <>
    <form className="entry-form" onSubmit={submit} noValidate ref={formElement}>
      <div className="tabs" role="tablist" aria-label="Kind of entry">
        {TABS.map(({ tab, label }) => (
          <button key={tab} type="button" role="tab" aria-selected={form.tab === tab}
            onClick={() => change(switchTab(form, tab, ledger), Object.keys(errors))}>{label}</button>
        ))}
      </div>
      {notice}
      {copiedAdvanced && (
        <p className="notice">
          This entry doesn’t fit the {copiedAdvanced} form, so it opens as raw postings. Saving it keeps the postings as
          they are here.
        </p>
      )}
      <Errors messages={errors['']} />
      {status && <p className="success" role="status">{status}</p>}
      <div className="fields">
        <Field label="Date" errors={errors.date}>
          <DateInput value={form.date} required onChange={(day) => set({ date: day })} />
        </Field>
        {form.tab === 'expense' && <ExpenseFields {...fields} families={onSaveFamily ? families : undefined} family={family}
          oldSplit={!familyBudgets || initial.split} />}
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
          currency={/^[A-Z]{3}$/.test(form.currency.trim().toUpperCase()) ? form.currency.trim().toUpperCase() : family.ledger.baseCurrency} byMember={byMember} problems={errors.familySplit ?? []}
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
    {similar && (
      <SimilarEntries criteria={criteriaOf(form, categoryPicked)} hold={hold} refresh={refresh} ledger={ledger}
        onCopy={(match) => void copy(match)} />
    )}
    </>
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
  /** Why a copied field stayed empty (D-461), shown under it. */
  notes?: CopyNotes
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

function AccountField({ form, ledger, errors, change, field, label, notes }: FieldsProps & {
  field: 'accountId' | 'toAccountId'
  label: string
}) {
  return (
    <Field label={label} errors={errors[field]} hint={field === 'accountId' ? notes?.accountId : undefined}>
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

/**
 * @param oldSplit whether the form offers the old "Split with family" (rule 7): always while the family budget is
 *        switched off, and with it on only for an entry that is a shared expense already (H4, F6c)
 */
function ExpenseFields(props: FieldsProps & { families?: FamilyLedger[]; family?: FamilyExpense; oldSplit: boolean }) {
  const { form, ledger, errors, set, families, family, oldSplit, notes } = props
  const parts = sharePreview(form)
  const shared = sharedAccount(ledger)
  const familyOn = isFamilyRecord(form)
  const reason = families && families.length > 0 ? unavailable(families, form.currency, family?.ledger) : undefined
  return (
    <>
      <PayeeField {...props} label={familyOn ? payeeLabel(family?.ledger.showsPayees === true) : 'Payee'} />
      <AccountField {...props} field="accountId" label={familyOn && form.refund ? 'Received into' : 'Paid from'} />
      <AmountFields {...props} label={form.split ? 'Total paid' : 'Amount'} />
      {!familyOn && (
        <Field label="Category" errors={errors.categoryId} hint={notes?.categoryId}>
          <CategorySelect categories={ledger.categories} type="EXPENSE" value={form.categoryId}
            onChange={(categoryId) => set({ categoryId })} />
        </Field>
      )}
      <div className="wide options">
        <label className="check">
          <input type="checkbox" checked={form.refund} onChange={(e) => set({ refund: e.target.checked })} />
          Refund: the money came back
        </label>
        {!familyOn && oldSplit && (
          <label className="check">
            <input type="checkbox" role="switch" checked={form.split} disabled={!shared}
              onChange={(e) => set({ split: e.target.checked })} />
            Split with family
          </label>
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
 * The switch that makes a new expense a family expense, or a new income a family income (C2, F4d): the old "Split with
 * family" goes while it is on, and so does an income's reversal; an expense's payee (D-81) and refund (D-79) stay,
 * and become the family expense's. Disabled, with the reason beside it, while no family budget keeps the entry's
 * currency.
 */
function FamilyOption({ form, set, families, reason }: FieldsProps & { families: FamilyLedger[]; reason?: string }) {
  const familyOn = isFamilyRecord(form)
  return (
    <label className="check">
      <input type="checkbox" role="switch" checked={familyOn} disabled={!familyOn && reason !== undefined}
        onChange={(e) => set(e.target.checked
          ? {
            familyId: String((familiesFor(families, form.currency)[0] ?? families[0]).id), split: false,
            ...(form.tab === 'income' ? { refund: false, payee: '' } : {}),
          }
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
function FamilyRecordFields({ form, errors, change, families, family, notes }: FieldsProps & {
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
      <Field label="Family category" errors={errors.familyCategoryId} hint={notes?.familyCategoryId}>
        <CategorySelect categories={ofType} type={type} value={form.familyCategoryId}
          onChange={(familyCategoryId) => change({ ...form, familyCategoryId }, ['familyCategoryId'])} />
      </Field>
      <Field label="Comment for the family budget" errors={errors.familyComment} className="wide"
        hint={`Every member of ${family.ledger.name} sees it.`}>
        <input value={form.familyComment} maxLength={500}
          onChange={(e) => change({ ...form, familyComment: e.target.value }, ['familyComment'])} />
      </Field>
    </>
  )
}

function IncomeFields(props: FieldsProps & { families?: FamilyLedger[]; family?: FamilyExpense }) {
  const { form, ledger, errors, set, families, family, notes } = props
  const familyOn = isFamilyRecord(form)
  const reason = families && families.length > 0 ? unavailable(families, form.currency, family?.ledger, 'income') : undefined
  return (
    <>
      {!familyOn && <PayeeField {...props} label="Payer" />}
      <AccountField {...props} field="accountId" label="Received into" />
      <AmountFields {...props} />
      {!familyOn && (
        <Field label="Category" errors={errors.categoryId} hint={notes?.categoryId}>
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
