import { useEffect } from 'react'
import { ApiError, useApi, useMutation, type FamilyLedger, type FamilyMember } from './api'

/** A family budget as its pages share it. `reload` loads it again, and the switcher's list with it. */
export interface FamilyData {
  ledger: FamilyLedger
  members: FamilyMember[]
  /** The ledger's path in the API: `/family-ledgers/{id}`. */
  path: string
  /**
   * The budget's page: `/family/{id}`. Links between its pages are absolute, since inside the splat route a relative
   * one would resolve against the whole URL.
   */
  page: string
  owner: boolean
  reload: () => void
  /** After the reader left the budget (F6a): the switcher's list again, and their personal budget. */
  left?: () => void
}

/** What creating a family budget couldn't finish, handed to its page (NewFamily). */
export interface CreationState { creationProblems?: string[] }

/**
 * A write about a family budget, as useMutation. A 404 or 409 loads the budget again: the budget may be gone, which
 * shows the not-found page, or changed meanwhile. `message` is the failure as the API words it for the screens,
 * unless it is only about fields of the form (400).
 */
export function useFamilyMutation(family: FamilyData, onDone: () => void = () => {}) {
  const mutation = useMutation(() => { family.reload(); onDone() })
  const failure = mutation.failure
  const message = failure && !(failure instanceof ApiError && failure.errors.length > 0)
    ? failure.message : undefined
  const { reload } = family
  useEffect(() => {
    if (failure instanceof ApiError && (failure.status === 404 || failure.status === 409)) reload()
  }, [failure]) // only when a new failure arrives, not when the budget reloads
  return { ...mutation, message }
}


/**
 * Loads a family budget's resource, as useApi. A 404 loads the budget again: if the budget is gone, its page shows
 * the not-found page; if only the resource is (a deleted expense), the budget stays and the caller says so.
 */
export function useFamilyApi<T>(family: FamilyData, path: string | null) {
  const result = useApi<T>(path)
  const { reload } = family
  useEffect(() => {
    if (result.status === 404) reload()
  }, [result.status]) // only when a load fails anew, not when the budget reloads
  return result
}
