// Day dates as the user chose to see and type them (D-304): as the browser shows them (null, the default), or one of
// four numeric patterns. The API, the URLs and every value in the app stay ISO dates ("2026-10-31"); only what is shown
// and typed follows the pattern. No React here: `MeProvider` sets the user's choice, and `formatDate` reads it.

export const DATE_FORMATS = ['DD.MM.YYYY', 'DD/MM/YYYY', 'MM/DD/YYYY', 'YYYY-MM-DD'] as const
export type DateFormat = (typeof DATE_FORMATS)[number]

export const isDateFormat = (value: unknown): value is DateFormat =>
  typeof value === 'string' && (DATE_FORMATS as readonly string[]).includes(value)

let chosen: DateFormat | null = null

/** The user's choice, from `/api/me`; null (or anything else) for as the browser shows dates. */
export function setDateFormat(format: unknown) {
  chosen = isDateFormat(format) ? format : null
}

export const dateFormat = () => chosen

type Field = 'day' | 'month' | 'year'

/** A pattern as the order of its fields and the separator between them. */
export interface DatePattern {
  order: Field[]
  separator: string
  /** "DD.MM.YYYY", the field's placeholder. */
  placeholder: string
}

const LETTERS: Record<Field, string> = { day: 'DD', month: 'MM', year: 'YYYY' }

function pattern(order: Field[], separator: string): DatePattern {
  return { order, separator, placeholder: order.map((f) => LETTERS[f]).join(separator) }
}

/**
 * The pattern dates are typed in: the chosen one, or for the browser's the numeric order and separator of its locale
 * (`formatToParts`), such as MM/DD/YYYY in en-US and DD.MM.YYYY in de-DE.
 */
export function datePattern(format: DateFormat | null = chosen, locale?: string): DatePattern {
  if (format !== null) {
    if (format === 'YYYY-MM-DD') return pattern(['year', 'month', 'day'], '-')
    return pattern(format.startsWith('MM') ? ['month', 'day', 'year'] : ['day', 'month', 'year'], format[2])
  }
  const parts = new Intl.DateTimeFormat(locale, { year: 'numeric', month: '2-digit', day: '2-digit' })
    .formatToParts(new Date(2026, 10, 30))
  const order = parts.map((p) => p.type).filter((t): t is Field => t === 'day' || t === 'month' || t === 'year')
  const literal = parts.find((p) => p.type === 'literal')?.value.trim() ?? '/'
  const separator = ['.', '/', '-'].includes(literal) ? literal : '/'
  return order.length === 3 ? pattern(order, separator) : pattern(['day', 'month', 'year'], '/')
}

/** "2026-10-31" in a numeric pattern: "31.10.2026". */
export function formatInPattern(iso: string, p: DatePattern): string {
  const [year, month, day] = iso.split('-')
  const values: Record<Field, string> = { year, month, day }
  return p.order.map((f) => values[f]).join(p.separator)
}

/** The ISO date, if it is a real day. */
export function isoIfReal(year: number, month: number, day: number): string | null {
  if (!Number.isInteger(year) || year < 1000 || year > 9999) return null
  const date = new Date(year, month - 1, day)
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) return null
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

/**
 * Typed text in the pattern's order as an ISO date: days and months of one or two digits, the year of four, and any
 * of the separators `.`, `/` and `-` between them ("1.2.2026", "01/02/2026" and "1-2-2026" are one day in DD.MM.YYYY).
 * Null for text that isn't a real day.
 */
export function parseInPattern(text: string, p: DatePattern): string | null {
  const match = /^\s*(\d{1,4})\s*[./-]\s*(\d{1,4})\s*[./-]\s*(\d{1,4})\s*$/.exec(text)
  if (!match) return null
  const values = {} as Record<Field, string>
  p.order.forEach((field, i) => { values[field] = match[i + 1] })
  if (values.year.length !== 4 || values.month.length > 2 || values.day.length > 2) return null
  return isoIfReal(Number(values.year), Number(values.month), Number(values.day))
}

/** A day date as the user chose to see it, or undefined for the browser's own way. */
export function chosenDay(iso: string): string | undefined {
  return chosen === null ? undefined : formatInPattern(iso, datePattern(chosen))
}
