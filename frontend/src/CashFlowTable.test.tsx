import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { CashFlowRow, CategoryType, ConvertedCashFlow } from './api'
import CashFlowTable from './CashFlowTable'
import {
  cashFlowTables, compareColumns, comparisons, compareTable, compareTables, convertedCashFlowTable, monthsBetween,
  type DayRange,
} from './dashboard'
import { formatMoney } from './money'

// UX-5 (D-393, D-398): the cash flow by category compares ranges; no month columns.

afterEach(cleanup)

const row = (month: string, categoryCode: string, categoryName: string, categoryType: CategoryType, currency: string,
  total: string): CashFlowRow => ({ month, categoryCode, categoryName, categoryType, currency, total })

// October 2026, compared with October 2025 and September 2026, on 9 November 2026.
const compared = comparisons({ from: '2026-10-01', to: '2026-10-31' }, '2026-11-09')!
const columns = compareColumns(compared)
const monthly = (range: DayRange, rows: CashFlowRow[]) => cashFlowTables(rows, monthsBetween(range.from, range.to))
const [, year, previous] = columns.map((c) => c.range)
const october = monthly(compared.section, [
  row('2026-10', 'SALARY', 'Salary', 'INCOME', 'EUR', '2000.00'),
  row('2026-10', 'GROCERIES', 'Groceries', 'EXPENSE', 'EUR', '330.00'),
  row('2026-10', 'TRANSPORT', 'Transport', 'EXPENSE', 'EUR', '-0.10'), // a refund larger than the month's fares
  row('2026-10', 'GROCERIES', 'Groceries', 'EXPENSE', 'RUB', '3114.50'),
])
const yearEarlier = monthly(year, [
  row('2025-10', 'SALARY', 'Salary', 'INCOME', 'EUR', '1600.00'),
  row('2025-10', 'GROCERIES', 'Groceries', 'EXPENSE', 'EUR', '300.00'),
  row('2025-10', 'RENT', 'Rent', 'EXPENSE', 'EUR', '700.00'),
])
const september = monthly(previous, [
  row('2026-09', 'SALARY', 'Salary', 'INCOME', 'EUR', '2000.00'),
  row('2026-09', 'GROCERIES', 'Groceries', 'EXPENSE', 'EUR', '0.00'),
])

function renderTables() {
  const tables = compareTables(columns, [october, yearEarlier, september], false)
  render(<>{tables.map((t) => <CashFlowTable key={t.currency} table={t} />)}</>)
}

const table = (currency: string) => screen.getByRole('table', { name: `Cash flow in ${currency}` })
/** A row's cells after its label: the period, its share (D-595), then each comparison's amount and change. */
function cells(currency: string, label: string) {
  const tr = within(table(currency)).getByRole('rowheader', { name: label }).closest('tr')!
  return within(tr).getAllByRole('cell').map((cell) => cell.textContent)
}
const eur = (amount: string) => formatMoney(amount, 'EUR')
const rub = (amount: string) => formatMoney(amount, 'RUB')
const minus = '−'

describe('the comparison table', () => {
  it('heads each column with its role above its range', () => {
    renderTables()
    const headers = within(table('EUR')).getAllByRole('columnheader').map((th) => th.textContent)
    expect(headers).toEqual(['EUR', 'Period Oct 2026', 'Share %', 'Year earlier Oct 2025', 'Change %',
      'Previous period Sep 2026', 'Change %'])
  })

  it('lists every category of any range, alphabetically, with the subtotals and the net', () => {
    renderTables()
    expect(within(table('EUR')).getAllByRole('rowheader').map((th) => th.textContent)).toEqual([
      'Income', 'Salary', 'Total income',
      'Expenses', 'Groceries', 'Rent', 'Transport', 'Total expenses',
      'Net',
    ])
  })

  it('gives each range’s total and the change in %, a dash with nothing or 0 to compare with', () => {
    renderTables()
    expect(cells('EUR', 'Salary')).toEqual([eur('2000.00'), '100.0%', eur('1600.00'), '+25.0%', eur('2000.00'), '0.0%'])
    // 330.00 of 329.90: a refund elsewhere makes a line more than its section.
    expect(cells('EUR', 'Groceries')).toEqual([eur('330.00'), '100.0%', eur('300.00'), '+10.0%', eur('0.00'), '—'])
    // In a comparison only: a dash in the period, and −100.0% against it.
    expect(cells('EUR', 'Rent')).toEqual(['—', '—', eur('700.00'), `${minus}100.0%`, '—', '—'])
    // Refunds larger than the line: no share.
    expect(cells('EUR', 'Transport')[1]).toBe('—')
    expect(cells('EUR', 'Total expenses')).toEqual([eur('329.90'), '', eur('1000.00'), `${minus}67.0%`, eur('0.00'), '—'])
    expect(cells('EUR', 'Net')).toEqual([eur('1670.10'), '', eur('600.00'), '+178.4%', eur('2000.00'), `${minus}16.5%`])
  })

  it('colours a change by its effect on the net, the sign always shown', () => {
    renderTables()
    const change = (label: string, i: number) =>
      within(table('EUR')).getByRole('rowheader', { name: label }).closest('tr')!.querySelectorAll('td.change')[i]
    expect(change('Total income', 0)!.className).toContain('better')
    expect(change('Total expenses', 0)!.className).toContain('better')
    expect(change('Groceries', 0)!.className).toContain('worse')
    expect(change('Salary', 1)!.className).not.toMatch(/better|worse/)
  })

  it('keeps each currency in its own table, without the comparisons that have nothing in it (D-685)', () => {
    renderTables()
    expect(screen.getAllByRole('table').map((t) => t.getAttribute('aria-label'))).toEqual(['Cash flow in EUR', 'Cash flow in RUB'])
    // Neither comparison has roubles: their columns go, in RUB only; EUR keeps both.
    expect(within(table('RUB')).getAllByRole('columnheader').map((th) => th.textContent))
      .toEqual(['RUB', 'Period Oct 2026', 'Share %'])
    expect(cells('RUB', 'Groceries')).toEqual([rub('3114.50'), '100.0%'])
    expect(within(table('EUR')).getAllByRole('columnheader')).toHaveLength(7)
  })

  it('in the base currency marks a missing figure and leaves its % empty; exchange lines have no %', () => {
    const converted = (month: string, groceries: string | null, realized: string): ConvertedCashFlow => ({
      currency: 'EUR',
      rows: [{ month, categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', total: groceries,
        missingRates: groceries === null ? [{ currency: 'KZT', from: `${month}-05`, to: `${month}-05`, days: 1 }] : [] }],
      exchangeResults: [{ month, realized, unrealized: '0.00', missingRates: [] }],
    })
    const t = compareTable('EUR', columns, [
      convertedCashFlowTable(converted('2026-10', null, '10.00'), ['2026-10']),
      convertedCashFlowTable(converted('2025-10', '40.00', '0.00'), ['2025-10']),
      convertedCashFlowTable(converted('2026-09', '50.00', '-2.50'), ['2026-09']),
    ], true)
    render(<CashFlowTable table={t} />)
    expect(cells('EUR', 'Groceries')).toEqual(['rate missing', '—', eur('40.00'), '', eur('50.00'), ''])
    expect(cells('EUR', 'Net')).toEqual(['rate missing', '', eur('-40.00'), '', eur('-50.00'), ''])
    expect(within(table('EUR')).getAllByRole('rowheader').map((th) => th.textContent).slice(-3)).toEqual([
      'Exchange rates (not in the net)', 'Realized on exchanges', 'Revaluation of balances'])
    expect(cells('EUR', 'Realized on exchanges')).toEqual([eur('10.00'), '', eur('0.00'), '', eur('-2.50'), ''])
  })

  it('has one comparison headed “Previous year” for a year-long period', () => {
    const c = comparisons({ from: '2025-01-01', to: '2025-12-31' }, '2026-11-09')!
    const t = compareTable('EUR', compareColumns(c), [
      cashFlowTables([row('2025-03', 'SALARY', 'Salary', 'INCOME', 'EUR', '110.00')], monthsBetween('2025-01-01', '2025-12-31'))[0],
      cashFlowTables([row('2024-03', 'SALARY', 'Salary', 'INCOME', 'EUR', '100.00')], monthsBetween('2024-01-01', '2024-12-31'))[0],
    ], false)
    render(<CashFlowTable table={t} />)
    expect(within(table('EUR')).getAllByRole('columnheader').map((th) => th.textContent))
      .toEqual(['EUR', 'Period 2025', 'Share %', 'Previous year 2024', 'Change %'])
    expect(cells('EUR', 'Salary')).toEqual([eur('110.00'), '100.0%', eur('100.00'), '+10.0%'])
  })
})
