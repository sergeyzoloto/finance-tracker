import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BreakdownRow, Counterparty } from './api'
import { isChosen, myShareTexts, withChosen, withOnly } from './breakdown'
import Entries from './Entries'
import { SUMMARY_FOLD_KEY } from './EntrySummaryPanel'
import { testLedger } from './testLedger'
import { WithMe } from './testMe'

// ENT-3 (D-873, D-881): the breakdown under the amount table: tabs, rows with a checkbox and "only", folding with the
// panel. Today is 2026-09-30 (TEST_ME).

const plain = (text: string | null | undefined) => (text ?? '').replace(/[   ]/g, ' ').replace(/−/g, '-').trim()
const row = (key: string | null, name: string, entries = 1, currencies: BreakdownRow['currencies'] = []): BreakdownRow =>
  ({ key, name, entries, currencies })
const shop: Counterparty = { id: 31, name: 'Shop', archived: false } as Counterparty

describe('the breakdown without React (D-873, D-881)', () => {
  it('shows My share as one signed net per currency', () => {
    expect(myShareTexts(row('1', 'Groceries', 3, [
      { currency: 'EUR', incoming: '0.00', outgoing: '45.20' },
      { currency: 'USD', incoming: '100.00', outgoing: '40.00' }])).map((s) => `${s.currency} ${plain(s.text)} ${s.sign}`))
      .toEqual(['EUR -€45.20 -1', 'USD +$60.00 1'])
    expect(myShareTexts(row(null, 'No category', 2))).toEqual([])
  })

  it('adds and removes a category or account in its own filter, and "only" makes it the only one', () => {
    const params = new URLSearchParams('period=all&categoryId=1&categoryId=2&accountId=5&page=2')
    expect(isChosen(params, 'category', row('2', 'Rent'), [])).toBe(true)
    expect(isChosen(params, 'category', row('3', 'Salary'), [])).toBe(false)
    expect(withChosen(params, 'category', row('3', 'Salary'), [], true).getAll('categoryId')).toEqual(['1', '2', '3'])
    expect(withChosen(params, 'category', row('3', 'Salary'), [], true).has('page')).toBe(false)
    expect(withChosen(params, 'category', row('1', 'Rent'), [], false).getAll('categoryId')).toEqual(['2'])
    expect(withOnly(params, 'category', row('9', 'Gifts')).getAll('categoryId')).toEqual(['9'])
    expect(withOnly(params, 'account', row('8', 'Cash')).getAll('accountId')).toEqual(['8'])
    expect(withOnly(params, 'account', row('8', 'Cash')).getAll('categoryId')).toEqual(['1', '2'])
    // The row without a key has no checkbox and no "only".
    expect(isChosen(params, 'category', row(null, 'No category'), [])).toBe(false)
    expect(withChosen(params, 'category', row(null, 'No category'), [], true)).toBe(params)
    expect(withOnly(params, 'category', row(null, 'No category'))).toBe(params)
  })

  it('takes a payee by name in any case, or by a counterparty of that name', () => {
    const named = new URLSearchParams('payee=SHOP&payee=Cafe')
    const byId = new URLSearchParams('counterpartyId=31')
    expect(isChosen(named, 'payee', row('shop', 'Shop'), [shop])).toBe(true)
    expect(isChosen(byId, 'payee', row('shop', 'Shop'), [shop])).toBe(true)
    expect(isChosen(byId, 'payee', row('cafe', 'Cafe'), [shop])).toBe(false)
    expect(withChosen(byId, 'payee', row('cafe', 'Cafe'), [shop], true).getAll('payee')).toEqual(['Cafe'])
    // Removed however it was chosen.
    const both = new URLSearchParams('payee=SHOP&payee=Cafe&counterpartyId=31&counterpartyId=40')
    const removed = withChosen(both, 'payee', row('shop', 'Shop'), [shop], false)
    expect(removed.getAll('payee')).toEqual(['Cafe'])
    expect(removed.getAll('counterpartyId')).toEqual(['40'])
    const only = withOnly(both, 'payee', row('cafe', 'Cafe'))
    expect(only.getAll('payee')).toEqual(['Cafe'])
    expect(only.has('counterpartyId')).toBe(false)
  })
})

let calls: string[] = []
const breakdowns: Record<string, BreakdownRow[]> = {
  category: [row('12', 'Groceries', 3, [{ currency: 'EUR', incoming: '0.00', outgoing: '45.20' }]),
    row('13', 'Salary', 1, [{ currency: 'EUR', incoming: '2000.00', outgoing: '0.00' }]), row(null, 'No category', 1)],
  payee: [row('shop', 'Shop', 2, [{ currency: 'EUR', incoming: '0.00', outgoing: '20.00' }]), row(null, 'No payee', 3)],
  account: [row('1', 'Current account', 4)],
}
function stubApi() {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    const [path, query = ''] = url.split('?')
    const params = new URLSearchParams(query)
    const bodies: Record<string, unknown> = {
      '/api/accounts': testLedger.accounts, '/api/categories': testLedger.categories,
      '/api/counterparties': testLedger.counterparties, '/api/settings': testLedger.settings,
      '/api/entries/family-payees': [],
      '/api/entries/summary': { count: 5, firstDate: '2026-09-01', lastDate: '2026-09-20', payees: 1, withoutPayee: 3,
        categories: 2, withoutCategory: 1, accounts: 1,
        currencies: [{ currency: 'EUR', incoming: '2000.00', outgoing: '45.20', transfers: '0.00', spent: '45.20',
          spentOthersShares: '0.00', received: '2000.00', receivedOthersShares: '0.00' }] },
      '/api/entries': { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 },
      '/api/entries/breakdown': { by: String(params.get('by')).toUpperCase(), rows: breakdowns[params.get('by') ?? ''] },
    }
    const body = bodies[path]
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
  render(
    <WithMe>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path="/entries" element={<><Entries /><Where /></>} /></Routes>
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

describe('the breakdown on Entries (D-873, D-881)', () => {
  it('lists each tab’s rows under the amount table, with the filters sent as they are', async () => {
    renderEntries('/entries?period=all&accountId=1')
    const table = await screen.findByRole('table', { name: 'By category' })
    expect(within(table).getAllByRole('row').slice(1).map((r) => plain(r.textContent))).toEqual([
      'Groceries3-€45.20only', 'Salary1+€2,000.00only', 'No category1—'])
    // No checkbox and no "only" for the row without a key.
    expect(within(table).getAllByRole('checkbox')).toHaveLength(2)
    expect(calls).toContain('/api/entries/breakdown?by=category&accountId=1')
    fireEvent.click(screen.getByRole('tab', { name: 'By payee' }))
    await screen.findByRole('table', { name: 'By payee' })
    expect(calls).toContain('/api/entries/breakdown?by=payee&accountId=1')
    expect(screen.getByRole('tab', { name: 'By payee' }).getAttribute('aria-selected')).toBe('true')
    fireEvent.click(screen.getByRole('tab', { name: 'By account' }))
    expect(await screen.findByRole('table', { name: 'By account' })).toBeDefined()
  })

  it('adds a value with its checkbox, removes it again, and "only" replaces the filter’s values', async () => {
    renderEntries('/entries?period=all&categoryId=13')
    const table = await screen.findByRole('table', { name: 'By category' })
    const salary = within(table).getByRole('checkbox', { name: 'Salary' }) as HTMLInputElement
    expect(salary.checked).toBe(true)
    expect((within(table).getByRole('checkbox', { name: 'Groceries' }) as HTMLInputElement).checked).toBe(false)
    fireEvent.click(within(table).getByRole('checkbox', { name: 'Groceries' }))
    expect(new URLSearchParams(where).getAll('categoryId')).toEqual(['13', '12'])
    await waitFor(() => expect(calls).toContain('/api/entries/breakdown?by=category&categoryId=13&categoryId=12'))
    fireEvent.click(within(await screen.findByRole('table', { name: 'By category' })).getByRole('checkbox', { name: 'Salary' }))
    expect(new URLSearchParams(where).getAll('categoryId')).toEqual(['12'])
    fireEvent.click(screen.getByRole('button', { name: 'Only Salary' }))
    expect(new URLSearchParams(where).getAll('categoryId')).toEqual(['13'])
    // The payee tab: "only" sets the name.
    fireEvent.click(screen.getByRole('tab', { name: 'By payee' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Only Shop' }))
    expect(new URLSearchParams(where).getAll('payee')).toEqual(['Shop'])
  })

  it('folds with the summary and asks nothing while folded', async () => {
    localStorage.setItem(SUMMARY_FOLD_KEY, 'folded')
    renderEntries('/entries?period=all')
    await screen.findByRole('button', { name: 'Unfold the summary' })
    expect(screen.queryByRole('table', { name: 'By category' })).toBeNull()
    expect(calls.some((c) => c.startsWith('/api/entries/breakdown'))).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Unfold the summary' }))
    expect(await screen.findByRole('table', { name: 'By category' })).toBeDefined()
  })
})
