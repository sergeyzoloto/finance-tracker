import { useEffect, useId, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { dateOfIso, formatDate, useApi, type EntryPage, type EntrySummary } from './api'
import { SharedMark } from './CashFlowTable'
import { AccountSelect, CategorySelect, Errors, Loading } from './components'
import DateInput from './DateInput'
import Breakdown from './Breakdown'
import EntrySummaryPanel from './EntrySummaryPanel'
import HistoryPanel from './HistoryPanel'
import MonthlyChart from './MonthlyChart'
import { DIRECTIONS, LIST_FILTERS, ORIGINS, toggled, valuesOf, withValue, withValues, type ListFilter } from './entryFilters'
import { payeeFilterOf, payeeFilterOptions, payeeFilterValue, useFamilyPayees, type PayeeOption } from './familyPayees'
import { categoryLabel, describeEntry, entryKindLabel, kindLabel, useLedger, type Ledger } from './ledger'
import { useToday } from './me'
import { formatMoney, negate } from './money'
import { forgetPage, rememberedSearch } from './pageState'
import { DEFAULT_PRESET, PERIOD_LABELS, presetNamed, presetRange, PRESETS, useLatestEntryDay, type Preset } from './periods'

const PAGE_SIZE = 50
const FILTERS = ['from', 'to', 'q'] as const
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
  // Several values per filter, any of them, all filters (ENT-2, D-873); a counterparty and a name are one Payee
  // filter (D-888).
  const lists = Object.fromEntries(LIST_FILTERS.map((f) => [f, valuesOf(params, f)])) as Record<ListFilter, string[]>
  // No request for another month before the latest entry's is known.
  const waiting = completed !== null || (period === 'latest-entry-month' && latest.day === undefined)

  const filters = new URLSearchParams()
  if (filter.from) filters.set('from', filter.from)
  if (filter.to) filters.set('to', filter.to)
  for (const f of ['accountId', 'categoryId', 'counterpartyId', 'payee'] as const) lists[f].forEach((v) => filters.append(f, v))
  if (filter.q) filters.set('q', filter.q)
  for (const f of ['direction', 'origin'] as const) lists[f].forEach((v) => filters.append(f, v))
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
  /** Adds a value to a list filter, or removes it (D-873). */
  const setValue = (f: ListFilter, value: string, add: boolean) => setParams(withValue(params, f, value, add))
  /** A toggle group's button (D-873): none pressed is all. */
  const toggle = (f: 'direction' | 'origin', value: string, all: readonly string[]) =>
    setParams(withValues(params, f, toggled(lists[f], value, all)))
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
  const filtered = period !== DEFAULT_PRESET || filter.q !== '' || LIST_FILTERS.some((f) => lists[f].length > 0)
  const clear = () => {
    forgetPage('/entries')
    setParams(new URLSearchParams())
  }
  const familyPayees = useFamilyPayees()
  const payeeOptions = ledger ? payeeFilterOptions(ledger.counterparties, familyPayees, lists.payee) : []
  const chips = ledger ? filterChips(lists, ledger, payeeOptions) : []
  const open = (id: number) => navigate(`/entries/${id}`, { state: { from: location.pathname + location.search } })
  const data = entries.data
  // The History panel's entry: component state, not the URL.
  const [historyOf, setHistoryOf] = useState<{ id: number; subject: string }>()

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
              <AccountSelect accounts={ledger.accounts.filter((a) => !lists.accountId.includes(String(a.id)))} value=""
                placeholder={lists.accountId.length ? 'Add an account' : 'All accounts'}
                onChange={(id) => id && setValue('accountId', id, true)} />
            </label>
            <label>
              Category{' '}
              <CategorySelect categories={ledger.categories.filter((c) => !c.archived && !lists.categoryId.includes(String(c.id)))}
                value="" placeholder={lists.categoryId.length ? 'Add a category' : 'All categories'}
                onChange={(id) => id && setValue('categoryId', id, true)} />
            </label>
            <label>
              Payee or person{' '}
              <select value="" onChange={(e) => {
                const { counterpartyId, payee } = payeeFilterOf(e.target.value)
                if (counterpartyId) setValue('counterpartyId', counterpartyId, true)
                else if (payee) setValue('payee', payee, true)
              }}>
                <option value="">{lists.counterpartyId.length + lists.payee.length ? 'Add a payee' : 'Anyone'}</option>
                {payeeOptions.filter((o) => !chips.some((c) => c.option === o.value)).map((o) => (
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
        <ToggleGroups label="Direction" options={DIRECTIONS} chosen={lists.direction}
          onToggle={(v) => toggle('direction', v, DIRECTIONS.map((d) => d.value))} />
        <ToggleGroups label="Origin" options={ORIGINS} chosen={lists.origin}
          onToggle={(v) => toggle('origin', v, ORIGINS.map((o) => o.value))} />
        {filtered && <button type="button" onClick={clear}>Clear filters</button>}
        {chips.length > 0 && (
          <ul className="chips" aria-label="Chosen filters">
            {chips.map((chip) => (
              <li key={`${chip.filter}:${chip.value}`} className="chip">
                <span className="muted">{chip.kind}</span> {chip.label}
                <button type="button" className="chip-remove" aria-label={`Remove ${chip.label}`} title={`Remove ${chip.label}`}
                  onClick={() => setValue(chip.filter, chip.value, false)}>×</button>
              </li>
            ))}
          </ul>
        )}
      </fieldset>

      <Errors messages={[ledgerError ?? latest.error ?? entries.error ?? summary.error]} />
      {summary.data && summary.data.count > 0 && (
        <EntrySummaryPanel summary={summary.data} loading={summary.loading}>
          {/* ENT-3: the breakdown by category, payee and account, folding with the panel. */}
          {ledger && <Breakdown filters={filters.toString()} counterparties={ledger.counterparties} />}
          {/* ENT-4: the monthly result, under the breakdown, folding with it. */}
          <MonthlyChart filters={filters.toString()} />
        </EntrySummaryPanel>
      )}
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
                      <div>
                        <button type="button" className="link small" aria-label={`History of the entry of ${formatDate(entry.entryDate)}`}
                          onClick={(e) => { e.stopPropagation(); setHistoryOf({ id: entry.id, subject: `Entry of ${formatDate(entry.entryDate)}` }) }}>
                          History
                        </button>
                      </div>
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
      {historyOf && (
        <HistoryPanel kind="ENTRY" id={historyOf.id} subject={historyOf.subject} ledger={ledger} onClose={() => setHistoryOf(undefined)} />
      )}
    </>
  )
}

/** A chosen value of a list filter, as a chip (D-873): its filter's name, its label, and the URL value × removes. */
interface Chip {
  filter: ListFilter
  value: string
  kind: string
  label: string
  /** The payee option it stands for, so that the Payee list doesn't offer it again. */
  option?: string
}

/** The chips of the chosen accounts, categories and payees, in that order, each by its name. */
export function filterChips(lists: Record<ListFilter, string[]>, ledger: Ledger, payeeOptions: PayeeOption[]): Chip[] {
  const chips: Chip[] = []
  for (const value of lists.accountId) {
    const account = ledger.accounts.find((a) => String(a.id) === value)
    chips.push({ filter: 'accountId', value, kind: 'Account', label: account?.name ?? `Account ${value}` })
  }
  for (const value of lists.categoryId) {
    const category = ledger.categories.find((c) => String(c.id) === value)
    chips.push({ filter: 'categoryId', value, kind: 'Category', label: category ? categoryLabel(category) : `Category ${value}` })
  }
  for (const value of lists.counterpartyId) {
    const option = payeeOptions.find((o) => o.value === `c:${value}`)
    chips.push({ filter: 'counterpartyId', value, kind: 'Payee', label: option?.name ?? `Payee ${value}`, option: `c:${value}` })
  }
  for (const value of lists.payee) {
    // A name an option has in another case is that option (D-568).
    const option = payeeFilterValue('', value, payeeOptions)
    const name = payeeOptions.find((o) => o.value === option)?.name ?? value
    chips.push({ filter: 'payee', value, kind: 'Payee', label: name, option })
  }
  return chips
}

/** A toggle group whose buttons each add or remove their value (D-873); none pressed is all of them. */
function ToggleGroups({ label, options, chosen, onToggle }: {
  label: string
  options: readonly { value: string; label: string }[]
  chosen: string[]
  onToggle: (value: string) => void
}) {
  const id = useId()
  return (
    <div className="toggle-group">
      <span id={id} className="toggle-label">{label}</span>
      <div role="group" aria-labelledby={id} className="toggles">
        {options.map((o) => (
          <button key={o.value} type="button" aria-pressed={chosen.includes(o.value)} onClick={() => onToggle(o.value)}>
            {o.label}
          </button>
        ))}
      </div>
    </div>
  )
}
