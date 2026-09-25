import { currentMonth, useApi, type CashFlowRow, type NetWorth } from './api'
import { Errors, Loading } from './components'
import { compare, formatMoney, sum } from './money'

export default function Dashboard() {
  const { from, to } = currentMonth()
  const netWorth = useApi<NetWorth[]>('/reports/net-worth')
  const cashFlow = useApi<CashFlowRow[]>(`/reports/cash-flow?from=${from}&to=${to}`)
  const month = new Date().toLocaleDateString(undefined, { month: 'long', year: 'numeric' })

  const error = netWorth.error ?? cashFlow.error
  if (error) return <><h2>Dashboard</h2><Errors messages={[error]} /></>
  if (!netWorth.data || !cashFlow.data) return <><h2>Dashboard</h2><Loading /></>
  const currencies = [...new Set(cashFlow.data.map((r) => r.currency))].sort()
  return (
    <>
      <h2>Dashboard</h2>
      <h3>Net worth</h3>
      {netWorth.data.length === 0 ? <p className="empty">No balances yet.</p> : (
        <table>
          <thead><tr><th>Currency</th><th className="amount">You have</th><th className="amount">You owe</th><th className="amount">Net worth</th></tr></thead>
          <tbody>
            {netWorth.data.map((n) => (
              <tr key={n.currency}>
                <td>{n.currency}</td>
                <td className="amount">{formatMoney(n.assets, n.currency)}</td>
                <td className="amount">{formatMoney(n.liabilities, n.currency)}</td>
                <td className="amount"><strong>{formatMoney(n.netWorth, n.currency)}</strong></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <h3>{month}</h3>
      {currencies.length === 0 && <p className="empty">No income or expenses this month.</p>}
      {currencies.map((currency) => {
        const rows = cashFlow.data!.filter((r) => r.currency === currency)
        const income = rows.filter((r) => r.categoryType === 'INCOME')
        const expenses = rows.filter((r) => r.categoryType === 'EXPENSE')
        return (
          <section key={currency}>
            <p>
              Income <strong className="income">{formatMoney(sum(income.map((r) => r.total)), currency)}</strong>,
              expenses <strong className="expense">{formatMoney(sum(expenses.map((r) => r.total)), currency)}</strong>
            </p>
            {expenses.length > 0 && (
              <table>
                <tbody>
                  {[...expenses].sort((a, b) => compare(b.total, a.total)).map((r) => (
                    <tr key={r.categoryCode}><td>{r.categoryName}</td><td className="amount">{formatMoney(r.total, currency)}</td></tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        )
      })}
    </>
  )
}
