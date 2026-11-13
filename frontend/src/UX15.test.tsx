import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Me } from './api'
import { accountLink } from './Header'
import { cookieDomain, setTheme, startTheme, THEME_COOKIE, THEME_KEY, themeCookie } from './theme'
import { userMenu } from './testHeader'

// UX-15: the cookie that carries the theme to Keycloak's pages (D-614, D-617), and the user menu's Account (D-619).

/** Records what is written to `document.cookie`, which jsdom would otherwise keep for its own host only. */
function cookieWrites(): string[] {
  const writes: string[] = []
  Object.defineProperty(document, 'cookie', {
    configurable: true,
    get: () => '',
    set: (value: string) => { writes.push(value) },
  })
  return writes
}

/** A `localStorage` that throws on every call. */
function brokenStorage() {
  vi.stubGlobal('localStorage', {
    getItem: () => { throw new Error('denied') },
    setItem: () => { throw new Error('denied') },
    removeItem: () => { throw new Error('denied') },
    clear: () => undefined,
  })
}

beforeEach(() => {
  localStorage.clear()
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addEventListener: () => {}, removeEventListener: () => {} })))
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  // Back to jsdom's own document.cookie, on Document's prototype.
  delete (document as unknown as Record<string, unknown>).cookie
  localStorage.clear()
})

describe('the theme cookie (D-617)', () => {
  it('takes the parent of a host of three labels or more as its Domain, and none for fewer or an address', () => {
    expect(cookieDomain('app.finance-nl.com')).toBe('finance-nl.com')
    expect(cookieDomain('a.app.finance-nl.com')).toBe('app.finance-nl.com')
    expect(cookieDomain('finance-nl.com')).toBeNull()
    expect(cookieDomain('localhost')).toBeNull()
    expect(cookieDomain('127.0.0.1')).toBeNull()
    expect(cookieDomain('::1')).toBeNull()
  })

  it('is Secure on https only, a year long, Lax, on the whole site', () => {
    expect(themeCookie('blue', { hostname: 'app.finance-nl.com', protocol: 'https:' }))
      .toBe('ft_theme=blue; Path=/; Max-Age=31536000; SameSite=Lax; Domain=finance-nl.com; Secure')
    expect(themeCookie('dark', { hostname: 'localhost', protocol: 'http:' }))
      .toBe('ft_theme=dark; Path=/; Max-Age=31536000; SameSite=Lax')
    expect(themeCookie('light', { hostname: 'finance-nl.com', protocol: 'https:' }))
      .toBe('ft_theme=light; Path=/; Max-Age=31536000; SameSite=Lax; Secure')
  })

  it('is written by every choice, for this page’s location', () => {
    const writes = cookieWrites()
    setTheme('dark')
    setTheme('blue')
    expect(writes).toEqual([
      themeCookie('dark', window.location),
      themeCookie('blue', window.location),
    ])
    expect(writes[0].startsWith(`${THEME_COOKIE}=dark;`)).toBe(true)
    expect(localStorage.getItem(THEME_KEY)).toBe('blue')
  })

  it('is written by a choice the storage refuses too', () => {
    brokenStorage()
    const writes = cookieWrites()
    setTheme('light')
    expect(writes).toEqual([themeCookie('light', window.location)])
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('is renewed at the start from a stored choice, a choice made before UX-15 included', () => {
    localStorage.setItem(THEME_KEY, 'dark')
    const writes = cookieWrites()
    startTheme()
    expect(writes).toEqual([themeCookie('dark', window.location)])
  })

  it('is never written without a choice: nothing stored, another value, or a storage that throws', () => {
    const writes = cookieWrites()
    startTheme()
    localStorage.setItem(THEME_KEY, 'purple')
    startTheme()
    brokenStorage()
    startTheme()
    expect(writes).toEqual([])
  })
})

const ME: Me = {
  name: 'Anna', timeZone: 'UTC', today: '2026-11-13', features: { familyLedgers: true },
  accountUrl: 'https://auth.finance-nl.com/realms/myapps/account?referrer=finance-tracker',
}

function renderApp(me: Me) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(url === '/api/family-ledgers' ? [] : {}), {
    status: url === '/api/family-ledgers' ? 200 : 404, headers: { 'Content-Type': 'application/json' },
  })))
  return render(<MemoryRouter initialEntries={['/accounts']}><App me={me} /></MemoryRouter>)
}

describe('Account in the user menu (D-619)', () => {
  it('sits between Settings and Log out, a plain link to /api/me’s address, in the same tab', () => {
    renderApp(ME)
    const panel = userMenu('Anna')
    const items = [...panel.querySelectorAll('li')].map((li) => li.textContent)
    expect(items).toEqual(['Settings', 'Account', 'Log out'])
    const account = within(panel).getByRole('link', { name: 'Account' })
    expect(account.getAttribute('href')).toBe(ME.accountUrl)
    expect(account.getAttribute('target')).toBeNull()
    expect(account.getAttribute('rel')).toBeNull()
  })

  it('stays inside a family budget too', () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })))
    render(<MemoryRouter initialEntries={['/family/7/members']}><App me={ME} /></MemoryRouter>)
    expect(within(userMenu('Anna')).getByRole('link', { name: 'Account' }).getAttribute('href')).toBe(ME.accountUrl)
  })

  it('is left out without an address from /api/me, or with one that isn’t http or https', () => {
    renderApp({ ...ME, accountUrl: undefined })
    expect(within(userMenu('Anna')).queryByRole('link', { name: 'Account' })).toBeNull()
    cleanup()
    renderApp({ ...ME, accountUrl: 'javascript:alert(1)' })
    expect(within(userMenu('Anna')).queryByRole('link', { name: 'Account' })).toBeNull()
    expect(accountLink('http://localhost:8080/realms/myapps/account?referrer=finance-tracker')).not.toBeNull()
    expect(accountLink(null)).toBeNull()
  })

  it('is, under 40rem, an item like the others: no rule of index.css names it', () => {
    renderApp(ME)
    const account = within(userMenu('Anna')).getByRole('link', { name: 'Account' })
    const settings = within(userMenu('Anna')).getByRole('link', { name: 'Settings' })
    expect(account.className).toBe('')
    expect(account.closest('li')!.className).toBe(settings.closest('li')!.className)
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
    expect(css).toMatch(/\.menu-panel li a, \.menu-panel li button\.link \{[^}]*min-height: 40px/)
    expect(css).not.toMatch(/(menu-panel|user-menu)[^{]*account/i)
    expect(screen.getAllByRole('link', { name: 'Account' })).toHaveLength(1)
  })
})
