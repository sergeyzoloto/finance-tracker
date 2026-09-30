import { useEffect, useState, type FormEvent } from 'react'
import { Link, Navigate, NavLink, Route, Routes, useLocation, useParams } from 'react-router'
import { api, fieldMessages, sentence, useApi, type FamilyLedger, type FamilyMember } from './api'
import { Errors, Field, Loading } from './components'
import { ROLE_LABELS } from './family'
import FamilyCategories from './FamilyCategories'
import { FamilyMembers, FamilySplitRule } from './FamilyMembers'
import { useFamilyMutation, type CreationState, type FamilyData } from './familyData'

/**
 * The pages of one family budget, under `/family/{ledgerId}` (ADR 0003, topic I): overview, members, split rule,
 * categories and settings. A budget the user isn't an ACTIVE member of, or that doesn't exist, answers 404, and so
 * does every request about it once it's gone: then the page says so, and the switcher's list is loaded again.
 *
 * @param onChanged loads the switcher's list again
 */
export default function Family({ onChanged }: { onChanged: () => void }) {
  const { ledgerId = '' } = useParams()
  const valid = /^[1-9]\d{0,17}$/.test(ledgerId)
  const path = `/family-ledgers/${ledgerId}`
  const ledger = useApi<FamilyLedger>(valid ? path : null)
  const members = useApi<FamilyMember[]>(valid ? `${path}/members` : null)
  const location = useLocation()
  const problems = (location.state as CreationState | null)?.creationProblems ?? []
  const notFound = !valid || ledger.status === 404 || members.status === 404

  useEffect(() => {
    if (notFound) onChanged()
  }, [notFound, onChanged])

  if (notFound) return <FamilyNotFound />
  const error = ledger.error ?? members.error
  if (!ledger.data || !members.data) return error ? <Errors messages={[error]} /> : <Loading what="the family budget" />

  const family: FamilyData = {
    ledger: ledger.data,
    members: members.data,
    path,
    page: `/family/${ledgerId}`,
    owner: ledger.data.role === 'OWNER',
    reload: () => { ledger.reload(); members.reload(); onChanged() },
  }
  return (
    <>
      <div className="page-title">
        <h2>{family.ledger.name}</h2>
        <span className="muted">Family budget · {family.ledger.baseCurrency} · {ROLE_LABELS[family.ledger.role]}</span>
      </div>
      <nav className="subnav" aria-label="Family budget">
        <NavLink to={family.page} end>Overview</NavLink>
        <NavLink to={`${family.page}/members`}>Members</NavLink>
        <NavLink to={`${family.page}/split-rule`}>Split rule</NavLink>
        <NavLink to={`${family.page}/categories`}>Categories</NavLink>
        <NavLink to={`${family.page}/settings`}>Settings</NavLink>
      </nav>
      {problems.length > 0 && (
        <div className="notice" role="alert">
          <p>The family budget was created, but not everything was set up:</p>
          <ul>{problems.map((p) => <li key={p}>{p}</li>)}</ul>
        </div>
      )}
      <Errors messages={[error]} />
      <Routes>
        <Route index element={<Overview family={family} />} />
        <Route path="members" element={<FamilyMembers family={family} />} />
        <Route path="split-rule" element={<FamilySplitRule family={family} />} />
        <Route path="categories" element={<FamilyCategories family={family} />} />
        <Route path="settings" element={<FamilySettings family={family} />} />
        <Route path="*" element={<Navigate to={family.page} replace />} />
      </Routes>
    </>
  )
}

/** A family budget that doesn't exist, or that the user isn't a member of: the same page for both. */
export function FamilyNotFound() {
  return (
    <>
      <h2>Family budget not found</h2>
      <p>This family budget doesn’t exist, or you aren’t a member of it.</p>
      <p><Link className="button primary" to="/">Back to Personal</Link></p>
    </>
  )
}

function Overview({ family }: { family: FamilyData }) {
  const { ledger, members } = family
  const me = members.find((m) => m.id === ledger.memberId)
  const active = members.filter((m) => m.status === 'ACTIVE')
  return (
    <section>
      <dl className="facts">
        <dt>Name</dt><dd>{ledger.name}</dd>
        <dt>Base currency</dt><dd>{ledger.baseCurrency}</dd>
        <dt>Your role</dt><dd>{ROLE_LABELS[ledger.role]}</dd>
        {me && <><dt>Your name in this budget</dt><dd>{me.displayName}</dd></>}
        <dt>Members</dt><dd>{active.length}</dd>
        <dt>Split rule</dt><dd>{ledger.splitRule === 'EQUAL' ? 'Equal shares' : 'Custom percentages'}</dd>
      </dl>
      <p className="actions">
        <Link className="button" to={`${family.page}/members`}>Members</Link>
        <Link className="button" to={`${family.page}/split-rule`}>Split rule</Link>
        <Link className="button" to={`${family.page}/categories`}>Categories</Link>
        <Link className="button" to={`${family.page}/settings`}>Settings</Link>
      </p>
      <p className="muted">
        Family expenses and incomes, and who owes whom, come in a later version. The other members see this budget’s
        members and categories, never your personal accounts, categories or entries.
      </p>
    </section>
  )
}

/** The name and the base currency; owners only (D-15). */
function FamilySettings({ family }: { family: FamilyData }) {
  const { ledger } = family
  const [name, setName] = useState(ledger.name)
  const [currency, setCurrency] = useState(ledger.baseCurrency)
  const [saved, setSaved] = useState(false)
  const save = useFamilyMutation(family, () => setSaved(true))

  if (!family.owner) {
    return (
      <section>
        <dl className="facts">
          <dt>Name</dt><dd>{ledger.name}</dd>
          <dt>Base currency</dt><dd>{ledger.baseCurrency}</dd>
        </dl>
        <p className="muted">Only an owner of the family budget changes its name and base currency.</p>
      </section>
    )
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    setSaved(false)
    void save.run(() => api(family.path, 'PATCH', { name: name.trim(), baseCurrency: currency }))
  }

  const nameErrors = fieldMessages(save.failure, 'name')
  const currencyErrors = fieldMessages(save.failure, 'baseCurrency')
  return (
    <section>
      <form onSubmit={submit} className="add">
        <Field label="Name" errors={nameErrors}>
          <input value={name} required maxLength={100} onChange={(e) => { setName(e.target.value); setSaved(false) }} />
        </Field>
        <Field label="Base currency" errors={currencyErrors}
          hint="Shares and balances are kept in it. It can change until the first family record.">
          <input className="currency" value={currency} required maxLength={3} pattern="[A-Za-z]{3}" autoComplete="off"
            spellCheck={false} onChange={(e) => { setCurrency(e.target.value.toUpperCase()); setSaved(false) }} />
        </Field>
        <button className="primary" disabled={save.pending || name.trim() === ''}>Save</button>
      </form>
      {saved && <p className="success" role="status">Saved.</p>}
      <Errors messages={[save.message ? sentence(save.message) : undefined]} />
    </section>
  )
}
