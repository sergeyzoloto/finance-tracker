// The Activity page without React: the request, and the plain sentence of each row. Names are the ones the API sends.

export interface ActivityActor { kind: string; memberId: number | null; name: string | null }
export interface ActivitySubject { memberId: number; name: string | null }

export interface ActivityRow {
  id: number
  at: string
  kind: string
  ledgerId: number | null
  actor: ActivityActor
  subject: ActivitySubject | null
  details: unknown
}

export interface ActivityPage { content: ActivityRow[]; page: number; size: number; total: number; totalPages: number }

/** Rows per request: the API's default. */
export const ACTIVITY_PAGE_SIZE = 50

export const activityPath = (page: number) => `/activity?page=${page}&size=${ACTIVITY_PAGE_SIZE}`

/** The page number of the address's `page` parameter: a whole number from 0, else 0. */
export function pageParam(value: string | null): number {
  return value !== null && /^\d{1,6}$/.test(value) ? Number(value) : 0
}

export const FORMER_MEMBER = 'Former member'
export const SOMEONE = 'Someone'
export const OTHER_ACTIVITY = 'Other activity'

/** What the page says once about sessions: a restart or a closed browser ends one without a sign-out. */
export const SESSION_NOTE =
  'A session can also end when the browser is closed or after the app restarts. Such an end is not recorded as a sign-out.'

/** Sentences of what the user did themselves, by kind. */
const OWN: Record<string, string> = {
  SIGN_IN: 'You signed in',
  SIGN_OUT: 'You signed out',
  SESSION_EXPIRED: 'Your session ended',
  SESSION_ENDED: 'Your session ended',
  SETTINGS_CHANGED: 'You changed your settings',
  MANUAL_RATE_SET: 'You set a manual exchange rate',
  MANUAL_RATE_CHANGED: 'You changed a manual exchange rate',
  MANUAL_RATE_REMOVED: 'You removed a manual exchange rate',
}

/** Sentences of the family budget's events; `who` is the member the sentence is about. */
const FAMILY: Record<string, (who: string, actor: string) => string> = {
  LEDGER_SETTINGS_CHANGED: (_who, actor) => `${actor} changed the family budget’s settings`,
  MEMBER_JOINED: (who) => `${who} joined the family budget`,
  SEAT_CLAIMED: (who) => `${who} took their place in the family budget`,
  MEMBER_LEFT: (who) => `${who} left the family budget`,
  INVITE_CREATED: (_who, actor) => `${actor} created an invitation to the family budget`,
  INVITE_REVOKED: (_who, actor) => `${actor} cancelled an invitation to the family budget`,
  SPLIT_RULE_RESET: () => 'The family budget’s split was reset to equal shares',
}

/** The name of the member a membership row is about: the API's, "Former member" when it sends none. */
export const subjectName = (row: ActivityRow) => row.subject?.name ?? FORMER_MEMBER

/** The name of who acted: the API's, "Someone" when it sends none. */
export const actorName = (row: ActivityRow) => row.actor?.name ?? SOMEONE

/** A snapshot of the ledger, by the name the API sent; without one, the plain sentence. */
function snapshotSentence(details: unknown): string {
  const name = typeof details === 'object' && details !== null ? (details as { name?: unknown }).name : undefined
  return typeof name === 'string' && name !== '' ? `You created a snapshot “${name}”` : 'You created a snapshot'
}

/** A restore: the snapshot's name and the records it wrote (restored, returned, deleted and archived; kept ones aren't). */
function restoreSentence(details: unknown): string {
  const d = typeof details === 'object' && details !== null ? (details as { name?: unknown; counts?: unknown }) : {}
  const counts = typeof d.counts === 'object' && d.counts !== null ? (d.counts as Record<string, unknown>) : {}
  const n = ['RESTORED', 'RETURNED', 'DELETED', 'ARCHIVED']
    .map((k) => counts[k])
    .reduce<number>((sum, v) => sum + (typeof v === 'number' ? v : 0), 0)
  const what = typeof d.name === 'string' && d.name !== '' ? `“${d.name}”` : 'a snapshot'
  return `Restored ${what}: ${n} ${n === 1 ? 'record' : 'records'} changed`
}

/** The sentence of a row; a kind it doesn't know is "Other activity", never the kind's name. */
export function sentence(row: ActivityRow): string {
  if (row.kind === 'SNAPSHOT_CREATED') return snapshotSentence(row.details)
  if (row.kind === 'RESTORE') return restoreSentence(row.details)
  const own = OWN[row.kind]
  if (own !== undefined) return own
  const family = FAMILY[row.kind]
  return family ? family(subjectName(row), actorName(row)) : OTHER_ACTIVITY
}
