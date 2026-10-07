import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'

// D-119: a number, id, amount or date looked for as a substring can match inside another value. `toContainText('32.10')`
// finds 132.10, 1,032.10 and 32.105; the specs compare a whole number with `wholeNumber('32.10')` (fixtures.ts) instead.
// This test reads the specs' source and fails on a substring match whose needle is a string made of a number: no
// letter in it, at least one digit.

const root = join(dirname(fileURLToPath(import.meta.url)), '..')

/** The substring matchers: Playwright's `toContainText` and `hasText`, and `toContain` and `toMatch` of any text. */
const NEEDLE = /(?:\.(?:toContainText|toContain|toMatch)\(|\bhasText:\s*)\s*(['"`])([^'"`]*)\1/g

/** The number-like string literals the source passes to a substring matcher. */
export function numberNeedles(source: string): string[] {
  return [...source.matchAll(NEEDLE)].map((m) => m[2]).filter((needle) => /\d/.test(needle) && !/[A-Za-z]/.test(needle))
}

function sources(): string[] {
  const specs = readdirSync(join(root, 'specs')).filter((name) => name.endsWith('.ts')).map((name) => join('specs', name))
  return [...specs, 'family-steps.ts', 'fixtures.ts']
}

test('no spec looks for a number as a substring of rendered text', () => {
  const found = sources().flatMap((file) => numberNeedles(readFileSync(join(root, file), 'utf8')).map((n) => `${file}: '${n}'`))
  assert.deepEqual(found, [], 'use wholeNumber(...) from fixtures.ts for a number or an amount (D-119)')
})

test('the check finds the substring matches it is meant to', () => {
  const bad = [
    "await expect(page.locator('main')).not.toContainText('32.10')",
    "await expect(row).toContainText('€12.34')",
    'expect(text).toContain("2026-10-07")',
    "page.getByRole('row').filter({ hasText: '€40.00' })",
    "expect(rates).toMatch('95.5')",
  ]
  assert.deepEqual(bad.flatMap(numberNeedles), ['32.10', '€12.34', '2026-10-07', '€40.00', '95.5'])
})

test('the check leaves alone what it isn’t about', () => {
  const fine = [
    "await expect(row).toContainText(wholeNumber('12.34'))",
    "await expect(share).toHaveText('€50.00')",
    "await expect(this.month).toContainText('Expenses €100.00 · Incomes €60.00')",
    "await expect(page.locator('main')).not.toContainText('Corner shop')",
    "page.getByRole('row').filter({ hasText: '€' })",
    "page.getByRole('row').filter({ hasText: wholeNumber('40.00') })",
  ]
  assert.deepEqual(fine.flatMap(numberNeedles), [])
})
