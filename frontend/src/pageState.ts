// Each tab's last URL state of every page (D-870, D-877): the query a path last showed, kept in session storage under
// one key per path with one prefix, so that the header's links lead back to it. Removed at sign-out. Every read and
// write tolerates a storage that throws: the app then works as if nothing were remembered.

export const PAGE_STATE_PREFIX = 'finance-tracker.page:'

const key = (path: string) => PAGE_STATE_PREFIX + path

/** Notes the query `path` shows now; an empty one forgets it. */
export function rememberPage(path: string, search: string) {
  try {
    if (search === '' || search === '?') sessionStorage.removeItem(key(path))
    else sessionStorage.setItem(key(path), search.startsWith('?') ? search : `?${search}`)
  } catch { /* no storage: nothing remembered */ }
}

/** The query `path` last showed in this tab, with its `?`, or ''. */
export function rememberedSearch(path: string): string {
  try {
    return sessionStorage.getItem(key(path)) ?? ''
  } catch {
    return ''
  }
}

/** Forgets `path`'s state (Entries' "Clear filters", D-874). */
export function forgetPage(path: string) {
  try {
    sessionStorage.removeItem(key(path))
  } catch { /* nothing to forget */ }
}

/** Forgets every page's state (sign-out, D-877). */
export function forgetAllPages() {
  try {
    for (let i = sessionStorage.length - 1; i >= 0; i--) {
      const k = sessionStorage.key(i)
      if (k?.startsWith(PAGE_STATE_PREFIX)) sessionStorage.removeItem(k)
    }
  } catch { /* nothing to forget */ }
}

/**
 * A header link's target (D-870): the page with the query it last showed in this tab; the current page's own query
 * while it is shown, since its newest state is noted only after this render.
 */
export function pageLink(path: string, current: { pathname: string; search: string }) {
  return { pathname: path, search: path === current.pathname ? current.search : rememberedSearch(path) }
}
