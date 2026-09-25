import { describe, expect, it } from 'vitest'
import { amountProblem, formatMoney, parseAmount, percentProblem, sharePercentOf, splitShared, sum } from './money'

describe('parseAmount', () => {
  it.each([
    ['725.55', '725.55'],
    ['725,55', '725.55'],
    ['1 234,50', '1234.5'],
    ['1.234,50', '1234.5'],
    ['1,234.50', '1234.5'],
    ['1.234.567', '1234567'],
    ['-12.5', '-12.5'],
    ['−12.5', '-12.5'],
    ['.5', '0.5'],
  ])('reads %s as %s', (text, amount) => expect(parseAmount(text)).toBe(amount))

  it.each(['', 'abc', '1e5', '12.5.3,1', '--1'])('refuses %j', (text) => expect(parseAmount(text)).toBeUndefined())
})

describe('amountProblem', () => {
  it('accepts what fits NUMERIC(19,4)', () => {
    expect(amountProblem('123456789012345.1234')).toBeUndefined()
    expect(amountProblem('0.00001')).toMatch(/4 decimal places/)
    expect(amountProblem('1234567890123456')).toMatch(/too large/)
  })

  it('takes a sign only where one is wanted', () => {
    expect(amountProblem('-5')).toMatch(/minus/)
    expect(amountProblem('-5', { signed: true })).toBeUndefined()
    expect(amountProblem('0', { signed: true })).toMatch(/zero/)
  })
})

describe('splitShared', () => {
  it('rounds the other part HALF_UP and leaves the own part the rest', () => {
    expect(splitShared('725.55', '50')).toEqual({ own: '362.77', other: '362.78' })
    expect(splitShared('10', '33.33')).toEqual({ own: '6.67', other: '3.33' })
  })

  it('splits a refund the same way, rounding away from zero', () => {
    expect(splitShared('-725.55', '50')).toEqual({ own: '-362.77', other: '-362.78' })
  })
})

describe('sharePercentOf', () => {
  it('finds the percentage a stored split was made with', () => {
    expect(sharePercentOf('725.55', '362.78')).toBe('50')
    expect(sharePercentOf('-725.55', '-362.78')).toBe('50')
    expect(sharePercentOf('100', '12.34')).toBe('12.34')
  })

  it('gives up on parts no percentage produces', () => {
    expect(sharePercentOf('0.01', '0.001')).toBeUndefined()
  })
})

it('checks share percentages', () => {
  expect(percentProblem('50')).toBeUndefined()
  expect(percentProblem('0')).toMatch(/more than 0/)
  expect(percentProblem('100')).toMatch(/less than 100/)
  expect(percentProblem('12.345')).toMatch(/2 decimal/)
})

it('sums without floating-point errors', () => {
  expect(sum(['0.1', '0.2', '-0.3'])).toBe('0')
  expect(sum(['9007199254740993.01', '0.01'])).toBe('9007199254740993.02')
})

it('formats every digit of large amounts', () => {
  // 2^53 + 1 isn't a double; formatted from the string, the last digit survives.
  expect(formatMoney('9007199254740993.25', 'EUR').replace(/\D/g, '')).toBe('900719925474099325')
  expect(formatMoney('0.005', 'EUR').replace(/\D/g, '')).toBe('0005')
})
