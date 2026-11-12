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
  return [...specs, 'family-steps.ts', 'fixtures.ts', 'export-steps.ts']
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

// D-586 (UX-13-doc): the demo is loaded only through `loadDemoData` of fixtures.ts, which waits for `POST /api/demo-data`
// and for the dashboard's headline. The empty ledger has the heading "Dashboard" too, so a spec that clicks "Load demo
// data" itself and waits for that heading reads the page before the demo is there (production's run 1 of UX-13). This
// test reads every spec and helper and fails on any line that names the button, but the helper's own.

const DEMO_BUTTON = /Load demo data/i

/** The lines of a source that name the demo's button: `file:line: text`. */
export function demoClicks(file: string, source: string): string[] {
  return source.split('\n').flatMap((line, i) => (DEMO_BUTTON.test(line) ? [`${file}:${i + 1}: ${line.trim()}`] : []))
}

/** The helper's own line in fixtures.ts, the one place allowed to name the button. */
const HELPER_LINE = "await page.getByRole('button', { name: 'Load demo data' }).click()"

test('only loadDemoData clicks "Load demo data"', () => {
  const found = sources().flatMap((file) => demoClicks(file, readFileSync(join(root, file), 'utf8')))
  const helper = found.filter((line) => line.startsWith('fixtures.ts:') && line.endsWith(HELPER_LINE))
  assert.equal(helper.length, 1, 'loadDemoData in fixtures.ts clicks the button once')
  assert.deepEqual(found.filter((line) => !helper.includes(line)), [],
    'load the demo with loadDemoData(page) from fixtures.ts, which waits for it (D-586)')
})

test('the demo check finds a spec that clicks the button itself', () => {
  const spec = [
    "await page.goto('/')",
    "await page.getByRole('button', { name: 'Load demo data' }).click()",
    "await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()",
    'await page.getByText(/load demo data/i).click()',
    'await loadDemoData(page)',
  ].join('\n')
  assert.deepEqual(demoClicks('specs/x.spec.ts', spec), [
    "specs/x.spec.ts:2: await page.getByRole('button', { name: 'Load demo data' }).click()",
    'specs/x.spec.ts:4: await page.getByText(/load demo data/i).click()',
  ])
})
