import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { CashFlowRow, ConvertedCashFlow, Me } from './api'

// UX-5: income and expenses compared with the same period a year earlier and with the previous one, on the page
// (D-393, D-396, D-398, D-399, D-400, D-401). Today is Monday 9 November 2026.

const ME: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-11-09', features: { familyLedgers: false } }
const minus = '−'

/** The cash flow of a request's From: each month's salary and groceries, in EUR. */
const flows: Record<string, { income: string; groceries: string; missing?: boolean }> = {
  '2026-10-01': { income: '2000.00', groceries: '440.00' },
  '2025-10-01': { income: '1600.00', groceries: '400.00' },
  '2026-09-01': { income: '2000.00', groceries: '500.00', missing: true },
  '2026-11-01': { income: '500.00', groceries: '100.00' },
  '2025-11-01': { income: '400.00', groceries: '100.00' },
  '2025-01-01': { income: '24000.00', groceries: '5000.00' },
  '2024-01-01': { income: '20000.00', groceries: '5000.00' },
}

let calls: string[] = []
function stubApi({ failFrom }: { failFrom?: string } = {}) {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    const [path, query = ''] = url.split('?')
    const params = new URLSearchParams(query)
    const inBase = params.get('currency') === 'BASE'
    const from = params.get('from') ?? ''
    if (path === '/api/reports/cash-flow' && from === failFrom) {
      return new Response(JSON.stringify({ status: 500, detail: 'Something went wrong' }),
        { status: 500, headers: { 'Content-Type': 'application/problem+json' } })
    }
    const flow = flows[from]
    const month = from.slice(0, 7)
    const rows: CashFlowRow[] = flow === undefined ? [] : [
      { month, categoryCode: 'SALARY', categoryName: 'Salary', categoryType: 'INCOME', currency: 'EUR', total: flow.income },
      { month, categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', currency: 'EUR', total: flow.groceries },
    ]
    const converted: ConvertedCashFlow = {
      currency: 'EUR',
      rows: rows.map(({ currency, total, ...r }) => ({
        ...r, total: flow?.missing && r.categoryCode === 'GROCERIES' ? null : total,
        missingRates: flow?.missing && r.categoryCode === 'GROCERIES'
          ? [{ currency: 'KZT', from: '2026-09-04', to: '2026-09-04', days: 1 }] : [],
      })),
      exchangeResults: [],
    }
    const bodies: Record<string, unknown> = {
      '/api/entries': { content: [], page: 0, size: 1, totalElements: 12, totalPages: 12 },
      '/api/settings': { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' },
      '/api/reports/net-worth': inBase
        ? { currency: 'EUR', assets: '100.00', liabilities: '0.00', netWorth: '100.00', unrealizedRevaluation: '0.00',
          realizedExchangeResult: '0.00', rates: [], missingRates: [] }
        : [{ currency: 'EUR', assets: '100.00', liabilities: '0.00', netWorth: '100.00' }],
      '/api/reports/balances': [],
      '/api/reports/shared-settlement': [],
      '/api/reports/counterparty-balances': [],
      '/api/reports/cash-flow': inBase ? converted : rows,
    }
    const body = bodies[path]
    return new Response(JSON.stringify(body ?? { status: 404 }), {
      status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' },
    })
  }))
}

let search = ''
function Probe() {
  search = useLocation().search
  return null
}
const renderAt = (path: string) =>
  render(<MemoryRouter initialEntries={[path]}><App me={ME} /><Probe /></MemoryRouter>)

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const plain = (text: string | null | undefined) => (text ?? '').replace(/[   ]/g, ' ')
const flowsSection = () => screen.getByRole('heading', { name: /^Income and expenses/ }).closest('section')!

describe('income and expenses compared (UX-5)', () => {
  it('asks the period and both comparisons at once, and heads the table with them (D-393, D-401)', async () => {
    stubApi()
    renderAt('/?period=previous-month&currency=each')
    const table = await screen.findByRole('table', { name: 'Cash flow in EUR' })
    expect(calls.filter((u) => u.startsWith('/api/reports/cash-flow'))).toEqual([
      '/api/reports/cash-flow?from=2026-10-01&to=2026-10-31',
      '/api/reports/cash-flow?from=2025-10-01&to=2025-10-31',
      '/api/reports/cash-flow?from=2026-09-01&to=2026-09-30',
    ])
    expect(within(table).getAllByRole('columnheader').map((th) => th.textContent)).toEqual([
      'EUR', 'Period Oct 2026', 'Year earlier Oct 2025', 'Change %', 'Previous period Sep 2026', 'Change %'])
    const groceries = within(table).getByRole('rowheader', { name: 'Groceries' }).closest('tr')!
    expect(within(groceries).getAllByRole('cell').map((c) => c.textContent))
      .toEqual(['€440.00', '€400.00', '+10.0%', '€500.00', `${minus}12.0%`])
    expect(screen.getByRole('heading', { name: 'Cash flow by category' })).toBeDefined()
  })

  it('puts the changes under the totals, the previous period’s first (D-400)', async () => {
    stubApi()
    renderAt('/?period=previous-month&currency=each')
    const totals = await screen.findByLabelText('Totals in EUR')
    const changes = within(totals).getAllByRole('definition').filter((d) => d.className === 'changes').map((d) => d.textContent)
    expect(changes).toEqual([
      '0.0% vs Sep 2026 · +25.0% vs Oct 2025',
      `${minus}12.0% vs Sep 2026 · +10.0% vs Oct 2025`,
      '+4.0% vs Sep 2026 · +30.0% vs Oct 2025',
    ])
    // Expenses down is good, and coloured so; the net's first dd is still its amount.
    const expenses = within(totals).getAllByRole('term').find((t) => t.textContent === 'Expenses')!.parentElement!
    expect(expenses.querySelector('.changes .better')!.textContent).toBe(`${minus}12.0%`)
    expect(expenses.querySelector('.changes .worse')!.textContent).toBe('+10.0%')
    const net = within(totals).getAllByRole('term').find((t) => t.textContent === 'Net')!
    expect(net.nextElementSibling!.textContent).toBe('+€1,560.00')
  })

  it('compares a running month to date and says so (D-396)', async () => {
    stubApi()
    renderAt('/?currency=each')
    await screen.findByRole('table', { name: 'Cash flow in EUR' })
    expect(plain(flowsSection().querySelector('h3')!.textContent)).toBe('Income and expenses Nov 1, 2026 – Nov 9, 2026, to date')
    expect(calls.filter((u) => u.startsWith('/api/reports/cash-flow'))).toEqual([
      '/api/reports/cash-flow?from=2026-11-01&to=2026-11-09',
      '/api/reports/cash-flow?from=2025-11-01&to=2025-11-09',
      '/api/reports/cash-flow?from=2026-10-01&to=2026-10-09',
    ])
    const headers = within(screen.getByRole('table', { name: 'Cash flow in EUR' })).getAllByRole('columnheader')
    expect(plain(headers[1].textContent)).toBe('To date Nov 1 – 9, 2026')
  })

  it('counts the comparisons’ missing rates in the notice, and leaves their % empty (D-398)', async () => {
    stubApi()
    renderAt('/?period=previous-month')
    const table = await screen.findByRole('table', { name: 'Cash flow in EUR' })
    expect(screen.getByRole('status').textContent).toContain('KZT on Sep 4, 2026')
    const groceries = within(table).getByRole('rowheader', { name: 'Groceries' }).closest('tr')!
    expect(within(groceries).getAllByRole('cell').map((c) => c.textContent))
      .toEqual(['€440.00', '€400.00', '+10.0%', 'rate missing', ''])
  })

  it('shows a comparison that failed as an error, and keeps the period and the other one (D-401)', async () => {
    stubApi({ failFrom: '2025-10-01' })
    renderAt('/?period=previous-month&currency=each')
    const table = await screen.findByRole('table', { name: 'Cash flow in EUR' })
    expect(within(table).getAllByRole('columnheader').map((th) => th.textContent)).toEqual([
      'EUR', 'Period Oct 2026', 'Previous period Sep 2026', 'Change %'])
    expect(within(flowsSection()).getByText(/^Year earlier \(Oct 2025\):/)).toBeDefined()
  })

  it('offers “Compare with” for three months or more where both can be drawn, kept in the URL (D-399)', async () => {
    stubApi()
    renderAt('/?period=last-3-months&currency=each')
    const compare = await screen.findByRole('group', { name: 'Compare with' })
    const year = within(compare).getByRole('button', { name: 'A year earlier' })
    const previous = within(compare).getByRole('button', { name: 'Previous period' })
    expect(year.getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(previous)
    await waitFor(() => expect(new URLSearchParams(search).get('compare')).toBe('previous'))
    expect(within(screen.getByRole('group', { name: 'Compare with' })).getByRole('button', { name: 'Previous period' })
      .getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(within(screen.getByRole('group', { name: 'Compare with' })).getByRole('button', { name: 'A year earlier' }))
    await waitFor(() => expect(new URLSearchParams(search).has('compare')).toBe(false))
  })

  it('has no “Compare with” for fewer than three months, or a year-long period’s one column', async () => {
    stubApi()
    renderAt('/?period=previous-month')
    await screen.findByRole('table', { name: 'Cash flow in EUR' })
    expect(screen.queryByRole('group', { name: 'Compare with' })).toBeNull()
    cleanup()
    stubApi()
    renderAt('/?period=previous-year')
    const table = await screen.findByRole('table', { name: 'Cash flow in EUR' })
    expect(within(table).getAllByRole('columnheader').map((th) => th.textContent))
      .toEqual(['EUR', 'Period 2025', 'Previous year 2024', 'Change %'])
    expect(screen.queryByRole('group', { name: 'Compare with' })).toBeNull()
  })
})
