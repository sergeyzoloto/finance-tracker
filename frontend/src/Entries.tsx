import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { dateOfIso, formatDate, useApi, type EntryPage, type EntrySummary } from './api'
import { SharedMark } from './CashFlowTable'
import { AccountSelect, CategorySelect, Errors, Loading } from './components'
import DateInput from './DateInput'
import EntrySummaryPanel from './EntrySummaryPanel'
import { payeeFilterOf, payeeFilterOptions, payeeFilterValue, useFamilyPayees } from './familyPayees'
import { describeEntry, entryKindLabel, kindLabel, useLedger } from './ledger'
import { useToday } from './me'
import { formatMoney, negate } from './money'
import { forgetPage, rememberedSearch } from './pageState'
import { DEFAULT_PRESET, PERIOD_LABELS, presetNamed, presetRange, PRESETS, useLatestEntryDay, type Preset } from './periods'

const PAGE_SIZE = 50
const FILTERS = ['from', 'to', 'accountId', 'categoryId', 'counterpartyId', 'payee', 'q'] as const
type Filter = (typeof FILTERS)[number]

const PERIOD_KEYS = ['period', 'from', 'to'] as const

/**
 * The Period list's choice from the URL (D-522, D-869): a preset by name (`period=<preset>`), `period=all` for All
 * time, else "custom" for typed dates (`from`, `to`, either or both), else the default, "Month of the latest entry".
 */
export function entriesPeriod(params: URLSearchParams): Preset | 'custom' | 'all' {
  if (params.get('period') === 'all') return 'all'
  const preset = presetNamed(params.get('period'))
  if (preset) return preset
  return params.get('from') || params.get('to') ? 'custom' : DEFAULT_PRESET
}

/**
 * A link into Entries with filters of its own but no period (D-874): the period this tab last showed on Entries, else
 * the default, put first; the link's own filters replace the remembered ones. Null when the URL needs nothing: it has a
 * period, or nothing at all (the default).
 */
export function withRememberedPeriod(params: URLSearchParams, remembered: string): URLSearchParams | null {
  if (PERIOD_KEYS.some((k) => params.has(k)) || [...params.keys()].length === 0) return null
  const before = new URLSearchParams(remembered)
  const next = new URLSearchParams()
  PERIOD_KEYS.forEach((k) => { const v = before.get(k); if (v) next.set(k, v) })
  if (!PERIOD_KEYS.some((k) => next.has(k))) next.set('period', DEFAULT_PRESET)
  params.forEach((value, k) => next.append(k, value))
  return next
}

/**
 * Entries, newest first, with filters and pages kept in the URL, so that Back and a reload keep them. Under 40rem each
 * entry is a block of lines, date, payee, category, accounts, amount and the family line, so that nothing scrolls
 * sideways at 375 px (D-351). The Period list is the dashboard's (D-522), and a summary row under the column headers
 * covers every entry the filters match (D-524).
 */
export default function Entries() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const location = useLocation()
  const today = useToday()
  const { ledger, error: ledgerError } = useLedger()
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const period = entriesPeriod(params)
  // D-874: a link with filters but no period takes the remembered one, shown in the URL, before any request.
  const completed = withRememberedPeriod(params, rememberedSearch('/entries'))
  useEffect(() => {
    if (completed) setParams(completed, { replace: true })
  })
  // "Month of the latest entry" is the whole ledger's, whatever the other filters (D-522).
  const latest = useLatestEntryDay(period === 'latest-entry-month', today)
  const range = period === 'all' || period === 'custom' ? null
    : presetRange(period, dateOfIso(today), latest.day ? dateOfIso(latest.day) : null)
  const filter = Object.fromEntries(FILTERS.map((f) => [f, params.get(f) ?? ''])) as Record<Filter, string>
  if (range) {
    filter.from = range.from
    filter.to = range.to
  } else if (period === 'all') {
    filter.from = ''
    filter.to = ''
  }
  // One of the two payee filters (D-547): a counterparty wins when, by hand, the URL has both (D-556).
  if (filter.counterpartyId !== '') filter.payee = ''
  // No request for another month before the latest entry's is known.
  const waiting = completed !== null || (period === 'latest-entry-month' && latest.day === undefined)

  const filters = new URLSearchParams(Object.entries(filter).filter(([, v]) => v !== ''))
  const query = new URLSearchParams(filters)
  query.set('page', String(page))
  query.set('size', String(PAGE_SIZE))
  const entries = useApi<EntryPage>(waiting ? null : `/entries?${query}`)
  // Asked when the filters change, not the page (D-524).
  const summary = useApi<EntrySummary>(waiting ? null : `/entries/summary?${filters}`)

  /** Changes filters; any change starts again at the first page. */
  const setFilter = (changes: Partial<Record<Filter | 'period', string>>, replace = false) => {
    const next = new URLSearchParams(params)
    Object.entries(changes).forEach(([key, value]) => (value ? next.set(key, value) : next.delete(key)))
    next.delete('page')
    setParams(next, { replace })
  }
  /** A preset by name, or All time (`period=all`, D-869); typed dates go (D-522). */
  const choosePeriod = (choice: string) => setFilter({ period: choice, from: '', to: '' })
  /** A typed From or To: Custom, keeping the other end of the period shown. */
  const setDay = (key: 'from' | 'to', day: string) =>
    setFilter({ period: '', from: filter.from, to: filter.to, [key]: day })
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

  // "Clear filters" shows whenever the state differs from the default (D-874).
  const filtered = period !== DEFAULT_PRESET || FILTERS.some((f) => f !== 'from' && f !== 'to' && filter[f] !== '')
  const clear = () => {
    forgetPage('/entries')
    setParams(new URLSearchParams())
  }
  const familyPayees = useFamilyPayees()
  const payeeOptions = ledger ? payeeFilterOptions(ledger.counterparties, familyPayees, filter.payee) : []
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
          <select value={period} onChange={(e) => e.target.value !== 'custom' && choosePeriod(e.target.value)}>
            <option value="all">All time</option>
            {PRESETS.map((p) => <option key={p} value={p}>{PERIOD_LABELS[p]}</option>)}
            <option value="custom" disabled>{PERIOD_LABELS.custom}</option>
          </select>
        </label>
        <label>From <DateInput value={filter.from} onChange={(day) => setDay('from', day)} /></label>
        <label>To <DateInput value={filter.to} onChange={(day) => setDay('to', day)} /></label>
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
              <select value={payeeFilterValue(filter.counterpartyId, filter.payee, payeeOptions)}
                onChange={(e) => setFilter(payeeFilterOf(e.target.value))}>
                <option value="">Anyone</option>
                {payeeOptions.map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </label>
          </>
        )}
        <label>
          Text{' '}
          <input type="search" value={text} placeholder="Memo or payee" maxLength={100} onChange={(e) => setText(e.target.value)} />
        </label>
        {filtered && <button type="button" onClick={clear}>Clear filters</button>}
      </fieldset>

      <Errors messages={[ledgerError ?? latest.error ?? entries.error ?? summary.error]} />
      {summary.data && summary.data.count > 0 && <EntrySummaryPanel summary={summary.data} loading={summary.loading} />}
      {(!data || !ledger) && !(ledgerError ?? entries.error) && <Loading what="entries" />}
      {data && ledger && data.content.length === 0 && (
        filtered ? <p className="empty">No entries match these filters.</p>
          : <p className="empty">No entries yet. <Link to="/entries/new">Add the first one</Link> or <Link to="/settings/import">import your ledger</Link>.</p>
      )}
      {data && ledger && data.content.length > 0 && (
        <>
          <table className={`entries entry-list ${entries.loading ? 'stale' : ''}`}>
            <thead>
              <tr><th>Date</th><th>Payee</th><th>Category</th><th>Accounts</th><th className="amount">Amount</th></tr>
            </thead>
            <tbody>
              {data.content.map((entry) => {
                const summary = describeEntry(entry, ledger, true)
                // UX-9: a payment or a receipt reads with its record's category, as a share does; display only.
                const category = summary.category || entry.family?.recordCategory?.name || ''
                const tone = summary.direction < 0 ? 'expense' : summary.direction > 0 ? 'income' : ''
                return (
                  <tr key={entry.id} className="clickable" onClick={() => open(entry.id)}>
                    <td className="date nowrap">
                      <Link to={`/entries/${entry.id}`} state={{ from: location.pathname + location.search }}
                        onClick={(e) => e.stopPropagation()}>{formatDate(entry.entryDate)}</Link>
                    </td>
                    <td className="payee">
                      {summary.payee || <span className="muted">—</span>}
                      {entry.memo && <div className="muted small">{entry.memo}</div>}
                    </td>
                    <td className="category">
                      <span className="category-name">
                        {category || <span className="muted">{kindLabel(entry.kind)}</span>}
                      </span>
                      {entry.family && (
                        // D-442: the row names its family budget here, once: the kind, then the shared icon and the name.
                        <div className="muted small family-line" title="It changes in the family budget">
                          {category && `${entryKindLabel(entry)} · `}
                          <SharedMark budget={entry.family.ledgerName} named />
                        </div>
                      )}
                    </td>
                    <td className="flow">{summary.flow}</td>
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
