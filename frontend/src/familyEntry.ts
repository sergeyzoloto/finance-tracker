import { ApiError, type FamilyLedger, type FamilyMember } from './api'
import type { EntryForm, FieldErrors } from './entryForm'
import { expenseProblems, splitRequest, type SplitContext, type SplitPreview } from './expenseForm'
import { fromMinor, parseMinor } from './minorUnits'

// A family expense or income from the personal entry form (C2, F4d) without React: the family budgets a new expense or
// income may go to, what keeps it from being saved, the request that creates it, and where the server's objections go.
// The family budget's rules are FamilyRecordService's; the server's answer is what counts.

/** What the form's tab makes of it: an expense, or an income, which mirrors it. */
const nounOf = (form: Pick<EntryForm, 'tab'>) => (form.tab === 'income' ? 'income' : 'expense')

/** The family budgets a new expense or income may go to: any, whatever the entry's currency (D-45). */
export const familiesFor = (families: FamilyLedger[], _currency?: string) => families

/**
 * Why the entry can't be a family expense or income, or undefined if it can: only an entry without a valid currency
 * yet. The record is in the entry's own currency, whatever the budget's main currency (D-45).
 */
export function unavailable(_families: FamilyLedger[], currency: string, _chosen?: FamilyLedger, noun = 'expense'): string | undefined {
  return /^[A-Z]{3}$/.test(currency.trim().toUpperCase()) ? undefined
    : `Choose the entry’s currency before making it a family ${noun}.`
}

/**
 * The split's context: who shares the entry's date, the entry's amount in its own currency's minor unit, which the
 * record keeps (D-45), and the user, who paid or received it.
 */
export function splitContext(form: EntryForm, family: FamilyLedger, members: FamilyMember[]): SplitContext {
  return {
    ledger: family, members, date: form.date, amount: entryAmount(form), payerId: family.memberId,
    noun: nounOf(form),
  }
}

/** The entry's amount in its own currency's minor unit, or undefined while it isn't one. */
export function entryAmount(form: EntryForm): bigint | undefined {
  const parsed = parseMinor(form.amount, form.currency.trim().toUpperCase() || 'EUR')
  return 'minor' in parsed ? parsed.minor : undefined
}

/** What keeps the family expense or income from being saved, beyond the entry's own checks (`validate`). */
export function familyProblems(form: EntryForm, family: FamilyLedger, preview: SplitPreview): FieldErrors {
  const errors: FieldErrors = {}
  const add = (field: string, message: string) => { (errors[field] ??= []).push(message) }
  const reason = unavailable([family], form.currency, family, nounOf(form))
  if (reason) add('', reason)
  if (form.date !== '' && form.date < family.startDate) {
    add('date', `The family budget ${family.name} starts on ${family.startDate}; an ${nounOf(form)} can’t be earlier.`)
  }
  preview.problems.forEach((p) => add('familySplit', p))
  return errors
}

/**
 * The request of POST /api/family-ledgers/{id}/records, as the family pages send it (C1, C5): the user paid an expense
 * from the entry's account, or received an income into it, in the entry's currency, which is the record's (D-45) and
 * the paying currency (D-89), whatever the account's default (`accountDefault`); the memo is their private note,
 * which only their own entry keeps.
 */
export function familyRequest(form: EntryForm, family: FamilyLedger, preview: SplitPreview,
  accountDefault: string | null = null) {
  const currency = form.currency.trim().toUpperCase()
  const parsed = parseMinor(form.amount, currency)
  if (!('minor' in parsed)) throw new Error(`A family ${nounOf(form)} needs a valid amount`)
  const inCurrency = currency === family.baseCurrency ? {} : { currency }
  return {
    type: form.tab === 'income' ? 'INCOME' as const : 'EXPENSE' as const,
    date: form.date,
    categoryId: Number(form.familyCategoryId),
    amount: fromMinor(parsed.minor, currency),
    ...inCurrency,
    // Named only where the account's own currency would otherwise be taken (D-89).
    ...(accountDefault !== null && accountDefault !== currency ? { accountCurrency: currency } : {}),
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
  add('currency', problems.currency)
  add('accountId', problems.payment)
  add('familyComment', problems.comment)
  add('familySplit', problems.split)
  add('', [...problems.payer, ...problems.other.filter((m) => !/privateNote/i.test(m))])
  for (const [memberId, messages] of problems.byMember) add(memberField(memberId), messages)
  return errors
}
