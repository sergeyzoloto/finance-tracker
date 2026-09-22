import { currentMonth, money, useApi, type Summary } from './api'

export default function Dashboard() {
  const { from, to } = currentMonth()
  const { data, error } = useApi<Summary>(`/dashboard/summary?from=${from}&to=${to}`)
  const month = new Date().toLocaleDateString(undefined, { month: 'long', year: 'numeric' })

  if (error) return <p className="error">{error}</p>
  if (!data) return <p>Loading…</p>
  return (
    <>
      <h2>Dashboard</h2>
      <p>Balance (all time): <strong>{money(data.balance)}</strong></p>
      <p>
        {month}: income <strong className="income">{money(data.income)}</strong>, expenses{' '}
        <strong className="expense">{money(data.expense)}</strong>
      </p>
      <h3>Spending by category, {month}</h3>
      {data.spendByCategory.length === 0 ? <p>No expenses this month.</p> : (
        <table>
          <tbody>
            {data.spendByCategory.map((c) => (
              <tr key={c.categoryId}><td>{c.name}</td><td className="amount">{money(c.total)}</td></tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
