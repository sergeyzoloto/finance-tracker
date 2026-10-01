import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  captureInvite, clearPendingInvite, inviteRedirect, KEEP_MS, pendingInvite, savePendingInvite, watchInviteLinks,
} from './invite'

// An invite link's token in the browser (F5, D-17): taken from the fragment, which leaves the address bar at once, kept
// in localStorage for 7 days at most, and the reason the app opens /invite after a sign-in or a registration.

const TOKEN = 'hdzuQFKxP6EGQ-9rAQCHxEOs0iXpnIkxdLhB9WhL0h0'
const NOW = Date.parse('2026-10-01T10:00:00Z')

function at(path: string) {
  history.replaceState(null, '', path)
}

afterEach(() => {
  clearPendingInvite()
  at('/')
  vi.restoreAllMocks()
})

describe('the link', () => {
  it('removes the fragment from the address bar before it keeps the token', () => {
    at('/invite#' + TOKEN)
    const replace = vi.spyOn(history, 'replaceState')
    const save = vi.spyOn(Storage.prototype, 'setItem')

    captureInvite(location, history, NOW)

    expect(location.pathname).toBe('/invite')
    expect(location.hash).toBe('')
    expect(location.href).not.toContain(TOKEN)
    expect(replace.mock.invocationCallOrder[0]).toBeLessThan(save.mock.invocationCallOrder[0])
    expect(pendingInvite(NOW)).toBe(TOKEN)
  })

  it('keeps nothing from other pages, and nothing that isn’t a token, but still clears the fragment', () => {
    at('/entries#' + TOKEN)
    captureInvite(location, history, NOW)
    expect(location.hash).toBe('#' + TOKEN)
    expect(pendingInvite(NOW)).toBeNull()

    at('/invite#<script>')
    captureInvite(location, history, NOW)
    expect(location.hash).toBe('')
    expect(pendingInvite(NOW)).toBeNull()
  })
})

describe('the token in localStorage', () => {
  it('lasts 7 days, and is erased when it is read after them', () => {
    savePendingInvite(TOKEN, NOW)
    expect(pendingInvite(NOW + KEEP_MS - 1)).toBe(TOKEN)
    expect(pendingInvite(NOW + KEEP_MS)).toBeNull()
    expect(localStorage.getItem('pendingInvite')).toBeNull()
  })

  it('is erased on demand, and anything broken in its place is erased too', () => {
    savePendingInvite(TOKEN, NOW)
    clearPendingInvite()
    expect(pendingInvite(NOW)).toBeNull()

    localStorage.setItem('pendingInvite', '{not json')
    expect(pendingInvite(NOW)).toBeNull()
    expect(localStorage.getItem('pendingInvite')).toBeNull()
  })

  it('reads as none when storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    expect(pendingInvite(NOW)).toBeNull()
  })
})

describe('the return after sign-in or registration', () => {
  it('opens the invite page wherever the app opens while a token waits, also in the verification email’s tab', () => {
    savePendingInvite(TOKEN, NOW)
    // The tab a sign-in or the verification link ends in lands on the start page, which finishLogin left it on.
    expect(inviteRedirect('/', true, NOW)).toBe('/invite')
    expect(inviteRedirect('/entries', true, NOW)).toBe('/invite')
    expect(inviteRedirect('/invite', true, NOW)).toBeNull()
  })

  it('stays where it is without a token, or with an old one', () => {
    expect(inviteRedirect('/', true, NOW)).toBeNull()
    savePendingInvite(TOKEN, NOW)
    expect(inviteRedirect('/', true, NOW + KEEP_MS)).toBeNull()
  })

  it('erases the token when the family budget is switched off, so that /invite ends like any unknown path', () => {
    savePendingInvite(TOKEN, NOW)
    expect(inviteRedirect('/invite', false, NOW)).toBeNull()
    expect(pendingInvite(NOW)).toBeNull()
  })
})

describe('a second link in the same tab', () => {
  it('takes the token when only the fragment changes, and reloads the page', () => {
    const OTHER = 'TG19eCPrn_Gr2ap6lYC1hdwYQ0YN1JnX_gHlD60Y-TE'
    const reload = vi.fn()
    const target = new EventTarget() as unknown as Window
    const fake = { pathname: '/invite', hash: '#' + OTHER, search: '', reload } as unknown as Location
    Object.assign(target, { location: fake, history })
    watchInviteLinks(target)

    target.dispatchEvent(new Event('hashchange'))

    expect(pendingInvite()).toBe(OTHER)
    expect(reload).toHaveBeenCalledOnce()
  })
})
