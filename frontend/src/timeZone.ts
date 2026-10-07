// The user's time zone (D-100, D-101) without React: the browser's zone, the list to choose from, and the hint's
// remembered dismissal.

/** The browser's own IANA zone, such as "Europe/Amsterdam"; null if it can't say. */
export function browserZone(): string | null {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || null
  } catch {
    return null
  }
}

/** Every zone the browser knows, and UTC, which `Intl.supportedValuesOf` leaves out; sorted. */
export function allZones(): string[] {
  let zones: string[] = []
  try {
    zones = Intl.supportedValuesOf('timeZone')
  } catch { /* an old browser: only the zone it has, and UTC, are offered */ }
  const own = browserZone()
  return [...new Set([...zones, 'UTC', ...(own ? [own] : [])])].sort((a, b) => a.localeCompare(b))
}

/**
 * The zones that match what the user typed: every word of it in the name, in any case, an underscore reading as a
 * space ("new york" finds America/New_York), and "Europe/Amsterdam" itself. At most `limit`; none matching is empty.
 */
export function zoneMatches(zones: string[], typed: string, limit = 60): string[] {
  const words = typed.toLowerCase().split(/[\s/]+/).filter(Boolean)
  return zones.filter((zone) => {
    const name = zone.toLowerCase().replaceAll('_', ' ')
    return words.every((word) => name.includes(word))
  }).slice(0, limit)
}

const DISMISSED = 'finance-tracker:zone-hint-dismissed'

/** What the hint was dismissed for: "browser zone>saved zone", so that a changed situation shows it again. */
export function dismissedHint(): string | null {
  try {
    return localStorage.getItem(DISMISSED)
  } catch {
    return null
  }
}

export function dismissHint(browser: string, saved: string) {
  try {
    localStorage.setItem(DISMISSED, `${browser}>${saved}`)
  } catch { /* the hint comes back next time */ }
}

export const hintKey = (browser: string, saved: string) => `${browser}>${saved}`
