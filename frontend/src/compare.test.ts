import { describe, expect, it } from 'vitest'
import type { CashFlowRow, ConvertedCashFlow } from './api'
import {
  cashFlowTables, changeOf, changeText, chartByMonth, compareColumns, compareTable, compareTables, comparisons,
  convertedCashFlowTable, drawable, drawnComparison, effectOf, MISSING, monthPairs, monthsBetween, periodFromQuery,
  presetRange, PRESETS, rangeBars, rangeLabel as label, totalChanges, type Comparisons, type Preset,
} from './dashboard'

// UX-5 (D-393 to D-399): the period compared with the same period a year earlier and with the previous one.

const iso = (y: number, m: number, d: number) => `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`
/** A preset's comparisons on `today`, as "from to" strings: [section, previous, year earlier]; null for none. */
function compared(preset: Preset, today: string) {
  const [y, m, d] = today.split('-').map(Number)
  const c = comparisons(presetRange(preset, new Date(y, m - 1, d)), today)!
  return described(c)
}
function described(c: Comparisons) {
  const range = (role: string) => {
    const r = c.ranges.find((x) => x.role === role)
    return r ? `${r.from} ${r.to}` : null
  }
  return {
    section: `${c.section.from} ${c.section.to}`,
    previous: range('previous') ?? range('previous-year'),
    year: range('year'),
    oneColumn: c.ranges.length === 1 && c.ranges[0].role === 'previous-year',
  }
}

describe('the comparison ranges (D-395, D-396)', () => {
  it('come with the four new presets in the page’s order (D-397)', () => {
    expect(PRESETS).toEqual(['latest-entry-month', 'this-week', 'previous-week', 'past-month', 'previous-month',
      'this-month', 'this-quarter', 'previous-quarter', 'last-3-months', 'past-year', 'this-year', 'previous-year'])
    const ranges = Object.fromEntries(PRESETS.map((p) => {
      const r = presetRange(p, new Date(2026, 10, 9))
      return [p, `${r.from} ${r.to}`]
    }))
    expect(ranges).toMatchObject({
      'this-week': '2026-11-09 2026-11-15', 'previous-week': '2026-11-02 2026-11-08',
      'this-quarter': '2026-10-01 2026-12-31', 'previous-quarter': '2026-07-01 2026-09-30',
    })
    // A Sunday is the end of its week; January's previous quarter is last year's last.
    expect(presetRange('this-week', new Date(2026, 10, 15))).toEqual({ from: '2026-11-09', to: '2026-11-15' })
    expect(presetRange('previous-quarter', new Date(2026, 0, 5))).toEqual({ from: '2025-10-01', to: '2025-12-31' })
    expect(periodFromQuery(new URLSearchParams('period=this-quarter'), new Date(2026, 10, 9)).choice).toBe('this-quarter')
  })

  it('give D-395’s worked example on Monday 2026-11-09', () => {
    const today = '2026-11-09'
    const table = Object.fromEntries((['this-week', 'previous-week', 'past-month', 'previous-month', 'this-month',
      'this-quarter', 'previous-quarter', 'last-3-months', 'past-year', 'this-year', 'previous-year'] as Preset[])
      .map((p) => [p, compared(p, today)]))
    expect(table).toEqual({
      'this-week': { section: '2026-11-09 2026-11-09', previous: '2026-11-02 2026-11-02', year: '2025-11-10 2025-11-10', oneColumn: false },
      'previous-week': { section: '2026-11-02 2026-11-08', previous: '2026-10-26 2026-11-01', year: '2025-11-03 2025-11-09', oneColumn: false },
      'past-month': { section: '2026-10-10 2026-11-09', previous: '2026-09-10 2026-10-09', year: '2025-10-10 2025-11-09', oneColumn: false },
      'previous-month': { section: '2026-10-01 2026-10-31', previous: '2026-09-01 2026-09-30', year: '2025-10-01 2025-10-31', oneColumn: false },
      'this-month': { section: '2026-11-01 2026-11-09', previous: '2026-10-01 2026-10-09', year: '2025-11-01 2025-11-09', oneColumn: false },
      'this-quarter': { section: '2026-10-01 2026-11-09', previous: '2026-07-01 2026-08-09', year: '2025-10-01 2025-11-09', oneColumn: false },
      'previous-quarter': { section: '2026-07-01 2026-09-30', previous: '2026-04-01 2026-06-30', year: '2025-07-01 2025-09-30', oneColumn: false },
      'last-3-months': { section: '2026-09-01 2026-11-09', previous: '2026-06-01 2026-08-09', year: '2025-09-01 2025-11-09', oneColumn: false },
      'past-year': { section: '2025-11-10 2026-11-09', previous: '2024-11-10 2025-11-09', year: null, oneColumn: true },
      'this-year': { section: '2026-01-01 2026-11-09', previous: '2025-01-01 2025-11-09', year: null, oneColumn: true },
      'previous-year': { section: '2025-01-01 2025-12-31', previous: '2024-01-01 2024-12-31', year: null, oneColumn: true },
    })
  })

  it('compare a running period to date, and say so', () => {
    const running = comparisons({ from: '2026-11-01', to: '2026-11-30' }, '2026-11-09')!
    expect(running.running).toBe(true)
    expect(compareColumns(running).map((c) => c.role)).toEqual(['To date', 'Year earlier', 'Previous period'])
    expect(comparisons({ from: '2026-10-01', to: '2026-10-31' }, '2026-11-09')!.running).toBe(false)
    // The last day of a period isn't running: the whole period is there.
    expect(comparisons({ from: '2026-11-01', to: '2026-11-09' }, '2026-11-09')!.running).toBe(false)
  })

  it('“This month” on 30 and 31 March 2027: February clamps to its end', () => {
    expect(compared('this-month', '2027-03-30')).toEqual({ section: '2027-03-01 2027-03-30',
      previous: '2027-02-01 2027-02-28', year: '2026-03-01 2026-03-30', oneColumn: false })
    expect(compared('this-month', '2027-03-31')).toEqual({ section: '2027-03-01 2027-03-31',
      previous: '2027-02-01 2027-02-28', year: '2026-03-01 2026-03-31', oneColumn: false })
  })

  it('February 2027, Monday the 1st to Sunday the 28th: whole months win over weeks', () => {
    expect(described(comparisons({ from: '2027-02-01', to: '2027-02-28' }, '2027-06-01')!)).toEqual({
      section: '2027-02-01 2027-02-28', previous: '2027-01-01 2027-01-31', year: '2026-02-01 2026-02-28', oneColumn: false })
  })

  it('February 2029 against all of February 2028', () => {
    expect(described(comparisons({ from: '2029-02-01', to: '2029-02-28' }, '2029-06-01')!).year)
      .toBe('2028-02-01 2028-02-29')
  })

  it('on 29 February 2028 the past year and this year are one column each', () => {
    expect(compared('past-year', '2028-02-29')).toEqual({ section: '2027-03-01 2028-02-29',
      previous: '2026-03-01 2027-02-28', year: null, oneColumn: true })
    expect(compared('this-year', '2028-02-29')).toEqual({ section: '2028-01-01 2028-02-29',
      previous: '2027-01-01 2027-02-28', year: null, oneColumn: true })
  })

  it('two whole weeks: the two before, and 52 weeks back', () => {
    expect(described(comparisons({ from: '2026-11-02', to: '2026-11-15' }, '2026-12-01')!)).toEqual({
      section: '2026-11-02 2026-11-15', previous: '2026-10-19 2026-11-01', year: '2025-11-03 2025-11-16', oneColumn: false })
  })

  it('14 days that aren’t weeks: as many days before, and the same dates a year back', () => {
    expect(described(comparisons({ from: '2026-11-03', to: '2026-11-16' }, '2026-12-01')!)).toEqual({
      section: '2026-11-03 2026-11-16', previous: '2026-10-20 2026-11-02', year: '2025-11-03 2025-11-16', oneColumn: false })
  })

  it('18 whole months: the 18 before, no year earlier', () => {
    expect(described(comparisons({ from: '2025-01-01', to: '2026-06-30' }, '2026-11-09')!)).toEqual({
      section: '2025-01-01 2026-06-30', previous: '2023-07-01 2024-12-31', year: null, oneColumn: false })
  })

  it('13 whole weeks: the year earlier drawn by month, the previous period not (D-399)', () => {
    const c = comparisons({ from: '2026-08-03', to: '2026-11-01' }, '2026-12-01')!
    expect(chartByMonth(c)).toBe(true)
    expect(drawable(c).map((r) => r.role)).toEqual(['year'])
    expect(drawnComparison(c, new URLSearchParams('compare=previous'))!.role).toBe('year')
    // 52 weeks back may cover a month more: then nothing is drawn behind the months (D-405).
    const late = comparisons({ from: '2027-08-02', to: '2027-10-31' }, '2027-12-01')!
    expect(described(late).year).toBe('2026-08-03 2026-11-01')
    expect(drawable(late)).toEqual([])
    expect(drawnComparison(late, new URLSearchParams(''))).toBeNull()
  })

  it('“Last 3 months” draws either, the year earlier unless compare=previous (D-399)', () => {
    const c = comparisons({ from: '2026-09-01', to: '2026-11-30' }, '2026-11-09')!
    expect(drawable(c).map((r) => r.role)).toEqual(['year', 'previous'])
    expect(drawnComparison(c, new URLSearchParams(''))!.role).toBe('year')
    expect(drawnComparison(c, new URLSearchParams('compare=previous'))!.role).toBe('previous')
    // Two calendar months: bars by range.
    expect(chartByMonth(comparisons({ from: '2026-10-01', to: '2026-12-31' }, '2026-11-09')!)).toBe(false)
  })

  it('name whole months, quarters and years, and other ranges by their days', () => {
    // Intl puts thin spaces around a range's dash.
    const rangeLabel = (r: { from: string; to: string }) => label(r).replace(/[\u2009\u202f]/g, ' ')
    expect(rangeLabel({ from: '2026-10-01', to: '2026-10-31' })).toBe('Oct 2026')
    expect(rangeLabel({ from: '2026-07-01', to: '2026-09-30' })).toBe('Q3 2026')
    expect(rangeLabel({ from: '2025-01-01', to: '2025-12-31' })).toBe('2025')
    expect(rangeLabel({ from: '2026-09-01', to: '2026-11-30' })).toBe('Sep – Nov 2026')
    expect(rangeLabel({ from: '2026-11-02', to: '2026-11-08' })).toBe('Nov 2 – 8, 2026')
    expect(rangeLabel({ from: '2026-10-10', to: '2026-11-09' })).toBe('Oct 10 – Nov 9, 2026')
    expect(rangeLabel({ from: '2026-11-09', to: '2026-11-09' })).toBe('Nov 9, 2026')
  })
})

describe('the change in % (D-398)', () => {
  const text = (now: string | null | typeof MISSING, before: string | null | typeof MISSING) =>
    changeText(changeOf(now, before))

  it('is signed with one decimal, rounded half up', () => {
    expect(text('112.30', '100.00')).toBe('+12.3%')
    expect(text('96.00', '100.00')).toBe('−4.0%')
    expect(text('100.00', '100.00')).toBe('0.0%')
    expect(text('1000.50', '1000.00')).toBe('+0.1%')
    expect(text('999.50', '1000.00')).toBe('−0.1%')
    expect(text('1000000.00', '0.01')).toBe('+9,999,999,900.0%')
  })

  it('is a dash with nothing or 0 to compare with, −100.0% with nothing now, empty for a missing rate', () => {
    expect(text('5.00', null)).toBe('—')
    expect(text('5.00', '0.00')).toBe('—')
    expect(text(null, '5.00')).toBe('−100.0%')
    expect(text(MISSING, '5.00')).toBe('')
    expect(text('5.00', MISSING)).toBe('')
  })

  it('is a dash when the two figures have opposite signs (D-440)', () => {
    // A category whose refunds outweigh its spending in one of the two ranges.
    expect(text('50.00', '-100.00')).toBe('—')
    expect(text('-50.00', '100.00')).toBe('—')
    expect(effectOf(changeOf('-50.00', '100.00'), 'down')).toBeNull()
    // The same sign either way is a percentage.
    // Against the comparison's size, as before: a refund-heavy category that grew more negative went down.
    expect(text('-150.00', '-100.00')).toBe('−50.0%')
    expect(text('-50.00', '-100.00')).toBe('+50.0%')
    // Something against 0 stays "—", and 0 against something −100.0%, as before.
    expect(text('-5.00', '0.00')).toBe('—')
    expect(text('0.00', '5.00')).toBe('−100.0%')
    // Against a negative comparison by the same rule as before: back to 0 from −5 is +100.0%.
    expect(text('0.00', '-5.00')).toBe('+100.0%')
    expect(text(MISSING, '-5.00')).toBe('')
  })

  it('for the net is a dash across 0: opposite signs, or 0 on either side, and never coloured (D-439)', () => {
    const net = (now: string | null | typeof MISSING, before: string | null | typeof MISSING) =>
      changeText(changeOf(now, before, true))
    expect(net('800.00', '-250.00')).toBe('—')
    expect(net('-800.00', '250.00')).toBe('—')
    expect(net('0.00', '250.00')).toBe('—')
    expect(net(null, '250.00')).toBe('—')
    expect(net('250.00', '0.00')).toBe('—')
    expect(net('250.00', null)).toBe('—')
    expect(effectOf(changeOf('0.00', '250.00', true), 'up')).toBeNull()
    expect(effectOf(changeOf('800.00', '-250.00', true), 'up')).toBeNull()
    // The same sign is a percentage, as for any line.
    expect(net('800.00', '400.00')).toBe('+100.0%')
    expect(net('-300.00', '-200.00')).toBe('−50.0%')
    expect(net(MISSING, '250.00')).toBe('')
    expect(net('250.00', MISSING)).toBe('')
  })

  it('is coloured by its effect on the net', () => {
    expect(effectOf(changeOf('110', '100'), 'up')).toBe('better')
    expect(effectOf(changeOf('110', '100'), 'down')).toBe('worse')
    expect(effectOf(changeOf('90', '100'), 'down')).toBe('better')
    expect(effectOf(changeOf('100', '100'), 'up')).toBeNull()
    expect(effectOf(changeOf('100', null), 'up')).toBeNull()
    expect(effectOf(changeOf(MISSING, '1'), 'up')).toBeNull()
  })
})

describe('the comparison table (D-393)', () => {
  const row = (month: string, code: string, type: CashFlowRow['categoryType'], currency: string, total: string): CashFlowRow =>
    ({ month, categoryCode: code, categoryName: code[0] + code.slice(1).toLowerCase(), categoryType: type, currency, total })
  const c = comparisons({ from: '2026-10-01', to: '2026-10-31' }, '2026-11-09')!
  const columns = compareColumns(c)
  const period = cashFlowTables([row('2026-10', 'SALARY', 'INCOME', 'EUR', '1000.00'),
    row('2026-10', 'GROCERIES', 'EXPENSE', 'EUR', '200.00'), row('2026-10', 'TAXI', 'EXPENSE', 'USD', '10.00')], ['2026-10'])
  const year = cashFlowTables([row('2025-10', 'SALARY', 'INCOME', 'EUR', '900.00'),
    row('2025-10', 'RENT', 'EXPENSE', 'EUR', '500.00')], ['2025-10'])
  const previous = cashFlowTables([row('2026-09', 'GROCERIES', 'EXPENSE', 'EUR', '250.00')], ['2026-09'])

  it('has the period, the year earlier and the previous period as columns, each a range’s total', () => {
    expect(columns.map((col) => [col.role, col.label])).toEqual([
      ['Period', 'Oct 2026'], ['Year earlier', 'Oct 2025'], ['Previous period', 'Sep 2026']])
    const [eur, usd] = compareTables(columns, [period, year, previous], false)
    expect(eur.currency).toBe('EUR')
    expect(eur.income.lines.map((l) => [l.label, l.amounts])).toEqual([['Salary', ['1000', '900', null]]])
    // A category in a comparison only is listed, nothing in the period.
    expect(eur.expense.lines.map((l) => [l.label, l.amounts])).toEqual([
      ['Groceries', ['200', null, '250']], ['Rent', [null, '500', null]]])
    expect(eur.expense.subtotal.amounts).toEqual(['200', '500', '250'])
    expect(eur.net.amounts).toEqual(['800', '400', '-250'])
    // Each currency on its own; D-685: neither comparison has dollars, so only the period's column is left in USD.
    expect(usd.columns.map((col) => col.key)).toEqual(['period'])
    expect(usd.expense.subtotal.amounts).toEqual(['10'])
    expect(usd.income.subtotal.amounts).toEqual([null])
    expect(eur.columns.map((col) => col.key)).toEqual(['period', 'year', 'previous'])
  })

  it('gives the totals’ changes, the previous period’s first (D-400)', () => {
    const [eur] = compareTables(columns, [period, year, previous], false)
    expect(totalChanges(eur, eur.expense.subtotal).map((t) => [changeText(t.change), t.label]))
      .toEqual([['−20.0%', 'Sep 2026'], ['−60.0%', 'Oct 2025']])
    // 800 against the previous period's −250 is "—" (D-439); against the year earlier's 400, +100.0%.
    expect(totalChanges(eur, eur.net).map((t) => changeText(t.change))).toEqual(['—', '+100.0%'])
  })

  it('in the base currency marks a missing figure in every sum, and keeps the exchange lines without a %', () => {
    const converted = (month: string, total: string | null, realized: string): ConvertedCashFlow => ({
      currency: 'EUR',
      rows: [{ month, categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', total,
        missingRates: total === null ? [{ currency: 'KZT', from: `${month}-03`, to: `${month}-03`, days: 1 }] : [] }],
      exchangeResults: [{ month, realized, unrealized: '0.00', missingRates: [] }],
    })
    const t = compareTable('EUR', columns, [convertedCashFlowTable(converted('2026-10', null, '3.00'), ['2026-10']),
      convertedCashFlowTable(converted('2025-10', '40.00', '0.00'), ['2025-10']),
      convertedCashFlowTable(converted('2026-09', '50.00', '-1.00'), ['2026-09'])], true)
    expect(t.expense.subtotal.amounts).toEqual([MISSING, '40', '50'])
    expect(t.net.amounts).toEqual([MISSING, '-40', '-50'])
    expect(changeText(changeOf(t.net.amounts[0], t.net.amounts[1]))).toBe('')
    expect(t.exchange.map((l) => [l.label, l.amounts, l.good])).toEqual([
      // The year earlier's are 0, and shown so.
      ['Realized on exchanges', ['3', '0', '-1'], null], ['Revaluation of balances', ['0', '0', '0'], null]])
  })
})

describe('the chart’s data (D-399)', () => {
  const row = (month: string, type: CashFlowRow['categoryType'], total: string): CashFlowRow =>
    ({ month, categoryCode: type, categoryName: type, categoryType: type, currency: 'EUR', total })

  it('by range: a pair of bars per column, oldest first (D-413)', () => {
    const c = comparisons({ from: '2026-10-01', to: '2026-10-31' }, '2026-11-09')!
    const t = compareTable('EUR', compareColumns(c), [
      cashFlowTables([row('2026-10', 'INCOME', '10.00')], ['2026-10'])[0],
      cashFlowTables([row('2025-10', 'EXPENSE', '7.00')], ['2025-10'])[0], null], false)
    expect(rangeBars(t)).toEqual([
      { key: 'year', role: 'Year earlier', label: 'Oct 2025', income: null, expense: '7' },
      { key: 'previous', role: 'Previous period', label: 'Sep 2026', income: null, expense: null },
      { key: 'period', role: 'Period', label: 'Oct 2026', income: '10', expense: null },
    ])
    // The table keeps D-393's order.
    expect(t.columns.map((col) => col.key)).toEqual(['period', 'year', 'previous'])
  })

  it('by range with one comparison left: that one, then the period (D-413, D-406)', () => {
    const c = comparisons({ from: '2026-10-01', to: '2026-10-31' }, '2026-11-09')!
    const [period, year, previous] = compareColumns(c)
    const october = cashFlowTables([row('2026-10', 'INCOME', '10.00')], ['2026-10'])[0]
    const keys = (columns: typeof period[]) =>
      rangeBars(compareTable('EUR', columns, columns.map((col) => (col === period ? october : null)), false))
        .map((b) => b.key)
    expect(keys([period, previous])).toEqual(['previous', 'period'])
    expect(keys([period, year])).toEqual(['year', 'period'])
    expect(keys([period])).toEqual(['period'])
  })

  it('by range: the year earlier starts before the previous period on every preset (D-395, D-413)', () => {
    for (const preset of PRESETS) {
      for (const today of ['2026-11-09', '2026-01-01', '2026-03-31', '2024-02-29', '2026-12-31']) {
        const [y, m, d] = today.split('-').map(Number)
        const c = comparisons(presetRange(preset, new Date(y, m - 1, d)), today)
        if (!c) continue
        const year = c.ranges.find((r) => r.role === 'year')
        const previous = c.ranges.find((r) => r.role === 'previous')
        if (year && previous) expect(year.from < previous.from, `${preset} on ${today}`).toBe(true)
      }
    }
  })

  it('by month: each month with the comparison’s month in the same place', () => {
    const c = comparisons({ from: '2026-09-01', to: '2026-11-30' }, '2026-11-09')!
    const [period] = cashFlowTables([row('2026-09', 'INCOME', '10.00'), row('2026-11', 'EXPENSE', '4.00')],
      monthsBetween(c.section.from, c.section.to))
    const previous = c.ranges.find((r) => r.role === 'previous')!
    const [before] = cashFlowTables([row('2026-06', 'INCOME', '8.00')], monthsBetween(previous.from, previous.to))
    expect(monthPairs(period, c.section, before, previous)).toEqual([
      { month: '2026-09', income: '10', expense: null, before: { month: '2026-06', income: '8', expense: null } },
      { month: '2026-10', income: null, expense: null, before: { month: '2026-07', income: null, expense: null } },
      { month: '2026-11', note: 'to Nov 9', income: null, expense: '4', before: { month: '2026-08', income: null, expense: null } },
    ])
    expect(monthPairs(period, c.section, null, null)[0]).toEqual({ month: '2026-09', income: '10', expense: null })
  })
})
