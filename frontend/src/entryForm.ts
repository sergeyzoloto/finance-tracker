import { ApiError, sentence, type Account, type Entry, type EntryCommand, type EntryKind, type Posting } from './api'
import {
  accountById, accountWithCode, categoryById, counterpartyById, counterpartyNamed, FX_EXCHANGE, LOANS, sharedAccount,
  UNALLOCATED, type Ledger,
} from './ledger'
import {
  abs, amountProblem, equal, isZero, negate, parseAmount, percentFromRatio, percentProblem, ratioFromPercent,
  sharePercentOf, signOf, splitShared, sum,
} from './money'

// The entry form: what the user fills in, and how it turns into an entry command and back. Users pick accounts,
// categories and a direction (a refund, a loan given or repaid); the signs of the postings follow from those, so
// debit and credit never show. Only Advanced shows postings, with signed amounts.

export type Tab = 'expense' | 'income' | 'transfer' | 'loan' | 'exchange' | 'advanced'

export const TABS: { tab: Tab; label: string }[] = [
  { tab: 'expense', label: 'Expense' },
  { tab: 'income', label: 'Income' },
  { tab: 'transfer', label: 'Transfer' },
  { tab: 'loan', label: 'Loan' },
  { tab: 'exchange', label: 'Currency exchange' },
  { tab: 'advanced', label: 'Advanced' },
]

/** A posting in Advanced. Ids are strings, as select values are; the counterparty is a name. */
export interface PostingDraft {
  key: number
  accountId: string
  currency: string
  amount: string
  categoryId: string
  counterparty: string
}

/**
 * One state for all tabs, so that switching tabs keeps what they share, such as the date and the amount. Ids are
 * strings ('' for none); payee and counterparty are names, and new ones are created when the entry is saved.
 */
export interface EntryForm {
  tab: Tab
  date: string
  payee: string
  memo: string
  /** Expense and income: the account. Transfer and exchange: where the money comes from. Loan: the user's account. */
  accountId: string
  /** Transfer and exchange: where the money goes. */
  toAccountId: string
  currency: string
  /** Positive; `refund` turns an expense or income around. */
  amount: string
  toCurrency: string
  toAmount: string
  categoryId: string
  refund: boolean
  /** Expense: shared with the family (rule 7). */
  split: boolean
  /** The family's share, in percent. */
  sharePercent: string
  loan: 'given' | 'repaid'
  /** Loan: the borrower. Transfer: whom a per-counterparty account's balance is with (rule 8). */
  counterparty: string
  postings: PostingDraft[]
}

/** Messages per field; '' holds those that belong to no field, `postings.<i>.<field>` those of a posting. */
export type FieldErrors = Record<string, string[]>

let postingKeys = 0

export function blankPosting(currency = ''): PostingDraft {
  return { key: ++postingKeys, accountId: '', currency, amount: '', categoryId: '', counterparty: '' }
}

export function newForm(ledger: Ledger, date: string, tab: Tab = 'expense'): EntryForm {
  const currency = ledger.settings.baseCurrency
  return {
    tab, date, payee: '', memo: '', accountId: '', toAccountId: '', currency, amount: '', toCurrency: '', toAmount: '',
    categoryId: '', refund: false, split: false, sharePercent: percentFromRatio(ledger.settings.defaultShareRatio),
    loan: 'given', counterparty: '', postings: [blankPosting(currency), blankPosting(currency)],
  }
}

/** The tab that edits entries of this kind. */
export const tabOf = (kind: EntryKind): Tab => ({
  EXPENSE: 'expense', SHARED_EXPENSE: 'expense', INCOME: 'income', TRANSFER: 'transfer', LOAN_GIVEN: 'loan',
  LOAN_REPAID: 'loan', CURRENCY_EXCHANGE: 'exchange', OPENING_BALANCE: 'advanced', MANUAL: 'advanced',
} as const)[kind]

/**
 * The form for a saved entry, in the tab of its kind if its postings have the shape that tab builds, and otherwise in
 * Advanced, then with `simple` false. Advanced always gets the postings, so switching to it shows them.
 */
export function formFromEntry(entry: Entry, ledger: Ledger): { form: EntryForm; simple: boolean } {
  const name = (id: number | null) => counterpartyById(ledger, id)?.name ?? ''
  const base: EntryForm = {
    ...newForm(ledger, entry.entryDate, 'advanced'),
    payee: name(entry.payeeId),
    memo: entry.memo ?? '',
    postings: entry.postings.map((p) => ({
      key: ++postingKeys, accountId: String(p.accountId), currency: p.currency, amount: p.amount,
      categoryId: p.categoryId === null ? '' : String(p.categoryId), counterparty: name(p.counterpartyId),
    })),
  }
  const fields = decode(entry, ledger)
  if (fields) return { form: { ...base, ...fields, tab: tabOf(entry.kind) }, simple: true }
  return { form: base, simple: tabOf(entry.kind) === 'advanced' }
}

/** The fields of the entry's tab, if its postings are exactly those the tab's command builds. */
function decode(entry: Entry, ledger: Ledger): Partial<EntryForm> | undefined {
  const ps = entry.postings
  const unallocated = accountWithCode(ledger, UNALLOCATED)?.id
  const loans = accountWithCode(ledger, LOANS)?.id
  const fx = accountWithCode(ledger, FX_EXCHANGE)?.id
  const plain = (p: Posting) => p.categoryId === null && p.counterpartyId === null
  const oneCurrency = ps.every((p) => p.currency === ps[0]?.currency)
  const id = String
  const name = (n: number | null) => counterpartyById(ledger, n)?.name ?? ''

  switch (entry.kind) {
    case 'EXPENSE':
    case 'INCOME': {
      const category = ps.find((p) => p.accountId === unallocated && p.categoryId !== null && p.counterpartyId === null)
      const account = ps.find((p) => p !== category)
      const type = categoryById(ledger, category?.categoryId)?.type
      if (ps.length !== 2 || !category || !account || !plain(account) || !oneCurrency
        || !equal(account.amount, negate(category.amount)) || type !== entry.kind) return undefined
      // An expense posts +amount to UNALLOCATED, an income −amount; the opposite sign is a refund (rule 5).
      const refund = signOf(category.amount) === (entry.kind === 'EXPENSE' ? -1 : 1)
      return {
        accountId: id(account.accountId), currency: account.currency, amount: abs(account.amount),
        categoryId: id(category.categoryId!), refund,
      }
    }
    case 'SHARED_EXPENSE': {
      const shared = sharedAccount(ledger)?.id
      const category = ps.find((p) => p.accountId === unallocated && p.categoryId !== null && p.counterpartyId === null)
      const other = ps.find((p) => p !== category && p.accountId === shared && plain(p)
        && category && signOf(p.amount) === signOf(category.amount))
      const account = ps.find((p) => p !== category && p !== other)
      if (ps.length !== 3 || !category || !other || !account || !plain(account) || !oneCurrency
        || categoryById(ledger, category.categoryId)?.type !== 'EXPENSE') return undefined
      const total = negate(account.amount)
      const percent = sharePercentOf(total, other.amount)
      if (!equal(total, sum([category.amount, other.amount])) || percent === undefined) return undefined
      return {
        accountId: id(account.accountId), currency: account.currency, amount: abs(total),
        categoryId: id(category.categoryId!), refund: signOf(total) < 0, split: true, sharePercent: percent,
      }
    }
    case 'TRANSFER': {
      const from = ps.find((p) => signOf(p.amount) < 0)
      const to = ps.find((p) => signOf(p.amount) > 0)
      if (ps.length !== 2 || !from || !to || !oneCurrency || from.categoryId !== null || to.categoryId !== null
        || !equal(from.amount, negate(to.amount))) return undefined
      // The form has a counterparty only for an account that needs one (rule 8), and puts it on both postings. The
      // importer puts it on that account's posting alone, which fits too.
      const perPerson = (p: Posting) => accountById(ledger, p.accountId)?.requiresCounterparty === true
      const counterparty = from.counterpartyId ?? to.counterpartyId
      if (counterparty !== null && !(perPerson(from) || perPerson(to))) return undefined
      if (![from, to].every((p) => p.counterpartyId === counterparty || p.counterpartyId === null && !perPerson(p))) return undefined
      return {
        accountId: id(from.accountId), toAccountId: id(to.accountId), currency: from.currency, amount: to.amount,
        counterparty: name(counterparty),
      }
    }
    case 'LOAN_GIVEN':
    case 'LOAN_REPAID': {
      const given = entry.kind === 'LOAN_GIVEN'
      // Given: the account pays out, LOANS_ASSET grows by the borrower's debt; repaid: the other way round.
      const loan = ps.find((p) => p.accountId === loans && p.counterpartyId !== null && p.categoryId === null
        && signOf(p.amount) === (given ? 1 : -1))
      const account = ps.find((p) => p !== loan)
      if (ps.length !== 2 || !loan || !account || !plain(account) || !oneCurrency
        || !equal(account.amount, negate(loan.amount))) return undefined
      return {
        loan: given ? 'given' : 'repaid', accountId: id(account.accountId), counterparty: name(loan.counterpartyId),
        currency: account.currency, amount: abs(loan.amount),
      }
    }
    case 'CURRENCY_EXCHANGE': {
      // Rule 9: source −A in X, FX_EXCHANGE +A in X, FX_EXCHANGE −B in Y, target +B in Y.
      const fxIn = ps.find((p) => p.accountId === fx && signOf(p.amount) > 0)
      const fxOut = ps.find((p) => p.accountId === fx && signOf(p.amount) < 0)
      const from = ps.find((p) => p !== fxIn && p !== fxOut && signOf(p.amount) < 0)
      const to = ps.find((p) => p !== fxIn && p !== fxOut && signOf(p.amount) > 0)
      if (ps.length !== 4 || !fxIn || !fxOut || !from || !to || !ps.every(plain) || from.currency !== fxIn.currency
        || to.currency !== fxOut.currency || from.currency === to.currency || !equal(from.amount, negate(fxIn.amount))
        || !equal(to.amount, negate(fxOut.amount))) return undefined
      return {
        accountId: id(from.accountId), currency: from.currency, amount: abs(from.amount),
        toAccountId: id(to.accountId), toCurrency: to.currency, toAmount: to.amount,
      }
    }
    default:
      return undefined
  }
}

/** The accounts that `field` of the form may take: open accounts, and the one it has already. */
export function accountChoices(ledger: Ledger, form: EntryForm, field: 'accountId' | 'toAccountId'): Account[] {
  const current = form[field]
  const unallocated = accountWithCode(ledger, UNALLOCATED)?.id
  const loans = accountWithCode(ledger, LOANS)?.id
  const fits = (a: Account) => {
    if (a.system) return false
    switch (form.tab) {
      // Expense, income and exchange have no counterparty to give an account that needs one (rule 8).
      case 'expense':
      case 'income':
        return !a.requiresCounterparty && a.id !== unallocated
      case 'exchange':
        return !a.requiresCounterparty
      case 'loan':
        return !a.requiresCounterparty && a.id !== loans
      default:
        return true
    }
  }
  return ledger.accounts.filter((a) => String(a.id) === current || (!a.archived && fits(a)))
}

/** Whether a transfer touches an account whose balances are kept per counterparty, which then needs one (rule 8). */
export const transferNeedsCounterparty = (form: EntryForm, ledger: Ledger) =>
  [form.accountId, form.toAccountId].some((id) => id !== '' && accountById(ledger, Number(id))?.requiresCounterparty)

/** The form with another tab. A category of the wrong type for the new tab is dropped. */
export function switchTab(form: EntryForm, tab: Tab, ledger: Ledger): EntryForm {
  const type = categoryById(ledger, idOrNull(form.categoryId))?.type
  const fits = tab === 'expense' ? type === 'EXPENSE' : tab === 'income' ? type === 'INCOME' : true
  return { ...form, tab, categoryId: fits ? form.categoryId : '' }
}

/**
 * The form with this payee. A known payee brings the category of their latest entry along (lastCategoryId), if it
 * suits the tab: an expense category for an expense, an income category for an income.
 */
export function withPayee(form: EntryForm, payee: string, ledger: Ledger): EntryForm {
  const last = categoryById(ledger, counterpartyNamed(ledger, payee)?.lastCategoryId)
  const fits = last && !last.archived
    && (form.tab === 'expense' && last.type === 'EXPENSE' || form.tab === 'income' && last.type === 'INCOME')
  return { ...form, payee, categoryId: fits ? String(last.id) : form.categoryId }
}

/** The form with this account; its currency follows the account's default currency, if it has one. */
export function withAccount(form: EntryForm, field: 'accountId' | 'toAccountId', id: string, ledger: Ledger): EntryForm {
  const currency = accountById(ledger, idOrNull(id))?.defaultCurrency
  const currencyField = field === 'toAccountId' && form.tab === 'exchange' ? 'toCurrency'
    : field === 'accountId' ? 'currency' : undefined
  return { ...form, [field]: id, ...(currency && currencyField ? { [currencyField]: currency } : {}) }
}

/** The posting with this account: its currency follows the account's default, and only EQUITY keeps a category. */
export function postingWithAccount(posting: PostingDraft, id: string, ledger: Ledger): PostingDraft {
  const account = accountById(ledger, idOrNull(id))
  return {
    ...posting,
    accountId: id,
    currency: account?.defaultCurrency ?? posting.currency,
    categoryId: account?.type === 'EQUITY' ? posting.categoryId : '',
  }
}

/** The two parts of a split expense, for showing live; undefined until amount and share are valid. */
export function sharePreview(form: EntryForm) {
  const amount = parseAmount(form.amount)
  const percent = parseAmount(form.sharePercent)
  if (form.tab !== 'expense' || !form.split || amount === undefined || amountProblem(form.amount) !== undefined
    || percent === undefined || percentProblem(form.sharePercent) !== undefined) return undefined
  return splitShared(form.refund ? negate(amount) : amount, percent)
}

/** What the postings of Advanced sum to, per currency, in the order the currencies first appear. */
export function postingBalances(postings: PostingDraft[]) {
  const totals = new Map<string, string[]>()
  for (const p of postings) {
    const amount = parseAmount(p.amount)
    if (p.currency.trim() === '' || amount === undefined) continue
    const currency = p.currency.trim().toUpperCase()
    totals.set(currency, [...totals.get(currency) ?? [], amount])
  }
  return [...totals].map(([currency, amounts]) => {
    const total = sum(amounts)
    return { currency, sum: total, balanced: isZero(total) }
  })
}

/** Whether Advanced may be saved: amounts in at least two postings, adding up to zero in every currency. */
export function postingsBalance(postings: PostingDraft[]) {
  const balances = postingBalances(postings)
  return postings.filter((p) => parseAmount(p.amount) !== undefined).length >= 2 && balances.every((b) => b.balanced)
}

const MEMO_MAX_LENGTH = 500

/** What the user has to fix before the form can be sent; empty if nothing. */
export function validate(form: EntryForm, ledger: Ledger): FieldErrors {
  const errors: FieldErrors = {}
  const add = (field: string, message: string) => { (errors[field] ??= []).push(message) }
  const required = (field: keyof EntryForm, message: string) => { if (String(form[field]).trim() === '') add(field, message) }
  const money = (amount: 'amount' | 'toAmount', currency: 'currency' | 'toCurrency') => {
    const problem = amountProblem(form[amount])
    if (problem) add(amount, problem)
    const currencyProblem = currencyCodeProblem(form[currency])
    if (currencyProblem) add(currency, currencyProblem)
  }

  required('date', 'Choose a date.')
  if ([...form.memo].length > MEMO_MAX_LENGTH) add('memo', `Keep the memo to ${MEMO_MAX_LENGTH} characters.`)
  switch (form.tab) {
    case 'expense':
    case 'income':
      required('accountId', 'Choose an account.')
      money('amount', 'currency')
      required('categoryId', 'Choose a category.')
      if (!accountWithCode(ledger, UNALLOCATED)) add('', 'You have no Unallocated account (code UNALLOCATED), which income and expenses need.')
      if (form.tab === 'expense' && form.split) {
        const problem = percentProblem(form.sharePercent)
        if (problem) add('sharePercent', problem)
        if (!sharedAccount(ledger)) add('', 'You have no family account (code FAMILY_DEBT) to split with.')
      }
      break
    case 'transfer':
      required('accountId', 'Choose where the money comes from.')
      required('toAccountId', 'Choose where the money goes.')
      if (form.accountId !== '' && form.accountId === form.toAccountId) add('toAccountId', 'Choose another account than the one the money comes from.')
      money('amount', 'currency')
      if (transferNeedsCounterparty(form, ledger)) required('counterparty', 'This account keeps balances per person; enter whom this is with.')
      break
    case 'loan':
      required('counterparty', form.loan === 'given' ? 'Enter who borrowed the money.' : 'Enter who paid back.')
      required('accountId', form.loan === 'given' ? 'Choose the account the money came from.' : 'Choose the account the money went to.')
      money('amount', 'currency')
      if (!accountWithCode(ledger, LOANS)) add('', 'You have no loans account (code LOANS_ASSET) to keep loans in.')
      break
    case 'exchange':
      required('accountId', 'Choose where the money comes from.')
      required('toAccountId', 'Choose where the money goes.')
      money('amount', 'currency')
      money('toAmount', 'toCurrency')
      if (form.currency.trim() !== '' && form.currency.trim().toUpperCase() === form.toCurrency.trim().toUpperCase()) {
        add('toCurrency', 'Choose another currency. Money moved within one currency is a transfer.')
      }
      break
    case 'advanced':
      if (form.postings.length < 2) add('postings', 'An entry needs at least two postings.')
      form.postings.forEach((p, i) => {
        const field = (name: string) => `postings.${i}.${name}`
        const account = accountById(ledger, idOrNull(p.accountId))
        if (!account) add(field('accountId'), 'Choose an account.')
        const currencyProblem = currencyCodeProblem(p.currency)
        if (currencyProblem) add(field('currency'), currencyProblem)
        const amount = amountProblem(p.amount, { signed: true })
        if (amount) add(field('amount'), amount)
        if (account?.requiresCounterparty && p.counterparty.trim() === '') add(field('counterparty'), 'This account needs a counterparty.')
      })
      postingBalances(form.postings).filter((b) => !b.balanced).forEach((b) =>
        add('postings', `The amounts in ${b.currency} must add up to zero.`))
      break
  }
  return errors
}

function currencyCodeProblem(code: string) {
  return /^[A-Za-z]{3}$/.test(code.trim()) ? undefined : 'Enter a three-letter currency code, such as EUR.'
}

/** Payee and counterparty names in the form that aren't counterparties yet; saving creates them first. */
export function newCounterpartyNames(form: EntryForm, ledger: Ledger): string[] {
  const names = form.tab === 'advanced' ? [form.payee, ...form.postings.map((p) => p.counterparty)]
    : form.tab === 'loan' ? [form.counterparty]
      : form.tab === 'transfer' && transferNeedsCounterparty(form, ledger) ? [form.payee, form.counterparty]
        : [form.payee]
  const unique = new Map(names.map((n) => n.trim()).filter((n) => n !== '' && !counterpartyNamed(ledger, n))
    .map((n) => [n.toLocaleLowerCase(), n]))
  return [...unique.values()]
}

/**
 * The command that writes the entry. Call it on a form that `validate` passes, with a ledger that has every
 * counterparty the form names.
 */
export function toCommand(form: EntryForm, ledger: Ledger): EntryCommand {
  const party = (name: string) => counterpartyNamed(ledger, name)?.id ?? null
  const entryDate = form.date
  const memo = form.memo.trim() === '' ? null : form.memo.trim()
  const payeeId = party(form.payee)
  const amount = parseAmount(form.amount)!
  const signed = form.refund ? negate(amount) : amount
  const currency = form.currency.trim().toUpperCase()
  const accountId = idOrNull(form.accountId)
  switch (form.tab) {
    case 'expense':
      return form.split
        ? {
          kind: 'SHARED_EXPENSE', entryDate, payeeId, memo, accountId, currency, total: signed,
          categoryId: idOrNull(form.categoryId), shareRatio: ratioFromPercent(parseAmount(form.sharePercent)!),
        }
        : { kind: 'EXPENSE', entryDate, payeeId, memo, accountId, currency, amount: signed, categoryId: idOrNull(form.categoryId) }
    case 'income':
      return { kind: 'INCOME', entryDate, payeeId, memo, accountId, currency, amount: signed, categoryId: idOrNull(form.categoryId) }
    case 'transfer':
      return {
        kind: 'TRANSFER', entryDate, payeeId, memo, fromAccountId: accountId, toAccountId: idOrNull(form.toAccountId),
        currency, amount, counterpartyId: transferNeedsCounterparty(form, ledger) ? party(form.counterparty) : null,
      }
    case 'loan':
      return form.loan === 'given'
        ? { kind: 'LOAN_GIVEN', entryDate, memo, fromAccountId: accountId, counterpartyId: party(form.counterparty), currency, amount }
        : { kind: 'LOAN_REPAID', entryDate, memo, toAccountId: accountId, counterpartyId: party(form.counterparty), currency, amount }
    case 'exchange':
      return {
        kind: 'CURRENCY_EXCHANGE', entryDate, payeeId, memo, fromAccountId: accountId, fromCurrency: currency,
        fromAmount: amount, toAccountId: idOrNull(form.toAccountId), toCurrency: form.toCurrency.trim().toUpperCase(),
        toAmount: parseAmount(form.toAmount)!,
      }
    case 'advanced':
      return {
        kind: 'MANUAL', entryDate, payeeId, memo,
        postings: form.postings.map((p) => ({
          accountId: idOrNull(p.accountId),
          currency: p.currency.trim().toUpperCase(),
          amount: parseAmount(p.amount)!,
          categoryId: idOrNull(p.categoryId),
          counterpartyId: party(p.counterparty),
        })),
      }
  }
}

/** The fields the tab shows; messages for others go to the top of the form. */
function shownFields(form: EntryForm, ledger: Ledger): string[] {
  const payee = form.tab === 'loan' ? [] : ['payee']
  const tabFields: Record<Tab, string[]> = {
    expense: ['accountId', 'currency', 'amount', 'categoryId', ...(form.split ? ['sharePercent'] : [])],
    income: ['accountId', 'currency', 'amount', 'categoryId'],
    transfer: ['accountId', 'toAccountId', 'currency', 'amount', ...(transferNeedsCounterparty(form, ledger) ? ['counterparty'] : [])],
    loan: ['counterparty', 'accountId', 'currency', 'amount'],
    exchange: ['accountId', 'currency', 'amount', 'toAccountId', 'toCurrency', 'toAmount'],
    advanced: ['postings'],
  }
  return ['date', 'memo', ...payee, ...tabFields[form.tab]]
}

/** A command's own checks (Problems on the backend), by the start of their message. */
const COMMAND_MESSAGES: [RegExp, string][] = [
  [/^the entry date/, 'date'],
  [/^the memo/, 'memo'],
  [/^payee /, 'payee'],
  [/^the (source )?account is missing/, 'accountId'],
  [/^the target account|^the two accounts/, 'toAccountId'],
  [/^the (source )?currency/, 'currency'],
  [/^the target currency|^the two currencies/, 'toCurrency'],
  [/^the (source )?amount|^the total/, 'amount'],
  [/^the target amount/, 'toAmount'],
  [/^the category/, 'categoryId'],
  [/^the share ratio/, 'sharePercent'],
  [/^the borrower/, 'counterparty'],
  [/^the list of postings|^a posting is missing|^an entry needs|^the postings in /, 'postings'],
]

/** Fields of the request body (400 `errors`), by their name in the command. */
function bodyField(form: EntryForm, path: string): string {
  const posting = /^postings\[(\d+)]\.(\w+)$/.exec(path)
  if (posting) return `postings.${posting[1]}.${posting[2] === 'counterpartyId' ? 'counterparty' : posting[2]}`
  const fields: Record<string, string> = {
    entryDate: 'date', payeeId: 'payee', memo: 'memo', accountId: 'accountId', fromAccountId: 'accountId',
    toAccountId: form.tab === 'loan' ? 'accountId' : 'toAccountId', currency: 'currency', fromCurrency: 'currency',
    toCurrency: 'toCurrency', amount: 'amount', total: 'amount', fromAmount: 'amount', toAmount: 'toAmount',
    categoryId: 'categoryId', shareRatio: 'sharePercent', counterpartyId: 'counterparty', postings: 'postings',
  }
  return fields[path] ?? ''
}

/**
 * The field of the form that built posting `n` (from 1) of the entry, for a message about its `part`. The order is
 * that of the backend's builders, such as expense: the account, then UNALLOCATED with the category.
 */
function postingField(form: EntryForm, n: number, part: 'account' | 'currency' | 'amount' | 'category' | 'counterparty') {
  if (form.tab === 'advanced') {
    return `postings.${n - 1}.${{ account: 'accountId', currency: 'currency', amount: 'amount', category: 'categoryId', counterparty: 'counterparty' }[part]}`
  }
  const second = form.tab === 'exchange' && n > 2
  if (part === 'currency') return second ? 'toCurrency' : 'currency'
  if (part === 'amount') return second ? 'toAmount' : 'amount'
  if (part === 'category') return 'categoryId'
  if (part === 'counterparty' && (form.tab === 'transfer' || form.tab === 'loan')) return 'counterparty'
  const accounts: Record<Exclude<Tab, 'advanced'>, string[]> = {
    expense: ['accountId'],
    income: ['accountId'],
    transfer: ['accountId', 'toAccountId'],
    loan: form.loan === 'given' ? ['accountId', 'counterparty'] : ['counterparty', 'accountId'],
    exchange: ['accountId', '', '', 'toAccountId'],
  }
  return accounts[form.tab][n - 1] ?? ''
}

/** A validator message about one posting, in words for the field it is shown at. */
function postingMessage(form: EntryForm, message: string): string {
  if (/requires a counterparty/.test(message)) {
    return form.tab === 'advanced' || form.tab === 'transfer' || form.tab === 'loan'
      ? 'This account needs a counterparty.'
      : 'This account keeps balances per person; record this in Transfer or Advanced.'
  }
  const type = /^category \S+ is \w+, and an entry of kind \w+ needs (\w+) categories/.exec(message)?.[1]
  if (type) return `Choose ${type === 'INCOME' ? 'an income' : 'an expense'} category.`
  if (/only postings to EQUITY accounts can have a category/.test(message)) return 'Only equity accounts, such as Unallocated, take a category.'
  return sentence(message)
}

/**
 * The messages of a failed save, at the fields they are about: a 400 names the fields, a 422 lists the ledger rules
 * the entry breaks (LedgerValidator), in words that mention the field or the posting. The rest goes to ''.
 */
export function serverErrors(form: EntryForm, ledger: Ledger, error: unknown): FieldErrors {
  const errors: FieldErrors = {}
  const shown = shownFields(form, ledger)
  const add = (field: string, message: string) => {
    const at = field.startsWith('postings.') && form.tab === 'advanced' || shown.includes(field) ? field : ''
    const text = at === '' ? sentence(message) : message
    // Both postings of an expense can break the same rule, such as an unknown currency: one message is enough.
    if (!errors[at]?.includes(text)) (errors[at] ??= []).push(text)
  }
  if (!(error instanceof ApiError) || error.errors.length === 0 && error.violations.length === 0) {
    add('', error instanceof Error ? error.message : 'Saving failed.')
    return errors
  }
  error.errors.forEach((e) => add(bodyField(form, e.field), sentence(e.message)))
  error.violations.forEach((violation) => {
    const posting = /^posting (\d+)(?: \([^)]*\))?: (.*)$/.exec(violation)
    if (posting) {
      const message = posting[2]
      const part = /currency/.test(message) ? 'currency' : /amount/.test(message) ? 'amount'
        : /categor/.test(message) ? 'category' : /counterparty/.test(message) ? 'counterparty' : 'account'
      const field = postingField(form, Number(posting[1]), part)
      add(field, field === '' ? violation : postingMessage(form, message))
      return
    }
    const field = COMMAND_MESSAGES.find(([pattern]) => pattern.test(violation))?.[1] ?? ''
    add(field, sentence(violation))
  })
  return errors
}

export const idOrNull = (id: string) => (id === '' ? null : Number(id))
