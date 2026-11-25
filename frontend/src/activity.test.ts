import { describe, expect, it } from 'vitest'
import {
  activityPath, FORMER_MEMBER, OTHER_ACTIVITY, pageParam, sentence, SOMEONE, type ActivityRow,
} from './activity'

const row = (kind: string, extra: Partial<ActivityRow> = {}): ActivityRow => ({
  id: 1, at: '2026-11-12T10:00:00Z', kind, ledgerId: null, actor: { kind: 'USER', memberId: null, name: null },
  subject: null, details: null, ...extra,
})
const family = (kind: string, actor: string | null, subject: string | null | undefined): ActivityRow =>
  row(kind, {
    ledgerId: 7, actor: { kind: 'USER', memberId: 70, name: actor },
    subject: subject === undefined ? null : { memberId: 80, name: subject },
  })

describe('the Activity request and sentences', () => {
  it('asks for a page at the API’s default size', () => {
    expect(activityPath(0)).toBe('/activity?page=0&size=50')
    expect(activityPath(3)).toBe('/activity?page=3&size=50')
  })

  it('reads the page from the address, 0 for anything else', () => {
    expect(pageParam('2')).toBe(2)
    for (const bad of [null, '', '-1', 'x', '1.5', '2e3']) expect(pageParam(bad)).toBe(0)
    // More than six digits reads as the first page.
    expect(pageParam('1234567')).toBe(0)
  })

  it('says each of the user’s own kinds plainly', () => {
    expect(sentence(row('SIGN_IN'))).toBe('You signed in')
    expect(sentence(row('SIGN_OUT'))).toBe('You signed out')
    expect(sentence(row('SESSION_EXPIRED'))).toBe('Your session ended')
    expect(sentence(row('SESSION_ENDED'))).toBe('Your session ended')
    expect(sentence(row('SETTINGS_CHANGED'))).toBe('You changed your settings')
    expect(sentence(row('MANUAL_RATE_SET'))).toBe('You set a manual exchange rate')
    expect(sentence(row('MANUAL_RATE_CHANGED'))).toBe('You changed a manual exchange rate')
    expect(sentence(row('MANUAL_RATE_REMOVED'))).toBe('You removed a manual exchange rate')
  })

  it('says a snapshot with its name, and plainly without one', () => {
    expect(sentence(row('SNAPSHOT_CREATED', { details: { snapshotId: 3, name: 'Before the move', revision: 9, kind: 'MANUAL' } })))
      .toBe('You created a snapshot “Before the move”')
    expect(sentence(row('SNAPSHOT_CREATED'))).toBe('You created a snapshot')
  })

  it('says each family kind with the names the API sent', () => {
    expect(sentence(family('MEMBER_JOINED', 'Ben', 'Ben'))).toBe('Ben joined the family budget')
    expect(sentence(family('SEAT_CLAIMED', 'Ben', 'Ben'))).toBe('Ben took their place in the family budget')
    expect(sentence(family('MEMBER_LEFT', 'Anna', 'Ben'))).toBe('Ben left the family budget')
    expect(sentence(family('LEDGER_SETTINGS_CHANGED', 'Anna', undefined))).toBe('Anna changed the family budget’s settings')
    expect(sentence(family('INVITE_CREATED', 'Anna', undefined))).toBe('Anna created an invitation to the family budget')
    expect(sentence(family('INVITE_REVOKED', 'Anna', undefined))).toBe('Anna cancelled an invitation to the family budget')
    expect(sentence(row('SPLIT_RULE_RESET', { actor: { kind: 'SYSTEM', memberId: null, name: null } })))
      .toBe('The family budget’s split was reset to equal shares')
  })

  it('falls back to “Former member” and “Someone”', () => {
    expect(sentence(family('MEMBER_LEFT', 'Anna', null))).toBe(`${FORMER_MEMBER} left the family budget`)
    expect(sentence(row('MEMBER_JOINED'))).toBe(`${FORMER_MEMBER} joined the family budget`)
    expect(sentence(family('LEDGER_SETTINGS_CHANGED', null, undefined))).toBe(`${SOMEONE} changed the family budget’s settings`)
  })

  it('never shows the name of a kind it doesn’t know', () => {
    expect(sentence(row('SOMETHING_NEW'))).toBe(OTHER_ACTIVITY)
    expect(OTHER_ACTIVITY).toBe('Other activity')
  })
})
