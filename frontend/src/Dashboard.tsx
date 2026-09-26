import { lazy, Suspense, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router'
import {
  formatDate, sentence, useApi, type AccountBalance, type CashFlowRow, type ConvertedBalance, type ConvertedCashFlow,
  type ConvertedNetWorth, type CounterpartyBalance, type NetWorth, type Rate, type SharedSettlement,
} from './api'
import CashFlowTable from './CashFlowTable'
import { Amounts, Errors, Loading } from './components'
import {
  cashFlowTables, convertedCashFlowTable, daysBetween, DEFAULT_PRESET, inBaseFromQuery, LOAN_ACCOUNTS, loanSides,
  mergeMissing, MISSING, missingDays, monthsBetween, PERIOD_LABELS, periodFromQuery, PRESETS, settlementSentence,
  STALE_AFTER_DAYS, total, type CashFlowTable as Table, type Cell, type PeriodChoice,
} from './dashboard'
import { ACCOUNT_TYPES, TYPE_LABELS } from './ledger'
import { formatMoney, formatRate, signOf, sum } from './money'

// Recharts is most of the app's code; loading it with the chart keeps it off every other page.
const IncomeExpenseChart = lazy(() => import('./IncomeExpenseChart'))

/**
 * The home page: what the user has and owes on a day, and what came in and went out in a period, from the report
 * endpoints alone. The period, the day and the currency switch are kept in the URL. Each currency is shown on its
 * own, or everything in the base currency (`currency=base`) with what exchange rates did. The shared budget and open
 * loans stay in their own currencies.
 */
export default function Dashboard() {
  const [params, setParams] = useSearchParams()
  const period = periodFromQuery(params, new Date())
  const inBase = inBaseFromQuery(params)
  const backwards = period.from > period.to

  const asOf = `asOf=${period.asOf}`
  const each = (path: string) => (inBase ? null : path)
  const base = (path: string | null) => (inBase && path !== null ? `${path}&currency=BASE` : null)
  const netWorth = useApi<NetWorth[]>(each(`/reports/net-worth?${asOf}`))
  const baseNetWorth = useApi<ConvertedNetWorth>(base(`/reports/net-worth?${asOf}`))
  // In their own currencies either way: the shared budget and the open loans name their accounts from them.
  const balances = useApi<AccountBalance[]>(`/reports/balances?${asOf}`)
  const baseBalances = useApi<ConvertedBalance[]>(base(`/reports/balances?${asOf}`))
  const settlement = useApi<SharedSettlement[]>(`/reports/shared-settlement?${asOf}`)
  const lent = useApi<CounterpartyBalance[]>(`/reports/counterparty-balances?accountCode=LOANS_ASSET&${asOf}`)
  const borrowed = useApi<CounterpartyBalance[]>(`/reports/counterparty-balances?accountCode=CREDITOR_DEBT&${asOf}`)
  const cashFlowPath = backwards ? null : `/reports/cash-flow?from=${period.from}&to=${period.to}`
  const cashFlow = useApi<CashFlowRow[]>(cashFlowPath && each(cashFlowPath))
  const baseCashFlow = useApi<ConvertedCashFlow>(base(cashFlowPath))
  const months = monthsBetween(period.from, period.to)
  const tables: Loaded<Table[]> = backwards ? { error: 'The period ends before it starts.', loading: false }
    : inBase ? { ...baseCashFlow, data: baseCashFlow.data && nonEmpty([convertedCashFlowTable(baseCashFlow.data, months)]) }
      : { ...cashFlow, data: cashFlow.data && cashFlowTables(cashFlow.data, months) }
  const missing = inBase ? mergeMissing([
    baseNetWorth.data?.missingRates ?? [],
    ...(baseBalances.data ?? []).map((b) => b.missingRates),
    ...(baseCashFlow.data?.rows ?? []).map((r) => r.missingRates),
    ...(baseCashFlow.data?.exchangeResults ?? []).map((r) => r.missingRates),
  ]) : []

  const update = (change: (next: URLSearchParams) => void) => {
    const next = new URLSearchParams(params)
    change(next)
    setParams(next)
  }
  // A new period shows balances as of its end again.
  const choose = (choice: PeriodChoice) => update((next) => {
    next.delete('asOf')
    if (choice === 'custom') {
      next.delete('period')
      next.set('from', period.from)
      next.set('to', period.to)
    } else {
      next.delete('from')
      next.delete('to')
      if (choice === DEFAULT_PRESET) next.delete('period')
      else next.set('period', choice)
    }
  })
  const setDay = (key: 'from' | 'to', day: string) => day && update((next) => {
    next.delete('period')
    next.delete('asOf')
    next.set('from', period.from)
    next.set('to', period.to)
    next.set(key, day)
  })
  const setAsOf = (day: string) => update((next) => (day ? next.set('asOf', day) : next.delete('asOf')))
  const setInBase = (on: boolean) => update((next) => (on ? next.set('currency', 'base') : next.delete('currency')))

  const on = formatDate(period.asOf)
  return (
    <>
      <h2>Dashboard</h2>
      <fieldset className="filters">
        <legend>Period</legend>
        <label>
          Show{' '}
          <select value={period.choice} onChange={(e) => choose(e.target.value as PeriodChoice)}>
            {[...PRESETS, 'custom' as const].map((p) => <option key={p} value={p}>{PERIOD_LABELS[p]}</option>)}
          </select>
        </label>
        <label>From <input type="date" value={period.from} onChange={(e) => setDay('from', e.target.value)} /></label>
        <label>To <input type="date" value={period.to} onChange={(e) => setDay('to', e.target.value)} /></label>
        <label>
          Balances on <input type="date" value={period.asOf} onChange={(e) => setAsOf(e.target.value)} />
        </label>
        <label>
          Amounts{' '}
          <select value={inBase ? 'base' : 'each'} onChange={(e) => setInBase(e.target.value === 'base')}>
            <option value="each">In each currency</option>
            <option value="base">In the base currency</option>
          </select>
        </label>
      </fieldset>
      {missing.length > 0 && <MissingRates missing={missing} currency={baseNetWorth.data?.currency} />}

      <div className="dashboard">
        {inBase
          ? <Card title={`Net worth on ${on}`} state={baseNetWorth}>{(worth) => <BaseNetWorthTile worth={worth} asOf={period.asOf} />}</Card>
          : <Card title={`Net worth on ${on}`} state={netWorth}>{(rows) => <NetWorthTiles rows={rows} />}</Card>}
        <Card title="Shared budget" state={both(settlement, balances)}>
          {([open, accounts]) => <Settlement open={open} accounts={accounts} />}
        </Card>
        <Card title="Income and expenses by month" state={tables} className="wide">
          {(all) => all.length === 0 ? <NoCashFlow /> : all.map((table) => (
            <figure key={table.currency} className="chart" aria-label={`Income and expenses in ${table.currency}`}>
              {all.length > 1 && <figcaption>{table.currency}</figcaption>}
              <Suspense fallback={<div className="chart-placeholder"><Loading what="chart" /></div>}>
                <IncomeExpenseChart table={table} />
              </Suspense>
            </figure>
          ))}
        </Card>
        <Card title="Cash flow by category" state={tables} className="wide">
          {(all) => all.length === 0 ? <NoCashFlow /> : all.map((table) => <CashFlowTable key={table.currency} table={table} />)}
        </Card>
        {inBase
          ? <Card title={`Balances on ${on}`} state={baseBalances}>{(rows) => <BaseBalancesByType balances={rows} />}</Card>
          : <Card title={`Balances on ${on}`} state={balances}>{(rows) => <BalancesByType balances={rows} />}</Card>}
        <Card title={`Open loans on ${on}`} state={both(both(lent, borrowed), balances)}>
          {([loans, accounts]) => <OpenLoans loans={loans} accounts={accounts} />}
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
      {state.error ? <Errors messages={[state.error]} />
        : state.data === undefined ? <Loading />
          : <div className={state.loading ? 'stale' : undefined}>{children(state.data)}</div>}
    </section>
  )
}

const NoCashFlow = () => <p className="empty">No income or expenses in this period.</p>

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
function Figure({ amount, currency, signed = false }: { amount: Cell; currency: string; signed?: boolean }) {
  if (amount === MISSING) return <span className="missing" title="No exchange rate for a currency on some day">rate missing</span>
  if (amount === null) return <span className="muted">—</span>
  const className = signed ? (signOf(amount) < 0 ? 'expense' : signOf(amount) > 0 ? 'income' : '') : undefined
  return <span className={className}>{formatMoney(amount, currency, { signed, rounded: true })}</span>
}

const cell = (amount: string | null): Cell => amount ?? MISSING

/** Net worth in the base currency, with what exchanges realized and what rate changes did to balances. */
function BaseNetWorthTile({ worth, asOf }: { worth: ConvertedNetWorth; asOf: string }) {
  const c = worth.currency
  return (
    <div className="tiles">
      <div className="tile">
        <div className="label">{c}, the base currency</div>
        <div className="value"><Figure amount={cell(worth.netWorth)} currency={c} /></div>
        <div className="muted small">
          You have <Figure amount={cell(worth.assets)} currency={c} /> · you owe <Figure amount={cell(worth.liabilities)} currency={c} />
        </div>
        <table className="fx">
          <tbody>
            <tr>
              <th scope="row" title="What currency exchanges gained or lost against the rates of their days">Realized on exchanges</th>
              <td className="amount nowrap"><Figure amount={cell(worth.realizedExchangeResult)} currency={c} signed /></td>
            </tr>
            <tr>
              <th scope="row" title="What rate changes did to the money you still hold or owe in other currencies">Revaluation of balances</th>
              <td className="amount nowrap"><Figure amount={cell(worth.unrealizedRevaluation)} currency={c} signed /></td>
            </tr>
          </tbody>
        </table>
        {worth.rates.length > 0 && <UsedRates rates={worth.rates} asOf={asOf} />}
      </div>
    </div>
  )
}

/** The rate of each currency on the day, marked where it is old. */
function UsedRates({ rates, asOf }: { rates: Rate[]; asOf: string }) {
  return (
    <p className="muted small">
      At the rates{' '}
      {rates.map((r, i) => {
        const age = daysBetween(r.date, asOf)
        return (
          <span key={r.currency} className={age > STALE_AFTER_DAYS ? 'stale-rate' : undefined}>
            {i > 0 && '; '}1 EUR = {formatRate(r.perEuro)} {r.currency} of {formatDate(r.date)}
            {age > STALE_AFTER_DAYS && ` (${age} days old)`}
          </span>
        )
      })}
      . <Link to="/rates">Rates</Link>
    </p>
  )
}

/** The accounts that aren't archived by type, in the base currency, with a total per type. */
function BaseBalancesByType({ balances }: { balances: ConvertedBalance[] }) {
  if (balances.length === 0) return <p className="empty">No accounts yet.</p>
  return (
    <>
      {ACCOUNT_TYPES.map((type) => {
        const rows = balances.filter((b) => b.accountType === type)
        if (rows.length === 0) return null
        const currency = rows[0].currency
        return (
          <table key={type} className="by-type">
            <thead><tr><th scope="colgroup" colSpan={2}>{TYPE_LABELS[type]}</th></tr></thead>
            <tbody>
              {rows.map((b) => (
                <tr key={b.accountId}>
                  <td><Link to={`/entries?accountId=${b.accountId}`}>{b.accountName}</Link></td>
                  <td className="amount nowrap"><Figure amount={cell(b.balance)} currency={currency} /></td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">Total</th>
                <td className="amount nowrap"><Figure amount={total(rows.map((b) => cell(b.balance)))} currency={currency} /></td>
              </tr>
            </tfoot>
          </table>
        )
      })}
    </>
  )
}

function NetWorthTiles({ rows }: { rows: NetWorth[] }) {
  if (rows.length === 0) return <p className="empty">No balances yet.</p>
  return (
    <div className="tiles">
      {rows.map((n) => (
        <div key={n.currency} className="tile">
          <div className="label">{n.currency}</div>
          <div className="value">{formatMoney(n.netWorth, n.currency)}</div>
          <div className="muted small">
            You have {formatMoney(n.assets, n.currency)} · you owe {formatMoney(n.liabilities, n.currency)}
          </div>
        </div>
      ))}
    </div>
  )
}

/** Who owes whom between the user and the shared budget, per currency (rule 7). */
function Settlement({ open, accounts }: { open: SharedSettlement[]; accounts: AccountBalance[] }) {
  if (open.length === 0) return <p className="empty">Nothing is open with the shared budget.</p>
  return (
    <>
      {open.map((s) => {
        const name = accounts.find((a) => a.accountId === s.accountId)?.accountName ?? 'the shared budget'
        return (
          <div key={s.currency} className="settlement">
            <p className="sentence">{sentence(settlementSentence(s, name))}</p>
            <p className="muted small">
              Balance of <Link to={`/entries?accountId=${s.accountId}`}>{name}</Link>: {formatMoney(s.balance, s.currency)}
            </p>
          </div>
        )
      })}
    </>
  )
}

/** The accounts that aren't archived by type, with totals per currency. */
function BalancesByType({ balances }: { balances: AccountBalance[] }) {
  if (balances.length === 0) return <p className="empty">No accounts yet.</p>
  return (
    <>
      {ACCOUNT_TYPES.map((type) => {
        const rows = balances.filter((b) => b.accountType === type)
        if (rows.length === 0) return null
        const accounts = [...new Map(rows.map((b) => [b.accountId, b.accountName]))]
        const totals = [...new Set(rows.map((b) => b.currency))].sort().map((currency) => ({
          currency, amount: sum(rows.filter((b) => b.currency === currency).map((b) => b.balance)),
        }))
        return (
          <table key={type} className="by-type">
            <thead><tr><th scope="colgroup" colSpan={2}>{TYPE_LABELS[type]}</th></tr></thead>
            <tbody>
              {accounts.map(([id, name]) => (
                <tr key={id}>
                  <td><Link to={`/entries?accountId=${id}`}>{name}</Link></td>
                  <td className="amount nowrap">
                    <Amounts amounts={rows.filter((b) => b.accountId === id).map((b) => ({ currency: b.currency, amount: b.balance }))} />
                  </td>
                </tr>
              ))}
            </tbody>
            <tfoot><tr><th scope="row">Total</th><td className="amount nowrap"><Amounts amounts={totals} /></td></tr></tfoot>
          </table>
        )
      })}
    </>
  )
}

/** What is open with each counterparty on the accounts kept per counterparty (rule 8). */
function OpenLoans({ loans, accounts }: { loans: [CounterpartyBalance[], CounterpartyBalance[]]; accounts: AccountBalance[] }) {
  const groups = LOAN_ACCOUNTS.map((account, i) => {
    const balance = accounts.find((a) => a.accountCode === account.code)
    return { ...account, rows: loans[i], id: balance?.accountId, name: balance?.accountName ?? account.title }
  }).filter((group) => group.rows.length > 0)
  if (groups.length === 0) return <p className="empty">No open loans.</p>
  return (
    <div className="scroll-x">
      <table className="loans">
        <thead>
          <tr><th scope="col">Who</th><th scope="col" className="amount">Owes you</th><th scope="col" className="amount">You owe</th></tr>
        </thead>
        {groups.map((group) => (
          <tbody key={group.code}>
            <tr className="section"><th scope="rowgroup" colSpan={3}>{group.name}</th></tr>
            {group.rows.map((b) => {
              const { owesYou, youOwe } = loanSides(b, group.positive)
              return (
                <tr key={`${b.counterpartyId}:${b.currency}`}>
                  <td>
                    {group.id === undefined ? b.counterpartyName
                      : <Link to={`/entries?accountId=${group.id}&counterpartyId=${b.counterpartyId}`}>{b.counterpartyName}</Link>}
                  </td>
                  <td className="amount nowrap">{owesYou && formatMoney(owesYou, b.currency)}</td>
                  <td className="amount nowrap">{youOwe && formatMoney(youOwe, b.currency)}</td>
                </tr>
              )
            })}
          </tbody>
        ))}
      </table>
    </div>
  )
}
