import { useCallback, useEffect, useRef, useState } from 'react'
import { csrfToken, logIn } from './auth'

// The ledger's API (docs/adr/0001-double-entry-ledger.md). Amounts are decimal strings; see money.ts.

/** The signed-in user. `features` says what the backend has switched on; a missing field counts as off. */
/** The signed-in user: their name, their account's email (D-54, F8b) and which features are on. */
/**
 * The signed-in user. `timeZone` is their IANA time zone, null until it is set (D-100, D-101); `today` is the date in it,
 * which every default date of the screens is. The screens never work out today from the browser's clock.
 */
export interface Me {
  /** The account's name; null for one registered with an email only (F8c-fix). Show `email` then. */
  name?: string | null
  email?: string | null
  timeZone?: string | null
  today: string
  features?: { familyLedgers?: boolean }
}

/** Whether the family budget is switched on (D-25): off in production until F7. */
export const familyLedgersOn = (me: Me) => me.features?.familyLedgers === true
export type AccountType = 'ASSET' | 'LIABILITY' | 'EQUITY'
export type CategoryType = 'INCOME' | 'EXPENSE'
export interface Account {
  id: number
  code: string
  name: string
  type: AccountType
  defaultCurrency: string | null
  requiresCounterparty: boolean
  system: boolean
  archived: boolean
}
export interface Category {
  id: number
  code: string
  name: string
  type: CategoryType
  archived: boolean
  /** A family budget's category in the personal list (D-11): the budget. Left out for the user's own. */
  familyLedgerId?: number
  familyLedgerName?: string
}
export interface Counterparty {
  id: number
  name: string
  kind: 'MERCHANT' | 'PERSON' | 'ORGANIZATION' | null
  archived: boolean
  /** The category of the latest entry with this payee that has one, to preselect in the next. */
  lastCategoryId: number | null
}
export interface Settings { baseCurrency: string; sharedAccountId: number | null; defaultShareRatio: string }

// Family budgets (ADR 0003, topic I): the API calls them family ledgers, the screens family budgets.
export type SplitRule = 'EQUAL' | 'CUSTOM'
export type MemberRole = 'OWNER' | 'MEMBER'
export type MemberStatus = 'ACTIVE' | 'LEFT' | 'FORMER'
/** A family budget as its member sees it: `role` and `memberId` are the user's own. */
export interface FamilyLedger {
  id: number
  name: string
  baseCurrency: string
  splitRule: SplitRule
  role: MemberRole
  memberId: number
  createdAt: string
  /** The first day an expense may be dated, and the creator's join date (D-27). */
  startDate: string
}
/** A member as every member sees them; never a login or an email address (D-3). */
export interface FamilyMember {
  id: number
  displayName: string
  role: MemberRole
  status: MemberStatus
  joinDate: string
  /** False for a member without an account, and for a former member. */
  hasAccount: boolean
  /** Under a custom split rule, in basis points (2500 is 25.00 %); null under an equal one. */
  share: number | null
  /** When a member left or was removed, or a former member deleted their data (F6a); null while ACTIVE. */
  leftDate?: string | null
  /**
   * Whether the member took a seat by a claim (F6b, D-35): they take part in records from the budget's start date, and
   * their joinDate is the claim's, from which records post to them. Missing counts as false.
   */
  claimedSeat?: boolean
}

/**
 * What "Delete all my data" does to one of the user's family budgets (F6a, D-20), from GET /me/family-memberships:
 * their role and balance there, and whether the budget stays, passes to another owner, or is deleted.
 */
export interface FamilyMembershipImpact {
  ledgerId: number
  name: string
  role: MemberRole
  baseCurrency: string
  /** What the user owes the budget in each currency, the main one first (D-45): positive if they owe. */
  balances: CurrencyAmount[]
  outcome: 'STAYS' | 'OWNERSHIP_PASSES' | 'DELETED'
  /** Who becomes an owner, by display name, when the ownership passes. */
  newOwner: string | null
  /** The user's invites that stop working. */
  pendingInvites: number
  /** Whether the custom split rule goes back to equal shares. */
  splitRuleReset: boolean
}

export interface FamilyMemberships {
  memberships: FamilyMembershipImpact[]
  /** How many family budgets the user left: their name in those becomes "Former member". */
  left: number
}

// Invites (F5, D-17): owners create, list and revoke them; whoever holds a link looks it up, accepts or declines it.
export type InviteKind = 'NEW_MEMBER' | 'CLAIM'
export type InviteStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'REVOKED' | 'EXPIRED'
/** An invite as the owners see it: never its token. */
export interface FamilyInvite {
  id: number
  kind: InviteKind
  /** The member without an account whose place a claim takes. */
  seat?: MemberRef
  /** A claim's join date; for an accepted invite of a new member, the day they joined. */
  joinDate?: string
  createdBy: MemberRef
  createdAt: string
  expiresAt: string
  status: InviteStatus
  acceptedBy?: MemberRef
  acceptedAt?: string
  declinedAt?: string
  revokedAt?: string
}
/** The answer to creating an invite: the only one that holds its link, `…/invite#<token>`. */
export interface CreatedInvite extends FamilyInvite { link: string }
/** What an invite shows the signed-in user who holds its link, before they accept or decline it. */
export interface InviteLookup {
  ledgerName: string
  baseCurrency: string
  invitedBy: string
  kind: InviteKind
  /** The member without an account whose place a claim takes; null for a new member. */
  seatName: string | null
  /** From when the family's records are posted to the user: a claim's join date, or today. */
  joinDate: string
  expiresAt: string
  /** The family's categories that aren't archived. */
  categories: { code: string; name: string; type: CategoryType }[]
  /** The user's categories that merge into the family's with their code and type, which keeps its name. */
  merges: { categoryId: number; code: string; name: string; familyName: string; type: CategoryType }[]
  /** The user's categories with a family category's code but of the other type: they stay private. */
  keptPrivate: { categoryId: number; code: string; name: string; type: CategoryType; familyType: CategoryType }[]
  /** The user's categories the family doesn't have, which they may bring. */
  mayBring: { categoryId: number; code: string; name: string; type: CategoryType }[]
  /** The account's name, to prefill the name the other members will see. */
  displayName: string | null
  /**
   * A claim's opening balance in each currency, the main one first: what the place owes the family before the join
   * date, negative if owed (D-34, D-45); empty when nothing is owed.
   */
  openingBalances?: CurrencyAmount[]
  /** Whether the user was a member before and comes back (D-26). */
  returning?: boolean
  /** For a returning member, the correction in each currency that brings their debt in line (D-26, D-45). */
  corrections?: CurrencyAmount[]
  /**
   * For a returning member, their own entries on their former debt account dated after the join date, which belong to
   * no record of the family budget: accepting waits until they are moved or deleted (F6b, D-37). Null otherwise.
   */
  entriesAfterReturn?: EntryAfterReturn[] | null
}

/** An amount in a currency, as the per-currency lists give it (D-45). */
export interface CurrencyAmount { currency: string; amount: string }

/** One of the returning user's own entries (D-37): `amount` is what it adds to the debt that account shows. */
export interface EntryAfterReturn { entryId: number; date: string; amount: string; currency: string; memo: string | null }

// A family budget's expenses (F4a), incomes and settlements (F4d): the API calls them records. Each is in its own
// currency, its shares too (D-45); the budget's main currency is only the default and the currency of totals (D-47).
/** A member as a record, a balance or the journal names them: "Former member" once they deleted their data. */
export interface MemberRef { memberId: number; displayName: string }
/** How a record's amount is split: EQUAL comes from the budget's rule, PERCENT from the rule or the record's own. */
export type SplitMethod = 'EQUAL' | 'PERCENT' | 'AMOUNT' | 'ONE_MEMBER'
export interface FamilyShare {
  member: MemberRef
  amount: string
  /** The percentage it was split by, in basis points, or null. */
  basisPoints: number | null
  updatedBy: MemberRef
  updatedAt: string
}
export type FamilyRecordType = 'EXPENSE' | 'INCOME' | 'SETTLEMENT'
/**
 * A family expense, income or settlement as every member sees it: never anyone's account, personal category or entry
 * (C4). An income's payer is the member who received it; a settlement has no category, split or shares, and a payee.
 */
export interface FamilyRecord {
  id: number
  type: FamilyRecordType
  date: string
  category: { id: number; code: string; name: string; archived: boolean } | null
  amount: string
  currency: string
  comment: string | null
  payer: MemberRef
  /** Who received a settlement; missing for an expense or an income. */
  payee?: MemberRef
  splitMethod: SplitMethod | null
  /** By the members' join order. */
  shares: FamilyShare[]
  author: MemberRef
  createdAt: string
  updatedBy: MemberRef
  updatedAt: string
  version: number
  /** A member it involves has left or deleted their data, so nobody can change it (D-19). */
  frozen: boolean
  canEdit: boolean
  canDelete: boolean
  /**
   * Whether the reader may change the date, the amount and the payer (D-14): the payer with an account, else the author
   * or an owner; for a settlement, the date and the amount, by the side who recorded it.
   */
  canEditPayment: boolean
  /**
   * A settlement's other side, who has put their part on an account of theirs: until they move it back to “Specify
   * later”, its date and amount don't change and it isn't deleted (D-28). Only for who would otherwise change it.
   */
  lockedBy?: MemberRef
  /**
   * How the reader paid it, or received an income: only for its payer or receiver with an account, and missing for
   * everyone else (D-16); for a settlement, the reader's own side.
   */
  yourPayment?: YourPayment
  /**
   * An expense with a minus (D-79): `amount` and the shares are what was refunded, above 0; it reduces its category and
   * the balances, and its "payer" is who received the money back. False, or missing, for every other record.
   */
  refund?: boolean
  /** Who imported it, when an import wrote it (D-48); missing for a record entered in the app. */
  importedBy?: MemberRef
  importedAt?: string
}
/** The payer's own view of their payment: their payment entry, and the account, or "Specify later" with none. */
export interface YourPayment {
  entryId: number; accountId: number | null; accountName: string | null; later: boolean
  /**
   * What went from or into the account or "Specify later", in `currency`, the paying currency (D-89): the reader's own
   * side, which nobody else sees (D-88).
   */
  amount: string; currency: string
  /** The counterparty of the account's line, when the account requires one (D-80). */
  counterpartyId?: number
  /** The reader's own payee on their payment entry (D-81); nobody else sees it. */
  payeeId?: number
}
export interface FamilyRecordPage { content: FamilyRecord[]; page: number; size: number; totalElements: number; totalPages: number }
/** How a new or changed record is split (D-12). RULE is the budget's rule; percentages go in basis points. */
export type RecordSplit =
  | { method: 'RULE' }
  | { method: 'PERCENT'; shares: { memberId: number; basisPoints: number }[] }
  | { method: 'AMOUNT'; shares: { memberId: number; amount: string }[] }
  | { method: 'ONE_MEMBER'; memberId: number }
/** A member's balance: what they owe the family budget, positive, or what it owes them, negative. */
export interface FamilyBalance {
  memberId: number
  displayName: string
  status: MemberStatus
  hasAccount: boolean
  balance: string
  /** The member who reads. */
  you: boolean
}
/** Every member's balance in one currency, by join order; they sum to zero. */
export interface CurrencyBalances { currency: string; members: FamilyBalance[] }
/**
 * Every member's balance in each currency of the records, the main currency first (D-45), and D-47's total in the main
 * currency, for display only.
 */
export interface FamilyBalances { byCurrency: CurrencyBalances[]; total: BalancesTotal }
/**
 * D-47's total: each member's balances together in the main currency, by the reader's own rates as of `asOf`, today
 * (D-49); no members, and `missingCurrencies` named, when a currency has no rate.
 */
export interface BalancesTotal {
  currency: string
  asOf: string
  members: { memberId: number; balance: string }[]
  rates: Rate[]
  missingCurrencies: string[]
}
/**
 * The family report (E1, F6c): the expenses and incomes by month and category, each member's share and what they paid
 * (an expense) or received (an income), and each member's totals with the settlements, in each currency of the
 * period's records, the main currency first (D-45); and D-47's total in the main currency.
 */
export interface FamilyReport {
  from: string | null
  to: string | null
  members: { memberId: number; displayName: string; status: MemberStatus; hasAccount: boolean; you: boolean }[]
  byCurrency: FamilyReportSection[]
  total: FamilyReportTotals
}
/** The report in one currency, from its records only. */
export interface FamilyReportSection { currency: string; rows: FamilyReportRow[]; totals: FamilyReportTotal[] }
/**
 * D-47's total: each member's totals in the main currency, each month at its month-end rate and the current month at
 * today's (D-49), by the reader's own rates; none, and `missingCurrencies` named, when a currency has no rate.
 */
export interface FamilyReportTotals { currency: string; totals: FamilyReportTotal[]; rates: Rate[]; missingCurrencies: string[] }
export interface FamilyReportRow {
  /** "2026-09" */
  month: string
  categoryId: number
  categoryName: string
  categoryType: CategoryType
  archived: boolean
  total: string
  members: { memberId: number; share: string; paid: string }[]
}
/** {@code net} is how much the member's balance moved: positive means they owe the family more. */
export interface FamilyReportTotal {
  memberId: number
  expenseShares: string
  expensesPaid: string
  incomeShares: string
  incomesReceived: string
  settlementsPaid: string
  settlementsReceived: string
  net: string
}
/** One change of a field, as text; members and categories by their names now. Null where there was none, or erased. */
export interface FamilyFieldChange {
  field: string; member: MemberRef | null; old: string | null; new: string | null
  /** For an amount or a share, the currencies of `old` and `new` (D-93): the old one differs on a change of currency. */
  oldCurrency?: string | null; newCurrency?: string | null
}
/** An entry of the change journal (D-16): a member's change of a record, or a system change without an author. */
export interface FamilyChange {
  id: number
  at: string
  action: 'CREATE' | 'UPDATE' | 'DELETE' | 'SPLIT_RULE_RESET'
  recordId: number | null
  author: MemberRef | null
  /** The member a system change is about. */
  about: MemberRef | null
  changes: FamilyFieldChange[]
  /** The record as it is now, deleted or not; null for a system change. */
  record: { date: string; category: string | null; amount: string; deleted: boolean; type?: FamilyRecordType; currency?: string; refund?: boolean } | null
  /** The currency this row's amounts are in, the record's right after the change (D-93); null for a system change. */
  currency?: string | null
}
export interface FamilyJournalPage { content: FamilyChange[]; page: number; size: number; totalElements: number; totalPages: number }

export type EntryKind = 'EXPENSE' | 'INCOME' | 'TRANSFER' | 'SHARED_EXPENSE' | 'LOAN_GIVEN' | 'LOAN_REPAID'
  | 'CURRENCY_EXCHANGE' | 'OPENING_BALANCE' | 'MANUAL'
  // Posted by a family budget; no command makes them.
  | 'FAMILY_SHARE' | 'FAMILY_PAYMENT' | 'FAMILY_SETTLEMENT' | 'FAMILY_OPENING' | 'FAMILY_CORRECTION'
/** What a family budget has to do with a personal entry: it posted it, or it is the payer's payment for a record. */
export interface EntryFamily {
  ledgerId: number
  ledgerName: string
  recordId: number | null
  link: 'SHARE' | 'PAYMENT' | 'SETTLEMENT' | 'OPENING_BALANCE' | 'CORRECTION'
  /** It changes only through its family record. */
  readOnly: boolean
  /** The record's type: a PAYMENT of an INCOME is what the user received. Missing without a record. */
  recordType?: FamilyRecordType
}
export interface Posting {
  accountId: number
  currency: string
  amount: string
  categoryId: number | null
  counterpartyId: number | null
}
export interface Entry {
  id: number
  version: number
  entryDate: string
  kind: EntryKind
  payeeId: number | null
  memo: string | null
  postings: Posting[]
  /** Null (or missing) for an entry of the user's own. */
  family?: EntryFamily | null
}
export interface EntryPage { content: Entry[]; page: number; size: number; totalElements: number; totalPages: number }
/** A request to write an entry: the backend builds the postings from it (EntryCommandJson). */
export type EntryCommand = { kind: EntryKind; entryDate: string; memo: string | null } & Record<string, unknown>

export interface AccountBalance {
  accountId: number
  accountCode: string
  accountName: string
  accountType: AccountType
  currency: string
  balance: string
}
export interface CounterpartyBalance { counterpartyId: number; counterpartyName: string; currency: string; balance: string }
export interface NetWorth { currency: string; assets: string; liabilities: string; netWorth: string }
export interface CashFlowRow {
  month: string
  categoryCode: string
  categoryName: string
  categoryType: CategoryType
  currency: string
  total: string
  /** A family budget's category (D-11): the budget. Left out for the user's own. */
  familyLedgerId?: number
  familyLedgerName?: string
}
export interface SharedSettlement {
  accountId: number
  accountCode: string
  currency: string
  /** The shared account's displayed balance (rule 4). */
  balance: string
  /** Who owes whom, from the side the account's postings add up to: USER_OWES for a credit. */
  direction: 'USER_OWES' | 'USER_IS_OWED'
}
export interface IntegrityViolation {
  currency: string
  postingSum: string
  balanceSheetGap: string
  /** For a family membership whose debt account doesn't show the family balance (D-10); left out otherwise. */
  familyLedgerId?: number
  familyLedgerName?: string
  debtBalance?: string
  familyBalance?: string
}

// Reports with currency=BASE: every amount converted to the user's base currency by D-49's rules (D-90, D-91): the
// latest rate on or before its day, an ECB rate at most 7 days old, the user's own from its day until their next. A
// figure that needs a rate that doesn't apply is null, and `missingRates` says which.

/** No rate for `currency` on `days` days from `from` to `to`. */
export interface MissingRate { currency: string; from: string; to: string; days: number }
export type RateSource = 'ECB' | 'MANUAL'
/**
 * Units of `currency` for one euro, from `date` on, as a figure used it: `stale` for a manual rate more than 31 days
 * older than the day it converted on (D-49).
 */
export interface Rate { currency: string; date: string; perEuro: string; source: RateSource; stale?: boolean }
/** A rate as a displayed figure names it. */
export type DisplayRate = Rate
export interface ConvertedBalance {
  accountId: number
  accountCode: string
  accountName: string
  accountType: AccountType
  currency: string
  balance: string | null
  missingRates: MissingRate[]
  /** The rates `balance` used (D-90). */
  rates?: Rate[]
}
export interface ConvertedNetWorth {
  currency: string
  assets: string | null
  liabilities: string | null
  netWorth: string | null
  /** What rate changes did to money still held or owed; positive for a gain. */
  unrealizedRevaluation: string | null
  /** What currency exchanges gained: FX_EXCHANGE's displayed balance; positive for a gain. */
  realizedExchangeResult: string | null
  /** The rate used for each currency with a balance: an ECB one at most 7 days old, or the user's own (D-49). */
  rates: Rate[]
  missingRates: MissingRate[]
}
export interface ConvertedCashFlowRow {
  month: string
  categoryCode: string
  categoryName: string
  categoryType: CategoryType
  total: string | null
  missingRates: MissingRate[]
  familyLedgerId?: number
  familyLedgerName?: string
  /** The rates `total` used (D-90). */
  rates?: Rate[]
}
export interface ExchangeResult { month: string; realized: string | null; unrealized: string | null; missingRates: MissingRate[] }
export interface ConvertedCashFlow { currency: string; rows: ConvertedCashFlowRow[]; exchangeResults: ExchangeResult[] }

/**
 * A currency's rate as the user sees it today: the one that applies (D-49, D-91), else the latest there is, which
 * `applies` false marks (an ECB rate more than 7 days old); `date` is null for a currency without any.
 */
export interface LatestRate {
  currency: string; date: string | null; perEuro: string | null; source: RateSource | null; inLedger: boolean
  applies?: boolean; stale?: boolean
}
export interface RatesOverview { baseCurrency: string; latest: LatestRate[]; missing: MissingRate[] }
/** A manual rate as stored: `rate` units of `quote` for one `base`, which is EUR. */
export interface ManualRate { date: string; base: string; quote: string; rate: string }

export interface ImportProblem { file: string; row: number | null; message: string }
export interface ImportReport {
  commit: boolean
  outcome: 'DRY_RUN' | 'COMMITTED' | 'ABORTED'
  files: { role: string; name: string; sha256: string; rows: number }[]
  entriesByKind: Partial<Record<EntryKind, number>>
  referenceData: {
    accountsCreated: number
    accountsUpdated: number
    categoriesCreated: number
    categoriesUpdated: number
    counterpartiesCreated: number
  }
  errors: ImportProblem[]
  warnings: ImportProblem[]
  skipped: ImportProblem[]
  fxRowsToReview: { row: number; date: string; sum: string; currency: string; debit: string; credit: string; comment: string }[]
  balances: AccountBalance[]
  integrityViolations: IntegrityViolation[]
}

/** What POST /api/demo-data created in an empty ledger. */
export interface DemoLedger {
  entriesByKind: Partial<Record<EntryKind, number>>
  accounts: number
  categories: number
  counterparties: number
  /** The day of the opening balances, about six months ago. */
  from: string
  /** The day of the last entries: the day it was loaded. */
  to: string
  /** The demo's family budget (H1, F6c), only while the family budget is switched on. */
  familyLedgerId?: number
}

/** One invalid field of a request (400), such as `amount` or `postings[1].amount`. */
export interface InvalidField { field: string; message: string }

/**
 * One violation of a family budget's rule (422), with a stable code and the member it is about, if any, such as
 * `{ code: 'NO_SHARE', memberId: 12, message: 'Kid has no share' }`.
 */
export interface ViolationDetail { code: string; memberId: number | null; message: string }

/** An error answer of the API, an RFC 7807 problem detail (ApiExceptionHandler). */
export class ApiError extends Error {
  readonly status: number
  /** 400: each invalid field. */
  readonly errors: InvalidField[]
  /** 422: every ledger rule the request breaks. */
  readonly violations: string[]
  /** 422 of a family budget's rule: the violations again, each with its code and member. */
  readonly violationDetails: ViolationDetail[]
  /** 409 that the screens tell apart (F6a): `LAST_OWNER`. */
  readonly code: string | undefined

  constructor(message: string, status: number, errors: InvalidField[] = [], violations: string[] = [],
    violationDetails: ViolationDetail[] = [], code?: string) {
    super(message)
    this.status = status
    this.errors = errors
    this.violations = violations
    this.violationDetails = violationDetails
    this.code = code
  }
}

/**
 * Calls the backend with the session cookie, which refreshes the tokens behind it. An ended session goes back through
 * the Keycloak login and returns to this page, rather than ending in an error. A FormData body goes as multipart.
 */
export async function api<T = void>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const json = body !== undefined && !(body instanceof FormData)
  const response = await fetch(`/api${path}`, {
    method,
    headers: {
      ...(method === 'GET' ? {} : { 'X-XSRF-TOKEN': csrfToken() }),
      ...(json ? { 'Content-Type': 'application/json' } : {}),
    },
    body: json ? JSON.stringify(body) : (body as FormData | undefined),
  })
  if (response.status === 401) return logIn()
  if (response.status === 403) throw new Error('Access denied. Please reload the page.')
  if ([502, 503, 504].includes(response.status)) throw new ServerUnavailable()
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new ApiError(problem?.detail ?? problem?.title ?? `${response.status} ${response.statusText}`,
      response.status, problem?.errors ?? [], problem?.violations ?? [], problem?.violationDetails ?? [],
      problem?.code ?? undefined)
  }
  return (response.status === 204 ? undefined : await response.json()) as T
}

/** The backend is down or restarting; the session itself is fine. */
class ServerUnavailable extends Error {
  constructor() {
    super('The server is unavailable right now. Please try again in a moment.')
  }
}

/**
 * Loads `path` and reloads whenever it changes; responses to superseded requests are dropped. While the
 * backend is unavailable it keeps retrying, so the screen recovers on its own once the backend is back.
 * A null path loads nothing. `loading` is true while a request is out, even if older data is shown meanwhile.
 * `status` is the HTTP status of a failed load that the API answered, such as 404. `update` changes the data shown
 * until the next load, for what a write the page made has changed already (F6b: the switcher after joining or leaving).
 * With a `guard`, an answer that isn't the shape the page reads is an error, never data: the page shows "the answer was
 * not what it expects", and what reads nested fields of it never runs (F8c).
 */
export function useApi<T>(path: string | null, guard?: (answer: unknown) => answer is T) {
  const [data, setData] = useState<T>()
  const [error, setError] = useState<string>()
  const [status, setStatus] = useState<number>()
  const [loading, setLoading] = useState(path !== null)
  const latest = useRef(0)
  const reload = useCallback(() => {
    if (path === null) return
    const request = ++latest.current
    setLoading(true)
    api<T>(path).then(
      (result) => {
        if (request !== latest.current) return
        if (guard && !guard(result)) {
          setData(undefined)
          setError(UNEXPECTED_ANSWER)
          setStatus(undefined)
        } else {
          setData(result); setError(undefined); setStatus(undefined)
        }
        setLoading(false)
      },
      (e) => {
        if (request !== latest.current) return
        setError(errorMessage(e))
        setStatus(e instanceof ApiError ? e.status : undefined)
        setLoading(false)
        if (e instanceof ServerUnavailable) setTimeout(() => request === latest.current && reload(), 3000)
      },
    )
  }, [path, guard])
  useEffect(() => {
    reload()
    return () => { latest.current++ } // unmounted or path changed: drop pending responses and retries
  }, [reload])
  const update = useCallback((change: (current: T | undefined) => T | undefined) => setData(change), [])
  return { data, error, status, loading, reload, update }
}

/**
 * Runs a write, then `onDone`; keeps the failure for display, `error` as its message. Resolves to whether it
 * succeeded. `pending` is true while a write is running.
 */
export function useMutation(onDone: () => void = () => {}) {
  const [failure, setFailure] = useState<Error>()
  const [pending, setPending] = useState(false)
  const run = async (action: () => Promise<unknown>) => {
    setPending(true)
    try {
      await action()
      setFailure(undefined)
      onDone()
      return true
    } catch (e) {
      setFailure(e instanceof Error ? e : new Error(errorMessage(e)))
      return false
    } finally {
      setPending(false)
    }
  }
  return { run, error: failure?.message, failure, pending, clear: () => setFailure(undefined) }
}

/** What a page says of an answer whose shape it doesn't read (a guard of `useApi` refused it). */
export const UNEXPECTED_ANSWER = 'The server’s answer was not in the form this page expects. Reload the page; if it stays, tell the owner.'

export const errorMessage = (e: unknown) => (e instanceof Error ? e.message : 'Unexpected error')

/** The messages of a failed write for one field of a form (400 `errors`), or none. */
export const fieldMessages = (failure: Error | undefined, field: string) =>
  failure instanceof ApiError ? failure.errors.filter((e) => e.field === field).map((e) => sentence(e.message)) : []

/** "the account is missing" → "The account is missing." */
export const sentence = (text: string) => text.charAt(0).toUpperCase() + text.slice(1) + (/[.!?]$/.test(text) ? '' : '.')

// Local calendar dates; toISOString() would convert to UTC and shift the day near midnight.
export const isoDate = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

/**
 * A calendar date "2026-10-07" as a Date at local midnight of that day, for date arithmetic by `getFullYear`,
 * `getMonth` and `getDate` (month presets, `isoDate`): whatever the browser's zone, it names that day. Not an instant.
 */
export function dateOfIso(iso: string): Date {
  const [year, month, day] = iso.split('-').map(Number)
  return new Date(year, month - 1, day)
}

/** "2026-09-25" in the user's locale, without shifting the day by the time zone. */
export function formatDate(iso: string, options: Intl.DateTimeFormatOptions = { dateStyle: 'medium' }) {
  const [year, month, day] = iso.split('-').map(Number)
  return new Date(year, month - 1, day).toLocaleDateString(undefined, options)
}

/** An instant, such as "2026-09-30T08:00:00Z", in the user's locale and time zone: "Sep 30, 2026, 10:00 AM". */
export const formatInstant = (instant: string) =>
  new Date(instant).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
