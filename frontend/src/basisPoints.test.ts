import { describe, expect, it } from 'vitest'
import { basisPointsToPercent, equalShares, percentToBasisPoints } from './basisPoints'

describe('percentToBasisPoints', () => {
  it.each([
    ['33.33', 3333],
    ['33.3', 3330],
    ['100', 10000],
    ['0', 0],
    ['0.01', 1],
    ['100.00', 10000],
    ['50.5', 5050],
    [' 25 ', 2500],
  ])('reads %j as %i', (text, basisPoints) => {
    expect(percentToBasisPoints(text)).toBe(basisPoints)
  })

  it.each(['33.333', '-1', '100.01', 'abc', '', '1e2', '1000', '33,33', '.5', '33.', '+5', '0x10', 'Infinity', ' '])(
    'refuses %j', (text) => {
      expect(percentToBasisPoints(text)).toBeNull()
    })
})

describe('basisPointsToPercent', () => {
  it.each([
    [3333, '33.33'],
    [10000, '100.00'],
    [3330, '33.30'],
    [0, '0.00'],
    [1, '0.01'],
    [5, '0.05'],
    [99, '0.99'],
  ])('shows %i as %j', (basisPoints, text) => {
    expect(basisPointsToPercent(basisPoints)).toBe(text)
  })

  it('reads back what it shows', () => {
    for (let basisPoints = 0; basisPoints <= 10000; basisPoints++) {
      expect(percentToBasisPoints(basisPointsToPercent(basisPoints))).toBe(basisPoints)
    }
  })

  it.each([-1, 0.5, Number.NaN])('refuses %d', (basisPoints) => {
    expect(() => basisPointsToPercent(basisPoints)).toThrow(RangeError)
  })
})

describe('equalShares', () => {
  it('splits 10000 into shares that differ by at most 1 and add up', () => {
    expect(equalShares(1)).toEqual([10000])
    expect(equalShares(2)).toEqual([5000, 5000])
    expect(equalShares(3)).toEqual([3334, 3333, 3333])
    expect(equalShares(7).reduce((a, b) => a + b, 0)).toBe(10000)
    expect(equalShares(0)).toEqual([])
  })
})
