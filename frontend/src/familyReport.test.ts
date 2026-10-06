import { describe, expect, it } from 'vitest'
import type { FamilyReportTotal } from './api'
import { contributionWords, netWords, reportMonths, reportQuery, tookPart, totalLines } from './familyReport'
import { formatMoney } from './money'
import { workedReport } from './testFamilyReport'

const eur = (amount: string) => formatMoney(amount, 'EUR')

describe('the family report in words (E1)', () => {
  it('groups the rows by month and adds up each month’s expenses and incomes', () => {
    const months = reportMonths(workedReport.byCurrency[0].rows)
    expect(months.map((m) => [m.month, m.rows.length, m.expenses, m.incomes])).toEqual([
      ['2026-09', 3, '140', '300'], ['2026-10', 1, '10.01', '0']])
  })

  it('says a member’s part of a row: paid for an expense, received for an income, zeros left out', () => {
    const [groceries, rent, salary] = workedReport.byCurrency[0].rows
    expect(contributionWords(groceries, groceries.members[0], 'EUR')).toBe(`share ${eur('30.00')} · paid ${eur('90.00')}`)
    expect(contributionWords(groceries, groceries.members[1], 'EUR')).toBe(`share ${eur('30.00')}`)
    expect(contributionWords(salary, salary.members[0], 'EUR')).toBe(`share ${eur('100.00')} · received ${eur('300.00')}`)
    expect(contributionWords(rent, { share: '0.00', paid: '50.00' }, 'EUR')).toBe(`paid ${eur('50.00')}`)
  })

  it('says each member’s totals and how their balance moved', () => {
    const [mum, dad, kid] = workedReport.byCurrency[0].totals
    expect(totalLines(mum, 'EUR')).toEqual([
      `Expenses: share ${eur('58.33')}, paid ${eur('90.00')}`,
      `Incomes: share ${eur('100.00')}, received ${eur('300.00')}`,
      `Settlements: received ${eur('20.00')}`])
    expect(totalLines(kid, 'EUR')).toEqual([`Expenses: share ${eur('33.35')}, paid ${eur('10.01')}`, `Incomes: share ${eur('100.00')}`])
    expect(netWords(mum, workedReport.members[0], 'EUR')).toBe(`You owe ${eur('188.33')} more`)
    expect(netWords(dad, workedReport.members[1], 'EUR')).toBe(`Dad is owed ${eur('111.67')} more`)
    const even: FamilyReportTotal = { ...kid, net: '0.00' }
    expect(netWords(even, workedReport.members[2], 'EUR')).toBe('Kid’s balance didn’t change')
    expect(netWords(even, workedReport.members[0], 'EUR')).toBe('Your balance didn’t change')
  })

  it('knows who took part, and builds the query of a period', () => {
    expect(workedReport.byCurrency[0].totals.every(tookPart)).toBe(true)
    const none = { memberId: 4, expenseShares: '0.00', expensesPaid: '0.00', incomeShares: '0.00', incomesReceived: '0.00',
      settlementsPaid: '0.00', settlementsReceived: '0.00', net: '0.00' }
    expect(tookPart(none)).toBe(false)
    expect(tookPart({ ...none, settlementsReceived: '5.00', net: '5.00' })).toBe(true)
    expect(reportQuery('', '')).toBe('')
    expect(reportQuery('2026-10-01', '')).toBe('?from=2026-10-01')
    expect(reportQuery('2026-09-01', '2026-09-30')).toBe('?from=2026-09-01&to=2026-09-30')
  })
})
