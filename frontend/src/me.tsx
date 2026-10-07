import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { api, type Me } from './api'
import { isMe } from './guards'
import { browserZone, msUntilMidnight } from './timeZone'

/** What `/api/me` says of the user, kept up to date, and the one place that changes their time zone (D-101). */
interface MeState {
  me: Me
  /** Today's date in the user's zone, as the api says (D-53, D-100); never the browser's clock. */
  today: string
  /** Saves the zone, an IANA id; throws the api's refusal (422 for an id it doesn't know). */
  setZone: (zone: string) => Promise<void>
  /** Asks `/api/me` again, such as after "Delete all my data", which takes the zone with the rest. */
  reload: () => Promise<void>
  /** The zone ids the api accepts (D-103); null if it couldn't say, so that nothing is offered on a guess. */
  acceptedZones: () => Promise<Set<string> | null>
}

const MeContext = createContext<MeState | null>(null)

/** A tab that comes back into view asks again if its answer is older than this. */
const STALE_MS = 60_000
/** The midnight request goes a little after midnight, so that the api's own clock is past it too. */
const MIDNIGHT_SLACK_MS = 2_000
/** Whatever the clocks say, the midnight request is never sooner than this after the last answer. */
const MIN_WAIT_MS = 60_000

/**
 * Holds `/api/me` for the app (D-101). The first time it sees no zone set, it saves the browser's, once; a refusal or a
 * failure leaves the zone unset (UTC) and the app as it is, with no error shown and no retry until the next page load.
 * Today comes only from the api (D-106): it is asked again when the tab becomes visible (if the last answer is older
 * than a minute) and once at the next midnight of the saved zone, one timer re-armed after each answer. Nothing else
 * polls, so that an idle tab doesn't keep the session alive.
 */
export function MeProvider({ initial, children }: { initial: Me; children: ReactNode }) {
  const [me, setMe] = useState(initial)
  const askedAt = useRef(0)
  /** Counts the midnight timer's answers, so that its effect arms the next one. */
  const [answers, setAnswers] = useState(0)

  const reload = useCallback(async () => {
    try {
      const answer = await api<unknown>('/me')
      askedAt.current = Date.now()
      if (isMe(answer)) setMe(answer)
    } catch { /* the date stays as it was until the next try */ }
  }, [])

  const setZone = useCallback(async (zone: string) => {
    const saved = await api<{ timeZone: string; today: string }>('/settings/time-zone', 'PUT', { timeZone: zone })
    setMe((current) => ({ ...current, timeZone: saved.timeZone, today: saved.today }))
  }, [])

  // The zones the api accepts (D-103), asked once per page load and only when something offers a zone.
  const zones = useRef<Promise<Set<string> | null>>(undefined)
  const acceptedZones = useCallback(() => {
    zones.current ??= api<string[]>('/settings/time-zones').then((list) => new Set(list), () => null)
    return zones.current
  }, [])

  // The first load without a zone: the browser's, once. A refusal leaves the user on UTC until the next load.
  const tried = useRef(false)
  useEffect(() => {
    if (me.timeZone) {
      tried.current = false
      return
    }
    const zone = browserZone()
    if (tried.current || !zone) return
    tried.current = true
    setZone(zone).catch(() => {})
  }, [me.timeZone, setZone])

  useEffect(() => {
    const check = () => {
      if (document.visibilityState === 'visible' && Date.now() - askedAt.current > STALE_MS) void reload()
    }
    askedAt.current = Date.now()
    document.addEventListener('visibilitychange', check)
    return () => document.removeEventListener('visibilitychange', check)
  }, [reload])

  // The one timer: at the next midnight of the saved zone (UTC while none is), however the tab is shown.
  useEffect(() => {
    const wait = Math.max(msUntilMidnight(me.timeZone ?? 'UTC', Date.now()) + MIDNIGHT_SLACK_MS, MIN_WAIT_MS)
    const timer = setTimeout(() => { void reload().finally(() => setAnswers((n) => n + 1)) }, wait)
    return () => clearTimeout(timer)
  }, [me.timeZone, me.today, answers, reload])

  const value = useMemo(() => ({ me, today: me.today, setZone, reload, acceptedZones }),
    [me, setZone, reload, acceptedZones])
  return <MeContext.Provider value={value}>{children}</MeContext.Provider>
}

export function useMe(): MeState {
  const state = useContext(MeContext)
  if (!state) throw new Error('useMe needs a MeProvider: the screens take today from /api/me, never from the browser')
  return state
}

/** Today's date, "2026-10-07", in the user's zone. */
export const useToday = () => useMe().today
