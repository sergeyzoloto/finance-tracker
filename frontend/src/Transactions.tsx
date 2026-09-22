import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api, currentMonth, isoDate, money, useApi, useMutation, type Category, type Transaction } from './api'

export default function Transactions() {
  const [filter, setFilter] = useState({ ...currentMonth(), categoryId: '' })
  const query = new URLSearchParams(Object.entries(filter).filter(([, value]) => value)).toString()
  const transactions = useApi<Transaction[]>(`/transactions?${query}`)
  const categories = useApi<Category[]>('/categories')
  const { run, error } = useMutation(transactions.reload)
  const [editing, setEditing] = useState<Transaction>()
  const categoryById = new Map(categories.data?.map((c) => [c.id, c]))

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    const body = {
      categoryId: Number(data.get('categoryId')),
      amount: Number(data.get('amount')),
      occurredOn: data.get('occurredOn'),
      note: data.get('note') || null,
    }
    const saved = await run(() => editing
      ? api(`/transactions/${editing.id}`, 'PUT', body)
      : api('/transactions', 'POST', body))
    if (saved) {
      form.reset()
      setEditing(undefined)
    }
  }

  function remove(transaction: Transaction) {
    if (confirm(`Delete the ${money(transaction.amount)} transaction of ${transaction.occurredOn}?`)) {
      setEditing(undefined)
      void run(() => api(`/transactions/${transaction.id}`, 'DELETE'))
    }
  }

  if (categories.data?.length === 0) {
    return <p>Add a <Link to="/categories">category</Link> first; every transaction belongs to one.</p>
  }
  return (
    <>
      <h2>Transactions</h2>
      <form key={editing?.id ?? 'new'} onSubmit={save}>
        <label>
          Category{' '}
          <select name="categoryId" required defaultValue={editing?.categoryId}>
            {categories.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <label>
          Amount{' '}
          <input name="amount" type="number" step="0.01" min="0.01" required defaultValue={editing?.amount.toFixed(2)} />
        </label>
        <label>
          Date <input name="occurredOn" type="date" required defaultValue={editing?.occurredOn ?? isoDate(new Date())} />
        </label>
        <label>Note <input name="note" maxLength={500} defaultValue={editing?.note ?? ''} /></label>
        <button>{editing ? 'Save' : 'Add'}</button>
        {editing && <button type="button" onClick={() => setEditing(undefined)}>Cancel</button>}
      </form>

      <fieldset>
        <legend>Filter</legend>
        <label>From <input type="date" value={filter.from} onChange={(e) => setFilter({ ...filter, from: e.target.value })} /></label>
        <label>To <input type="date" value={filter.to} onChange={(e) => setFilter({ ...filter, to: e.target.value })} /></label>
        <label>
          Category{' '}
          <select value={filter.categoryId} onChange={(e) => setFilter({ ...filter, categoryId: e.target.value })}>
            <option value="">All</option>
            {categories.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
      </fieldset>

      {(error ?? transactions.error ?? categories.error) && (
        <p className="error">{error ?? transactions.error ?? categories.error}</p>
      )}
      <table>
        <thead><tr><th>Date</th><th>Category</th><th className="amount">Amount</th><th>Note</th><th /></tr></thead>
        <tbody>
          {transactions.data?.map((t) => {
            const category = categoryById.get(t.categoryId)
            return (
              <tr key={t.id}>
                <td>{t.occurredOn}</td>
                <td>{category?.name}</td>
                <td className={`amount ${category?.type.toLowerCase() ?? ''}`}>{money(t.amount)}</td>
                <td>{t.note}</td>
                <td>
                  <button onClick={() => setEditing(t)}>Edit</button>{' '}
                  <button onClick={() => remove(t)}>Delete</button>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      {transactions.data?.length === 0 && <p>No transactions match the filter.</p>}
    </>
  )
}
