import { describe, expect, it } from 'vitest'
import { badgeName, badgeText, countOf, destination, notificationsPath, readPath } from './notifications'

describe('notifications.ts', () => {
  it('builds the request paths', () => {
    expect(notificationsPath(2)).toBe('/notifications?page=2&size=50')
    expect(readPath(9)).toBe('/notifications/9/read')
  })

  it('sends membership kinds to the members and the others to the journal of their budget', () => {
    for (const kind of ['MEMBER_JOINED', 'MEMBER_LEFT', 'SEAT_CLAIMED']) expect(destination(kind, 7)).toBe('/family/7/members')
    for (const kind of ['FAMILY_CHANGES', 'FAMILY_IMPORTED']) expect(destination(kind, 7)).toBe('/family/7/journal')
  })

  it('words the badge', () => {
    expect(badgeText(5)).toBe('5')
    expect(badgeText(99)).toBe('99')
    expect(badgeText(100)).toBe('99+')
    expect(badgeName(1)).toBe('1 unread notification')
    expect(badgeName(3)).toBe('3 unread notifications')
  })

  it('reads the count only when it is a whole number from 0', () => {
    expect(countOf({ count: 4 })).toBe(4)
    expect(countOf({ count: -1 })).toBeNull()
    expect(countOf({ count: '4' })).toBeNull()
    expect(countOf(null)).toBeNull()
  })
})
