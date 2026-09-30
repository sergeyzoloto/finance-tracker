import { ApiError, type FamilyLedger, type FamilyMember } from './api'
import type { EntryForm, FieldErrors } from './entryForm'
import { expenseProblems, splitRequest, type SplitContext, type SplitPreview } from './expenseForm'
import { fromMinor, parseMinor } from './minorUnits'

// A family expense from the personal entry form (C2) without React: the family budgets a new expense may go to, what
// keeps it from being saved, the request that creates it, and where the server's objections go. The family budget's
// rules are FamilyRecordService's; the server's answer is what counts.

/** The family budgets whose base currency the entry is in: until other currencies come (F4e), the ones it may go to. */
export const familiesFor = (families: FamilyLedger[], currency: string) =>
  families.filter((f) => f.baseCurrency === currency.trim().toUpperCase())

/**
 * Why the entry can't be a family expense, or undefined if it can: the chosen family budget keeps another currency,
 * or, with none chosen yet, every family budget does.
 */
export function unavailable(families: FamilyLedger[], currency: string, chosen?: FamilyLedger): string | undefined {
  const code = currency.trim().toUpperCase()
  const entry = code === '' ? 'this entry has no currency yet' : `this entry is in ${code}`
  if (chosen) {
    return chosen.baseCurrency === code ? undefined
      : `The family budget ${chosen.name} keeps its expenses in ${chosen.baseCurrency}, and ${entry}. Expenses in other currencies come later.`
  }
  if (familiesFor(families, code).length > 0) return undefined
  const currencies = [...new Set(families.map((f) => f.baseCurrency))].join(', ')
  return `A family expense is in its family budget’s currency (${currencies}), and ${entry}. Expenses in other currencies come later.`
}

/** The split's context: who shares the entry's date, its amount in the minor unit, and the user, who paid. */
export function splitContext(form: EntryForm, family: FamilyLedger, members: FamilyMember[]): SplitContext {
  const parsed = parseMinor(form.amount, family.baseCurrency)
  return { ledger: family, members, date: form.date, amount: 'minor' in parsed ? parsed.minor : undefined, payerId: family.memberId }
}

/** What keeps the family expense from being saved, beyond the entry's own checks (`validate`). */
export function familyProblems(form: EntryForm, family: FamilyLedger, preview: SplitPreview): FieldErrors {
  const errors: FieldErrors = {}
  const add = (field: string, message: string) => { (errors[field] ??= []).push(message) }
  const reason = unavailable([family], form.currency, family)
  if (reason) add('', reason)
  const parsed = parseMinor(form.amount, family.baseCurrency)
  if (form.amount.trim() !== '' && 'problem' in parsed) add('amount', parsed.problem)
  if (form.date !== '' && form.date < family.startDate) {
    add('date', `The family budget ${family.name} starts on ${family.startDate}; an expense can’t be earlier.`)
  }
  preview.problems.forEach((p) => add('familySplit', p))
  return errors
}

/**
 * The request of POST /api/family-ledgers/{id}/records, as the family pages send it (C1): the user paid it from the
 * entry's account, and the memo is their private note, which only their payment entry keeps.
 */
export function familyRequest(form: EntryForm, family: FamilyLedger, preview: SplitPreview) {
  const currency = family.baseCurrency
  const parsed = parseMinor(form.amount, currency)
  if (!('minor' in parsed)) throw new Error('A family expense needs a valid amount')
  return {
    date: form.date,
    categoryId: Number(form.familyCategoryId),
    amount: fromMinor(parsed.minor, currency),
    comment: form.familyComment.trim() || null,
    payerMemberId: family.memberId,
    paymentAccountId: Number(form.accountId),
    split: splitRequest(form.familySplit, preview, currency),
    privateNote: form.memo.trim() || null,
  }
}

/** The key of a member's messages in the split: `familyMember.<id>`. */
export const memberField = (memberId: number) => `familyMember.${memberId}`

/** The server's objections at the entry form's fields; a member of the split's at `familyMember.<id>`. */
export function familyServerErrors(error: unknown, preview: SplitPreview, payerId: number): FieldErrors {
  const failure = error instanceof Error ? error : new Error('Saving failed.')
  const errors: FieldErrors = {}
  const add = (field: string, messages: string[]) => { if (messages.length > 0) (errors[field] ??= []).push(...messages) }
  if (failure instanceof ApiError && failure.errors.some((e) => e.field === 'privateNote')) {
    add('memo', failure.errors.filter((e) => e.field === 'privateNote').map((e) => `Keep the note to 500 characters (${e.message}).`))
  }
  const problems = expenseProblems(failure, preview.rows.map((r) => r.member.id), payerId, 'date')
  add('date', problems.date)
  add('familyCategoryId', problems.category)
  add('amount', problems.amount)
  add('accountId', problems.payment)
  add('familyComment', problems.comment)
  add('familySplit', problems.split)
  add('', [...problems.payer, ...problems.other.filter((m) => !/privateNote/i.test(m))])
  for (const [memberId, messages] of problems.byMember) add(memberField(memberId), messages)
  return errors
}
