import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { CashFlowRow, DailyCashFlowRow, Me } from './api'
import { hasDailyFigure } from './daily'
import { fromFirstFigure, MISSING, type MonthPair, type YearRow } from './dashboard'
import IncomeExpenseChart from './IncomeExpenseChart'

// UX-17 (D-684 to D-687): where nothing was posted, the dashboard shows nothing. A comparison with nothing in a
// currency is left out of that currency's table, chart and strip; a chart starts at its first row with a figure and
// isn't drawn without one; daily spending only with a figure; the hover panel leaves an empty comparison month out.
// Today is Monday 9 November 2026.

const ME: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-11-09', features: { familyLedgers: false } }

const row = (month: string, type: 'INCOME' | 'EXPENSE', currency: string, total: string): CashFlowRow => ({
  month, categoryCode: type === 'INCOME' ? 'SALARY' : 'GROCERIES', categoryName: type === 'INCOME' ? 'Salary' : 'Groceries',
  categoryType: type, currency, total,
})
const day = (date: string, currency: string, total: string): DailyCashFlowRow => ({
  day: date, categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', currency, total,
})

/** The api, each cash flow by its From, each daily cash flow by its From; nothing for any other range. */
function stubApi(flows: Record<string, CashFlowRow[]>, days: Record<string, DailyCashFlowRow[]> = {}, failFrom?: string) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const [path, query = ''] = url.split('?')
    const from = new URLSearchParams(query).get('from') ?? ''
    if (path === '/api/reports/cash-flow' && from === failFrom) {
      return new Response(JSON.stringify({ status: 500, detail: 'Something went wrong' }),
        { status: 500, headers: { 'Content-Type': 'application/problem+json' } })
    }
    const bodies: Record<string, unknown> = {
      '/api/entries': { content: [], page: 0, size: 1, totalElements: 12, totalPages: 12 },
      '/api/settings': { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' },
      '/api/reports/net-worth': [{ currency: 'EUR', assets: '100.00', liabilities: '0.00', netWorth: '100.00' }],
      '/api/reports/balances': [],
      '/api/reports/shared-settlement': [],
      '/api/reports/counterparty-balances': [],
      '/api/reports/cash-flow': flows[from] ?? [],
      '/api/reports/daily-cash-flow': days[from] ?? [],
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

const headers = (table: HTMLElement) => within(table).getAllByRole('columnheader')
  .map((th) => th.textContent!.replace(/\s+/g, ' '))
const rowLabels = (figure: HTMLElement) => [...figure.querySelectorAll('.ie-row .ie-main')].map((e) => e.textContent)
const markers = (figure: HTMLElement) => [...figure.querySelectorAll('.ie-row')].map((r) => r.querySelectorAll('.ie-marker').length)

/** Previous month (Oct 2026): euros in every range; dollars in the period and the previous period only. */
const OCTOBER = {
  '2026-10-01': [row('2026-10', 'INCOME', 'EUR', '2100.00'), row('2026-10', 'EXPENSE', 'EUR', '450.00'),
    row('2026-10', 'EXPENSE', 'USD', '30.00')],
  '2025-10-01': [row('2025-10', 'INCOME', 'EUR', '2000.00'), row('2025-10', 'EXPENSE', 'EUR', '400.00')],
  '2026-09-01': [row('2026-09', 'INCOME', 'EUR', '2050.00'), row('2026-09', 'EXPENSE', 'EUR', '500.00'),
    row('2026-09', 'EXPENSE', 'USD', '25.00')],
}

describe('a comparison with nothing posted in a currency is left out of it (D-685)', () => {
  it('loses its columns in that currency’s table, while another currency keeps its own', async () => {
    stubApi(OCTOBER)
    renderAt('/?period=previous-month&currency=each')
    const usd = await screen.findByRole('table', { name: 'Cash flow in USD' })
    expect(headers(usd)).toEqual(['USD', 'Period Oct 2026', 'Share %', 'Previous period Sep 2026', 'Change %'])
    expect(headers(screen.getByRole('table', { name: 'Cash flow in EUR' }))).toEqual(['EUR', 'Period Oct 2026', 'Share %',
      'Year earlier Oct 2025', 'Change %', 'Previous period Sep 2026', 'Change %'])
  })

  it('loses its row on the chart by range', async () => {
    stubApi(OCTOBER)
    renderAt('/?period=previous-month&currency=each')
    const usd = await screen.findByRole('figure', { name: 'Income and expenses in USD' })
    expect(rowLabels(usd)).toEqual(['Previous period', 'Period'])
    expect(rowLabels(screen.getByRole('figure', { name: 'Income and expenses in EUR' })))
      .toEqual(['Year earlier', 'Previous period', 'Period'])
  })

  it('loses its change in the headline strip', async () => {
    stubApi(OCTOBER)
    renderAt('/?period=previous-month&currency=each')
    await screen.findByRole('table', { name: 'Cash flow in USD' })
    const strip = await screen.findByLabelText('Headline figures in USD')
    const expenses = within(strip).getByText('Expenses').closest('.figure')!
    expect(expenses.querySelector('.changes')!.textContent).toBe('+20.0% vs Sep 2026')
    const euros = within(screen.getByLabelText('Headline figures in EUR')).getByText('Expenses').closest('.figure')!
    expect(euros.querySelector('.changes')!.textContent).toBe('−10.0% vs Sep 2026 · +12.5% vs Oct 2025')
  })

  it('keeps the period’s column and row with nothing in it, its categories listed with “—”', async () => {
    stubApi({ ...OCTOBER, '2025-10-01': [...OCTOBER['2025-10-01'], row('2025-10', 'EXPENSE', 'JPY', '1200')] })
    renderAt('/?period=previous-month&currency=each')
    const jpy = await screen.findByRole('table', { name: 'Cash flow in JPY' })
    expect(headers(jpy)).toEqual(['JPY', 'Period Oct 2026', 'Share %', 'Year earlier Oct 2025', 'Change %'])
    expect(within(jpy).getByRole('rowheader', { name: 'Groceries' }).closest('tr')!.querySelector('td')!.textContent).toBe('—')
    expect(rowLabels(screen.getByRole('figure', { name: 'Income and expenses in JPY' }))).toEqual(['Year earlier', 'Period'])
  })

  it('keeps a failed comparison’s error, its columns left out (D-401)', async () => {
    stubApi(OCTOBER, {}, '2026-09-01')
    renderAt('/?period=previous-month&currency=each')
    const usd = await screen.findByRole('table', { name: 'Cash flow in USD' })
    expect(headers(usd)).toEqual(['USD', 'Period Oct 2026', 'Share %'])
    expect(screen.getByText(/^Previous period \(Sep 2026\): Something went wrong/)).toBeDefined()
  })

  /** Last 3 months (Sep to Nov 2026, to date): euros in every range; dollars in the period and a year earlier. */
  const QUARTER = {
    '2026-09-01': [row('2026-09', 'INCOME', 'EUR', '2000.00'), row('2026-10', 'EXPENSE', 'EUR', '500.00'),
      row('2026-10', 'EXPENSE', 'USD', '40.00')],
    '2025-09-01': [row('2025-09', 'INCOME', 'EUR', '1800.00'), row('2025-10', 'EXPENSE', 'EUR', '450.00'),
      row('2025-10', 'EXPENSE', 'USD', '35.00')],
    '2026-06-01': [row('2026-06', 'INCOME', 'EUR', '1900.00'), row('2026-07', 'EXPENSE', 'EUR', '480.00')],
  }

  it('loses its markers and its legend entry on the chart by month, in that currency only', async () => {
    stubApi(QUARTER)
    renderAt('/?period=last-3-months&currency=each&compare=previous')
    const usd = await screen.findByRole('figure', { name: 'Income and expenses in USD' })
    const eur = screen.getByRole('figure', { name: 'Income and expenses in EUR' })
    // Both are offered, since euros keep both; dollars have no previous period, so no marker and no legend for it.
    expect(within(screen.getByRole('group', { name: 'Compare with' })).getByRole('button', { name: 'Previous period' })
      .getAttribute('aria-pressed')).toBe('true')
    expect(usd.querySelector('.ie-legend')!.textContent).toBe('IncomeExpenses')
    expect(usd.querySelectorAll('.ie-marker')).toHaveLength(0)
    expect(eur.querySelector('.ie-legend')!.textContent).toBe('IncomeExpensesprevious period')
    expect(markers(eur)).toEqual([1, 1, 0])
  })

  it('offers “Compare with” only what a currency keeps; compare=previous falls back to the one left', async () => {
    const { '2026-06-01': _, ...withoutPrevious } = QUARTER
    stubApi(withoutPrevious)
    renderAt('/?period=last-3-months&currency=each&compare=previous')
    const eur = await screen.findByRole('figure', { name: 'Income and expenses in EUR' })
    expect(screen.queryByRole('group', { name: 'Compare with' })).toBeNull()
    expect(eur.querySelector('.ie-legend')!.textContent).toBe('IncomeExpensesa year earlier')
    expect(markers(eur)).toEqual([1, 1, 0])
    expect(headers(screen.getByRole('table', { name: 'Cash flow in EUR' }))).toEqual(['EUR', 'To date Sep 1 – Nov 9, 2026',
      'Share %', 'Year earlier Sep 1 – Nov 9, 2025', 'Change %'])
  })
})

describe('empty rows, charts and panels (D-686)', () => {
  it('leaves out the leading months and years with nothing, the drawn comparison’s too', () => {
    const month = (m: string, income: MonthPair['income'], expense: MonthPair['expense'],
      before?: MonthPair['before']): MonthPair => ({ month: m, income, expense, ...(before ? { before } : {}) })
    expect(fromFirstFigure([month('2026-01', null, null), month('2026-02', null, '5'), month('2026-03', null, null),
      month('2026-04', '1', null)]).map((m) => m.month)).toEqual(['2026-02', '2026-03', '2026-04'])
    // A comparison's figure is a figure; MISSING is one too.
    expect(fromFirstFigure([month('2026-01', null, null, { month: '2025-01', income: null, expense: null }),
      month('2026-02', null, null, { month: '2025-02', income: '3', expense: null }),
      month('2026-03', null, null)]).map((m) => m.month)).toEqual(['2026-02', '2026-03'])
    expect(fromFirstFigure([month('2026-01', null, null), month('2026-02', MISSING, null)]).map((m) => m.month))
      .toEqual(['2026-02'])
    const years: YearRow[] = [{ year: '2023', income: null, expense: null }, { year: '2024', income: '1', expense: null },
      { year: '2025', income: null, expense: null }]
    expect(fromFirstFigure(years).map((y) => y.year)).toEqual(['2024', '2025'])
    expect(fromFirstFigure([month('2026-01', null, null)])).toEqual([])
  })

  it('starts the chart by month at its first month with a figure, a later empty month kept', async () => {
    stubApi({ '2026-09-01': [row('2026-10', 'EXPENSE', 'EUR', '500.00')] })
    renderAt('/?period=last-3-months&currency=each')
    const eur = await screen.findByRole('figure', { name: 'Income and expenses in EUR' })
    expect(rowLabels(eur)).toEqual(['Oct 2026', 'Nov 2026'])
  })

  it('draws no chart for a currency with no row left, and keeps its table', async () => {
    // Dollars only in the previous period, which isn't drawn while a year earlier is.
    stubApi({
      '2026-09-01': [row('2026-10', 'EXPENSE', 'EUR', '500.00')],
      '2025-09-01': [row('2025-10', 'EXPENSE', 'EUR', '450.00')],
      '2026-06-01': [row('2026-07', 'EXPENSE', 'EUR', '480.00'), row('2026-07', 'EXPENSE', 'USD', '20.00')],
    })
    renderAt('/?period=last-3-months&currency=each')
    await screen.findByRole('figure', { name: 'Income and expenses in EUR' })
    expect(screen.queryByRole('figure', { name: 'Income and expenses in USD' })).toBeNull()
    expect(headers(screen.getByRole('table', { name: 'Cash flow in USD' })))
      .toEqual(['USD', 'To date Sep 1 – Nov 9, 2026', 'Share %', 'Previous period Jun 1 – Aug 9, 2026', 'Change %'])
  })

  it('shows daily spending only for a currency with a figure on one of its days, the chart alone the whole row', async () => {
    stubApi(OCTOBER, { '2026-10-01': [day('2026-10-04', 'EUR', '30.00')] })
    const { container } = renderAt('/?period=previous-month&currency=each')
    await screen.findByRole('region', { name: 'Daily spending, October 2026' })
    expect([...container.querySelectorAll('section.daily')].map((s) => s.getAttribute('data-currency'))).toEqual(['EUR'])
    const usd = screen.getByRole('figure', { name: 'Income and expenses in USD' }).closest('.chart-row')!
    expect(usd.className).toBe('chart-row solo')
    expect(screen.getByRole('figure', { name: 'Income and expenses in EUR' }).closest('.chart-row')!.className).toBe('chart-row')
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
    expect(css).toMatch(/@container \(min-width: 40rem\) \{[^}]*\}\s*\/\*[^*]*\*\/\s*\.chart-row\.solo \{ grid-template-columns: minmax\(0, 1fr\); \}/)
  })

  it('knows a figure from nothing: spending, a refund and a missing rate are figures, days to come and income aren’t', () => {
    const range = { from: '2026-11-01', to: '2026-11-30' }
    expect(hasDailyFigure([], range, '2026-11-09')).toBe(false)
    expect(hasDailyFigure([{ ...day('2026-11-03', 'EUR', '0'), categoryType: 'INCOME', total: '100.00' }], range, '2026-11-09'))
      .toBe(false)
    expect(hasDailyFigure([day('2026-11-20', 'EUR', '5.00')], range, '2026-11-09')).toBe(false)
    expect(hasDailyFigure([day('2026-11-03', 'EUR', '5.00')], range, '2026-11-09')).toBe(true)
    expect(hasDailyFigure([day('2026-11-03', 'EUR', '-5.00')], range, '2026-11-09')).toBe(true)
    expect(hasDailyFigure([{ ...day('2026-11-03', 'EUR', '0'), total: null }], range, '2026-11-09')).toBe(true)
  })

  it('keeps NoCashFlow’s text for a period and comparisons with nothing in any currency', async () => {
    stubApi({})
    renderAt('/?period=previous-month&currency=each')
    expect(await screen.findAllByText('No income or expenses in this period or the ones it is compared with.')).toHaveLength(2)
  })
})

describe('the hover panel by month (D-687)', () => {
  const draw = (before: MonthPair['before']) => render(<IncomeExpenseChart currency="EUR" rounded={false} data={{
    form: 'months', role: 'year',
    months: [{ month: '2026-08', income: '1000', expense: '400', before },
      { month: '2026-09', income: '900', expense: '300', before: { month: '2025-09', income: '800', expense: '200' } }],
  }} />).container
  const panel = (container: HTMLElement) => [...container.querySelector('.ie-panel')!.children].map((e) => e.textContent)

  it('leaves the comparison out when its month has no figure in either series', () => {
    const container = draw({ month: '2025-08', income: null, expense: null })
    fireEvent.pointerEnter(container.querySelector('.ie-row[data-key="2026-08"]')!)
    expect(panel(container)).toEqual(['Aug 2026', '€1,000.00 Income', '€400.00 Expenses'])
  })

  it('shows “—” with no “(—)” for the series without a figure', () => {
    const container = draw({ month: '2025-08', income: null, expense: '500' })
    fireEvent.pointerEnter(container.querySelector('.ie-row[data-key="2026-08"]')!)
    expect(panel(container)).toEqual(['Aug 2026', '€1,000.00 Income', '€400.00 Expenses',
      'Aug 2025, a year earlier', '— Income', '€500.00 Expenses (−20.0%)'])
  })
})
