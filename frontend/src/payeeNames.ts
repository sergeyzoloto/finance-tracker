import type { Counterparty, FamilyPayee } from './api'

// Payee names without React (UX-12, D-549, D-550): compared as the backend compares them, ignoring case.

export const key = (name: string) => name.trim().toLocaleLowerCase()
export const byName = (a: string, b: string) => a.localeCompare(b, undefined, { sensitivity: 'base' }) || a.localeCompare(b)

/**
 * The names a payee field suggests (D-549, D-550): the user's counterparties not archived and the family payees, one
 * per name ignoring case (the counterparty's spelling first), sorted by name ignoring case.
 */
export function payeeSuggestions(counterparties: Counterparty[], familyPayees: FamilyPayee[]): string[] {
  const names = new Map<string, string>()
  for (const name of [...counterparties.filter((c) => !c.archived).map((c) => c.name), ...familyPayees.map((p) => p.name)]) {
    if (!names.has(key(name))) names.set(key(name), name)
  }
  return [...names.values()].sort(byName)
}

/** The counterparty of the user's with this name, in any case, archived ones included, as the backend matches names. */
export function counterpartyCalled(counterparties: Counterparty[], name: string) {
  const wanted = key(name)
  return wanted === '' ? undefined : counterparties.find((c) => key(c.name) === wanted)
}

