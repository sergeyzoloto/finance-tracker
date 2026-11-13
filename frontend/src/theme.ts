import { useSyncExternalStore } from 'react'

/**
 * The app's three themes (D-495 to D-497): Blue, Light and Dark, their tokens in `index.css` under `data-theme` on
 * `<html>`. The choice belongs to the browser (`localStorage`, never the server); with none, the device decides: dark
 * gives Blue, light gives Light, followed live while nothing is chosen. `public/theme.js` sets the first paint by the
 * same rules (its test runs both); from then on this module owns every change. A choice is also written to the cookie
 * `ft_theme` (D-617), which Keycloak's pages read to show the same theme (D-614).
 */
export const THEMES = ['blue', 'light', 'dark'] as const
export type Theme = (typeof THEMES)[number]

export const THEME_KEY = 'finance-tracker.theme'
const DARK_QUERY = '(prefers-color-scheme: dark)'

export const isTheme = (value: unknown): value is Theme => THEMES.includes(value as Theme)

/** The stored choice, or null for none: nothing stored, another value, or a storage that throws. */
export function storedTheme(): Theme | null {
  try {
    const value = localStorage.getItem(THEME_KEY)
    return isTheme(value) ? value : null
  } catch {
    return null
  }
}

function darkQuery(): MediaQueryList | null {
  try {
    return typeof matchMedia === 'function' ? matchMedia(DARK_QUERY) : null
  } catch {
    return null
  }
}

/** The device's theme: Blue for a dark device, else Light. */
export function deviceTheme(): Theme {
  return darkQuery()?.matches ? 'blue' : 'light'
}

/** The theme in force: the stored choice, else the device's. */
export function currentTheme(): Theme {
  return storedTheme() ?? deviceTheme()
}

const listeners = new Set<() => void>()
let snapshot: { theme: Theme; chosen: boolean } | null = null
/** A choice the storage refused: in force for this page only. */
let unsaved: Theme | null = null

function read() {
  const chosen = storedTheme() ?? unsaved
  const theme = chosen ?? deviceTheme()
  if (!snapshot || snapshot.theme !== theme || snapshot.chosen !== (chosen !== null)) {
    snapshot = { theme, chosen: chosen !== null }
  }
  return snapshot
}

function apply() {
  const { theme } = read()
  document.documentElement.setAttribute('data-theme', theme)
  listeners.forEach((listener) => listener())
}

/** The cookie that tells the sign-in pages (Keycloak's theme, ../auth_server) the choice (D-614, D-617). */
export const THEME_COOKIE = 'ft_theme'
const COOKIE_MAX_AGE = 31536000

/**
 * The cookie's `Domain` (D-617): the parent of the app's host when the host has at least three labels
 * (`app.finance-nl.com` gives `finance-nl.com`, so that `auth.finance-nl.com` reads it), else none, a host-only cookie
 * (`localhost`, which Keycloak on `localhost:8080` reads too, cookies taking no port). An IP address has no parent.
 */
export function cookieDomain(hostname: string): string | null {
  if (/^[\d.]+$/.test(hostname) || hostname.includes(':')) return null
  const labels = hostname.split('.')
  return labels.length >= 3 ? labels.slice(1).join('.') : null
}

/** The `Set-Cookie`-like text `document.cookie` takes for a choice, at this location (D-617). */
export function themeCookie(theme: Theme, location: { hostname: string; protocol: string }): string {
  const domain = cookieDomain(location.hostname)
  return `${THEME_COOKIE}=${theme}; Path=/; Max-Age=${COOKIE_MAX_AGE}; SameSite=Lax`
    + (domain ? `; Domain=${domain}` : '') + (location.protocol === 'https:' ? '; Secure' : '')
}

/** Writes the cookie for a choice. Nothing on the server reads it; only Keycloak's pages do, in the browser. */
function writeCookie(theme: Theme) {
  try {
    document.cookie = themeCookie(theme, window.location)
  } catch {
    // A browser that refuses cookies: the sign-in pages follow the device.
  }
}

/**
 * Stores the choice and applies it at once, and writes the cookie (D-617), also when the storage refuses the choice.
 * A storage that throws still applies it for this page.
 */
export function setTheme(theme: Theme) {
  try {
    localStorage.setItem(THEME_KEY, theme)
    unsaved = null
  } catch {
    // Not kept: the theme applies to this page only.
    unsaved = theme
  }
  writeCookie(theme)
  apply()
}

let started = false
let deviceQuery: MediaQueryList | null = null

/**
 * Sets `data-theme` (as theme.js has, unless it didn't run) and follows the device's setting and other tabs' choices
 * from then on. Called once, by `main.tsx`, for the app, the landing page and the notices alike.
 */
export function startTheme() {
  unsaved = null
  // A stored choice renews the cookie's year, and writes it for a choice made before UX-15 (D-617); none writes none.
  const stored = storedTheme()
  if (stored) writeCookie(stored)
  // Only matters while nothing is chosen: `read` takes a stored choice first.
  deviceQuery?.removeEventListener?.('change', apply)
  deviceQuery = darkQuery()
  deviceQuery?.addEventListener?.('change', apply)
  apply()
  if (started) return
  started = true
  window.addEventListener('storage', (event) => { if (event.key === THEME_KEY || event.key === null) apply() })
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

/** The theme in force, whether it was chosen, and how to choose one. */
export function useTheme(): { theme: Theme; chosen: boolean; setTheme: (theme: Theme) => void } {
  const { theme, chosen } = useSyncExternalStore(subscribe, read)
  return { theme, chosen, setTheme }
}
