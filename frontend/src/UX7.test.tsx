import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { FamilyLedger, Me } from './api'
import CashFlowTable from './CashFlowTable'
import type { CompareTable } from './dashboard'
import { currentLinks, userMenu } from './testHeader'

// UX-7: the header's user menu (D-433, D-434; its main menu superseded by D-498), Settings in parts (D-437, D-438), and the net's % across 0 (D-439).

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

// The main menu of D-433 and D-436 went with UX-10 (D-498): row 2's tests are in Header.test.tsx. The user's menu stays.

describe('the user’s menu (D-433, D-464; restyled by D-498)', () => {
  it('holds Settings and Log out, its button the name with an icon and a title; Import and Export are in Settings', () => {
    stubApi()
    renderApp('/accounts')
    expect(within(header()).getAllByRole('button').map((b) => b.textContent)).toEqual(['Anna'])
    expect(button('Anna').querySelector('svg')).not.toBeNull()
    // A long name is cut by CSS; the whole name is its title.
    expect(button('Anna').getAttribute('title')).toBe('Anna')
    expect(within(userMenu('Anna')).getByRole('link', { name: 'Settings' }).getAttribute('href')).toBe('/settings')
    expect(within(userMenu('Anna')).getByRole('button', { name: 'Log out' })).toBeDefined()
    expect(screen.queryByRole('link', { name: 'Import' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Export' })).toBeNull()
  })

  it('stays inside a family budget, with Settings alone as its link', () => {
    stubApi()
    renderApp('/family/7/members')
    expect(within(header()).getAllByRole('button').map((b) => b.textContent)).toEqual(['Anna'])
    expect(within(userMenu('Anna')).getAllByRole('link').map((a) => a.textContent)).toEqual(['Settings'])
  })

  it('gives the name as the panel’s first line, for the person icon under 40rem (D-464)', () => {
    stubApi()
    renderApp('/accounts')
    expect(button('Anna').querySelector('.icon.person')).not.toBeNull()
    expect(userMenu('Anna').querySelector('.menu-user-name')?.textContent).toBe('Anna')
  })
})

describe('the user’s menu as a disclosure (D-434)', () => {
  it('is a button with aria-expanded and aria-controls, its panel under it, not role="menu"', () => {
    stubApi()
    renderApp('/accounts')
    const user = button('Anna')
    expect(user.getAttribute('aria-expanded')).toBe('false')
    const panel = document.getElementById(user.getAttribute('aria-controls')!)!
    expect(panel.hidden).toBe(true)
    // Items only while it is open.
    expect(within(panel).queryAllByRole('link')).toEqual([])
    fireEvent.click(user)
    expect(user.getAttribute('aria-expanded')).toBe('true')
    expect(panel.hidden).toBe(false)
    expect(document.querySelector('[role="menu"], [role="menuitem"]')).toBeNull()
    fireEvent.click(user)
    expect(user.getAttribute('aria-expanded')).toBe('false')
  })

  it('closes on Escape and puts the focus back on its button', () => {
    stubApi()
    renderApp('/accounts')
    within(userMenu('Anna')).getByRole('button', { name: 'Log out' }).focus()
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    expect(button('Anna').getAttribute('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(button('Anna'))
  })

  it('closes on a click outside, but not on one inside its panel', () => {
    stubApi()
    renderApp('/accounts')
    const panel = userMenu('Anna')
    fireEvent.mouseDown(panel)
    expect(button('Anna').getAttribute('aria-expanded')).toBe('true')
    fireEvent.mouseDown(document.querySelector('main')!)
    expect(button('Anna').getAttribute('aria-expanded')).toBe('false')
    userMenu('Anna')
    fireEvent.mouseDown(document.body)
    expect(button('Anna').getAttribute('aria-expanded')).toBe('false')
  })

  it('closes on choosing an item, and on any route change', async () => {
    stubApi()
    renderApp('/accounts')
    fireEvent.click(within(userMenu('Anna')).getByRole('link', { name: 'Settings' }))
    await waitFor(() => expect(where()).toBe('/settings/time-zone'))
    expect(button('Anna').getAttribute('aria-expanded')).toBe('false')
    userMenu('Anna')
    // A route change from anywhere else, with no click in the page (no mousedown).
    fireEvent.click(screen.getByRole('button', { name: 'Go elsewhere' }))
    expect(where()).toBe('/rates')
    expect(button('Anna').getAttribute('aria-expanded')).toBe('false')
  })

  it('marks Settings on each of its parts, and no page of row 2 then', async () => {
    stubApi()
    renderApp('/accounts')
    expect(currentLinks()).toEqual(['Accounts'])
    fireEvent.click(within(userMenu('Anna')).getByRole('link', { name: 'Settings' }))
    await waitFor(() => expect(where()).toBe('/settings/time-zone'))
    expect(currentLinks(userMenu('Anna'))).toEqual(['Settings'])
    expect(currentLinks()).toEqual([])
  })
})

describe('Settings in parts (D-437, D-438)', () => {
  const parts = [
    ['Time zone', '/settings/time-zone'], ['Date format', '/settings/date-format'],
    ['Appearance', '/settings/appearance'], ['Import', '/settings/import'],
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
    // Appearance's one heading is its Theme (D-499).
    expect(screen.getByRole('heading', { level: 3, name: label === 'Appearance' ? 'Theme' : label })).toBeDefined()
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
