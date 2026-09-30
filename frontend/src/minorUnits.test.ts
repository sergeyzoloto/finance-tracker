import { describe, expect, it } from 'vitest'
import { fromMinor, minorUnit, parseMinor, toMinor } from './minorUnits'
import { formatMoney } from './money'

describe('minor units', () => {
  it('are those the backend uses', () => {
    expect(minorUnit('EUR')).toBe(2)
    expect(minorUnit('usd')).toBe(2)
    expect(minorUnit('JPY')).toBe(0)
    expect(minorUnit('KWD')).toBe(3)
    expect(minorUnit('XAU')).toBe(4)
  })

  it('turn decimal strings into whole numbers and back, without floating point', () => {
    expect(toMinor('10.01', 'EUR')).toBe(1001n)
    expect(toMinor('10.1', 'EUR')).toBe(1010n)
    expect(toMinor('12.5000', 'EUR')).toBe(1250n)
    expect(toMinor('-0.05', 'EUR')).toBe(-5n)
    expect(toMinor('1001', 'JPY')).toBe(1001n)
    expect(toMinor('1.000', 'JPY')).toBe(1n)
    // Beyond what a float holds exactly.
    expect(toMinor('123456789012345.67', 'EUR')).toBe(12345678901234567n)
    expect(fromMinor(12345678901234567n, 'EUR')).toBe('123456789012345.67')
    expect(fromMinor(1001n, 'EUR')).toBe('10.01')
    expect(fromMinor(5n, 'EUR')).toBe('0.05')
    expect(fromMinor(-5n, 'EUR')).toBe('-0.05')
    expect(fromMinor(0n, 'EUR')).toBe('0.00')
    expect(fromMinor(1001n, 'JPY')).toBe('1001')
    expect(fromMinor(1n, 'KWD')).toBe('0.001')
  })

  it('refuse more decimals than the currency has', () => {
    expect(toMinor('0.001', 'EUR')).toBeUndefined()
    expect(toMinor('1.5', 'JPY')).toBeUndefined()
    expect(toMinor('1e3', 'EUR')).toBeUndefined()
    expect(toMinor('', 'EUR')).toBeUndefined()
  })

  it('read what a user types', () => {
    expect(parseMinor('12,50', 'EUR')).toEqual({ minor: 1250n })
    expect(parseMinor('1.234,50', 'EUR')).toEqual({ minor: 123450n })
    expect(parseMinor(' 1 000 ', 'JPY')).toEqual({ minor: 1000n })
    expect(parseMinor('', 'EUR')).toEqual({ problem: 'Enter an amount.' })
    expect(parseMinor('abc', 'EUR')).toEqual({ problem: 'Enter a number, such as 12.50.' })
    expect(parseMinor('1.005', 'EUR')).toEqual({ problem: 'EUR has at most 2 decimals.' })
    expect(parseMinor('1.5', 'JPY')).toEqual({ problem: 'JPY has no decimals.' })
    expect(parseMinor('0', 'EUR')).toEqual({ problem: 'The amount must be above 0.' })
    expect(parseMinor('-3', 'EUR')).toEqual({ problem: 'The amount must be above 0.' })
    expect(parseMinor('0', 'EUR', { zero: true })).toEqual({ minor: 0n })
    expect(parseMinor('-3', 'EUR', { zero: true })).toEqual({ problem: 'Enter 0 or more.' })
    expect(parseMinor('1000000000000000', 'EUR')).toEqual({ problem: 'The amount is too large.' })
  })

  it('format in the currency’s own way', () => {
    expect(formatMoney(fromMinor(501n, 'EUR'), 'EUR')).toBe('€5.01')
    expect(formatMoney(fromMinor(501n, 'JPY'), 'JPY')).toBe('¥501')
  })
})
