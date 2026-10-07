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

/**
 * Milliseconds from `now` to the next midnight in the zone (D-106), by the browser's clock: only a delay for a timer,
 * never a date shown (those come from /api/me). A zone this browser doesn't know counts as UTC. On a day with a clock
 * change the answer is an hour off, which the timer's re-arming after each answer absorbs.
 */
export function msUntilMidnight(zone: string, now: number): number {
  const parts = (id: string) => new Intl.DateTimeFormat('en-GB', {
    timeZone: id, hourCycle: 'h23', hour: 'numeric', minute: 'numeric', second: 'numeric',
  }).formatToParts(now)
  let found: Intl.DateTimeFormatPart[]
  try {
    found = parts(zone)
  } catch {
    found = parts('UTC')
  }
  const of = (type: string) => Number(found.find((p) => p.type === type)?.value ?? 0)
  const sinceMidnight = ((of('hour') * 60 + of('minute')) * 60 + of('second')) * 1000 + (((now % 1000) + 1000) % 1000)
  return 24 * 3600_000 - sinceMidnight
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
