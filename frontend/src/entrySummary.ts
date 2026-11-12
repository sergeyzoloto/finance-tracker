import type { EntrySummary } from './api'
import { formatDayRange } from './dashboard'
import { formatMoney, isZero, negate, signOf, sum } from './money'

// The Entries page's summary row (UX-11, D-524) without React: what `GET /api/entries/summary` says, in words, for
// each column. Every filter's matches, on every page; amounts per currency, never added across currencies.

const counted = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`
const without = (n: number) => (n > 0 ? ` · ${n} without` : '')

/** One line of the Amount column: its label and the amount as the rows show it. */
export interface AmountLine { label: 'In' | 'Out' | 'Net' | 'Transfers'; currency: string; text: string }

/** The summary row's texts, column by column. */
export interface SummaryTexts {
  date: string
  payee: string
  category: string
  accounts: string
  amounts: AmountLine[]
}

export function summaryTexts(summary: EntrySummary): SummaryTexts {
  const dates = summary.firstDate && summary.lastDate ? ` · ${formatDayRange(summary.firstDate, summary.lastDate)}` : ''
  return {
    date: `${counted(summary.count, 'entry', 'entries')}${dates}`,
    payee: `${counted(summary.payees, 'payee', 'payees')}${without(summary.withoutPayee)}`,
    category: `${counted(summary.categories, 'category', 'categories')}${without(summary.withoutCategory)}`,
    accounts: counted(summary.accounts, 'account', 'accounts'),
    amounts: summary.currencies.flatMap((c) => amountLines(c.currency, c.incoming, c.outgoing, c.transfers)),
  }
}

/**
 * A currency's lines, each only when it isn't 0, as the rows show amounts: "In" with +, "Out" with −, "Net" (In − Out,
 * signed) only when both are there, "Transfers" without a sign.
 */
export function amountLines(currency: string, incoming: string, outgoing: string, transfers: string): AmountLine[] {
  const lines: AmountLine[] = []
  const hasIn = !isZero(incoming)
  const hasOut = !isZero(outgoing)
  if (hasIn) lines.push({ label: 'In', currency, text: formatMoney(incoming, currency, { signed: true }) })
  if (hasOut) lines.push({ label: 'Out', currency, text: formatMoney(negate(outgoing), currency, { signed: true }) })
  if (hasIn && hasOut) {
    const net = sum([incoming, negate(outgoing)])
    lines.push({ label: 'Net', currency, text: formatMoney(net, currency, { signed: signOf(net) !== 0 }) })
  }
  if (!isZero(transfers)) lines.push({ label: 'Transfers', currency, text: formatMoney(transfers, currency) })
  return lines
}
