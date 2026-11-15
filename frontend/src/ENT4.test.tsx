import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { EntryMonthly } from './api'
import Entries from './Entries'
import { SUMMARY_FOLD_KEY } from './EntrySummaryPanel'
import { columnBox, direction, monthLabel, monthlyCurrencies, monthsBetween, waterfall, waterfallRange, zeroLine } from './monthly'
import { testLedger } from './testLedger'
import { WithMe } from './testMe'

// ENT-4 (D-882, D-883): the monthly result chart, a waterfall per currency. Today is 2026-09-30 (TEST_ME).

const plain = (text: string | null | undefined) => (text ?? '').replace(/[   ]/g, ' ').replace(/−/g, '-').trim()
const data = (months: EntryMonthly['months'], first: string | null = '2026-01', last: string | null = '2026-04'): EntryMonthly =>
  ({ firstMonth: first, lastMonth: last, months })

describe('the waterfall without React (D-882)', () => {
  it('lists the months of a span, across a year end', () => {
    expect(monthsBetween('2025-11', '2026-02')).toEqual(['2025-11', '2025-12', '2026-01', '2026-02'])
    expect(monthsBetween('2026-05', '2026-05')).toEqual(['2026-05'])
  })

  it('runs each month from the total before it to the one after, then a Total from 0', () => {
    const columns = waterfall(data([
      { month: '2026-01', currency: 'EUR', incoming: '1000.00', outgoing: '400.00' },
      { month: '2026-03', currency: 'EUR', incoming: '0.00', outgoing: '900.00' },
      { month: '2026-04', currency: 'EUR', incoming: '50.00', outgoing: '0.00' },
      { month: '2026-01', currency: 'USD', incoming: '10.00', outgoing: '0.00' }]), 'EUR')
    expect(columns.map((c) => [c.key, c.before, c.after, c.change])).toEqual([
      ['2026-01', '0', '600', '600'],
      // A month with no figure is a column of no change.
      ['2026-02', '600', '600', '0'],
      ['2026-03', '600', '-300', '-900'],
      ['2026-04', '-300', '-250', '50'],
      ['total', '0', '-250', '-250']])
    expect(columns.map(direction)).toEqual(['up', 'flat', 'down', 'up', 'down'])
    // A currency alone, never added across.
    expect(waterfall(data([{ month: '2026-01', currency: 'USD', incoming: '10.00', outgoing: '0.00' },
      { month: '2026-01', currency: 'EUR', incoming: '1.00', outgoing: '0.00' }]), 'USD').at(-1)!.after).toBe('10')
    expect(monthlyCurrencies(data([{ month: '2026-01', currency: 'USD', incoming: '1', outgoing: '0' },
      { month: '2026-01', currency: 'EUR', incoming: '1', outgoing: '0' }]))).toEqual(['EUR', 'USD'])
    expect(waterfall(data([], null, null), 'EUR')).toEqual([])
  })

  it('places the columns on one scale that holds 0, with the zero line on it', () => {
    const columns = waterfall(data([
      { month: '2026-01', currency: 'EUR', incoming: '100.00', outgoing: '0.00' },
      { month: '2026-03', currency: 'EUR', incoming: '0.00', outgoing: '300.00' }]), 'EUR')
    const range = waterfallRange(columns)
    expect(range).toEqual({ min: -200, max: 100 })
    expect(zeroLine(range)).toBeCloseTo(66.67, 1)
    expect(columnBox(columns[0], range)).toEqual({ bottom: (200 / 300) * 100, height: (100 / 300) * 100 })
    // The Total runs from 0 down to -200.
    expect(columnBox(columns.at(-1)!, range).bottom).toBeCloseTo(0, 5)
    expect(columnBox(columns.at(-1)!, range).height).toBeCloseTo(66.67, 1)
  })

  it('names a month short, with its year at the start and after each December', () => {
    expect(monthLabel('2026-01', false, 'en-US')).toEqual({ name: 'Jan', year: '2026' })
    expect(monthLabel('2025-11', true, 'en-US')).toEqual({ name: 'Nov', year: '2025' })
    expect(monthLabel('2025-12', false, 'en-US')).toEqual({ name: 'Dec', year: null })
  })
})

let calls: string[] = []
function stubApi(monthly: EntryMonthly) {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    const path = url.split('?')[0]
    const bodies: Record<string, unknown> = {
      '/api/accounts': testLedger.accounts, '/api/categories': testLedger.categories,
      '/api/counterparties': testLedger.counterparties, '/api/settings': testLedger.settings,
      '/api/entries/family-payees': [],
      '/api/entries/summary': { count: 5, firstDate: '2026-01-01', lastDate: '2026-04-20', payees: 1, withoutPayee: 3,
        categories: 2, withoutCategory: 1, accounts: 1,
        currencies: [{ currency: 'EUR', incoming: '1050.00', outgoing: '1300.00', transfers: '0.00', spent: '1300.00',
          spentOthersShares: '0.00', received: '1050.00', receivedOthersShares: '0.00' }] },
      '/api/entries': { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 },
      '/api/entries/breakdown': { by: 'CATEGORY', rows: [] },
      '/api/entries/monthly': monthly,
    }
    const body = bodies[path]
    return new Response(JSON.stringify(body ?? { status: 404 }), {
      status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' },
    })
  }))
}
function renderEntries(path: string, monthly: EntryMonthly) {
  stubApi(monthly)
  render(
    <WithMe>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path="/entries" element={<Entries />} /></Routes>
      </MemoryRouter>
    </WithMe>,
  )
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true, addEventListener() {}, removeEventListener() {} })))
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const two = data([
  { month: '2026-01', currency: 'EUR', incoming: '1000.00', outgoing: '400.00' },
  { month: '2026-03', currency: 'EUR', incoming: '0.00', outgoing: '900.00' },
  { month: '2026-04', currency: 'EUR', incoming: '50.00', outgoing: '0.00' },
  { month: '2026-02', currency: 'USD', incoming: '20.00', outgoing: '5.00' }])

describe('the monthly result on Entries (D-882, D-883)', () => {
  it('draws a waterfall per currency under the breakdown, each month and a Total, with the filters as they are', async () => {
    renderEntries('/entries?period=all&accountId=1', two)
    const eur = await waitFor(() => {
      const el = document.querySelector<HTMLElement>('.monthly-currency[data-currency="EUR"]')
      expect(el).not.toBeNull()
      return el!
    })
    expect(calls).toContain('/api/entries/monthly?accountId=1')
    expect([...eur.querySelectorAll('.monthly-label')].map((l) => plain(l.textContent))).toEqual(
      ['Jan 2026', 'Feb', 'Mar', 'Apr', 'Total'])
    expect([...eur.querySelectorAll('.monthly-bar')].map((b) => b.className.replace('monthly-bar ', ''))).toEqual(
      ['up', 'flat', 'down', 'up', 'down'])
    expect([...eur.querySelectorAll('.monthly-value')].map((v) => plain(v.textContent))).toEqual(
      ['+€600.00', '€0.00', '-€900.00', '+€50.00', '-€250.00'])
    // The currencies are never added: a heading names each, and the USD one has its own columns.
    expect(screen.getByRole('heading', { name: 'EUR' })).toBeDefined()
    expect(screen.getByRole('heading', { name: 'USD' })).toBeDefined()
    expect(document.querySelector('.monthly-currency[data-currency="USD"] .monthly-column.total')).not.toBeNull()
    // The drawing is hidden from screen readers; the table has every month.
    expect(eur.querySelector('.monthly-chart')!.getAttribute('aria-hidden')).toBe('true')
    const table = within(eur).getByRole('table', { name: 'Monthly result in EUR' })
    expect([...table.querySelectorAll('tbody tr')].map((r) => plain(r.textContent))).toEqual([
      '2026-01+€600.00+€600.00', '2026-02€0.00+€600.00', '2026-03-€900.00-€300.00', '2026-04+€50.00-€250.00',
      'Total-€250.00-€250.00'])
  })

  it('has no heading for one currency, says so when nothing matches, and folds with the panel', async () => {
    renderEntries('/entries?period=all', data([{ month: '2026-01', currency: 'EUR', incoming: '5.00', outgoing: '0.00' }], '2026-01', '2026-01'))
    await waitFor(() => expect(document.querySelector('.monthly-currency')).not.toBeNull())
    expect(screen.queryByRole('heading', { name: 'EUR' })).toBeNull()
    cleanup()
    renderEntries('/entries?period=all', data([], null, null))
    expect(await screen.findByText('Nothing to show by month.')).toBeDefined()
    cleanup()
    localStorage.setItem(SUMMARY_FOLD_KEY, 'folded')
    renderEntries('/entries?period=all', two)
    await screen.findByRole('button', { name: 'Unfold the summary' })
    expect(calls.some((c) => c.startsWith('/api/entries/monthly'))).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Unfold the summary' }))
    await waitFor(() => expect(document.querySelector('.monthly-currency')).not.toBeNull())
  })
})
