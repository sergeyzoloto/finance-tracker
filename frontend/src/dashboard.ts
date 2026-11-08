import {
  formatDate, isoDate, type AccountBalance, type AccountType, type CashFlowRow, type CategoryType, type ConvertedBalance,
  type ConvertedCashFlow, type CounterpartyBalance, type MissingRate, type Rate,
} from './api'
import { minorUnit } from './minorUnits'
import { abs, formatRate, isZero, negate, roundsToZero, signOf, sum } from './money'

// The dashboard without React: the period, the currency and the scope in the URL, the cash flow as a monthly pivot,
// and who owes whom. Every figure comes from the report endpoints; amounts in different currencies are never added
// together. In the base currency, the backend converts them, and a figure it can't convert for want of a rate stays
// missing: it is never added up as if it were zero.

/** The period buttons, in their order on the page (D-296). */
export const PRESETS = [
  'past-month', 'previous-month', 'this-month', 'last-3-months', 'past-year', 'this-year', 'previous-year',
] as const
export type Preset = (typeof PRESETS)[number]
export type PeriodChoice = Preset | 'custom'
export const DEFAULT_PRESET: Preset = 'this-month'
export const PERIOD_LABELS: Record<PeriodChoice, string> = {
  'past-month': 'Past month',
  'previous-month': 'Previous month',
  'this-month': 'This month',
  'last-3-months': 'Last 3 months',
  'past-year': 'Past year',
  'this-year': 'This year',
  'previous-year': 'Previous year',
  custom: 'Custom',
}
/** Names a bookmark may still use: "Previous year" was "Last year" (`period=last-year`). */
const OLD_PRESETS: Record<string, Preset> = { 'last-year': 'previous-year' }

/**
 * A preset's first and last day, inclusive. "Last 3 months" is this month and the two before it. "Past month" and
 * "Past year" end today and start the day after the same date a month or a year back, that date clamped to the end of a
 * shorter month: on 31 March, 1 to 31 March; on 29 February 2028, from 1 March 2027.
 */
export function presetRange(preset: Preset, today: Date) {
  const y = today.getFullYear()
  const m = today.getMonth()
  const d = today.getDate()
  /** The same date `months` back, clamped to that month's last day; then the day after it. */
  const dayAfterSameDate = (months: number) => {
    const last = new Date(y, m - months + 1, 0).getDate()
    return new Date(y, m - months, Math.min(d, last) + 1)
  }
  const [from, to] = {
    'past-month': [dayAfterSameDate(1), today],
    'previous-month': [new Date(y, m - 1, 1), new Date(y, m, 0)],
    'this-month': [new Date(y, m, 1), new Date(y, m + 1, 0)],
    'last-3-months': [new Date(y, m - 2, 1), new Date(y, m + 1, 0)],
    'past-year': [dayAfterSameDate(12), today],
    'this-year': [new Date(y, 0, 1), new Date(y, 11, 31)],
    'previous-year': [new Date(y - 1, 0, 1), new Date(y - 1, 11, 31)],
  }[preset]
  return { from: isoDate(from), to: isoDate(to) }
}

/** What the dashboard shows: income and expenses from `from` to `to`, and balances as of `asOf`. */
export interface Period {
  choice: PeriodChoice
  from: string
  to: string
  asOf: string
}

/**
 * The period the URL's query string selects: a custom range as `from` and `to`, or else a preset as
 * `period=this-year`, or else this month. Presets are kept by name, so a bookmark stays current. Balances are shown
 * as of `asOf`, which defaults to the period's last day, or to today while the period is still running.
 */
export function periodFromQuery(params: URLSearchParams, today: Date): Period {
  const from = validDate(params.get('from'))
  const to = validDate(params.get('to'))
  const named = params.get('period') ?? ''
  const preset = PRESETS.find((p) => p === named) ?? OLD_PRESETS[named] ?? DEFAULT_PRESET
  const range = from && to ? { from, to } : presetRange(preset, today)
  const day = isoDate(today)
  return {
    choice: from && to ? 'custom' : preset,
    ...range,
    asOf: validDate(params.get('asOf')) ?? (range.to < day ? range.to : day),
  }
}

/**
 * Whether the page is in the base currency (D-297): unless the URL asks for each currency (`currency=each`); an old
 * link's `currency=base` means the base currency, as no `currency` does.
 */
export const inBaseFromQuery = (params: URLSearchParams) => params.get('currency') !== 'each'

/** Which income and expenses the cash flow counts (D-302); the whole ledger's balances either way. */
export const SCOPES = ['all', 'personal', 'family-mine', 'family-others'] as const
export type Scope = (typeof SCOPES)[number]
export const SCOPE_LABELS: Record<Scope, string> = {
  all: 'All',
  personal: 'Personal',
  'family-mine': 'Family: my records',
  'family-others': 'Family: others’ records',
}
const SCOPE_PARAMS: Record<Scope, string> = {
  all: 'ALL', personal: 'PERSONAL', 'family-mine': 'FAMILY_MINE', 'family-others': 'FAMILY_OTHERS',
}
export const isFamilyScope = (scope: Scope) => scope === 'family-mine' || scope === 'family-others'

/**
 * The scope the URL asks for (`scope=personal|family-mine|family-others`, else All), and for a family scope the family
 * budget (`family=<id>`, else all of them). Only where the family budget is switched on and the user is a member of
 * one; otherwise All, whatever the URL says.
 */
export function scopeFromQuery(params: URLSearchParams, familyIds: number[] | null): { scope: Scope; family: number | null } {
  const named = SCOPES.find((s) => s === params.get('scope')) ?? 'all'
  const scope = familyIds === null || familyIds.length === 0 ? 'all' : named
  const id = Number(params.get('family'))
  const family = isFamilyScope(scope) && familyIds !== null && familyIds.includes(id) ? id : null
  return { scope, family }
}

/** The cash flow report's parameters of a scope, for the API: "" for All. */
export const scopeParams = ({ scope, family }: { scope: Scope; family: number | null }) =>
  scope === 'all' ? '' : `&scope=${SCOPE_PARAMS[scope]}${family === null ? '' : `&familyLedgerId=${family}`}`

/**
 * What a month's column says when the period covers only part of it (D-296): "from Sep 16" for the first, "to Oct 15"
 * for the last, both for a period within one month; nothing for a whole month.
 */
export function monthNote(month: string, from: string, to: string): string | undefined {
  const first = `${month}-01`
  const [year, m] = month.split('-').map(Number)
  const last = isoDate(new Date(year, m, 0))
  const day = (iso: string) => formatDate(iso, { day: 'numeric', month: 'short' })
  const starts = from > first && from <= last
  const ends = to < last && to >= first
  if (starts && ends) return `${day(from)} to ${day(to)}`
  if (starts) return `from ${day(from)}`
  if (ends) return `to ${day(to)}`
  return undefined
}

/** "2024-02-29" if it is a real day, else undefined. */
function validDate(text: string | null) {
  if (!text || !/^\d{4}-\d{2}-\d{2}$/.test(text)) return undefined
  const [year, month, day] = text.split('-').map(Number)
  return isoDate(new Date(year, month - 1, day)) === text ? text : undefined
}

/** The months that the days `from` to `to` fall in, as "2024-03", oldest first. */
export function monthsBetween(from: string, to: string) {
  const months: string[] = []
  let [year, month] = from.split('-').map(Number)
  const [lastYear, lastMonth] = to.split('-').map(Number)
  while (year < lastYear || (year === lastYear && month <= lastMonth)) {
    months.push(`${year}-${String(month).padStart(2, '0')}`)
    if (++month > 12) [year, month] = [year + 1, 1]
  }
  return months
}

/** "2024-03" → "Mar 2024" in the user's locale. */
export const monthLabel = (month: string) => formatDate(`${month}-01`, { month: 'short', year: 'numeric' })

/** A figure that can't be converted to the base currency, for want of an exchange rate. */
export const MISSING = Symbol('rate missing')
/** An amount; null where nothing was posted; MISSING where it can't be converted. */
export type Cell = string | null | typeof MISSING

/** A row of the cash flow table: an amount per month, and their total. */
export interface CashFlowLine {
  key: string
  label: string
  months: Cell[]
  total: Cell
  /** For a family budget's category: the budget, which marks the line. */
  family?: string
}
export interface CashFlowSection { lines: CashFlowLine[]; subtotal: CashFlowLine }
/** The cash flow of one currency, as a pivot of categories by month. */
export interface CashFlowTable {
  currency: string
  months: string[]
  income: CashFlowSection
  expense: CashFlowSection
  /** Income minus expenses. */
  net: CashFlowLine
  /** Converted to the base currency, so shown with the currency's usual decimals. */
  converted: boolean
  /**
   * In the base currency, what exchange rates did: the realized result of exchanges and the revaluation of what is
   * held or owed. Not income or expenses, so not in the net. Empty where they are zero throughout.
   */
  exchange: CashFlowLine[]
}

/** A cash flow row whose total is known, or MISSING. */
interface PivotRow {
  month: string
  categoryCode: string
  categoryName: string
  categoryType: CategoryType
  total: string | typeof MISSING
  familyLedgerId?: number
  familyLedgerName?: string
}

/** A row's category: its code, and for a family budget's category the budget, since one may share its code. */
const categoryKey = (row: PivotRow) => row.familyLedgerId === undefined ? row.categoryCode
  : `${row.categoryCode}@${row.familyLedgerId}`

/**
 * The cash flow report as the owner's Excel pivot: a table per currency, with categories as rows and `months` as
 * columns, and subtotals for income and for expenses. A month that has rows but isn't among `months` gets a column
 * too, so no row is left out.
 */
export function cashFlowTables(rows: CashFlowRow[], months: string[]): CashFlowTable[] {
  const columns = [...new Set([...months, ...rows.map((r) => r.month)])].sort()
  const currencies = [...new Set(rows.map((r) => r.currency))].sort()
  return currencies.map((currency) => pivot(currency, rows.filter((r) => r.currency === currency), columns))
}

/**
 * The cash flow in the base currency as one pivot, with a line each for the realized result of exchanges and for the
 * revaluation of balances. A total the backend couldn't convert is MISSING, and so is every sum it is part of.
 */
export function convertedCashFlowTable(report: ConvertedCashFlow, months: string[]): CashFlowTable {
  const rows: PivotRow[] = report.rows.map((r) => ({ ...r, total: r.total ?? MISSING }))
  const columns = [...new Set([...months, ...rows.map((r) => r.month), ...report.exchangeResults.map((r) => r.month)])]
    .sort()
  const result = (month: string, figure: 'realized' | 'unrealized'): Cell => {
    const found = report.exchangeResults.find((r) => r.month === month)
    return found === undefined ? null : (found[figure] ?? MISSING)
  }
  const exchange = [
    line('exchange:realized', 'Realized on exchanges', columns.map((month) => result(month, 'realized'))),
    line('exchange:unrealized', 'Revaluation of balances', columns.map((month) => result(month, 'unrealized'))),
  ]
  const shown = exchange.some((l) => l.months.some((cell) => cell === MISSING || (cell !== null && !isZero(cell))))
  return { ...pivot(report.currency, rows, columns), converted: true, exchange: shown ? exchange : [] }
}

function pivot(currency: string, rows: PivotRow[], columns: string[]): CashFlowTable {
  const income = section(rows.filter((r) => r.categoryType === 'INCOME'), columns, 'Total income')
  const expense = section(rows.filter((r) => r.categoryType === 'EXPENSE'), columns, 'Total expenses')
  const net = columns.map((_, i) => {
    const earned: Cell = income.subtotal.months[i]
    const spent: Cell = expense.subtotal.months[i]
    if (earned === null && spent === null) return null
    if (earned === MISSING || spent === MISSING) return MISSING
    return sum([earned ?? '0', negate(spent ?? '0')])
  })
  return { currency, months: columns, income, expense, net: line('net', 'Net', net), converted: false, exchange: [] }
}

/**
 * The categories of one type in alphabetical order, as the Excel pivot lists them, and their subtotal. A family
 * budget's category is a line of its own, marked with the budget, after the user's own of the same name.
 */
function section(rows: PivotRow[], columns: string[], subtotalLabel: string): CashFlowSection {
  const categories = [...new Map(rows.map((r) => [categoryKey(r), r]))]
    .sort(([, a], [, b]) => a.categoryName.localeCompare(b.categoryName)
      || (a.familyLedgerName ?? '').localeCompare(b.familyLedgerName ?? ''))
  const lines = categories.map(([key, row]) => ({
    ...line(`category:${key}`, row.categoryName, columns.map((month) =>
      total(rows.filter((r) => categoryKey(r) === key && r.month === month).map((r) => r.total)))),
    ...(row.familyLedgerName === undefined ? {} : { family: row.familyLedgerName }),
  }))
  return { lines, subtotal: line('subtotal', subtotalLabel, columns.map((_, i) => total(lines.map((l) => l.months[i])))) }
}

const line = (key: string, label: string, months: Cell[]): CashFlowLine => ({ key, label, months, total: total(months) })

/** The sum of the amounts that are there: null if none is, MISSING if one can't be converted. */
export function total(cells: Cell[]): Cell {
  if (cells.includes(MISSING)) return MISSING
  const present = cells.filter((a): a is string => typeof a === 'string')
  return present.length === 0 ? null : sum(present)
}

/**
 * The rates missing for several figures, one per currency, from the first day that needs one to the last; the
 * numbers of days are left out, since the figures can share days.
 */
export function mergeMissing(lists: MissingRate[][]): { currency: string; from: string; to: string }[] {
  const byCurrency = new Map<string, { currency: string; from: string; to: string }>()
  for (const { currency, from, to } of lists.flat()) {
    const seen = byCurrency.get(currency)
    byCurrency.set(currency, seen
      ? { currency, from: from < seen.from ? from : seen.from, to: to > seen.to ? to : seen.to }
      : { currency, from, to })
  }
  return [...byCurrency.values()].sort((a, b) => a.currency.localeCompare(b.currency))
}

/** "KZT on 3 Aug 2026" or "KZT from 3 Aug 2026 to 20 Aug 2026". */
export const missingDays = ({ currency, from, to }: { currency: string; from: string; to: string }) =>
  from === to ? `${currency} on ${formatDate(from)}` : `${currency} from ${formatDate(from)} to ${formatDate(to)}`

/** Days from `from` to `to`, both ISO dates. */
export function daysBetween(from: string, to: string) {
  const utc = (iso: string) => { const [y, m, d] = iso.split('-').map(Number); return Date.UTC(y, m - 1, d) }
  return Math.round((utc(to) - utc(from)) / 86_400_000)
}


/**
 * Whether an amount is nothing in its own currency (D-349): it rounds to 0 in the currency's minor unit, such as the
 * RUB 0.004 that the import's four decimals left. Open loans leave such a line out, and a family budget whose every
 * amount is one reads "Settled".
 */
export const belowMinorUnit = (amount: string, currency: string) => roundsToZero(amount, minorUnit(currency))

/** The accounts whose balances are kept per counterparty (rule 8), and which side a positive balance is on. */
export const LOAN_ACCOUNTS = [
  { code: 'LOANS_ASSET', title: 'Money lent', positive: 'owesYou' },
  { code: 'CREDITOR_DEBT', title: 'Money borrowed', positive: 'youOwe' },
] as const

/**
 * Splits an open loan by who owes whom. A positive balance of money lent means the counterparty owes the user, and a
 * positive one of money borrowed that the user owes them; a negative one, such as an overpaid loan, is the reverse.
 */
export function loanSides(balance: { balance: string }, positive: 'owesYou' | 'youOwe') {
  const side = signOf(balance.balance) > 0 ? positive : positive === 'owesYou' ? 'youOwe' : 'owesYou'
  const amount = abs(balance.balance)
  return { owesYou: side === 'owesYou' ? amount : null, youOwe: side === 'youOwe' ? amount : null }
}

/**
 * Which side a displayed balance (rule 4) of a debt between the user and others is on: a LIABILITY above 0, such as
 * a family budget's debt account, is what the user owes, below 0 what they are owed; an ASSET the other way round.
 */
export const debtSides = (type: AccountType, balance: string) =>
  loanSides({ balance }, type === 'ASSET' ? 'owesYou' : 'youOwe')

/** A line of open loans' "Family budgets" group (D-299): a family budget's debt account, or the old shared account. */
export interface FamilyDebt {
  key: string
  name: string
  /** Where its name links: the budget's Balances page, or the shared account's entries. */
  to: string
  accountType: AccountType
  /** Per currency, or in base mode the one converted figure (null with a missing rate). */
  amounts: { currency: string; balance: string | null }[]
  /** In base mode, the amounts in their own currencies, shown small when there is more than the base currency. */
  own: { currency: string; balance: string }[]
}

/**
 * The family group of open loans (D-299): each family budget's debt account, named by its budget (never found by its
 * code or name: by `familyLedgerId`), and the settings' shared account of rule 7 when its balance isn't zero, under
 * its own name. In base mode, from the converted balances, with the amounts in their own currencies beside them.
 */
export function familyDebts(balances: AccountBalance[], converted: ConvertedBalance[] | null,
  sharedAccountId: number | null): FamilyDebt[] {
  const debts: FamilyDebt[] = []
  const own = (accountId: number) => balances.filter((b) => b.accountId === accountId)
    .map((b) => ({ currency: b.currency, balance: b.balance }))
  const add = (accountId: number, name: string, to: string, accountType: AccountType) => {
    const amounts = converted === null ? own(accountId)
      : converted.filter((b) => b.accountId === accountId).map((b) => ({ currency: b.currency, balance: b.balance }))
    debts.push({ key: `account:${accountId}`, name, to, accountType, amounts, own: own(accountId) })
  }
  const seen = new Set<number>()
  for (const b of balances) {
    if (b.familyLedgerId === undefined || seen.has(b.accountId)) continue
    seen.add(b.accountId)
    add(b.accountId, b.familyLedgerName ?? b.accountName, `/family/${b.familyLedgerId}/balances`, b.accountType)
  }
  const shared = sharedAccountId === null ? undefined : balances.find((b) => b.accountId === sharedAccountId)
  if (shared && !seen.has(shared.accountId) && own(shared.accountId).some((b) => !belowMinorUnit(b.balance, b.currency))) {
    add(shared.accountId, shared.accountName, `/entries?accountId=${shared.accountId}`, shared.accountType)
  }
  return debts
}

/** Whether amounts in their own currencies say more than the base figure: another currency than the base, or several. */
export const showsOwnCurrencies = (own: { currency: string }[], base: string) =>
  own.length > 1 || own.some((a) => a.currency !== base)

/** The period's totals (D-305): the table's own subtotals of income and expenses, and its net. */
export const periodTotals = (table: CashFlowTable): { income: Cell; expense: Cell; net: Cell } => ({
  income: table.income.subtotal.total,
  expense: table.expense.subtotal.total,
  net: table.net.total,
})

/**
 * A rate a figure in the base currency used, in words (D-49, D-90): "1 EUR = 95.5 RUB, manual rate of Aug 1, 2026 (rate
 * stale)", the ECB's or the user's own.
 */
export const usedRateText = (r: Rate) =>
  `1 EUR = ${formatRate(r.perEuro)} ${r.currency}, ${r.source === 'MANUAL' ? 'manual' : 'ECB'} rate of ${formatDate(r.date)}${r.stale ? ' (rate stale)' : ''}`

/**
 * Whether the Balances card lists an account (D-364): an account that isn't archived always, an archived one only
 * while one of its amounts in its own currencies doesn't round to 0 in that currency's minor unit (D-349's test). The
 * type totals still count every account, as net worth does (D-355).
 */
export const listedInBalances = (own: { currency: string; balance: string }[], archived: boolean) =>
  !archived || own.some((b) => !belowMinorUnit(b.balance, b.currency))

/** The system equity accounts that keep their own lines in the Balances card (D-367). */
export const SYSTEM_EQUITY = ['OPENING_BALANCE', 'FX_EXCHANGE'] as const
export const isSystemEquity = (b: { accountType: AccountType; accountCode: string }) =>
  b.accountType === 'EQUITY' && (SYSTEM_EQUITY as readonly string[]).includes(b.accountCode)
/** The equity accounts D-367 shows as two lines, UNALLOCATED and RESERVE in the owner's ledger, whatever their names. */
export const isReserveEquity = (b: { accountType: AccountType; accountCode: string }) =>
  b.accountType === 'EQUITY' && !isSystemEquity(b)

/**
 * The days of "Result for the period" (D-367): from the period's From to "Balances on", or To if earlier; null when
 * that ends before it starts (Balances on before From, or a period that ends before it starts), when the result is 0.
 */
export function resultRange(period: { from: string; to: string; asOf: string }): { from: string; to: string } | null {
  const to = period.asOf < period.to ? period.asOf : period.to
  return to < period.from ? null : { from: period.from, to }
}

/** One currency's two lines in place of the reserve accounts (D-367). */
export interface EquitySplit { currency: string; reserve: string; result: string }

/**
 * D-367 in each currency: "Result for the period" is the Net of income and expenses, under All, from `resultRange`'s
 * tables (null for none: 0); "Accumulated reserve" is the reserve accounts' balance on Balances on less that result,
 * which is their balance on the day before From plus any transfer to or from them within the period. Sums within a
 * currency only.
 */
export function equitySplits(balances: AccountBalance[], tables: CashFlowTable[] | null): EquitySplit[] {
  const reserve = balances.filter(isReserveEquity)
  const results = new Map((tables ?? []).map((t) => [t.currency, periodTotals(t).net]))
  const currencies = [...new Set([...reserve.map((b) => b.currency), ...results.keys()])].sort()
  return currencies.map((currency) => {
    const net = results.get(currency)
    const result = typeof net === 'string' ? net : '0'
    const held = sum(reserve.filter((b) => b.currency === currency).map((b) => b.balance))
    return { currency, result, reserve: sum([held, negate(result)]) }
  })
}

/**
 * D-367 and D-369 in the base currency: "Result for the period" is the cash flow's Net at each posting's own day's rate,
 * the same figure as the Net of income and expenses (`table`'s; null for none: 0); "Accumulated reserve" is the rest of
 * equity at the rate on Balances on: net worth less the system equity accounts' lines and the result, so that the lines
 * add up to Equity's total, net worth, and the reserve includes the revaluation. MISSING when a figure lacks a rate.
 */
export function baseEquitySplit(converted: ConvertedBalance[], netWorth: Cell, table: CashFlowTable | null):
  { reserve: Cell; result: Cell } {
  const result: Cell = table === null ? '0' : (periodTotals(table).net ?? '0')
  const system: Cell[] = converted.filter(isSystemEquity).map((b) => b.balance ?? MISSING)
  if (netWorth === MISSING || result === MISSING || system.includes(MISSING)) return { reserve: MISSING, result }
  const parts = (system as (string | null)[]).filter((s): s is string => s !== null)
  return { result, reserve: sum([netWorth ?? '0', ...parts.map(negate), negate(result)]) }
}
