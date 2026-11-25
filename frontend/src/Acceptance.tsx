import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { useLocation } from 'react-router'
import { sentence, useMutation, type FamilyLedger } from './api'
import {
  acceptedText, acceptLabel, acceptLedger, askPending, bannerText, isPendingRefusal, PENDING_MARK, PENDING_MARK_NOTE,
  PRESS_AGAIN, type Pending,
} from './acceptance'
import { Errors } from './components'
import type { FamilyData } from './familyData'

// Family acceptance on the screens (HIST-3 part 5, ADR 0006 section 7.2). Everything here draws nothing while the
// server's acceptance is off: the pending summary then answers 404 once and the answer is kept for the session.

interface Value {
  /** Acceptance is on and the member has changes to accept. */
  count: number
  /** Raised by every accept, so a list or a record page loads again. */
  generation: number
  /** Accepts this budget's pending changes; the count accepted. */
  accept: () => Promise<number>
}

const Context = createContext<Value>({ count: 0, generation: 0, accept: async () => 0 })
export const useAcceptance = () => useContext(Context)

/** The family budgets the user belongs to, as the shell loaded them; none while they haven't loaded. */
export const KnownLedgers = createContext<FamilyLedger[] | undefined>(undefined)

/**
 * Around a family budget's pages: asks the pending summary when a page opens and on each move between its pages (never
 * by a timer), shows the banner with Accept above the page, and lets the page's forms accept in place.
 */
export function AcceptanceScope({ family, children }: { family: FamilyData; children: ReactNode }) {
  const { pathname } = useLocation()
  const ledgerId = family.ledger.id
  const [pending, setPending] = useState<Pending | null>(null)
  const [generation, setGeneration] = useState(0)
  const [confirmation, setConfirmation] = useState<string>()
  const latest = useRef(0)
  const banner = useMutation()

  const refresh = useCallback(() => {
    const request = ++latest.current
    void askPending(ledgerId).then((answer) => { if (request === latest.current) setPending(answer) })
  }, [ledgerId])
  useEffect(() => {
    refresh()
    setConfirmation(undefined)
    // The cleanup drops an answer that arrives after the page has changed.
    return () => { latest.current++ }
  }, [refresh, pathname])

  const accept = useCallback(async () => {
    const accepted = await acceptLedger(ledgerId)
    setGeneration((g) => g + 1)
    refresh()
    return accepted
  }, [ledgerId, refresh])

  const count = pending?.count ?? 0
  return (
    <Context.Provider value={{ count, generation, accept }}>
      {count > 0 && (
        <div className="notice pending-banner" role="status">
          <span>{bannerText(count)}</span>
          {' '}
          <button type="button" disabled={banner.pending}
            onClick={() => void banner.run(async () => setConfirmation(acceptedText(await accept())))}>
            Accept
          </button>
        </div>
      )}
      {confirmation && <p className="success" role="status">{confirmation}</p>}
      <Errors messages={[banner.error]} />
      {children}
    </Context.Provider>
  )
}

/** Loads a page's data again after an accept of the budget, never on the first render. */
export function useReloadOnAccept(reload: () => void) {
  const { generation } = useAcceptance()
  const seen = useRef(generation)
  useEffect(() => {
    if (generation === seen.current) return
    seen.current = generation
    reload()
  }, [generation, reload])
}

/**
 * The refusal of a write while changes wait (409), in the place the action's other errors show: the server's message
 * and one button that accepts them. After it the form stays as typed, the count accepted is said, and nothing is
 * retried: the user presses the same button again.
 */
export function PendingOffer({ failure, accept }: { failure: Error | undefined; accept: () => Promise<number> }) {
  const [done, setDone] = useState<{ failure: Error; count: number }>()
  const [problem, setProblem] = useState<string>()
  const [busy, setBusy] = useState(false)
  if (!isPendingRefusal(failure)) return null
  if (done?.failure === failure) {
    return <p className="success" role="status">{acceptedText(done.count)} {PRESS_AGAIN}</p>
  }
  const n = failure.pendingChanges
  const run = async () => {
    setBusy(true)
    setProblem(undefined)
    try {
      setDone({ failure, count: await accept() })
    } catch (e) {
      setProblem(e instanceof Error ? e.message : 'Unexpected error')
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="error-box" role="alert">
      <p>{sentence(failure.message)}</p>
      <p><button type="button" disabled={busy} onClick={() => void run()}>{n === undefined ? 'Accept' : acceptLabel(n)}</button></p>
      {problem && <p>{problem}</p>}
    </div>
  )
}

/** `PendingOffer` of a family budget's page, accepting through its `AcceptanceScope`. */
export function FamilyPendingOffer({ failure }: { failure: Error | undefined }) {
  return <PendingOffer failure={failure} accept={useAcceptance().accept} />
}

/** The small mark on a record the reader hasn't accepted the latest change of. */
export function PendingMark() {
  return <span className="badge pending-mark" title={PENDING_MARK_NOTE}>{PENDING_MARK}</span>
}
