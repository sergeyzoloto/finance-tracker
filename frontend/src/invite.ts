// An invite link's token in the browser (F5; D-17, ADR 0003 topic G). The link is /invite#<token>: a fragment never
// reaches a server, so the token stays out of every access log. The page takes it from the fragment at once, removes
// the fragment from the address bar, and keeps the token in localStorage, which every tab of the origin shares: the
// tab the verification email opens after a registration finds it too. It is sent to the API only in request bodies,
// and erased after any outcome (accepted, declined, invalid or expired), at logout, and after 7 days.

export const INVITE_PATH = '/invite'

const KEY = 'pendingInvite'
/** How long the browser keeps a token it hasn't used: the longest an invite lasts (D-17). */
export const KEEP_MS = 7 * 24 * 60 * 60 * 1000
/** What a token looks like: base64url, 43 characters for 32 bytes; anything else isn't kept. */
const TOKEN = /^[A-Za-z0-9_-]{16,200}$/

interface Pending { token: string; expiresAt: number }

/**
 * On /invite with a token in the fragment: removes the fragment from the address bar first, then keeps the token.
 * Runs before anything else on the page, the request for the session included.
 */
export function captureInvite(where: Location = location, hist: History = history, now = Date.now()) {
  if (where.pathname !== INVITE_PATH || where.hash.length <= 1) return
  const token = where.hash.slice(1)
  hist.replaceState(hist.state, '', INVITE_PATH + where.search)
  if (TOKEN.test(token)) savePendingInvite(token, now)
}

/**
 * A link opened in a tab that shows /invite already, pasted or followed, changes only the fragment, which reloads
 * nothing: this takes the token from it as a page load would, and reloads the page to show its invite.
 */
export function watchInviteLinks(target: Window = window) {
  target.addEventListener('hashchange', () => {
    if (target.location.pathname !== INVITE_PATH || target.location.hash.length <= 1) return
    captureInvite(target.location, target.history)
    target.location.reload()
  })
}

export function savePendingInvite(token: string, now = Date.now()) {
  write(JSON.stringify({ token, expiresAt: now + KEEP_MS } satisfies Pending))
}

/** The token that waits for an outcome, or null; one older than 7 days is erased. */
export function pendingInvite(now = Date.now()): string | null {
  const stored = read()
  if (stored === null) return null
  try {
    const pending = JSON.parse(stored) as Pending
    if (typeof pending.token === 'string' && TOKEN.test(pending.token) && typeof pending.expiresAt === 'number'
      && pending.expiresAt > now) return pending.token
  } catch {
    // Not ours, or broken: erased below.
  }
  clearPendingInvite()
  return null
}

export function clearPendingInvite() {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // Storage blocked: nothing was kept either.
  }
}

/**
 * Where the app opens once it knows the user (main.tsx): /invite while a token waits, also in the tab that a sign-in
 * or a registration's verification email ends in; null to stay. With the family budget switched off there is no
 * invite page: the token is erased, and /invite ends on the dashboard like any unknown path.
 */
export function inviteRedirect(pathname: string, familyOn: boolean, now = Date.now()): string | null {
  if (!familyOn) {
    clearPendingInvite()
    return null
  }
  return pendingInvite(now) !== null && pathname !== INVITE_PATH ? INVITE_PATH : null
}

function read(): string | null {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}

function write(value: string) {
  try {
    localStorage.setItem(KEY, value)
  } catch {
    // Storage blocked: the token is lost, and the invite page asks for the link to be opened again.
  }
}
