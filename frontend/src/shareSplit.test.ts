import { describe, expect, it } from 'vitest'
import { basisPointsOf, byWeights, equalSplit, oneMemberSplit, percentSplit } from './shareSplit'

// ShareSplitTests' cases (backend, ledger.family), in the minor unit.
const PAYER = 1
const PARTNER = 2
const KID = 3

const amounts = (shares: { memberId: number; amount: bigint }[]) => shares.map((s) => [s.memberId, s.amount])

describe('the split preview (D-12)', () => {
  it('gives the payer 5.01 of 10.01 split 50/50', () => {
    expect(percentSplit(1001n, [{ memberId: PARTNER, basisPoints: 5000 }, { memberId: PAYER, basisPoints: 5000 }], PAYER))
      .toEqual([{ memberId: PARTNER, amount: 500n, basisPoints: 5000 }, { memberId: PAYER, amount: 501n, basisPoints: 5000 }])
    expect(amounts(equalSplit(1001n, [PARTNER, PAYER], PAYER))).toEqual([[PARTNER, 500n], [PAYER, 501n]])
  })

  it('splits 100.00 among three', () => {
    // The payer joined second: the tie goes to them all the same.
    expect(amounts(equalSplit(10000n, [PARTNER, PAYER, KID], PAYER))).toEqual([[PARTNER, 3333n], [PAYER, 3334n], [KID, 3333n]])
    // A payer who isn't among them leaves it to the first by join order.
    expect(amounts(equalSplit(10000n, [PARTNER, KID], PAYER))).toEqual([[PARTNER, 5000n], [KID, 5000n]])
    expect(amounts(equalSplit(2n, [PARTNER, KID, PAYER], 99))).toEqual([[PARTNER, 2n], [KID, 0n], [PAYER, 0n]])
  })

  it('splits a currency without minor units in whole units', () => {
    expect(amounts(equalSplit(1001n, [PARTNER, PAYER], PAYER))).toEqual([[PARTNER, 500n], [PAYER, 501n]])
    expect(amounts(percentSplit(100n, [
      { memberId: PAYER, basisPoints: 3333 }, { memberId: PARTNER, basisPoints: 3333 }, { memberId: KID, basisPoints: 3334 },
    ], PAYER))).toEqual([[PAYER, 33n], [PARTNER, 33n], [KID, 34n]])
  })

  it('gives the remainder to the largest share, not to a payer with 0 %', () => {
    expect(amounts(percentSplit(1001n, [{ memberId: PAYER, basisPoints: 0 }, { memberId: PARTNER, basisPoints: 10000 }], PAYER)))
      .toEqual([[PAYER, 0n], [PARTNER, 1001n]])
    expect(amounts(percentSplit(5n, [{ memberId: PAYER, basisPoints: 3000 }, { memberId: PARTNER, basisPoints: 7000 }], PAYER)))
      .toEqual([[PAYER, 1n], [PARTNER, 4n]])
  })

  it('puts the whole amount on one member', () => {
    expect(oneMemberSplit(4250n, KID)).toEqual([{ memberId: KID, amount: 4250n, basisPoints: null }])
  })

  it('always adds up to the amount', () => {
    const splits = [
      [{ memberId: PAYER, weight: 1, basisPoints: null }, { memberId: PARTNER, weight: 1, basisPoints: null }, { memberId: KID, weight: 1, basisPoints: null }],
      [{ memberId: PAYER, weight: 3333, basisPoints: 3333 }, { memberId: PARTNER, weight: 3333, basisPoints: 3333 }, { memberId: KID, weight: 3334, basisPoints: 3334 }],
      [{ memberId: PAYER, weight: 1, basisPoints: 1 }, { memberId: PARTNER, weight: 9999, basisPoints: 9999 }],
    ]
    for (let cents = 1n; cents <= 2000n; cents += 7n) {
      for (const weights of splits) {
        expect(byWeights(cents, weights, PARTNER).reduce((sum, s) => sum + s.amount, 0n)).toBe(cents)
      }
    }
  })

  it('needs a weight', () => {
    expect(percentSplit(100n, [{ memberId: PAYER, basisPoints: 0 }], PAYER)).toEqual([])
  })

  it('shows a share of an amount as basis points, rounded half up', () => {
    expect(basisPointsOf(501n, 1001n)).toBe(5005)
    expect(basisPointsOf(500n, 1001n)).toBe(4995)
    expect(basisPointsOf(1n, 3n)).toBe(3333)
    expect(basisPointsOf(2n, 3n)).toBe(6667)
    expect(basisPointsOf(0n, 0n)).toBe(0)
  })
})
