import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api, fieldMessages, useApi, useMutation, type Category, type CategoryType } from './api'
import { Errors, Loading } from './components'

const SECTIONS: { type: CategoryType; title: string }[] = [
  { type: 'EXPENSE', title: 'Expenses' },
  { type: 'INCOME', title: 'Income' },
]

export default function Categories() {
  const categories = useApi<Category[]>('/categories')
  const [showArchived, setShowArchived] = useState(false)
  const add = useMutation(categories.reload)

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    const name = String(data.get('name')).trim()
    const code = categoryCode(name, categories.data?.map((c) => c.code) ?? [])
    if (await add.run(() => api('/categories', 'POST', { code, name, type: data.get('type') }))) form.reset()
  }

  // The code is made from the name, so what the server says about either is about the name.
  const nameErrors = [...fieldMessages(add.failure, 'name'), ...fieldMessages(add.failure, 'code')]
  const archivedCount = categories.data?.filter((c) => c.archived).length ?? 0
  return (
    <>
      <div className="page-title">
        <h2>Categories</h2>
        <label className="check">
          <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
          Show archived{archivedCount > 0 && ` (${archivedCount})`}
        </label>
      </div>

      <form className="add" onSubmit={create}>
        <label className="field">
          <span className="label">New category</span>
          <input name="name" required maxLength={100} placeholder="Name" aria-invalid={nameErrors.length > 0}
            onChange={add.clear} />
          {nameErrors.map((m) => <small key={m} className="error" role="alert">{m}</small>)}
        </label>
        <label className="field">
          <span className="label">Type</span>
          <select name="type" defaultValue="EXPENSE">
            <option value="EXPENSE">Expense</option>
            <option value="INCOME">Income</option>
          </select>
        </label>
        <button className="primary" disabled={add.pending}>Add</button>
      </form>
      <Errors messages={[nameErrors.length === 0 ? add.error : undefined, categories.error]} />
      <p className="muted">A category’s type is fixed once it is created.</p>

      {!categories.data && !categories.error && <Loading what="categories" />}
      {categories.data && SECTIONS.map(({ type, title }) => {
        const shown = categories.data!.filter((c) => c.type === type && (showArchived || !c.archived))
        return (
          <section key={type}>
            <h3>{title}</h3>
            {shown.length === 0 ? <p className="empty">No {title.toLowerCase()} categories yet.</p> : (
              <table>
                <tbody>
                  {shown.map((c) => <CategoryRow key={c.id} category={c} onChanged={categories.reload} />)}
                </tbody>
              </table>
            )}
          </section>
        )
      })}
    </>
  )
}

function CategoryRow({ category, onChanged }: { category: Category; onChanged: () => void }) {
  const [renaming, setRenaming] = useState(false)
  const { run, failure, pending, clear } = useMutation(onChanged)

  async function rename(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const name = String(new FormData(event.currentTarget).get('name')).trim()
    if (name === category.name) return setRenaming(false)
    if (await run(() => api(`/categories/${category.id}`, 'PATCH', { name }))) setRenaming(false)
  }

  function archive(archived: boolean) {
    if (archived && !confirm(`Archive “${category.name}”? It leaves the lists you pick categories from; its entries keep it.`)) return
    void run(() => api(`/categories/${category.id}`, 'PATCH', { archived }))
  }

  const nameErrors = fieldMessages(failure, 'name')
  const otherError = failure && nameErrors.length === 0 ? failure.message : undefined
  return (
    <tr className={category.archived ? 'archived' : ''}>
      <td>
        {renaming ? (
          <form className="inline" onSubmit={rename}>
            <input name="name" defaultValue={category.name} required maxLength={100} autoFocus aria-label="Category name"
              aria-invalid={nameErrors.length > 0} />
            <button className="primary" disabled={pending}>Save</button>
            <button type="button" onClick={() => { setRenaming(false); clear() }}>Cancel</button>
            {nameErrors.map((m) => <small key={m} className="error" role="alert">{m}</small>)}
          </form>
        ) : (
          <>
            <Link to={`/entries?categoryId=${category.id}`} title="Show its entries">{category.name}</Link>
            {category.archived && <span className="badge">Archived</span>}
          </>
        )}
        {otherError && <div><small className="error" role="alert">{otherError}</small></div>}
      </td>
      <td className="actions nowrap">
        {!renaming && !category.archived && <button type="button" onClick={() => { clear(); setRenaming(true) }}>Rename</button>}
        {!renaming && (
          <button type="button" disabled={pending} onClick={() => archive(!category.archived)}>
            {category.archived ? 'Restore' : 'Archive'}
          </button>
        )}
      </td>
    </tr>
  )
}

const CYRILLIC: Record<string, string> = {
  а: 'a', б: 'b', в: 'v', г: 'g', ґ: 'g', д: 'd', е: 'e', ё: 'e', є: 'ye', ж: 'zh', з: 'z', и: 'i', і: 'i', ї: 'yi',
  й: 'y', к: 'k', л: 'l', м: 'm', н: 'n', о: 'o', п: 'p', р: 'r', с: 's', т: 't', у: 'u', ф: 'f', х: 'kh', ц: 'ts',
  ч: 'ch', ш: 'sh', щ: 'shch', ъ: '', ы: 'y', ь: '', э: 'e', ю: 'yu', я: 'ya',
}

/**
 * A code for a new category, which the backend needs (capital letters, digits and underscores, starting with a
 * letter; unique per user), made from its name: "Eating out" → EATING_OUT, "Продукты" → PRODUKTY. The user never
 * sees it.
 */
export function categoryCode(name: string, taken: string[]): string {
  const latin = [...name.toLowerCase()].map((ch) => CYRILLIC[ch] ?? ch).join('')
    .normalize('NFKD').replace(/[̀-ͯ]/g, '')
  let base = latin.toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 44)
  if (!/^[A-Z]/.test(base)) base = base ? `C_${base}` : 'CATEGORY'
  let code = base
  for (let n = 2; taken.includes(code); n++) code = `${base}_${n}`
  return code
}
