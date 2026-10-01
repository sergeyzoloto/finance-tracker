import { ApiError, type FamilyLedger, type FamilyMember } from './api'
import type { EntryForm, FieldErrors } from './entryForm'
import { expenseProblems, splitRequest, type SplitContext, type SplitPreview } from './expenseForm'
import type { BaseAmount } from './currency'
import { fromMinor, parseMinor } from './minorUnits'

// A family expense or income from the personal entry form (C2, F4d) without React: the family budgets a new expense or
// income may go to, what keeps it from being saved, the request that creates it, and where the server's objections go.
// The family budget's rules are FamilyRecordService's; the server's answer is what counts.

/** What the form's tab makes of it: an expense, or an income, which mirrors it. */
const nounOf = (form: Pick<EntryForm, 'tab'>) => (form.tab === 'income' ? 'income' : 'expense')

/** The family budgets a new expense or income may go to: any, whatever the entry's currency, since F4e (D-13). */
export const familiesFor = (families: FamilyLedger[], _currency?: string) => families

/**
 * Why the entry can't be a family expense or income, or undefined if it can: only an entry without a valid currency
 * yet. An entry in another currency than the budget's is converted into it (F4e).
 */
export function unavailable(_families: FamilyLedger[], currency: string, _chosen?: FamilyLedger, noun = 'expense'): string | undefined {
  return /^[A-Z]{3}$/.test(currency.trim().toUpperCase()) ? undefined
    : `Choose the entry’s currency before making it a family ${noun}.`
}

/**
 * The split's context: who shares the entry's date, the amount in the budget's base currency in its minor unit (the
 * entry's amount, or its conversion, F4e), and the user, who paid or received it.
 */
export function splitContext(form: EntryForm, family: FamilyLedger, members: FamilyMember[], base: BaseAmount): SplitContext {
  return {
    ledger: family, members, date: form.date, amount: base.minor, payerId: family.memberId,
    noun: nounOf(form),
  }
}

/** The entry's amount in its own currency's minor unit, or undefined while it isn't one. */
export function entryAmount(form: EntryForm): bigint | undefined {
  const parsed = parseMinor(form.amount, form.currency.trim().toUpperCase() || 'EUR')
  return 'minor' in parsed ? parsed.minor : undefined
}

/** What keeps the family expense or income from being saved, beyond the entry's own checks (`validate`). */
export function familyProblems(form: EntryForm, family: FamilyLedger, preview: SplitPreview, base: BaseAmount): FieldErrors {
  const errors: FieldErrors = {}
  const add = (field: string, message: string) => { (errors[field] ??= []).push(message) }
  const reason = unavailable([family], form.currency, family, nounOf(form))
  if (reason) add('', reason)
  if (base.problem) add('familyBaseAmount', base.problem)
  else if (base.state === 'MISSING') add('familyBaseAmount', `Enter the amount in ${family.baseCurrency}.`)
  else if (base.state === 'PENDING' && form.amount.trim() !== '') add('familyBaseAmount', `The amount in ${family.baseCurrency} is still being converted.`)
  if (form.date !== '' && form.date < family.startDate) {
    add('date', `The family budget ${family.name} starts on ${family.startDate}; an ${nounOf(form)} can’t be earlier.`)
  }
  preview.problems.forEach((p) => add('familySplit', p))
  return errors
}

/**
 * The request of POST /api/family-ledgers/{id}/records, as the family pages send it (C1, C5): the user paid an expense
 * from the entry's account, or received an income into it, and the memo is their private note, which only their own
 * entry keeps.
 */
export function familyRequest(form: EntryForm, family: FamilyLedger, preview: SplitPreview, base?: BaseAmount) {
  const currency = form.currency.trim().toUpperCase()
  const parsed = parseMinor(form.amount, currency)
  if (!('minor' in parsed)) throw new Error(`A family ${nounOf(form)} needs a valid amount`)
  // In another currency than the budget's (F4e): the currency, and the base amount when typed in.
  const inCurrency = currency === family.baseCurrency ? {} : { currency }
  const typedBase = base?.state === 'ENTERED' && base.minor !== undefined
    ? { baseAmount: fromMinor(base.minor, family.baseCurrency) } : {}
  return {
    type: form.tab === 'income' ? 'INCOME' as const : 'EXPENSE' as const,
    date: form.date,
    categoryId: Number(form.familyCategoryId),
    amount: fromMinor(parsed.minor, currency),
    ...inCurrency,
    ...typedBase,
    comment: form.familyComment.trim() || null,
    payerMemberId: family.memberId,
    paymentAccountId: Number(form.accountId),
    split: splitRequest(form.familySplit, preview, family.baseCurrency),
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
  add('familyBaseAmount', problems.baseAmount)
  add('currency', problems.currency)
  add('accountId', problems.payment)
  add('familyComment', problems.comment)
  add('familySplit', problems.split)
  add('', [...problems.payer, ...problems.other.filter((m) => !/privateNote/i.test(m))])
  for (const [memberId, messages] of problems.byMember) add(memberField(memberId), messages)
  return errors
}
