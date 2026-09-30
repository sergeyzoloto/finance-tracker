import { parseAmount } from './money'

// Amounts of a family budget in its currency's minor unit, such as cents for EUR, as whole numbers: BigInt, never a
// floating-point number. A share is cut to the minor unit, so these are the numbers D-12's split works with.

// The minor units the backend uses (java.util.Currency, ISO 4217) where they aren't 2.
const DIGITS: Record<string, number> = Object.fromEntries([
  ...['ADP', 'BEF', 'BIF', 'BYB', 'BYR', 'CLP', 'DJF', 'ESP', 'GNF', 'GRD', 'ISK', 'ITL', 'JPY', 'KMF', 'KRW', 'LUF',
    'MGF', 'PTE', 'PYG', 'ROL', 'RWF', 'TPE', 'TRL', 'UGX', 'UYI', 'VND', 'VUV', 'XAF', 'XOF', 'XPF'].map((c) => [c, 0]),
  ...['BHD', 'IQD', 'JOD', 'KWD', 'LYD', 'OMR', 'TND'].map((c) => [c, 3]),
  // CLF, and the codes without a minor unit (gold, special drawing rights, …), which keep the 4 places amounts are
  // stored with (ShareSplit.minorUnit).
  ...['CLF', 'XAG', 'XAU', 'XBA', 'XBB', 'XBC', 'XBD', 'XDR', 'XFO', 'XFU', 'XPD', 'XPT', 'XSU', 'XTS', 'XUA', 'XXX']
    .map((c) => [c, 4]),
])

/** The currency's minor unit as decimal places: 2 for EUR, 0 for JPY, 3 for KWD. */
export const minorUnit = (currency: string) => DIGITS[currency.toUpperCase()] ?? 2

/**
 * A plain decimal string ("10.01", "-5", "12.50") as a whole number of the currency's minor unit (1001 cents), or
 * undefined if it has more decimals than the currency: "0.001" isn't an amount of EUR, nor "1.5" of JPY. Trailing zeros
 * don't count ("12.5000" is 1250 cents), since the API sends amounts with up to four places.
 */
export function toMinor(amount: string, currency: string): bigint | undefined {
  const match = /^(-?)(\d+)(?:\.(\d*))?$/.exec(amount.trim())
  if (!match) return undefined
  const [, sign, integer, fraction = ''] = match
  const digits = minorUnit(currency)
  const significant = fraction.replace(/0+$/, '')
  if (significant.length > digits) return undefined
  const minor = BigInt(integer + significant.padEnd(digits, '0'))
  return sign ? -minor : minor
}

/** A whole number of the minor unit as a plain decimal string with the currency's places: 1001n in EUR → "10.01". */
export function fromMinor(minor: bigint, currency: string): string {
  const digits = minorUnit(currency)
  const text = (minor < 0n ? -minor : minor).toString().padStart(digits + 1, '0')
  const plain = digits === 0 ? text : `${text.slice(0, -digits)}.${text.slice(-digits)}`
  return minor < 0n ? `-${plain}` : plain
}

/**
 * An amount a user typed, in the minor unit, or a message saying what is wrong with it: it is a number above 0 (or 0
 * too, with `zero`) with at most the currency's decimals. Separators are read as parseAmount reads them, so
 * "1.234,50" is 123450 cents.
 */
export function parseMinor(text: string, currency: string, { zero = false } = {}): { minor: bigint } | { problem: string } {
  if (text.trim() === '') return { problem: 'Enter an amount.' }
  const amount = parseAmount(text)
  if (amount === undefined) return { problem: 'Enter a number, such as 12.50.' }
  const minor = toMinor(amount, currency)
  const digits = minorUnit(currency)
  if (minor === undefined) {
    return { problem: digits === 0 ? `${currency} has no decimals.` : `${currency} has at most ${digits} decimals.` }
  }
  if (minor < 0n || (minor === 0n && !zero)) return { problem: zero ? 'Enter 0 or more.' : 'The amount must be above 0.' }
  if (minor >= 10n ** BigInt(15 + digits)) return { problem: 'The amount is too large.' }
  return { minor }
}
