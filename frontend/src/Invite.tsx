import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import { api, ApiError, errorMessage, fieldMessages, formatDate, sentence, type FamilyLedger, type InviteLookup } from './api'
import { Errors, Field, Loading } from './components'
import { balanceSentence } from './family'
import { clearPendingInvite, pendingInvite } from './invite'
import { abs, formatMoney, signOf } from './money'

/** What the page shows instead of the invite: the server's answer, and whether the token is spent. */
interface Problem { message: string; again?: boolean }

const TOO_MANY = 'Too many attempts with invite links. Try again in a few minutes.'

/**
 * An invite to a family budget (F5; D-17, D-18, D-11), at /invite. The token came from the link's fragment and waits
 * in localStorage (invite.ts); the page asks the API what the invite is, says who invites, to which budget and place,
 * from when the user's shares appear in their personal budget, what the other members will and won't see, and what
 * becomes of the user's categories; then the user accepts with the name the others will see and the categories they
 * bring, or declines. After any outcome the token is erased: accepted, declined, invalid or expired, or one the user
 * can't use (409). A 429 keeps it, to try again later.
 *
 * @param onJoined loads the switcher's list again
 */
export default function InvitePage({ onJoined }: { onJoined: () => void }) {
  const navigate = useNavigate()
  const [token] = useState(() => pendingInvite())
  const [lookup, setLookup] = useState<InviteLookup>()
  const [problem, setProblem] = useState<Problem>()
  const [declined, setDeclined] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (token === null) return
    let current = true
    setProblem(undefined)
    api<InviteLookup>('/invites/lookup', 'POST', { token }).then(
      (answer) => { if (current) setLookup(answer) },
      (e) => { if (current) setProblem(problemOf(e)) },
    )
    return () => { current = false }
  }, [token, attempt])

  if (token === null && !declined) {
    return (
      <Page title="No invite to open">
        <p>Open the invite link you got again. It works once, until it expires, and only after you sign in.</p>
      </Page>
    )
  }
  if (declined) {
    return <Page title="Invite declined"><p>You declined the invite. Its link doesn’t work any more.</p></Page>
  }
  if (problem) {
    return (
      <Page title={problem.again ? 'Try again later' : 'This invite can’t be used'}>
        <p className={problem.again ? undefined : 'error'} role="alert">{sentence(problem.message)}</p>
        {problem.again && <p><button onClick={() => setAttempt((n) => n + 1)}>Try again</button></p>}
      </Page>
    )
  }
  if (!lookup) return <Loading what="the invite" />
  return (
    <Acceptance token={token!} lookup={lookup} onProblem={setProblem} onDeclined={() => setDeclined(true)}
      onJoined={(ledger) => { onJoined(); navigate(`/family/${ledger.id}`, { replace: true }) }} />
  )
}

function Acceptance({ token, lookup, onProblem, onDeclined, onJoined }: {
  token: string
  lookup: InviteLookup
  onProblem: (problem: Problem) => void
  onDeclined: () => void
  onJoined: (ledger: FamilyLedger) => void
}) {
  const [name, setName] = useState(lookup.displayName ?? '')
  const [bring, setBring] = useState<Set<number>>(new Set())
  const [failure, setFailure] = useState<Error>()
  const [pending, setPending] = useState(false)
  const claim = lookup.kind === 'CLAIM'

  async function accept(event: FormEvent) {
    event.preventDefault()
    setPending(true)
    setFailure(undefined)
    try {
      const ledger = await api<FamilyLedger>('/invites/accept', 'POST',
        { token, displayName: name.trim(), categoryIds: [...bring] })
      clearPendingInvite()
      onJoined(ledger)
    } catch (e) {
      // A 409 is the name, which the user can change, unless the invite can't be used any more: ask again.
      if (e instanceof ApiError && e.status === 409) {
        try {
          await api<InviteLookup>('/invites/lookup', 'POST', { token })
          setFailure(e)
        } catch (again) {
          onProblem(problemOf(again))
        }
      } else if (e instanceof ApiError && (e.status === 404 || e.status === 429)) {
        onProblem(problemOf(e))
      } else {
        setFailure(e instanceof Error ? e : new Error(errorMessage(e)))
      }
    } finally {
      setPending(false)
    }
  }

  async function decline() {
    if (!confirm(`Decline the invite to the family budget “${lookup.ledgerName}”? Its link stops working.`)) return
    setPending(true)
    try {
      await api('/invites/decline', 'POST', { token })
      clearPendingInvite()
      onDeclined()
    } catch (e) {
      if (e instanceof ApiError && [404, 409, 429].includes(e.status)) onProblem(problemOf(e))
      else setFailure(e instanceof Error ? e : new Error(errorMessage(e)))
    } finally {
      setPending(false)
    }
  }

  const nameErrors = [...fieldMessages(failure, 'displayName'),
    ...(failure instanceof ApiError && failure.status === 409 ? [sentence(failure.message)] : [])]
  const others = failure instanceof ApiError && (failure.status === 409 || failure.errors.length > 0) ? undefined
    : failure && (failure instanceof ApiError && failure.violations.length > 0
      ? failure.violations.map(sentence).join(' ') : sentence(failure.message))
  const groups = (['EXPENSE', 'INCOME'] as const).map((type) => ({
    type, title: type === 'EXPENSE' ? 'Expense categories' : 'Income categories',
    categories: lookup.mayBring.filter((c) => c.type === type),
  })).filter((g) => g.categories.length > 0)
  return (
    <section className="invite">
      <h2>Join the family budget “{lookup.ledgerName}”</h2>
      <p>
        {lookup.invitedBy} invites you to the family budget “{lookup.ledgerName}”, kept in {lookup.baseCurrency}
        {claim ? <>, to take the place of <strong>{lookup.seatName}</strong>.</>
          : lookup.returning ? ', where you were a member before: you come back in your earlier place.' : ', as a new member.'}
      </p>
      {claim && lookup.openingBalance != null && (
        <p data-testid="opening-balance">
          Before {formatDate(lookup.joinDate)}, <strong>{balanceSentence(lookup.openingBalance, lookup.baseCurrency,
            lookup.seatName)}</strong>. That becomes your opening balance in the family budget, and in your personal
          budget on that day.
        </p>
      )}
      {lookup.returning && lookup.correction != null && (
        <p data-testid="correction">
          {signOf(lookup.correction) === 0
            ? 'Your personal budget already shows your balance with the family budget as it is: no correction is needed.'
            : <>One correction of <strong>{formatMoney(abs(lookup.correction), lookup.baseCurrency)}</strong> on that day
              {signOf(lookup.correction) > 0 ? ' adds to' : ' takes from'} what your personal budget shows you owe the family
              budget, so that it matches the family budget again: for example, for entries of it you changed or deleted
              since you left, or records of before that changed meanwhile.</>}
        </p>
      )}
      {claim && (
        <p>
          The expenses, incomes and settlements recorded for {lookup.seatName} become yours, and so does what
          {' '}{lookup.seatName} owes the others or is owed.
        </p>
      )}
      <p>
        From <strong>{formatDate(lookup.joinDate)}</strong>, your share of each family expense and income appears in your
        personal budget.
        {claim && <> What {lookup.seatName} paid, received or settled from that day appears there too, under “Payments
          without a specified account”, for you to put on your own accounts; what {lookup.seatName} owed or was owed
          before that day arrives as one opening balance on that day.</>}
      </p>
      <p className="muted small">This invite works until {new Date(lookup.expiresAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}.</p>

      <div className="choices">
        <div className="choice">
          <h3>What the other members will see</h3>
          <ul>
            <li>Your name in this budget, which you choose below and can change later</li>
            <li>The family’s expenses, incomes and settlements, with their amounts, shares, categories and comments</li>
            <li>Everyone’s balance in the budget, and the journal of who changed what</li>
          </ul>
        </div>
        <div className="choice">
          <h3>What they never see</h3>
          <ul>
            <li>Your accounts, and which one you paid with</li>
            <li>Your personal categories and entries</li>
            <li>Your private notes, your email address and your account</li>
          </ul>
        </div>
      </div>

      <form onSubmit={accept}>
        <Field label="Your name in this budget" errors={nameErrors}
          hint="The other members see it. It must differ from the other members’ names.">
          <input value={name} required maxLength={100} onChange={(e) => { setName(e.target.value); setFailure(undefined) }}
            aria-invalid={nameErrors.length > 0} />
        </Field>

        <fieldset className="section">
          <legend>Your categories</legend>
          {lookup.merges.length > 0 && (
            <>
              <p>These of yours become the family’s categories with the same code; the family’s names stay, and your
                entries move to them:</p>
              <ul className="merges">
                {lookup.merges.map((m) => (
                  <li key={m.categoryId}>
                    {m.name === m.familyName ? <>“{m.name}”</> : <>Your “{m.name}” becomes “{m.familyName}”</>}
                  </li>
                ))}
              </ul>
            </>
          )}
          {lookup.keptPrivate.length > 0 && (
            <>
              <p>These stay private: the family has a category with the same code, of the other type.</p>
              <ul className="merges">
                {lookup.keptPrivate.map((k) => <li key={k.categoryId}>“{k.name}”</li>)}
              </ul>
            </>
          )}
          {groups.length > 0 ? (
            <>
              <p>You may bring more of yours into the family’s categories; the rest stay private, and only you see them.</p>
              <div className="category-groups">
                {groups.map((g) => (
                  <div key={g.type} className="category-group">
                    <h3>{g.title}</h3>
                    {g.categories.map((c) => (
                      <label key={c.categoryId} className="check">
                        <input type="checkbox" checked={bring.has(c.categoryId)} onChange={(e) => {
                          const next = new Set(bring)
                          if (e.target.checked) next.add(c.categoryId)
                          else next.delete(c.categoryId)
                          setBring(next)
                        }} />
                        {c.name}
                      </label>
                    ))}
                  </div>
                ))}
              </div>
            </>
          ) : <p className="muted">You have no other categories to bring.</p>}
          {lookup.categories.length > 0 && (
            <p className="muted small">The family’s categories: {lookup.categories.map((c) => c.name).join(', ')}.</p>
          )}
        </fieldset>

        <div className="actions">
          <button className="primary" disabled={pending || name.trim() === ''}>Accept</button>
          <button type="button" disabled={pending} onClick={() => void decline()}>Decline</button>
        </div>
        <Errors messages={[others]} />
      </form>
    </section>
  )
}

function Page({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h2>{title}</h2>
      {children}
      <p><Link className="button primary" to="/">Back to Personal</Link></p>
    </section>
  )
}

/** The page's answer to a failed call: a 429 keeps the token to try again; any other spends it. */
function problemOf(e: unknown): Problem {
  if (e instanceof ApiError && e.status === 429) return { message: TOO_MANY, again: true }
  if (e instanceof ApiError && (e.status === 404 || e.status === 409)) {
    clearPendingInvite()
    return { message: e.message }
  }
  return { message: errorMessage(e), again: true }
}
