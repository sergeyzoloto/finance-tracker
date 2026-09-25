import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import {
  api, fieldMessages, useApi, useMutation, type Account, type AccountBalance, type CounterpartyBalance,
} from './api'
import { Amounts, Errors, Loading } from './components'
import { ACCOUNT_TYPES, TYPE_LABELS } from './ledger'
import { sum } from './money'

const TYPE_HINTS = {
  ASSET: 'What you have: cash, bank accounts, money lent.',
  LIABILITY: 'What you owe.',
  EQUITY: 'Your own money by purpose, and the ledger’s own accounts.',
}

/** The accounts by type with their balances per currency (rule 4), renamed and archived here. */
export default function Accounts() {
  const accounts = useApi<Account[]>('/accounts')
  const balances = useApi<AccountBalance[]>('/reports/balances')
  const [showArchived, setShowArchived] = useState(false)
  const reload = () => { accounts.reload(); balances.reload() }

  const error = accounts.error ?? balances.error
  const archivedCount = accounts.data?.filter((a) => a.archived).length ?? 0
  return (
    <>
      <div className="page-title">
        <h2>Accounts</h2>
        <label className="check">
          <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
          Show archived{archivedCount > 0 && ` (${archivedCount})`}
        </label>
      </div>
      <Errors messages={[error]} />
      {!error && (!accounts.data || !balances.data) && <Loading what="accounts" />}
      {accounts.data?.length === 0 && <p className="empty">You have no accounts yet.</p>}
      {accounts.data && balances.data && ACCOUNT_TYPES.map((type) => {
        const ofType = accounts.data!.filter((a) => a.type === type && (showArchived || !a.archived))
        if (ofType.length === 0) return null
        const rows = balances.data!.filter((b) => b.accountType === type)
        const currencies = [...new Set(rows.map((b) => b.currency))].sort()
        const totals = currencies.map((currency) => ({
          currency, amount: sum(rows.filter((b) => b.currency === currency).map((b) => b.balance)),
        }))
        return (
          <section key={type}>
            <h3>{TYPE_LABELS[type]}</h3>
            <p className="muted">{TYPE_HINTS[type]}</p>
            <table className="accounts">
              <thead><tr><th>Account</th><th className="amount">Balance</th><th /></tr></thead>
              <tbody>
                {ofType.map((account) => (
                  <AccountRow key={account.id} account={account} onChanged={reload}
                    balances={balances.data!.filter((b) => b.accountId === account.id)} />
                ))}
              </tbody>
              {totals.length > 0 && (
                <tfoot>
                  <tr><th>Total</th><td className="amount"><Amounts amounts={totals} /></td><td /></tr>
                </tfoot>
              )}
            </table>
          </section>
        )
      })}
    </>
  )
}

function AccountRow({ account, balances, onChanged }: {
  account: Account
  balances: AccountBalance[]
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
          {otherError && <div><small className="error" role="alert">{otherError}</small></div>}
        </td>
        <td className="amount">
          {account.archived ? <span className="muted" title="Balances are reported for open accounts only">—</span>
            : balances.length > 0 ? <Amounts amounts={balances.map((b) => ({ currency: b.currency, amount: b.balance }))} />
              : <span className="muted">—</span>}
        </td>
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
      {details && (
        <tr className="details">
          <td colSpan={3}><CounterpartyBalances account={account} /></td>
        </tr>
      )}
    </>
  )
}

/** An account's balance per counterparty (rule 8), such as who owes how much of the loans given. */
function CounterpartyBalances({ account }: { account: Account }) {
  const { data, error } = useApi<CounterpartyBalance[]>(
    `/reports/counterparty-balances?accountCode=${encodeURIComponent(account.code)}`)
  if (error) return <Errors messages={[error]} />
  if (!data) return <Loading />
  if (data.length === 0) return <p className="muted">Nothing open with anyone.</p>
  const people = [...new Map(data.map((b) => [b.counterpartyId, b.counterpartyName]))]
  return (
    <table className="nested">
      <tbody>
        {people.map(([id, name]) => (
          <tr key={id}>
            <td><Link to={`/entries?accountId=${account.id}&counterpartyId=${id}`}>{name}</Link></td>
            <td className="amount">
              <Amounts amounts={data.filter((b) => b.counterpartyId === id).map((b) => ({ currency: b.currency, amount: b.balance }))} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

