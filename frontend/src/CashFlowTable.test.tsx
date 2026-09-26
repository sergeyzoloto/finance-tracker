import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { CashFlowRow, CategoryType, ConvertedCashFlow } from './api'
import CashFlowTable from './CashFlowTable'
import { cashFlowTables, convertedCashFlowTable, monthLabel, monthsBetween } from './dashboard'
import { formatMoney } from './money'

afterEach(cleanup)

const row = (month: string, categoryCode: string, categoryName: string, categoryType: CategoryType, currency: string,
  total: string): CashFlowRow => ({ month, categoryCode, categoryName, categoryType, currency, total })

// The cash flow report for January to March 2024, as the backend sends it: nothing in January.
const report: CashFlowRow[] = [
  row('2024-02', 'GROCERIES', 'Groceries', 'EXPENSE', 'RUB', '100.10'),
  row('2024-02', 'TRANSPORT', 'Transport', 'EXPENSE', 'RUB', '-0.10'), // a refund larger than the month's fares
  row('2024-03', 'EATING_OUT', 'Eating out', 'EXPENSE', 'EUR', '25.80'),
  row('2024-03', 'EATING_OUT', 'Eating out', 'EXPENSE', 'RUB', '422.78'),
  row('2024-03', 'GROCERIES', 'Groceries', 'EXPENSE', 'RUB', '3114.50'),
  row('2024-03', 'INTEREST', 'Interest', 'INCOME', 'EUR', '12.34'),
  row('2024-03', 'INTEREST', 'Interest', 'INCOME', 'RUB', '26.97'),
  row('2024-03', 'SALARY', 'Salary', 'INCOME', 'RUB', '85000.00'),
]

function renderReport() {
  const tables = cashFlowTables(report, monthsBetween('2024-01-01', '2024-03-31'))
  render(<>{tables.map((table) => <CashFlowTable key={table.currency} table={table} />)}</>)
}

const table = (currency: string) => screen.getByRole('table', { name: `Cash flow in ${currency}` })

/** A row's cells after its label: one per month, then the total. */
function cells(currency: string, label: string) {
  const tr = within(table(currency)).getByRole('rowheader', { name: label }).closest('tr')!
  return within(tr).getAllByRole('cell').map((cell) => cell.textContent)
}

const rub = (amount: string) => formatMoney(amount, 'RUB')
const eur = (amount: string) => formatMoney(amount, 'EUR')

describe('Cash flow table', () => {
  it('has a column per month of the period, empty ones included, and a total column', () => {
    renderReport()
    const headers = within(table('RUB')).getAllByRole('columnheader').map((th) => th.textContent)
    expect(headers).toEqual(['RUB', monthLabel('2024-01'), monthLabel('2024-02'), monthLabel('2024-03'), 'Total'])
  })

  it('lists the categories alphabetically, each section with its subtotal, and the net last', () => {
    renderReport()
    expect(within(table('RUB')).getAllByRole('rowheader').map((th) => th.textContent)).toEqual([
      'Income', 'Interest', 'Salary', 'Total income',
      'Expenses', 'Eating out', 'Groceries', 'Transport', 'Total expenses',
      'Net',
    ])
  })

  it('adds up every category, both sections and the net, per month and in total', () => {
    renderReport()
    expect(cells('RUB', 'Groceries')).toEqual(['—', rub('100.10'), rub('3114.50'), rub('3214.60')])
    expect(cells('RUB', 'Total income')).toEqual(['—', '—', rub('85026.97'), rub('85026.97')])
    // February's refund outweighs its fares, so it lowers the month's expenses.
    expect(cells('RUB', 'Transport')).toEqual(['—', rub('-0.10'), '—', rub('-0.10')])
    expect(cells('RUB', 'Total expenses')).toEqual(['—', rub('100.00'), rub('3537.28'), rub('3637.28')])
    expect(cells('RUB', 'Net')).toEqual(['—', rub('-100.00'), rub('81489.69'), rub('81389.69')])
  })

  it('never adds amounts in different currencies together', () => {
    renderReport()
    expect(screen.getAllByRole('table').map((t) => t.getAttribute('aria-label')))
      .toEqual(['Cash flow in EUR', 'Cash flow in RUB'])
    expect(cells('EUR', 'Total income')).toEqual(['—', '—', eur('12.34'), eur('12.34')])
    expect(cells('EUR', 'Total expenses')).toEqual(['—', '—', eur('25.80'), eur('25.80')])
    expect(cells('EUR', 'Net')).toEqual(['—', '—', eur('-13.46'), eur('-13.46')])
  })

  it('shows an empty section with a dash for its subtotal', () => {
    render(<CashFlowTable table={cashFlowTables([report[0]], ['2024-02'])[0]} />)
    expect(cells('RUB', 'Total income')).toEqual(['—', '—'])
    expect(cells('RUB', 'Net')).toEqual([rub('-100.10'), rub('-100.10')])
  })
})

describe('Cash flow table in the base currency', () => {
  const report: ConvertedCashFlow = {
    currency: 'EUR',
    rows: [
      { month: '2024-03', categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', total: null,
        missingRates: [{ currency: 'KZT', from: '2024-03-05', to: '2024-03-05', days: 1 }] },
      { month: '2024-03', categoryCode: 'SALARY', categoryName: 'Salary', categoryType: 'INCOME', total: '2000.00', missingRates: [] },
    ],
    exchangeResults: [{ month: '2024-03', realized: '10.00', unrealized: '-2.50', missingRates: [] }],
  }

  it('marks what can’t be converted, and every total it is part of, instead of adding it up as zero', () => {
    render(<CashFlowTable table={convertedCashFlowTable(report, ['2024-03'])} />)
    expect(cells('EUR', 'Groceries')).toEqual(['rate missing', 'rate missing'])
    expect(cells('EUR', 'Total expenses')).toEqual(['rate missing', 'rate missing'])
    expect(cells('EUR', 'Total income')).toEqual([eur('2000.00'), eur('2000.00')])
    expect(cells('EUR', 'Net')).toEqual(['rate missing', 'rate missing'])
  })

  it('shows what exchange rates did after the net, which leaves them out', () => {
    render(<CashFlowTable table={convertedCashFlowTable(report, ['2024-03'])} />)
    expect(within(table('EUR')).getAllByRole('rowheader').map((th) => th.textContent).slice(-4)).toEqual([
      'Net', 'Exchange rates (not in the net)', 'Realized on exchanges', 'Revaluation of balances',
    ])
    expect(cells('EUR', 'Realized on exchanges')).toEqual([eur('10.00'), eur('10.00')])
    expect(cells('EUR', 'Revaluation of balances')).toEqual([eur('-2.50'), eur('-2.50')])
  })
})
