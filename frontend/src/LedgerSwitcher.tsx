import { useLocation, useNavigate } from 'react-router'
import type { FamilyLedger } from './api'

const PERSONAL = 'personal'
const NEW = 'new'

/** Which budget the page belongs to: a family budget's id, a new one, or the personal pages. */
function current(pathname: string) {
  const family = /^\/family\/([^/]+)/.exec(pathname)
  return family ? family[1] : PERSONAL
}

/**
 * The header's choice between the personal pages and the user's family budgets (ADR 0003, topic I; D-5): "Personal",
 * each family budget by name, and "New family budget". Shown only while the family budget is switched on. A native
 * select, so it shows the current choice and stays usable on a narrow screen.
 *
 * @param families the user's family budgets, by name; undefined while they load
 */
export default function LedgerSwitcher({ families }: { families: FamilyLedger[] | undefined }) {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const value = current(pathname)
  // A family budget that isn't in the list (yet): while the list loads, or one the user isn't a member of.
  const unlisted = value !== PERSONAL && value !== NEW && !families?.some((f) => String(f.id) === value)

  function choose(choice: string) {
    if (choice === PERSONAL) navigate('/')
    else if (choice === NEW) navigate('/family/new')
    else navigate(`/family/${choice}`)
  }

  return (
    <select className="ledger-switcher" aria-label="Budget" value={value} onChange={(e) => choose(e.target.value)}>
      <option value={PERSONAL}>Personal</option>
      {unlisted && <option value={value} disabled>Family budget</option>}
      {families && families.length > 0 && (
        <optgroup label="Family budgets">
          {families.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
        </optgroup>
      )}
      <option value={NEW}>New family budget…</option>
    </select>
  )
}
