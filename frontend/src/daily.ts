import { dateOfIso, formatDate, isoDate, type CategoryType, type MissingRate } from './api'
import { daysBetween, MISSING, rangeLabel, type Cell, type DayRange } from './dashboard'
import { negate, signOf, sum } from './money'

/**
 * Daily spending (D-571, UX-13), without React: which days the panel shows, what each day spent and the biggest day.
 */

/** The longest period whose own days the panel shows (D-571); a longer one shows a calendar month. */
export const DAILY_MAX_DAYS = 31

/** The days the panel shows, which of them are asked for, and its title. */
export interface DailyRange extends DayRange {
  /** The days asked for: from the first shown day to the earlier of the last and today; null when none has come yet. */
  asked: DayRange | null
  /** Whether the shown days are one whole calendar month. */
  wholeMonth: boolean
}

const endOfMonth = (iso: string) => {
  const d = dateOfIso(iso)
  return isoDate(new Date(d.getFullYear(), d.getMonth() + 1, 0))
}
const nextDay = (iso: string) => {
  const d = dateOfIso(iso)
  return isoDate(new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1))
}

/**
 * D-571's days, as D-584 amends D-574: a period of at most 31 days shows its own days, From to To, those after today
 * still to come (so a running month shows the whole month); a longer period shows the calendar month of the section's
 * last day (D-396: To, or today while the period runs), from the period's From if that is later and to the period's
 * To if that is earlier, so that the panel never shows a day outside the period. Asked from the first shown day to the
 * earlier of the last and today; nothing when the first shown day is after today.
 */
export function dailyRange(period: DayRange, section: DayRange, today: string): DailyRange {
  const own = daysBetween(period.from, period.to) + 1 <= DAILY_MAX_DAYS
  const monthStart = `${section.to.slice(0, 7)}-01`
  const monthEnd = endOfMonth(section.to)
  const from = own || period.from > monthStart ? period.from : monthStart
  const to = own || period.to < monthEnd ? period.to : monthEnd
  const last = to < today ? to : today
  return {
    from, to,
    asked: from > last ? null : { from, to: last },
    wholeMonth: from.endsWith('-01') && to === endOfMonth(from),
  }
}

/** "Daily spending, November 2026" for a whole calendar month, else the range as the comparisons name it (D-571). */
export function dailyTitle(range: DayRange & { wholeMonth: boolean }): string {
  const name = range.wholeMonth ? formatDate(range.from, { month: 'long', year: 'numeric' }) : rangeLabel(range)
  return `Daily spending, ${name}`
}

/** A row of the daily cash flow in either shape, its total null where the base currency can't convert it. */
export interface DailyRow {
  day: string
  categoryType: CategoryType
  categoryName: string
  familyLedgerName?: string
  total: string | null
  missingRates?: MissingRate[]
}

/** A shown day: what it spent (null for nothing posted, MISSING for a figure without a rate), or a day still to come. */
export interface DaySlot { day: string; amount: Cell; future: boolean }

/** The biggest day and its largest category, as the line under the bars names them. */
export interface BiggestDay {
  day: string
  amount: string
  category: { name: string; family?: string }
}

export interface DailySpending {
  slots: DaySlot[]
  /** The largest day's spending above 0, for the bars' heights; null when no day spent anything. */
  max: string | null
  biggest: BiggestDay | null
}

const greater = (a: string, b: string) => signOf(sum([a, negate(b)])) > 0

/**
 * Each shown day's spending in one currency (D-571): its EXPENSE rows' total, a refund lowering it (D-79); MISSING when
 * one of them can't be converted. Days after today are slots still to come. The biggest day is the one that spent most
 * above 0, the earlier on a tie; its largest category the EXPENSE row with the largest total, on a tie the first by name
 * (then by family budget).
 */
export function dailySpending(rows: DailyRow[], range: DayRange, today: string): DailySpending {
  const expenses = rows.filter((r) => r.categoryType === 'EXPENSE')
  const slots: DaySlot[] = []
  for (let day = range.from; day <= range.to; day = nextDay(day)) {
    const own = expenses.filter((r) => r.day === day)
    const amount: Cell = day > today || own.length === 0 ? null
      : own.some((r) => r.total === null) ? MISSING : sum(own.map((r) => r.total!))
    slots.push({ day, amount, future: day > today })
  }
  let best: DaySlot | null = null
  for (const slot of slots) {
    if (typeof slot.amount !== 'string' || signOf(slot.amount) <= 0) continue
    if (best === null || greater(slot.amount, best.amount as string)) best = slot
  }
  if (best === null) return { slots, max: null, biggest: null }
  const categories = expenses.filter((r) => r.day === best.day && r.total !== null)
    .sort((a, b) => (greater(b.total!, a.total!) ? 1 : greater(a.total!, b.total!) ? -1 : 0)
      || a.categoryName.localeCompare(b.categoryName) || (a.familyLedgerName ?? '').localeCompare(b.familyLedgerName ?? ''))
  const top = categories[0]
  return {
    slots,
    max: best.amount as string,
    biggest: {
      day: best.day, amount: best.amount as string,
      category: { name: top.categoryName, ...(top.familyLedgerName === undefined ? {} : { family: top.familyLedgerName }) },
    },
  }
}

/** The day numbers under the bars (D-571): the first day's, today's while the range runs, and the last day's. */
export function dayMarks(range: DayRange, today: string): { day: string; label: string; at: number }[] {
  const count = daysBetween(range.from, range.to) + 1
  const mark = (day: string) => ({ day, label: String(Number(day.slice(8))), at: daysBetween(range.from, day) / count })
  const running = range.from <= today && today < range.to
  return [mark(range.from), ...(running && today !== range.from ? [mark(today)] : []),
    ...(range.to !== range.from ? [mark(range.to)] : [])]
}

/**
 * D-686: whether any shown day up to today has a figure in the rows (spending, a refund, or a day without a rate);
 * daily spending isn't shown for a currency without one, nor before its first day is asked.
 */
export const hasDailyFigure = (rows: DailyRow[], range: DayRange, today: string) =>
  dailySpending(rows, range, today).slots.some((slot) => !slot.future && slot.amount !== null)
