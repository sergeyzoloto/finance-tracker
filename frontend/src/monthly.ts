import type { EntryMonthly } from './api'
import { isZero, negate, signOf, sum, toChartNumber } from './money'

// Entries' monthly result chart (ENT-4, D-882, D-883) without React: a waterfall per currency. Each month a column from
// the running total before it to the one after, My share's net (income less expenses); then one last column, "Total",
// from 0 to the whole span's. Never added across currencies.

/** One column of the waterfall: a month, or the Total. `before` and `after` are decimal strings. */
export interface WaterfallColumn {
  key: string
  /** The month `YYYY-MM`, or null for the Total. */
  month: string | null
  before: string
  after: string
  /** `after` less `before`: the month's change, or the whole span's. */
  change: string
}

/** The months from `first` to `last`, both `YYYY-MM`, inclusive. */
export function monthsBetween(first: string, last: string): string[] {
  const months: string[] = []
  let [y, m] = first.split('-').map(Number)
  const [ly, lm] = last.split('-').map(Number)
  while (y < ly || (y === ly && m <= lm)) {
    months.push(`${y}-${String(m).padStart(2, '0')}`)
    if (++m === 13) {
      m = 1
      y++
    }
  }
  return months
}

/** The currencies with a figure in the span, sorted. */
export const monthlyCurrencies = (data: EntryMonthly): string[] =>
  [...new Set(data.months.map((m) => m.currency))].sort()

/**
 * A currency's waterfall: a column for every month of the span, a month with no figure a column of no change, then the
 * Total. Empty for a span with no month.
 */
export function waterfall(data: EntryMonthly, currency: string): WaterfallColumn[] {
  if (data.firstMonth === null || data.lastMonth === null) return []
  const columns: WaterfallColumn[] = []
  let running = '0'
  for (const month of monthsBetween(data.firstMonth, data.lastMonth)) {
    const row = data.months.find((m) => m.month === month && m.currency === currency)
    const change = row ? sum([row.incoming, negate(row.outgoing)]) : '0'
    const after = sum([running, change])
    columns.push({ key: month, month, before: running, after, change })
    running = after
  }
  columns.push({ key: 'total', month: null, before: '0', after: running, change: running })
  return columns
}

/** The vertical range of the columns, always holding 0: the lowest and the highest value any column reaches. */
export function waterfallRange(columns: WaterfallColumn[]): { min: number; max: number } {
  let min = 0
  let max = 0
  for (const c of columns) {
    for (const v of [c.before, c.after]) {
      min = Math.min(min, toChartNumber(v))
      max = Math.max(max, toChartNumber(v))
    }
  }
  return { min, max }
}

/** A column's place in the chart, as percentages of its height: where it starts from the bottom and how tall it is. */
export function columnBox(column: WaterfallColumn, range: { min: number; max: number }): { bottom: number; height: number } {
  const span = range.max - range.min || 1
  const lo = Math.min(toChartNumber(column.before), toChartNumber(column.after))
  const hi = Math.max(toChartNumber(column.before), toChartNumber(column.after))
  return { bottom: ((lo - range.min) / span) * 100, height: ((hi - lo) / span) * 100 }
}

/** Where 0 lies, as a percentage of the chart's height from the bottom. */
export const zeroLine = (range: { min: number; max: number }) => ((0 - range.min) / (range.max - range.min || 1)) * 100

/** Whether a column goes up (a gain), down (a loss) or stays; the Total by its sign. */
export const direction = (column: WaterfallColumn): 'up' | 'down' | 'flat' =>
  signOf(column.change) > 0 ? 'up' : signOf(column.change) < 0 ? 'down' : 'flat'

/** A month's short label: "Nov", with its year after the first column and after each December ("Jan 2026"). */
export function monthLabel(month: string, first: boolean, locale?: string): { name: string; year: string | null } {
  const [y, m] = month.split('-').map(Number)
  const name = new Intl.DateTimeFormat(locale, { month: 'short', timeZone: 'UTC' }).format(new Date(Date.UTC(y, m - 1, 1)))
  return { name, year: first || m === 1 ? String(y) : null }
}

/** Whether the span has anything to draw in a currency: some month with a change. */
export const hasChange = (columns: WaterfallColumn[]) => columns.some((c) => !isZero(c.change))
