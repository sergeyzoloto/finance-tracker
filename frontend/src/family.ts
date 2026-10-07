import type {
  CurrencyAmount, CurrencyBalances, FamilyBalance, FamilyBalances, FamilyChange, FamilyFieldChange, FamilyMember, FamilyMembershipImpact, FamilyRecord,
  FamilyRecordType, MemberRef, MemberRole, MemberStatus, SplitMethod, SplitRule, ViolationDetail,
} from './api'
import { formatDate } from './api'
import { basisPointsToPercent } from './basisPoints'
import { fromMinor, toMinor } from './minorUnits'
import { abs, formatMoney, negate, signOf } from './money'

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

/** What the screens call a record of each type. */
export const RECORD_NOUNS: Record<FamilyRecordType, string> = { EXPENSE: 'expense', INCOME: 'income', SETTLEMENT: 'settlement' }

/**
 * A record as a sentence names it: "Groceries, Sep 12, 2026" for an expense, "the refund Groceries, Sep 12, 2026" for a
 * refund (D-79), "the income Salary, Sep 12, 2026", "the settlement of Sep 12, 2026".
 */
export function recordName(record: { type?: FamilyRecordType; category: string | null; date: string; refund?: boolean }) {
  switch (record.type) {
    case 'SETTLEMENT': return `the settlement of ${formatDate(record.date)}`
    case 'INCOME': return `the income ${expenseName(record.category, record.date)}`
    default: return `${record.refund ? 'the refund ' : ''}${expenseName(record.category, record.date)}`
  }
}

/**
 * An amount of a record as the screens show it: a refund's, an expense with a minus (D-79), carries the minus. The
 * api's amounts of a refund are what was refunded, above 0, and the shares likewise.
 */
export const shownAmount = (record: { refund?: boolean }, amount: string) => (record.refund ? negate(amount) : amount)

/** A member as the reader reads them: "you", or their name. */
export const memberName = (member: MemberRef, me: number) => (member.memberId === me ? 'you' : member.displayName)

const capital = (text: string) => text.charAt(0).toUpperCase() + text.slice(1)

/** A settlement from the reader's side: "Sam paid you €36.20", "You paid Sam €36.20", "Kid paid Sam €5.00". */
export function settlementSentence(record: FamilyRecord, me: number) {
  const to = record.payee ? memberName(record.payee, me) : 'someone'
  return `${capital(memberName(record.payer, me))} paid ${to} ${formatMoney(record.amount, record.currency)}`
}

/** A record's title in the list: its category, or "Settlement". */
export const recordTitle = (record: FamilyRecord) =>
  record.type === 'SETTLEMENT' ? 'Settlement'
    : `${record.category?.name ?? RECORD_NOUNS[record.type]}${record.refund ? ' (refund)' : ''}`

/** Who paid or received it, from the reader's side: "Paid by you", "Received by Sam", or a settlement's sentence. */
export function recordWho(record: FamilyRecord, me: number) {
  if (record.type === 'SETTLEMENT') return settlementSentence(record, me)
  return `${record.type === 'INCOME' || record.refund ? 'Received' : 'Paid'} by ${memberName(record.payer, me)}`
}

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

/** One member paying another settles part of the balances, in one currency (D-46). */
export interface Debt { from: FamilyBalance; to: FamilyBalance; amount: string; currency: string }

/**
 * Who owes whom: the payments that would settle every balance, as few as the rule below finds. The member who owes
 * most pays the one who is owed most, as much as either can, until nobody owes anything; ties go by join order. With
 * two members that is the one payment there is. It only reads the balances; a settlement records a payment (F4d).
 */
export function whoOwesWhom({ currency, members }: CurrencyBalances): Debt[] {
  const open = members.map((member, order) => ({ member, order, rest: toMinor(member.balance, currency) ?? 0n }))
  // The largest first, then by join order.
  const largest = (a: bigint, b: bigint) => (a > b ? -1 : a < b ? 1 : 0)
  const debts: Debt[] = []
  for (;;) {
    const debtor = open.filter((o) => o.rest > 0n).sort((a, b) => largest(a.rest, b.rest) || a.order - b.order)[0]
    const creditor = open.filter((o) => o.rest < 0n).sort((a, b) => largest(-a.rest, -b.rest) || a.order - b.order)[0]
    if (!debtor || !creditor) return debts
    const amount = debtor.rest < -creditor.rest ? debtor.rest : -creditor.rest
    debts.push({ from: debtor.member, to: creditor.member, amount: fromMinor(amount, currency), currency })
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

/** Who owes whom, with the reader's own debts first (to settle up), the others after them in their order. */
export const settleUpOrder = (debts: Debt[]) =>
  [...debts.filter((d) => d.from.you || d.to.you), ...debts.filter((d) => !d.from.you && !d.to.you)]

/**
 * Whether the reader may record a settlement between these members: they pay or receive it, or they are an owner and
 * neither side has an account (D-24).
 */
export const maySettle = (payer: { memberId: number; hasAccount: boolean }, payee: { memberId: number; hasAccount: boolean },
  me: number, owner: boolean) =>
  payer.memberId === me || payee.memberId === me || (owner && !payer.hasAccount && !payee.hasAccount)

/**
 * The reader's balance in words, member by member and currency by currency (D-45): "Sam owes you €40.00", "You owe Sam
 * $12.00", or "You are settled".
 */
export function yourBalance(balances: FamilyBalances): string[] {
  const yours = balances.byCurrency.flatMap((inCurrency) => whoOwesWhom(inCurrency))
    .filter((d) => d.from.you || d.to.you)
  return yours.length > 0 ? yours.map((d) => debtSentence(d, d.currency)) : ['You are settled']
}

/** Who owes whom in every currency, the reader's own debts first in each (D-46: each settles in its own currency). */
export const allDebts = (balances: FamilyBalances): Debt[] =>
  balances.byCurrency.flatMap((inCurrency) => settleUpOrder(whoOwesWhom(inCurrency)))

/**
 * Balances in several currencies in words, for the confirmations: "you owe €30.00 and are owed $40.00", or "you are
 * settled" when every one is 0 (D-45).
 */
export function balancesSentence(balances: CurrencyAmount[], who: string | null): string {
  const open = balances.filter((b) => signOf(b.amount) !== 0)
  if (open.length === 0) return balanceSentence('0', 'EUR', who)
  return open.map((b, i) => {
    const text = balanceSentence(b.amount, b.currency, who)
    return i === 0 ? text : text.replace(/^(you|.+?) (?=(owe|is|are))/, '').replace(/^owes /, 'owes ')
  }).join(' and ')
}

// The change journal (D-16) as sentences. Values come as text: amounts in the base currency, dates, and the names of
// members and categories as they are now, so a member who deleted their data reads "Former member".

/** A journal entry in words, with a line per kind of change where one sentence can't hold them all. */
export interface JournalLine { text: string; details: string[] }

const quoted = (text: string | null) => (text === null ? 'none' : `“${text}”`)

/** A split's changes: "equal shares → one member; Anna €6.66 → no share, Kid €6.66 → €20.00". */
function splitChange(changes: FamilyFieldChange[], currency: string, refund: boolean) {
  // Each value in the currency its row stored (D-93): the old shares of a change of currency in the old one.
  const share = (value: string | null, in_: string | null | undefined) =>
    (value === null ? 'no share' : formatMoney(refund ? negate(value) : value, in_ ?? currency))
  const method = changes.find((c) => c.field === 'splitMethod')
  const shares = changes.filter((c) => c.field === 'share')
    .map((c) => `${c.member?.displayName ?? 'Former member'} ${share(c.old, c.oldCurrency)} → ${share(c.new, c.newCurrency)}`)
  const methods = method ? `${label(method.old)} → ${label(method.new)}` : ''
  return [methods, shares.join(', ')].filter((part) => part !== '').join('; ')
}

const label = (method: string | null) => (method === null ? 'none' : SPLIT_LABELS[method as SplitMethod] ?? method)

/** A created record's split: "equal shares: Anna €10.00, Sam €10.00". */
function createdSplit(changes: FamilyFieldChange[], currency: string, refund: boolean) {
  const method = changes.find((c) => c.field === 'splitMethod')?.new ?? null
  const shares = changes.filter((c) => c.field === 'share' && c.new !== null)
    .map((c) => `${c.member?.displayName ?? 'Former member'} ${formatMoney(refund ? negate(c.new!) : c.new!, c.newCurrency ?? currency)}`)
  return `${label(method)}: ${shares.join(', ')}`
}

/** One entry of the change journal in words, "Alex changed the split of Groceries, Sep 12, 2026: …". */
/** An original amount as the journal stores it, "9000.00 RUB", formatted: "RUB 9,000.00" in the reader's locale. */
function originalMoney(value: string | null | undefined): string {
  if (!value) return 'none'
  const [amount, code] = value.split(' ')
  return code ? formatMoney(amount, code) : value
}

/**
 * @param currency the family's main currency, for a record created in it before its journal named a currency (D-45):
 *        each record's amounts are in its own currency
 */
export function journalLine(change: FamilyChange, currency: string): JournalLine {
  if (change.action === 'SPLIT_RULE_RESET') {
    return {
      text: `The split rule went back to equal shares when a member left: ${change.about?.displayName ?? 'Former member'}.`,
      details: [],
    }
  }
  const who = change.author?.displayName ?? 'Former member'
  const record = change.record
  const type = record?.type ?? 'EXPENSE'
  const name = record ? recordName(record) : 'an expense'
  const field = (f: string) => change.changes.find((c) => c.field === f)
  // A refund (D-79): its amounts are shown with the minus.
  const refund = record?.refund === true || field('refund') !== undefined
  const money = (value: string, in_: string) => formatMoney(refund ? negate(value) : value, in_)
  // The record's currency then (D-93): the row's own. Rows without one (an api before F8d) name a change of it, or a
  // creation in one other than the main currency.
  const currencyChange = field('currency')
  const then = change.currency ?? currencyChange?.new ?? (change.action === 'CREATE' ? currency : record?.currency ?? currency)
  switch (change.action) {
    case 'CREATE': {
      const amount = field('amount')?.new ?? record?.amount
      const payer = field('payer')?.new
      const comment = field('comment')?.new ?? null
      const commented = comment ? [`Comment: ${quoted(comment)}`] : []
      if (type === 'SETTLEMENT') {
        const payee = field('payee')?.new
        const original = field('originalAmount')?.new
        return {
          text: `${who} recorded ${name}: ${payer ?? 'someone'} paid ${payee ?? 'someone'} ${original ? `${originalMoney(original)} → ` : ''}${amount ? formatMoney(amount, then) : ''}.`,
          details: commented,
        }
      }
      const how = type === 'INCOME' ? 'received by' : 'paid by'
      const original = field('originalAmount')?.new
      const shown = `${original ? `${originalMoney(original)} → ` : ''}${amount ? money(amount, then) : ''}`
      return {
        text: `${who} added ${name}: ${shown}${payer ? `, ${refund ? 'received by' : how} ${payer}` : ''}.`,
        details: [`Split: ${createdSplit(change.changes, then, refund)}`, ...commented],
      }
    }
    case 'DELETE':
      return { text: `${who} deleted ${name}${record ? `, ${money(record.amount, record.currency ?? currency)}` : ''}.`, details: [] }
    default: {
      const parts: [string, string][] = []
      // The payment's fields (F4c), then the family's.
      const date = field('date')
      if (date) parts.push(['the date', `${date.old ? formatDate(date.old) : 'none'} → ${date.new ? formatDate(date.new) : 'none'}`])
      const amount = field('amount')
      const original = field('originalAmount')
      if (original) {
        parts.push(['the amount paid', `${originalMoney(original.old)} → ${originalMoney(original.new)}`])
      }
      if (amount) {
        const before = amount.oldCurrency ?? currencyChange?.old ?? then
        parts.push([original ? `the amount in ${then}` : 'the amount',
          `${amount.old ? money(amount.old, before) : 'none'} → ${amount.new ? money(amount.new, amount.newCurrency ?? then) : 'none'}`])
      } else if (currencyChange) {
        parts.push(['the currency', `${currencyChange.old ?? 'none'} → ${currencyChange.new ?? 'none'}`])
      }
      const payer = field('payer')
      if (payer) parts.push([type === 'INCOME' ? 'the receiver' : 'the payer', `${payer.old ?? 'none'} → ${payer.new ?? 'none'}`])
      const category = field('category')
      if (category) parts.push(['the category', `${category.old ?? 'none'} → ${category.new ?? 'none'}`])
      if (change.changes.some((c) => c.field === 'splitMethod' || c.field === 'share')) {
        parts.push(['the split', splitChange(change.changes, then, refund)])
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

// Leaving, removal and "Delete all my data" (F6a; D-19, D-20). A member who leaves or is removed becomes one who left;
// nothing is posted, and what was posted stays in their personal budget as their own.

/** "you owe €40.00", "Sam is owed €12.50", "you are settled": a balance in words, for the confirmations. */
export function balanceSentence(balance: string, currency: string, who: string | null) {
  const sign = signOf(balance)
  const amount = formatMoney(abs(balance), currency)
  if (who === null) return sign === 0 ? 'you are settled' : sign > 0 ? `you owe ${amount}` : `you are owed ${amount}`
  return sign === 0 ? `${who} is settled` : sign > 0 ? `${who} owes ${amount}` : `${who} is owed ${amount}`
}

/**
 * Whether the reader is the family budget's last owner while another member with an account remains: then they make
 * one an owner before they leave (D-19), and the server answers 409 `LAST_OWNER` otherwise.
 */
export function lastOwner(members: FamilyMember[], me: number) {
  const self = members.find((m) => m.id === me)
  return self?.role === 'OWNER'
    && !members.some((m) => m.id !== me && m.role === 'OWNER')
    && members.some((m) => m.id !== me && m.status === 'ACTIVE' && m.hasAccount)
}

/**
 * What leaving, or an owner's removal of a member, does, for its confirmation (D-19): the member's balance, what stays
 * in their personal budget, the records that freeze, the split rule back to equal shares, a budget that closes.
 *
 * @param balances the member's balance in each currency of the budget (D-45), if they have loaded
 */
export function departureNotes({ member, me, balances, members, splitRule, budget }: {
  member: FamilyMember
  me: number
  balances: CurrencyAmount[] | undefined
  members: FamilyMember[]
  splitRule: SplitRule
  budget: string
}): string[] {
  const self = member.id === me
  const last = closesBudget(member, members)
  const notes: string[] = []
  const name = member.displayName
  if (balances !== undefined) {
    const settled = balances.every((b) => signOf(b.amount) === 0)
    notes.push(`${sentenceStart(balancesSentence(balances, self ? null : name))}.${settled ? ''
      : self ? ` That stays in your personal budget, on “Debt to family budget: ${budget}”, which becomes an account of `
        + 'yours; after you leave, you and the others each record a settlement in your own budgets.'
        : member.hasAccount ? ' That stays in their personal budget, on an account of theirs.' : ''}`)
  }
  if (self) {
    notes.push('What this family budget added to your personal budget stays there as your own entries, which you can '
      + 'change or delete; the family categories they use become personal categories of yours.')
    if (!last) {
      notes.push('You won’t see this family budget any more. The others keep seeing your name in its records, which '
        + 'can no longer be changed where they involve you.')
    }
  } else if (member.hasAccount) {
    notes.push(`What this family budget added to ${name}’s personal budget stays there as their own entries; the family `
      + 'categories they use become personal categories of theirs.')
    notes.push(`${name} won’t see this family budget any more, and the records that involve them can no longer be `
      + 'changed.')
  } else {
    notes.push(`If a record names ${name}, they stay in it as a member who left, and those records can no longer be `
      + 'changed; otherwise they are removed altogether. Invites to take their place stop working.')
  }
  if (splitRule === 'CUSTOM' && (member.share ?? 0) > 0) notes.push('The split rule goes back to equal shares.')
  if (last) {
    notes.push(`Nobody else here has an account, so the family budget “${budget}” and its records will be deleted: its `
      + 'members without an account, categories, journal and invites go with it.')
  }
  return notes
}

/** Whether the member is the last ACTIVE one with an account, whose going deletes the family budget (D-36). */
export const closesBudget = (member: FamilyMember, members: FamilyMember[]) => member.hasAccount
  && !members.some((m) => m.id !== member.id && m.status === 'ACTIVE' && m.hasAccount)

/** What "Delete all my data" does to one of the user's family budgets, in words (D-20). */
export function deletionNotes(impact: FamilyMembershipImpact): string[] {
  const notes = [`${sentenceStart(balancesSentence(impact.balances, null))}.`]
  if (impact.outcome === 'DELETED') {
    notes.push('Nobody else in it has an account, so it is deleted with its records.')
    return notes
  }
  notes.push('Its records stay, with your name replaced by “Former member” and your comments erased; those that involve '
    + 'you can no longer be changed.')
  if (impact.outcome === 'OWNERSHIP_PASSES') notes.push(`${impact.newOwner} becomes its owner.`)
  if (impact.splitRuleReset) notes.push('Its split rule goes back to equal shares.')
  if (impact.pendingInvites > 0) {
    notes.push(impact.pendingInvites === 1 ? 'Your invite that wasn’t used yet stops working.'
      : `Your ${impact.pendingInvites} invites that weren’t used yet stop working.`)
  }
  return notes
}

function sentenceStart(text: string) {
  return text.charAt(0).toUpperCase() + text.slice(1)
}
