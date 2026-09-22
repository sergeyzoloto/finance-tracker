import { useState, type FormEvent } from 'react'
import { api, useApi, useMutation, type Category } from './api'

export default function Categories() {
  const categories = useApi<Category[]>('/categories')
  const { run, error } = useMutation(categories.reload)
  const [editing, setEditing] = useState<Category>()

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    const body = { name: data.get('name'), type: data.get('type') }
    const saved = await run(() => editing
      ? api(`/categories/${editing.id}`, 'PUT', body)
      : api('/categories', 'POST', body))
    if (saved) {
      form.reset()
      setEditing(undefined)
    }
  }

  function remove(category: Category) {
    if (confirm(`Delete category "${category.name}"?`)) {
      setEditing(undefined)
      void run(() => api(`/categories/${category.id}`, 'DELETE'))
    }
  }

  return (
    <>
      <h2>Categories</h2>
      <form key={editing?.id ?? 'new'} onSubmit={save}>
        <label>Name <input name="name" required maxLength={100} defaultValue={editing?.name} /></label>
        <label>
          Type{' '}
          <select name="type" defaultValue={editing?.type ?? 'EXPENSE'}>
            <option value="EXPENSE">Expense</option>
            <option value="INCOME">Income</option>
          </select>
        </label>
        <button>{editing ? 'Save' : 'Add'}</button>
        {editing && <button type="button" onClick={() => setEditing(undefined)}>Cancel</button>}
      </form>
      {(error ?? categories.error) && <p className="error">{error ?? categories.error}</p>}
      <table>
        <thead><tr><th>Name</th><th>Type</th><th /></tr></thead>
        <tbody>
          {categories.data?.map((c) => (
            <tr key={c.id}>
              <td>{c.name}</td>
              <td className={c.type.toLowerCase()}>{c.type === 'INCOME' ? 'Income' : 'Expense'}</td>
              <td>
                <button onClick={() => setEditing(c)}>Edit</button>{' '}
                <button onClick={() => remove(c)}>Delete</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  )
}
