import { useContext, useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { api, formatInstant, useApi, useMutation } from './api'
import { pageParam } from './activity'
import { KnownLedgers } from './Acceptance'
import { acceptedText, acceptLabel, acceptLedger, acceptanceState, askPending } from './acceptance'
import { Errors, Loading } from './components'
import {
  destination, isAcceptKind, notificationsPath, READ_ALL_PATH, readPath, type NotificationPage, type NotificationRow,
} from './notifications'
import { useRefreshUnread } from './unread'

/**
 * The user's notifications (D-1120), newest first, 50 to a page; the page number is the address's `page`. Nothing
 * changes before the server has answered (D-1138). Opening a row marks it read, then goes to the family budget's
 * journal or members (D-1136); a failed mark keeps the row unread and stays on the page.
 */
export default function Notifications() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const refreshUnread = useRefreshUnread()
  const page = pageParam(params.get('page'))
  const { data, error, loading, reload } = useApi<NotificationPage>(notificationsPath(page))
  const mark = useMutation()
  const [retry, setRetry] = useState<() => Promise<unknown>>()
  // Acceptance (HIST-3): the member's pending count per family budget, for the rows that can be accepted.
  const known = useContext(KnownLedgers)
  const [pendingBy, setPendingBy] = useState<Record<number, number>>({})
  const [confirmation, setConfirmation] = useState<string>()
  const [recount, setRecount] = useState(0)
  const acceptIds = [...new Set((data?.content ?? []).filter((r) => isAcceptKind(r.kind)).map((r) => r.ledgerId))]
    .filter((id) => known?.some((l) => l.id === id)).sort((a, b) => a - b)
  const idsKey = acceptIds.join(',')
  useEffect(() => {
    let current = true
    void (async () => {
      const counts: Record<number, number> = {}
      for (const id of idsKey === '' ? [] : idsKey.split(',').map(Number)) {
        const answer = await askPending(id)
        if (!current) return
        if (answer) counts[id] = answer.count
        else if (acceptanceState() === 'off') break
      }
      if (current) setPendingBy(counts)
    })()
    return () => { current = false }
  }, [idsKey, recount])
  const go = (to: number) => setParams(to === 0 ? {} : { page: String(to) })

  /** Runs a mark, then `after` when it worked; "Try again" runs the same again. */
  const attempt = async (action: () => Promise<unknown>, after: () => void) => {
    setRetry(() => () => attempt(action, after))
    mark.clear()
    setConfirmation(undefined)
    if (!await mark.run(action)) return
    refreshUnread()
    after()
  }
  const open = (row: NotificationRow) => {
    const to = () => navigate(destination(row.kind, row.ledgerId))
    if (row.read) to()
    else void attempt(() => api(readPath(row.id), 'POST'), to)
  }
  const accept = (row: NotificationRow) => void attempt(
    async () => setConfirmation(acceptedText(await acceptLedger(row.ledgerId))),
    () => { reload(); setRecount((n) => n + 1) })
  const readAll = () => void attempt(() => api(READ_ALL_PATH, 'POST'), reload)

  return (
    <section className="notifications">
      <h2>Notifications</h2>
      {error !== undefined && (
        <>
          <Errors messages={[error]} />
          <button type="button" onClick={reload}>Try again</button>
        </>
      )}
      {mark.error !== undefined && (
        <>
          <Errors messages={[mark.error]} />
          <button type="button" disabled={mark.pending} onClick={() => void retry?.()}>Try again</button>
        </>
      )}
      {confirmation && <p className="success" role="status">{confirmation}</p>}
      {error === undefined && (loading || !data) && <Loading />}
      {error === undefined && !loading && data && data.content.length === 0 && (
        <>
          <p className="empty">{page > 0 ? 'No notifications on this page.' : 'No notifications yet.'}</p>
          {page > 0 && <button type="button" onClick={() => go(0)}>Back to the first page</button>}
        </>
      )}
      {error === undefined && data && data.content.length > 0 && (
        <>
          <p><button type="button" disabled={mark.pending} onClick={readAll}>Mark all as read</button></p>
          <ol className="notification-list">
            {data.content.map((row) => (
              <li key={row.id} className={isAcceptKind(row.kind) && (pendingBy[row.ledgerId] ?? 0) > 0 ? 'with-accept' : undefined}>
                <button type="button" className={`notification-item${row.read ? '' : ' unread'}`} disabled={mark.pending}
                  onClick={() => open(row)}>
                  <span className="notification-text">
                    {!row.read && <span className="unread-label">Unread</span>}{row.text}
                  </span>
                  <span className="muted small">{formatInstant(row.at)}</span>
                </button>
                {isAcceptKind(row.kind) && (pendingBy[row.ledgerId] ?? 0) > 0 && (
                  <button type="button" className="notification-accept" disabled={mark.pending}
                    title={acceptLabel(pendingBy[row.ledgerId])} onClick={() => accept(row)}>Accept</button>
                )}
              </li>
            ))}
          </ol>
          <nav className="pager" aria-label="Pages">
            <button type="button" disabled={page === 0} onClick={() => go(page - 1)}>← Newer</button>
            <span>{page * data.size + 1}–{page * data.size + data.content.length} of {data.total}</span>
            <button type="button" disabled={page + 1 >= data.totalPages} onClick={() => go(page + 1)}>Older →</button>
          </nav>
        </>
      )}
    </section>
  )
}
