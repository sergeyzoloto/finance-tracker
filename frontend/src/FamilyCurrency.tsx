import { useApi, type FamilyConversion } from './api'
import { CurrencyInput, Field } from './components'
import { baseAmount, conversionPath, missingRate, rateLine, type BaseAmount } from './currency'
import { fromMinor } from './minorUnits'

// The base amount of a family record in another currency (F4e, D-13): the server converts the amount as it would for
// the reader, with the ECB's rate or their own, and the form shows it with its rate and source, editable.

/**
 * The base amount of the amount the form holds: the amount itself in the base currency, the one the user typed, or the
 * server's conversion (GET /conversion), which a change of the amount, the currency or the date asks for again.
 */
export function useBaseAmount(familyPath: string, currency: string, baseCurrency: string, amount: bigint | undefined,
  date: string, entered: string | undefined): BaseAmount {
  const typed = entered !== undefined && entered.trim() !== ''
  const conversion = useApi<FamilyConversion>(typed ? null : conversionPath(familyPath, currency, baseCurrency, amount, date))
  return baseAmount({ currency, baseCurrency, amount, entered, conversion: conversion.data, loading: conversion.loading })
}

/**
 * The amount in the base currency, for an amount in another one: the conversion, shown with its rate and source, which
 * the user may overwrite; without a rate, a line that says why it is needed. Nothing in the base currency.
 *
 * @param entered what the user typed; undefined while the conversion stands
 * @param errors the server's objections to it, RATE_MISSING among them
 */
export function BaseAmountField({ base, currency, baseCurrency, date, entered, onEntered, errors }: {
  base: BaseAmount
  currency: string
  baseCurrency: string
  date: string
  entered: string | undefined
  onEntered: (text: string | undefined) => void
  errors: string[]
}) {
  if (currency === baseCurrency) return null
  const typed = entered !== undefined
  const shown = (base.state === 'RATE' || base.state === 'KEPT') && base.minor !== undefined
    ? fromMinor(base.minor, baseCurrency) : ''
  const line = base.state === 'KEPT' ? base.line
    : base.state === 'RATE' && base.conversion ? rateLine(base.conversion)
    : base.state === 'MISSING' ? missingRate(currency, baseCurrency, date)
      : base.state === 'PENDING' ? 'Converting…' : undefined
  return (
    <Field label={`Amount in ${baseCurrency}`} errors={[...(base.problem ? [base.problem] : []), ...errors]}
      hint={(
        <>
          {typed ? `Entered by you; the family budget counts it in ${baseCurrency}. ` : line}
          {typed && (
            <button type="button" className="link" onClick={() => onEntered(undefined)}>Use the exchange rate</button>
          )}
        </>
      )}>
      <input className="amount" inputMode="decimal" autoComplete="off" value={typed ? entered : shown}
        placeholder={base.state === 'MISSING' ? '0.00' : ''} onChange={(e) => onEntered(e.target.value)} />
    </Field>
  )
}

/** The currency of an amount whose account doesn't decide it: a three-letter code, suggesting the usual ones. */
export function CurrencyField({ value, onChange, suggestions, errors, label = 'Currency' }: {
  value: string
  onChange: (code: string) => void
  suggestions: string[]
  errors: string[]
  label?: string
}) {
  const problem = /^[A-Z]{3}$/.test(value) ? [] : ['Enter a three-letter currency code, such as USD.']
  return (
    <Field label={label} errors={[...problem, ...errors]} className="narrow">
      <CurrencyInput currencies={[...new Set(suggestions)]} value={value} onChange={onChange} />
    </Field>
  )
}

/** The currencies a form suggests: the base currency, the reader's accounts' currencies, and a few common ones. */
export function currencySuggestions(baseCurrency: string, accountCurrencies: (string | null)[]): string[] {
  return [...new Set([baseCurrency, ...accountCurrencies.filter((c): c is string => c !== null), 'EUR', 'USD', 'GBP',
    'CHF', 'RUB', 'JPY'])]
}
