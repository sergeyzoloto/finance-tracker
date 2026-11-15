import type { CurrencyTotals, EntrySummary } from './api'
import { formatDayRange } from './dashboard'
import { formatMoney, isZero, negate, signOf, sum } from './money'

// The Entries page's summary panel (UX-11, D-524; ENT-1, D-871, D-875) without React: what `GET /api/entries/summary`
// says, in words: the counts line, and per currency a table of amounts. Never added across currencies.

const counted = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`
const without = (n: number) => (n > 0 ? ` · ${n} without` : '')

/** The counts line's parts, worded as the summary row's cells were. */
export interface SummaryTexts {
  date: string
  payee: string
  category: string
  accounts: string
}

export function summaryTexts(summary: EntrySummary): SummaryTexts {
  const dates = summary.firstDate && summary.lastDate ? ` · ${formatDayRange(summary.firstDate, summary.lastDate)}` : ''
  return {
    date: `${counted(summary.count, 'entry', 'entries')}${dates}`,
    payee: `${counted(summary.payees, 'payee', 'payees')}${without(summary.withoutPayee)}`,
    category: `${counted(summary.categories, 'category', 'categories')}${without(summary.withoutCategory)}`,
    accounts: counted(summary.accounts, 'account', 'accounts'),
  }
}

export type AmountRowLabel = 'Income' | 'Expenses' | 'Net' | 'Transfers'
export type AmountColumn = 'Full amount' | 'Others’ shares' | 'My share' | 'Amount'

/** One row of a currency's table: a text per column, '' for an empty cell, and its tone. */
export interface AmountRow {
  label: AmountRowLabel
  cells: string[]
  tone: 'income' | 'expense' | ''
}

/** A currency's amount table (D-871): its columns and rows. */
export interface AmountTable {
  currency: string
  columns: AmountColumn[]
  rows: AmountRow[]
}

/**
 * A currency's table (D-871). Rows Income, Expenses, Net and Transfers; columns Full amount, Others’ shares and My
 * share, or one column "Amount" (My share) in a currency without others' shares (D-875). Income's full amount is what
 * was received (+), its others' shares −, my share the summary's `incoming` (+); Expenses' full amount what was spent
 * (−), its others' shares +, my share `outgoing` (−). Net (incoming − outgoing, signed) only in My share and only when
 * both Income and Expenses are there; Transfers without a sign, in Full amount and My share. A row that is 0 everywhere
 * is left out; a 0 cell is empty.
 */
export function amountTable(c: CurrencyTotals): AmountTable {
  const { currency } = c
  const shared = !isZero(c.receivedOthersShares ?? '0') || !isZero(c.spentOthersShares ?? '0')
  const money = (amount: string, sign: 1 | -1 | 0) => {
    if (isZero(amount)) return ''
    return sign === 0 ? formatMoney(amount, currency)
      : formatMoney(sign < 0 ? negate(amount) : amount, currency, { signed: true })
  }
  const rows: AmountRow[] = []
  const add = (label: AmountRowLabel, full: string, others: string, mine: string, tone: AmountRow['tone']) => {
    const cells = shared ? [full, others, mine] : [mine]
    if (cells.every((cell) => cell === '')) return
    rows.push({ label, cells, tone })
  }
  add('Income', money(c.received ?? c.incoming, 1), money(c.receivedOthersShares ?? '0', -1), money(c.incoming, 1), 'income')
  add('Expenses', money(c.spent ?? c.outgoing, -1), money(c.spentOthersShares ?? '0', 1), money(c.outgoing, -1), 'expense')
  if (!isZero(c.incoming) && !isZero(c.outgoing)) {
    const net = sum([c.incoming, negate(c.outgoing)])
    rows.push({ label: 'Net', cells: shared ? ['', '', formatMoney(net, currency, { signed: signOf(net) !== 0 })]
      : [formatMoney(net, currency, { signed: signOf(net) !== 0 })], tone: '' })
  }
  add('Transfers', money(c.transfers, 0), '', money(c.transfers, 0), '')
  return { currency, columns: shared ? ['Full amount', 'Others’ shares', 'My share'] : ['Amount'], rows }
}

/** My share's net in a currency (incoming − outgoing), signed, for the folded panel's line (D-872). */
export function myNet(c: CurrencyTotals): string {
  const net = sum([c.incoming, negate(c.outgoing)])
  return formatMoney(net, c.currency, { signed: signOf(net) !== 0 })
}
