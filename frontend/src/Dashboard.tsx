import { Suspense, useState, type ReactNode } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import {
  dateOfIso, formatDate, useApi, type AccountBalance, type CashFlowRow, type ConvertedBalance, type ConvertedCashFlow,
  type ConvertedCounterpartyBalance, type ConvertedNetWorth, type CounterpartyBalance, type EntryPage, type FamilyLedger,
  type NetWorth, type Rate, type Settings, type SharedSettlement,
} from './api'
import CashFlowTable from './CashFlowTable'
import { lazyWithReload } from './chunkReload'
import { Amounts, ErrorBoundary, Errors, Loading, ToggleGroup } from './components'
import {
  balancesDay, baseEquitySplit, belowMinorUnit, cashFlowTables, changeText, chartByMonth, compareColumns, comparisons,
  compareTables, convertedCashFlowTable, debtSides, DEFAULT_PRESET, drawable, drawnComparison, effectOf, equitySplits,
  familyDebts,
  inBaseFromQuery, isFamilyScope, isReserveEquity, listedInBalances, LOAN_ACCOUNTS, loanSides, mergeMissing, MISSING,
  missingDays, monthPairs, monthsBetween, needsLatestEntry, PERIOD_LABELS, periodFromQuery, PRESETS, rangeBars, RECORDS, RECORDS_LABELS,
  recordsOf, resultRange, scopeFromQuery, scopeParams, showOf, showOptions, showsOwnCurrencies, totalChanges,
  usedRateText, withRecords, withShow,
  type CashFlowTable as Table, type Cell, type CompareLine, type CompareTable, type Comparisons, type DayRange,
  type FamilyDebt, type PeriodChoice, type Records,
} from './dashboard'
import DateInput from './DateInput'
import EmptyLedger from './EmptyLedger'
import { ACCOUNT_TYPES, TYPE_LABELS } from './ledger'
import { useToday } from './me'
import { useLatestEntryDay } from './periods'
import { abs, formatMoney, signOf, sum } from './money'
import type { ChartData } from './IncomeExpenseChart'

// Recharts is most of the app's code; loading it with the chart keeps it off every other page.
const IncomeExpenseChart = lazyWithReload(() => import('./IncomeExpenseChart'))

/**
 * The home page. A ledger without any entry gets the choice of the demo data or a start from scratch; once the demo
 * is loaded, the dashboard shows its whole period.
 *
 * @param familyOn whether the family budget is switched on (D-25): the demo then comes with its family budget (H1)
 * @param families the user's family budgets, for the scopes of income and expenses (D-302); undefined while they load
 * @param onFamilyCreated called once the demo created its family budget, for the switcher to list it
 */
export default function Dashboard({ familyOn = false, families, onFamilyCreated }: {
  familyOn?: boolean
  families?: FamilyLedger[]
  onFamilyCreated?: () => void
}) {
  const navigate = useNavigate()
  const deleted = (useLocation().state as { dataDeleted?: boolean } | null)?.dataDeleted ?? false
  const entries = useApi<EntryPage>('/entries?size=1')
  const [demoLoaded, setDemoLoaded] = useState(false)
  if (entries.data?.totalElements === 0 && !demoLoaded) {
    return (
      <EmptyLedger deleted={deleted} familyOn={familyOn} onLoaded={(demo) => {
        setDemoLoaded(true)
        if (demo.familyLedgerId !== undefined) onFamilyCreated?.()
        navigate(`/?from=${demo.from}&to=${demo.to}`)
      }} />
    )
  }
  return <LedgerDashboard families={familyOn ? families ?? [] : null} />
}

/**
 * What the user has and owes on a day, and what came in and went out in a period, from the report endpoints alone.
 * The period, the day, the currency and the scope are kept in the URL only (D-306): a link to `/` opens the month of
 * the latest entry (D-521) in the base currency (D-297), with every income and expense. In the base currency the whole page is one: one headline
 * row, one chart, one table, one row per account and counterparty; in each currency, a block per currency where it
 * must. The scope (D-302) narrows only income and expenses; balances, the headline row and open loans are the whole
 * ledger's.
 *
 * @param families the family budgets of the scopes; null with the family budget switched off
 */
function LedgerDashboard({ families }: { families: FamilyLedger[] | null }) {
  const [params] = useSearchParams()
  const today = useToday()
  // "Month of the latest entry" (D-521) needs that entry's day first: no report request for another period before.
  const latest = useLatestEntryDay(needsLatestEntry(params), today)
  if (latest.day === undefined) {
    return latest.error ? <Errors messages={[latest.error]} /> : <Loading what="the dashboard" />
  }
  return <PeriodDashboard families={families} latest={latest.day} />
}

/** The dashboard once its period is known; `latest` is the day of the latest entry on or before today, or null. */
function PeriodDashboard({ families, latest }: { families: FamilyLedger[] | null; latest: string | null }) {
  const [params, setParams] = useSearchParams()
  const today = useToday()
  const period = periodFromQuery(params, dateOfIso(today), latest)
  const inBase = inBaseFromQuery(params)
  const chosen = scopeFromQuery(params, families && families.map((f) => f.id))
  const backwards = period.from > period.to
  // What income and expenses cover and what they are compared with (D-395, D-396): asked all at once, with the page's
  // scope (D-401).
  const compared = comparisons(period, today)
  const flowPath = (range: DayRange | undefined) =>
    range === undefined ? null : `/reports/cash-flow?from=${range.from}&to=${range.to}${scopeParams(chosen)}`

  // Balances, net worth, loans and the family settlement as of the period's To, or today while it runs (D-430, D-443).
  const day = balancesDay(period, today)
  const asOf = `asOf=${day}`
  const each = (path: string) => (inBase ? null : path)
  const base = (path: string | null) => (inBase && path !== null ? `${path}&currency=BASE` : null)
  const settings = useApi<Settings>('/settings')
  const netWorth = useApi<NetWorth[]>(each(`/reports/net-worth?${asOf}`))
  const baseNetWorth = useApi<ConvertedNetWorth>(base(`/reports/net-worth?${asOf}`))
  // In their own currencies either way: open loans name accounts and show a family debt's own currencies from them.
  const balances = useApi<AccountBalance[]>(`/reports/balances?${asOf}`)
  const baseBalances = useApi<ConvertedBalance[]>(base(`/reports/balances?${asOf}`))
  const shared = useApi<SharedSettlement[]>(`/reports/shared-settlement?${asOf}`)
  const loanPath = (code: string) => `/reports/counterparty-balances?accountCode=${code}&${asOf}`
  const lent = useApi<CounterpartyBalance[]>(each(loanPath('LOANS_ASSET')))
  const borrowed = useApi<CounterpartyBalance[]>(each(loanPath('CREDITOR_DEBT')))
  const baseLent = useApi<ConvertedCounterpartyBalance[]>(base(loanPath('LOANS_ASSET')))
  const baseBorrowed = useApi<ConvertedCounterpartyBalance[]>(base(loanPath('CREDITOR_DEBT')))
  const cashFlowPath = flowPath(compared?.section)
  const cashFlow = useApi<CashFlowRow[]>(cashFlowPath && each(cashFlowPath))
  const baseCashFlow = useApi<ConvertedCashFlow>(base(cashFlowPath))
  const [first, second] = compared?.ranges ?? []
  const firstPath = flowPath(first)
  const secondPath = flowPath(second)
  const firstFlow = useApi<CashFlowRow[]>(firstPath && each(firstPath))
  const firstBase = useApi<ConvertedCashFlow>(base(firstPath))
  const secondFlow = useApi<CashFlowRow[]>(secondPath && each(secondPath))
  const secondBase = useApi<ConvertedCashFlow>(base(secondPath))
  const months = compared ? monthsBetween(compared.section.from, compared.section.to) : []
  // "Result for the period" in the Balances card (D-367, D-430, D-443): income and expenses under All, from From to the
  // balances' day; the page's own load when that is the same request.
  const range = resultRange(period, today)
  const resultPath = range && `/reports/cash-flow?from=${range.from}&to=${range.to}`
  const sameAsPage = resultPath !== null && resultPath === cashFlowPath
  const ownResult = useApi<CashFlowRow[]>(sameAsPage ? null : resultPath && each(resultPath))
  const ownBaseResult = useApi<ConvertedCashFlow>(sameAsPage ? null : base(resultPath))
  const resultMonths = range === null ? [] : monthsBetween(range.from, range.to)
  const noResult = { data: null, loading: false }
  const result: Loaded<Table[] | null> = range === null ? noResult : (() => {
    const load = sameAsPage ? cashFlow : ownResult
    return { ...load, data: load.data && cashFlowTables(load.data, resultMonths) }
  })()
  const baseResult: Loaded<Table | null> = range === null ? noResult : (() => {
    const load = sameAsPage ? baseCashFlow : ownBaseResult
    return { ...load, data: load.data && convertedCashFlowTable(load.data, resultMonths) }
  })()
  // Each range's own monthly tables: the period's, then each comparison's.
  const monthly = (range: DayRange | undefined, rows: Loaded<CashFlowRow[]>, converted: Loaded<ConvertedCashFlow>):
    Loaded<Table[]> => {
    const own = range ? monthsBetween(range.from, range.to) : []
    return inBase ? { ...converted, data: converted.data && nonEmpty([convertedCashFlowTable(converted.data, own)]) }
      : { ...rows, data: rows.data && cashFlowTables(rows.data, own) }
  }
  const tables: Loaded<Table[]> = backwards ? { error: 'The period ends before it starts.', loading: false }
    : monthly(compared?.section, cashFlow, baseCashFlow)
  const comparedTables = [monthly(first, firstFlow, firstBase), monthly(second, secondFlow, secondBase)]
    .slice(0, compared?.ranges.length ?? 0)
  const missing = inBase ? mergeMissing([
    baseNetWorth.data?.missingRates ?? [],
    ...(baseBalances.data ?? []).map((b) => b.missingRates),
    ...[...(baseLent.data ?? []), ...(baseBorrowed.data ?? [])].map((b) => b.missingRates),
    // The comparisons' figures too (D-398).
    ...[baseCashFlow, firstBase, secondBase].flatMap((load) => [
      ...(load.data?.rows ?? []).map((r) => r.missingRates),
      ...(load.data?.exchangeResults ?? []).map((r) => r.missingRates),
    ]),
  ]) : []
  const baseCurrency = baseNetWorth.data?.currency ?? settings.data?.baseCurrency

  const update = (change: (next: URLSearchParams) => void) => {
    const next = new URLSearchParams(params)
    change(next)
    setParams(next)
  }
  // A choice of the Period list (D-432); an old link's `asOf` goes with it (D-430).
  const choose = (choice: PeriodChoice) => update((next) => {
    next.delete('asOf')
    next.delete('from')
    next.delete('to')
    if (choice === DEFAULT_PRESET) next.delete('period')
    else next.set('period', choice)
  })
  const setDay = (key: 'from' | 'to', day: string) => day && update((next) => {
    next.delete('period')
    next.delete('asOf')
    next.set('from', period.from)
    next.set('to', period.to)
    next.set(key, day)
  })
  const setInBase = (on: boolean) => update((next) => (on ? next.delete('currency') : next.set('currency', 'each')))
  const setShow = (show: string) => setParams(withShow(params, show))
  const setRecords = (records: Records) => setParams(withRecords(params, records))
  const setCompare = (role: string) => update((next) => (role === 'previous' ? next.set('compare', 'previous') : next.delete('compare')))

  const loans: Loaded<LoanGroups> = inBase
    ? { ...both(both(baseLent, baseBorrowed), both(balances, both(shared, baseBalances))),
      data: baseLent.data && baseBorrowed.data && balances.data && shared.data && baseBalances.data && {
        lent: baseLent.data, borrowed: baseBorrowed.data, accounts: balances.data,
        family: familyDebts(balances.data, baseBalances.data, shared.data[0]?.accountId ?? null),
      } }
    : { ...both(both(lent, borrowed), both(balances, shared)),
      data: lent.data && borrowed.data && balances.data && shared.data && {
        lent: lent.data, borrowed: borrowed.data, accounts: balances.data,
        family: familyDebts(balances.data, null, shared.data[0]?.accountId ?? null),
      } }
  // D-431: the Balances card names its day, as "Income and expenses" names its period.
  const balancesTitle = <>Balances <span className="muted period">on {formatDate(day)}</span></>
  const scopeFamilies = chosen.family === null ? families ?? [] : (families ?? []).filter((f) => f.id === chosen.family)

  return (
    <>
      <div className="page-title">
        <h2>Dashboard</h2>
      </div>
      <div className="controls">
        <div className="period-days">
          <PeriodList choice={period.choice} onChoose={choose} />
          <label>From <DateInput value={period.from} onChange={(day) => setDay('from', day)} /></label>
          <label>To <DateInput value={period.to} onChange={(day) => setDay('to', day)} /></label>
        </div>
        <ToggleGroup label="Amounts" value={inBase ? 'base' : 'each'} onChange={(v) => setInBase(v === 'base')}
          options={[
            { value: 'base', label: baseCurrency ? `${baseCurrency} (base)` : 'Base currency' },
            { value: 'each', label: 'Each currency' },
          ]} />
      </div>
      {missing.length > 0 && <MissingRates missing={missing} currency={baseCurrency} />}

      {inBase
        ? <Block state={baseNetWorth} label="What you have and owe">{(worth) => <BaseHeadline worth={worth} />}</Block>
        : <Block state={netWorth} label="What you have and owe">{(rows) => <Headlines rows={rows} />}</Block>}

      <section className="card flows" aria-labelledby="flows-title">
        <h3 id="flows-title">
          Income and expenses{' '}
          <span className="muted period">
            {compared === null ? ''
              : `${formatDate(compared.section.from)} – ${formatDate(compared.section.to)}${compared.running ? ', to date' : ''}`}
          </span>
        </h3>
        {families !== null && families.length > 0 && (
          <div className="scope">
            <ToggleGroup label="Show" value={showOf(chosen)} onChange={setShow} options={showOptions(families)} />
            {isFamilyScope(chosen.scope) && (
              <ToggleGroup label="Records" value={recordsOf(chosen.scope)} onChange={setRecords}
                options={RECORDS.map((r) => ({ value: r, label: RECORDS_LABELS[r] }))} />
            )}
            {isFamilyScope(chosen.scope) && (
              <p className="small whole-family">
                {scopeFamilies.map((f, i) => (
                  <span key={f.id}>
                    {i > 0 && ' · '}
                    <Link to={`/family/${f.id}/report?from=${period.from}&to=${period.to}`}>
                      Whole family budget{families.length > 1 ? `: ${f.name}` : ''} →
                    </Link>
                  </span>
                ))}
                <span className="muted"> Your own shares are here; the budget’s report has every member’s.</span>
              </p>
            )}
          </div>
        )}
        {tables.error || compared === null ? <Errors messages={[tables.error ?? '']} />
          : (
            <Flows compared={compared} period={tables} comparisons={comparedTables} inBase={inBase} params={params}
              onCompare={setCompare} />
          )}
      </section>

      <div className="dashboard">
        {inBase
          ? <Card title={balancesTitle} state={both(both(baseBalances, baseNetWorth), both(balances, baseResult))}>
            {([[rows, worth], [own, flow]]) => <BaseBalancesByType balances={rows} worth={worth} own={own} result={flow} />}
          </Card>
          : <Card title={balancesTitle} state={both(balances, result)}>
            {([rows, flow]) => <BalancesByType balances={rows} result={flow} />}
          </Card>}
        <Card title="Open loans" state={loans}>
          {(groups) => <OpenLoans groups={groups} inBase={inBase} baseCurrency={baseCurrency ?? ''} />}
        </Card>
      </div>
    </>
  )
}

interface Loaded<T> { data?: T; error?: string; loading: boolean }

/** Two loads as one: there once both are, failed if either failed. */
function both<A, B>(a: Loaded<A>, b: Loaded<B>): Loaded<[A, B]> {
  return {
    data: a.data !== undefined && b.data !== undefined ? [a.data, b.data] : undefined,
    error: a.error ?? b.error,
    loading: a.loading || b.loading,
  }
}

/**
 * The period as a native list (D-432): "Month of the latest entry" (D-521), the presets in D-296's order, then "Custom", which can't be chosen and is shown
 * chosen while From and To are a custom range, as on the Entries page. A choice does what its button did.
 */
function PeriodList({ choice, onChoose }: { choice: PeriodChoice; onChoose: (choice: PeriodChoice) => void }) {
  return (
    <label>
      Period{' '}
      <select value={choice} onChange={(e) => {
        const value = e.target.value as PeriodChoice
        if (value !== 'custom') onChoose(value)
      }}>
        {PRESETS.map((p) => <option key={p} value={p}>{PERIOD_LABELS[p]}</option>)}
        <option value="custom" disabled>{PERIOD_LABELS.custom}</option>
      </select>
    </label>
  )
}

/** A widget. While it reloads, the previous figures stay, dimmed, so nothing jumps. */
function Card<T>({ title, state, className = '', children }: {
  title: ReactNode
  state: Loaded<T>
  className?: string
  children: (data: T) => ReactNode
}) {
  return (
    <section className={`card ${className}`}>
      <h3>{title}</h3>
      <Body state={state}>{children}</Body>
    </section>
  )
}

/** A part of the page without a card of its own, such as the headline row. */
function Block<T>({ state, label, children }: { state: Loaded<T>; label: string; children: (data: T) => ReactNode }) {
  return <section className="headline" aria-label={label}><Body state={state}>{children}</Body></section>
}

function Body<T>({ state, children }: { state: Loaded<T>; children: (data: T) => ReactNode }) {
  return state.error ? <Errors messages={[state.error]} />
    : state.data === undefined ? <Loading />
      : <div className={state.loading ? 'stale' : undefined}>{children(state.data)}</div>
}

const NoCashFlow = () => <p className="empty">No income or expenses in this period or the ones it is compared with.</p>

/**
 * Income and expenses compared (D-393 to D-400): the totals with their changes, the chart of each currency and the
 * comparison table of each. Shown once the period and every comparison have answered or failed; a comparison that
 * failed shows its error and leaves the period's figures and the other comparison (D-401).
 */
function Flows({ compared, period, comparisons: loads, inBase, params, onCompare }: {
  compared: Comparisons
  period: Loaded<Table[]>
  comparisons: Loaded<Table[]>[]
  inBase: boolean
  params: URLSearchParams
  onCompare: (role: string) => void
}) {
  if (period.data === undefined || loads.some((l) => l.data === undefined && !l.error)) return <Loading />
  const all = compareColumns(compared)
  const errors = loads.flatMap((l, i) => (l.error ? [`${all[i + 1].role} (${all[i + 1].label}): ${l.error}`] : []))
  const kept = compared.ranges.map((range, i) => ({ range, load: loads[i] })).filter(({ load }) => !load.error)
  const columns = [all[0], ...all.slice(1).filter((_, i) => !loads[i].error)]
  const tables = compareTables(columns, [period.data, ...kept.map(({ load }) => load.data!)], inBase)
  const stale = period.loading || loads.some((l) => l.loading)
  const shown: Comparisons = { ...compared, ranges: kept.map(({ range }) => range) }
  const byMonth = chartByMonth(shown)
  const drawn = byMonth ? drawnComparison(shown, params) : null
  const choices = byMonth ? drawable(shown) : []
  const chart = (table: CompareTable): ChartData => {
    if (!byMonth) return { form: 'ranges', bars: rangeBars(table) }
    const own = period.data!.find((t) => t.currency === table.currency) ?? null
    const i = drawn === null ? -1 : kept.findIndex(({ range }) => range.role === drawn.role)
    const before = i < 0 ? null : kept[i].load.data!.find((t) => t.currency === table.currency) ?? null
    return { form: 'months', months: monthPairs(own, compared.section, before, drawn), role: drawn?.role ?? null }
  }
  return (
    <div className={stale ? 'stale' : undefined}>
      {errors.length > 0 && <Errors messages={errors} />}
      {tables.length === 0 ? <NoCashFlow /> : (
        <>
          <PeriodTotals tables={tables} />
          {choices.length > 1 && (
            <ToggleGroup label="Compare with" value={drawn?.role === 'previous' ? 'previous' : 'year'} onChange={onCompare}
              options={[{ value: 'year', label: 'A year earlier' }, { value: 'previous', label: 'Previous period' }]} />
          )}
          {tables.map((table) => (
            <figure key={table.currency} className="chart" aria-label={`Income and expenses in ${table.currency}`}>
              {tables.length > 1 && <figcaption>{table.currency}</figcaption>}
              <ErrorBoundary fallback={<p className="error">The chart couldn’t load. Reload the page to try again.</p>}>
                <Suspense fallback={<div className="chart-placeholder"><Loading what="chart" /></div>}>
                  <IncomeExpenseChart data={chart(table)} currency={table.currency} rounded={table.converted} />
                </Suspense>
              </ErrorBoundary>
            </figure>
          ))}
          <h4>Cash flow by category</h4>
          {tables.map((table) => <CashFlowTable key={table.currency} table={table} />)}
        </>
      )}
    </div>
  )
}

/** A converted table with nothing in it is no table. */
const nonEmpty = (tables: Table[]) => tables.filter((t) =>
  t.income.lines.length + t.expense.lines.length + t.exchange.length > 0)

/** Which figures can't be converted, and where to fix that. */
function MissingRates({ missing, currency }: { missing: { currency: string; from: string; to: string }[]; currency?: string }) {
  return (
    <p className="notice" role="status">
      Some amounts can’t be shown in {currency ?? 'the base currency'}: there is no exchange rate for{' '}
      {missing.map(missingDays).join('; ')}. <Link to="/rates">Add rates</Link>
    </p>
  )
}

/**
 * An amount converted to the base currency, with the currency's usual decimals, or "rate missing" for one that can't
 * be converted. `signed` colours it as a gain or a loss.
 */
function Figure({ amount, currency, signed = false, rounded = true }: {
  amount: Cell
  currency: string
  signed?: boolean
  rounded?: boolean
}) {
  if (amount === MISSING) return <span className="missing" title="No exchange rate for a currency on some day">rate missing</span>
  if (amount === null) return <span className="muted">—</span>
  const className = signed ? (signOf(amount) < 0 ? 'expense' : signOf(amount) > 0 ? 'income' : '') : undefined
  return <span className={className}>{formatMoney(amount, currency, { signed, rounded })}</span>
}

const cell = (amount: string | null): Cell => amount ?? MISSING

/**
 * The headline row (D-301): what the user has, owes and is worth on the day, in that order, each as large as the
 * others, "You have" never muted. What the user owes below zero, as when others owe more than the user does, reads
 * "Owed to you" with the amount above zero (D-350).
 */
function HeadlineRow({ currency, assets, liabilities, netWorth, rounded, label }: {
  currency: string
  assets: Cell
  liabilities: Cell
  netWorth: Cell
  rounded: boolean
  label?: string
}) {
  const owedToYou = typeof liabilities === 'string' && signOf(liabilities) < 0
  return (
    <div className="headline-row">
      {label && <div className="headline-currency">{label}</div>}
      <dl className="figures">
        <div className="figure"><dt>You have</dt><dd><Figure amount={assets} currency={currency} rounded={rounded} /></dd></div>
        <div className="figure">
          <dt>{owedToYou ? 'Owed to you' : 'You owe'}</dt>
          <dd><Figure amount={owedToYou ? abs(liabilities as string) : liabilities} currency={currency} rounded={rounded} /></dd>
        </div>
        <div className="figure"><dt>Net worth</dt><dd><Figure amount={netWorth} currency={currency} rounded={rounded} /></dd></div>
      </dl>
    </div>
  )
}

/** The headline row in the base currency, and under it, small, what exchange rates did and the rates used. */
function BaseHeadline({ worth }: { worth: ConvertedNetWorth }) {
  const c = worth.currency
  return (
    <>
      <HeadlineRow currency={c} assets={cell(worth.assets)} liabilities={cell(worth.liabilities)}
        netWorth={cell(worth.netWorth)} rounded />
      <p className="muted small fx">
        <span title="What currency exchanges gained or lost against the rates of their days">
          Realized on exchanges <Figure amount={cell(worth.realizedExchangeResult)} currency={c} signed />
        </span>
        {' · '}
        <span title="What rate changes did to the money you still hold or owe in other currencies">
          Revaluation of balances <Figure amount={cell(worth.unrealizedRevaluation)} currency={c} signed />
        </span>
      </p>
      {worth.rates.length > 0 && <UsedRates rates={worth.rates} />}
    </>
  )
}

/** One headline row per currency. */
function Headlines({ rows }: { rows: NetWorth[] }) {
  if (rows.length === 0) return <p className="empty">No balances yet.</p>
  return (
    <>
      {rows.map((n) => (
        <HeadlineRow key={n.currency} label={n.currency} currency={n.currency} assets={n.assets}
          liabilities={n.liabilities} netWorth={n.netWorth} rounded={false} />
      ))}
    </>
  )
}

/**
 * The period's income, expenses and net (D-305), from each table's own subtotals, each with a small line of its
 * changes (D-400): "+12.3% vs Oct 2026 · −4.0% vs Oct 2025", coloured by the effect on the net. A comparison with
 * nothing to compare, or a figure without a rate, is left out of the line.
 */
function PeriodTotals({ tables }: { tables: CompareTable[] }) {
  return (
    <div className="period-totals">
      {tables.map((table) => {
        const shown = { currency: table.currency, rounded: table.converted }
        const figure = (title: string, line: CompareLine, className: string, signed = false) => {
          const changes = totalChanges(table, line).filter((c) => typeof c.change === 'object')
          return (
            <div className="figure">
              <dt>{title}</dt>
              <dd className={className}><Figure amount={line.amounts[0]} {...shown} signed={signed} /></dd>
              {changes.length > 0 && (
                <dd className="changes">
                  {changes.map((c, i) => (
                    <span key={c.label}>
                      {i > 0 && ' · '}
                      <span className={effectOf(c.change, c.good) ?? undefined}>{changeText(c.change)}</span> vs {c.label}
                    </span>
                  ))}
                </dd>
              )}
            </div>
          )
        }
        return (
          <dl key={table.currency} className="figures small-figures" aria-label={`Totals in ${table.currency}`}>
            {tables.length > 1 && <div className="headline-currency">{table.currency}</div>}
            {figure('Income', table.income.subtotal, 'income')}
            {figure('Expenses', table.expense.subtotal, 'expense')}
            {figure('Net', table.net, '', true)}
          </dl>
        )
      })}
    </div>
  )
}

/**
 * The rate of each currency on the day (D-49, D-90): an ECB one at most 7 days old, or the user's own, marked "manual",
 * and "rate stale" when that is more than 31 days old.
 */
function UsedRates({ rates }: { rates: Rate[] }) {
  return (
    <p className="muted small">
      At the rates{' '}
      {rates.map((r, i) => (
        <span key={r.currency} className={r.stale ? 'stale-rate' : undefined}>
          {i > 0 && '; '}{usedRateText(r)}
        </span>
      ))}
      . <Link to="/rates">Rates</Link>
    </p>
  )
}

/** The labels of D-367's two lines, the same for every user (D-368). */
const RESERVE_LABEL = 'Accumulated reserve'
const RESULT_LABEL = 'Result for the period'
const RESERVE_HINT = 'Your equity accounts’ balance before the period, with what you moved to or from them in it'
const RESULT_HINT = 'Income less expenses from the period’s start to the balances’ day: the Net of Income and expenses'

/**
 * The accounts by type, in the base currency, with a total per type. The totals are net worth's figures (D-348): each
 * the unrounded sum, rounded once (D-49), so Assets' total is "You have", Liabilities' "You owe" and Equity's net worth;
 * the rows, each rounded on its own, may then differ from their total by a few minor units, which a note says. An
 * archived account is listed while one of its own amounts doesn't round to 0 (D-355, D-364). Equity shows, in place of
 * the accounts other than OPENING_BALANCE and FX_EXCHANGE, "Accumulated reserve" and "Result for the period" (D-367,
 * D-369): the result at each posting's own day's rate, the reserve the rest at the rate on the balances' day, so the lines
 * add up to net worth and the reserve includes the revaluation, as a note says.
 *
 * @param own the balances in their own currencies, for D-364's test
 * @param result the cash flow of the result's days, null for none
 */
function BaseBalancesByType({ balances, worth, own, result }: {
  balances: ConvertedBalance[]
  worth: ConvertedNetWorth
  own: AccountBalance[]
  result: Table | null
}) {
  if (balances.length === 0) return <p className="empty">No accounts yet.</p>
  const totals: Record<string, Cell> = {
    ASSET: cell(worth.assets), LIABILITY: cell(worth.liabilities), EQUITY: cell(worth.netWorth),
  }
  const currency = worth.currency
  const split = baseEquitySplit(balances, cell(worth.netWorth), result)
  const listed = (b: ConvertedBalance) => listedInBalances(own.filter((o) => o.accountId === b.accountId), b.archived === true)
  return (
    <>
      {ACCOUNT_TYPES.map((type) => {
        const rows = balances.filter((b) => b.accountType === type && !isReserveEquity(b) && listed(b))
        if (rows.length === 0 && type !== 'EQUITY') return null
        return (
          <table key={type} className="by-type">
            <thead><tr><th scope="colgroup" colSpan={2}>{TYPE_LABELS[type]}</th></tr></thead>
            <tbody>
              {type === 'EQUITY' && (
                <>
                  <tr className="equity-line">
                    <td title={RESERVE_HINT}>{RESERVE_LABEL}</td>
                    <td className="amount nowrap"><Figure amount={split.reserve} currency={currency} /></td>
                  </tr>
                  <tr className="equity-line">
                    <td title={RESULT_HINT}>{RESULT_LABEL}</td>
                    <td className="amount nowrap"><Figure amount={split.result} currency={currency} /></td>
                  </tr>
                </>
              )}
              {rows.map((b) => (
                <tr key={b.accountId}>
                  <td><AccountName id={b.accountId} name={b.accountName} archived={b.archived === true} /></td>
                  <td className="amount nowrap"><Figure amount={cell(b.balance)} currency={currency} /></td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">Total</th>
                <td className="amount nowrap"><Figure amount={totals[type]} currency={currency} /></td>
              </tr>
            </tfoot>
          </table>
        )
      })}
      <p className="muted small rounding-note">
        Each amount is rounded on its own and each total once, so a total may differ slightly from the sum of its rows.
        The accumulated reserve is at the rates on the day the balances are shown, so it includes the revaluation of
        what is in other currencies.
      </p>
    </>
  )
}

/** An account's name, linking to its entries, marked when it is archived (D-355). */
const AccountName = ({ id, name, archived }: { id: number; name: string; archived: boolean }) => (
  <>
    <Link to={`/entries?accountId=${id}`}>{name}</Link>
    {archived && <span className="badge" title="Archived, but it still holds money">archived</span>}
  </>
)

/**
 * The accounts by type, with totals per currency over every account, as net worth counts them; an archived one is
 * listed while one of its amounts doesn't round to 0 (D-355, D-364), with those amounts. Equity shows "Accumulated
 * reserve" and "Result for the period" per currency in place of the accounts other than OPENING_BALANCE and
 * FX_EXCHANGE (D-367).
 *
 * @param result the tables of the result's days, null for none
 */
function BalancesByType({ balances, result }: { balances: AccountBalance[]; result: Table[] | null }) {
  if (balances.length === 0) return <p className="empty">No accounts yet.</p>
  const splits = equitySplits(balances, result)
  return (
    <>
      {ACCOUNT_TYPES.map((type) => {
        const all = balances.filter((b) => b.accountType === type)
        const rows = all.filter((b) => !isReserveEquity(b))
        const accounts = [...new Map(rows.map((b) => [b.accountId, b]))].filter(([id, account]) =>
          listedInBalances(rows.filter((b) => b.accountId === id), account.archived === true))
        const totals = [...new Set(all.map((b) => b.currency))].sort().map((currency) => ({
          currency, amount: sum(all.filter((b) => b.currency === currency).map((b) => b.balance)),
        }))
        const equity = type === 'EQUITY' && splits.length > 0
        if (accounts.length === 0 && !equity) return null
        return (
          <table key={type} className="by-type">
            <thead><tr><th scope="colgroup" colSpan={2}>{TYPE_LABELS[type]}</th></tr></thead>
            <tbody>
              {equity && (
                <>
                  <tr className="equity-line">
                    <td title={RESERVE_HINT}>{RESERVE_LABEL}</td>
                    <td className="amount nowrap">
                      <Amounts amounts={splits.map((s) => ({ currency: s.currency, amount: s.reserve }))} />
                    </td>
                  </tr>
                  <tr className="equity-line">
                    <td title={RESULT_HINT}>{RESULT_LABEL}</td>
                    <td className="amount nowrap">
                      <Amounts amounts={splits.map((s) => ({ currency: s.currency, amount: s.result }))} />
                    </td>
                  </tr>
                </>
              )}
              {accounts.map(([id, account]) => {
                const amounts = rows.filter((b) => b.accountId === id)
                  .filter((b) => !account.archived || !belowMinorUnit(b.balance, b.currency))
                return (
                  <tr key={id}>
                    <td><AccountName id={id} name={account.accountName} archived={account.archived === true} /></td>
                    <td className="amount nowrap">
                      <Amounts amounts={amounts.map((b) => ({ currency: b.currency, amount: b.balance }))} />
                    </td>
                  </tr>
                )
              })}
            </tbody>
            <tfoot><tr><th scope="row">Total</th><td className="amount nowrap"><Amounts amounts={totals} /></td></tr></tfoot>
          </table>
        )
      })}
    </>
  )
}

/** What open loans show: money lent and borrowed per counterparty, and the family budgets' debts (D-299). */
interface LoanGroups {
  lent: (CounterpartyBalance | ConvertedCounterpartyBalance)[]
  borrowed: (CounterpartyBalance | ConvertedCounterpartyBalance)[]
  /** In their own currencies, for the loan accounts' names and links. */
  accounts: AccountBalance[]
  family: FamilyDebt[]
}

/** A line of open loans: who, what they owe the user or the user owes them, and, small, the amounts behind it. */
interface LoanLine { key: string; who: ReactNode; owesYou: ReactNode; youOwe: ReactNode; detail?: ReactNode }

/** The amounts in their own currencies, shown small under a converted figure. */
const OwnAmounts = ({ amounts }: { amounts: { currency: string; balance: string }[] }) => (
  <span className="muted small own-amounts">
    {amounts.filter((a) => !belowMinorUnit(a.balance, a.currency)).map((a) => formatMoney(a.balance, a.currency)).join(' · ')}
  </span>
)

/**
 * What is open with each counterparty on the accounts kept per counterparty (rule 8), and with each family budget
 * (D-299): in each currency a line per currency, in the base currency one line per counterparty or budget, its own
 * currencies small below it when there is more than the base currency (D-298). A line whose amounts all round to 0
 * in their own currency's minor unit is left out, and a group left without a line (D-349).
 */
function OpenLoans({ groups, inBase, baseCurrency }: { groups: LoanGroups; inBase: boolean; baseCurrency: string }) {
  const sections = LOAN_ACCOUNTS.map((account) => {
    const rows = account.code === 'LOANS_ASSET' ? groups.lent : groups.borrowed
    const balance = groups.accounts.find((a) => a.accountCode === account.code)
    const name = balance?.accountName ?? account.title
    const link = (counterpartyId: number, text: string) => balance === undefined ? text
      : <Link to={`/entries?accountId=${balance.accountId}&counterpartyId=${counterpartyId}`}>{text}</Link>
    const open = rows.filter((b) => 'amounts' in b
      ? b.amounts.some((a) => !belowMinorUnit(a.balance, a.currency))
      : !belowMinorUnit(b.balance, b.currency))
    const lines: LoanLine[] = open.map((b) => {
      if ('amounts' in b) {
        // The side of a figure that can't be converted is the side of its amounts.
        const sides = loanSides({ balance: b.balance ?? b.amounts[0]?.balance ?? '0' }, account.positive)
        const shown = (amount: string | null) => amount === null ? null
          : b.balance === null ? <Figure amount={MISSING} currency={b.currency} />
            : formatMoney(amount, b.currency, { rounded: true })
        return {
          key: `${b.counterpartyId}`, who: link(b.counterpartyId, b.counterpartyName),
          owesYou: shown(sides.owesYou), youOwe: shown(sides.youOwe),
          detail: showsOwnCurrencies(b.amounts.filter((a) => !belowMinorUnit(a.balance, a.currency)), b.currency)
            ? <OwnAmounts amounts={b.amounts} /> : undefined,
        }
      }
      const { owesYou, youOwe } = loanSides(b, account.positive)
      return {
        key: `${b.counterpartyId}:${b.currency}`, who: link(b.counterpartyId, b.counterpartyName),
        owesYou: owesYou && formatMoney(owesYou, b.currency), youOwe: youOwe && formatMoney(youOwe, b.currency),
      }
    })
    return { key: account.code, title: name, lines }
  })
  const family: LoanLine[] = groups.family.flatMap((debt) => {
    const who = <Link to={debt.to}>{debt.name}</Link>
    // Settled when every amount in its own currency rounds to 0 (D-349); in base mode, then the converted figure is shown.
    const settled = debt.own.every((o) => belowMinorUnit(o.balance, o.currency))
    const open = settled ? [] : inBase ? debt.amounts
      : debt.amounts.filter((a) => a.balance === null || !belowMinorUnit(a.balance, a.currency))
    if (open.length === 0) {
      return [{ key: debt.key, who, owesYou: null, youOwe: null, detail: <span className="muted small">Settled</span> }]
    }
    return open.map((a) => {
      const sides = debtSides(debt.accountType, a.balance ?? debt.own[0]?.balance ?? '0')
      const shown = (amount: string | null) => amount === null ? null
        : a.balance === null ? <Figure amount={MISSING} currency={a.currency} />
          : formatMoney(amount, a.currency, { rounded: inBase })
      return {
        key: `${debt.key}:${a.currency}`, who, owesYou: shown(sides.owesYou), youOwe: shown(sides.youOwe),
        detail: inBase && showsOwnCurrencies(debt.own.filter((o) => !belowMinorUnit(o.balance, o.currency)), baseCurrency)
          ? <OwnAmounts amounts={debt.own} /> : undefined,
      }
    })
  })
  const all = [...sections, { key: 'family', title: 'Family budgets', lines: family }].filter((s) => s.lines.length > 0)
  if (all.length === 0) return <p className="empty">No open loans.</p>
  return (
    <div className="scroll-x">
      <table className="loans">
        <thead>
          <tr><th scope="col">Who</th><th scope="col" className="amount">Owes you</th><th scope="col" className="amount">You owe</th></tr>
        </thead>
        {all.map((section) => (
          <tbody key={section.key}>
            <tr className="section"><th scope="rowgroup" colSpan={3}>{section.title}</th></tr>
            {section.lines.map((line) => (
              <tr key={line.key}>
                <td>{line.who}{line.detail && <div>{line.detail}</div>}</td>
                <td className="amount nowrap">{line.owesYou}</td>
                <td className="amount nowrap">{line.youOwe}</td>
              </tr>
            ))}
          </tbody>
        ))}
      </table>
    </div>
  )
}
