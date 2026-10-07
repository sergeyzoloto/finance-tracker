import Big from 'big.js'
import { formatDate, type Account, type DisplayRate } from './api'
import { fromMinor, parseMinor } from './minorUnits'
import { formatMoney, negate } from './money'

// Currencies in a family budget without React (F8b; D-45, D-47, D-49, D-87, D-88, D-89). A record keeps its own
// currency, and no rate ever touches it: the paying side names what went from or into the account when it was paid in
// another currency. Rates appear only in displayed totals, by the reader's own rates; the server's figures count.

/**
 * The paying currency of a side on an account (D-89): the one chosen; else, on the account the side is on, the one it
 * is in; else the account's default currency; else the record's.
 */
export function payingCurrency(account: Account | undefined, recordCurrency: string, chosen?: string, kept?: string): string {
  if (chosen) return chosen
  if (kept) return kept
  return account?.defaultCurrency ?? recordCurrency
}

/** The reader's paying side as a form holds it. */
export interface PayingSideForm {
  /** The paying currency chosen; undefined for the default. */
  currency?: string
  /** What went from or into the account, as typed; '' for none. */
  amountText: string
}

export const newPayingSide = (): PayingSideForm => ({ amountText: '' })

/**
 * What a paying side adds to a request (D-89): `accountCurrency` whenever an account is named, and `accountAmount` when
 * that currency isn't the record's and the amount is needed or typed; or why it can't be sent.
 *
 * @param needed whether the amount must be named: a new side, or a side whose record amount or currency, account or
 *        paying currency changes; otherwise a typed amount is sent, an empty one keeps what the side holds
 * @param serverDefault the paying currency the server takes when none is named (D-89); `accountCurrency` is sent only
 *        when the paying currency differs from it
 */
export function payingSideRequest(paying: string, recordCurrency: string, side: PayingSideForm, needed: boolean,
  serverDefault?: string): { request: { accountCurrency?: string; accountAmount?: string } } | { problem: string } {
  if (!/^[A-Z]{3}$/.test(paying)) return { problem: 'Enter a three-letter currency code, such as USD.' }
  const named = paying === serverDefault ? {} : { accountCurrency: paying }
  if (paying === recordCurrency) return { request: named }
  if (side.amountText.trim() === '') {
    return needed ? { problem: `Enter the amount in ${paying}.` } : { request: named }
  }
  const parsed = parseMinor(side.amountText, paying)
  if (!('minor' in parsed)) return { problem: parsed.problem }
  return { request: { ...named, accountAmount: fromMinor(parsed.minor, paying) } }
}

/** The paying currency the server takes for a side on the account when none is named (D-89). */
export const serverDefault = (account: Account | undefined, recordCurrency: string, kept?: string) =>
  kept ?? account?.defaultCurrency ?? recordCurrency

/**
 * The reader's paying side as a patch (D-89): on an account, its paying currency and, where it isn't the record's, what
 * went from or into it, needed when the record's amount or currency, the account or the paying currency changes, else
 * sent only when typed; nothing when the side doesn't change. Or why it can't be sent.
 */
export function sidePatch(args: {
  account: Account | undefined; currency: string; side: PayingSideForm; kept?: string; moves: boolean; accountChanged: boolean
}): { patch: { accountCurrency?: string; accountAmount?: string } } | { problem: string } {
  const { account, currency, side, kept, moves, accountChanged } = args
  if (!account) return { patch: {} }
  const paying = payingCurrency(account, currency, side.currency, kept)
  const currencyChanged = side.currency !== undefined && side.currency !== kept
  const needed = moves || accountChanged || currencyChanged
  if (!needed && side.amountText.trim() === '') return { patch: {} }
  const request = payingSideRequest(paying, currency, side, needed, serverDefault(account, currency, kept))
  return 'problem' in request ? request : { patch: request.request }
}

/**
 * A record's amount in its own currency, as the lists show it: "$56.00", and "−$12.00" for a refund (D-79), whose
 * amount the api gives as what was refunded.
 */
export const recordAmount = (r: { amount: string; currency: string; refund?: boolean }) =>
  formatMoney(r.refund ? negate(r.amount) : r.amount, r.currency)

/** A rate's source in words: the ECB's, or the reader's own, "manual". */
export const sourceWord = (rate: Pick<DisplayRate, 'source'>) => (rate.source === 'MANUAL' ? 'manual' : 'ECB')

/**
 * The rates a displayed total used (D-47, D-49): "ECB rate of Oct 10, 2026", "manual rate of Aug 1, 2026, rate stale",
 * one per rate, by currency and date.
 */
export function rateNotes(rates: DisplayRate[]): string[] {
  return rates.map((r) => `1 EUR = ${new Big(r.perEuro).toString()} ${r.currency}, ${r.source === 'MANUAL' ? 'manual' : 'ECB'} rate of ${formatDate(r.date)}${r.stale ? ', rate stale' : ''}`)
}

/** Whether any rate of a total is the reader's own, or stale: what the screens mark it with. */
export const marks = (rates: DisplayRate[]) => ({
  manual: rates.some((r) => r.source === 'MANUAL'),
  stale: rates.some((r) => r.stale),
})

/**
 * A manual rate as the rates page takes it (D-49): "1 EUR = 95,50 RUB", the amount with a comma or a dot. Returns the
 * decimal for the API, units of the currency for one euro, or why it isn't one.
 */
export function parseRateInput(text: string): { rate: string } | { problem: string } {
  const cleaned = text.trim().replace(/\s/g, '')
  if (!/^\d+([.,]\d+)?$/.test(cleaned)) return { problem: 'Enter the rate as a number, such as 95,50 or 95.50.' }
  const rate = cleaned.replace(',', '.')
  if (new Big(rate).lte(0)) return { problem: 'The rate must be more than 0.' }
  if ((rate.split('.')[1] ?? '').length > 8) return { problem: 'The rate has at most 8 decimals.' }
  return { rate }
}
