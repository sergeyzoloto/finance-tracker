import { dateOfIso, isoDate } from './api'
import { changeOf, changeText } from './dashboard'
import { negate, sum } from './money'
import { validDate } from './periods'

// The Accounts page's dates without React (UX-11, D-526): the day its balances are on, and the day they are compared
// with, from the URL; and each currency's figures on both days, their change and its percentage.

/** The days of the page: `on` (the user's today without `on=`), and while comparing `compare`; `days` in date order. */
export interface AccountDays {
  on: string
  compare: string | null
  /** One day, or the earlier then the later, whichever was typed first. */
  days: string[]
}

/** The last day of the month before `day`'s month: the comparison's default (D-526). */
export function defaultCompare(day: string) {
  const d = dateOfIso(day)
  return isoDate(new Date(d.getFullYear(), d.getMonth(), 0))
}

/** The page's days from the URL: `on=` (left out for today) and `compare=`; an invalid date is left out. */
export function accountDays(params: URLSearchParams, today: string): AccountDays {
  const on = validDate(params.get('on')) ?? today
  const compare = validDate(params.get('compare')) ?? null
  const days = compare === null ? [on] : compare === on ? [on, on] : [on, compare].sort()
  return { on, compare, days }
}

/** One currency's figures on both days. */
export interface ComparedLine {
  currency: string
  earlier: string | null
  later: string | null
  /** later − earlier, nothing counting as 0. */
  change: string
  /** Against the earlier day by the dashboard's rule (`changeOf`, not the net's): "—" across 0 and from 0. */
  percent: string
}

/** Per currency, sorted, of what either day has: both figures, the change and its percentage (D-526). */
export function comparedLines(earlier: { currency: string; amount: string }[],
  later: { currency: string; amount: string }[]): ComparedLine[] {
  const currencies = [...new Set([...earlier, ...later].map((m) => m.currency))].sort()
  return currencies.map((currency) => {
    const before = earlier.find((m) => m.currency === currency)?.amount ?? null
    const now = later.find((m) => m.currency === currency)?.amount ?? null
    return {
      currency,
      earlier: before,
      later: now,
      change: sum([now ?? '0', negate(before ?? '0')]),
      percent: changeText(changeOf(now, before)),
    }
  })
}

/** The amounts per currency of these balances, summed, sorted by currency. */
export function totalsOf(rows: { currency: string; balance: string }[]) {
  const currencies = [...new Set(rows.map((b) => b.currency))].sort()
  return currencies.map((currency) => ({
    currency, amount: sum(rows.filter((b) => b.currency === currency).map((b) => b.balance)),
  }))
}
