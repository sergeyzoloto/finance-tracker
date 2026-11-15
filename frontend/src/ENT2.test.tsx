import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Entries from './Entries'
import { DIRECTIONS, toggled, valuesOf, withValue, withValues } from './entryFilters'
import { testLedger } from './testLedger'
import { WithMe } from './testMe'

// ENT-2 (D-873, D-880, D-888): several values per filter as repeated parameters, chips with ×, the selects adding a
// value, and Direction and Origin as toggle buttons. Today is 2026-09-30 (TEST_ME).

let calls: string[] = []
function stubApi() {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    const path = url.split('?')[0]
    const bodies: Record<string, unknown> = {
      '/api/accounts': testLedger.accounts, '/api/categories': testLedger.categories,
      '/api/counterparties': testLedger.counterparties, '/api/settings': testLedger.settings,
      '/api/entries/family-payees': [],
      '/api/entries/summary': { count: 0, firstDate: null, lastDate: null, payees: 0, withoutPayee: 0, categories: 0,
        withoutCategory: 0, accounts: 0, currencies: [] },
      '/api/entries': { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 },
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

const url = () => new URLSearchParams(where)
const chips = () => within(screen.getByRole('list', { name: 'Chosen filters' })).getAllByRole('listitem')
  .map((li) => li.textContent!.replace('×', '').trim())
const account = (code: string) => testLedger.accounts.find((a) => a.code === code)!
const category = (code: string) => testLedger.categories.find((c) => c.code === code)!

beforeEach(() => sessionStorage.clear())
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the list filters without React (D-873)', () => {
  it('reads each value once, drops blanks and unknown toggles, and adds and removes values', () => {
    const params = new URLSearchParams('accountId=1&accountId=2&accountId=1&accountId=&direction=SIDEWAYS&direction=INCOME&page=3')
    expect(valuesOf(params, 'accountId')).toEqual(['1', '2'])
    expect(valuesOf(params, 'direction')).toEqual(['INCOME'])
    expect(withValue(params, 'accountId', '3', true).toString())
      .toBe('direction=SIDEWAYS&direction=INCOME&accountId=1&accountId=2&accountId=3')
    expect(withValue(params, 'accountId', '1', false).getAll('accountId')).toEqual(['2'])
    expect(withValues(params, 'accountId', ['9']).getAll('accountId')).toEqual(['9'])
  })

  it('toggles: none pressed is all, the first press chooses that one, all pressed is none again', () => {
    const all = DIRECTIONS.map((d) => d.value)
    expect(toggled([], 'EXPENSES', all)).toEqual(['EXPENSES'])
    expect(toggled(['EXPENSES'], 'INCOME', all)).toEqual(['INCOME', 'EXPENSES'])
    expect(toggled(['INCOME', 'EXPENSES'], 'TRANSFERS', all)).toEqual([])
    expect(toggled(['EXPENSES'], 'EXPENSES', all)).toEqual([])
  })
})

describe('Entries’ chips and toggles (D-873)', () => {
  it('keeps a single-value link working, and adds a second account and category as chips', async () => {
    const current = account('CURRENT_ACCOUNT')
    const cash = account('CASH')
    renderEntries(`/entries?period=all&accountId=${current.id}`)
    await waitFor(() => expect(chips()).toEqual([`Account ${current.name}`]))
    await waitFor(() => expect(calls).toContain(`/api/entries/summary?accountId=${current.id}`))
    const select = screen.getByRole('combobox', { name: 'Account' }) as HTMLSelectElement
    // The select adds a value and doesn't offer one chosen.
    expect(select.value).toBe('')
    expect([...select.options].map((o) => o.value)).not.toContain(String(current.id))
    fireEvent.change(select, { target: { value: String(cash.id) } })
    expect(url().getAll('accountId')).toEqual([String(current.id), String(cash.id)])
    await waitFor(() => expect(calls).toContain(`/api/entries/summary?accountId=${current.id}&accountId=${cash.id}`))

    const groceries = category('GROCERIES')
    fireEvent.change(screen.getByRole('combobox', { name: 'Category' }), { target: { value: String(groceries.id) } })
    expect(url().getAll('categoryId')).toEqual([String(groceries.id)])
    expect(chips()).toEqual([`Account ${current.name}`, `Account ${cash.name}`, `Category ${groceries.name}`])

    fireEvent.click(screen.getByRole('button', { name: `Remove ${current.name}` }))
    expect(url().getAll('accountId')).toEqual([String(cash.id)])
  })

  it('sends Direction and Origin as toggles, none pressed meaning all', async () => {
    renderEntries('/entries?period=all')
    const group = await screen.findByRole('group', { name: 'Direction' })
    const button = (name: string) => within(group).getByRole('button', { name })
    expect(within(group).getAllByRole('button').map((b) => b.getAttribute('aria-pressed'))).toEqual(['false', 'false', 'false'])
    fireEvent.click(button('Expenses'))
    expect(url().getAll('direction')).toEqual(['EXPENSES'])
    expect(button('Expenses').getAttribute('aria-pressed')).toBe('true')
    await waitFor(() => expect(calls).toContain('/api/entries/summary?direction=EXPENSES'))
    fireEvent.click(button('Income'))
    expect(url().getAll('direction')).toEqual(['INCOME', 'EXPENSES'])

    const origin = screen.getByRole('group', { name: 'Origin' })
    fireEvent.click(within(origin).getByRole('button', { name: 'Family' }))
    await waitFor(() => expect(calls).toContain('/api/entries/summary?direction=INCOME&direction=EXPENSES&origin=FAMILY'))
    // A toggle is a filter: Clear filters shows, and clears it.
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(where).toBe('')
  })
})
