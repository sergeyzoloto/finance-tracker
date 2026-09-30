import type { FamilyMember, MemberRole, MemberStatus } from './api'
import { basisPointsToPercent } from './basisPoints'

// The family budget's screens without React. The API says "family ledger" and counts shares in basis points; the
// screens say "family budget" and show percentages.

export const ROLE_LABELS: Record<MemberRole, string> = { OWNER: 'Owner', MEMBER: 'Member' }
export const STATUS_LABELS: Record<MemberStatus, string> = { ACTIVE: 'Active', LEFT: 'Left', FORMER: 'Former member' }

/**
 * A message of the API in the screens' words: "family budget" for "family ledger", and percentages for basis
 * points. "Kid has a share of 3333 basis points …" → "Kid has a share of 33.33 % …".
 */
export function familyMessage(message: string): string {
  return message
    .replace(/\b([Ff])amily ledger/g, (_, f: string) => `${f}amily budget`)
    .replace(/\b(\d+) basis points/g, (_, bp: string) => `${basisPointsToPercent(Number(bp))} %`)
    .replace(/\bnot 10000\b/g, 'not 100.00 %')
}

/**
 * The violations of a 422 by the member they name ("Kid (member 12) has no share", "member 12 is not …"), and the
 * others, each in the screens' words.
 */
export function violationsByMember(violations: string[]) {
  const byMember = new Map<number, string[]>()
  const other: string[] = []
  for (const violation of violations) {
    const member = /\bmember (\d+)\b/.exec(violation)
    const text = familyMessage(violation.replace(/ \(member \d+\)/, ''))
    if (member) byMember.set(Number(member[1]), [...(byMember.get(Number(member[1])) ?? []), text])
    else other.push(text)
  }
  return { byMember, other }
}

/** The members a custom split covers: the ACTIVE ones, with or without an account. */
export const splitMembers = (members: FamilyMember[]) => members.filter((m) => m.status === 'ACTIVE')

/** A member's share as a percentage, or null under an equal split. */
export const shareText = (member: FamilyMember) => (member.share === null ? null : `${basisPointsToPercent(member.share)} %`)
