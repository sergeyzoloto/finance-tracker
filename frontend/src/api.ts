import { useCallback, useEffect, useRef, useState } from 'react'
import { csrfToken, logIn } from './auth'

// The ledger's API (docs/adr/0001-double-entry-ledger.md). Amounts are decimal strings; see money.ts.

export interface Me { name: string }
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
export interface Category { id: number; code: string; name: string; type: CategoryType; archived: boolean }
export interface Counterparty {
  id: number
  name: string
  kind: 'MERCHANT' | 'PERSON' | 'ORGANIZATION' | null
  archived: boolean
  /** The category of the latest entry with this payee that has one, to preselect in the next. */
  lastCategoryId: number | null
}
export interface Settings { baseCurrency: string; sharedAccountId: number | null; defaultShareRatio: string }

export type EntryKind = 'EXPENSE' | 'INCOME' | 'TRANSFER' | 'SHARED_EXPENSE' | 'LOAN_GIVEN' | 'LOAN_REPAID'
  | 'CURRENCY_EXCHANGE' | 'OPENING_BALANCE' | 'MANUAL'
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
export interface IntegrityViolation { currency: string; postingSum: string; balanceSheetGap: string }

// Reports with currency=BASE: every amount converted to the user's base currency at the latest rate on or before its
// day. A figure that needs a rate that doesn't exist is null, and `missingRates` says which.

/** No rate for `currency` on `days` days from `from` to `to`. */
export interface MissingRate { currency: string; from: string; to: string; days: number }
export type RateSource = 'ECB' | 'MANUAL'
/** Units of `currency` for one euro, from `date` on. */
export interface Rate { currency: string; date: string; perEuro: string; source: RateSource }
export interface ConvertedBalance {
  accountId: number
  accountCode: string
  accountName: string
  accountType: AccountType
  currency: string
  balance: string | null
  missingRates: MissingRate[]
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
  /** The rate used for each currency with a balance, possibly from long before the day. */
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
}
export interface ExchangeResult { month: string; realized: string | null; unrealized: string | null; missingRates: MissingRate[] }
export interface ConvertedCashFlow { currency: string; rows: ConvertedCashFlowRow[]; exchangeResults: ExchangeResult[] }

/** A currency's latest rate as the user sees it; `date` is null for a currency without any. */
export interface LatestRate { currency: string; date: string | null; perEuro: string | null; source: RateSource | null; inLedger: boolean }
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

/** One invalid field of a request (400), such as `amount` or `postings[1].amount`. */
export interface InvalidField { field: string; message: string }

/** An error answer of the API, an RFC 7807 problem detail (ApiExceptionHandler). */
export class ApiError extends Error {
  readonly status: number
  /** 400: each invalid field. */
  readonly errors: InvalidField[]
  /** 422: every ledger rule the request breaks. */
  readonly violations: string[]

  constructor(message: string, status: number, errors: InvalidField[] = [], violations: string[] = []) {
    super(message)
    this.status = status
    this.errors = errors
    this.violations = violations
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
      response.status, problem?.errors ?? [], problem?.violations ?? [])
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
 */
export function useApi<T>(path: string | null) {
  const [data, setData] = useState<T>()
  const [error, setError] = useState<string>()
  const [loading, setLoading] = useState(path !== null)
  const latest = useRef(0)
  const reload = useCallback(() => {
    if (path === null) return
    const request = ++latest.current
    setLoading(true)
    api<T>(path).then(
      (result) => { if (request === latest.current) { setData(result); setError(undefined); setLoading(false) } },
      (e) => {
        if (request !== latest.current) return
        setError(errorMessage(e))
        setLoading(false)
        if (e instanceof ServerUnavailable) setTimeout(() => request === latest.current && reload(), 3000)
      },
    )
  }, [path])
  useEffect(() => {
    reload()
    return () => { latest.current++ } // unmounted or path changed: drop pending responses and retries
  }, [reload])
  return { data, error, loading, reload }
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

export const errorMessage = (e: unknown) => (e instanceof Error ? e.message : 'Unexpected error')

/** The messages of a failed write for one field of a form (400 `errors`), or none. */
export const fieldMessages = (failure: Error | undefined, field: string) =>
  failure instanceof ApiError ? failure.errors.filter((e) => e.field === field).map((e) => sentence(e.message)) : []

/** "the account is missing" → "The account is missing." */
export const sentence = (text: string) => text.charAt(0).toUpperCase() + text.slice(1) + (/[.!?]$/.test(text) ? '' : '.')

// Local calendar dates; toISOString() would convert to UTC and shift the day near midnight.
export const isoDate = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

/** "2026-09-25" in the user's locale, without shifting the day by the time zone. */
export function formatDate(iso: string, options: Intl.DateTimeFormatOptions = { dateStyle: 'medium' }) {
  const [year, month, day] = iso.split('-').map(Number)
  return new Date(year, month - 1, day).toLocaleDateString(undefined, options)
}
