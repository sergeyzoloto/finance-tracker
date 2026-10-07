import type { FamilyBalances, FamilyRecordPage, FamilyReport, Me } from './api'

// Guards for the answers whose nested fields the screens read (F8c): a family budget's balances, its report and its
// record pages, and /api/me. Each checks only the shape that the code reading it relies on, so that a wrong answer
// (a stale server, a proxy's error page with status 200, a field gone) shows an error where it is read, and never
// throws in render. They are the `guard` of `useApi`; the pages that read a nested field of one of these answers name
// the guard that stands before it.

type Obj = Record<string, unknown>
const isObject = (value: unknown): value is Obj => typeof value === 'object' && value !== null && !Array.isArray(value)
const isArrayOf = (value: unknown, each: (item: unknown) => boolean): boolean => Array.isArray(value) && value.every(each)
const isString = (value: unknown) => typeof value === 'string'
const isNumber = (value: unknown) => typeof value === 'number'
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

const isOptionalString = (value: unknown) => value === undefined || value === null || isString(value)

/**
 * `/api/me`. The app can't run without one thing from it: `today`, a date as "2026-10-07", which every default date is
 * (D-101). Everything else is optional, as the api sends it: `name` is null for an account registered with its email
 * only (nothing in Keycloak but the email), `email` and `timeZone` are null or missing, `features` missing counts as
 * all off. A field that is there must be what the code reading it expects, a string or null; so a proxy's page, a
 * server of another version or a `today` that isn't a date is a notice, and a nameless account is not (F8c-fix).
 */
export function isMe(answer: unknown): answer is Me {
  return isObject(answer) && isString(answer.today) && ISO_DATE.test(answer.today as string)
    && isOptionalString(answer.name) && isOptionalString(answer.email) && isOptionalString(answer.timeZone)
    && (answer.features === undefined || answer.features === null || isObject(answer.features))
}

const isRate = (rate: unknown) => isObject(rate) && isString(rate.currency)

/**
 * A family budget's balances: `byCurrency[].members[]` (read by `yourBalance`, `whoOwesWhom`, the balances page and the
 * departure notes) and D-47's `total` with its `members`, `rates` and `missingCurrencies`.
 */
export function isBalances(answer: unknown): answer is FamilyBalances {
  if (!isObject(answer) || !isArrayOf(answer.byCurrency, (c) => isObject(c) && isString(c.currency)
    && isArrayOf(c.members, (m) => isObject(m) && isNumber(m.memberId) && isString(m.balance)
      && isString(m.displayName)))) return false
  const total = answer.total
  return isObject(total) && isString(total.currency) && isArrayOf(total.members, (m) => isObject(m) && isNumber(m.memberId))
    && isArrayOf(total.rates, isRate) && isArrayOf(total.missingCurrencies, isString)
}

const isReportTotal = (t: unknown) => isObject(t) && isNumber(t.memberId)

/**
 * The family report: `members`, `byCurrency[].rows[].members[]` and `.totals[]`, and D-47's `total` with its `totals`,
 * `rates` and `missingCurrencies`.
 */
export function isReport(answer: unknown): answer is FamilyReport {
  if (!isObject(answer) || !isArrayOf(answer.members, (m) => isObject(m) && isNumber(m.memberId) && isString(m.displayName))
    || !isArrayOf(answer.byCurrency, (c) => isObject(c) && isString(c.currency)
      && isArrayOf(c.rows, (r) => isObject(r) && isString(r.month) && isString(r.categoryName) && isString(r.total)
        && isArrayOf(r.members, (m) => isObject(m) && isNumber(m.memberId)))
      && isArrayOf(c.totals, isReportTotal))) return false
  const total = answer.total
  return isObject(total) && isString(total.currency) && isArrayOf(total.totals, isReportTotal)
    && isArrayOf(total.rates, isRate) && isArrayOf(total.missingCurrencies, isString)
}

/**
 * A page of records: `content[]`, each with its `payer`, `category` and `shares[].member` that the lists and the detail
 * read. A settlement's `payee` and the like are left to the error boundaries.
 */
export function isRecordPage(answer: unknown): answer is FamilyRecordPage {
  return isObject(answer) && isNumber(answer.totalElements)
    && isArrayOf(answer.content, (r) => isObject(r) && isNumber(r.id) && isString(r.date) && isString(r.amount)
      && isObject(r.payer) && isNumber(r.payer.memberId) && (r.category === null || isObject(r.category))
      && isArrayOf(r.shares, (share) => isObject(share) && isObject(share.member) && isNumber(share.member.memberId)
        && isString(share.amount)))
}
