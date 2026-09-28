import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { api, useMutation } from './api'
import { Errors, Field } from './components'
import { CONTACT_EMAIL, PRIVACY_URL } from './links'

/** What the user types to confirm that all their data goes. */
export const CONFIRMATION = 'DELETE'

/** The user's data as a whole: deleting all of it (DELETE /api/me/data). */
export default function Settings() {
  const navigate = useNavigate()
  const [typed, setTyped] = useState('')
  const deletion = useMutation(() => navigate('/', { state: { dataDeleted: true } }))
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
