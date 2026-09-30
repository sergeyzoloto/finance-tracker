import { ApiError, type Account, type FamilyLedger, type FamilyMember, type FamilyRecord, type RecordSplit } from './api'
import { basisPointsToPercent, equalShares, percentToBasisPoints, WHOLE } from './basisPoints'
import { fromMinor, parseMinor, toMinor } from './minorUnits'
import { equalSplit, oneMemberSplit, percentSplit, type SplitShare } from './shareSplit'

// A family expense's or income's form without React: who shares it, the split as typed and its preview, the request,
// and where the server's objections go. The backend's rules are FamilyRecordService's; the server's answer is what
// counts.

/**
 * How the form splits: a request's method, or KEEP, a stored record's equal shares as they are (the F4c review): the
 * server splits them again among the record's own members, whatever the budget's rule is now.
 */
export type SplitMode = RecordSplit['method'] | 'KEEP'

/** A split as the form holds it: the mode, and what is typed per member id. */
export interface SplitForm {
  mode: SplitMode
  percents: Record<number, string>
  amounts: Record<number, string>
  /** For ONE_MEMBER, the member's id, or '' until one is chosen. */
  member: string
  /**
   * For a stored record split equally: the members it is split among, and its date. KEEP shares it among them, less a
   * member with an account who joined after a new date, plus one who joined after the stored date and by the new one,
   * as FamilyRecordService.resplit does.
   */
  keep?: { among: number[]; since: string }
}

export const newSplit = (): SplitForm => ({ mode: 'RULE', percents: {}, amounts: {}, member: '' })

/**
 * Who shares an expense of the date: the ACTIVE members, a member without an account for any date, and one with an
 * account from their join date on (D-7, D-18), in join order.
 */
export const sharers = (members: FamilyMember[], date: string) =>
  members.filter((m) => m.status === 'ACTIVE' && (!m.hasAccount || m.joinDate <= date))

/** A member's row in the split: their share in the minor unit, once the amount allows one, and what is wrong. */
export interface SplitRow { member: FamilyMember; amount: bigint | null; problem?: string }

export interface SplitPreview {
  rows: SplitRow[]
  /** PERCENT: the typed percentages' sum in basis points, null while one isn't a percentage. */
  percentTotal: number | null
  /** AMOUNT: the typed amounts' sum in the minor unit, null while one isn't an amount. */
  amountTotal: bigint | null
  /** Why it can't be saved yet; empty when it can. */
  problems: string[]
}

export interface SplitContext {
  ledger: FamilyLedger
  members: FamilyMember[]
  date: string
  /** The record's amount in the minor unit, or undefined while it isn't a valid one. */
  amount: bigint | undefined
  /** Who paid an expense or received an income: D-12's tie goes to them. */
  payerId: number | null
  /** What the messages call the record: "expense" (the default) or "income". */
  noun?: string
}

/**
 * The rows a mode splits among. The rule under custom percentages: the members with a share in it. A stored equal split
 * (KEEP): the record's own members as of the date.
 */
export function splitRows(form: SplitForm, { ledger, members, date }: SplitContext) {
  if (form.mode === 'KEEP' && form.keep) {
    const { among, since } = form.keep
    return sharers(members, date).filter((m) => among.includes(m.id) || (m.hasAccount && m.joinDate > since))
  }
  return form.mode === 'RULE' && ledger.splitRule === 'CUSTOM'
    ? members.filter((m) => m.status === 'ACTIVE' && m.share !== null)
    : sharers(members, date)
}

/** The percentages typed, with equal ones for the rows that have none yet: 33.34, 33.33 and 33.33 for three. */
export function percentsOf(form: SplitForm, rows: FamilyMember[]): Record<number, string> {
  if (rows.every((m) => m.id in form.percents)) return form.percents
  const equal = equalShares(rows.length)
  return { ...Object.fromEntries(rows.map((m, i) => [m.id, basisPointsToPercent(equal[i])])), ...form.percents }
}

/** Every member's share as the form stands, as the server would split it (D-12), and what keeps it from being saved. */
export function previewSplit(form: SplitForm, context: SplitContext, currency: string): SplitPreview {
  const { amount, payerId, date } = context
  const members = splitRows(form, context)
  const problems: string[] = []
  let shares: SplitShare[] | null = null
  let percentTotal: number | null = null
  let amountTotal: bigint | null = null
  const rowProblems = new Map<number, string>()

  if (members.length === 0) problems.push(`Nobody shares ${context.noun === 'income' ? 'an income' : 'an expense'} of this date.`)
  switch (form.mode) {
    case 'KEEP':
      if (amount !== undefined && members.length > 0) shares = equalSplit(amount, members.map((m) => m.id), payerId)
      break
    case 'RULE':
      if (context.ledger.splitRule === 'EQUAL') {
        if (amount !== undefined && members.length > 0) shares = equalSplit(amount, members.map((m) => m.id), payerId)
      } else {
        for (const m of members) {
          if ((m.share ?? 0) > 0 && m.hasAccount && m.joinDate > date) {
            rowProblems.set(m.id, `${m.displayName} joined after this date.`)
          }
        }
        if (rowProblems.size > 0) {
          problems.push(`Someone in the split rule joined after this date; split this ${context.noun ?? 'expense'} another way.`)
        }
        if (amount !== undefined && members.length > 0) {
          shares = percentSplit(amount, members.map((m) => ({ memberId: m.id, basisPoints: m.share ?? 0 })), payerId)
        }
      }
      break
    case 'PERCENT': {
      const values = percentsOf(form, members)
      let total = 0
      for (const m of members) {
        const bp = percentToBasisPoints(values[m.id] ?? '')
        if (bp === null) rowProblems.set(m.id, 'A percentage from 0 to 100, with at most two decimals.')
        else total += bp
      }
      percentTotal = rowProblems.size > 0 ? null : total
      if (percentTotal !== WHOLE) problems.push('The percentages must add up to exactly 100.00 %.')
      else if (amount !== undefined) {
        shares = percentSplit(amount, members.map((m) => ({ memberId: m.id, basisPoints: percentToBasisPoints(values[m.id])! })), payerId)
      }
      break
    }
    case 'AMOUNT': {
      let total = 0n
      const typed = new Map<number, bigint>()
      for (const m of members) {
        const parsed = parseMinor(form.amounts[m.id] ?? '0', currency, { zero: true })
        if ('problem' in parsed) rowProblems.set(m.id, parsed.problem)
        else { typed.set(m.id, parsed.minor); total += parsed.minor }
      }
      amountTotal = rowProblems.size > 0 ? null : total
      if (amountTotal === null) problems.push('Every amount must be 0 or more.')
      else if (amount !== undefined && amountTotal !== amount) {
        problems.push(`The amounts add up to ${fromMinor(amountTotal, currency)}, not to ${fromMinor(amount, currency)}.`)
      }
      shares = members.map((m) => ({ memberId: m.id, amount: typed.get(m.id) ?? 0n, basisPoints: null }))
      break
    }
    case 'ONE_MEMBER': {
      const chosen = members.find((m) => String(m.id) === form.member)
      if (!chosen) problems.push('Choose the member the whole amount is on.')
      else if (amount !== undefined) shares = oneMemberSplit(amount, chosen.id)
      break
    }
  }
  const byMember = new Map((shares ?? []).map((s) => [s.memberId, s.amount]))
  const rows = members.map((member) => ({
    member,
    amount: shares === null ? null : byMember.get(member.id) ?? 0n,
    problem: rowProblems.get(member.id),
  }))
  return { rows, percentTotal, amountTotal, problems }
}

/** The split as the API takes it; null for KEEP, which a change leaves out, so that the server keeps the stored split. */
export function splitRequest(form: SplitForm, preview: SplitPreview, currency: string): RecordSplit | null {
  switch (form.mode) {
    case 'KEEP':
      return null
    case 'RULE':
      return { method: 'RULE' }
    case 'PERCENT': {
      const values = percentsOf(form, preview.rows.map((r) => r.member))
      return {
        method: 'PERCENT',
        shares: preview.rows.map((r) => ({ memberId: r.member.id, basisPoints: percentToBasisPoints(values[r.member.id]) ?? 0 })),
      }
    }
    case 'AMOUNT':
      return { method: 'AMOUNT', shares: preview.rows.map((r) => ({ memberId: r.member.id, amount: fromMinor(r.amount ?? 0n, currency) })) }
    case 'ONE_MEMBER':
      return { method: 'ONE_MEMBER', memberId: Number(form.member) }
  }
}

/**
 * A stored record's split as the form opens it: its own percentages or amounts, one member, or its equal shares as
 * they are (KEEP), whatever the budget's rule is now (after the F4c review). Members who share its date but have no
 * share get 0.
 */
export function formFromRecord(record: FamilyRecord, members: FamilyMember[]): SplitForm {
  const shares = new Map(record.shares.map((s) => [s.member.memberId, s]))
  const rows = sharers(members, record.date)
  const form = newSplit()
  if (record.splitMethod === 'ONE_MEMBER') {
    const on = record.shares.find((s) => s.amount === record.amount) ?? record.shares[0]
    return { ...form, mode: 'ONE_MEMBER', member: on ? String(on.member.memberId) : '' }
  }
  if (record.splitMethod === 'EQUAL') {
    return { ...form, mode: 'KEEP', keep: { among: record.shares.map((s) => s.member.memberId), since: record.date } }
  }
  if (record.splitMethod === 'PERCENT' && record.shares.every((s) => s.basisPoints !== null)) {
    return {
      ...form, mode: 'PERCENT',
      percents: Object.fromEntries(rows.map((m) => [m.id, basisPointsToPercent(shares.get(m.id)?.basisPoints ?? 0)])),
    }
  }
  return {
    ...form, mode: 'AMOUNT',
    amounts: Object.fromEntries(rows.map((m) => {
      const stored = shares.get(m.id)
      return [m.id, fromMinor(stored ? toMinor(stored.amount, record.currency) ?? 0n : 0n, record.currency)]
    })),
  }
}

/**
 * The accounts a payer may name for paying a family expense, as the backend accepts them: their own assets and
 * liabilities, never a system account (a family budget's debt account is one), nor one kept per counterparty, nor an
 * archived one.
 */
export const paymentAccounts = (accounts: Account[]) => accounts.filter((a) => (a.type === 'ASSET' || a.type === 'LIABILITY')
  && !a.system && !a.requiresCounterparty && !a.archived)

/** Where the server's objections to an expense, an income or a settlement go on the form. */
export interface ExpenseProblems {
  date: string[]
  category: string[]
  amount: string[]
  payer: string[]
  /** A settlement's receiver. */
  payee: string[]
  payment: string[]
  comment: string[]
  split: string[]
  /** By the member of the split they name. */
  byMember: Map<number, string[]>
  /** Next to the action. */
  other: string[]
}

const FIELDS: Record<string, keyof Omit<ExpenseProblems, 'byMember' | 'other'>> = {
  date: 'date', categoryId: 'category', amount: 'amount', payerMemberId: 'payer', payeeMemberId: 'payee',
  paymentAccountId: 'payment', paymentLater: 'payment', comment: 'comment',
}
const CODES: Record<string, keyof Omit<ExpenseProblems, 'byMember' | 'other'>> = {
  CATEGORY: 'category', AMOUNT: 'amount', PAYER: 'payer', PAYEE: 'payee', PAYMENT: 'payment',
}

/**
 * The server's objections by the field or member they name: a 400's invalid fields, and a 422's violationDetails by
 * their code and member. A member of the split gets theirs next to their share; the payer's go to the payer, and a
 * settlement's receiver's to the receiver. A 409 goes
 * to `conflict` (the date for a new expense, whose only 409 is D-27's start date), and anything else next to the action.
 */
export function expenseProblems(failure: Error | undefined, splitMemberIds: number[], payerId: number | null,
  conflict: 'date' | 'other' = 'other', payeeId: number | null = null): ExpenseProblems {
  const problems: ExpenseProblems = {
    date: [], category: [], amount: [], payer: [], payee: [], payment: [], comment: [], split: [], byMember: new Map(), other: [],
  }
  if (!failure) return problems
  const sentence = (text: string) => text.charAt(0).toUpperCase() + text.slice(1) + (/[.!?]$/.test(text) ? '' : '.')
  if (!(failure instanceof ApiError)) {
    problems.other.push(failure.message)
    return problems
  }
  if (failure.status === 409) {
    problems[conflict].push(sentence(failure.message))
    return problems
  }
  if (failure.errors.length > 0) {
    for (const { field, message } of failure.errors) {
      const key = FIELDS[field] ?? (field.startsWith('split') ? 'split' : undefined)
      if (key) problems[key].push(sentence(message))
      else problems.other.push(sentence(`${field} ${message}`))
    }
    return problems
  }
  if (failure.violationDetails.length === 0) {
    problems.other.push(sentence(failure.message))
    return problems
  }
  for (const { code, memberId, message } of failure.violationDetails) {
    const text = sentence(message)
    if (CODES[code]) problems[CODES[code]].push(text)
    else if (memberId !== null && splitMemberIds.includes(memberId)) {
      problems.byMember.set(memberId, [...(problems.byMember.get(memberId) ?? []), text])
    } else if (memberId !== null && memberId === payerId) problems.payer.push(text)
    else if (memberId !== null && memberId === payeeId) problems.payee.push(text)
    else problems.split.push(text)
  }
  return problems
}
