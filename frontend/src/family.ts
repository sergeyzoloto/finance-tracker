import type { FamilyMember, MemberRole, MemberStatus, ViolationDetail } from './api'
import { basisPointsToPercent } from './basisPoints'

// The family budget's screens without React. The API's messages say "family budget" and show shares as percentages,
// as the screens do, so they are shown as they come.

export const ROLE_LABELS: Record<MemberRole, string> = { OWNER: 'Owner', MEMBER: 'Member' }
export const STATUS_LABELS: Record<MemberStatus, string> = { ACTIVE: 'Active', LEFT: 'Left', FORMER: 'Former member' }

/** The violations of a 422 by the member each names (`violationDetails`), and the others. */
export function violationsByMember(details: ViolationDetail[]) {
  const byMember = new Map<number, string[]>()
  const other: string[] = []
  for (const { memberId, message } of details) {
    if (memberId !== null) byMember.set(memberId, [...(byMember.get(memberId) ?? []), message])
    else other.push(message)
  }
  return { byMember, other }
}

/** The members a custom split covers: the ACTIVE ones, with or without an account. */
export const splitMembers = (members: FamilyMember[]) => members.filter((m) => m.status === 'ACTIVE')

/** A member's share as a percentage, or null under an equal split. */
export const shareText = (member: FamilyMember) => (member.share === null ? null : `${basisPointsToPercent(member.share)} %`)
