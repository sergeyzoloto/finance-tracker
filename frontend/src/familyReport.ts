import { type FamilyReport, type FamilyReportRow, type FamilyReportTotal } from './api'
import { abs, formatMoney, signOf, sum } from './money'

/**
 * The family report (E1, F6c) in words, without React: its rows by month, each member's contribution to a row, and
 * each member's totals over the period. Amounts are the API's decimal strings; sums go through money.ts.
 */

export interface ReportMonth {
  /** "2026-09" */
  month: string
  rows: FamilyReportRow[]
  /** The month's expense rows added up, and its income rows. */
  expenses: string
  incomes: string
}

/** The rows by month, in the report's order, with each month's expenses and incomes added up. */
export function reportMonths(report: FamilyReport): ReportMonth[] {
  const months: ReportMonth[] = []
  for (const row of report.rows) {
    let month = months.at(-1)
    if (month?.month !== row.month) {
      month = { month: row.month, rows: [], expenses: '0', incomes: '0' }
      months.push(month)
    }
    month.rows.push(row)
  }
  return months.map((m) => ({
    ...m,
    expenses: sum(m.rows.filter((r) => r.categoryType === 'EXPENSE').map((r) => r.total)),
    incomes: sum(m.rows.filter((r) => r.categoryType === 'INCOME').map((r) => r.total)),
  }))
}

/** A member's part of a row: "share €30.00 · paid €90.00", or "received" for an income; a zero is left out. */
export function contributionWords(row: FamilyReportRow, contribution: { share: string; paid: string }, currency: string) {
  const parts: string[] = []
  if (signOf(contribution.share) !== 0) parts.push(`share ${formatMoney(contribution.share, currency)}`)
  if (signOf(contribution.paid) !== 0) {
    parts.push(`${row.categoryType === 'INCOME' ? 'received' : 'paid'} ${formatMoney(contribution.paid, currency)}`)
  }
  return parts.join(' · ')
}

/**
 * A member's totals as sentences, each part only when it isn't zero: "Expenses: share €58.33, paid €90.00",
 * "Incomes: share €100.00, received €300.00", "Settlements: paid €20.00".
 */
export function totalLines(total: FamilyReportTotal, currency: string) {
  const money = (amount: string) => formatMoney(amount, currency)
  const line = (label: string, parts: [string, string][]) => {
    const said = parts.filter(([, amount]) => signOf(amount) !== 0).map(([word, amount]) => `${word} ${money(amount)}`)
    return said.length === 0 ? [] : [`${label}: ${said.join(', ')}`]
  }
  return [
    ...line('Expenses', [['share', total.expenseShares], ['paid', total.expensesPaid]]),
    ...line('Incomes', [['share', total.incomeShares], ['received', total.incomesReceived]]),
    ...line('Settlements', [['paid', total.settlementsPaid], ['received', total.settlementsReceived]]),
  ]
}

/**
 * How the member's balance moved over the period, in words: "You owe €188.33 more", "Dad is owed €111.67 more",
 * "Kid's balance didn't change".
 */
export function netWords(total: FamilyReportTotal, member: { displayName: string; you: boolean }, currency: string) {
  const sign = signOf(total.net)
  const amount = formatMoney(abs(total.net), currency)
  const who = member.you ? 'You' : member.displayName
  if (sign === 0) return member.you ? 'Your balance didn’t change' : `${who}’s balance didn’t change`
  if (sign > 0) return `${who} ${member.you ? 'owe' : 'owes'} ${amount} more`
  return `${who} ${member.you ? 'are' : 'is'} owed ${amount} more`
}

/** Whether the member took part in the period at all: a share, a payment, a receipt or a settlement. */
export const tookPart = (total: FamilyReportTotal) => [total.expenseShares, total.expensesPaid, total.incomeShares,
  total.incomesReceived, total.settlementsPaid, total.settlementsReceived].some((amount) => signOf(amount) !== 0)

/** The report's query: `?from=…&to=…`, each only when given. */
export function reportQuery(from: string, to: string) {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  const query = params.toString()
  return query === '' ? '' : `?${query}`
}
