import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { startApex } from './apex'
import { clearSessionHint, hasSessionHint, sessionCookie, setSessionHint } from './hint'
import Landing from './Landing'
import { appOrigin, AUTHOR_URL, isApexHost } from './links'
import { TEST_ME } from './testMe'

// WEB-1 (D-979 to D-982): the author's link, the apex host's landing page, and the sign-in hint.

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  document.cookie = 'ft_session=; Path=/; Max-Age=0'
})

describe('the host test (D-980)', () => {
  it('knows the apex by its two labels and nothing else', () => {
    expect(isApexHost('finance-nl.com')).toBe(true)
    expect(isApexHost('app.finance-nl.com')).toBe(false)
    expect(isApexHost('www.finance-nl.com')).toBe(false)
    expect(isApexHost('localhost')).toBe(false)
    expect(isApexHost('127.0.0.1')).toBe(false)
    expect(isApexHost('[::1]')).toBe(false)
    expect(appOrigin('finance-nl.com')).toBe('https://app.finance-nl.com')
  })
})

describe('the sign-in hint (D-981)', () => {
  it('is a session cookie on the parent domain, Secure on https, removed with Max-Age 0', () => {
    expect(sessionCookie({ hostname: 'app.finance-nl.com', protocol: 'https:' }, true))
      .toBe('ft_session=1; Path=/; SameSite=Lax; Domain=finance-nl.com; Secure')
    expect(sessionCookie({ hostname: 'app.finance-nl.com', protocol: 'https:' }, false))
      .toBe('ft_session=; Path=/; SameSite=Lax; Max-Age=0; Domain=finance-nl.com; Secure')
    expect(sessionCookie({ hostname: 'localhost', protocol: 'http:' }, true)).toBe('ft_session=1; Path=/; SameSite=Lax')
    expect(sessionCookie({ hostname: 'app.finance-nl.com', protocol: 'https:' }, true)).not.toContain('Max-Age')
  })

  it('is set and removed in the browser, and read from the cookie string', () => {
    setSessionHint()
    expect(hasSessionHint(document.cookie)).toBe(true)
    clearSessionHint()
    expect(hasSessionHint(document.cookie)).toBe(false)
    expect(hasSessionHint('a=1; ft_session=1; b=2')).toBe(true)
    expect(hasSessionHint('xft_session=1; ft_session=0')).toBe(false)
  })
})

describe('the apex branch (D-980)', () => {
  const fetch = () => {
    const stub = vi.fn()
    vi.stubGlobal('fetch', stub)
    return stub
  }

  it('redirects to the app with the hint and asks the backend nothing', () => {
    const stub = fetch()
    const replace = vi.fn()
    startApex({ hostname: 'finance-nl.com', replace }, 'a=b; ft_session=1')
    expect(replace).toHaveBeenCalledWith('https://app.finance-nl.com/')
    expect(stub).not.toHaveBeenCalled()
  })

  it('shows the landing page without it, its sign-in at the app and its privacy link on the apex', () => {
    const stub = fetch()
    const replace = vi.fn()
    render(<>{startApex({ hostname: 'finance-nl.com', replace }, 'ft_theme=blue')}</>)
    expect(replace).not.toHaveBeenCalled()
    expect(stub).not.toHaveBeenCalled()
    screen.getAllByRole('link', { name: /Sign in/ }).forEach((link) =>
      expect(link.getAttribute('href')).toBe('https://app.finance-nl.com/oauth2/authorization/keycloak'))
    expect(screen.getByRole('link', { name: 'Privacy policy' }).getAttribute('href')).toBe('/privacy')
  })

  it('is no branch at all on the app’s host, localhost or an address', () => {
    const replace = vi.fn()
    for (const hostname of ['app.finance-nl.com', 'localhost', '127.0.0.1']) {
      expect(startApex({ hostname, replace }, 'ft_session=1')).toBeNull()
    }
    expect(replace).not.toHaveBeenCalled()
  })
})

describe('the footers (D-979)', () => {
  it('end with the author’s link, in the landing page and in the app', () => {
    const { unmount } = render(<Landing />)
    expect(screen.getByRole('link', { name: 'Sergey Zolotko' }).getAttribute('href')).toBe(AUTHOR_URL)
    expect(AUTHOR_URL).toBe('https://cv-zoloto.pages.dev/')
    expect(document.querySelector('footer')!.textContent).toContain('Privacy policy · Source code · Sergey Zolotko')
    unmount()
    vi.stubGlobal('fetch', vi.fn(async () => new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })))
    render(<MemoryRouter><App me={TEST_ME} /></MemoryRouter>)
    expect(screen.getByRole('link', { name: 'Sergey Zolotko' }).getAttribute('href')).toBe(AUTHOR_URL)
    expect(document.querySelector('footer')!.textContent).toContain('Privacy policy · Source code · Sergey Zolotko')
  })
})
