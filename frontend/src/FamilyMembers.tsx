import { useState, type FormEvent } from 'react'
import { api, ApiError, fieldMessages, formatDate, sentence, type CurrencyAmount, type FamilyBalances, type FamilyMember, type SplitRule } from './api'
import { basisPointsToPercent, equalShares, percentToBasisPoints, WHOLE } from './basisPoints'
import { Errors } from './components'
import { useFamilyApi, useFamilyMutation, type FamilyData } from './familyData'
import {
  closesBudget, departureNotes, lastOwner, ROLE_LABELS, shareText, splitMembers, STATUS_LABELS, violationsByMember,
} from './family'
import { FamilyInvites } from './FamilyInvites'

/**
 * The members of a family budget (D-3, D-15, D-19). Owners add and rename members without an account, remove any
 * other member, and make a member with an account an owner; every member with an account changes their own name and
 * may leave. Leaving and removal first show what happens: the member's balance, what stays in their personal budget,
 * the records that freeze, the split rule going back to equal shares (F6a).
 */
export function FamilyMembers({ family }: { family: FamilyData }) {
  const custom = family.ledger.splitRule === 'CUSTOM'
  const [name, setName] = useState('')
  // The member without an account whose place an owner is about to offer (F5).
  const [seat, setSeat] = useState<FamilyMember | null>(null)
  // The member about to leave or be removed, whose confirmation shows below the table (F6a).
  const [departing, setDeparting] = useState<FamilyMember | null>(null)
  const balances = useFamilyApi<FamilyBalances>(family, `${family.path}/balances`)
  const add = useFamilyMutation(family, () => setName(''))

  function submit(event: FormEvent) {
    event.preventDefault()
    void add.run(() => api(`${family.path}/members`, 'POST', { displayName: name.trim() }))
  }

  const nameErrors = fieldMessages(add.failure, 'displayName')
  return (
    <section>
      <div className="scroll-x">
        <table className="members">
          <thead>
            <tr>
              <th>Name</th><th>Role</th><th>Status</th><th>Joined</th>{custom && <th className="amount">Share</th>}<th />
            </tr>
          </thead>
          <tbody>
            {family.members.map((m) => (
              <MemberRow key={m.id} member={m} family={family} custom={custom} onInvite={() => setSeat(m)}
                onDepart={() => setDeparting(m)} />
            ))}
          </tbody>
        </table>
      </div>
      {departing && (
        <Departure key={departing.id} member={departing} family={family}
          balances={balances.data?.byCurrency.map((c) => ({ currency: c.currency,
            amount: c.members.find((b) => b.memberId === departing.id)?.balance ?? '0' }))}
          onClose={() => setDeparting(null)} />
      )}
      {family.owner ? (
        <form className="add" onSubmit={submit}>
          <label className="field">
            <span className="label">Add a member without an account</span>
            <input value={name} onChange={(e) => { setName(e.target.value); add.clear() }} required maxLength={100}
              placeholder="Name, such as your partner’s" aria-invalid={nameErrors.length > 0 || !!add.message} />
            {nameErrors.map((m) => <small key={m} className="error" role="alert">{m}</small>)}
          </label>
          <button className="primary" disabled={add.pending || name.trim() === ''}>Add</button>
          {add.message && <small className="error" role="alert">{sentence(add.message)}</small>}
        </form>
      ) : (
        <p className="muted">Only an owner adds, renames and removes members.</p>
      )}
      <p className="muted small">
        A member without an account is someone you keep the budget with who doesn’t use Finance Tracker, such as a
        partner or a child. Other members see every member’s name as it is here.
      </p>
      {family.owner && <FamilyInvites family={family} seat={seat} onSeatDone={() => setSeat(null)} />}
    </section>
  )
}

function MemberRow({ member, family, custom, onInvite, onDepart }: {
  member: FamilyMember
  family: FamilyData
  custom: boolean
  onInvite: () => void
  onDepart: () => void
}) {
  const [renaming, setRenaming] = useState(false)
  const own = member.id === family.ledger.memberId
  const active = member.status === 'ACTIVE'
  const change = useFamilyMutation(family, () => setRenaming(false))
  // Owners rename members without an account; a member who left or a former member stays as they are.
  const renamable = family.owner && !member.hasAccount && active
  const removable = family.owner && !own && active
  const promotable = family.owner && active && member.hasAccount && member.role === 'MEMBER'

  function rename(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const displayName = String(new FormData(event.currentTarget).get('name')).trim()
    if (displayName === member.displayName) return setRenaming(false)
    const path = own ? `${family.path}/members/me` : `${family.path}/members/${member.id}`
    void change.run(() => api(path, 'PATCH', { displayName }))
  }

  function makeOwner() {
    if (!confirm(`Make “${member.displayName}” an owner of the family budget? Owners manage its members, invites, `
      + 'split rule and categories.')) return
    void change.run(() => api(`${family.path}/members/${member.id}/owner`, 'POST'))
  }

  const nameErrors = fieldMessages(change.failure, 'displayName')
  return (
    <tr className={active ? '' : 'archived'}>
      <td>
        {renaming ? (
          <form className="inline" onSubmit={rename}>
            <input name="name" defaultValue={member.displayName} required maxLength={100} autoFocus
              aria-label={own ? 'Your name in this budget' : 'Member name'} aria-invalid={nameErrors.length > 0} />
            <button className="primary" disabled={change.pending}>Save</button>
            <button type="button" onClick={() => { setRenaming(false); change.clear() }}>Cancel</button>
            {nameErrors.map((m) => <small key={m} className="error" role="alert">{m}</small>)}
          </form>
        ) : (
          <>
            {member.displayName}
            {own && <span className="badge">You</span>}
            {!member.hasAccount && member.status !== 'FORMER' && <span className="badge">No account</span>}
          </>
        )}
        {change.message && (
          <div><small className="error" role="alert">{sentence(change.message)}</small></div>
        )}
      </td>
      <td data-label="Role">{ROLE_LABELS[member.role]}</td>
      <td className="nowrap" data-label="Status">
        {member.status === 'LEFT' && member.leftDate ? `Left on ${formatDate(member.leftDate)}` : STATUS_LABELS[member.status]}
      </td>
      <td className="nowrap" data-label="Joined">{formatDate(member.joinDate)}</td>
      {custom && <td className="amount nowrap" data-label="Share">{shareText(member) ?? '—'}</td>}
      <td className="actions nowrap">
        {!renaming && own && active && member.hasAccount && (
          <>
            <button type="button" onClick={() => { change.clear(); setRenaming(true) }}>Change my name</button>
            <button type="button" onClick={onDepart}>Leave</button>
          </>
        )}
        {!renaming && renamable && (
          <button type="button" onClick={() => { change.clear(); setRenaming(true) }}>Rename</button>
        )}
        {!renaming && promotable && (
          <button type="button" disabled={change.pending} onClick={makeOwner}>Make owner</button>
        )}
        {!renaming && removable && <button type="button" onClick={onDepart}>Remove</button>}
        {!renaming && renamable && <button type="button" onClick={onInvite}>Invite to take this place</button>}
      </td>
    </tr>
  )
}

/**
 * The confirmation of leaving the family budget, or of an owner's removal of a member (D-19): what happens, then the
 * request. The last owner is told to make another member an owner first, as the server's 409 `LAST_OWNER` says too.
 * After leaving, the reader is taken to their personal budget.
 */
function Departure({ member, family, balances, onClose }: {
  member: FamilyMember
  family: FamilyData
  balances: CurrencyAmount[] | undefined
  onClose: () => void
}) {
  const self = member.id === family.ledger.memberId
  const blocked = self && lastOwner(family.members, family.ledger.memberId)
  const deletes = closesBudget(member, family.members)
  const go = useFamilyMutation(family, () => {
    if (self) family.left?.()
    else onClose()
  })
  const notes = departureNotes({ member, me: family.ledger.memberId, balances, members: family.members,
    splitRule: family.ledger.splitRule, budget: family.ledger.name })
  const lastOwnerRefused = go.failure instanceof ApiError && go.failure.code === 'LAST_OWNER'
  return (
    <div className="card notice" role="region" aria-label={self ? 'Leave the family budget' : `Remove ${member.displayName}`}>
      <h3>{self ? `Leave “${family.ledger.name}”?` : `Remove “${member.displayName}” from the family budget?`}</h3>
      {blocked ? (
        <p role="alert">You are the family budget’s last owner. Make another member with an account an owner first, then
          leave.</p>
      ) : (
        <ul>{notes.map((note) => <li key={note}>{note}</li>)}</ul>
      )}
      <div className="actions">
        {!blocked && (
          <button type="button" className="danger" disabled={go.pending}
            onClick={() => void go.run(() => api(`${family.path}/members/${self ? 'me' : member.id}`, 'DELETE'))}>
            {!self ? `Remove ${member.displayName}`
              : deletes ? 'Leave and delete the family budget' : 'Leave the family budget'}
          </button>
        )}
        <button type="button" onClick={onClose}>Cancel</button>
      </div>
      <Errors messages={[go.message && !lastOwnerRefused ? sentence(go.message) : undefined,
        lastOwnerRefused ? sentence(go.message!) : undefined]} />
    </div>
  )
}

/** One person in a table of custom shares: a member, or on the creation page a member to be. */
export interface ShareRow { key: string; name: string }

/**
 * Percentages per person with a live total, as typed (up to two decimals). `errors` are the server's by row key.
 * `total` is null while a value isn't a percentage.
 */
export function ShareTable({ rows, values, onChange, errors = new Map() }: {
  rows: ShareRow[]
  values: Record<string, string>
  onChange: (key: string, value: string) => void
  errors?: Map<string, string[]>
}) {
  const total = shareTotal(rows, values)
  return (
    <table className="shares">
      <tbody>
        {rows.map((row) => {
          const invalid = percentToBasisPoints(values[row.key] ?? '') === null
          const messages = [...(invalid ? ['A percentage from 0 to 100, with at most two decimals.'] : []),
            ...(errors.get(row.key) ?? [])]
          return (
            <tr key={row.key}>
              <th scope="row">{row.name}</th>
              <td className="amount nowrap">
                <input className="amount percent" inputMode="decimal" value={values[row.key] ?? ''}
                  aria-label={`Share of ${row.name} in percent`} aria-invalid={messages.length > 0}
                  onChange={(e) => onChange(row.key, e.target.value)} /> %
                {messages.map((m) => <small key={m} className="error" role="alert">{sentence(m)}</small>)}
              </td>
            </tr>
          )
        })}
      </tbody>
      <tfoot>
        <tr>
          <th scope="row">Total</th>
          <td className={`amount nowrap ${total === WHOLE ? '' : 'error'}`} data-testid="share-total">
            {total === null ? '—' : `${basisPointsToPercent(total)} %`}
          </td>
        </tr>
      </tfoot>
    </table>
  )
}

/** The sum of the rows' shares in basis points, or null if a value isn't a percentage. */
export function shareTotal(rows: ShareRow[], values: Record<string, string>): number | null {
  let total = 0
  for (const row of rows) {
    const share = percentToBasisPoints(values[row.key] ?? '')
    if (share === null) return null
    total += share
  }
  return total
}

/** Equal shares as typed values, the larger ones first: for three, 33.34, 33.33 and 33.33. */
export function equalValues(rows: ShareRow[]): Record<string, string> {
  const shares = equalShares(rows.length)
  return Object.fromEntries(rows.map((row, i) => [row.key, basisPointsToPercent(shares[i])]))
}

/**
 * The default split rule (D-12): equal shares, or a percentage per ACTIVE member that sum to exactly 100.00 %.
 * Saving stays disabled until they do; the server's 422 is shown by the member it names. Owners only.
 */
export function FamilySplitRule({ family }: { family: FamilyData }) {
  const active = splitMembers(family.members)
  const rows: ShareRow[] = active.map((m) => ({ key: String(m.id), name: m.displayName }))
  const [rule, setRule] = useState<SplitRule>(family.ledger.splitRule)
  const [values, setValues] = useState<Record<string, string>>(() => family.ledger.splitRule === 'CUSTOM'
    ? Object.fromEntries(active.map((m) => [String(m.id), basisPointsToPercent(m.share ?? 0)]))
    : equalValues(rows))
  const [saved, setSaved] = useState(false)
  const save = useFamilyMutation(family, () => setSaved(true))

  if (!family.owner) {
    return (
      <section>
        {family.ledger.splitRule === 'EQUAL' ? <p>Every member has an equal share.</p> : (
          <table className="shares">
            <tbody>
              {active.map((m) => <tr key={m.id}><th scope="row">{m.displayName}</th><td className="amount nowrap">{shareText(m)}</td></tr>)}
            </tbody>
          </table>
        )}
        <p className="muted">Only an owner changes the split rule.</p>
      </section>
    )
  }

  // A member added or removed since the page opened has no value, or one too many: start from equal shares again.
  const current = rows.every((row) => row.key in values) ? values : { ...equalValues(rows), ...values }
  const total = shareTotal(rows, current)
  const ready = rule === 'EQUAL' || total === WHOLE

  function submit(event: FormEvent) {
    event.preventDefault()
    setSaved(false)
    if (!ready) return
    const shares = rule === 'EQUAL' ? [] : rows.map((row) => ({ memberId: Number(row.key), share: percentToBasisPoints(current[row.key])! }))
    void save.run(() => api(`${family.path}/split-rule`, 'PUT', { rule, shares }))
  }

  // A 422 by the member it names, next to their share; the rest, and any other failure, below the form.
  const unprocessable = save.failure instanceof ApiError && save.failure.violationDetails.length > 0
  const violations = violationsByMember(unprocessable ? (save.failure as ApiError).violationDetails : [])
  const byRow = new Map<string, string[]>()
  const general: (string | undefined)[] = unprocessable ? violations.other : [save.message]
  for (const [id, messages] of violations.byMember) {
    if (rows.some((row) => row.key === String(id))) byRow.set(String(id), messages)
    else general.push(...messages)
  }
  return (
    <section>
      <form onSubmit={submit} className="split-rule">
        <fieldset className="choice">
          <legend>How new family expenses are split by default</legend>
          <label className="check">
            <input type="radio" name="rule" checked={rule === 'EQUAL'} onChange={() => { setRule('EQUAL'); setSaved(false) }} />
            Equal shares
          </label>
          <label className="check">
            <input type="radio" name="rule" checked={rule === 'CUSTOM'} onChange={() => { setRule('CUSTOM'); setSaved(false) }} />
            Custom percentages
          </label>
        </fieldset>
        {rule === 'EQUAL' ? (
          <p className="muted">Every active member gets the same share, whoever joins or leaves.</p>
        ) : (
          <ShareTable rows={rows} values={current} errors={byRow}
            onChange={(key, value) => { setValues({ ...current, [key]: value }); setSaved(false); save.clear() }} />
        )}
        <div className="actions">
          <button className="primary" disabled={!ready || save.pending}>Save</button>
          {!ready && <span className="muted">The shares must add up to exactly 100.00 %.</span>}
        </div>
      </form>
      {saved && <p className="success" role="status">Saved.</p>}
      <Errors messages={general.map((m) => (m ? sentence(m) : undefined))} />
      <p className="muted small">The rule applies to new expenses only; an expense can still be split its own way.</p>
    </section>
  )
}
