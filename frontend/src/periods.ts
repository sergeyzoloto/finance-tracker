import { isoDate, useApi, type EntryPage } from './api'

// The named periods that the dashboard and the Entries page share (D-522): one list, one set of labels, one range per
// preset. React only in `useLatestEntryDay`.

/**
 * The period's presets, in their order in the Period list (D-296, D-432); "Month of the latest entry" first and the
 * default (D-521).
 */
export const PRESETS = [
  'latest-entry-month', 'this-week', 'previous-week', 'past-month', 'previous-month', 'this-month', 'this-quarter',
  'previous-quarter', 'last-3-months', 'past-year', 'this-year', 'previous-year',
] as const
export type Preset = (typeof PRESETS)[number]
export type PeriodChoice = Preset | 'custom'
export const DEFAULT_PRESET: Preset = 'latest-entry-month'
export const PERIOD_LABELS: Record<PeriodChoice, string> = {
  'latest-entry-month': 'Month of the latest entry',
  'this-week': 'This week',
  'previous-week': 'Previous week',
  'this-quarter': 'This quarter',
  'previous-quarter': 'Previous quarter',
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

/** The preset a URL's `period` names, an old name's successor, or undefined for none or an unknown one. */
export const presetNamed = (name: string | null): Preset | undefined =>
  PRESETS.find((p) => p === name) ?? (name === null ? undefined : OLD_PRESETS[name])

/**
 * A preset's first and last day, inclusive. A week is Monday to Sunday and a quarter a calendar quarter (D-397). "Last
 * 3 months" is this month and the two before it. "Past month" and
 * "Past year" end today and start the day after the same date a month or a year back, that date clamped to the end of a
 * shorter month: on 31 March, 1 to 31 March; on 29 February 2028, from 1 March 2027. "Month of the latest entry" is
 * the calendar month of `latest`, the ledger's latest entry dated on or before today, or this month without one
 * (D-521).
 */
export function presetRange(preset: Preset, today: Date, latest: Date | null = null) {
  const y = today.getFullYear()
  const m = today.getMonth()
  const d = today.getDate()
  /** The same date `months` back, clamped to that month's last day; then the day after it. */
  const dayAfterSameDate = (months: number) => {
    const last = new Date(y, m - months + 1, 0).getDate()
    return new Date(y, m - months, Math.min(d, last) + 1)
  }
  const monday = d - ((today.getDay() + 6) % 7)
  const quarter = m - (m % 3)
  const month = latest ?? today
  const [from, to] = {
    'latest-entry-month': [new Date(month.getFullYear(), month.getMonth(), 1),
      new Date(month.getFullYear(), month.getMonth() + 1, 0)],
    'this-week': [new Date(y, m, monday), new Date(y, m, monday + 6)],
    'previous-week': [new Date(y, m, monday - 7), new Date(y, m, monday - 1)],
    'this-quarter': [new Date(y, quarter, 1), new Date(y, quarter + 3, 0)],
    'previous-quarter': [new Date(y, quarter - 3, 1), new Date(y, quarter, 0)],
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

/** A real day as YYYY-MM-DD, or undefined. */
export function validDate(text: string | null) {
  if (!text || !/^\d{4}-\d{2}-\d{2}$/.test(text)) return undefined
  const [year, month, day] = text.split('-').map(Number)
  return isoDate(new Date(year, month - 1, day)) === text ? text : undefined
}

/**
 * The day of the ledger's latest entry dated on or before `today` (D-521), from `GET /api/entries?size=1&to=<today>`,
 * newest first: the whole ledger's, whatever a page's other filters. Asked only while `needed`; `day` is undefined
 * until the first answer, then null for a ledger with no such entry. An answer stays while a later one loads, so going
 * back to the preset doesn't wait again.
 */
export function useLatestEntryDay(needed: boolean, today: string): { day: string | null | undefined; error?: string } {
  const latest = useApi<EntryPage>(needed ? `/entries?size=1&to=${today}` : null)
  if (!needed) return { day: null }
  if (latest.data === undefined) return { day: undefined, error: latest.error }
  return { day: latest.data.content[0]?.entryDate ?? null }
}
