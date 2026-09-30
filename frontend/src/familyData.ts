import { useEffect } from 'react'
import { ApiError, useMutation, type FamilyLedger, type FamilyMember } from './api'
import { familyMessage } from './family'

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
}

/** What creating a family budget couldn't finish, handed to its page (NewFamily). */
export interface CreationState { creationProblems?: string[] }

/**
 * A write about a family budget, as useMutation. A 404 or 409 loads the budget again: the budget may be gone, which
 * shows the not-found page, or changed meanwhile. `message` is the failure in the screens' words, unless it is only
 * about fields of the form (400).
 */
export function useFamilyMutation(family: FamilyData, onDone: () => void = () => {}) {
  const mutation = useMutation(() => { family.reload(); onDone() })
  const failure = mutation.failure
  const message = failure && !(failure instanceof ApiError && failure.errors.length > 0)
    ? familyMessage(failure.message) : undefined
  const { reload } = family
  useEffect(() => {
    if (failure instanceof ApiError && (failure.status === 404 || failure.status === 409)) reload()
  }, [failure]) // only when a new failure arrives, not when the budget reloads
  return { ...mutation, message }
}

