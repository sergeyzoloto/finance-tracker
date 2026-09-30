// A family budget's custom split rule is kept in basis points, integers out of 10000 (D-12), and shown as percentages
// with two decimals: "33.33" is 3333, and 3333 is "33.33". Only integers and digit strings take part, never a
// floating-point number, so no share is ever off by a rounding error.

/** All of a split, 100.00 %. */
export const WHOLE = 10_000

const PERCENT = /^(\d{1,3})(?:\.(\d{1,2}))?$/

/**
 * The basis points of a percentage typed with at most two decimals, from "0" to "100": "33.3" → 3330. Null for
 * anything else: more decimals, a sign, an exponent, a comma, letters, or nothing.
 */
export function percentToBasisPoints(text: string): number | null {
  const match = PERCENT.exec(text.trim())
  if (!match) return null
  const basisPoints = Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'))
  return basisPoints <= WHOLE ? basisPoints : null
}

/** A number of basis points as a percentage with two decimals: 3333 → "33.33", 10000 → "100.00", 5 → "0.05". */
export function basisPointsToPercent(basisPoints: number): string {
  if (!Number.isSafeInteger(basisPoints) || basisPoints < 0) throw new RangeError(`Not a number of basis points: ${basisPoints}`)
  const digits = String(basisPoints).padStart(3, '0')
  return `${digits.slice(0, -2)}.${digits.slice(-2)}`
}

/** 10000 split into `count` shares that differ by at most 1, the larger ones first: 3 → 3334, 3333, 3333. */
export function equalShares(count: number): number[] {
  if (count <= 0) return []
  const share = (WHOLE - (WHOLE % count)) / count
  return Array.from({ length: count }, (_, i) => share + (i < WHOLE % count ? 1 : 0))
}
