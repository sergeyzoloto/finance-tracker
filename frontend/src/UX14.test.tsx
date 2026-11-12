import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { CashFlowRow, ConvertedCashFlow, FamilyLedger, Me, Rate } from './api'
import CashFlowTable from './CashFlowTable'
import {
  cashFlowTables, compareColumns, compareTable, comparisons, convertedCashFlowTable, MISSING, monthsBetween, shareOf,
  shareText,
} from './dashboard'

// UX-14: the dashboard's two columns (D-593), daily spending by the card's width (D-594), "Cash flow by category" with
// each category's share (D-595) and the Rates card (D-596). Today is Thursday 12 November 2026.

const TODAY = '2026-11-12'
const ME: Me = { name: 'Anna', timeZone: 'UTC', today: TODAY, features: { familyLedgers: true } }
const home: FamilyLedger = {
  id: 7, name: 'Demo household', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
  createdAt: '2026-01-01T10:00:00Z', startDate: '2026-01-01',
}
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
/** Narrow and thin spaces as plain ones. */
const plain = (text: string | null | undefined) => (text ?? '').replace(/[   ]/g, ' ')

describe('a category’s share of its section (D-595)', () => {
  it('rounds HALF_UP to one decimal, in big.js', () => {
    expect(shareOf('1.00', '3.00')).toEqual({ percent: '33.3', width: '33.3' })
    expect(shareOf('2.00', '3.00')).toEqual({ percent: '66.7', width: '66.7' })
    // 12.25% is 12.3, not the even 12.2; 12.345% is 12.3.
    expect(shareOf('12.25', '100.00')!.percent).toBe('12.3')
    expect(shareOf('12.345', '100.00')!.percent).toBe('12.3')
    // A float would give 0.1 + 0.2's 30.000000000000004.
    expect(shareOf('0.30', '1.00')!.percent).toBe('30.0')
    expect(shareText('33.8')).toBe('33.8%')
    expect(shareText('100.0')).toBe('100.0%')
  })

  it('has none for a line below 0, a missing figure, or a subtotal of 0 or below', () => {
    // Refunds exceeding the line's expenses.
    expect(shareOf('-0.10', '329.90')).toBeNull()
    expect(shareOf(MISSING, '100.00')).toBeNull()
    expect(shareOf('10.00', MISSING)).toBeNull()
    expect(shareOf(null, '100.00')).toBeNull()
    expect(shareOf('10.00', null)).toBeNull()
    expect(shareOf('0.00', '0.00')).toBeNull()
    expect(shareOf('5.00', '-5.00')).toBeNull()
  })

  it('gives a line of 0 0.0 with an empty bar, and a line above its section a full one', () => {
    expect(shareOf('0.00', '50.00')).toEqual({ percent: '0.0', width: '0.0' })
    // Another line's refund makes this one more than the section.
    expect(shareOf('330.00', '320.00')).toEqual({ percent: '103.1', width: '100' })
  })
})

const row = (categoryCode: string, categoryName: string, categoryType: 'INCOME' | 'EXPENSE', currency: string,
  total: string, month = '2026-10'): CashFlowRow => ({ month, categoryCode, categoryName, categoryType, currency, total })

/** The share cell of a row of the table in `currency`, by its label. */
function share(currency: string, label: string) {
  const table = screen.getByRole('table', { name: `Cash flow in ${currency}` })
  const tr = within(table).getByRole('rowheader', { name: label }).closest('tr')!
  return tr.querySelector('td.share')!
}

describe('the Share column (D-595)', () => {
  const october = comparisons({ from: '2026-10-01', to: '2026-10-31' }, TODAY)!
  const columns = compareColumns(october)

  it('follows the period’s column, each line a share of its own section, the totals and the net empty', () => {
    const [t] = cashFlowTables([
      row('SALARY', 'Salary', 'INCOME', 'EUR', '1500.00'), row('BONUS', 'Bonus', 'INCOME', 'EUR', '500.00'),
      row('GROCERIES', 'Groceries', 'EXPENSE', 'EUR', '135.20'), row('RENT', 'Rent', 'EXPENSE', 'EUR', '264.80'),
      row('TRANSPORT', 'Transport', 'EXPENSE', 'EUR', '0.00'),
    ], ['2026-10'])
    render(<CashFlowTable table={compareTable('EUR', columns, [t, null, null], false)} />)
    const headers = within(screen.getByRole('table')).getAllByRole('columnheader').map((th) => th.textContent)
    expect(headers.slice(0, 3)).toEqual(['EUR', 'Period Oct 2026', 'Share %'])
    expect(screen.getByRole('columnheader', { name: 'Share %' }).querySelector('.col-role')!.textContent).toBe('Share')
    // Of Total income 2,000.00 and of Total expenses 400.00.
    expect(share('EUR', 'Salary').textContent).toBe('75.0%')
    expect(share('EUR', 'Bonus').textContent).toBe('25.0%')
    expect(share('EUR', 'Groceries').textContent).toBe('33.8%')
    expect(share('EUR', 'Rent').textContent).toBe('66.2%')
    expect(share('EUR', 'Transport').textContent).toBe('0.0%')
    for (const label of ['Total income', 'Total expenses', 'Net']) expect(share('EUR', label).textContent).toBe('')
    // The bar: hidden from screen readers, in the chart's colour of its section, as wide as the share.
    const bar = share('EUR', 'Groceries').querySelector('.share-track')!
    expect(bar.getAttribute('aria-hidden')).toBe('true')
    expect(bar.querySelector('.share-fill.expense')!.getAttribute('style')).toBe('width: 33.8%;')
    expect(share('EUR', 'Salary').querySelector('.share-fill.income')!.getAttribute('style')).toBe('width: 75%;')
    expect(share('EUR', 'Transport').querySelector('.share-fill')!.getAttribute('style')).toBe('width: 0%;')
  })

  it('shows “—” without a bar for a line under its refunds, and a section of 0', () => {
    const [t] = cashFlowTables([
      row('SALARY', 'Salary', 'INCOME', 'EUR', '100.00'), row('REFUND', 'Gift', 'INCOME', 'EUR', '-100.00'),
      row('GROCERIES', 'Groceries', 'EXPENSE', 'EUR', '50.00'), row('TRANSPORT', 'Transport', 'EXPENSE', 'EUR', '-10.00'),
    ], ['2026-10'])
    render(<CashFlowTable table={compareTable('EUR', columns, [t, null, null], false)} />)
    // Total income is 0: no line has a share of it.
    expect(share('EUR', 'Salary').textContent).toBe('—')
    expect(share('EUR', 'Gift').textContent).toBe('—')
    expect(share('EUR', 'Salary').querySelector('.share-track')).toBeNull()
    // Transport's refunds exceed it; Groceries is more than the section of 40.00.
    expect(share('EUR', 'Transport').textContent).toBe('—')
    expect(share('EUR', 'Groceries').textContent).toBe('125.0%')
    expect(share('EUR', 'Groceries').querySelector('.share-fill')!.getAttribute('style')).toBe('width: 100%;')
  })

  it('in the base currency leaves a missing figure’s line and section without a share', () => {
    const converted: ConvertedCashFlow = {
      currency: 'EUR', exchangeResults: [{ month: '2026-10', realized: '3.00', unrealized: '0.00', missingRates: [] }],
      rows: [
        { month: '2026-10', categoryCode: 'SALARY', categoryName: 'Salary', categoryType: 'INCOME', total: '900.00', missingRates: [] },
        { month: '2026-10', categoryCode: 'BONUS', categoryName: 'Bonus', categoryType: 'INCOME', total: '100.00', missingRates: [] },
        { month: '2026-10', categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', total: null,
          missingRates: [{ currency: 'KZT', from: '2026-10-05', to: '2026-10-05', days: 1 }] },
        { month: '2026-10', categoryCode: 'RENT', categoryName: 'Rent', categoryType: 'EXPENSE', total: '700.00', missingRates: [] },
      ],
    }
    render(<CashFlowTable table={compareTable('EUR', columns,
      [convertedCashFlowTable(converted, monthsBetween('2026-10-01', '2026-10-31')), null, null], true)} />)
    expect(share('EUR', 'Salary').textContent).toBe('90.0%')
    expect(share('EUR', 'Bonus').textContent).toBe('10.0%')
    // Total expenses is "rate missing": no share for either line.
    expect(share('EUR', 'Groceries').textContent).toBe('—')
    expect(share('EUR', 'Rent').textContent).toBe('—')
    expect(share('EUR', 'Realized on exchanges').textContent).toBe('')
  })
})

// The page against a stubbed api.

const OCTOBER: CashFlowRow[] = [
  row('SALARY', 'Salary', 'INCOME', 'EUR', '2000.00'),
  row('GROCERIES', 'Groceries', 'EXPENSE', 'EUR', '85.00'),
  row('TRAVEL', 'Travel', 'EXPENSE', 'USD', '20.00'),
]
const USD_RATE: Rate = { currency: 'USD', date: '2026-10-30', perEuro: '1.1193', source: 'ECB', stale: false }
const RUB_RATE: Rate = { currency: 'RUB', date: '2026-08-01', perEuro: '95.5', source: 'MANUAL', stale: true }

let calls: string[] = []
function stubApi({ rates = [USD_RATE, RUB_RATE] }: { rates?: Rate[] } = {}) {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    const [path, query = ''] = url.split('?')
    const params = new URLSearchParams(query)
    const inBase = params.get('currency') === 'BASE'
    const rows = params.get('from') === '2026-10-01' ? OCTOBER : []
    const converted: ConvertedCashFlow = {
      currency: 'EUR', exchangeResults: [],
      rows: rows.map(({ currency, total, ...r }) => ({ ...r, total: currency === 'USD' ? '18.00' : total, missingRates: [] })),
    }
    const bodies: Record<string, unknown> = {
      '/api/entries': { content: [], page: 0, size: 1, totalElements: 12, totalPages: 12 },
      '/api/settings': { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' },
      '/api/family-ledgers': [home],
      '/api/reports/net-worth': inBase
        ? { currency: 'EUR', assets: '155.00', liabilities: '9.50', netWorth: '145.50', unrealizedRevaluation: '1.20',
          realizedExchangeResult: '-0.40', rates, missingRates: [] }
        : [{ currency: 'EUR', assets: '155.00', liabilities: '9.50', netWorth: '145.50' }],
      '/api/reports/balances': [],
      '/api/reports/shared-settlement': [],
      '/api/reports/counterparty-balances': [],
      '/api/reports/cash-flow': inBase ? converted : rows,
      '/api/reports/daily-cash-flow': inBase ? { currency: 'EUR', rows: [] } : [],
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

/** The cards below the strip, by their headings, in the DOM's order. */
const cardTitles = (container: HTMLElement) =>
  [...container.querySelectorAll('.dashboard-columns section.card > h3')].map((h) => plain(h.textContent))

describe('the two columns (D-593)', () => {
  it('has the main column’s cards, then the side column’s, in design C’s phone order', async () => {
    stubApi()
    const { container } = renderAt('/?period=previous-month')
    await screen.findByRole('table', { name: 'Cash flow in EUR' })
    await screen.findByRole('region', { name: 'Rates on Oct 31, 2026' })
    expect(cardTitles(container)).toEqual(['Income and expenses Oct 1, 2026 – Oct 31, 2026', 'Cash flow by category',
      'Balances on Oct 31, 2026', 'Open loans', 'Rates on Oct 31, 2026'])
    const [main, side] = [...container.querySelectorAll('.dashboard-columns > .dashboard-column')]
    expect([...main.querySelectorAll(':scope > section.card')].map((s) => s.querySelector('h3')!.id))
      .toEqual(['flows-title', 'cash-flow-title'])
    expect([...side.querySelectorAll(':scope > section.card')].map((s) => s.querySelector('h3')!.id))
      .toEqual(['balances-title', 'loans-title', 'rates-title'])
    // The strip comes before both columns; the old grid is gone.
    expect(container.querySelector('section.headline')!.compareDocumentPosition(main) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(container.querySelector('.dashboard')).toBeNull()
  })

  it('lays them out in two columns from 64rem and stacks each column’s cards, one column below', () => {
    expect(css).toMatch(/\.dashboard-columns \{ display: grid; grid-template-columns: minmax\(0, 1fr\); gap: 1rem; align-items: start; \}/)
    expect(css).toMatch(/\.dashboard-column \{ display: flex; flex-direction: column; gap: 1rem; min-width: 0; \}/)
    expect(css).toMatch(/@media \(min-width: 64rem\) \{\s*\.dashboard-columns \{ grid-template-columns: minmax\(0, 2fr\) minmax\(0, 1fr\); \}/)
    expect(css).not.toMatch(/\.dashboard \{/)
  })

  it('puts daily spending beside the chart by the card’s width (D-594)', async () => {
    expect(css).toMatch(/\.card\.flows \{ container-type: inline-size; \}/)
    expect(css).toMatch(/@container \(min-width: 40rem\) \{\s*\.chart-row \{ grid-template-columns: minmax\(0, 2fr\) minmax\(0, 1fr\);/)
    // No viewport query for it any more.
    expect(css).not.toMatch(/@media \(min-width: 64rem\) \{\s*\.chart-row/)
    stubApi()
    renderAt('/?period=previous-month')
    const panel = await screen.findByRole('region', { name: 'Daily spending, October 2026' })
    expect(panel.closest('.chart-row')!.closest('section.card.flows')).not.toBeNull()
  })
})

describe('“Cash flow by category” (D-595)', () => {
  const card = () => screen.getByRole('region', { name: 'Cash flow by category' })

  it('is a card of its own holding the tables, with no heading of its own inside “Income and expenses”', async () => {
    stubApi()
    renderAt('/?period=previous-month&currency=each')
    await screen.findByRole('table', { name: 'Cash flow in USD' })
    expect(within(card()).getAllByRole('table').map((t) => t.getAttribute('aria-label')))
      .toEqual(['Cash flow in EUR', 'Cash flow in USD'])
    const flows = screen.getByRole('region', { name: /^Income and expenses/ })
    // Only daily spending's hidden table of days is left in it.
    expect(within(flows).queryByRole('table', { name: /^Cash flow/ })).toBeNull()
    expect(within(flows).queryByRole('heading', { name: 'Cash flow by category' })).toBeNull()
    expect(within(card()).getByRole('heading', { level: 3 }).textContent).toBe('Cash flow by category')
    // All: no scope line.
    expect(card().querySelector('.scope-line')).toBeNull()
  })

  it('names any scope but All under its heading, as the strip does', async () => {
    stubApi()
    renderAt('/?period=previous-month&scope=family-mine&family=7')
    await within(await screen.findByRole('region', { name: 'Cash flow by category' })).findByRole('table')
    expect(card().querySelector('.scope-line')!.textContent).toBe('Demo household · My records')
    cleanup()
    stubApi()
    renderAt('/?period=previous-month&scope=personal')
    await within(await screen.findByRole('region', { name: 'Cash flow by category' })).findByRole('table')
    expect(card().querySelector('.scope-line')!.textContent).toBe('Personal')
  })

  it('says “No income or expenses…” as the chart’s card does', async () => {
    stubApi()
    renderAt('/?period=previous-year')
    await waitFor(() => expect(within(card()).getByText(/^No income or expenses in this period/)).toBeDefined())
  })
})

describe('the Rates card (D-596)', () => {
  const ratesCard = () => screen.findByRole('region', { name: 'Rates on Oct 31, 2026' })

  it('lists each rate net worth used on the balances’ day, its source and date, a stale one marked', async () => {
    stubApi()
    renderAt('/?period=previous-month')
    const card = await ratesCard()
    const items = await within(card).findAllByRole('listitem')
    expect(items.map((li) => plain(li.textContent))).toEqual([
      '1 EUR 1.1193 USDECB rate of Oct 30, 2026', '1 EUR 95.5 RUBManual rate of Aug 1, 2026 (rate stale)'])
    expect(items[0].querySelectorAll('.rate-pair .amount')).toHaveLength(2)
    expect(items[1].querySelector('.stale-rate')!.textContent).toBe('Manual rate of Aug 1, 2026 (rate stale)')
    expect(items[0].querySelector('.stale-rate')).toBeNull()
    expect(within(card).getByRole('link', { name: 'Rates' }).getAttribute('href')).toBe('/rates')
    expect(card.querySelector('h3 .muted.period')!.textContent).toBe('on Oct 31, 2026')
  })

  it('says no rate is needed when none was used', async () => {
    stubApi({ rates: [] })
    renderAt('/?period=previous-month')
    const card = await ratesCard()
    await within(card).findByText('Every amount is in EUR: no rate needed.')
    expect(within(card).queryByRole('list')).toBeNull()
    expect(within(card).getByRole('link', { name: 'Rates' })).toBeDefined()
  })

  it('takes the place of the rates’ line under the strip, and isn’t there in each currency', async () => {
    stubApi()
    renderAt('/?period=previous-month')
    await ratesCard()
    const headline = screen.getByRole('region', { name: 'Headline figures' })
    expect(headline.textContent).not.toContain('At the rates')
    expect(within(headline).queryByRole('link', { name: 'Rates' })).toBeNull()
    cleanup()
    stubApi()
    const { container } = renderAt('/?period=previous-month&currency=each')
    await screen.findByRole('table', { name: 'Cash flow in USD' })
    await screen.findByRole('region', { name: 'Open loans' })
    expect(screen.queryByRole('region', { name: /^Rates/ })).toBeNull()
    expect(cardTitles(container)).toEqual(['Income and expenses Oct 1, 2026 – Oct 31, 2026', 'Cash flow by category',
      'Balances on Oct 31, 2026', 'Open loans'])
  })
})
