import type { BreakdownRow, Counterparty } from './api'
import { withValue, withValues } from './entryFilters'
import { formatMoney, isZero, negate, signOf, sum } from './money'

// Entries' breakdown (ENT-3, D-873, D-881) without React: the tabs, what a row says, and how a row's checkbox and
// "only" change the filters. A row's value is added to, or removed from, its filter; "only" makes it the filter's single
// value. The row without a key (no category, no payee) has neither.

export const TABS = [
  { value: 'category', label: 'By category', filter: 'categoryId' },
  { value: 'payee', label: 'By payee', filter: 'payee' },
  { value: 'account', label: 'By account', filter: 'accountId' },
] as const
export type Tab = (typeof TABS)[number]['value']

/** A row's My share, one signed net per currency (income less expenses), "—" for a row with none. */
export function myShareTexts(row: BreakdownRow): { currency: string; text: string; sign: number }[] {
  return row.currencies.map((c) => {
    const net = sum([c.incoming, negate(c.outgoing)])
    return { currency: c.currency, text: formatMoney(net, c.currency, { signed: !isZero(net) }), sign: signOf(net) }
  })
}

const lower = (text: string) => text.toLowerCase()

/** The counterparties of the user whose name is `key`'s, in any case: the Payee filter holds them by id. */
const namedLike = (counterparties: Counterparty[], key: string) =>
  counterparties.filter((c) => lower(c.name) === key).map((c) => String(c.id))

/** Whether the row's value is in its filter now (a payee by name, or by a counterparty of that name). */
export function isChosen(params: URLSearchParams, tab: Tab, row: BreakdownRow, counterparties: Counterparty[]): boolean {
  if (row.key === null) return false
  if (tab !== 'payee') return params.getAll(tab === 'category' ? 'categoryId' : 'accountId').includes(row.key)
  const ids = namedLike(counterparties, row.key)
  return params.getAll('payee').some((p) => lower(p.trim()) === row.key)
    || params.getAll('counterpartyId').some((id) => ids.includes(id))
}

/** The URL with the row's value added to its filter, or removed from it; any change starts at the first page. */
export function withChosen(params: URLSearchParams, tab: Tab, row: BreakdownRow, counterparties: Counterparty[],
  add: boolean): URLSearchParams {
  if (row.key === null) return params
  if (tab === 'category') return withValue(params, 'categoryId', row.key, add)
  if (tab === 'account') return withValue(params, 'accountId', row.key, add)
  if (add) return withValue(params, 'payee', row.name, true)
  // Removing a payee drops it however it was chosen: by name in any case, or by a counterparty of that name.
  const ids = namedLike(counterparties, row.key)
  const kept = params.getAll('payee').filter((p) => lower(p.trim()) !== row.key)
  const keptIds = params.getAll('counterpartyId').filter((id) => !ids.includes(id))
  return withValues(withValues(params, 'payee', kept), 'counterpartyId', keptIds)
}

/** The URL with the row's value the filter's only one: a payee's both filters, a name. */
export function withOnly(params: URLSearchParams, tab: Tab, row: BreakdownRow): URLSearchParams {
  if (row.key === null) return params
  if (tab === 'category') return withValues(params, 'categoryId', [row.key])
  if (tab === 'account') return withValues(params, 'accountId', [row.key])
  return withValues(withValues(params, 'counterpartyId', []), 'payee', [row.name])
}
