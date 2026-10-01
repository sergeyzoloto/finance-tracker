import Big from 'big.js'
import { formatDate, type Account, type FamilyConversion, type FamilyRecord, type RateSourceName } from './api'
import { fromMinor, parseMinor, toMinor } from './minorUnits'
import { formatMoney } from './money'

// Family records in other currencies (F4e, D-13) without React: the currency an amount is in, where its base amount
// comes from, and how a record's two amounts and its rate read. The server's conversion is what counts; this reads it.

/** How a record's or a conversion's base amount was found, in words. */
const SOURCES: Record<RateSourceName, string> = { ECB: 'ECB rate', MANUAL: 'your own rate', ENTERED: 'entered' }

/**
 * The rate as the screens show it: "1 USD = 0.892857 EUR, ECB rate of Sep 10, 2026"; "Entered" for a base amount typed
 * in; undefined in the base currency. The rate is cut to six significant digits, HALF_UP, with big.js.
 */
export function rateLine(r: { currency: string; baseCurrency: string; rate?: string; rateSource?: RateSourceName; rateDate?: string },
  { reader = true } = {}): string | undefined {
  if (r.currency === r.baseCurrency || !r.rateSource) return undefined
  if (r.rateSource === 'ENTERED' || !r.rate) return 'Amount in ' + r.baseCurrency + ' entered by hand'
  const shown = new Big(r.rate).prec(6, Big.roundHalfUp).toString()
  // Another member's manual rate is theirs, not the reader's.
  const source = r.rateSource === 'MANUAL' && !reader ? 'manual rate' : SOURCES[r.rateSource]
  return `1 ${r.currency} = ${shown} ${r.baseCurrency}, ${source}${r.rateDate ? ` of ${formatDate(r.rateDate)}` : ''}`
}

/** A record's rate line, from its answer: its original currency against the base currency. */
export const recordRateLine = (r: FamilyRecord) => rateLine({
  currency: r.originalCurrency, baseCurrency: r.currency, rate: r.rate, rateSource: r.rateSource, rateDate: r.rateDate,
}, { reader: false })

/** A record's amount as the lists show it: "₽9,000.00 → €92.15" in another currency, else the base amount alone. */
export function recordAmount(r: Pick<FamilyRecord, 'amount' | 'currency' | 'originalAmount' | 'originalCurrency'>): string {
  const base = formatMoney(r.amount, r.currency)
  return !r.originalCurrency || r.originalCurrency === r.currency ? base : `${formatMoney(r.originalAmount, r.originalCurrency)} → ${base}`
}

/**
 * The currency of an amount paid from or into the account: its own currency if it has one, else the one chosen, as
 * the paying account's currency decides a record's (D-13). Accounts may hold any currency, so one without a default
 * leaves the choice to the user.
 */
export const accountCurrency = (account: Account | undefined, chosen: string) => account?.defaultCurrency ?? chosen

/** The conversion's path for an amount, or null when there is nothing to convert yet. */
export function conversionPath(familyPath: string, currency: string, baseCurrency: string, amount: bigint | undefined, date: string) {
  if (currency === baseCurrency || amount === undefined || date === '' || !/^[A-Z]{3}$/.test(currency)) return null
  return `${familyPath}/conversion?amount=${fromMinor(amount, currency)}&currency=${currency}&date=${date}`
}

/** A form's base amount: where it comes from, and its value in the base currency's minor unit when there is one. */
export interface BaseAmount {
  /** In the base currency's minor unit; undefined while it can't be known. */
  minor?: bigint
  /**
   * SAME: the amount is in the base currency. RATE: converted. ENTERED: typed in. MISSING: no rate, so it must be typed
   * in. PENDING: converting. KEPT: a stored record's base amount, while nothing it follows from changed.
   */
  state: 'SAME' | 'RATE' | 'ENTERED' | 'MISSING' | 'PENDING' | 'KEPT'
  /** For KEPT: how the stored base amount was found. */
  line?: string
  /** What is wrong with the typed base amount. */
  problem?: string
  /** The conversion it came from, for its rate line. */
  conversion?: FamilyConversion
}

/**
 * The base amount of a form: the amount itself in the base currency; else the typed one, when the user typed one;
 * else the server's conversion of exactly this amount and currency; else MISSING, or PENDING while it converts.
 */
export function baseAmount(args: {
  currency: string; baseCurrency: string; amount: bigint | undefined; entered?: string; conversion?: FamilyConversion; loading?: boolean
}): BaseAmount {
  const { currency, baseCurrency, amount, entered, conversion, loading } = args
  if (currency === baseCurrency) return { minor: amount, state: 'SAME' }
  if (entered !== undefined && entered.trim() !== '') {
    const parsed = parseMinor(entered, baseCurrency)
    return 'minor' in parsed ? { minor: parsed.minor, state: 'ENTERED' } : { state: 'ENTERED', problem: parsed.problem }
  }
  const current = conversion && amount !== undefined && conversion.currency === currency
    && toMinor(conversion.amount, currency) === amount ? conversion : undefined
  if (!current || loading) return { state: 'PENDING' }
  if (current.baseAmount === null) return { state: 'MISSING', conversion: current }
  return { minor: toMinor(current.baseAmount, baseCurrency), state: 'RATE', conversion: current }
}

/** A stored record's base amount, kept while its amount, currency and date stay. */
export const keptBase = (r: FamilyRecord): BaseAmount => ({
  minor: toMinor(r.amount, r.currency), state: 'KEPT', line: recordRateLine(r),
})

/** The line that says why a base amount must be typed in: no rate for the currency on or before the date. */
export const missingRate = (currency: string, baseCurrency: string, date: string) =>
  `There is no exchange rate for ${currency} on or before ${date ? formatDate(date) : 'this date'}: enter the amount in ${baseCurrency}, or add your own rate on the Rates page.`
