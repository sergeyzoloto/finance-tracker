import { useState, type FormEvent } from 'react'
import { api, fieldMessages, sentence, useApi, type Category, type CategoryType } from './api'
import { categoryCode } from './Categories'
import { Errors, Loading } from './components'
import { useFamilyMutation, type FamilyData } from './familyData'

const SECTIONS: { type: CategoryType; title: string }[] = [
  { type: 'EXPENSE', title: 'Expenses' },
  { type: 'INCOME', title: 'Income' },
]

/**
 * A family budget's own categories (D-11), as the personal categories page lists them. Any member adds one; owners
 * rename, archive and delete (D-15), and a category in use can only be archived, which the server's 409 says.
 */
export default function FamilyCategories({ family }: { family: FamilyData }) {
  const categories = useApi<Category[]>(`${family.path}/categories`)
  const [showArchived, setShowArchived] = useState(false)
  const add = useFamilyMutation(family, categories.reload)

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    const name = String(data.get('name')).trim()
    const code = categoryCode(name, categories.data?.map((c) => c.code) ?? [])
    if (await add.run(() => api(`${family.path}/categories`, 'POST', { code, name, type: data.get('type') }))) form.reset()
  }

  // The code is made from the name, so what the server says about either is about the name.
  const nameErrors = [...fieldMessages(add.failure, 'name'), ...fieldMessages(add.failure, 'code')]
  const archivedCount = categories.data?.filter((c) => c.archived).length ?? 0
  return (
    <section>
      <div className="page-title">
        <h3>Categories</h3>
        <label className="check">
          <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
          Show archived{archivedCount > 0 && ` (${archivedCount})`}
        </label>
      </div>
      <form className="add" onSubmit={create}>
        <label className="field">
          <span className="label">New family category</span>
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
      <Errors messages={[nameErrors.length === 0 && add.message ? sentence(add.message) : undefined, categories.error]} />
      <p className="muted">
        Every member of the family budget sees these categories and can add one.
        {family.owner ? ' As an owner, you rename, archive and delete them.' : ' Only an owner renames, archives and deletes them.'}
      </p>

      {!categories.data && !categories.error && <Loading what="categories" />}
      {categories.data && SECTIONS.map(({ type, title }) => {
        const shown = categories.data!.filter((c) => c.type === type && (showArchived || !c.archived))
        return (
          <section key={type}>
            <h4>{title}</h4>
            {shown.length === 0 ? <p className="empty">No {title.toLowerCase()} categories yet.</p> : (
              <table>
                <tbody>
                  {shown.map((c) => (
                    <CategoryRow key={c.id} category={c} family={family} onChanged={categories.reload} />
                  ))}
                </tbody>
              </table>
            )}
          </section>
        )
      })}
    </section>
  )
}

function CategoryRow({ category, family, onChanged }: { category: Category; family: FamilyData; onChanged: () => void }) {
  const [renaming, setRenaming] = useState(false)
  const change = useFamilyMutation(family, () => { setRenaming(false); onChanged() })
  const path = `${family.path}/categories/${category.id}`

  async function rename(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const name = String(new FormData(event.currentTarget).get('name')).trim()
    if (name === category.name) return setRenaming(false)
    await change.run(() => api(path, 'PATCH', { name }))
  }

  function archive(archived: boolean) {
    if (archived && !confirm(`Archive “${category.name}”? It leaves the lists members pick categories from.`)) return
    void change.run(() => api(path, 'PATCH', { archived }))
  }

  function remove() {
    if (!confirm(`Delete “${category.name}”? This works only while nothing uses it.`)) return
    void change.run(() => api(path, 'DELETE'))
  }

  const nameErrors = fieldMessages(change.failure, 'name')
  const otherError = nameErrors.length === 0 ? change.message : undefined
  return (
    <tr className={category.archived ? 'archived' : ''}>
      <td>
        {renaming ? (
          <form className="inline" onSubmit={rename}>
            <input name="name" defaultValue={category.name} required maxLength={100} autoFocus aria-label="Category name"
              aria-invalid={nameErrors.length > 0} />
            <button className="primary" disabled={change.pending}>Save</button>
            <button type="button" onClick={() => { setRenaming(false); change.clear() }}>Cancel</button>
            {nameErrors.map((m) => <small key={m} className="error" role="alert">{m}</small>)}
          </form>
        ) : (
          <>
            {category.name}
            {category.archived && <span className="badge">Archived</span>}
          </>
        )}
        {otherError && <div><small className="error" role="alert">{sentence(otherError)}</small></div>}
      </td>
      <td className="actions nowrap">
        {family.owner && !renaming && (
          <>
            {!category.archived && <button type="button" onClick={() => { change.clear(); setRenaming(true) }}>Rename</button>}
            <button type="button" disabled={change.pending} onClick={() => archive(!category.archived)}>
              {category.archived ? 'Restore' : 'Archive'}
            </button>
            <button type="button" disabled={change.pending} onClick={remove}>Delete</button>
          </>
        )}
      </td>
    </tr>
  )
}
