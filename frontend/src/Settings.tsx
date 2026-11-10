import { useState, type FormEvent } from 'react'
import { Navigate, NavLink, Route, Routes, useNavigate } from 'react-router'
import { api, useApi, useMutation, type FamilyLedger, type FamilyMemberships } from './api'
import { Errors, Field } from './components'
import DateFormatSettings from './DateFormatSettings'
import Export from './Export'
import { deletionNotes, ROLE_LABELS } from './family'
import Import from './Import'
import { CONTACT_EMAIL, PRIVACY_URL } from './links'
import { TimeZoneSettings } from './TimeZone'

/** What the user types to confirm that all their data goes. */
export const CONFIRMATION = 'DELETE'

/** Settings' parts, in their order (D-438): each its own page under `/settings/`, the danger last. */
export const SETTINGS_PARTS = [
  ['time-zone', 'Time zone'],
  ['date-format', 'Date format'],
  ['import', 'Import'],
  ['export', 'Export'],
  ['delete', 'Delete all my data'],
] as const

/**
 * Settings in parts (D-437, D-438): the heading, the parts as a sub-navigation, then the part the path names. `/settings`
 * and an unknown part open Time zone, replacing the address.
 *
 * @param onDeleted after "Delete all my data", which also releases the user's family budgets (D-20)
 * @param familyOn whether the family budget is switched on (D-25)
 * @param families the family budgets the import may name; undefined with the family budget switched off
 */
export default function Settings({ onDeleted = () => {}, familyOn = false, families }: {
  onDeleted?: () => void
  familyOn?: boolean
  families?: FamilyLedger[]
}) {
  return (
    <>
      <h2>Settings</h2>
      <nav className="subnav" aria-label="Settings">
        {SETTINGS_PARTS.map(([path, label]) => <NavLink key={path} to={`/settings/${path}`}>{label}</NavLink>)}
      </nav>
      <Routes>
        <Route path="time-zone" element={<TimeZoneSettings />} />
        <Route path="date-format" element={<DateFormatSettings />} />
        <Route path="import" element={<Import families={families} />} />
        <Route path="export" element={<Export />} />
        <Route path="delete" element={<DeleteAllData onDeleted={onDeleted} familyOn={familyOn} />} />
        <Route path="*" element={<Navigate to="/settings/time-zone" replace />} />
      </Routes>
    </>
  )
}

/**
 * The user's data as a whole: deleting all of it (DELETE /api/me/data). With the family budget switched on, the screen
 * first lists every family budget the user is in, with their role and balance there and what the deletion does to it
 * (D-20, F6a); otherwise it is as before.
 */
export function DeleteAllData({ onDeleted = () => {}, familyOn = false }: {
  onDeleted?: () => void
  familyOn?: boolean
}) {
  const navigate = useNavigate()
  const memberships = useApi<FamilyMemberships>(familyOn ? '/me/family-memberships' : null)
  const [typed, setTyped] = useState('')
  const deletion = useMutation(() => { onDeleted(); navigate('/', { state: { dataDeleted: true } }) })
  const confirmed = typed === CONFIRMATION

  function submit(event: FormEvent) {
    event.preventDefault()
    if (confirmed) void deletion.run(() => api('/me/data', 'DELETE'))
  }

  return (
    <section className="card danger-zone">
      <h3>Delete all my data</h3>
      <p>
        This deletes everything you have in Finance Tracker, at once and for good: entries, accounts, categories,
        counterparties, settings, exchange rates you entered, and the records of your imports. Your ledger then
        starts empty again, and you can load the demo data again.
      </p>
      <p className="muted">
        Your login account is not deleted: it lives on auth.finance-nl.com, the login service. To have it deleted
        too, email <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>, as the{' '}
        <a href={PRIVACY_URL}>privacy policy</a> describes.
      </p>
      {familyOn && <FamilyBudgetsTouched memberships={memberships.data} error={memberships.error} />}
      <form onSubmit={submit}>
        <Field label={<>Type <strong>{CONFIRMATION}</strong> to confirm</>}>
          <input value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" spellCheck={false}
            aria-describedby="delete-warning" />
        </Field>
        <button type="submit" className="danger" disabled={!confirmed || deletion.pending}>
          {deletion.pending ? 'Deleting…' : 'Delete all my data'}
        </button>
      </form>
      <p id="delete-warning" className="small muted">This can’t be undone.</p>
      <Errors messages={[deletion.error]} />
    </section>
  )
}

/** The user's family budgets as the deletion touches them, before they confirm (D-20). */
function FamilyBudgetsTouched({ memberships, error }: { memberships: FamilyMemberships | undefined; error?: string }) {
  if (error) return <Errors messages={[error]} />
  if (!memberships) return <p className="muted">Loading your family budgets…</p>
  if (memberships.memberships.length === 0 && memberships.left === 0) return null
  return (
    <div className="family-budgets-touched">
      {memberships.memberships.length > 0 && (
        <>
          <p>You are in {memberships.memberships.length === 1 ? 'a family budget' : 'these family budgets'}. In each, the
            entries in My finances go with the rest of your data, and:</p>
          <ul>
            {memberships.memberships.map((m) => (
              <li key={m.ledgerId}>
                <strong>{m.name}</strong> <span className="muted">({ROLE_LABELS[m.role]})</span>
                <ul>{deletionNotes(m).map((note) => <li key={note}>{note}</li>)}</ul>
              </li>
            ))}
          </ul>
        </>
      )}
      {memberships.left > 0 && (
        <p>{memberships.left === 1 ? 'In the family budget you left' : `In the ${memberships.left} family budgets you left`},
          your name becomes “Former member” too, and your comments are erased.</p>
      )}
    </div>
  )
}
