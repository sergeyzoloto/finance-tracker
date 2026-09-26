import {
  formatDate, isoDate, type CashFlowRow, type CategoryType, type ConvertedCashFlow, type CounterpartyBalance,
  type MissingRate, type SharedSettlement,
} from './api'
import { abs, formatMoney, isZero, negate, signOf, sum } from './money'

// The dashboard without React: the period in the URL, the cash flow as a monthly pivot, and who owes whom. Every
// figure comes from the report endpoints; amounts in different currencies are never added together. In the base
// currency, the backend converts them, and a figure it can't convert for want of a rate stays missing: it is never
// added up as if it were zero.

export const PRESETS = ['this-month', 'last-3-months', 'this-year', 'last-year'] as const
export type Preset = (typeof PRESETS)[number]
export type PeriodChoice = Preset | 'custom'
export const DEFAULT_PRESET: Preset = 'this-month'
export const PERIOD_LABELS: Record<PeriodChoice, string> = {
  'this-month': 'This month',
  'last-3-months': 'Last 3 months',
  'this-year': 'This year',
  'last-year': 'Last year',
  custom: 'Custom',
}

/** A preset's first and last day, inclusive. "Last 3 months" is this month and the two before it. */
export function presetRange(preset: Preset, today: Date) {
  const y = today.getFullYear()
  const m = today.getMonth()
  const [from, to] = {
    'this-month': [new Date(y, m, 1), new Date(y, m + 1, 0)],
    'last-3-months': [new Date(y, m - 2, 1), new Date(y, m + 1, 0)],
    'this-year': [new Date(y, 0, 1), new Date(y, 11, 31)],
    'last-year': [new Date(y - 1, 0, 1), new Date(y - 1, 11, 31)],
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
  const preset = PRESETS.find((p) => p === params.get('period')) ?? DEFAULT_PRESET
  const range = from && to ? { from, to } : presetRange(preset, today)
  const day = isoDate(today)
  return {
    choice: from && to ? 'custom' : preset,
    ...range,
    asOf: validDate(params.get('asOf')) ?? (range.to < day ? range.to : day),
  }
}

/** Whether the URL asks for amounts in the base currency (`currency=base`) rather than in each currency. */
export const inBaseFromQuery = (params: URLSearchParams) => params.get('currency') === 'base'

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
export interface CashFlowLine { key: string; label: string; months: Cell[]; total: Cell }
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
interface PivotRow { month: string; categoryCode: string; categoryName: string; categoryType: CategoryType; total: string | typeof MISSING }

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

/** The categories of one type in alphabetical order, as the Excel pivot lists them, and their subtotal. */
function section(rows: PivotRow[], columns: string[], subtotalLabel: string): CashFlowSection {
  const categories = [...new Map(rows.map((r) => [r.categoryCode, r.categoryName]))]
    .sort(([, a], [, b]) => a.localeCompare(b))
  const lines = categories.map(([code, name]) => line(`category:${code}`, name, columns.map((month) =>
    total(rows.filter((r) => r.categoryCode === code && r.month === month).map((r) => r.total)))))
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

/** A rate this many days older than the day it converts on is worth pointing out: the ECB publishes every working day. */
export const STALE_AFTER_DAYS = 7

/** "You owe €30.00 to Family budget." or "Family budget owes you €30.00." */
export function settlementSentence(settlement: SharedSettlement, accountName: string) {
  const amount = formatMoney(abs(settlement.balance), settlement.currency)
  return settlement.direction === 'USER_OWES'
    ? `You owe ${amount} to ${accountName}.`
    : `${accountName} owes you ${amount}.`
}

/** The accounts whose balances are kept per counterparty (rule 8), and which side a positive balance is on. */
export const LOAN_ACCOUNTS = [
  { code: 'LOANS_ASSET', title: 'Money lent', positive: 'owesYou' },
  { code: 'CREDITOR_DEBT', title: 'Money borrowed', positive: 'youOwe' },
] as const

/**
 * Splits an open loan by who owes whom. A positive balance of money lent means the counterparty owes the user, and a
 * positive one of money borrowed that the user owes them; a negative one, such as an overpaid loan, is the reverse.
 */
export function loanSides(balance: CounterpartyBalance, positive: 'owesYou' | 'youOwe') {
  const side = signOf(balance.balance) > 0 ? positive : positive === 'owesYou' ? 'youOwe' : 'owesYou'
  const amount = abs(balance.balance)
  return { owesYou: side === 'owesYou' ? amount : null, youOwe: side === 'youOwe' ? amount : null }
}
