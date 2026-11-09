import { describe, expect, it } from 'vitest'
import type { AccountBalance, ConvertedBalance, ConvertedCashFlow, CounterpartyBalance, MissingRate } from './api'
import {
  cashFlowTables, convertedCashFlowTable, daysBetween, debtSides, familyDebts, inBaseFromQuery, loanSides,
  mergeMissing, MISSING, monthNote, monthsBetween, periodFromQuery, periodTotals, presetRange, PRESETS, scopeFromQuery,
  scopeParams, showsOwnCurrencies, total, usedRateText, type Preset,
} from './dashboard'

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
    expect(period('period=previous-year'))
      .toEqual({ choice: 'previous-year', from: '2025-01-01', to: '2025-12-31', asOf: '2025-12-31' })
  })

  it('still takes an old bookmark’s “last-year” as the previous year (D-296)', () => {
    expect(period('period=last-year')).toEqual(period('period=previous-year'))
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

describe('the period buttons (D-296)', () => {
  const ranges = (y: number, m: number, d: number) => Object.fromEntries(PRESETS.map((p: Preset) => {
    const { from, to } = presetRange(p, new Date(y, m - 1, d))
    return [p, `${from} ${to}`]
  }))

  it('come in the page’s order', () => {
    expect(PRESETS).toEqual(['this-week', 'previous-week', 'past-month', 'previous-month', 'this-month', 'this-quarter',
      'previous-quarter', 'last-3-months', 'past-year', 'this-year', 'previous-year'])
  })

  it('on 31 March: a month back clamps to the end of February', () => {
    expect(ranges(2026, 3, 31)).toEqual({
      'this-week': '2026-03-30 2026-04-05',
      'previous-week': '2026-03-23 2026-03-29',
      'this-quarter': '2026-01-01 2026-03-31',
      'previous-quarter': '2025-10-01 2025-12-31',
      'past-month': '2026-03-01 2026-03-31',
      'previous-month': '2026-02-01 2026-02-28',
      'this-month': '2026-03-01 2026-03-31',
      'last-3-months': '2026-01-01 2026-03-31',
      'past-year': '2025-04-01 2026-03-31',
      'this-year': '2026-01-01 2026-12-31',
      'previous-year': '2025-01-01 2025-12-31',
    })
  })

  it('on 29 February 2028: a year back is 28 February 2027, so the past year starts on 1 March', () => {
    expect(ranges(2028, 2, 29)).toMatchObject({
      'past-month': '2028-01-30 2028-02-29',
      'previous-month': '2028-01-01 2028-01-31',
      'past-year': '2027-03-01 2028-02-29',
    })
  })

  it('on 1 January: the previous month and year are last year’s', () => {
    expect(ranges(2026, 1, 1)).toMatchObject({
      'past-month': '2025-12-02 2026-01-01',
      'previous-month': '2025-12-01 2025-12-31',
      'last-3-months': '2025-11-01 2026-01-31',
      'past-year': '2025-01-02 2026-01-01',
      'previous-year': '2025-01-01 2025-12-31',
    })
  })

  it('on 31 October 2026 and on 15 October', () => {
    expect(ranges(2026, 10, 31)).toMatchObject({
      'past-month': '2026-10-01 2026-10-31',
      'previous-month': '2026-09-01 2026-09-30',
      'past-year': '2025-11-01 2026-10-31',
    })
    expect(ranges(2026, 10, 15)).toMatchObject({ 'past-month': '2026-09-16 2026-10-15' })
  })

  it('end today, so balances are as of today', () => {
    expect(periodFromQuery(new URLSearchParams('period=past-year'), new Date(2026, 9, 31)))
      .toEqual({ choice: 'past-year', from: '2025-11-01', to: '2026-10-31', asOf: '2026-10-31' })
  })
})

describe('a month the period covers in part (D-296)', () => {
  it('says from or to which day, and nothing for a whole month', () => {
    expect(monthNote('2026-09', '2026-09-16', '2026-10-15')).toBe('from Sep 16')
    expect(monthNote('2026-10', '2026-09-16', '2026-10-15')).toBe('to Oct 15')
    expect(monthNote('2026-10', '2026-10-03', '2026-10-15')).toBe('Oct 3 to Oct 15')
    expect(monthNote('2026-10', '2026-10-01', '2026-10-31')).toBeUndefined()
    expect(monthNote('2026-08', '2026-07-16', '2026-10-15')).toBeUndefined()
  })
})

describe('the scope of income and expenses (D-302)', () => {
  const scope = (query: string, families: number[] | null = [7, 9]) =>
    scopeFromQuery(new URLSearchParams(query), families)

  it('is All by default, and takes the URL’s scope and family budget', () => {
    expect(scope('')).toEqual({ scope: 'all', family: null })
    expect(scope('scope=personal')).toEqual({ scope: 'personal', family: null })
    expect(scope('scope=family-mine')).toEqual({ scope: 'family-mine', family: null })
    expect(scope('scope=family-others&family=9')).toEqual({ scope: 'family-others', family: 9 })
  })

  it('takes a family budget only with a family scope, and only one of the user’s', () => {
    expect(scope('scope=personal&family=9')).toEqual({ scope: 'personal', family: null })
    expect(scope('scope=family-mine&family=8')).toEqual({ scope: 'family-mine', family: null })
    expect(scope('scope=everything')).toEqual({ scope: 'all', family: null })
  })

  it('is All with the switch off or without a membership, whatever the URL says', () => {
    expect(scope('scope=family-mine&family=7', null)).toEqual({ scope: 'all', family: null })
    expect(scope('scope=personal', [])).toEqual({ scope: 'all', family: null })
  })

  it('asks the API for it', () => {
    expect(scopeParams({ scope: 'all', family: null })).toBe('')
    expect(scopeParams({ scope: 'personal', family: null })).toBe('&scope=PERSONAL')
    expect(scopeParams({ scope: 'family-mine', family: 7 })).toBe('&scope=FAMILY_MINE&familyLedgerId=7')
    expect(scopeParams({ scope: 'family-others', family: null })).toBe('&scope=FAMILY_OTHERS')
  })
})

describe('monthsBetween', () => {
  it('lists every month the days fall in, across a new year', () => {
    expect(monthsBetween('2025-11-20', '2026-02-03')).toEqual(['2025-11', '2025-12', '2026-01', '2026-02'])
  })
})

describe('who owes whom', () => {
  it('puts a loan on the side its sign says, an overpaid one on the other', () => {
    const loan = (balance: string): CounterpartyBalance => ({ counterpartyId: 1, counterpartyName: 'Ivan', currency: 'EUR', balance })
    expect(loanSides(loan('100.00'), 'owesYou')).toEqual({ owesYou: '100', youOwe: null })
    expect(loanSides(loan('-2.50'), 'owesYou')).toEqual({ owesYou: null, youOwe: '2.5' })
    expect(loanSides(loan('20000.00'), 'youOwe')).toEqual({ owesYou: null, youOwe: '20000' })
  })

  it('puts a debt account on the side its type and sign say (D-299)', () => {
    expect(debtSides('LIABILITY', '30.00')).toEqual({ owesYou: null, youOwe: '30' })
    expect(debtSides('LIABILITY', '-12.50')).toEqual({ owesYou: '12.5', youOwe: null })
    expect(debtSides('ASSET', '-5.00')).toEqual({ owesYou: null, youOwe: '5' })
  })
})

describe('the family budgets of open loans (D-299)', () => {
  const balance = (accountId: number, accountName: string, currency: string, amount: string,
    family?: { id: number; name: string }): AccountBalance => ({
    accountId, accountCode: `CODE_${accountId}`, accountName, accountType: 'LIABILITY', currency, balance: amount,
    ...(family ? { familyLedgerId: family.id, familyLedgerName: family.name } : {}),
  })
  const home = { id: 7, name: 'Home' }
  const balances = [
    balance(1, 'Cash', 'EUR', '100.00'),
    balance(2, 'Debt to family budget: Home', 'EUR', '30.00', home),
    balance(2, 'Debt to family budget: Home', 'USD', '-5.00', home),
    balance(3, 'Family budget', 'EUR', '-12.00'),
  ]

  it('names each debt account by its budget and links its balances, per currency', () => {
    expect(familyDebts(balances, null, null)).toEqual([{
      key: 'account:2', name: 'Home', to: '/family/7/balances', accountType: 'LIABILITY',
      amounts: [{ currency: 'EUR', balance: '30.00' }, { currency: 'USD', balance: '-5.00' }],
      own: [{ currency: 'EUR', balance: '30.00' }, { currency: 'USD', balance: '-5.00' }],
    }])
  })

  it('adds the old shared account under its own name while its balance isn’t zero', () => {
    expect(familyDebts(balances, null, 3).map((d) => [d.name, d.to])).toEqual([
      ['Home', '/family/7/balances'], ['Family budget', '/entries?accountId=3'],
    ])
    const settled = balances.map((b) => (b.accountId === 3 ? { ...b, balance: '0.00' } : b))
    expect(familyDebts(settled, null, 3).map((d) => d.name)).toEqual(['Home'])
  })

  it('in the base currency takes the converted figure, with the own currencies beside it', () => {
    const converted: ConvertedBalance[] = [{ accountId: 2, accountCode: 'CODE_2', accountName: 'Debt to family budget: Home',
      accountType: 'LIABILITY', currency: 'EUR', balance: '26.00', missingRates: [], familyLedgerId: 7, familyLedgerName: 'Home' }]
    const [debt] = familyDebts(balances, converted, null)
    expect(debt.amounts).toEqual([{ currency: 'EUR', balance: '26.00' }])
    expect(debt.own).toHaveLength(2)
    expect(showsOwnCurrencies(debt.own, 'EUR')).toBe(true)
    expect(showsOwnCurrencies([{ currency: 'EUR' }], 'EUR')).toBe(false)
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

  it('is the default, and each currency only with currency=each; an old link’s currency=base still works (D-297)', () => {
    expect(inBaseFromQuery(new URLSearchParams(''))).toBe(true)
    expect(inBaseFromQuery(new URLSearchParams('period=this-year'))).toBe(true)
    expect(inBaseFromQuery(new URLSearchParams('currency=base'))).toBe(true)
    expect(inBaseFromQuery(new URLSearchParams('currency=each'))).toBe(false)
  })

  it('totals the period from the table’s own subtotals (D-305)', () => {
    const table = convertedCashFlowTable(report, ['2026-08', '2026-09'])
    expect(periodTotals(table)).toEqual({ income: '2000', expense: MISSING, net: MISSING })
    const [eur] = cashFlowTables([
      { month: '2026-09', categoryCode: 'SALARY', categoryName: 'Salary', categoryType: 'INCOME', currency: 'EUR', total: '100.00' },
      { month: '2026-09', categoryCode: 'RENT', categoryName: 'Rent', categoryType: 'EXPENSE', currency: 'EUR', total: '30.50' },
    ], ['2026-09'])
    expect(periodTotals(eur)).toEqual({ income: '100', expense: '30.5', net: '69.5' })
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

describe('the rates a figure used (D-49, D-90)', () => {
  it('names the source and date, and marks a stale manual rate', () => {
    expect(usedRateText({ currency: 'USD', date: '2026-09-29', perEuro: '1.1', source: 'ECB', stale: false }))
      .toBe('1 EUR = 1.1 USD, ECB rate of Sep 29, 2026')
    expect(usedRateText({ currency: 'RUB', date: '2026-08-01', perEuro: '95.5', source: 'MANUAL', stale: true }))
      .toBe('1 EUR = 95.5 RUB, manual rate of Aug 1, 2026 (rate stale)')
  })
})
