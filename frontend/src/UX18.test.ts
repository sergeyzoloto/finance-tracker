import { describe, expect, it } from 'vitest'
import type { CurrencyTotals } from './api'
import { amountLines } from './entrySummary'

// UX-18, D-796 to D-799: the summary's Amount lines with family records counted in full.

const plain = (text: string) => text.replace(/ /g, ' ').replace(/−/g, '-')

const totals = (t: Partial<CurrencyTotals>): CurrencyTotals => ({
  currency: 'EUR', incoming: '0.00', outgoing: '0.00', transfers: '0.00', spent: '0.00', spentOthersShares: '0.00',
  received: '0.00', receivedOthersShares: '0.00', ...t,
})
const lines = (t: Partial<CurrencyTotals>) => amountLines(totals(t)).map((l) => `${l.label} ${plain(l.text)}`)

describe('the Amount lines with family records (UX-18)', () => {
  it('reads a payee paid for the family: Spent, Others’ shares, Out, and no Transfers', () => {
    expect(lines({ outgoing: '75.00', spent: '120.00', spentOthersShares: '45.00' }))
      .toEqual(['Spent -€120.00', 'Others’ shares +€45.00', 'Out -€75.00'])
  })

  it('puts each side’s three lines together, then Net and Transfers, with D-799’s signs', () => {
    expect(lines({ incoming: '30.00', received: '60.00', receivedOthersShares: '30.00', outgoing: '15.00',
      spent: '30.00', spentOthersShares: '15.00', transfers: '5.00' })).toEqual([
      'Received +€60.00', 'Others’ shares -€30.00', 'In +€30.00',
      'Spent -€30.00', 'Others’ shares +€15.00', 'Out -€15.00',
      'Net +€15.00', 'Transfers €5.00'])
  })

  it('leaves Spent and Received out while their side’s others’ shares are 0', () => {
    expect(lines({ incoming: '30.00', received: '60.00', receivedOthersShares: '30.00', outgoing: '15.00',
      spent: '15.00' })).toEqual(['Received +€60.00', 'Others’ shares -€30.00', 'In +€30.00', 'Out -€15.00',
      'Net +€15.00'])
    expect(lines({ outgoing: '15.00', spent: '15.00', transfers: '2.00' })).toEqual(['Out -€15.00', 'Transfers €2.00'])
  })

  it('leaves out a line that is 0: a record the others carry wholly', () => {
    // A record of 10 whose share is 0 has no share entry and isn't counted; one whose whole share is the user's
    // has no others' shares. Out 0 with others' shares can't happen, but a 0 line never shows.
    expect(lines({ spent: '10.00', spentOthersShares: '10.00' })).toEqual(['Spent -€10.00', 'Others’ shares +€10.00'])
    expect(lines({})).toEqual([])
  })
})
