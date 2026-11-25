import { api, ApiError } from './api'

// Family acceptance without React (HIST-3 part 5, ADR 0006 section 7): the pending summary, whether acceptance is on,
// and the words. Acceptance is on when a family budget's pending summary answers; an unknown path (404) means off.
// The answer is kept for the session, "off" included, so that with acceptance off nothing is asked twice.

/** The code of a refused write (409) while the member has changes to accept. */
export const PENDING_CODE = 'FAMILY_PENDING_CHANGES'

/** What `GET /family-ledgers/{id}/pending` answers. */
export interface Pending { count: number; recordIds: number[] }

/** What `POST /family-ledgers/{id}/accept` answers: how many records it brought in. */
export interface AcceptAnswer { accepted: number; revision: number }

export const pendingPath = (ledgerId: number | string) => `/family-ledgers/${ledgerId}/pending`
export const acceptPath = (ledgerId: number | string) => `/family-ledgers/${ledgerId}/accept`

/** "1 change", "3 changes". */
export const changesText = (n: number) => `${n} change${n === 1 ? '' : 's'}`

/** The banner's sentence: "1 change by others is waiting for you". */
export const bannerText = (n: number) =>
  `${changesText(n)} by others ${n === 1 ? 'is' : 'are'} waiting for you`

/** The accept button of a refusal or a notification: "Accept 3 changes". */
export const acceptLabel = (n: number) => `Accept ${changesText(n)}`

/** What an accept says it did. */
export const acceptedText = (n: number) =>
  n === 0 ? 'Nothing was waiting for you.' : `Accepted ${changesText(n)}.`

/** The mark on a record that has a change the reader hasn't accepted. */
export const PENDING_MARK = 'Waiting for you'
export const PENDING_MARK_NOTE =
  'The record shows its latest version. Your own books follow once you accept the change.'

/** After an accept that stopped a refused action: nothing was retried. */
export const PRESS_AGAIN = 'Nothing was saved yet. Press the same button again.'

/** The pending summary in an answer, or null when it isn't one. */
export function pendingOf(answer: unknown): Pending | null {
  const a = answer as { count?: unknown; recordIds?: unknown } | null
  if (!a || typeof a.count !== 'number' || !Number.isInteger(a.count) || a.count < 0) return null
  if (!Array.isArray(a.recordIds) || !a.recordIds.every((id) => typeof id === 'number')) return null
  return { count: a.count, recordIds: a.recordIds as number[] }
}

/** Whether a failure is the refusal of a write because changes wait to be accepted. */
export const isPendingRefusal = (failure: Error | undefined): failure is ApiError =>
  failure instanceof ApiError && failure.status === 409 && failure.code === PENDING_CODE

type State = 'unknown' | 'on' | 'off'
let state: State = 'unknown'
let probe: Promise<unknown> | undefined

/** Whether acceptance is known to be on or off in this session. */
export const acceptanceState = (): State => state

/** Forgets what the session learned (tests only). */
export function resetAcceptance() {
  state = 'unknown'
  probe = undefined
}

/**
 * The member's pending summary of a family budget, or null: acceptance is off, the answer failed or it was not
 * a summary. The first answer of the session decides: 404 means off (an unknown path), but only when `current` says
 * the member belongs to that budget, since 404 is also what a budget they left answers. Once off, nothing is asked.
 */
export async function askPending(ledgerId: number | string, current = true): Promise<Pending | null> {
  if (state === 'unknown' && probe) await probe.catch(() => undefined)
  if (state === 'off') return null
  const request = api<unknown>(pendingPath(ledgerId))
  if (state === 'unknown') probe = request
  try {
    const summary = pendingOf(await request)
    if (summary) state = 'on'
    return summary
  } catch (e) {
    if (e instanceof ApiError && e.status === 404 && current) state = 'off'
    return null
  } finally {
    if (probe === request) probe = undefined
  }
}

/** Accepts the member's pending changes of a family budget; the count it accepted. */
export async function acceptLedger(ledgerId: number | string): Promise<number> {
  return (await api<AcceptAnswer>(acceptPath(ledgerId), 'POST')).accepted
}
