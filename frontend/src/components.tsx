import { useId, type ReactNode } from 'react'
import type { Account, Category, CategoryType, Counterparty } from './api'
import { ACCOUNT_TYPES, TYPE_LABELS } from './ledger'
import { formatMoney } from './money'

/** A labelled form control with its hint and the messages about it, such as the server's validation errors. */
export function Field({ label, errors, hint, children, className }: {
  label: ReactNode
  errors?: string[]
  hint?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <label className={`field ${className ?? ''} ${errors?.length ? 'invalid' : ''}`}>
      <span className="label">{label}</span>
      {children}
      {hint && <small className="hint">{hint}</small>}
      {errors?.map((message) => <small key={message} className="error" role="alert">{message}</small>)}
    </label>
  )
}

/** Messages that belong to no single field. */
export function Errors({ messages }: { messages?: (string | undefined)[] }) {
  const shown = messages?.filter((m): m is string => !!m) ?? []
  if (shown.length === 0) return null
  return <div className="error-box" role="alert">{shown.map((m) => <p key={m}>{m}</p>)}</div>
}

export const Loading = ({ what = '' }: { what?: string }) => <p className="muted" aria-busy="true">Loading{what && ` ${what}`}…</p>

/** Accounts grouped by type. `accounts` are the choices; archived ones among them are marked. */
export function AccountSelect({ accounts, value, onChange, placeholder = 'Choose an account', ...rest }: {
  accounts: Account[]
  value: string
  onChange: (id: string) => void
  placeholder?: string
  'aria-label'?: string
  name?: string
}) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} {...rest}>
      <option value="">{placeholder}</option>
      {ACCOUNT_TYPES.map((type) => {
        const ofType = accounts.filter((a) => a.type === type)
        return ofType.length === 0 ? null : (
          <optgroup key={type} label={TYPE_LABELS[type]}>
            {ofType.map((a) => <option key={a.id} value={a.id}>{a.name}{a.archived ? ' (archived)' : ''}</option>)}
          </optgroup>
        )
      })}
    </select>
  )
}

/** Categories of one type, or all grouped by type; archived ones only if chosen already. */
export function CategorySelect({ categories, type, value, onChange, placeholder = 'Choose a category', ...rest }: {
  categories: Category[]
  type?: CategoryType
  value: string
  onChange: (id: string) => void
  placeholder?: string
  disabled?: boolean
  'aria-label'?: string
  name?: string
}) {
  const shown = categories.filter((c) => (!type || c.type === type) && (!c.archived || String(c.id) === value))
  const option = (c: Category) => <option key={c.id} value={c.id}>{c.name}{c.archived ? ' (archived)' : ''}</option>
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} {...rest}>
      <option value="">{placeholder}</option>
      {type ? shown.map(option) : (['EXPENSE', 'INCOME'] as const).map((t) => (
        <optgroup key={t} label={t === 'EXPENSE' ? 'Expenses' : 'Income'}>
          {shown.filter((c) => c.type === t).map(option)}
        </optgroup>
      ))}
    </select>
  )
}

/**
 * A payee or other counterparty by name, suggesting the known ones as the user types. A name that isn't known yet is
 * fine: it is added when the entry is saved.
 */
export function CounterpartyInput({ counterparties, value, onChange, ...rest }: {
  counterparties: Counterparty[]
  value: string
  onChange: (name: string) => void
  placeholder?: string
  'aria-label'?: string
  name?: string
}) {
  const list = useId()
  return (
    <>
      <input list={list} value={value} onChange={(e) => onChange(e.target.value)} autoComplete="off" maxLength={100} {...rest} />
      <datalist id={list}>
        {counterparties.filter((c) => !c.archived).map((c) => <option key={c.id} value={c.name} />)}
      </datalist>
    </>
  )
}

/** A three-letter currency code, suggesting the user's currencies. */
export function CurrencyInput({ currencies, value, onChange, ...rest }: {
  currencies: string[]
  value: string
  onChange: (code: string) => void
  'aria-label'?: string
  name?: string
}) {
  const list = useId()
  return (
    <>
      <input className="currency" list={list} value={value} maxLength={3} autoComplete="off" spellCheck={false}
        onChange={(e) => onChange(e.target.value.toUpperCase())} {...rest} />
      <datalist id={list}>{currencies.map((c) => <option key={c} value={c} />)}</datalist>
    </>
  )
}

/** Amounts in several currencies, one per line. */
export function Amounts({ amounts, signed = false }: { amounts: { currency: string; amount: string }[]; signed?: boolean }) {
  return <>{amounts.map((m) => <div key={m.currency}>{formatMoney(m.amount, m.currency, { signed })}</div>)}</>
}
