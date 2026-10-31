import { expect, type Locator, type Page } from '@playwright/test'

// Day dates as the account sees and types them (D-304, UX-1): the app's DateInput shows its pattern as its
// placeholder ("DD.MM.YYYY", or the browser locale's order when the account chose none), so the specs type and read a
// date in it here, and never in ISO. The API and the URLs keep ISO dates.

/** "2026-10-31" in a pattern such as "DD.MM.YYYY": "31.10.2026". */
export function inPattern(iso: string, pattern: string): string {
  const [year, month, day] = iso.split('-')
  const separator = /[./-]/.exec(pattern)?.[0] ?? '/'
  const values: Record<string, string> = { YYYY: year, MM: month, DD: day }
  return pattern.split(separator).map((field) => values[field]).join(separator)
}

async function patternOf(field: Locator): Promise<string> {
  const pattern = await field.getAttribute('placeholder')
  expect(pattern, 'a date field shows its pattern').toMatch(/^(DD|MM|YYYY)[./-](DD|MM)[./-](DD|MM|YYYY)$/)
  return pattern!
}

/** Types the day into a date field in the field's own pattern; an empty day clears it. */
export async function fillDate(field: Locator, iso: string) {
  await field.fill(iso === '' ? '' : inPattern(iso, await patternOf(field)))
}

/** The date field shows the day, in its own pattern. */
export async function expectDate(field: Locator, iso: string) {
  await expect(field).toHaveValue(inPattern(iso, await patternOf(field)))
}

/** The account's date format from `/api/me`: null for as the browser shows dates. */
export async function dateFormatOf(page: Page): Promise<string | null> {
  const me = await (await page.context().request.get('/api/me')).json()
  return me.dateFormat ?? null
}

/**
 * A day as the app shows it for a format: in the pattern, or for none as the browser (en-US in these runs) shows a
 * date: "Oct 31, 2026".
 */
export function shownDay(iso: string, format: string | null): string {
  return format === null
    ? new Date(`${iso}T12:00:00Z`).toLocaleDateString('en-US', { dateStyle: 'medium', timeZone: 'UTC' })
    : inPattern(iso, format)
}
