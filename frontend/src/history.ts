import { formatDate, type MemberRef } from './api'
import { SPLIT_LABELS } from './family'
import { categoryLabel, type Ledger } from './ledger'
import { formatMoney } from './money'

// The History panel without React: the answer's shape, the labels of what happened and of each field, and every value
// as text. Nothing here reads a clock; dates and instants go through `formatDate` and the caller's `formatInstant`.

/** The record kinds that have a history endpoint (a family category has none). */
export type RecordKind = 'ENTRY' | 'ACCOUNT' | 'CATEGORY' | 'COUNTERPARTY' | 'FAMILY_RECORD'

export interface HistoryActor { kind: string; memberId: number | null; name: string | null }

/**
 * One changed field. A personal record's `old` and `new` are the stored values (ids by id, amounts as decimal strings);
 * a family record's are text with members and categories named, and `member`, `oldCurrency` and `newCurrency` of a
 * share or an amount.
 */
export interface HistoryChange {
  field: string
  old: unknown
  new: unknown
  member?: MemberRef | null
  oldCurrency?: string | null
  newCurrency?: string | null
}

export interface HistoryVersion {
  seq: number
  revision: number
  op: string
  at: string
  cause: string | null
  actor: HistoryActor
  changes: HistoryChange[]
}

export interface HistoryPage { content: HistoryVersion[]; page: number; size: number; total: number; totalPages: number }

/** Versions per request. */
export const HISTORY_PAGE_SIZE = 20

export const historyPath = (kind: RecordKind, id: number, page: number) =>
  `/history/${kind}/${id}?page=${page}&size=${HISTORY_PAGE_SIZE}`

export const OP_LABELS: Record<string, string> = {
  CREATE: 'Created', UPDATE: 'Changed', DELETE: 'Deleted', ARCHIVE: 'Archived', RESTORE: 'Restored',
  BASELINE: 'State when history began',
}
export const opLabel = (op: string) => OP_LABELS[op] ?? 'Changed'

/** The causes worth saying; an ordinary edit and the baseline's own cause say nothing more than the op. */
export const CAUSE_LABELS: Record<string, string | null> = {
  EDIT: null,
  BASELINE: null,
  IMPORT: 'Import',
  DEMO: 'Demo data',
  PROVISION: 'Set up automatically',
  LEAVE: 'A member left the family budget',
  RETURN: 'A member returned to the family budget',
  MERGE: 'Merged with another item',
  RESTORE: 'Restored',
  RECONCILE: 'Changes noticed later',
}

/** The cause as text, or null when there is nothing to say (none given, or an ordinary edit). */
export function causeLabel(cause: string | null | undefined): string | null {
  if (cause === null || cause === undefined || cause === '') return null
  return cause in CAUSE_LABELS ? CAUSE_LABELS[cause] : 'Other change'
}

/** Who made a version: a member by name, "Import", "System", or "You" for a user the answer doesn't name. */
export function actorLabel(actor: HistoryActor): string {
  if (actor.kind === 'IMPORT') return 'Import'
  if (actor.kind === 'SYSTEM') return 'System'
  return actor.name ?? (actor.kind === 'USER' ? 'You' : 'Someone')
}

const FIELD_LABELS: Record<RecordKind, Record<string, string>> = {
  ENTRY: {
    date: 'Date', kind: 'Type', payee: 'Payee', memo: 'Memo', externalRef: 'Import reference',
    postings: 'Accounts and amounts',
  },
  ACCOUNT: {
    code: 'Code', name: 'Name', type: 'Type', defaultCurrency: 'Currency', requiresCounterparty: 'Kept per person',
    system: 'System account', archived: 'Archived',
  },
  CATEGORY: { code: 'Code', name: 'Name', type: 'Type', archived: 'Archived' },
  COUNTERPARTY: { name: 'Name', kind: 'Kind', archived: 'Archived' },
  FAMILY_RECORD: {
    date: 'Date', category: 'Category', amount: 'Amount', currency: 'Currency', refund: 'Refund', payer: 'Paid by',
    payee: 'Received by', splitMethod: 'Split', comment: 'Comment', originalAmount: 'Amount paid',
  },
}

export const OTHER_FIELD = 'Other detail'
export const REMOVED = 'Removed item'
export const NO_VALUE = '—'

/** The name of a field in the words the forms use; "Other detail" for one that isn't known. */
export function fieldLabel(kind: RecordKind, change: Pick<HistoryChange, 'field' | 'member'>): string {
  if (kind === 'FAMILY_RECORD' && change.field === 'share') {
    return `Share of ${change.member?.displayName ?? 'a former member'}`
  }
  return FIELD_LABELS[kind][change.field] ?? OTHER_FIELD
}

/** What the screen can name of a personal record's references; an id it can't name shows as "Removed item". */
export interface Names {
  account: (id: number) => string | undefined
  category: (id: number) => string | undefined
  counterparty: (id: number) => string | undefined
}

export const namesOf = (ledger: Ledger | undefined): Names => ({
  account: (id) => ledger?.accounts.find((a) => a.id === id)?.name,
  category: (id) => {
    const category = ledger?.categories.find((c) => c.id === id)
    return category ? categoryLabel(category) : undefined
  },
  counterparty: (id) => ledger?.counterparties.find((c) => c.id === id)?.name,
})

const ENUM_LABELS: Record<string, Record<string, string>> = {
  'ENTRY.kind': {
    EXPENSE: 'Expense', INCOME: 'Income', TRANSFER: 'Transfer', SHARED_EXPENSE: 'Shared expense', LOAN_GIVEN: 'Loan given',
    LOAN_REPAID: 'Loan repaid', CURRENCY_EXCHANGE: 'Exchange', OPENING_BALANCE: 'Opening balance', MANUAL: 'Correction',
    FAMILY_SHARE: 'Family share', FAMILY_PAYMENT: 'Family payment', FAMILY_SETTLEMENT: 'Family settlement',
    FAMILY_OPENING: 'Family opening balance', FAMILY_CORRECTION: 'Family correction',
  },
  'ACCOUNT.type': { ASSET: 'Asset', LIABILITY: 'Liability', EQUITY: 'Equity' },
  'CATEGORY.type': { EXPENSE: 'Expense', INCOME: 'Income' },
  'COUNTERPARTY.kind': { MERCHANT: 'Merchant', PERSON: 'Person', ORGANIZATION: 'Organization' },
  'FAMILY_RECORD.splitMethod': SPLIT_LABELS as Record<string, string>,
}

const DATE_FIELDS = new Set(['date'])
const ID_FIELDS: Record<string, keyof Names> = { payee: 'counterparty' }
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** An amount of the family journal, "9000.00 RUB", as money; any other text as it is. */
const originalAmountText = (value: string): string => {
  const [amount, code] = value.split(' ')
  return code && /^-?\d+(\.\d+)?$/.test(amount) ? formatMoney(amount, code) : value
}

/** One posting of an entry: "Cash −12.00 EUR, Groceries, Ivan" with the account, the amount and what it names. */
function postingText(posting: unknown, names: Names): string {
  if (!isObject(posting)) return NO_VALUE
  const name = (lookup: (id: number) => string | undefined, id: unknown) =>
    typeof id === 'number' ? lookup(id) ?? REMOVED : undefined
  const amount = typeof posting.amount === 'string' && typeof posting.currency === 'string'
    ? formatMoney(posting.amount, posting.currency, { signed: true }) : undefined
  return [name(names.account, posting.account) ?? REMOVED, amount, name(names.category, posting.category),
    name(names.counterparty, posting.counterparty)].filter((part) => part !== undefined).join(', ')
}

/**
 * A value as text: null as a dash, a yes or no, a date in the user's format, money in its currency, a stored id as the
 * name the screen has for it (else "Removed item"), a known code as its label. `currency` is for the amounts of a
 * family record.
 */
export function formatValue(kind: RecordKind, field: string, value: unknown, names: Names, currency?: string | null): string {
  if (value === null || value === undefined) return NO_VALUE
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (kind === 'FAMILY_RECORD' && typeof value === 'string') {
    if (field === 'refund') return value === 'true' ? 'Yes' : value === 'false' ? 'No' : value
    if ((field === 'amount' || field === 'share') && currency && /^-?\d+(\.\d+)?$/.test(value)) return formatMoney(value, currency)
    if (field === 'originalAmount') return originalAmountText(value)
  }
  if (kind === 'ENTRY' && field === 'postings') {
    if (!Array.isArray(value)) return OTHER_DETAIL_VALUE
    return value.length === 0 ? NO_VALUE : value.map((p) => postingText(p, names)).join('; ')
  }
  if (typeof value === 'string') {
    if (DATE_FIELDS.has(field) && ISO_DAY.test(value)) return formatDate(value)
    return ENUM_LABELS[`${kind}.${field}`]?.[value] ?? value
  }
  if (typeof value === 'number') {
    const lookup = kind === 'ENTRY' ? ID_FIELDS[field] : undefined
    return lookup ? names[lookup](value) ?? REMOVED : String(value)
  }
  return OTHER_DETAIL_VALUE
}

/** The value of a field that is a list or an object we have no words for. */
const OTHER_DETAIL_VALUE = 'Several details'

/** A change as "Field: old → new". */
export function changeLine(kind: RecordKind, change: HistoryChange, names: Names): string {
  const label = fieldLabel(kind, change)
  const before = formatValue(kind, change.field, change.old, names, change.oldCurrency ?? change.newCurrency)
  const after = formatValue(kind, change.field, change.new, names, change.newCurrency ?? change.oldCurrency)
  return `${label}: ${before} → ${after}`
}

/** Versions of a later page added to the loaded ones; one already loaded (a new version moved the pages) is kept once. */
export function withMore(loaded: HistoryVersion[], more: HistoryVersion[]): HistoryVersion[] {
  const seen = new Set(loaded.map((v) => v.seq))
  return [...loaded, ...more.filter((v) => !seen.has(v.seq))]
}

/** Whether the answer's page was the last one. */
export const isLastPage = (page: HistoryPage) => page.page + 1 >= page.totalPages
