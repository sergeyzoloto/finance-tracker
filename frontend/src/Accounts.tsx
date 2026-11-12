import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router'
import {
  api, fieldMessages, formatDate, useApi, useMutation, type Account, type AccountBalance, type AccountType,
  type CounterpartyBalance,
} from './api'
import { accountDays, comparedLines, defaultCompare, totalsOf, type ComparedLine } from './accounts'
import { Amounts, Errors, Loading } from './components'
import { belowMinorUnit, listedInBalances } from './dashboard'
import DateInput from './DateInput'
import { ACCOUNT_TYPES, TYPE_LABELS } from './ledger'
import { useToday } from './me'
import { formatMoney } from './money'

const TYPE_HINTS = {
  ASSET: 'What you have: cash, bank accounts, money lent.',
  LIABILITY: 'What you owe.',
  EQUITY: 'Your own money by purpose, and the ledger’s own accounts.',
}

type Money = { currency: string; amount: string }
const moneyOf = (rows: { currency: string; balance: string }[]): Money[] =>
  rows.map((b) => ({ currency: b.currency, amount: b.balance }))

/**
 * The accounts by type with their balances per currency (rule 4), renamed and archived here. With "Show archived" off,
 * an archived account is still listed while one of its amounts doesn't round to 0 at its currency's minor unit, as the
 * dashboard lists it (D-382, D-364), so that each type's total, which counts every account, matches its rows.
 *
 * UX-11: the balances are on a day, the user's today by default (`on=`), and may be compared with another
 * (`compare=`): the earlier day's balance, the later day's, the change and its percentage, per currency (D-526). The
 * three types are one table, a `tbody` each, so that their columns line up at every width from 40rem (D-525).
 */
export default function Accounts() {
  const [params, setParams] = useSearchParams()
  const today = useToday()
  const { on, compare, days } = accountDays(params, today)
  const comparing = days.length === 2
  const accounts = useApi<Account[]>('/accounts')
  const first = useApi<AccountBalance[]>(`/reports/balances?asOf=${days[0]}`)
  const second = useApi<AccountBalance[]>(comparing ? `/reports/balances?asOf=${days[1]}` : null)
  const [showArchived, setShowArchived] = useState(false)
  const reload = () => { accounts.reload(); first.reload(); if (comparing) second.reload() }

  const setDay = (key: 'on' | 'compare', day: string | null) => {
    const next = new URLSearchParams(params)
    if (day === null || (key === 'on' && day === today)) next.delete(key)
    else next.set(key, day)
    setParams(next)
  }

  const error = accounts.error ?? first.error ?? (comparing ? second.error : undefined)
  const loaded = accounts.data && first.data && (!comparing || second.data)
  const archivedCount = accounts.data?.filter((a) => a.archived).length ?? 0
  const balancesOn = [first.data ?? [], comparing ? second.data ?? [] : []]
  const columns = comparing ? 6 : 3
  return (
    <>
      <div className="page-title">
        <h2>Accounts</h2>
        <label className="check">
          <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
          Show archived{archivedCount > 0 && ` (${archivedCount})`}
        </label>
      </div>
      <div className="controls account-days">
        <label>Balances on <DateInput value={on} onChange={(day) => day && setDay('on', day)} /></label>
        {compare === null ? (
          <button type="button" onClick={() => setDay('compare', defaultCompare(on))}>Compare</button>
        ) : (
          <>
            <label>Compared with <DateInput value={compare} onChange={(day) => day && setDay('compare', day)} /></label>
            <button type="button" onClick={() => setDay('compare', null)}>End the comparison</button>
          </>
        )}
      </div>
      <Errors messages={[error]} />
      {!error && !loaded && <Loading what="accounts" />}
      {accounts.data?.length === 0 && <p className="empty">You have no accounts yet.</p>}
      {loaded && (
        <table className={`accounts ${comparing ? 'compared' : ''} ${first.loading || second.loading ? 'stale' : ''}`}>
          <thead>
            <tr>
              <th>Account</th>
              {comparing ? (
                <>
                  <th className="amount">{formatDate(days[0])}</th>
                  <th className="amount">{formatDate(days[1])}</th>
                  <th className="amount">Change</th>
                  <th className="amount">%</th>
                </>
              ) : <th className="amount">Balance</th>}
              <th><span className="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          {ACCOUNT_TYPES.map((type) => {
            const ownOf = (day: number, id: number) => balancesOn[day].filter((b) => b.accountId === id)
            const ofType = accounts.data!.filter((a) => a.type === type && (showArchived
              || days.some((_, day) => listedInBalances(ownOf(day, a.id), a.archived))))
            if (ofType.length === 0) return null
            const totals = days.map((_, day) => totalsOf(balancesOn[day].filter((b) => b.accountType === type)))
            return (
              <TypeGroup key={type} type={type} columns={columns}>
                {ofType.map((account) => (
                  <AccountRow key={account.id} account={account} onChanged={reload} days={days} columns={columns}
                    balances={days.map((_, day) => ownOf(day, account.id))} />
                ))}
                {totals.some((t) => t.length > 0) && (
                  <tr className="type-total">
                    <th scope="row">Total</th>
                    <Figures amounts={totals} days={days} />
                    <td />
                  </tr>
                )}
              </TypeGroup>
            )
          })}
        </table>
      )}
    </>
  )
}

/**
 * One type's rows: a row for its name and hint, its accounts, its total. The name is a heading, and the group is
 * labelled by it, for a screen reader.
 */
function TypeGroup({ type, columns, children }: { type: AccountType; columns: number; children: ReactNode }) {
  const id = `account-type-${type}`
  return (
    <tbody className="account-type" aria-labelledby={id}>
      <tr className="type-head">
        <th colSpan={columns} scope="rowgroup">
          <h3 id={id}>{TYPE_LABELS[type]}</h3>
          <p className="muted">{TYPE_HINTS[type]}</p>
        </th>
      </tr>
      {children}
    </tbody>
  )
}

/**
 * The amount cells of a row: its balance, or while comparing the earlier day's, the later day's, the change and its
 * percentage, each a line per currency; no colour for better or worse, a change only signed (D-526). Under 40rem each
 * comparison cell names its column (`data-label`), as the headers are hidden there.
 */
function Figures({ amounts, days, dash = false }: { amounts: Money[][]; days: string[]; dash?: boolean }) {
  if (amounts.length === 1) {
    return (
      <td className="amount">
        {amounts[0].length > 0 ? <Amounts amounts={amounts[0]} /> : dash && <span className="muted">—</span>}
      </td>
    )
  }
  const lines = comparedLines(amounts[0], amounts[1])
  const column = (label: string, cell: (line: ComparedLine) => ReactNode, className = 'amount') => (
    <td className={className} data-label={label}>
      {lines.length === 0 ? <span className="muted">—</span>
        : lines.map((line) => <div key={line.currency}>{cell(line)}</div>)}
    </td>
  )
  const money = (amount: string | null, currency: string) =>
    amount === null ? <span className="muted">—</span> : formatMoney(amount, currency)
  return (
    <>
      {column(formatDate(days[0]), (line) => money(line.earlier, line.currency))}
      {column(formatDate(days[1]), (line) => money(line.later, line.currency))}
      {column('Change', (line) => formatMoney(line.change, line.currency, { signed: true }), 'amount change')}
      {column('%', (line) => line.percent, 'amount percent')}
    </>
  )
}

function AccountRow({ account, balances, days, columns, onChanged }: {
  account: Account
  /** The account's balances on each day. */
  balances: AccountBalance[][]
  days: string[]
  columns: number
  onChanged: () => void
}) {
  const [renaming, setRenaming] = useState(false)
  const [details, setDetails] = useState(false)
  const { run, failure, pending, clear } = useMutation(onChanged)

  async function rename(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const name = String(new FormData(event.currentTarget).get('name')).trim()
    if (name === account.name) return setRenaming(false)
    if (await run(() => api(`/accounts/${account.id}`, 'PATCH', { name }))) setRenaming(false)
  }

  function archive(archived: boolean) {
    if (archived && !confirm(`Archive “${account.name}”? It leaves the lists you pick accounts from; its entries stay.`)) return
    void run(() => api(`/accounts/${account.id}`, 'PATCH', { archived }))
  }

  // An archived account shows only its amounts that don't round to 0 at their minor unit (D-382, D-375).
  const shown = balances.map((own) => moneyOf(account.archived
    ? own.filter((b) => !belowMinorUnit(b.balance, b.currency)) : own))
  const nameErrors = fieldMessages(failure, 'name')
  const otherError = failure && nameErrors.length === 0 ? failure.message : undefined
  return (
    <>
      <tr className={account.archived ? 'archived' : ''}>
        <td>
          {renaming ? (
            <form className="inline" onSubmit={rename}>
              <input name="name" defaultValue={account.name} required maxLength={100} autoFocus aria-label="Account name"
                aria-invalid={nameErrors.length > 0} />
              <button className="primary" disabled={pending}>Save</button>
              <button type="button" onClick={() => { setRenaming(false); clear() }}>Cancel</button>
              {nameErrors.map((m) => <small key={m} className="error" role="alert">{m}</small>)}
            </form>
          ) : (
            <>
              <Link to={`/entries?accountId=${account.id}`} title="Show its entries">{account.name}</Link>
              {account.system && <span className="badge">System</span>}
              {account.archived && <span className="badge">Archived</span>}
              {account.requiresCounterparty && <span className="badge">Per person</span>}
            </>
          )}
        </td>
        <Figures amounts={shown} days={days} dash />
        <td className="actions nowrap">
          {account.requiresCounterparty && !account.archived && (
            <button type="button" aria-expanded={details} onClick={() => setDetails(!details)}>By person</button>
          )}
          {!account.system && !renaming && (
            <>
              {!account.archived && <button type="button" onClick={() => { clear(); setRenaming(true) }}>Rename</button>}
              <button type="button" disabled={pending} onClick={() => archive(!account.archived)}>
                {account.archived ? 'Restore' : 'Archive'}
              </button>
            </>
          )}
        </td>
      </tr>
      {otherError && (
        // Such as the refusal to archive an account that still holds money (D-366), across the row under the account.
        <tr className="row-error">
          <td colSpan={columns}><small className="error" role="alert">{otherError}</small></td>
        </tr>
      )}
      {details && (
        <tr className="details">
          <td colSpan={columns}><CounterpartyBalances account={account} days={days} /></td>
        </tr>
      )}
    </>
  )
}

/**
 * An account's balance per counterparty (rule 8), such as who owes how much of the loans given, on each of the page's
 * days, compared as the accounts are.
 */
function CounterpartyBalances({ account, days }: { account: Account; days: string[] }) {
  const path = (day: string) =>
    `/reports/counterparty-balances?accountCode=${encodeURIComponent(account.code)}&asOf=${day}`
  const first = useApi<CounterpartyBalance[]>(path(days[0]))
  const second = useApi<CounterpartyBalance[]>(days.length === 2 ? path(days[1]) : null)
  const error = first.error ?? (days.length === 2 ? second.error : undefined)
  if (error) return <Errors messages={[error]} />
  if (!first.data || (days.length === 2 && !second.data)) return <Loading />
  const each = [first.data, ...(days.length === 2 ? [second.data!] : [])]
  const all = each.flat()
  if (all.length === 0) return <p className="muted">Nothing open with anyone.</p>
  const people = [...new Map(all.map((b) => [b.counterpartyId, b.counterpartyName]))]
  return (
    <table className="nested">
      <tbody>
        {people.map(([id, name]) => (
          <tr key={id}>
            <td><Link to={`/entries?accountId=${account.id}&counterpartyId=${id}`}>{name}</Link></td>
            <Figures amounts={each.map((rows) => moneyOf(rows.filter((b) => b.counterpartyId === id)))} days={days} />
          </tr>
        ))}
      </tbody>
    </table>
  )
}
