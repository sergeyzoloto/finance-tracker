import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { CashFlowRow, ConvertedCashFlow } from './api'
import CashFlowTable from './CashFlowTable'
import { cashFlowTables, compareColumns, compareTable, comparisons, convertedCashFlowTable } from './dashboard'

// UX-6 (D-419): in "Cash flow by category" a family budget's category is marked by a small "shared" icon named
// "Shared: <the budget>", and the budget's name follows it only when the table holds more than one budget's categories.
// The scope's controls (D-420) are in Dashboard.test.tsx and dashboard.test.ts.

afterEach(cleanup)

const row = (categoryCode: string, total: string, family?: [number, string], categoryType: CashFlowRow['categoryType'] = 'EXPENSE'): CashFlowRow => ({
  month: '2026-09', categoryCode, categoryName: categoryCode[0] + categoryCode.slice(1).toLowerCase(), categoryType,
  currency: 'EUR', total,
  ...(family === undefined ? {} : { familyLedgerId: family[0], familyLedgerName: family[1] }),
})
const HOME: [number, string] = [7, 'Home']
const COTTAGE: [number, string] = [9, 'Cottage']

function renderTable(rows: CashFlowRow[]) {
  const [table] = cashFlowTables(rows, ['2026-09'])
  const c = comparisons({ from: '2026-09-01', to: '2026-09-30' }, '2026-10-05')!
  render(<CashFlowTable table={compareTable('EUR', compareColumns(c), [table, null, null], false)} />)
  return screen.getByRole('table', { name: 'Cash flow in EUR' })
}

const header = (line: HTMLElement) => within(line).getByRole('rowheader')

describe('the shared icon (D-419)', () => {
  it('marks a family budget’s category with one budget in the table, without the budget’s name', () => {
    const table = renderTable([row('GROCERIES', '4.00'), row('GROCERIES', '32.25', HOME),
      row('SALARY', '100.00', HOME, 'INCOME')])
    const icons = within(table).getAllByRole('img')
    expect(icons.map((i) => i.getAttribute('aria-label'))).toEqual(['Shared: Home', 'Shared: Home'])
    const icon = icons[1]
    // Its title, its colour and size, after the category's name.
    expect(icon.querySelector('title')!.textContent).toBe('Shared: Home')
    expect(icon.getAttribute('fill')).toBe('currentColor')
    expect(icon.getAttribute('height')).toBe('1em')
    expect(icon.tagName.toLowerCase()).toBe('svg')
    const line = icon.closest('tr')!
    // The name, then the icon (its title is its only text).
    expect(header(line).firstChild!.textContent).toBe('Groceries')
    expect(header(line).textContent).toBe('GroceriesShared: Home')
    // The user's own Groceries has no icon, and nothing says "Home" in words.
    const own = within(table).getAllByRole('row').find((r) => r !== line && within(r).queryByText('Groceries'))!
    expect(within(own).queryByRole('img')).toBeNull()
    expect(within(table).queryByText('Home')).toBeNull()
    // The old badge is gone.
    expect(table.querySelector('.badge')).toBeNull()
  })

  it('names the budget after the icon when the table holds two budgets’ categories', () => {
    const table = renderTable([row('GROCERIES', '32.25', HOME), row('GROCERIES', '10.00', COTTAGE)])
    const home = within(table).getByRole('img', { name: 'Shared: Home' }).closest('tr')!
    const cottage = within(table).getByRole('img', { name: 'Shared: Cottage' }).closest('tr')!
    expect(header(home).textContent).toBe('GroceriesShared: HomeHome')
    expect(within(home).getByText('Home').className).toBe('shared-name')
    expect(header(cottage).textContent).toBe('GroceriesShared: CottageCottage')
    expect(within(cottage).getAllByRole('cell')[0].textContent).toBe('€10.00')
  })

  it('does the same in the base currency', () => {
    const report: ConvertedCashFlow = {
      currency: 'EUR', exchangeResults: [],
      rows: [row('GROCERIES', '32.25', HOME), row('RENT', '24.00', COTTAGE)]
        .map(({ currency, ...r }) => ({ ...r, missingRates: [] })),
    }
    const c = comparisons({ from: '2026-09-01', to: '2026-09-30' }, '2026-10-05')!
    render(<CashFlowTable table={compareTable('EUR', compareColumns(c),
      [convertedCashFlowTable(report, ['2026-09']), null, null], true)} />)
    expect(screen.getAllByRole('img').map((i) => i.getAttribute('aria-label')))
      .toEqual(['Shared: Home', 'Shared: Cottage'])
    expect(screen.getByText('Cottage').className).toBe('shared-name')
  })
})
