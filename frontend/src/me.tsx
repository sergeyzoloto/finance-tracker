import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { api, type Me } from './api'
import { isMe } from './guards'
import { browserZone } from './timeZone'

/** What `/api/me` says of the user, kept up to date, and the one place that changes their time zone (D-101). */
interface MeState {
  me: Me
  /** Today's date in the user's zone, as the api says (D-53, D-100); never the browser's clock. */
  today: string
  /** Saves the zone, an IANA id; throws the api's refusal (422 for an id it doesn't know). */
  setZone: (zone: string) => Promise<void>
  /** Asks `/api/me` again, such as after "Delete all my data", which takes the zone with the rest. */
  reload: () => Promise<void>
}

const MeContext = createContext<MeState | null>(null)

/** How long a tab may stay in view before it asks again whether the date has turned. */
const RECHECK_MS = 10 * 60_000
/** A tab that comes back into view asks again if its answer is older than this. */
const STALE_MS = 60_000

/**
 * Holds `/api/me` for the app (D-101). The first time it sees no zone set, it saves the browser's. Today comes only from
 * the api: it is asked again when the tab comes back into view or stays open for ten minutes, so that a tab left open
 * overnight turns its dates over.
 */
export function MeProvider({ initial, children }: { initial: Me; children: ReactNode }) {
  const [me, setMe] = useState(initial)
  const askedAt = useRef(0)

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
    window.addEventListener('focus', check)
    const timer = setInterval(check, RECHECK_MS)
    return () => {
      document.removeEventListener('visibilitychange', check)
      window.removeEventListener('focus', check)
      clearInterval(timer)
    }
  }, [reload])

  const value = useMemo(() => ({ me, today: me.today, setZone, reload }), [me, setZone, reload])
  return <MeContext.Provider value={value}>{children}</MeContext.Provider>
}

export function useMe(): MeState {
  const state = useContext(MeContext)
  if (!state) throw new Error('useMe needs a MeProvider: the screens take today from /api/me, never from the browser')
  return state
}

/** Today's date, "2026-10-07", in the user's zone. */
export const useToday = () => useMe().today
