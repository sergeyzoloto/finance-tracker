import { Link } from 'react-router'
import type { Account, Rate } from './api'
import { CurrencyInput, Field } from './components'
import { marks, payingCurrency, rateNotes, type PayingSideForm } from './currency'
import { formatMoney } from './money'

// Currencies on the family pages (F8b; D-45, D-47, D-88, D-89): a record's currency, the reader's paying side, and
// D-47's "≈" total in the main currency with its rates.

/** A currency field: a three-letter code, suggesting the usual ones. */
export function CurrencyField({ value, onChange, suggestions, errors, label = 'Currency', hint }: {
  value: string
  onChange: (code: string) => void
  suggestions: string[]
  errors: string[]
  label?: string
  hint?: string
}) {
  const problem = /^[A-Z]{3}$/.test(value) ? [] : ['Enter a three-letter currency code, such as USD.']
  return (
    <Field label={label} errors={[...problem, ...errors]} className="narrow" hint={hint}>
      <CurrencyInput currencies={[...new Set(suggestions)]} value={value} onChange={onChange} />
    </Field>
  )
}

/** The currencies a form suggests: the main currency, the reader's accounts' currencies, and a few common ones. */
export function currencySuggestions(mainCurrency: string, accountCurrencies: (string | null)[]): string[] {
  return [...new Set([mainCurrency, ...accountCurrencies.filter((c): c is string => c !== null), 'EUR', 'USD', 'GBP',
    'CHF', 'RUB', 'JPY'])]
}

/**
 * The reader's own side on their account (D-89): the currency the account paid or received in, by default the
 * account's own (or the record's for an account without one), which an account holds any number of; and, when that
 * isn't the record's, what went from or into it. Only the reader sees either (D-88).
 *
 * @param kept the paying currency the side is in now, while it stays on the same account
 * @param keptAmount what the side holds now in `kept`, shown until something makes it be asked again
 */
export function PayingSideFields({ account, recordCurrency, side, onChange, way, errors, suggestions, kept, keptAmount }: {
  account: Account | undefined
  recordCurrency: string
  side: PayingSideForm
  onChange: (side: PayingSideForm) => void
  /** "paid" or "received" */
  way: 'paid' | 'received'
  errors: { currency: string[]; amount: string[] }
  suggestions: string[]
  kept?: string
  keptAmount?: string
}) {
  const paying = payingCurrency(account, recordCurrency, side.currency, kept)
  const other = paying !== recordCurrency
  return (
    <>
      <CurrencyField label={way === 'paid' ? 'Paid in' : 'Received in'} value={paying} errors={errors.currency}
        suggestions={[recordCurrency, ...suggestions]}
        hint={`The currency that went ${way === 'paid' ? 'from' : 'into'} the account. Only you see it.`}
        onChange={(code) => onChange({ currency: code, amountText: '' })} />
      {other && (
        <Field label={`Amount ${way} in ${paying}`} errors={errors.amount}
          hint={`What went ${way === 'paid' ? 'from' : 'into'} your account, in ${paying}. Only you see it; the difference goes through your currency exchange.`}>
          <input className="amount" inputMode="decimal" autoComplete="off" value={side.amountText}
            placeholder={keptAmount && paying === kept ? keptAmount : '0.00'}
            onChange={(e) => onChange({ ...side, currency: side.currency ?? paying, amountText: e.target.value })} />
        </Field>
      )}
    </>
  )
}

/**
 * D-47's total as a line: "≈ €209.09", with the rates' dates and sources, and "manual" and "rate stale" where they
 * apply; or, without a rate for a currency, "No RUB rate" with a link to the rates page.
 */
export function TotalAmount({ amount, currency, rates, missing }: {
  amount: string | undefined
  currency: string
  rates: Rate[]
  missing: string[]
}) {
  if (missing.length > 0) return <NoRate currencies={missing} />
  if (amount === undefined) return null
  const { manual, stale } = marks(rates)
  return (
    <span className="total-amount">
      {rates.length > 0 ? '≈ ' : ''}{formatMoney(amount, currency)}
      {manual && <span className="badge">manual</span>}
      {stale && <span className="badge warning">rate stale</span>}
    </span>
  )
}

/** The rates a total used, one line each, under it. */
export function RateNotes({ rates }: { rates: Rate[] }) {
  if (rates.length === 0) return null
  return (
    <ul className="rate-notes small muted">
      {rateNotes(rates).map((note) => <li key={note}>{note}</li>)}
    </ul>
  )
}

/** "No RUB rate": no total, and where to enter one (D-47). */
export function NoRate({ currencies }: { currencies: string[] }) {
  return (
    <span className="no-rate">
      {currencies.map((c) => `No ${c} rate`).join(', ')}: <Link to="/rates">enter a rate</Link>
    </span>
  )
}
