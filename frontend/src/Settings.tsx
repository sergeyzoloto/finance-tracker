import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { api, useApi, useMutation, type FamilyMemberships } from './api'
import { Errors, Field } from './components'
import { deletionNotes, ROLE_LABELS } from './family'
import { CONTACT_EMAIL, PRIVACY_URL } from './links'

/** What the user types to confirm that all their data goes. */
export const CONFIRMATION = 'DELETE'

/**
 * The user's data as a whole: deleting all of it (DELETE /api/me/data). With the family budget switched on, the screen
 * first lists every family budget the user is in, with their role and balance there and what the deletion does to it
 * (D-20, F6a); otherwise it is as before.
 *
 * @param onDeleted after the deletion, which also releases the user's family budgets (D-20)
 * @param familyOn whether the family budget is switched on (D-25)
 */
export default function Settings({ onDeleted = () => {}, familyOn = false }: {
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
    <>
      <h2>Settings</h2>
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
    </>
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
          <p>You are in {memberships.memberships.length === 1 ? 'a family budget' : 'these family budgets'}. In each, your
            personal budget’s entries go with the rest of your data, and:</p>
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
