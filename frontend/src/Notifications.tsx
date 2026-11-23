import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { api, formatInstant, useApi, useMutation } from './api'
import { pageParam } from './activity'
import { Errors, Loading } from './components'
import {
  destination, notificationsPath, READ_ALL_PATH, readPath, type NotificationPage, type NotificationRow,
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
  const go = (to: number) => setParams(to === 0 ? {} : { page: String(to) })

  /** Runs a mark, then `after` when it worked; "Try again" runs the same again. */
  const attempt = async (action: () => Promise<unknown>, after: () => void) => {
    setRetry(() => () => attempt(action, after))
    mark.clear()
    if (!await mark.run(action)) return
    refreshUnread()
    after()
  }
  const open = (row: NotificationRow) => {
    const to = () => navigate(destination(row.kind, row.ledgerId))
    if (row.read) to()
    else void attempt(() => api(readPath(row.id), 'POST'), to)
  }
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
              <li key={row.id}>
                <button type="button" className={`notification-item${row.read ? '' : ' unread'}`} disabled={mark.pending}
                  onClick={() => open(row)}>
                  <span className="notification-text">
                    {!row.read && <span className="unread-label">Unread</span>}{row.text}
                  </span>
                  <span className="muted small">{formatInstant(row.at)}</span>
                </button>
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
