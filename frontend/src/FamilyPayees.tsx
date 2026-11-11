import { useState } from 'react'
import { api, sentence } from './api'
import { Errors } from './components'
import { useFamilyMutation, type FamilyData } from './familyData'
import { SHOW_PAYEES_HINT, SHOW_PAYEES_LABEL, showPayeesQuestion } from './payees'

/**
 * "Your payees" on a family budget's Settings tab (UX-9): whether the other members see the payees of the reader's own
 * family payments. Every ACTIVE member with an account has it for themself, whatever their role; nobody switches it for
 * anyone else. Each change asks first; Cancel changes nothing and sends nothing, and a failure puts the box back.
 * Below it, who shows their payees.
 */
export default function YourPayees({ family }: { family: FamilyData }) {
  const { ledger, members } = family
  const [shown, setShown] = useState(ledger.showsPayees === true)
  const [saved, setSaved] = useState(false)
  const save = useFamilyMutation(family, () => setSaved(true))

  async function change(next: boolean) {
    if (!confirm(showPayeesQuestion(next, ledger.name))) return
    setSaved(false)
    setShown(next)
    const done = await save.run(() => api(`${family.path}/me`, 'PATCH', { showsPayees: next }))
    if (!done) setShown(!next)
  }

  const withAccount = members.filter((m) => m.status === 'ACTIVE' && m.hasAccount)
  return (
    <section className="payees">
      <h3>Your payees</h3>
      <label className="check">
        <input type="checkbox" checked={shown} disabled={save.pending}
          onChange={(e) => void change(e.target.checked)} />
        {' '}{SHOW_PAYEES_LABEL}
      </label>
      <p className="muted small">{SHOW_PAYEES_HINT}</p>
      {saved && <p className="success" role="status">Saved.</p>}
      <Errors messages={[save.failure ? sentence(save.message ?? save.failure.message) : undefined]} />
      <h4>Who shows payees</h4>
      <ul className="plain">
        {withAccount.map((m) => (
          <li key={m.id}>
            {m.displayName}{m.id === ledger.memberId ? ' (you)' : ''}: {m.showsPayees ? 'Shown' : 'Hidden'}
          </li>
        ))}
      </ul>
    </section>
  )
}
