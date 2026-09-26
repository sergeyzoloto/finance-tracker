import { describe, expect, it } from 'vitest'
import type { ConvertedCashFlow, CounterpartyBalance, MissingRate, SharedSettlement } from './api'
import {
  convertedCashFlowTable, daysBetween, inBaseFromQuery, loanSides, mergeMissing, MISSING, monthsBetween,
  periodFromQuery, settlementSentence, total,
} from './dashboard'
import { formatMoney } from './money'

describe('periodFromQuery', () => {
  const today = new Date(2026, 0, 15)
  const period = (query: string) => periodFromQuery(new URLSearchParams(query), today)

  it('is this month without a query, with balances as of today while the month runs', () => {
    expect(period('')).toEqual({ choice: 'this-month', from: '2026-01-01', to: '2026-01-31', asOf: '2026-01-15' })
  })

  it('reaches into the year before for the last 3 months', () => {
    expect(period('period=last-3-months')).toMatchObject({ from: '2025-11-01', to: '2026-01-31' })
  })

  it('shows balances as of the last day of a period that is over', () => {
    expect(period('period=last-year')).toEqual({ choice: 'last-year', from: '2025-01-01', to: '2025-12-31', asOf: '2025-12-31' })
  })

  it('takes a custom range and the day of the balances from the URL', () => {
    expect(period('from=2024-02-29&to=2024-03-31&asOf=2024-03-15'))
      .toEqual({ choice: 'custom', from: '2024-02-29', to: '2024-03-31', asOf: '2024-03-15' })
  })

  it('ignores days that do not exist and unknown presets', () => {
    expect(period('from=2023-02-29&to=2023-03-31&period=this-year')).toMatchObject({ choice: 'this-year', from: '2026-01-01' })
    expect(period('period=forever')).toMatchObject({ choice: 'this-month' })
  })
})

describe('monthsBetween', () => {
  it('lists every month the days fall in, across a new year', () => {
    expect(monthsBetween('2025-11-20', '2026-02-03')).toEqual(['2025-11', '2025-12', '2026-01', '2026-02'])
  })
})

describe('who owes whom', () => {
  const settlement = (balance: string, direction: SharedSettlement['direction']): SharedSettlement =>
    ({ accountId: 5, accountCode: 'FAMILY_DEBT', currency: 'EUR', balance, direction })

  it('says it in a sentence for the shared budget', () => {
    expect(settlementSentence(settlement('-30.00', 'USER_IS_OWED'), 'Family budget'))
      .toBe(`Family budget owes you ${formatMoney('30', 'EUR')}.`)
    expect(settlementSentence(settlement('12.50', 'USER_OWES'), 'Family budget'))
      .toBe(`You owe ${formatMoney('12.5', 'EUR')} to Family budget.`)
  })

  it('puts a loan on the side its sign says, an overpaid one on the other', () => {
    const loan = (balance: string): CounterpartyBalance => ({ counterpartyId: 1, counterpartyName: 'Ivan', currency: 'EUR', balance })
    expect(loanSides(loan('100.00'), 'owesYou')).toEqual({ owesYou: '100', youOwe: null })
    expect(loanSides(loan('-2.50'), 'owesYou')).toEqual({ owesYou: null, youOwe: '2.5' })
    expect(loanSides(loan('20000.00'), 'youOwe')).toEqual({ owesYou: null, youOwe: '20000' })
  })
})

describe('amounts in the base currency', () => {
  const kzt: MissingRate = { currency: 'KZT', from: '2026-08-03', to: '2026-08-03', days: 1 }
  const report: ConvertedCashFlow = {
    currency: 'EUR',
    rows: [
      { month: '2026-08', categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', total: null, missingRates: [kzt] },
      { month: '2026-08', categoryCode: 'RENT', categoryName: 'Rent', categoryType: 'EXPENSE', total: '500.00', missingRates: [] },
      { month: '2026-09', categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', total: '22.50', missingRates: [] },
      { month: '2026-09', categoryCode: 'SALARY', categoryName: 'Salary', categoryType: 'INCOME', total: '2000.00', missingRates: [] },
    ],
    exchangeResults: [
      { month: '2026-08', realized: '0.00', unrealized: null, missingRates: [kzt] },
      { month: '2026-09', realized: '10.00', unrealized: '-10.00', missingRates: [] },
    ],
  }

  it('is switched on by currency=base in the URL', () => {
    expect(inBaseFromQuery(new URLSearchParams('currency=base'))).toBe(true)
    expect(inBaseFromQuery(new URLSearchParams('period=this-year'))).toBe(false)
  })

  it('never adds up a figure that could not be converted, and marks every sum it is part of', () => {
    const table = convertedCashFlowTable(report, ['2026-08', '2026-09'])
    expect(table.expense.lines.map((l) => [l.label, l.months, l.total])).toEqual([
      ['Groceries', [MISSING, '22.5'], MISSING],
      ['Rent', ['500', null], '500'],
    ])
    expect(table.expense.subtotal.months).toEqual([MISSING, '22.5'])
    expect(table.net.months).toEqual([MISSING, '1977.5'])
    expect(table.net.total).toBe(MISSING)
  })

  it('shows what exchange rates did as lines of their own, outside the net', () => {
    const table = convertedCashFlowTable(report, ['2026-08', '2026-09'])
    expect(table.exchange.map((l) => [l.label, l.months, l.total])).toEqual([
      ['Realized on exchanges', ['0.00', '10.00'], '10'],
      ['Revaluation of balances', [MISSING, '-10.00'], MISSING],
    ])
    // Nothing to show where exchange rates did nothing.
    const quiet = { ...report, exchangeResults: report.exchangeResults.map((r) => ({ ...r, realized: '0.00', unrealized: '0.00' })) }
    expect(convertedCashFlowTable(quiet, ['2026-08', '2026-09']).exchange).toEqual([])
  })

  it('adds up what is there, and nothing is null', () => {
    expect(total(['1.10', null, '2.20'])).toBe('3.3')
    expect(total([null, null])).toBeNull()
    expect(total(['1.10', MISSING])).toBe(MISSING)
  })

  it('merges the missing rates of several figures by currency', () => {
    expect(mergeMissing([[kzt], [{ currency: 'KZT', from: '2026-07-01', to: '2026-07-31', days: 5 }],
      [{ currency: 'AMD', from: '2026-09-01', to: '2026-09-01', days: 1 }]])).toEqual([
      { currency: 'AMD', from: '2026-09-01', to: '2026-09-01' },
      { currency: 'KZT', from: '2026-07-01', to: '2026-08-03' },
    ])
  })

  it('counts the days between two dates across a change to summer time', () => {
    expect(daysBetween('2026-03-28', '2026-03-30')).toBe(2)
    expect(daysBetween('2022-03-01', '2026-09-26')).toBe(1670)
  })
})
