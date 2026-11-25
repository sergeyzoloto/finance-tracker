// Notifications without React: the request paths, where a row leads, and the badge's wording. The text of a row is
// built by the server and shown as sent.

export interface NotificationAuthor { memberId: number | null; name: string }

export interface NotificationRow {
  id: number
  kind: string
  at: string
  read: boolean
  ledgerId: number
  author: NotificationAuthor | null
  fromRevision: number | null
  toRevision: number | null
  changesCount: number | null
  text: string
}

export interface NotificationPage { content: NotificationRow[]; page: number; size: number; total: number; totalPages: number }

/** Rows per request: the API's default. */
export const NOTIFICATION_PAGE_SIZE = 50

export const NOTIFICATIONS_PATH = '/notifications'
export const UNREAD_COUNT_PATH = '/notifications/unread-count'
export const READ_ALL_PATH = '/notifications/read-all'
export const notificationsPath = (page: number) => `/notifications?page=${page}&size=${NOTIFICATION_PAGE_SIZE}`
export const readPath = (id: number) => `/notifications/${id}/read`

/** The kinds about who is in the family budget; they lead to its members. */
const MEMBERSHIP_KINDS = new Set(['MEMBER_JOINED', 'MEMBER_LEFT', 'SEAT_CLAIMED'])

/** Where opening a notification leads: the members of its family budget for a membership kind, else its journal. */
export function destination(kind: string, ledgerId: number): string {
  return `/family/${ledgerId}/${MEMBERSHIP_KINDS.has(kind) ? 'members' : 'journal'}`
}

/** The badge's text: the count, "99+" over 99. */
export const badgeText = (count: number) => (count > 99 ? '99+' : String(count))

/** The badge's accessible name. */
export const badgeName = (count: number) => `${count} unread notification${count === 1 ? '' : 's'}`

/** The count in an answer of the count endpoint, or none when it isn't a whole number from 0. */
export function countOf(answer: unknown): number | null {
  const count = (answer as { count?: unknown } | null)?.count
  return typeof count === 'number' && Number.isInteger(count) && count >= 0 ? count : null
}

/** The kinds about changes to the family budget's records, which the member accepts (HIST-3). */
const ACCEPT_KINDS = new Set(['FAMILY_CHANGES', 'FAMILY_IMPORTED', 'FAMILY_RESTORED'])
export const isAcceptKind = (kind: string) => ACCEPT_KINDS.has(kind)
