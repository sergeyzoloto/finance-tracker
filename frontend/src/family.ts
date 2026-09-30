import type {
  FamilyBalance, FamilyBalances, FamilyChange, FamilyFieldChange, FamilyMember, MemberRole, MemberStatus, SplitMethod,
  ViolationDetail,
} from './api'
import { formatDate } from './api'
import { basisPointsToPercent } from './basisPoints'
import { fromMinor, toMinor } from './minorUnits'
import { abs, formatMoney, signOf } from './money'

// The family budget's screens without React. The API's messages say "family budget" and show shares as percentages,
// as the screens do, so they are shown as they come.

export const ROLE_LABELS: Record<MemberRole, string> = { OWNER: 'Owner', MEMBER: 'Member' }
export const STATUS_LABELS: Record<MemberStatus, string> = { ACTIVE: 'Active', LEFT: 'Left', FORMER: 'Former member' }
export const SPLIT_LABELS: Record<SplitMethod, string> = {
  EQUAL: 'equal shares', PERCENT: 'percentages', AMOUNT: 'amounts', ONE_MEMBER: 'one member',
}

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

/** An expense as the screens name it: its category and date, "Groceries, Sep 12, 2026". */
export const expenseName = (category: string | null, date: string) => `${category ?? 'An expense'}, ${formatDate(date)}`

// Balances (D1). A member's balance is what they owe the family budget: positive if they owe, negative if they are
// owed. The balances sum to zero, so what some owe, the others are owed.

/** A member's balance in words without the member: "owes €40.00", "is owed €12.50", "is settled". */
export function balancePhrase(member: FamilyBalance, currency: string) {
  const sign = signOf(member.balance)
  const amount = formatMoney(abs(member.balance), currency)
  return sign === 0 ? 'is settled' : sign > 0 ? `owes ${amount}` : `is owed ${amount}`
}

/** A member's balance in words: "Sam owes €40.00", "You are owed €12.50", "Kid is settled". */
export function balanceWords(member: FamilyBalance, currency: string) {
  const amount = formatMoney(abs(member.balance), currency)
  const sign = signOf(member.balance)
  const who = member.you ? 'You' : member.displayName
  if (sign === 0) return `${who} ${member.you ? 'are' : 'is'} settled`
  if (sign > 0) return `${who} ${member.you ? 'owe' : 'owes'} ${amount}`
  return `${who} ${member.you ? 'are' : 'is'} owed ${amount}`
}

/** One member paying another settles part of the balances. */
export interface Debt { from: FamilyBalance; to: FamilyBalance; amount: string }

/**
 * Who owes whom: the payments that would settle every balance, as few as the rule below finds. The member who owes
 * most pays the one who is owed most, as much as either can, until nobody owes anything; ties go by join order. With
 * two members that is the one payment there is. It only reads the balances; settling is a later stage (F4c).
 */
export function whoOwesWhom({ currency, members }: FamilyBalances): Debt[] {
  const open = members.map((member, order) => ({ member, order, rest: toMinor(member.balance, currency) ?? 0n }))
  // The largest first, then by join order.
  const largest = (a: bigint, b: bigint) => (a > b ? -1 : a < b ? 1 : 0)
  const debts: Debt[] = []
  for (;;) {
    const debtor = open.filter((o) => o.rest > 0n).sort((a, b) => largest(a.rest, b.rest) || a.order - b.order)[0]
    const creditor = open.filter((o) => o.rest < 0n).sort((a, b) => largest(-a.rest, -b.rest) || a.order - b.order)[0]
    if (!debtor || !creditor) return debts
    const amount = debtor.rest < -creditor.rest ? debtor.rest : -creditor.rest
    debts.push({ from: debtor.member, to: creditor.member, amount: fromMinor(amount, currency) })
    debtor.rest -= amount
    creditor.rest += amount
  }
}

/** A debt in words, from the reader's side: "Sam owes you €40.00", "You owe Sam €12.50", "Kid owes Sam €5.00". */
export function debtSentence({ from, to, amount }: Debt, currency: string) {
  const money = formatMoney(amount, currency)
  if (from.you) return `You owe ${to.displayName} ${money}`
  return `${from.displayName} owes ${to.you ? 'you' : to.displayName} ${money}`
}

/** The reader's balance in words, member by member: "Sam owes you €40.00", or "You are settled". */
export function yourBalance(balances: FamilyBalances): string[] {
  const yours = whoOwesWhom(balances).filter((d) => d.from.you || d.to.you)
  return yours.length > 0 ? yours.map((d) => debtSentence(d, balances.currency)) : ['You are settled']
}

// The change journal (D-16) as sentences. Values come as text: amounts in the base currency, dates, and the names of
// members and categories as they are now, so a member who deleted their data reads "Former member".

/** A journal entry in words, with a line per kind of change where one sentence can't hold them all. */
export interface JournalLine { text: string; details: string[] }

const quoted = (text: string | null) => (text === null ? 'none' : `“${text}”`)

/** A split's changes: "equal shares → one member; Anna €6.66 → no share, Kid €6.66 → €20.00". */
function splitChange(changes: FamilyFieldChange[], currency: string) {
  const share = (value: string | null) => (value === null ? 'no share' : formatMoney(value, currency))
  const method = changes.find((c) => c.field === 'splitMethod')
  const shares = changes.filter((c) => c.field === 'share')
    .map((c) => `${c.member?.displayName ?? 'Former member'} ${share(c.old)} → ${share(c.new)}`)
  const methods = method ? `${label(method.old)} → ${label(method.new)}` : ''
  return [methods, shares.join(', ')].filter((part) => part !== '').join('; ')
}

const label = (method: string | null) => (method === null ? 'none' : SPLIT_LABELS[method as SplitMethod] ?? method)

/** A created record's split: "equal shares: Anna €10.00, Sam €10.00". */
function createdSplit(changes: FamilyFieldChange[], currency: string) {
  const method = changes.find((c) => c.field === 'splitMethod')?.new ?? null
  const shares = changes.filter((c) => c.field === 'share' && c.new !== null)
    .map((c) => `${c.member?.displayName ?? 'Former member'} ${formatMoney(c.new!, currency)}`)
  return `${label(method)}: ${shares.join(', ')}`
}

/** One entry of the change journal in words, "Alex changed the split of Groceries, Sep 12, 2026: …". */
export function journalLine(change: FamilyChange, currency: string): JournalLine {
  if (change.action === 'SPLIT_RULE_RESET') {
    return {
      text: `The split rule went back to equal shares when a member left: ${change.about?.displayName ?? 'Former member'}.`,
      details: [],
    }
  }
  const who = change.author?.displayName ?? 'Former member'
  const record = change.record
  const name = record ? expenseName(record.category, record.date) : 'an expense'
  const field = (f: string) => change.changes.find((c) => c.field === f)
  switch (change.action) {
    case 'CREATE': {
      const amount = field('amount')?.new ?? record?.amount
      const payer = field('payer')?.new
      const comment = field('comment')?.new ?? null
      return {
        text: `${who} added ${name}: ${amount ? formatMoney(amount, currency) : ''}${payer ? `, paid by ${payer}` : ''}.`,
        details: [`Split: ${createdSplit(change.changes, currency)}`, ...(comment ? [`Comment: ${quoted(comment)}`] : [])],
      }
    }
    case 'DELETE':
      return { text: `${who} deleted ${name}${record ? `, ${formatMoney(record.amount, currency)}` : ''}.`, details: [] }
    default: {
      const parts: [string, string][] = []
      const category = field('category')
      if (category) parts.push(['the category', `${category.old ?? 'none'} → ${category.new ?? 'none'}`])
      if (change.changes.some((c) => c.field === 'splitMethod' || c.field === 'share')) {
        parts.push(['the split', splitChange(change.changes, currency)])
      }
      const comment = field('comment')
      if (comment) parts.push(['the comment', `${quoted(comment.old)} → ${quoted(comment.new)}`])
      if (parts.length === 0) return { text: `${who} changed ${name}.`, details: [] }
      if (parts.length === 1) return { text: `${who} changed ${parts[0][0]} of ${name}: ${parts[0][1]}.`, details: [] }
      const kinds = parts.map(([kind]) => kind)
      return {
        text: `${who} changed ${kinds.slice(0, -1).join(', ')} and ${kinds.at(-1)} of ${name}:`,
        details: parts.map(([kind, detail]) => `${kind.charAt(4).toUpperCase()}${kind.slice(5)}: ${detail}`),
      }
    }
  }
}
