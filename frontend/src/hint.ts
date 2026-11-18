import { cookieDomain } from './theme'

/**
 * The sign-in hint (D-981): the cookie `ft_session=1` tells the apex page (finance-nl.com) that this browser has signed
 * in at the app, so that it sends the visitor there instead of showing the landing page. It holds nothing about the
 * user, decides nothing in the app, lasts as long as the browser session (no Max-Age), and is on the parent domain
 * like `ft_theme` (`finance-nl.com` in production, host-only for localhost).
 */
export const SESSION_COOKIE = 'ft_session'

type Place = { hostname: string; protocol: string }

/** The text `document.cookie` takes to set the hint, or with `set` false to remove it. */
export function sessionCookie(place: Place, set: boolean): string {
  const domain = cookieDomain(place.hostname)
  return `${SESSION_COOKIE}=${set ? '1' : ''}; Path=/; SameSite=Lax` + (set ? '' : '; Max-Age=0')
    + (domain ? `; Domain=${domain}` : '') + (place.protocol === 'https:' ? '; Secure' : '')
}

/** Whether a `document.cookie` string holds the hint. */
export function hasSessionHint(cookies: string): boolean {
  return cookies.split('; ').includes(`${SESSION_COOKIE}=1`)
}

function write(text: string) {
  try {
    document.cookie = text
  } catch {
    // A browser that refuses cookies: the apex page shows the landing page, whose sign-in link works.
  }
}

/** The app got a valid `/api/me`: the browser has a session. */
export function setSessionHint() {
  write(sessionCookie(window.location, true))
}

/** A 401, a 403, or a logout: the browser has none. */
export function clearSessionHint() {
  write(sessionCookie(window.location, false))
}
