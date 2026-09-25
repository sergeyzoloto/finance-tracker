import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { formatDate, isoDate, useApi, type EntryPage } from './api'
import { AccountSelect, CategorySelect, Errors, Loading } from './components'
import { describeEntry, useLedger } from './ledger'
import { formatMoney, negate } from './money'

const PAGE_SIZE = 50
const FILTERS = ['from', 'to', 'accountId', 'categoryId', 'counterpartyId', 'q'] as const
type Filter = (typeof FILTERS)[number]

/** Named periods, as from–to dates. */
function periods() {
  const now = new Date()
  const y = now.getFullYear()
  const m = now.getMonth()
  return {
    'this-month': [isoDate(new Date(y, m, 1)), isoDate(new Date(y, m + 1, 0))],
    'last-month': [isoDate(new Date(y, m - 1, 1)), isoDate(new Date(y, m, 0))],
    'this-year': [isoDate(new Date(y, 0, 1)), isoDate(new Date(y, 11, 31))],
    'last-year': [isoDate(new Date(y - 1, 0, 1)), isoDate(new Date(y - 1, 11, 31))],
  } as Record<string, [string, string]>
}

/** Entries, newest first, with filters and pages kept in the URL, so that Back and a reload keep them. */
export default function Entries() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const location = useLocation()
  const { ledger, error: ledgerError } = useLedger()
  const filter = Object.fromEntries(FILTERS.map((f) => [f, params.get(f) ?? ''])) as Record<Filter, string>
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)

  const query = new URLSearchParams(Object.entries(filter).filter(([, v]) => v !== ''))
  query.set('page', String(page))
  query.set('size', String(PAGE_SIZE))
  const entries = useApi<EntryPage>(`/entries?${query}`)

  /** Changes filters; any change starts again at the first page. */
  const setFilter = (changes: Partial<Record<Filter, string>>, replace = false) => {
    const next = new URLSearchParams(params)
    Object.entries(changes).forEach(([key, value]) => (value ? next.set(key, value) : next.delete(key)))
    next.delete('page')
    setParams(next, { replace })
  }
  const setPage = (p: number) => {
    const next = new URLSearchParams(params)
    if (p === 0) next.delete('page')
    else next.set('page', String(p))
    setParams(next)
  }

  // The search text goes to the URL, and so to the server, once the user pauses typing.
  const [text, setText] = useState(filter.q)
  useEffect(() => setText((typed) => (typed.trim() === filter.q ? typed : filter.q)), [filter.q])
  useEffect(() => {
    if (text.trim() === filter.q) return
    const timer = setTimeout(() => setFilter({ q: text.trim() }, true), 300)
    return () => clearTimeout(timer)
  })

  const named = periods()
  const period = !filter.from && !filter.to ? 'all'
    : Object.entries(named).find(([, [from, to]]) => from === filter.from && to === filter.to)?.[0] ?? 'custom'
  const filtered = FILTERS.some((f) => filter[f] !== '')
  const open = (id: number) => navigate(`/entries/${id}`, { state: { from: location.pathname + location.search } })
  const data = entries.data

  return (
    <>
      <div className="page-title">
        <h2>Entries</h2>
        <Link className="button primary" to="/entries/new" state={{ from: location.pathname + location.search }}>New entry</Link>
      </div>

      <fieldset className="filters">
        <legend>Filter</legend>
        <label>
          Period{' '}
          <select value={period} onChange={(e) => {
            const [from, to] = named[e.target.value] ?? ['', '']
            if (e.target.value !== 'custom') setFilter({ from, to })
          }}>
            <option value="all">All time</option>
            <option value="this-month">This month</option>
            <option value="last-month">Last month</option>
            <option value="this-year">This year</option>
            <option value="last-year">Last year</option>
            <option value="custom" disabled={period !== 'custom'}>Custom</option>
          </select>
        </label>
        <label>From <input type="date" value={filter.from} onChange={(e) => setFilter({ from: e.target.value })} /></label>
        <label>To <input type="date" value={filter.to} onChange={(e) => setFilter({ to: e.target.value })} /></label>
        {ledger && (
          <>
            <label>
              Account{' '}
              <AccountSelect accounts={ledger.accounts} value={filter.accountId} placeholder="All accounts"
                onChange={(accountId) => setFilter({ accountId })} />
            </label>
            <label>
              Category{' '}
              <CategorySelect categories={ledger.categories.filter((c) => !c.archived || String(c.id) === filter.categoryId)}
                value={filter.categoryId} placeholder="All categories" onChange={(categoryId) => setFilter({ categoryId })} />
            </label>
            <label>
              Payee or person{' '}
              <select value={filter.counterpartyId} onChange={(e) => setFilter({ counterpartyId: e.target.value })}>
                <option value="">Anyone</option>
                {ledger.counterparties.map((c) => (
                  <option key={c.id} value={c.id}>{c.name}{c.archived ? ' (archived)' : ''}</option>
                ))}
              </select>
            </label>
          </>
        )}
        <label>
          Text{' '}
          <input type="search" value={text} placeholder="Memo or payee" maxLength={100} onChange={(e) => setText(e.target.value)} />
        </label>
        {filtered && <button type="button" onClick={() => setParams(new URLSearchParams())}>Clear filters</button>}
      </fieldset>

      <Errors messages={[ledgerError ?? entries.error]} />
      {(!data || !ledger) && !(ledgerError ?? entries.error) && <Loading what="entries" />}
      {data && ledger && data.content.length === 0 && (
        filtered ? <p className="empty">No entries match these filters.</p>
          : <p className="empty">No entries yet. <Link to="/entries/new">Add the first one</Link> or <Link to="/import">import your ledger</Link>.</p>
      )}
      {data && ledger && data.content.length > 0 && (
        <>
          <table className={`entries ${entries.loading ? 'stale' : ''}`}>
            <thead>
              <tr><th>Date</th><th>Payee</th><th>Category</th><th>Accounts</th><th className="amount">Amount</th></tr>
            </thead>
            <tbody>
              {data.content.map((entry) => {
                const summary = describeEntry(entry, ledger)
                const tone = summary.direction < 0 ? 'expense' : summary.direction > 0 ? 'income' : ''
                return (
                  <tr key={entry.id} className="clickable" onClick={() => open(entry.id)}>
                    <td className="nowrap">
                      <Link to={`/entries/${entry.id}`} state={{ from: location.pathname + location.search }}
                        onClick={(e) => e.stopPropagation()}>{formatDate(entry.entryDate)}</Link>
                    </td>
                    <td>
                      {summary.payee || <span className="muted">—</span>}
                      {entry.memo && <div className="muted small">{entry.memo}</div>}
                    </td>
                    <td>{summary.category || <span className="muted">{kindLabel(entry.kind)}</span>}</td>
                    <td>{summary.flow}</td>
                    <td className={`amount nowrap ${tone}`}>
                      {summary.amounts.map((m) => formatMoney(summary.direction < 0 ? negate(m.amount) : m.amount, m.currency,
                        { signed: summary.direction !== 0 })).join(' → ')}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          <nav className="pager" aria-label="Pages">
            <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>← Newer</button>
            <span>
              {data.page * data.size + 1}–{data.page * data.size + data.content.length} of {data.totalElements}
            </span>
            <button type="button" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Older →</button>
          </nav>
        </>
      )}
      {data && data.content.length === 0 && page > 0 && (
        <button type="button" onClick={() => setPage(0)}>Back to the first page</button>
      )}
    </>
  )
}

/** What an entry without a category is, for the category column. */
function kindLabel(kind: string) {
  return ({
    TRANSFER: 'Transfer', LOAN_GIVEN: 'Loan given', LOAN_REPAID: 'Loan repaid', CURRENCY_EXCHANGE: 'Exchange',
    OPENING_BALANCE: 'Opening balance', MANUAL: 'Correction',
  } as Record<string, string>)[kind] ?? '—'
}
