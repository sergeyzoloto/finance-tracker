import { useState, type FormEvent } from 'react'
import {
  api, ApiError, formatDate, formatInstant, isoDate, sentence, type CreatedInvite, type FamilyInvite,
  type FamilyMember,
} from './api'
import { Errors, Field, Loading } from './components'
import { useFamilyApi, useFamilyMutation, type FamilyData } from './familyData'

/**
 * A family budget's invites, for its owners (F5; B2, B4; D-15, D-17): "Invite someone new", and for a member without
 * an account "Invite to take this place" from a join date (D-18). A new link shows once, with a copy button: nothing
 * shows it again, since only its hash is kept. Below, every invite with its status, and Revoke for a pending one.
 *
 * @param seat the member without an account whose place to offer, chosen on the members' list; null for none
 * @param onSeatDone closes the place's form
 */
export function FamilyInvites({ family, seat, onSeatDone }: {
  family: FamilyData
  seat: FamilyMember | null
  onSeatDone: () => void
}) {
  const invites = useFamilyApi<FamilyInvite[]>(family, `${family.path}/invites`)
  const [created, setCreated] = useState<CreatedInvite>()
  const [joinDate, setJoinDate] = useState(() => isoDate(new Date()))
  const create = useFamilyMutation(family, invites.reload)
  const revoke = useFamilyMutation(family, invites.reload)

  function invite(request: object) {
    setCreated(undefined)
    void create.run(async () => {
      setCreated(await api<CreatedInvite>(`${family.path}/invites`, 'POST', request))
      onSeatDone()
    })
  }

  function claim(event: FormEvent) {
    event.preventDefault()
    if (seat) invite({ kind: 'CLAIM', seatMemberId: seat.id, joinDate })
  }

  const today = isoDate(new Date())
  const joinDateErrors = create.failure instanceof ApiError
    ? create.failure.violationDetails.filter((v) => v.code === 'JOIN_DATE').map((v) => sentence(v.message)) : []
  return (
    <section className="invites">
      <h3>Invites</h3>
      <p className="muted">
        An invite is a link for one person. They sign in or register, see the family budget’s name and who invites
        them, and accept or decline. Only a hash of the link is kept, so it shows once.
      </p>
      <div className="actions">
        <button type="button" className="primary" disabled={create.pending}
          onClick={() => invite({ kind: 'NEW_MEMBER' })}>Invite someone new</button>
      </div>
      {seat && (
        <form className="add" onSubmit={claim}>
          <Field label={`Invite someone to take ${seat.displayName}’s place, from`} errors={joinDateErrors}
            hint={`What was recorded for ${seat.displayName} from this day on becomes theirs; before it, one opening balance.`}>
            <input type="date" value={joinDate} min={family.ledger.startDate} max={today} required
              onChange={(e) => { setJoinDate(e.target.value); create.clear() }} />
          </Field>
          <button className="primary" disabled={create.pending}>Create the link</button>
          <button type="button" onClick={onSeatDone}>Cancel</button>
        </form>
      )}
      <Errors messages={[joinDateErrors.length > 0 ? undefined : create.message && sentence(create.message)]} />
      {created && <NewLink invite={created} />}

      {invites.data ? (
        invites.data.length === 0 ? <p className="empty">No invites yet.</p> : (
          <ul className="invite-list">
            {invites.data.map((i) => (
              <li key={i.id}>
                <div>
                  <strong>{i.kind === 'CLAIM' ? `To take ${i.seat?.displayName}’s place` : 'A new member'}</strong>
                  {i.kind === 'CLAIM' && i.joinDate && <span className="muted"> from {formatDate(i.joinDate)}</span>}
                </div>
                <div className="muted small">
                  By {i.createdBy.displayName}, {formatInstant(i.createdAt)} · {statusText(i)}
                </div>
                {i.status === 'PENDING' && (
                  <button type="button" disabled={revoke.pending} onClick={() => {
                    if (confirm('Revoke this invite? Its link stops working.')) {
                      void revoke.run(() => api(`${family.path}/invites/${i.id}`, 'DELETE'))
                    }
                  }}>Revoke</button>
                )}
              </li>
            ))}
          </ul>
        )
      ) : !invites.error && <Loading what="the invites" />}
      <Errors messages={[invites.error, revoke.message && sentence(revoke.message)]} />
    </section>
  )
}

/** The new link, once, with a copy button and how long it works. */
function NewLink({ invite }: { invite: CreatedInvite }) {
  const [copied, setCopied] = useState(false)
  return (
    <div className="success new-link" role="status">
      <p>
        {invite.kind === 'CLAIM' ? `The link to take ${invite.seat?.displayName}’s place` : 'The link for a new member'}:
      </p>
      <div className="link-copy">
        <input readOnly value={invite.link} aria-label="Invite link" onFocus={(e) => e.target.select()} />
        <button type="button" onClick={() => {
          void navigator.clipboard?.writeText(invite.link).then(() => setCopied(true), () => setCopied(false))
        }}>{copied ? 'Copied' : 'Copy'}</button>
      </div>
      <p className="small">
        It works once, for one person, until {formatInstant(invite.expiresAt)}. Send it only to them; it won’t show
        again.
      </p>
    </div>
  )
}

function statusText(invite: FamilyInvite) {
  switch (invite.status) {
    case 'PENDING': return `pending until ${formatInstant(invite.expiresAt)}`
    case 'ACCEPTED': return `accepted by ${invite.acceptedBy?.displayName} on ${formatInstant(invite.acceptedAt!)}`
    case 'DECLINED': return `declined on ${formatInstant(invite.declinedAt!)}`
    case 'REVOKED': return `revoked on ${formatInstant(invite.revokedAt!)}`
    case 'EXPIRED': return `expired on ${formatInstant(invite.expiresAt)}`
  }
}
