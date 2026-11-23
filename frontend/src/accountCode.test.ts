import { describe, expect, it } from 'vitest'
import { accountCode } from './accountCode'

// The code of a new account, made from its name (D-1148).

describe('accountCode', () => {
  it('upper-cases a Latin name', () => {
    expect(accountCode('Savings', [])).toBe('SAVINGS')
    expect(accountCode('Credit card 2026', [])).toBe('CREDIT_CARD_2026')
  })

  it('makes one underscore of each run of spaces and punctuation, none at the ends', () => {
    expect(accountCode('Bank, savings #2', [])).toBe('BANK_SAVINGS_2')
    expect(accountCode('  --Cash!!  ', [])).toBe('CASH')
  })

  it('falls back to ACCOUNT for a name with no Latin letters or digits', () => {
    expect(accountCode('Копилка', [])).toBe('ACCOUNT')
    expect(accountCode('  ', [])).toBe('ACCOUNT')
  })

  it('does not prefix a name that starts with a digit', () => {
    expect(accountCode('2nd card', [])).toBe('ACCOUNT')
  })

  it('cuts a long name to 40 characters', () => {
    const code = accountCode('a'.repeat(60), [])
    expect(code).toBe('A'.repeat(40))
    expect(code).toHaveLength(40)
  })

  it('takes the next free number when the code is taken', () => {
    expect(accountCode('Savings', ['SAVINGS'])).toBe('SAVINGS_2')
    expect(accountCode('Savings', ['SAVINGS', 'SAVINGS_2'])).toBe('SAVINGS_3')
    expect(accountCode('Savings', ['savings'])).toBe('SAVINGS')
  })

  const awkward = ['', '   ', '!!!', 'Копилка', '💰💰', 'x'.repeat(500), '12345', 'Mixed Ключ 7 ✓ account_name', '_', 'a_b__c']

  it.each(awkward)('is a valid code for %j, with and without collisions', (name) => {
    const free = accountCode(name, [])
    expect(free).toMatch(/^[A-Z][A-Z0-9_]*$/)
    expect(free.length).toBeLessThanOrEqual(50)
    const taken = [free, `${free}_2`, `${free}_3`]
    const next = accountCode(name, taken)
    expect(next).toMatch(/^[A-Z][A-Z0-9_]*$/)
    expect(next.length).toBeLessThanOrEqual(50)
    expect(taken).not.toContain(next)
  })
})
