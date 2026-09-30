import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import {
  api, errorMessage, fieldMessages, sentence, useApi, useMutation, type Account, type Category, type CategoryType,
  type FamilyLedger, type FamilyMember, type Me, type Settings, type SplitRule,
} from './api'
import { percentToBasisPoints, WHOLE } from './basisPoints'
import { CurrencyInput, Errors, Field, Loading } from './components'
import type { CreationState } from './familyData'
import { familyMessage } from './family'
import { equalValues, ShareTable, shareTotal, type ShareRow } from './FamilyMembers'

const GROUPS: { type: CategoryType; title: string }[] = [
  { type: 'EXPENSE', title: 'Expenses' },
  { type: 'INCOME', title: 'Income' },
]

/** The creator's row in the table of shares. */
const ME = 'me'

/**
 * Creating a family budget (A1, D-11, D-12, B1): its name, base currency and the creator's name in it, the personal
 * categories to bring, members without an account, and the split rule. The calls go one after the other: the budget
 * (POST), then each member, then a custom split rule. If a later call fails, the budget exists already, so its page
 * opens and says what failed.
 *
 * @param onCreated loads the switcher's list again
 */
export default function NewFamily({ me, onCreated }: { me: Me; onCreated: () => void }) {
  const navigate = useNavigate()
  const settings = useApi<Settings>('/settings')
  const accounts = useApi<Account[]>('/accounts')
  const categories = useApi<Category[]>('/categories')

  const [name, setName] = useState('')
  const [currency, setCurrency] = useState<string>()
  const [displayName, setDisplayName] = useState(me.name)
  const [chosen, setChosen] = useState<Set<number>>(new Set())
  const [members, setMembers] = useState<ShareRow[]>([])
  const [memberName, setMemberName] = useState('')
  const [nextKey, setNextKey] = useState(1)
  const [rule, setRule] = useState<SplitRule>('EQUAL')
  const [shares, setShares] = useState<Record<string, string>>({})
  const creation = useMutation()

  const baseCurrency = currency ?? settings.data?.baseCurrency ?? ''
  const currencies = [...new Set([settings.data?.baseCurrency, ...(accounts.data ?? []).map((a) => a.defaultCurrency)]
    .filter((c): c is string => !!c))].sort()
  const rows: ShareRow[] = [{ key: ME, name: displayName.trim() || 'You' }, ...members]
  const shareValues = rows.every((row) => row.key in shares) ? shares : { ...equalValues(rows), ...shares }
  const total = shareTotal(rows, shareValues)

  const taken = (candidate: string) => [displayName, ...members.map((m) => m.name)]
    .some((n) => n.trim().toLocaleLowerCase() === candidate.trim().toLocaleLowerCase())
  const memberProblem = memberName.trim() !== '' && taken(memberName)
    ? 'Everyone in a family budget needs a name of their own.' : undefined
  const ready = name.trim() !== '' && /^[A-Z]{3}$/.test(baseCurrency) && displayName.trim() !== ''
    && (rule === 'EQUAL' || total === WHOLE)

  function addMember() {
    if (memberName.trim() === '' || memberProblem) return
    setMembers([...members, { key: `m${nextKey}`, name: memberName.trim() }])
    setNextKey(nextKey + 1)
    setMemberName('')
    setShares({})
  }

  function removeMember(key: string) {
    setMembers(members.filter((m) => m.key !== key))
    setShares({})
  }

  function toggle(ids: number[], on: boolean) {
    const next = new Set(chosen)
    ids.forEach((id) => (on ? next.add(id) : next.delete(id)))
    setChosen(next)
  }

  async function create() {
    const created = await api<FamilyLedger>('/family-ledgers', 'POST', {
      name: name.trim(), baseCurrency, displayName: displayName.trim(), categoryIds: [...chosen],
    })
    // From here on the budget exists: what fails is said on its page rather than here.
    const problems: string[] = []
    const memberIds = new Map([[ME, created.memberId]])
    for (const member of members) {
      try {
        const added = await api<FamilyMember>(`/family-ledgers/${created.id}/members`, 'POST', { displayName: member.name })
        memberIds.set(member.key, added.id)
      } catch (e) {
        problems.push(`${member.name} wasn’t added: ${sentence(familyMessage(errorMessage(e)))}`)
      }
    }
    if (rule === 'CUSTOM' && problems.length > 0) {
      problems.push('The split rule stayed equal, since not every member was added. Add them on the Members page, '
        + 'then set the percentages on the Split rule page.')
    } else if (rule === 'CUSTOM') {
      try {
        await api(`/family-ledgers/${created.id}/split-rule`, 'PUT', {
          rule: 'CUSTOM',
          shares: rows.map((row) => ({ memberId: memberIds.get(row.key), share: percentToBasisPoints(shareValues[row.key]) })),
        })
      } catch (e) {
        problems.push(`The split rule stayed equal: ${sentence(familyMessage(errorMessage(e)))} Set it on the Split rule page.`)
      }
    }
    onCreated()
    const state: CreationState | undefined = problems.length > 0 ? { creationProblems: problems } : undefined
    navigate(`/family/${created.id}`, { state })
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (ready) void creation.run(create)
  }

  const loadError = settings.error ?? accounts.error ?? categories.error
  if (!settings.data || !categories.data) return loadError ? <Errors messages={[loadError]} /> : <Loading />
  const errorsOf = (field: string) => fieldMessages(creation.failure, field)
  const fieldsFailed = ['name', 'baseCurrency', 'displayName'].some((f) => errorsOf(f).length > 0)
  const open = categories.data.filter((c) => !c.archived)
  return (
    <>
      <h2>New family budget</h2>
      <p className="lead">
        A family budget is kept together with the people you share expenses with. You can add them now or later.
      </p>
      <form className="family-form" onSubmit={submit}>
        <div className="fields">
          <Field label="Name" errors={errorsOf('name')}>
            <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={100} placeholder="Our home" />
          </Field>
          <Field label="Base currency" errors={errorsOf('baseCurrency')}
            hint="Shares and balances are kept in it. It can change until the first family record.">
            <CurrencyInput currencies={currencies} value={baseCurrency} onChange={setCurrency} required />
          </Field>
          <Field label="Your name in this budget" errors={errorsOf('displayName')}
            hint="The other members see this name.">
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required maxLength={100} />
          </Field>
        </div>

        <fieldset className="section">
          <legend>Categories to bring</legend>
          <p className="muted">
            Bringing a category puts a copy of its name and code into the family budget; your personal entries don’t
            change.
          </p>
          {open.length === 0 && <p className="empty">You have no categories to bring.</p>}
          <div className="category-groups">
            {GROUPS.map(({ type, title }) => {
              const ofType = open.filter((c) => c.type === type)
              if (ofType.length === 0) return null
              const all = ofType.every((c) => chosen.has(c.id))
              return (
                <div key={type} className="category-group">
                  <div className="page-title">
                    <h3>{title}</h3>
                    <button type="button" aria-label={`${all ? 'Clear' : 'Select all'} ${title.toLowerCase()}`}
                      onClick={() => toggle(ofType.map((c) => c.id), !all)}>
                      {all ? 'Clear' : 'Select all'}
                    </button>
                  </div>
                  {ofType.map((c) => (
                    <label key={c.id} className="check">
                      <input type="checkbox" checked={chosen.has(c.id)} onChange={(e) => toggle([c.id], e.target.checked)} />
                      {c.name}
                    </label>
                  ))}
                </div>
              )
            })}
          </div>
        </fieldset>

        <fieldset className="section">
          <legend>Members without an account (optional)</legend>
          <p className="muted">
            People you keep the budget with who don’t use Finance Tracker, such as your partner. Others join later by
            invitation.
          </p>
          {members.length > 0 && (
            <ul className="plain">
              {members.map((m) => (
                <li key={m.key}>
                  {m.name} <button type="button" aria-label={`Remove ${m.name}`} onClick={() => removeMember(m.key)}>Remove</button>
                </li>
              ))}
            </ul>
          )}
          <div className="add">
            <Field label="Name of a member" errors={memberProblem ? [memberProblem] : []}>
              <input value={memberName} maxLength={100} onChange={(e) => setMemberName(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addMember() } }} />
            </Field>
            <button type="button" onClick={addMember} disabled={memberName.trim() === '' || !!memberProblem}>Add member</button>
          </div>
        </fieldset>

        <fieldset className="section">
          <legend>Split rule</legend>
          <div className="options">
            <label className="check">
              <input type="radio" name="rule" checked={rule === 'EQUAL'} onChange={() => setRule('EQUAL')} />
              Equal shares
            </label>
            <label className="check">
              <input type="radio" name="rule" checked={rule === 'CUSTOM'} onChange={() => setRule('CUSTOM')} />
              Custom percentages
            </label>
          </div>
          {rule === 'CUSTOM' && (
            <ShareTable rows={rows} values={shareValues} onChange={(key, value) => setShares({ ...shareValues, [key]: value })} />
          )}
        </fieldset>

        <div className="actions">
          <button className="primary" disabled={!ready || creation.pending}>
            {creation.pending ? 'Creating…' : 'Create family budget'}
          </button>
          {rule === 'CUSTOM' && total !== WHOLE && <span className="muted">The shares must add up to exactly 100.00 %.</span>}
        </div>
        <Errors messages={[fieldsFailed || !creation.error ? undefined : sentence(familyMessage(creation.error))]} />
      </form>
    </>
  )
}
