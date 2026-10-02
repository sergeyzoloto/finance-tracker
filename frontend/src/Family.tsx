import { useEffect, useState, type FormEvent } from 'react'
import { Link, Navigate, NavLink, Route, Routes, useLocation, useNavigate, useParams } from 'react-router'
import {
  api, fieldMessages, formatDate, sentence, useApi, type FamilyBalances as Balances, type FamilyLedger, type FamilyMember,
  type FamilyRecordPage,
} from './api'
import { Errors, Field, Loading } from './components'
import { ROLE_LABELS } from './family'
import FamilyBalances, { YourBalance } from './FamilyBalances'
import FamilyCategories from './FamilyCategories'
import NewRecord from './FamilyExpenseForm'
import { AddButtons, FamilyRecords, RecordDetail, RecordTable } from './FamilyExpenses'
import FamilyJournal from './FamilyJournal'
import { FamilyMembers, FamilySplitRule } from './FamilyMembers'
import NewSettlement from './FamilySettlement'
import { useFamilyApi, useFamilyMutation, type CreationState, type FamilyData } from './familyData'

/**
 * The pages of one family budget, under `/family/{ledgerId}` (ADR 0003, topic I): overview, activity (expenses, incomes
 * and settlements, under `expenses`, a new expense, a new income under `incomes/new`, and each record under
 * `expenses`), a new settlement under `settle`, balances, journal, members, split rule, categories and settings. A budget the user isn't an ACTIVE member of, or that doesn't exist, answers 404, and so
 * does every request about it once it's gone: then the page says so, and the switcher's list is loaded again.
 *
 * @param onChanged loads the switcher's list again
 * @param onLeft after the reader left the budget: the switcher's list without it at once, then loaded again
 */
export default function Family({ onChanged, onLeft }: { onChanged: () => void; onLeft?: (ledgerId: number) => void }) {
  const { ledgerId = '' } = useParams()
  const valid = /^[1-9]\d{0,17}$/.test(ledgerId)
  const path = `/family-ledgers/${ledgerId}`
  const ledger = useApi<FamilyLedger>(valid ? path : null)
  const members = useApi<FamilyMember[]>(valid ? `${path}/members` : null)
  const location = useLocation()
  const navigate = useNavigate()
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
    left: () => {
      // Away from the budget first, so that the switcher never shows it as an unknown one meanwhile.
      navigate('/', { replace: true })
      if (onLeft) onLeft(Number(ledgerId))
      else onChanged()
    },
  }
  return (
    <>
      <div className="page-title">
        <h2>{family.ledger.name}</h2>
        <span className="muted">Family budget · {family.ledger.baseCurrency} · {ROLE_LABELS[family.ledger.role]}</span>
      </div>
      <nav className="subnav" aria-label="Family budget">
        <NavLink to={family.page} end>Overview</NavLink>
        <NavLink to={`${family.page}/expenses`}>Activity</NavLink>
        <NavLink to={`${family.page}/balances`}>Balances</NavLink>
        <NavLink to={`${family.page}/journal`}>Journal</NavLink>
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
        <Route path="expenses" element={<FamilyRecords family={family} />} />
        <Route path="expenses/new" element={<NewRecord key="expense" family={family} />} />
        <Route path="incomes/new" element={<NewRecord key="income" family={family} type="INCOME" />} />
        <Route path="expenses/:recordId" element={<RecordDetail family={family} />} />
        <Route path="settle" element={<NewSettlement family={family} />} />
        <Route path="balances" element={<FamilyBalances family={family} />} />
        <Route path="journal" element={<FamilyJournal family={family} />} />
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
  const balances = useFamilyApi<Balances>(family, `${family.path}/balances`)
  const records = useFamilyApi<FamilyRecordPage>(family, `${family.path}/records?size=5`)
  return (
    <section>
      <div className="settlement">
        {balances.data ? <YourBalance balances={balances.data} /> : !balances.error && <Loading what="your balance" />}
        <p className="muted small"><Link to={`${family.page}/balances`}>Everyone’s balance</Link></p>
      </div>
      <Errors messages={[balances.error, records.error]} />

      <h3>Latest activity</h3>
      <AddButtons family={family} />
      {records.data && records.data.totalElements === 0 && <p className="empty">Nothing recorded yet.</p>}
      {records.data && records.data.content.length > 0 && (
        <>
          <RecordTable records={records.data.content} family={family} />
          {records.data.totalElements > records.data.content.length && (
            <p><Link to={`${family.page}/expenses`}>All {records.data.totalElements} records</Link></p>
          )}
        </>
      )}

      <dl className="facts">
        <dt>Start date</dt><dd>{formatDate(ledger.startDate)}</dd>
        <dt>Base currency</dt><dd>{ledger.baseCurrency}</dd>
        <dt>Your role</dt><dd>{ROLE_LABELS[ledger.role]}</dd>
        {me && <><dt>Your name in this budget</dt><dd>{me.displayName}</dd></>}
        <dt>Members</dt><dd>{active.length}</dd>
        <dt>Split rule</dt><dd>{ledger.splitRule === 'EQUAL' ? 'Equal shares' : 'Custom percentages'}</dd>
      </dl>
      <p className="muted">
        The other members see this budget’s members, categories, expenses, incomes and settlements, never your personal
        accounts, categories or entries. Your share of each expense and income is posted into your personal ledger.
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
          hint="Shares and balances are kept in it. It can change until the first expense.">
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
