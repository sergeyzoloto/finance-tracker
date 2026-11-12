import { Component, useId, type ReactNode } from 'react'
import type { Account, Category, CategoryType, Counterparty } from './api'
import { ACCOUNT_TYPES, categoryLabel, TYPE_LABELS } from './ledger'
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

/**
 * Shows `fallback` in place of children that failed to render, such as a lazily loaded chunk that failed to load. A
 * change of `resetKey`, such as the page's address, tries the children again.
 */
export class ErrorBoundary extends Component<{ fallback: ReactNode; children: ReactNode; resetKey?: unknown },
  { failed: boolean; key: unknown }> {
  state = { failed: false, key: this.props.resetKey }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  static getDerivedStateFromProps(props: { resetKey?: unknown }, state: { key: unknown }) {
    return props.resetKey === state.key ? null : { failed: false, key: props.resetKey }
  }

  render() {
    return this.state.failed ? this.props.fallback : this.props.children
  }
}

/**
 * A page or a widget that may fail on an answer it can't read (F8c): in its place a short error with a link that reloads
 * the page, and never a blank screen. `what` completes "Couldn't show …".
 */
export function Safe({ what, resetKey, children }: { what: string; resetKey?: unknown; children: ReactNode }) {
  return (
    <ErrorBoundary resetKey={resetKey} fallback={
      <p className="error" role="alert">Couldn’t show {what}. <a href={window.location.href}>Reload the page</a>.</p>}>
      {children}
    </ErrorBoundary>
  )
}

export const Loading = ({ what = '' }: { what?: string }) => <p className="muted" aria-busy="true">Loading{what && ` ${what}`}…</p>

/**
 * Accounts grouped by type. `accounts` are the choices; archived ones among them are marked. `children` are further
 * options after them, such as "Specify later".
 */
export function AccountSelect({ accounts, value, onChange, placeholder = 'Choose an account', children, ...rest }: {
  accounts: Account[]
  value: string
  onChange: (id: string) => void
  placeholder?: string
  'aria-label'?: string
  name?: string
  children?: ReactNode
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
      {children}
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
  const option = (c: Category) => <option key={c.id} value={c.id}>{categoryLabel(c)}{c.archived ? ' (archived)' : ''}</option>
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
  required?: boolean
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

/**
 * A row of toggle buttons for one choice (the dashboard's period, currency and scope): a group with a visible label,
 * each button `aria-pressed`, the chosen one pressed and none while nothing is (a custom period). They wrap on a narrow
 * screen.
 */
export function ToggleGroup<T extends string>({ label, options, value, onChange }: {
  label: string
  options: { value: T; label: ReactNode }[]
  value: T | null
  onChange: (value: T) => void
}) {
  const id = useId()
  return (
    <div className="toggle-group">
      <span id={id} className="toggle-label">{label}</span>
      <div role="group" aria-labelledby={id} className="toggles">
        {options.map((option) => (
          <button key={option.value} type="button" aria-pressed={option.value === value}
            onClick={() => onChange(option.value)}>{option.label}</button>
        ))}
      </div>
    </div>
  )
}

/** The shared icon: two people (D-419). Named where it stands alone, decorative beside a name (the header, D-498). */
export function SharedIcon({ label }: { label?: string }) {
  return (
    <svg role={label ? 'img' : undefined} aria-label={label} aria-hidden={label ? undefined : true}
      focusable="false" viewBox="0 0 16 16" width="1em" height="1em" fill="currentColor">
      {label && <title>{label}</title>}
      <circle cx="5.5" cy="4.75" r="2.4" />
      <path d="M1 13.5c0-2.7 2-4.6 4.5-4.6s4.5 1.9 4.5 4.6z" />
      <circle cx="11.25" cy="5.5" r="2" />
      <path d="M10.4 9.1a4 4 0 0 1 .85-.1c2.1 0 3.75 1.7 3.75 4.1v.4h-3.9c0-1.75-.25-3.2-.7-4.4z" />
    </svg>
  )
}
