import { describe, expect, it } from 'vitest'
import type { FamilyChange } from './api'
import { baseAmount, conversionPath, rateLine, recordAmount } from './currency'
import { journalLine } from './family'

// Records in other currencies (F4e): the rate as the screens say it, a record's two amounts, and where a form's base
// amount comes from.

describe('the rate line', () => {
  const usd = { currency: 'USD', baseCurrency: 'EUR', rate: '0.892857142857', rateDate: '2026-09-10' }
  it('names the rate, to six significant digits, its source and its day', () => {
    expect(rateLine({ ...usd, rateSource: 'ECB' })).toBe('1 USD = 0.892857 EUR, ECB rate of Sep 10, 2026')
    expect(rateLine({ ...usd, rateSource: 'MANUAL' })).toBe('1 USD = 0.892857 EUR, your own rate of Sep 10, 2026')
    expect(rateLine({ ...usd, rateSource: 'MANUAL' }, { reader: false })).toBe('1 USD = 0.892857 EUR, manual rate of Sep 10, 2026')
    expect(rateLine({ currency: 'JPY', baseCurrency: 'EUR', rate: '0.00625', rateSource: 'ECB' })).toBe('1 JPY = 0.00625 EUR, ECB rate')
  })
  it('says when the base amount was typed in, and nothing in the base currency', () => {
    expect(rateLine({ currency: 'RUB', baseCurrency: 'EUR', rateSource: 'ENTERED' })).toBe('Amount in EUR entered by hand')
    expect(rateLine({ currency: 'EUR', baseCurrency: 'EUR' })).toBeUndefined()
  })
})

describe('a record’s amount', () => {
  it('shows the original amount and the base amount where the currencies differ, else the base amount alone', () => {
    expect(recordAmount({ amount: '50.00', currency: 'EUR', originalAmount: '56.00', originalCurrency: 'USD' })).toBe('$56.00 → €50.00')
    expect(recordAmount({ amount: '10.00', currency: 'EUR', originalAmount: '10.00', originalCurrency: 'EUR' })).toBe('€10.00')
  })
})

describe('a form’s base amount', () => {
  const conversion = { amount: '56.00', currency: 'USD', baseAmount: '50.00', baseCurrency: 'EUR', rate: '0.89', rateSource: 'ECB' as const }
  it('is the amount itself in the base currency', () => {
    expect(baseAmount({ currency: 'EUR', baseCurrency: 'EUR', amount: 1001n })).toEqual({ minor: 1001n, state: 'SAME' })
  })
  it('is the server’s conversion of exactly this amount, pending while it converts', () => {
    expect(baseAmount({ currency: 'USD', baseCurrency: 'EUR', amount: 5600n, conversion }).minor).toBe(5000n)
    expect(baseAmount({ currency: 'USD', baseCurrency: 'EUR', amount: 5700n, conversion }).state).toBe('PENDING')
    expect(baseAmount({ currency: 'USD', baseCurrency: 'EUR', amount: 5600n, conversion, loading: true }).state).toBe('PENDING')
    expect(baseAmount({ currency: 'USD', baseCurrency: 'EUR', amount: 5600n, conversion: { ...conversion, baseAmount: null } }).state)
      .toBe('MISSING')
  })
  it('is the typed one when typed, with its problem in the base currency', () => {
    expect(baseAmount({ currency: 'USD', baseCurrency: 'EUR', amount: 5600n, entered: '49.99', conversion })).toEqual({ minor: 4999n, state: 'ENTERED' })
    expect(baseAmount({ currency: 'USD', baseCurrency: 'EUR', amount: 5600n, entered: '1.001' }).problem).toBe('EUR has at most 2 decimals.')
    expect(baseAmount({ currency: 'EUR', baseCurrency: 'JPY', amount: 1003n, entered: '1504.5' }).problem).toBe('JPY has no decimals.')
  })
  it('asks the server only for an amount in another valid currency', () => {
    expect(conversionPath('/family-ledgers/7', 'JPY', 'EUR', 1000n, '2026-09-10'))
      .toBe('/family-ledgers/7/conversion?amount=1000&currency=JPY&date=2026-09-10')
    expect(conversionPath('/family-ledgers/7', 'EUR', 'EUR', 1000n, '2026-09-10')).toBeNull()
    expect(conversionPath('/family-ledgers/7', 'US', 'EUR', 1000n, '2026-09-10')).toBeNull()
    expect(conversionPath('/family-ledgers/7', 'USD', 'EUR', undefined, '2026-09-10')).toBeNull()
  })
})

describe('the journal', () => {
  const at = '2026-09-12T10:00:00Z'
  const change = (action: FamilyChange['action'], changes: FamilyChange['changes']): FamilyChange => ({
    id: 1, at, action, recordId: 5, author: { memberId: 70, displayName: 'Anna' }, about: null, changes,
    record: { date: '2026-09-10', category: 'Groceries', amount: '50.00', deleted: false, type: 'EXPENSE' },
  })
  it('names the original amount of a record in another currency', () => {
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
