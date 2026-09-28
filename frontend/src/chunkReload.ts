import { lazy, type ComponentType } from 'react'

// After an update, a tab opened before it still runs the old build, and the old build's lazily loaded chunks are gone
// from the server: importing one fails. Reloading the page gets the new build, since index.html is never cached
// (frontend/web.conf). If the page was reloaded for this a minute ago or less, the new build fails too, so the error
// is shown instead of reloading in a loop.

const RELOADED_AT = 'reloadedForNewBuildAt' // sessionStorage: when the page last reloaded for this, in ms
export const RELOAD_GUARD_MS = 60_000

/**
 * Reloads the page, unless it was reloaded for a new build within the last minute or the browser keeps no session
 * storage, which the guard against a loop needs. Returns whether it reloads.
 */
export function reloadForNewBuild(reload: () => void = () => location.reload(), now = Date.now()): boolean {
  try {
    const last = Number(sessionStorage.getItem(RELOADED_AT))
    if (last && now - last >= 0 && now - last < RELOAD_GUARD_MS) return false
    sessionStorage.setItem(RELOADED_AT, String(now))
  } catch {
    return false
  }
  reload()
  return true
}

/**
 * React.lazy, except that a chunk that fails to load reloads the page once, for a tab that outlived its build. `T` has
 * React.lazy's own bound, so that any component fits.
 */
export function lazyWithReload<T extends ComponentType<any>>(load: () => Promise<{ default: T }>) {
  return lazy(() => load().catch((error: unknown) => {
    // While the page reloads, Suspense keeps showing its fallback.
    if (reloadForNewBuild()) return new Promise<never>(() => {})
    throw error
  }))
}
