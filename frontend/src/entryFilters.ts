// Entries' list filters (ENT-2, D-873, D-880) without React: several values per filter as repeated query parameters
// (any of a filter's values, all filters), Direction and Origin as toggles, and the chips that name what is chosen.

/** The filters that take several values, by their query parameter. */
export const LIST_FILTERS = ['accountId', 'categoryId', 'counterpartyId', 'payee', 'direction', 'origin'] as const
export type ListFilter = (typeof LIST_FILTERS)[number]

export const DIRECTIONS = [
  { value: 'INCOME', label: 'Income' },
  { value: 'EXPENSES', label: 'Expenses' },
  { value: 'TRANSFERS', label: 'Transfers' },
] as const
export const ORIGINS = [
  { value: 'PERSONAL', label: 'Personal' },
  { value: 'FAMILY', label: 'Family' },
] as const

/** A filter's values in the URL, in their order, each once; blanks and unknown toggles dropped. */
export function valuesOf(params: URLSearchParams, filter: ListFilter): string[] {
  const known = filter === 'direction' ? DIRECTIONS.map((d) => d.value as string)
    : filter === 'origin' ? ORIGINS.map((o) => o.value as string) : null
  const values = params.getAll(filter).map((v) => v.trim()).filter((v) => v !== '' && (!known || known.includes(v)))
  return [...new Set(values)]
}

/** The URL with `value` added to a filter (once), or removed from it; any change starts at the first page. */
export function withValue(params: URLSearchParams, filter: ListFilter, value: string, add: boolean): URLSearchParams {
  const values = valuesOf(params, filter).filter((v) => v !== value)
  if (add) values.push(value)
  return withValues(params, filter, values)
}

/** The URL with a filter's values replaced ("only", ENT-3); any change starts at the first page. */
export function withValues(params: URLSearchParams, filter: ListFilter, values: string[]): URLSearchParams {
  const next = new URLSearchParams(params)
  next.delete(filter)
  values.forEach((v) => next.append(filter, v))
  next.delete('page')
  return next
}

/**
 * A toggle group's new values when one is pressed (D-873): no value chosen means all of them; pressing one of all
 * chooses it alone, pressing a chosen one drops it, and choosing every value means no filter.
 */
export function toggled(chosen: string[], value: string, all: readonly string[]): string[] {
  const next = chosen.includes(value) ? chosen.filter((v) => v !== value) : [...chosen, value]
  return next.length === all.length ? [] : all.filter((v) => next.includes(v))
}
