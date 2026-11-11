import type { Entry, FamilyLedger, FamilyMember, FamilyRecord, SplitRule } from './api'
import { basisPointsToPercent, equalShares } from './basisPoints'
import { formFromEntry, isFamilyRecord, type EntryForm } from './entryForm'
import { newSplit, sharers, type SplitForm } from './expenseForm'
import { fromMinor, toMinor } from './minorUnits'
import { accountById, categoryById, counterpartyById, type Ledger } from './ledger'
import { amountProblem, parseAmount } from './money'

// New entry's similar entries without React (UX-8, D-453 to D-462): what the form asks for, and what Copy puts in it.
// The backend's matching is EntryMatchService's; the form only says what to look for.

/** A saved entry like the one being typed, as GET /api/entries/matches gives it (D-463). */
export interface EntryMatch {
  entry: Entry
  /** A personal entry's amount on its account, or a family row's record's, above 0. */
  amount: string | null
  currency: string | null
  categoryId: number | null
  refund: boolean
}
export interface EntryMatchPage { content: EntryMatch[]; page: number; size: number; totalElements: number; totalPages: number }

/** What the form looks for; a field left out is no criterion. */
export interface Criteria {
  type: 'EXPENSE' | 'INCOME'
  payee?: string
  memo?: string
  accountId?: string
  categoryId?: string
  /** The typed amount as `parseAmount` writes it, without its sign (D-459). */
  amount?: string
  currency?: string
}

/** A text counts from 2 characters, trimmed (D-458). */
const text = (value: string) => (value.trim().length >= 2 ? value.trim() : undefined)

/**
 * The criteria of the form as it shows its fields (D-456): only on Expense and Income; with a family expense or income
 * on, the family category and the private note; a field the form hides is none (a family income has no Payer). The
 * category is one only once the user picked it (`categoryPicked`), not while it is the payee's last category that
 * `withPayee` put in. Undefined without a criterion: no list and no request.
 */
export function criteriaOf(form: EntryForm, categoryPicked: boolean): Criteria | undefined {
  if (form.tab !== 'expense' && form.tab !== 'income') return undefined
  const family = isFamilyRecord(form)
  const parsed = parseAmount(form.amount)
  const amount = parsed === undefined ? undefined : parsed.replace(/^-/, '')
  const currency = form.currency.trim().toUpperCase()
  const category = family ? form.familyCategoryId : categoryPicked ? form.categoryId : ''
  const criteria: Criteria = {
    type: form.tab === 'income' ? 'INCOME' : 'EXPENSE',
    payee: family && form.tab === 'income' ? undefined : text(form.payee),
    memo: text(form.memo),
    accountId: form.accountId || undefined,
    categoryId: category || undefined,
    amount,
  }
  if (amount !== undefined && /^[A-Z]{3}$/.test(currency)) criteria.currency = currency
  const { type: _type, currency: _currency, ...given } = criteria
  return Object.values(given).some((v) => v !== undefined) ? criteria : undefined
}

/** The request's query for page `page` of 10 (D-460). */
export function matchesQuery(criteria: Criteria, page = 0): string {
  const query = new URLSearchParams()
  for (const [name, value] of Object.entries(criteria)) if (value !== undefined) query.set(name, value)
  query.set('page', String(page))
  query.set('size', String(MATCHES_PAGE))
  return `/entries/matches?${query}`
}

export const MATCHES_PAGE = 10

/** The account a row shows: the entry's line that isn't on an EQUITY account nor on a family budget's debt account. */
export function matchAccount(match: EntryMatch, ledger: Ledger) {
  const lines = match.entry.postings.map((p) => accountById(ledger, p.accountId))
  return lines.find((a) => a && a.type !== 'EQUITY' && !(a.system && a.code.startsWith('FAMILY_DEBT')))
    ?? lines.find((a) => a && a.type !== 'EQUITY')
}

/** The sign a row's amount shows, as the Entries list shows it: money out with a minus, a refund's money back with a plus. */
export function matchSign(match: EntryMatch, type: Criteria['type']): -1 | 1 {
  const out = type === 'EXPENSE' ? !match.refund : match.refund
  return out ? -1 : 1
}

/** Why a copied field stays empty, by the field it is shown under. */
export type CopyNotes = Partial<Record<'accountId' | 'categoryId' | 'familyCategoryId', string>>

export interface Copied {
  form: EntryForm
  /** False for an entry that opens in Advanced, whose postings don't fit its tab, as on its own page. */
  simple: boolean
  notes: CopyNotes
}

/** Whether the form's amount is one Copy keeps, with its currency (D-461): a valid amount, typed by the user. */
export const keepsAmount = (form: EntryForm) => form.amount.trim() !== '' && amountProblem(form.amount) === undefined

const ARCHIVED_ACCOUNT = 'The entry’s account is archived, so it isn’t copied. Choose another.'
const ARCHIVED_CATEGORY = 'The entry’s category is archived, so it isn’t copied. Choose another.'
const PER_PERSON_ACCOUNT = 'The entry’s account keeps balances per person, which this form doesn’t take. Choose another.'

/**
 * A personal row copied into the form (D-461): as `formFromEntry` opens it, Family expense or income off, with the
 * form's date, and its amount and currency when Amount holds a valid one. One that opens in Advanced keeps only the
 * date. An archived account or category isn't copied, with a note why.
 */
export function copyPersonal(form: EntryForm, entry: Entry, ledger: Ledger): Copied {
  const { form: opened, simple } = formFromEntry(entry, ledger)
  const copied: EntryForm = {
    ...opened, date: form.date, familyId: '', familyCategoryId: '', familyComment: '', familySplit: newSplit(),
  }
  // An entry that opens in Advanced keeps nothing of the form but the date.
  if (!simple || copied.tab === 'advanced') return { form: copied, simple, notes: {} }
  if (keepsAmount(form)) {
    copied.amount = form.amount
    copied.currency = form.currency
  }
  const notes: CopyNotes = {}
  const account = accountById(ledger, copied.accountId === '' ? null : Number(copied.accountId))
  if (account?.archived) {
    copied.accountId = ''
    notes.accountId = ARCHIVED_ACCOUNT
  }
  if (categoryById(ledger, copied.categoryId === '' ? null : Number(copied.categoryId))?.archived) {
    copied.categoryId = ''
    notes.categoryId = ARCHIVED_CATEGORY
  }
  return { form: copied, simple, notes }
}

/**
 * The split a copied family record gets as a new record's (D-461): ONE_MEMBER, PERCENT and AMOUNT as stored, among the
 * members who share the form's date (one who shares it without a stored share gets 0); EQUAL becomes the rule when the
 * budget's rule is EQUAL now, else equal percentages among those members.
 */
export function copySplit(record: FamilyRecord, members: FamilyMember[], splitRule: SplitRule, date: string): SplitForm {
  const form = newSplit()
  const rows = sharers(members, date)
  const stored = new Map(record.shares.map((s) => [s.member.memberId, s]))
  switch (record.splitMethod) {
    case 'ONE_MEMBER': {
      const on = record.shares.find((s) => s.amount === record.amount) ?? record.shares[0]
      return { ...form, mode: 'ONE_MEMBER', member: on ? String(on.member.memberId) : '' }
    }
    case 'PERCENT':
      if (record.shares.every((s) => s.basisPoints !== null)) {
        return {
          ...form, mode: 'PERCENT',
          percents: Object.fromEntries(rows.map((m) => [m.id, basisPointsToPercent(stored.get(m.id)?.basisPoints ?? 0)])),
        }
      }
      break
    case 'EQUAL':
    case null:
      if (splitRule === 'EQUAL') return form
      {
        const equal = equalShares(rows.length)
        return { ...form, mode: 'PERCENT', percents: Object.fromEntries(rows.map((m, i) => [m.id, basisPointsToPercent(equal[i])])) }
      }
  }
  return {
    ...form, mode: 'AMOUNT',
    amounts: Object.fromEntries(rows.map((m) => {
      const share = stored.get(m.id)
      return [m.id, fromMinor(share ? toMinor(share.amount, record.currency) ?? 0n : 0n, record.currency)]
    })),
  }
}

/**
 * A family row copied into the form (D-461): Family expense or income on, in the record's budget, with its family
 * category, comment and refund, its amount and currency unless the form's are kept, the payment's account ("Specify
 * later" leaves the form's) and payee, the entry's memo as the private note, and the split as a new record's
 * (`copySplit`). The date stays the form's. An archived account or family category isn't copied, nor an account that
 * keeps balances per person, which the personal form doesn't offer.
 */
export function copyFamily(form: EntryForm, match: EntryMatch, record: FamilyRecord, budget: FamilyLedger,
  members: FamilyMember[], ledger: Ledger): Copied {
  const notes: CopyNotes = {}
  const tab = record.type === 'INCOME' ? 'income' : 'expense'
  const keep = keepsAmount(form)
  let accountId = form.accountId
  const paid = record.yourPayment
  if (paid && !paid.later && paid.accountId !== null) {
    const account = accountById(ledger, paid.accountId)
    if (account?.archived) {
      accountId = ''
      notes.accountId = ARCHIVED_ACCOUNT
    } else if (account?.requiresCounterparty) {
      accountId = ''
      notes.accountId = PER_PERSON_ACCOUNT
    } else {
      accountId = String(paid.accountId)
    }
  }
  const payeeId = paid?.payeeId ?? match.entry.payeeId
  let familyCategoryId = record.category ? String(record.category.id) : ''
  if (record.category?.archived) {
    familyCategoryId = ''
    notes.familyCategoryId = ARCHIVED_CATEGORY
  }
  const copied: EntryForm = {
    ...form,
    tab,
    familyId: String(budget.id),
    familyCategoryId,
    familyComment: record.comment ?? '',
    familySplit: copySplit(record, members, budget.splitRule, form.date),
    refund: tab === 'expense' && record.refund === true,
    split: false,
    categoryId: '',
    accountId,
    payee: tab === 'income' ? '' : counterpartyById(ledger, payeeId)?.name ?? '',
    memo: match.entry.memo ?? '',
    amount: keep ? form.amount : record.amount,
    currency: keep ? form.currency : record.currency,
  }
  return { form: copied, simple: true, notes }
}
