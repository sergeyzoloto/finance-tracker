import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { FamilyLedger, Me } from './api'
import CashFlowTable from './CashFlowTable'
import type { CompareTable } from './dashboard'
import { mainMenu, userMenu } from './testHeader'

// UX-7: the header's two menus (D-433 to D-436), Settings in parts (D-437, D-438), and the net's % across 0 (D-439).

const ON: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-11-10', features: { familyLedgers: true } }
const OFF: Me = { ...ON, features: { familyLedgers: false } }
const home: FamilyLedger = { id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70, createdAt: '2026-09-29T10:00:00Z', startDate: '2026-09-01' }
const allotment: FamilyLedger = { ...home, id: 8, name: 'Allotment', role: 'MEMBER', memberId: 80 }

/** Answers the family budgets' list; everything else 404, which the pages show as their own errors. */
function stubApi(families: FamilyLedger[] = [home, allotment]) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const body = url === '/api/family-ledgers' ? families : { status: 404, detail: 'Not found.' }
    return new Response(JSON.stringify(body), {
      status: url === '/api/family-ledgers' ? 200 : 404, headers: { 'Content-Type': 'application/json' },
    })
  }))
}

/** The location, and a way to change it from outside the header, for the assertions. */
function Where() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <>
      <p data-testid="where">{location.pathname}{location.search}</p>
      <button type="button" onClick={() => navigate('/rates')}>Go elsewhere</button>
    </>
  )
}

function renderApp(path: string, me: Me = ON) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App me={me} />
      <Routes><Route path="*" element={<Where />} /></Routes>
    </MemoryRouter>,
  )
}

const where = () => screen.getByTestId('where').textContent
const header = () => document.querySelector('header')!
const button = (name: string) => within(header()).getByRole('button', { name })

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the header in My finances (D-433)', () => {
  it('has the main menu, “New entry” and the user’s menu, and no other link', async () => {
    stubApi()
    renderApp('/accounts')
    const controls = within(header()).getAllByRole('button').map((b) => b.textContent)
    expect(controls).toEqual(['My finances', 'Anna'])
    expect(within(header()).getAllByRole('link').map((a) => [a.textContent, a.getAttribute('href')]))
      .toEqual([['New entry', '/entries/new']])
    expect(button('My finances').querySelector('svg')).not.toBeNull()
    expect(button('Anna').querySelector('svg')).not.toBeNull()
    // A long name is cut by CSS; the whole name is its title.
    expect(button('Anna').getAttribute('title')).toBe('Anna')

    const menu = mainMenu()
    await waitFor(() => expect(within(menu).getAllByRole('link').map((a) => a.textContent)).toEqual([
      'Dashboard', 'Entries', 'Accounts', 'Categories', 'Rates', 'Allotment', 'Home', 'New family budget…']))
    expect(within(menu).getByRole('list', { name: 'Family budgets' })).toBeDefined()
    expect(within(userMenu('Anna')).getByRole('link', { name: 'Settings' }).getAttribute('href')).toBe('/settings')
    expect(within(userMenu('Anna')).getByRole('button', { name: 'Log out' })).toBeDefined()
    // Import and Export moved into Settings (D-437).
    expect(screen.queryByRole('link', { name: 'Import' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Export' })).toBeNull()
  })

  it('with family budgets off, the main menu holds the pages only', () => {
    stubApi()
    renderApp('/accounts', OFF)
    expect(within(mainMenu()).getAllByRole('link').map((a) => a.textContent))
      .toEqual(['Dashboard', 'Entries', 'Accounts', 'Categories', 'Rates'])
    expect(screen.queryByText(/family budget/i)).toBeNull()
  })

  it('keeps My finances’ header on /family/new and /invite, which aren’t inside a budget (D-436)', () => {
    for (const path of ['/family/new', '/invite']) {
      stubApi()
      renderApp(path)
      expect(button('My finances')).toBeDefined()
      expect(within(header()).getByRole('link', { name: 'New entry' })).toBeDefined()
      cleanup()
    }
  })
})

describe('the header inside a family budget (D-435, D-436)', () => {
  it('has “← My finances” on the left and the user’s menu, no main menu and no “New entry”', () => {
    stubApi()
    renderApp('/family/7/members')
    expect(within(header()).getAllByRole('link').map((a) => [a.textContent, a.getAttribute('href')]))
      .toEqual([['← My finances', '/']])
    expect(within(header()).getAllByRole('button').map((b) => b.textContent)).toEqual(['Anna'])
    expect(within(header()).getByRole('link', { name: '← My finances' }).className).toBe('button')
    expect(within(userMenu('Anna')).getAllByRole('link').map((a) => a.textContent)).toEqual(['Settings'])
    fireEvent.click(within(header()).getByRole('link', { name: '← My finances' }))
    expect(where()).toBe('/')
    expect(button('My finances')).toBeDefined()
  })
})

describe('the menus (D-434)', () => {
  it('is a disclosure: a button with aria-expanded and aria-controls, its panel under it, not role="menu"', () => {
    stubApi()
    renderApp('/accounts')
    const main = button('My finances')
    expect(main.getAttribute('aria-expanded')).toBe('false')
    const panel = document.getElementById(main.getAttribute('aria-controls')!)!
    expect(panel.hidden).toBe(true)
    fireEvent.click(main)
    expect(main.getAttribute('aria-expanded')).toBe('true')
    expect(panel.hidden).toBe(false)
    expect(document.querySelector('[role="menu"], [role="menuitem"]')).toBeNull()
    fireEvent.click(main)
    expect(main.getAttribute('aria-expanded')).toBe('false')
  })

  it('closes on Escape and puts the focus back on its button', () => {
    stubApi()
    renderApp('/accounts')
    for (const name of ['My finances', 'Anna']) {
      const panel = name === 'Anna' ? userMenu(name) : mainMenu()
      within(panel).getAllByRole(name === 'Anna' ? 'button' : 'link')[0].focus()
      fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
      expect(button(name).getAttribute('aria-expanded')).toBe('false')
      expect(document.activeElement).toBe(button(name))
    }
  })

  it('closes on a click outside, but not on one inside its panel', () => {
    stubApi()
    renderApp('/accounts')
    const panel = mainMenu()
    fireEvent.mouseDown(panel)
    expect(button('My finances').getAttribute('aria-expanded')).toBe('true')
    fireEvent.mouseDown(document.querySelector('main')!)
    expect(button('My finances').getAttribute('aria-expanded')).toBe('false')
    userMenu('Anna')
    fireEvent.mouseDown(document.body)
    expect(button('Anna').getAttribute('aria-expanded')).toBe('false')
  })

  it('closes on choosing an item, and on any route change', () => {
    stubApi()
    renderApp('/accounts')
    fireEvent.click(within(mainMenu()).getByRole('link', { name: 'Entries' }))
    expect(where()).toBe('/entries')
    expect(button('My finances').getAttribute('aria-expanded')).toBe('false')
    userMenu('Anna')
    // A route change from anywhere else, with no click in the page (no mousedown).
    fireEvent.click(screen.getByRole('button', { name: 'Go elsewhere' }))
    expect(where()).toBe('/rates')
    expect(button('Anna').getAttribute('aria-expanded')).toBe('false')
  })

  it('has one open at a time', () => {
    stubApi()
    renderApp('/accounts')
    mainMenu()
    fireEvent.click(button('Anna'))
    expect(button('Anna').getAttribute('aria-expanded')).toBe('true')
    expect(button('My finances').getAttribute('aria-expanded')).toBe('false')
    fireEvent.click(button('My finances'))
    expect(button('Anna').getAttribute('aria-expanded')).toBe('false')
    expect(document.querySelectorAll('.menu-panel:not([hidden])')).toHaveLength(1)
  })

  it('marks the current page, and Settings on each of its parts', async () => {
    stubApi()
    renderApp('/accounts')
    const current = (panel: HTMLElement) =>
      within(panel).queryAllByRole('link').filter((a) => a.getAttribute('aria-current') === 'page').map((a) => a.textContent)
    expect(current(mainMenu())).toEqual(['Accounts'])
    fireEvent.click(within(mainMenu()).getByRole('link', { name: 'Dashboard' }))
    expect(current(mainMenu())).toEqual(['Dashboard'])
    fireEvent.click(within(userMenu('Anna')).getByRole('link', { name: 'Settings' }))
    await waitFor(() => expect(where()).toBe('/settings/time-zone'))
    expect(current(userMenu('Anna'))).toEqual(['Settings'])
    expect(current(mainMenu())).toEqual([])
  })
})

describe('Settings in parts (D-437, D-438)', () => {
  const parts = [
    ['Time zone', '/settings/time-zone'], ['Date format', '/settings/date-format'], ['Import', '/settings/import'],
    ['Export', '/settings/export'], ['Delete all my data', '/settings/delete'],
  ]

  it('opens Time zone for /settings, replacing the address', async () => {
    stubApi()
    renderApp('/settings')
    await waitFor(() => expect(where()).toBe('/settings/time-zone'))
    expect(screen.getByRole('heading', { level: 3, name: 'Time zone' })).toBeDefined()
  })

  it.each(parts)('shows %s as its own page: the heading, the parts, then the part, one h2', async (label, path) => {
    stubApi()
    const { container } = renderApp(path)
    const nav = screen.getByRole('navigation', { name: 'Settings' })
    expect(nav.className).toBe('subnav')
    expect(within(nav).getAllByRole('link').map((a) => [a.textContent, a.getAttribute('href')])).toEqual(parts)
    expect(within(nav).getByRole('link', { name: label }).getAttribute('aria-current')).toBe('page')
    expect([...container.querySelectorAll('h2')].map((h) => h.textContent)).toEqual(['Settings'])
    expect(screen.getByRole('heading', { level: 3, name: label })).toBeDefined()
    expect(where()).toBe(path)
  })

  it('sends an unknown part to Time zone', async () => {
    stubApi()
    renderApp('/settings/nothing')
    await waitFor(() => expect(where()).toBe('/settings/time-zone'))
  })

  it.each([
    ['/import', '/settings/import'],
    ['/import?from=x', '/settings/import?from=x'],
    ['/export?from=2026-10-01&to=2026-10-31', '/settings/export?from=2026-10-01&to=2026-10-31'],
  ])('redirects %s to %s, with its query', async (from, to) => {
    stubApi()
    renderApp(from)
    await waitFor(() => expect(where()).toBe(to))
  })

  it('links Import to Export at its new place', () => {
    stubApi()
    renderApp('/settings/import')
    const text = screen.getByText(/Import your transactions file/)
    expect(within(text).getByRole('link', { name: 'Export' }).getAttribute('href')).toBe('/settings/export')
  })
})

describe('the net’s % across 0 in the table (D-439, D-440)', () => {
  const column = (key: CompareTable['columns'][number]['key'], role: string, label: string) =>
    ({ key, role, label, range: { from: '2026-10-01', to: '2026-10-31' } })
  const table: CompareTable = {
    currency: 'EUR', converted: false,
    columns: [column('period', 'Period', 'Oct 2026'), column('year', 'Year earlier', 'Oct 2025'),
      column('previous', 'Previous period', 'Sep 2026')],
    income: { lines: [], subtotal: { key: 'subtotal', label: 'Total income', good: 'up', amounts: ['100', '100', '100'] } },
    expense: {
      lines: [{ key: 'GROCERIES', label: 'Groceries', good: 'down', amounts: ['-20', '50', '-40'] }],
      subtotal: { key: 'subtotal', label: 'Total expenses', good: 'down', amounts: ['-20', '50', '-40'] },
    },
    net: { key: 'net', label: 'Net', good: 'up', amounts: ['120', '50', '0'] },
    exchange: [],
  }

  it('shows “—” uncoloured where the two figures have opposite signs, or the net has 0 on either side', () => {
    render(<CashFlowTable table={table} />)
    const changes = (label: string) => [...screen.getByRole('rowheader', { name: label }).closest('tr')!
      .querySelectorAll('td.change')].map((td) => [td.textContent, td.className.trim()])
    // Groceries: refunds outweighed spending this October; against last year's spending "—", against September's
    // refunds a percentage.
    expect(changes('Groceries')).toEqual([['—', 'amount nowrap change'], ['+50.0%', 'amount nowrap change worse']])
    // The net: 120 against 50 a percentage; against September's 0, "—", where any other line would read −100.0%.
    expect(changes('Net')).toEqual([['+140.0%', 'amount nowrap change better'], ['—', 'amount nowrap change']])
  })
})
