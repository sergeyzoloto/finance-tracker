import { WHOLE } from './basisPoints'

// D-12's split of a family expense into its members' shares, as the backend makes it (ledger.family.ShareSplit), to
// show every member's amount before saving. Amounts are whole numbers of the currency's minor unit (minorUnits.ts), and
// every step is integer arithmetic. The server's answer is what counts; this only previews it.

/** A member's share in the minor unit, with the percentage it was split by, in basis points, where there is one. */
export interface SplitShare { memberId: number; amount: bigint; basisPoints: number | null }

/** A member's weight: basis points out of 10000 for percentages, or 1 each for equal shares. */
export interface Weight { memberId: number; weight: number; basisPoints: number | null }

/**
 * Splits `amount` by the weights, given in the members' join order. Every share is the amount times its weight, cut
 * down to the minor unit, so that together they never exceed the amount; what that leaves goes to the member with the
 * largest weight, on a tie to the payer, and otherwise to the first of them in join order. So the shares always add up
 * to the amount, and 10.01 split 50/50 gives the payer 5.01.
 */
export function byWeights(amount: bigint, weights: Weight[], payerId: number | null): SplitShare[] {
  const total = weights.reduce((sum, w) => sum + BigInt(w.weight), 0n)
  if (total <= 0n) return []
  const shares = weights.map((w) => ({ memberId: w.memberId, amount: (amount * BigInt(w.weight)) / total, basisPoints: w.basisPoints }))
  const rest = amount - shares.reduce((sum, s) => sum + s.amount, 0n)
  const largest = Math.max(...weights.map((w) => w.weight))
  const tied = weights.map((w, i) => ({ ...w, i })).filter((w) => w.weight === largest)
  const recipient = (tied.find((w) => w.memberId === payerId) ?? tied[0]).i
  shares[recipient].amount += rest
  return shares
}

/** Equal shares of the members, given in join order. */
export const equalSplit = (amount: bigint, memberIds: number[], payerId: number | null) =>
  byWeights(amount, memberIds.map((memberId) => ({ memberId, weight: 1, basisPoints: null })), payerId)

/** Shares by percentage, in basis points summing to 10000, of the members given in join order. */
export const percentSplit = (amount: bigint, basisPoints: { memberId: number; basisPoints: number }[], payerId: number | null) =>
  byWeights(amount, basisPoints.map((b) => ({ memberId: b.memberId, weight: b.basisPoints, basisPoints: b.basisPoints })), payerId)

/** The whole amount on one member. */
export const oneMemberSplit = (amount: bigint, memberId: number): SplitShare[] => [{ memberId, amount, basisPoints: null }]

/**
 * A share's part of the amount in basis points, rounded half up, to show a share that was split by amount or equally
 * as a percentage: 5.01 of 10.01 is 5005 (50.05 %). 0 of an amount of 0.
 */
export function basisPointsOf(share: bigint, amount: bigint): number {
  if (amount <= 0n) return 0
  return Number((share * BigInt(WHOLE) * 2n + amount) / (amount * 2n))
}
