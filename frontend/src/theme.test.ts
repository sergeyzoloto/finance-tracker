import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { deviceTheme, setTheme, startTheme, storedTheme, THEME_KEY, useTheme } from './theme'

// D-497: the theme belongs to the browser. theme.ts and public/theme.js (the first paint) follow the same rules.

/** A device in dark or light mode whose setting can change, as `matchMedia` gives it. */
function device(dark: boolean) {
  const listeners = new Set<() => void>()
  const query = {
    matches: dark,
    media: '(prefers-color-scheme: dark)',
    addEventListener: (_: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_: string, listener: () => void) => listeners.delete(listener),
  }
  vi.stubGlobal('matchMedia', vi.fn(() => query))
  return { set(next: boolean) { query.matches = next; listeners.forEach((l) => l()) } }
}

/** A `localStorage` that throws on every call. */
function brokenStorage() {
  const broken = { getItem: () => { throw new Error('denied') }, setItem: () => { throw new Error('denied') } }
  vi.stubGlobal('localStorage', broken)
}

const themeJs = readFileSync(resolve(process.cwd(), 'public/theme.js'), 'utf8')
/** Runs public/theme.js as the browser does, and gives what it set. */
function firstPaint(): string | null {
  document.documentElement.removeAttribute('data-theme')
  new Function('window', 'document', themeJs)(globalThis, document)
  return document.documentElement.getAttribute('data-theme')
}

beforeEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
})
afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

/** Each case: what is stored (or a storage that throws), the device, and the theme both must give. */
const CASES: [string, string | null | 'throws', boolean, 'blue' | 'light' | 'dark'][] = [
  ['a stored blue on a light device', 'blue', false, 'blue'],
  ['a stored light on a dark device', 'light', true, 'light'],
  ['a stored dark', 'dark', false, 'dark'],
  ['nothing stored, a dark device', null, true, 'blue'],
  ['nothing stored, a light device', null, false, 'light'],
  ['a stored value that isn’t a theme, a dark device', 'Blue', true, 'blue'],
  ['a stored value that isn’t a theme, a light device', 'purple', false, 'light'],
  ['a storage that throws, a dark device', 'throws', true, 'blue'],
  ['a storage that throws, a light device', 'throws', false, 'light'],
]

describe('theme.ts and theme.js give the same theme', () => {
  it.each(CASES)('%s', (_, stored, dark, expected) => {
    device(dark)
    if (stored === 'throws') brokenStorage()
    else if (stored !== null) localStorage.setItem(THEME_KEY, stored)
    startTheme()
    expect(document.documentElement.getAttribute('data-theme')).toBe(expected)
    expect(firstPaint()).toBe(expected)
  })
})

describe('theme.ts (D-497)', () => {
  it('reads a stored choice, and counts anything else as none', () => {
    localStorage.setItem(THEME_KEY, 'dark')
    expect(storedTheme()).toBe('dark')
    localStorage.setItem(THEME_KEY, 'sepia')
    expect(storedTheme()).toBeNull()
    brokenStorage()
    expect(storedTheme()).toBeNull()
  })

  it('without a choice follows the device, and its change', () => {
    const settings = device(false)
    startTheme()
    const { result } = renderHook(() => useTheme())
    expect(result.current).toMatchObject({ theme: 'light', chosen: false })
    act(() => settings.set(true))
    expect(deviceTheme()).toBe('blue')
    expect(result.current).toMatchObject({ theme: 'blue', chosen: false })
    expect(document.documentElement.getAttribute('data-theme')).toBe('blue')
    act(() => settings.set(false))
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('setTheme stores the choice and sets data-theme; the device no longer decides', () => {
    const settings = device(true)
    startTheme()
    const { result } = renderHook(() => useTheme())
    act(() => result.current.setTheme('dark'))
    expect(localStorage.getItem(THEME_KEY)).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(result.current).toMatchObject({ theme: 'dark', chosen: true })
    act(() => settings.set(false))
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    act(() => setTheme('light'))
    expect(result.current.theme).toBe('light')
  })

  it('applies a choice the storage refuses, for this page', () => {
    device(true)
    brokenStorage()
    startTheme()
    act(() => setTheme('dark'))
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })

  it('follows a choice made in another tab', () => {
    device(false)
    startTheme()
    localStorage.setItem(THEME_KEY, 'dark')
    act(() => { window.dispatchEvent(new StorageEvent('storage', { key: THEME_KEY })) })
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })
})
