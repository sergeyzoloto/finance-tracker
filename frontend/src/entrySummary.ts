import type { CurrencyTotals, EntrySummary } from './api'
import { formatDayRange } from './dashboard'
import { formatMoney, isZero, negate, signOf, sum } from './money'

// The Entries page's summary row (UX-11, D-524) without React: what `GET /api/entries/summary` says, in words, for
// each column. Every filter's matches, on every page; amounts per currency, never added across currencies.

const counted = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`
const without = (n: number) => (n > 0 ? ` · ${n} without` : '')

/** One line of the Amount column: its label and the amount as the rows show it. */
export interface AmountLine {
  label: 'Received' | 'In' | 'Spent' | 'Out' | 'Others’ shares' | 'Net' | 'Transfers'
  currency: string
  text: string
}

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
    amounts: summary.currencies.flatMap(amountLines),
  }
}

/**
 * A currency's lines, each only when it isn't 0, as the rows show amounts. The incoming side, then the outgoing one:
 * with family records counted in full (UX-18, D-796, D-799), "Received" (+), "Others’ shares" (−) and "In" (+), and
 * "Spent" (−), "Others’ shares" (+) and "Out" (−), the first two only while that side's others' shares aren't 0;
 * then "Net" (In − Out, signed) only when both In and Out are there, and "Transfers" without a sign.
 */
export function amountLines(c: CurrencyTotals): AmountLine[] {
  const { currency } = c
  const lines: AmountLine[] = []
  const line = (label: AmountLine['label'], amount: string, sign: 1 | -1 | 0) => {
    if (isZero(amount)) return
    const text = sign === 0 ? formatMoney(amount, currency)
      : formatMoney(sign < 0 ? negate(amount) : amount, currency, { signed: true })
    lines.push({ label, currency, text })
  }
  if (!isZero(c.receivedOthersShares ?? '0')) {
    line('Received', c.received, 1)
    line('Others’ shares', c.receivedOthersShares, -1)
  }
  line('In', c.incoming, 1)
  if (!isZero(c.spentOthersShares ?? '0')) {
    line('Spent', c.spent, -1)
    line('Others’ shares', c.spentOthersShares, 1)
  }
  line('Out', c.outgoing, -1)
  if (!isZero(c.incoming) && !isZero(c.outgoing)) {
    const net = sum([c.incoming, negate(c.outgoing)])
    lines.push({ label: 'Net', currency, text: formatMoney(net, currency, { signed: signOf(net) !== 0 }) })
  }
  line('Transfers', c.transfers, 0)
  return lines
}
