import { describe, expect, it } from 'vitest'
import { ApiError, type Entry, type Posting } from './api'
import { formFromEntry, newCounterpartyNames, newForm, serverErrors, toCommand, validate, type EntryForm } from './entryForm'
import { describeEntry } from './ledger'
import { testLedger as ledger } from './testLedger'

const posting = (accountId: number, currency: string, amount: string, categoryId: number | null = null,
  counterpartyId: number | null = null): Posting => ({ accountId, currency, amount, categoryId, counterpartyId })

const entry = (kind: Entry['kind'], postings: Posting[], payeeId: number | null = null): Entry =>
  ({ id: 1, version: 0, entryDate: '2026-09-25', kind, payeeId, memo: null, postings })

const form = (changes: Partial<EntryForm>): EntryForm => ({ ...newForm(ledger, '2026-09-25'), ...changes })

describe('formFromEntry', () => {
  it('opens a shared expense in Expense with the split it was made with', () => {
    const shared = entry('SHARED_EXPENSE', [posting(2, 'EUR', '-725.55'), posting(6, 'EUR', '362.77', 12), posting(5, 'EUR', '362.78')], 21)
    const { form, simple } = formFromEntry(shared, ledger)
    expect(simple).toBe(true)
    expect(form).toMatchObject({
      tab: 'expense', payee: 'Albert Heijn', accountId: '2', amount: '725.55', categoryId: '12', split: true,
      sharePercent: '50', refund: false,
    })
    expect(toCommand(form, ledger)).toEqual({
      kind: 'SHARED_EXPENSE', entryDate: '2026-09-25', payeeId: 21, memo: null, accountId: 2, currency: 'EUR',
      total: '725.55', categoryId: 12, shareRatio: '0.5',
    })
  })

  it('opens a refund as an expense with the refund option', () => {
    const refund = entry('EXPENSE', [posting(2, 'EUR', '12.5'), posting(6, 'EUR', '-12.5', 12)])
    const { form } = formFromEntry(refund, ledger)
    expect(form).toMatchObject({ tab: 'expense', amount: '12.5', refund: true })
    expect(toCommand(form, ledger)).toMatchObject({ kind: 'EXPENSE', amount: '-12.5' })
  })

  it('opens loans and exchanges in their tabs', () => {
    expect(formFromEntry(entry('LOAN_REPAID', [posting(3, 'EUR', '-50', null, 22), posting(1, 'EUR', '50')]), ledger).form)
      .toMatchObject({ tab: 'loan', loan: 'repaid', accountId: '1', counterparty: 'Ivan', amount: '50' })
    const exchange = entry('CURRENCY_EXCHANGE', [
      posting(2, 'EUR', '-100'), posting(8, 'EUR', '100'), posting(8, 'USD', '-108.3'), posting(9, 'USD', '108.3')])
    expect(formFromEntry(exchange, ledger).form).toMatchObject({
      tab: 'exchange', accountId: '2', currency: 'EUR', amount: '100', toAccountId: '9', toCurrency: 'USD', toAmount: '108.3',
    })
  })

  it('opens a transfer with a creditor in Transfer, and one with a stray counterparty in Advanced', () => {
    const borrowed = entry('TRANSFER', [posting(4, 'EUR', '-200', null, 22), posting(2, 'EUR', '200')])
    expect(formFromEntry(borrowed, ledger).form).toMatchObject({ tab: 'transfer', accountId: '4', toAccountId: '2', counterparty: 'Ivan' })
    const stray = entry('TRANSFER', [posting(1, 'EUR', '-200', null, 22), posting(2, 'EUR', '200', null, 22)])
    expect(formFromEntry(stray, ledger)).toMatchObject({ simple: false, form: { tab: 'advanced' } })
  })

  it('opens postings of another shape in Advanced', () => {
    const odd = entry('EXPENSE', [posting(2, 'EUR', '-10'), posting(6, 'EUR', '7', 12), posting(1, 'EUR', '3')])
    const { form, simple } = formFromEntry(odd, ledger)
    expect(simple).toBe(false)
    expect(form.tab).toBe('advanced')
    expect(form.postings.map((p) => [p.accountId, p.amount, p.categoryId])).toEqual([['2', '-10', ''], ['6', '7', '12'], ['1', '3', '']])
  })
})

describe('toCommand', () => {
  it('builds a loan from the direction the user picked', () => {
    expect(toCommand(form({ tab: 'loan', loan: 'given', accountId: '1', counterparty: 'ivan', amount: '20' }), ledger)).toEqual({
      kind: 'LOAN_GIVEN', entryDate: '2026-09-25', memo: null, fromAccountId: 1, counterpartyId: 22, currency: 'EUR', amount: '20',
    })
  })

  it('names the counterparties to create first', () => {
    expect(newCounterpartyNames(form({ payee: ' Jumbo ' }), ledger)).toEqual(['Jumbo'])
    expect(newCounterpartyNames(form({ payee: 'albert heijn' }), ledger)).toEqual([])
  })
})

describe('validate', () => {
  it('asks for a counterparty where a transfer touches a per-person account', () => {
    const transfer = form({ tab: 'transfer', accountId: '1', toAccountId: '4', amount: '10' })
    expect(validate(transfer, ledger).counterparty).toBeDefined()
    expect(validate({ ...transfer, toAccountId: '2' }, ledger)).toEqual({})
  })
})

describe('serverErrors', () => {
  it('puts ledger rule violations at the fields they are about', () => {
    const expense = form({ tab: 'expense', accountId: '4', amount: '5', categoryId: '11' })
    const errors = serverErrors(expense, ledger, new ApiError('Invalid', 422, [], [
      'the amount must not be zero',
      'posting 1 (CREDITOR_DEBT): the account requires a counterparty',
      'posting 2 (UNALLOCATED): category SALARY is INCOME, and an entry of kind EXPENSE needs EXPENSE categories',
      'the postings in EUR do not balance: debits 5.00, credits 4.00, difference 1.00',
    ]))
    expect(errors).toEqual({
      amount: ['The amount must not be zero.'],
      accountId: ['This account keeps balances per person; record this in Transfer or Advanced.'],
      categoryId: ['Choose an expense category.'],
      '': ['The postings in EUR do not balance: debits 5.00, credits 4.00, difference 1.00.'],
    })
  })

  it('puts invalid request fields and postings at their fields', () => {
    const advanced = form({ tab: 'advanced' })
    expect(serverErrors(advanced, ledger, new ApiError('Invalid', 400, [{ field: 'postings[1].amount', message: 'must be a decimal number' }])))
      .toEqual({ 'postings.1.amount': ['Must be a decimal number.'] })
    expect(serverErrors(advanced, ledger, new ApiError('Invalid', 422, [], ['posting 2 (CASH): counterparty 99 does not exist'])))
      .toEqual({ 'postings.1.counterparty': ['Counterparty 99 does not exist.'] })
  })

  it('shows other failures at the top', () => {
    expect(serverErrors(form({}), ledger, new ApiError('Entry 1 has changed since version 0.', 409)))
      .toEqual({ '': ['Entry 1 has changed since version 0.'] })
  })
})

describe('describeEntry', () => {
  it('reads an expense as money going from the account to the category', () => {
    const summary = describeEntry(entry('SHARED_EXPENSE',
      [posting(2, 'EUR', '-725.55'), posting(6, 'EUR', '362.77', 12), posting(5, 'EUR', '362.78')], 21), ledger)
    expect(summary).toEqual({
      payee: 'Albert Heijn', category: 'Groceries', flow: 'ING → Groceries, Family budget',
      amounts: [{ currency: 'EUR', amount: '725.55' }], direction: -1,
    })
  })

  it('leaves out the exchange account and shows both currencies', () => {
    const summary = describeEntry(entry('CURRENCY_EXCHANGE', [
      posting(2, 'EUR', '-100'), posting(8, 'EUR', '100'), posting(8, 'USD', '-108.3'), posting(9, 'USD', '108.3')]), ledger)
    expect(summary).toMatchObject({ flow: 'ING → Wise', amounts: [{ currency: 'EUR', amount: '100' }, { currency: 'USD', amount: '108.3' }], direction: 0 })
  })

  it('names the borrower of a loan', () => {
    const summary = describeEntry(entry('LOAN_GIVEN', [posting(1, 'EUR', '-50'), posting(3, 'EUR', '50', null, 22)]), ledger)
    expect(summary).toMatchObject({ payee: 'Ivan', flow: 'Cash → Loans given (Ivan)', direction: 0 })
  })
})
