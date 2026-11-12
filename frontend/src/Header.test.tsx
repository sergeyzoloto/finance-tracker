import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { FamilyLedger, Me } from './api'
import { currentLinks, mainMenu, menuBudgets, PAGES } from './testHeader'

// UX-10: the header's two rows (D-498). Row 1 the wordmark, "New entry" and the user's menu (UX7.test.tsx); row 2 the
// main navigation. No theme control anywhere in it (D-499).

const ON: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-11-12', features: { familyLedgers: true } }
const OFF: Me = { ...ON, features: { familyLedgers: false } }
const home: FamilyLedger = { id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70, createdAt: '2026-09-29T10:00:00Z', startDate: '2026-09-01' }
const allotment: FamilyLedger = { ...home, id: 8, name: 'Allotment', role: 'MEMBER', memberId: 80 }

function stubApi(families: FamilyLedger[] = [home, allotment]) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const ok = url === '/api/family-ledgers'
    return new Response(JSON.stringify(ok ? families : { status: 404, detail: 'Not found.' }), {
      status: ok ? 200 : 404, headers: { 'Content-Type': 'application/json' },
    })
  }))
}

function Where() {
  const location = useLocation()
  return <p data-testid="where">{location.pathname}</p>
}

function renderApp(path: string, me: Me = ON) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App me={me} />
      <Routes><Route path="*" element={<Where />} /></Routes>
    </MemoryRouter>,
  )
}

const header = () => document.querySelector('header')!
const where = () => screen.getByTestId('where').textContent

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the header’s two rows (D-498)', () => {
  it('has the wordmark, “New entry” and the user’s menu in row 1, and the main navigation in row 2', async () => {
    stubApi()
    renderApp('/accounts')
    const rows = [...header().children]
    expect(rows.map((r) => r.tagName.toLowerCase() + (r.className ? `.${r.className}` : ''))).toEqual(['div.header-row', 'nav.main-nav'])
    expect(within(rows[0] as HTMLElement).getAllByRole('link').map((a) => [a.textContent, a.getAttribute('href')]))
      .toEqual([['Finance Tracker', '/'], ['New entry', '/entries/new']])
    expect(within(rows[0] as HTMLElement).getAllByRole('button').map((b) => b.textContent)).toEqual(['Anna'])
    expect(mainMenu()).toBe(rows[1])
    await waitFor(() => expect(within(mainMenu()).getAllByRole('link').map((a) => [a.textContent, a.getAttribute('href')]))
      .toEqual([['Dashboard', '/'], ['Entries', '/entries'], ['Accounts', '/accounts'], ['Categories', '/categories'],
        ['Rates', '/rates'], ['Allotment', '/family/8'], ['Home', '/family/7'], ['New family budget', '/family/new']]))
    // "My finances" and "← My finances" went (D-498, D-503).
    expect(within(header()).queryByText(/My finances/)).toBeNull()
  })

  it('lists the family budgets sorted, each with the shared icon, after a divider, then “New family budget” with a plus', async () => {
    stubApi([home, allotment])
    renderApp('/')
    await waitFor(() => expect(menuBudgets()).toEqual(['Allotment', 'Home', 'New family budget']))
    const links = within(mainMenu()).getAllByRole('link')
    const divider = mainMenu().querySelector('.nav-divider')!
    expect(divider.getAttribute('aria-hidden')).toBe('true')
    expect(divider.previousElementSibling?.textContent).toBe('Rates')
    expect(divider.nextElementSibling?.textContent).toBe('Allotment')
    for (const link of links.slice(PAGES.length, -1)) {
      // The icon is decorative: the link's name is the budget's.
      expect(link.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true')
    }
    expect(links.at(-1)!.querySelector('svg')).not.toBeNull()
  })

  it('has none of the family part with the switch off', () => {
    stubApi()
    renderApp('/accounts', OFF)
    expect(within(mainMenu()).getAllByRole('link').map((a) => a.textContent)).toEqual(PAGES)
    expect(mainMenu().querySelector('.nav-divider')).toBeNull()
    expect(screen.queryByText(/family budget/i)).toBeNull()
  })

  it.each([
    ['/', ['Dashboard'], true],
    ['/entries/new', ['Entries'], true],
    ['/settings/appearance', [], true],
    ['/family/new', ['New family budget'], true],
    ['/invite', [], true],
    ['/family/7/expenses', ['Home'], false],
  ])('on %s marks %j as current, with “New entry”: %s', async (path, current, newEntry) => {
    stubApi()
    renderApp(path)
    await waitFor(() => expect(menuBudgets()).toEqual(['Allotment', 'Home', 'New family budget']))
    expect(currentLinks()).toEqual(current)
    expect(within(header()).queryByRole('link', { name: 'New entry' }) !== null).toBe(newEntry)
    for (const link of within(mainMenu()).getAllByRole('link')) {
      expect(link.getAttribute('aria-current')).toBe(current.includes(link.textContent!) ? 'page' : null)
    }
  })

  it('inside a family budget, keeps its tab current on each of its pages, with no “← My finances”', async () => {
    stubApi()
    renderApp('/family/7/members')
    await waitFor(() => expect(currentLinks()).toEqual(['Home']))
    expect(screen.queryByRole('link', { name: '← My finances' })).toBeNull()
    fireEvent.click(within(mainMenu()).getByRole('link', { name: 'Dashboard' }))
    expect(where()).toBe('/')
    expect(currentLinks()).toEqual(['Dashboard'])
    expect(within(header()).getByRole('link', { name: 'New entry' })).toBeDefined()
  })

  it('the wordmark leads to the dashboard', () => {
    stubApi()
    renderApp('/rates')
    fireEvent.click(within(header()).getByRole('link', { name: 'Finance Tracker' }))
    expect(where()).toBe('/')
  })
})

describe('no theme control in the header (D-499)', () => {
  it.each([1280, 375])('has none at %i px', async (width) => {
    vi.stubGlobal('innerWidth', width)
    stubApi()
    renderApp('/accounts')
    await waitFor(() => expect(menuBudgets()).toHaveLength(3))
    act(() => { window.dispatchEvent(new Event('resize')) })
    // The user's panel, open, is inside the header too: nothing in either chooses a theme.
    fireEvent.click(within(header()).getByRole('button', { name: 'Anna' }))
    expect(within(header()).queryAllByRole('radio')).toEqual([])
    expect(within(header()).queryAllByRole('combobox')).toEqual([])
    expect(header().textContent).not.toMatch(/\b(theme|Blue|Light|Dark|Appearance)\b/i)
    expect(within(header()).getAllByRole('button').map((b) => b.textContent)).toEqual(['Anna', 'Log out'])
  })
})
