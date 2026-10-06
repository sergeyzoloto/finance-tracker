import { describe, expect, it } from 'vitest'
import type { Account, FamilyChange, Rate } from './api'
import { marks, parseRateInput, payingCurrency, payingSideRequest, rateNotes, recordAmount, sidePatch } from './currency'
import { journalLine } from './family'
import { formatMoney } from './money'

// Currencies in a family budget (F8b): the paying side of D-89, the rates of D-47's totals, D-49's rate input, and the
// journal's currencies (D-45).

const account = (defaultCurrency: string | null): Account => ({
  id: 5, code: 'CASH', name: 'Cash', type: 'ASSET', defaultCurrency, requiresCounterparty: false, system: false, archived: false,
} as Account)

describe('the paying currency (D-89)', () => {
  it('is the one chosen, else the side’s on the same account, else the account’s default, else the record’s', () => {
    expect(payingCurrency(account('EUR'), 'USD', 'USD')).toBe('USD')
    expect(payingCurrency(account('EUR'), 'USD', undefined, 'GBP')).toBe('GBP')
    expect(payingCurrency(account('EUR'), 'USD')).toBe('EUR')
    expect(payingCurrency(account(null), 'USD')).toBe('USD')
  })

  it('names what went from the account only in another currency than the record’s, and asks for it when needed', () => {
    const typed = (amountText: string, currency?: string) => ({ amountText, currency })
    expect(payingSideRequest('USD', 'USD', typed(''), true)).toEqual({ request: { accountCurrency: 'USD' } })
    expect(payingSideRequest('EUR', 'USD', typed(''), true)).toEqual({ problem: 'Enter the amount in EUR.' })
    expect(payingSideRequest('EUR', 'USD', typed(''), false)).toEqual({ request: { accountCurrency: 'EUR' } })
    expect(payingSideRequest('EUR', 'USD', typed('51,50'), true)).toEqual({ request: { accountCurrency: 'EUR', accountAmount: '51.50' } })
    expect(payingSideRequest('JPY', 'USD', typed('10.5'), true)).toEqual({ problem: 'JPY has no decimals.' })
    expect(payingSideRequest('EU', 'USD', typed(''), true)).toEqual({ problem: 'Enter a three-letter currency code, such as USD.' })
  })

  it('is asked again when the amount, the account or the paying currency moves, not otherwise', () => {
    const side = { amountText: '' }
    const base = { account: account('EUR'), currency: 'USD', side, kept: 'EUR' }
    expect(sidePatch({ ...base, moves: false, accountChanged: false })).toEqual({ patch: {} })
    expect(sidePatch({ ...base, moves: true, accountChanged: false })).toEqual({ problem: 'Enter the amount in EUR.' })
    expect(sidePatch({ ...base, moves: false, accountChanged: true })).toEqual({ problem: 'Enter the amount in EUR.' })
    expect(sidePatch({ ...base, side: { amountText: '', currency: 'USD' }, moves: false, accountChanged: false }))
      .toEqual({ patch: { accountCurrency: 'USD' } })
    expect(sidePatch({ ...base, side: { amountText: '52.00' }, moves: false, accountChanged: false }))
      .toEqual({ patch: { accountAmount: '52.00' } })
    expect(sidePatch({ ...base, account: undefined, moves: true, accountChanged: true })).toEqual({ patch: {} })
  })
})

describe('a total’s rates (D-47, D-49)', () => {
  const ecb: Rate = { currency: 'USD', date: '2026-10-10', perEuro: '1.10', source: 'ECB', stale: false }
  const mine: Rate = { currency: 'RUB', date: '2026-08-01', perEuro: '95.50', source: 'MANUAL', stale: true }
  it('names each rate with its date and source, and marks manual and stale ones', () => {
    expect(rateNotes([ecb, mine])).toEqual([
      '1 EUR = 1.1 USD, ECB rate of Oct 10, 2026', '1 EUR = 95.5 RUB, manual rate of Aug 1, 2026, rate stale'])
    expect(marks([ecb])).toEqual({ manual: false, stale: false })
    expect(marks([ecb, mine])).toEqual({ manual: true, stale: true })
  })
})

describe('a manual rate as typed (D-49)', () => {
  it('takes "1 EUR = 95,50 RUB" with a comma or a dot', () => {
    expect(parseRateInput('95,50')).toEqual({ rate: '95.50' })
    expect(parseRateInput(' 95.5 ')).toEqual({ rate: '95.5' })
    expect(parseRateInput('1 000,25')).toEqual({ rate: '1000.25' })
    expect(parseRateInput('abc')).toEqual({ problem: 'Enter the rate as a number, such as 95,50 or 95.50.' })
    expect(parseRateInput('0')).toEqual({ problem: 'The rate must be more than 0.' })
    expect(parseRateInput('1.123456789')).toEqual({ problem: 'The rate has at most 8 decimals.' })
  })
})

describe('a record’s amount', () => {
  it('is in its own currency (D-45)', () => {
    expect(recordAmount({ amount: '56.00', currency: 'USD' })).toBe('$56.00')
  })
})

describe('the journal', () => {
  const at = '2026-09-12T10:00:00Z'
  const change = (action: FamilyChange['action'], changes: FamilyChange['changes'], currency = 'EUR'): FamilyChange => ({
    id: 1, at, action, recordId: 5, author: { memberId: 70, displayName: 'Anna' }, about: null, changes,
    record: { date: '2026-09-10', category: 'Groceries', amount: '50.00', deleted: false, type: 'EXPENSE', currency },
  })

  it('says each record’s amounts in its own currency (D-45)', () => {
    expect(journalLine(change('CREATE', [
      { field: 'amount', member: null, old: null, new: '56.00' },
      { field: 'currency', member: null, old: null, new: 'USD' },
      { field: 'payer', member: null, old: null, new: 'Anna' },
    ], 'USD'), 'EUR').text).toBe('Anna added Groceries, Sep 10, 2026: $56.00, paid by Anna.')
    expect(journalLine(change('UPDATE', [
      { field: 'amount', member: null, old: '50.00', new: '56.00' },
      { field: 'currency', member: null, old: 'EUR', new: 'USD' },
    ], 'USD'), 'EUR').text).toBe('Anna changed the amount of Groceries, Sep 10, 2026: €50.00 → $56.00.')
    expect(journalLine(change('DELETE', [], 'RUB'), 'EUR').text).toBe(`Anna deleted Groceries, Sep 10, 2026, ${formatMoney('50.00', 'RUB')}.`)
  })

  it('names the original amount of an F4e record to the member who changed it', () => {
    expect(journalLine(change('CREATE', [
      { field: 'amount', member: null, old: null, new: '50.00' },
      { field: 'originalAmount', member: null, old: null, new: '56.00 USD' },
      { field: 'payer', member: null, old: null, new: 'Anna' },
    ]), 'EUR').text).toBe('Anna added Groceries, Sep 10, 2026: $56.00 → €50.00, paid by Anna.')
    expect(journalLine(change('UPDATE', [
      { field: 'amount', member: null, old: '50.00', new: '60.00' },
      { field: 'originalAmount', member: null, old: '56.00 USD', new: '67.20 USD' },
    ]), 'EUR')).toEqual({
      text: 'Anna changed the amount paid and the amount in EUR of Groceries, Sep 10, 2026:',
      details: ['Amount paid: $56.00 → $67.20', 'Amount in EUR: €50.00 → €60.00'],
    })
  })
})
