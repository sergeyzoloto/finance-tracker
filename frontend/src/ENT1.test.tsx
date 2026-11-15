import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CurrencyTotals, EntrySummary } from './api'
import { logOut } from './auth'
import Entries, { entriesPeriod, withRememberedPeriod } from './Entries'
import { amountTable, myNet } from './entrySummary'
import EntrySummaryPanel, { SUMMARY_FOLD_KEY } from './EntrySummaryPanel'
import Header from './Header'
import { forgetAllPages, PAGE_STATE_PREFIX, pageLink, rememberedSearch, rememberPage } from './pageState'
import { testLedger } from './testLedger'
import { WithMe } from './testMe'

// ENT-1: the default period (D-869), each page's remembered state (D-870, D-874, D-877), the summary as a table
// (D-871, D-875) and folding it (D-872, D-876). Today is 2026-09-30 (TEST_ME).

const plain = (text: string | null | undefined) => (text ?? '').replace(/[\u00a0\u2009\u202f]/g, ' ').replace(/−/g, '-').trim()

const totals = (t: Partial<CurrencyTotals>): CurrencyTotals => ({
  currency: 'EUR', incoming: '0.00', outgoing: '0.00', transfers: '0.00', spent: '0.00', spentOthersShares: '0.00',
  received: '0.00', receivedOthersShares: '0.00', ...t,
})
/** A table as lines of text: the columns, then each row's label and cells ('' for an empty one). */
const table = (t: Partial<CurrencyTotals>) => {
  const { columns, rows } = amountTable(totals(t))
  return [columns.join(' | '), ...rows.map((r) => [r.label, ...r.cells.map(plain)].join(' | '))]
}

let calls: string[] = []
let summary: EntrySummary
function stubApi() {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    const [path, query = ''] = url.split('?')
    const params = new URLSearchParams(query)
    const bodies: Record<string, unknown> = {
      '/api/accounts': testLedger.accounts, '/api/categories': testLedger.categories,
      '/api/counterparties': testLedger.counterparties, '/api/settings': testLedger.settings,
      '/api/entries/summary': summary, '/api/entries/family-payees': [],
    }
    let body = bodies[path]
    if (path === '/api/entries') {
      body = params.get('size') === '1'
        ? { content: [{ id: 99, version: 0, entryDate: '2026-08-20', kind: 'EXPENSE', payeeId: null, memo: null, postings: [] }],
          page: 0, size: 1, totalElements: 1, totalPages: 1 }
        : { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }
    }
    return new Response(JSON.stringify(body ?? { status: 404 }), {
      status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' },
    })
  }))
}

let where = ''
function Where() {
  where = useLocation().search
  return null
}

function renderEntries(path: string) {
  stubApi()
  return render(
    <WithMe>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path="/entries" element={<><Entries /><Where /></>} /></Routes>
      </MemoryRouter>
    </WithMe>,
  )
}

/** A device whose width is at least 40rem or not. */
const width = (wide: boolean) => vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: wide, addEventListener() {}, removeEventListener() {} })))

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  width(true)
  summary = {
    count: 3, firstDate: '2026-08-02', lastDate: '2026-08-20', payees: 2, withoutPayee: 1, categories: 2,
    withoutCategory: 0, accounts: 1,
    currencies: [totals({ incoming: '100.00', received: '100.00', outgoing: '40.00', spent: '40.00' })],
  }
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the summary’s amount table (D-871)', () => {
  it('has one column, Amount, in a currency without others’ shares', () => {
    expect(table({ incoming: '5.00', received: '5.00', outgoing: '7.25', spent: '7.25', transfers: '1.00' })).toEqual([
      'Amount', 'Income | +€5.00', 'Expenses | -€7.25', 'Net | -€2.25', 'Transfers | €1.00'])
  })

  it('has Full amount, Others’ shares and My share once a side has others’ shares, with today’s signs', () => {
    expect(table({ incoming: '30.00', received: '60.00', receivedOthersShares: '30.00', outgoing: '15.00',
      spent: '30.00', spentOthersShares: '15.00', transfers: '5.00' })).toEqual([
      'Full amount | Others’ shares | My share',
      'Income | +€60.00 | -€30.00 | +€30.00',
      'Expenses | -€30.00 | +€15.00 | -€15.00',
      'Net |  |  | +€15.00',
      'Transfers | €5.00 |  | €5.00'])
  })

  it('shows Net only with both Income and Expenses, and leaves out a row that is 0 everywhere', () => {
    expect(table({ outgoing: '75.00', spent: '120.00', spentOthersShares: '45.00' })).toEqual([
      'Full amount | Others’ shares | My share', 'Expenses | -€120.00 | +€45.00 | -€75.00'])
    expect(table({ incoming: '5.00', received: '5.00' })).toEqual(['Amount', 'Income | +€5.00'])
    expect(table({})).toEqual(['Amount'])
    // A record the others carry wholly: a full amount and its others' shares, my share empty.
    expect(table({ spent: '10.00', spentOthersShares: '10.00' })).toEqual([
      'Full amount | Others’ shares | My share', 'Expenses | -€10.00 | +€10.00 | '])
    expect(table({ incoming: '5.00', received: '5.00', outgoing: '5.00', spent: '5.00' })).toContain('Net | €0.00')
  })

  it('gives My share’s net for the folded line', () => {
    expect(plain(myNet(totals({ incoming: '100.00', outgoing: '40.00' })))).toBe('+€60.00')
    expect(plain(myNet(totals({ outgoing: '40.00' })))).toBe('-€40.00')
    expect(plain(myNet(totals({})))).toBe('€0.00')
  })
})

describe('the summary panel (D-872, D-875, D-876)', () => {
  const panel = (s: EntrySummary = summary) => render(<EntrySummaryPanel summary={s} loading={false} />)

  it('shows the counts line, then a table per currency, headed by its code with more than one', () => {
    panel({ ...summary, currencies: [...summary.currencies, totals({ currency: 'USD', outgoing: '20.00', spent: '20.00' })] })
    const region = screen.getByRole('region', { name: 'Summary of all matching entries' })
    expect(plain(region.querySelector('.summary-counts')!.textContent))
      .toBe('3 entries · Aug 2 – 20, 2026 · 2 payees · 1 without · 2 categories · 1 account')
    const tables = within(region).getAllByRole('table')
    expect(tables.map((t) => t.querySelector('caption')?.textContent)).toEqual(['EUR', 'USD'])
    expect(plain(within(tables[1]).getByRole('row', { name: /Expenses/ }).textContent)).toBe('Expenses-$20.00')
  })

  it('has no caption with one currency', () => {
    panel()
    expect(screen.getByRole('table', { name: 'Amounts in EUR' }).querySelector('caption')).toBeNull()
  })

  it('starts unfolded from 40rem and folds to one line: count, dates and each currency’s net', () => {
    panel()
    const button = screen.getByRole('button', { name: 'Fold the summary' })
    expect(button.getAttribute('aria-expanded')).toBe('true')
    const body = document.getElementById(button.getAttribute('aria-controls')!)!
    expect(body.hidden).toBe(false)
    fireEvent.click(button)
    expect(button.getAttribute('aria-expanded')).toBe('false')
    expect(body.hidden).toBe(true)
    expect(plain(document.querySelector('.summary-counts')!.textContent)).toBe('3 entries · Aug 2 – 20, 2026 · Net +€60.00')
    expect(localStorage.getItem(SUMMARY_FOLD_KEY)).toBe('folded')
  })

  it('starts folded under 40rem, and the user’s choice wins over the width', () => {
    width(false)
    panel()
    expect(screen.getByRole('button', { name: 'Unfold the summary' }).getAttribute('aria-expanded')).toBe('false')
    cleanup()
    localStorage.setItem(SUMMARY_FOLD_KEY, 'open')
    panel()
    expect(screen.getByRole('button', { name: 'Fold the summary' })).toBeDefined()
    cleanup()
    width(true)
    localStorage.setItem(SUMMARY_FOLD_KEY, 'folded')
    panel()
    expect(screen.getByRole('button', { name: 'Unfold the summary' })).toBeDefined()
  })

  it('stands between the filters and the list on Entries', async () => {
    renderEntries('/entries?period=all')
    const region = await screen.findByRole('region', { name: 'Summary of all matching entries' })
    const filters = document.querySelector('fieldset.filters')!
    expect(filters.compareDocumentPosition(region) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(document.querySelector('table.entry-list tbody.entry-summary')).toBeNull()
  })
})

describe('Entries’ default period (D-869)', () => {
  it('opens on the month of the latest entry, All time being period=all', async () => {
    renderEntries('/entries')
    const period = await screen.findByRole('combobox', { name: 'Period' }) as HTMLSelectElement
    expect(period.value).toBe('latest-entry-month')
    await waitFor(() => expect(calls).toContain('/api/entries/summary?from=2026-08-01&to=2026-08-31'))
    expect(calls.filter((c) => c.startsWith('/api/entries?') && !c.includes('size=1')).every((c) => c.includes('2026-08-01')))
      .toBe(true)
    expect(screen.queryByRole('button', { name: 'Clear filters' })).toBeNull()
    fireEvent.change(period, { target: { value: 'all' } })
    await waitFor(() => expect(where).toBe('?period=all'))
    await waitFor(() => expect(calls).toContain('/api/entries/summary?'))
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeDefined()
    expect(entriesPeriod(new URLSearchParams('accountId=1'))).toBe('latest-entry-month')
  })
})

describe('each page’s remembered state (D-870, D-874, D-877)', () => {
  it('keeps one key per path under one prefix, forgets an empty query, and all of them at once', () => {
    rememberPage('/entries', '?period=all')
    rememberPage('/accounts', 'on=2026-09-01')
    rememberPage('/family/7/report', '?from=2026-01-01')
    expect(sessionStorage.getItem(`${PAGE_STATE_PREFIX}/entries`)).toBe('?period=all')
    expect(rememberedSearch('/accounts')).toBe('?on=2026-09-01')
    rememberPage('/accounts', '')
    expect(rememberedSearch('/accounts')).toBe('')
    sessionStorage.setItem('returnTo', '/x')
    forgetAllPages()
    expect(rememberedSearch('/entries')).toBe('')
    expect(rememberedSearch('/family/7/report')).toBe('')
    expect(sessionStorage.getItem('returnTo')).toBe('/x')
  })

  it('leads the header’s links to each page’s remembered state, the current page to its own', () => {
    rememberPage('/entries', '?period=all&q=rent')
    rememberPage('/', '?period=this-year')
    rememberPage('/family/7', '?x=1')
    render(
      <MemoryRouter initialEntries={['/accounts?on=2026-09-01']}>
        <Header families={[{ id: 7, name: 'Home' } as never]} name="Anna" />
      </MemoryRouter>,
    )
    const nav = screen.getByRole('navigation', { name: 'Main' })
    const href = (name: string) => within(nav).getByRole('link', { name }).getAttribute('href')
    expect(href('Entries')).toBe('/entries?period=all&q=rent')
    expect(href('Dashboard')).toBe('/?period=this-year')
    expect(href('Accounts')).toBe('/accounts?on=2026-09-01')
    expect(href('Categories')).toBe('/categories')
    expect(href('Home')).toBe('/family/7?x=1')
    expect(pageLink('/rates', { pathname: '/', search: '' })).toEqual({ pathname: '/rates', search: '' })
  })

  it('gives a link with filters but no period the remembered period, its own filters replacing the remembered ones', async () => {
    rememberPage('/entries', '?period=previous-month&accountId=3&q=rent')
    renderEntries('/entries?categoryId=7')
    await waitFor(() => expect(where).toBe('?period=previous-month&categoryId=7'))
    await waitFor(() => expect(calls).toContain('/api/entries/summary?from=2026-08-01&to=2026-08-31&categoryId=7'))
    // No request went out with another period first.
    expect(calls.filter((c) => c.startsWith('/api/entries/summary')).every((c) => c.includes('from=2026-08-01'))).toBe(true)
    expect(withRememberedPeriod(new URLSearchParams('accountId=1'), '?from=2026-01-05&q=x')?.toString())
      .toBe('from=2026-01-05&accountId=1')
    expect(withRememberedPeriod(new URLSearchParams(''), '?period=all')).toBeNull()
    expect(withRememberedPeriod(new URLSearchParams('period=all&accountId=1'), '?period=this-year')).toBeNull()
  })

  it('gives such a link the default period when nothing is remembered', async () => {
    renderEntries('/entries?accountId=1')
    await waitFor(() => expect(where).toBe('?period=latest-entry-month&accountId=1'))
  })

  it('forgets Entries’ state with Clear filters and returns to the default', async () => {
    rememberPage('/entries', '?period=all&q=rent')
    renderEntries('/entries?period=all&q=rent')
    fireEvent.click(await screen.findByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(where).toBe(''))
    expect(rememberedSearch('/entries')).toBe('')
    expect((screen.getByRole('combobox', { name: 'Period' }) as HTMLSelectElement).value).toBe('latest-entry-month')
    expect(screen.queryByRole('button', { name: 'Clear filters' })).toBeNull()
  })

  it('removes every page’s state at sign-out', () => {
    vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {})
    rememberPage('/entries', '?period=all')
    rememberPage('/', '?period=this-year')
    logOut()
    expect(Object.keys(sessionStorage).filter((k) => k.startsWith(PAGE_STATE_PREFIX))).toEqual([])
  })
})
