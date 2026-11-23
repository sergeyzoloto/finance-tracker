import { useSearchParams } from 'react-router'
import { formatInstant, useApi } from './api'
import { activityPath, pageParam, SESSION_NOTE, sentence, type ActivityPage } from './activity'
import { Errors, Loading } from './components'

/**
 * The user's activity (D-1119): sign-ins, sign-outs, ended sessions, settings and rates changes and, in a family
 * budget, its membership and settings events, newest first, 50 to a page. The page number is the address's `page`.
 */
export default function Activity() {
  const [params, setParams] = useSearchParams()
  const page = pageParam(params.get('page'))
  const { data, error, loading, reload } = useApi<ActivityPage>(activityPath(page))
  const go = (to: number) => setParams(to === 0 ? {} : { page: String(to) })

  return (
    <section className="activity">
      <h2>Activity</h2>
      <p className="muted small">{SESSION_NOTE}</p>
      {error !== undefined && (
        <>
          <Errors messages={[error]} />
          <button type="button" onClick={reload}>Try again</button>
        </>
      )}
      {error === undefined && (loading || !data) && <Loading />}
      {error === undefined && !loading && data && data.content.length === 0 && (
        <>
          <p className="empty">{page > 0 ? 'No activity on this page.' : 'No activity yet.'}</p>
          {page > 0 && <button type="button" onClick={() => go(0)}>Back to the first page</button>}
        </>
      )}
      {error === undefined && data && data.content.length > 0 && (
        <>
          <ol className="activity-list">
            {data.content.map((row) => (
              <li key={row.id} className="activity-row">
                <span>{sentence(row)}</span>
                <span className="muted small">{formatInstant(row.at)}</span>
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
