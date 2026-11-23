import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router'
import { api } from './api'
import { badgeName, badgeText, countOf, UNREAD_COUNT_PATH } from './notifications'

/** Fetches the unread count again; the notifications page calls it after a mark. */
export const RefreshUnread = createContext<() => void>(() => {})
export const useRefreshUnread = () => useContext(RefreshUnread)

/**
 * The caller's unread count (D-1134): fetched when the shell mounts and again each time the pathname changes, and
 * on `refresh`; never by a timer, on focus or on visibility. A failed fetch hides the badge (null) until a later one
 * succeeds (D-1139). With `enabled` false nothing is requested.
 */
export function useUnreadCount(enabled: boolean) {
  const [count, setCount] = useState<number | null>(null)
  const latest = useRef(0)
  const { pathname } = useLocation()
  const refresh = useCallback(() => {
    if (!enabled) return
    const request = ++latest.current
    api<unknown>(UNREAD_COUNT_PATH).then(
      (answer) => { if (request === latest.current) setCount(countOf(answer)) },
      () => { if (request === latest.current) setCount(null) },
    )
  }, [enabled])
  useEffect(() => {
    refresh()
    // The cleanup drops a count reply that arrives after the page has changed.
    return () => { latest.current++ }
  }, [refresh, pathname])
  return { count: enabled ? count : null, refresh }
}

/** The header's badge (D-1135): a link to the notifications, nothing at 0 or without a count. */
export function UnreadBadge({ count }: { count: number | null }) {
  if (count === null || count <= 0) return null
  return (
    <Link className="unread-badge" to="/notifications" aria-label={badgeName(count)} title={badgeName(count)}>
      <BellIcon />
      <span className="unread-count" aria-hidden="true">{badgeText(count)}</span>
    </Link>
  )
}

function BellIcon() {
  return (
    <svg className="icon" width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
      <path d="M3.5 11.5h9l-1.2-1.6V7a3.3 3.3 0 0 0-6.6 0v2.9zM6.6 13.4a1.5 1.5 0 0 0 2.8 0"
        stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" fill="none" />
    </svg>
  )
}
