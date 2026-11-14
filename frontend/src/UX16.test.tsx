import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { CashFlowRow, Me } from './api'

// UX-16 on the dashboard (D-648, D-650, D-652): the chart drawn at once, with no chunk to load; by month with the
// comparison's markers; by year for a section of more than 24 calendar months, with no "Compare with". Today is
// Monday 9 November 2026.

const ME: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-11-09', features: { familyLedgers: false } }

const row = (month: string, type: 'INCOME' | 'EXPENSE', total: string): CashFlowRow => ({
  month, categoryCode: type === 'INCOME' ? 'SALARY' : 'GROCERIES', categoryName: type === 'INCOME' ? 'Salary' : 'Groceries',
  categoryType: type, currency: 'EUR', total,
})

/** Each request's cash flow by its From; every other range has none. */
const flows: Record<string, CashFlowRow[]> = {
  // Previous month.
  '2026-10-01': [row('2026-10', 'INCOME', '2100.00'), row('2026-10', 'EXPENSE', '450.00')],
  // Last 3 months (Sep to Nov 2026, to date) and a year earlier.
  '2026-09-01': [row('2026-09', 'INCOME', '2000.00'), row('2026-09', 'EXPENSE', '500.00'),
    row('2026-11', 'INCOME', '2100.00'), row('2026-11', 'EXPENSE', '450.00')],
  '2025-09-01': [row('2025-09', 'INCOME', '1800.00'), row('2025-11', 'EXPENSE', '900.00')],
  // Their previous period, so that "Compare with" offers both (D-685).
  '2026-06-01': [row('2026-06', 'INCOME', '1900.00'), row('2026-07', 'EXPENSE', '480.00')],
  // The 35 months' previous period, so that the table keeps it (D-685).
  '2021-01-01': [row('2022-05', 'INCOME', '700.00')],
  // A custom range of 35 months.
  '2023-12-01': [row('2023-12', 'INCOME', '100.00'), row('2024-03', 'INCOME', '1000.00'), row('2024-03', 'EXPENSE', '250.25'),
    row('2025-07', 'EXPENSE', '40.00'), row('2026-10', 'INCOME', '9.99')],
}

function stubApi() {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const [path, query = ''] = url.split('?')
    const from = new URLSearchParams(query).get('from') ?? ''
    const bodies: Record<string, unknown> = {
      '/api/entries': { content: [], page: 0, size: 1, totalElements: 12, totalPages: 12 },
      '/api/settings': { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' },
      '/api/reports/net-worth': [{ currency: 'EUR', assets: '100.00', liabilities: '0.00', netWorth: '100.00' }],
      '/api/reports/balances': [],
      '/api/reports/shared-settlement': [],
      '/api/reports/counterparty-balances': [],
      '/api/reports/cash-flow': flows[from] ?? [],
      '/api/reports/daily-cash-flow': [],
    }
    const body = bodies[path]
    return new Response(JSON.stringify(body ?? { status: 404 }), {
      status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' },
    })
  }))
}

const renderAt = (path: string) => render(<MemoryRouter initialEntries={[path]}><App me={ME} /></MemoryRouter>)

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const rowLabels = (figure: HTMLElement) => [...figure.querySelectorAll('.ie-row .ie-main')].map((e) => e.textContent)

describe('the chart on the dashboard (UX-16)', () => {
  it('is drawn at once, with no chunk to wait for (D-648)', async () => {
    stubApi()
    renderAt('/?period=previous-month&currency=each')
    const figure = await screen.findByRole('figure', { name: 'Income and expenses in EUR' })
    expect(figure.querySelector('.ie-chart.ie-ranges')).not.toBeNull()
    expect(screen.queryByText('Loading chart…')).toBeNull()
    expect(document.querySelector('.chart-placeholder')).toBeNull()
  })

  it('marks a year earlier on each month’s bars where it has a figure (D-650)', async () => {
    stubApi()
    renderAt('/?period=last-3-months&currency=each')
    const figure = await screen.findByRole('figure', { name: 'Income and expenses in EUR' })
    expect(screen.getByRole('group', { name: 'Compare with' })).toBeDefined()
    expect(rowLabels(figure)).toEqual(['Sep 2026', 'Oct 2026', 'Nov 2026'])
    expect(figure.querySelector('.ie-legend')!.textContent).toBe('IncomeExpensesa year earlier')
    // Sep's income (1800) and Nov's expenses (900) a year earlier; nothing else.
    expect([...figure.querySelectorAll('.ie-row')].map((r) => r.querySelectorAll('.ie-marker').length)).toEqual([1, 0, 1])
  })

  it('draws a section of more than 24 months by calendar year, with no “Compare with”, the table keeping its comparison (D-652)', async () => {
    stubApi()
    renderAt('/?from=2023-12-01&to=2026-10-31&currency=each')
    const figure = await screen.findByRole('figure', { name: 'Income and expenses in EUR' })
    expect(figure.querySelector('.ie-chart.ie-years')).not.toBeNull()
    expect(rowLabels(figure)).toEqual(['2023', '2024', '2025', '2026'])
    const table = within(figure).getByRole('table', { name: 'Income and expenses in EUR' })
    expect([...table.querySelectorAll('tbody tr')].map((tr) => [...tr.querySelectorAll('th, td')].map((c) => c.textContent)))
      .toEqual([
        ['2023, from Dec 1', '€100.00', '—'],
        ['2024', '€1,000.00', '€250.25'],
        ['2025', '—', '€40.00'],
        ['2026, to Oct 31', '€9.99', '—'],
      ])
    expect(figure.querySelectorAll('.ie-marker')).toHaveLength(0)
    expect(figure.querySelector('.ie-legend')!.textContent).toBe('IncomeExpenses')
    expect(screen.queryByRole('group', { name: 'Compare with' })).toBeNull()
    const cashFlow = await screen.findByRole('table', { name: 'Cash flow in EUR' })
    expect(within(cashFlow).getAllByRole('columnheader').map((th) => th.textContent)[3]).toMatch(/^Previous period/)
  })
})
