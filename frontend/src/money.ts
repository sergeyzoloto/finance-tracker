import Big from 'big.js'

// Amounts travel as decimal strings, such as "725.55" (the backend's BigDecimal, rule 3), and all arithmetic on them
// happens here. A constructor of our own in strict mode takes only strings and refuses to turn into a JavaScript
// number, so a float can't slip into a calculation unnoticed. Rounding is HALF_UP, as on the backend.
const Decimal = Big()
Decimal.strict = true
Decimal.RM = Big.roundHalfUp
Decimal.DP = 20

/** NUMERIC(19,4): at most 4 decimal places and 15 digits before the point. */
const MAX_DECIMALS = 4
const MAX_INTEGER_DIGITS = 15

/**
 * The amount a user typed, as a plain decimal string ("1234.5"), or undefined if it isn't a number. Spaces are
 * dropped. A comma alone is a decimal comma; with both, the last one is the decimal separator and the other groups
 * thousands, so "1.234,50" and "1,234.50" both read 1234.5. One separator used several times groups thousands.
 */
export function parseAmount(text: string): string | undefined {
  const s = text.trim().replace(/[\s\u00a0\u202f']/g, '').replace(/^\u2212/, '-')
  const last = Math.max(s.lastIndexOf(','), s.lastIndexOf('.'))
  const separator = s[last]
  const other = separator === ',' ? '.' : ','
  const grouping = last >= 0 && !s.includes(other) && s.indexOf(separator) !== last
  const [integer, fraction] = last < 0 || grouping ? [s, undefined] : [s.slice(0, last), s.slice(last + 1)]
  const group = grouping ? separator : other
  if (integer.includes(group) && !new RegExp(`^[+-]?\\d{1,3}(\\${group}\\d{3})+$`).test(integer)) return undefined
  const plain = integer.replaceAll(group, '') + (fraction === undefined ? '' : `.${fraction}`)
  if (!/^[+-]?(\d+\.?\d*|\.\d+)$/.test(plain)) return undefined
  return new Decimal(plain.replace(/^\+/, '')).toFixed()
}

/**
 * What is wrong with a typed amount, or undefined if it is fine. Amounts that users type are positive unless
 * `signed`; a direction such as "refund" is a separate choice.
 */
export function amountProblem(text: string, { signed = false } = {}): string | undefined {
  if (text.trim() === '') return 'Enter an amount.'
  const amount = parseAmount(text)
  if (amount === undefined) return 'Enter a number, such as 12.50.'
  const value = new Decimal(amount)
  if (value.eq('0')) return 'The amount can’t be zero.'
  if (!signed && value.lt('0')) return 'Enter the amount without a minus sign.'
  const [integer, fraction = ''] = value.abs().toFixed().split('.')
  if (fraction.length > MAX_DECIMALS) return `Use at most ${MAX_DECIMALS} decimal places.`
  if (integer.length > MAX_INTEGER_DIGITS) return 'The amount is too large.'
  return undefined
}

export const negate = (amount: string) => new Decimal(amount).neg().toFixed()
export const abs = (amount: string) => new Decimal(amount).abs().toFixed()
export const signOf = (amount: string) => new Decimal(amount).cmp('0')
export const isZero = (amount: string) => new Decimal(amount).eq('0')
export const equal = (a: string, b: string) => new Decimal(a).eq(b)
/** For sorting: negative if a < b, 0 if equal, positive if a > b. */
export const compare = (a: string, b: string) => new Decimal(a).cmp(b)
export const sum = (amounts: string[]) => amounts.reduce((total, a) => total.plus(a), new Decimal('0')).toFixed()

/**
 * A shared expense's two parts (rule 7): of the total T at the other side's share of `percent` %, the other part is
 * round(T × r, 2) and the user's own part is the rest, so the parts always add up to T.
 */
export function splitShared(total: string, percent: string) {
  const other = new Decimal(total).times(ratioFromPercent(percent)).round(2, Big.roundHalfUp)
  return { own: new Decimal(total).minus(other).toFixed(), other: other.toFixed() }
}

/** "50" → "0.5": the share ratio the backend expects. */
export const ratioFromPercent = (percent: string) => new Decimal(percent).div('100').toFixed()
/** "0.5" → "50". */
export const percentFromRatio = (ratio: string) => new Decimal(ratio).times('100').toFixed()

/** What is wrong with a typed share percentage, or undefined: more than 0 and less than 100, 2 decimals at most. */
export function percentProblem(text: string): string | undefined {
  const percent = parseAmount(text)
  if (percent === undefined) return 'Enter a percentage, such as 50.'
  const value = new Decimal(percent)
  if (value.lte('0') || value.gte('100')) return 'Enter more than 0 and less than 100.'
  if ((percent.split('.')[1] ?? '').length > 2) return 'Use at most 2 decimal places.'
  return undefined
}

/**
 * The share percentage a stored shared expense was split with, found from its total and the other side's part, or
 * undefined if no percentage with 2 decimals gives that part. A whole percentage is preferred where one fits.
 */
export function sharePercentOf(total: string, other: string): string | undefined {
  const exact = new Decimal(other).div(total).times('100')
  for (const decimals of [0, 2]) {
    const percent = exact.round(decimals, Big.roundHalfUp).toFixed()
    if (percentProblem(percent) === undefined && equal(splitShared(total, percent).other, other)) return percent
  }
  return undefined
}

/**
 * The amount as a JavaScript number, only to size a mark in a chart, where a float's last digits make no visible
 * difference. Never calculate with it or show it: labels and tooltips format the decimal string.
 */
export const toChartNumber = (amount: string) => Number(amount)

/** How many units of `to` one unit of `from` bought, for showing an exchange rate; 6 significant digits. */
export const rate = (fromAmount: string, toAmount: string) =>
  new Decimal(toAmount).div(fromAmount).prec(6).toFixed()

/**
 * An exchange rate, such as "95.5" or "0.86045", in the user's locale with every decimal it has (up to 8, as the
 * backend stores them).
 */
export const formatRate = (rate: string) =>
  new Intl.NumberFormat(undefined, { maximumFractionDigits: 8 }).format(rate as Intl.StringNumericLiteral)

/**
 * What is wrong with a typed exchange rate, or undefined: more than 0, at most 8 decimal places and 11 digits before
 * the point, as the backend stores rates (NUMERIC(19,8)).
 */
export function rateProblem(text: string): string | undefined {
  if (text.trim() === '') return 'Enter a rate.'
  const rate = parseAmount(text)
  if (rate === undefined) return 'Enter a number, such as 95.50.'
  if (new Decimal(rate).lte('0')) return 'The rate must be more than 0.'
  const [integer, fraction = ''] = new Decimal(rate).toFixed().split('.')
  if (fraction.length > 8) return 'Use at most 8 decimal places.'
  if (integer.length > 11) return 'The rate is too large.'
  return undefined
}

const formats = new Map<string, Intl.NumberFormat>()

function currencyFormat(currency: string, signed: boolean, rounded: boolean) {
  const key = `${currency}:${signed}:${rounded}`
  let format = formats.get(key)
  if (!format) {
    const sign = signed ? 'exceptZero' : 'auto'
    // Amounts have up to 4 decimals (NUMERIC(19,4)); none is hidden unless `rounded`, and the currency's own minimum
    // is kept.
    const decimals = rounded ? {} : { maximumFractionDigits: 4 }
    try {
      format = new Intl.NumberFormat(undefined, { style: 'currency', currency, ...decimals, signDisplay: sign })
    } catch {
      // Not a currency Intl knows: the plain number, followed by the code.
      format = new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: rounded ? 2 : 4, signDisplay: sign })
    }
    formats.set(key, format)
  }
  return format
}

/**
 * The amount in the user's locale and the currency's format, such as "€1,234.50". Intl formats the decimal string
 * itself, so no digit is lost to a float on the way. `signed` shows "+" for amounts above zero. `rounded` shows the
 * currency's usual decimals, rounding half away from zero (HALF_UP), for an amount converted from other currencies,
 * whose further decimals come from exchange rates and mean nothing.
 */
export function formatMoney(amount: string, currency: string, { signed = false, rounded = false } = {}) {
  const format = currencyFormat(currency, signed, rounded)
  const text = format.format(amount as Intl.StringNumericLiteral)
  return format.resolvedOptions().style === 'currency' ? text : `${text} ${currency}`
}
