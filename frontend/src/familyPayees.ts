import { api, familyLedgersOn, useApi, type Counterparty, type FamilyPayee } from './api'
import { useMe } from './me'
import { byName, counterpartyCalled, key } from './payeeNames'

// The payees that family records show on the user's entries (UX-12, D-547 to D-550): Entries' "Payee or person"
// list beside the user's counterparties, and the payee fields' suggestions. No React here but the hook.

/** The family payees, asked only with the family switch on; none while they load or after a failure. */
export function useFamilyPayees(): FamilyPayee[] {
  const { me } = useMe()
  return useApi<FamilyPayee[]>(familyLedgersOn(me) ? '/entries/family-payees' : null).data ?? []
}

/** One of Entries' "Payee or person" options: a counterparty of the user's (`c:<id>`) or a family payee's name (`p:<name>`). */
export interface PayeeOption { value: string; label: string; name: string }

/**
 * The option's value for the URL's filter: `counterpartyId` wins when, by hand, both are there (D-556). A `payee` that
 * is an option's name in any case is that option, a counterparty's before a family payee's (D-568, D-578): both find
 * the same entries (D-531, D-548), and the URL keeps its `payee`.
 */
export function payeeFilterValue(counterpartyId: string, payee: string, options: PayeeOption[] = []): string {
  if (counterpartyId !== '') return `c:${counterpartyId}`
  if (payee === '') return ''
  const same = options.filter((o) => key(o.name) === key(payee))
  return (same.find((o) => o.value.startsWith('c:')) ?? same[0])?.value ?? `p:${payee}`
}

/** The filters an option sets: one of `counterpartyId` and `payee`, the other dropped (D-547). */
export function payeeFilterOf(value: string): { counterpartyId: string; payee: string } {
  if (value.startsWith('c:')) return { counterpartyId: value.slice(2), payee: '' }
  if (value.startsWith('p:')) return { counterpartyId: '', payee: value.slice(2) }
  return { counterpartyId: '', payee: '' }
}

/**
 * Entries' "Payee or person" options (D-547): the user's counterparties (archived ones marked) and the family payees no
 * counterparty of the user's names, in any case, each labelled with its family budgets; one list sorted by name ignoring
 * case. The chosen payee is always listed, as its name alone when no family payee is it any more.
 */
export function payeeFilterOptions(counterparties: Counterparty[], familyPayees: FamilyPayee[], chosenPayee: string): PayeeOption[] {
  const options: PayeeOption[] = counterparties.map((c) => ({
    value: `c:${c.id}`, name: c.name, label: c.archived ? `${c.name} (archived)` : c.name,
  }))
  const taken = new Set(counterparties.map((c) => key(c.name)))
  for (const p of familyPayees) {
    if (taken.has(key(p.name))) continue
    taken.add(key(p.name))
    options.push({ value: `p:${p.name}`, name: p.name, label: `${p.name} · ${p.budgets.map((b) => b.name).join(', ')}` })
  }
  // The chosen payee, once: unless an option has its name in any case (D-568).
  if (chosenPayee !== '' && !options.some((o) => key(o.name) === key(chosenPayee))) {
    options.push({ value: `p:${chosenPayee}`, name: chosenPayee, label: chosenPayee })
  }
  return options.sort((a, b) => byName(a.name, b.name))
}

/**
 * What saving a family form's payee field does (D-549), by its trimmed name against the payee it had:
 * - `keep`: nothing sent (empty and none before, or the payee it had, in any case);
 * - `remove`: `payeeId: null` (emptied);
 * - `id`: a counterparty of the user's, archived included;
 * - `create`: any other name, which becomes the user's counterparty first.
 */
export type PayeeChange = { kind: 'keep' } | { kind: 'remove' } | { kind: 'id'; id: number } | { kind: 'create'; name: string }

export function payeeChange(typed: string, had: string, counterparties: Counterparty[]): PayeeChange {
  const name = typed.trim()
  if (name === '') return had.trim() === '' ? { kind: 'keep' } : { kind: 'remove' }
  if (had.trim() !== '' && key(name) === key(had)) return { kind: 'keep' }
  const known = counterpartyCalled(counterparties, name)
  return known ? { kind: 'id', id: known.id } : { kind: 'create', name }
}

/**
 * The request's `payeeId` for the typed name (D-549): undefined to send nothing, null to remove, else an id, creating
 * the counterparty first for a new name. A counterparty created here goes into `created`, so that a second save before
 * the list reloads takes it instead of creating it again (D-462); `onCreated` reloads the list.
 */
export async function payeeIdFor(typed: string, had: string, counterparties: Counterparty[], created: Counterparty[],
  onCreated?: () => void): Promise<number | null | undefined> {
  const change = payeeChange(typed, had, [...counterparties, ...created])
  switch (change.kind) {
    case 'keep': return undefined
    case 'remove': return null
    case 'id': return change.id
    case 'create': {
      const counterparty = await api<Counterparty>('/counterparties', 'POST', { name: change.name })
      created.push(counterparty)
      onCreated?.()
      return counterparty.id
    }
  }
}

/** The payee field's hint for a name no counterparty of the user's has (D-549), as New entry's. */
export const NEW_PAYEE_HINT = 'New payee: it is added when you save.'
