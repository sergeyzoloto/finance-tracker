import type { Page } from '@playwright/test'
import { fillDate } from '../dates.ts'
import { deleteAllMyData, expect, loadDemoData, shot, test, today, type Session } from '../fixtures.ts'

// UX-11's Accounts page (D-525, D-526) with A, on the demo:
// 1. At 1280 px and at 800 px, Assets', Liabilities' and Equity's columns have the same left edges and widths, without
//    and with a comparison.
// 2. "Balances on" a day of last month, then "Compare": the day before that month's start by default. Every figure
//    equals `/api/reports/balances?asOf=` for its day, the earlier day's column first; "Change" is later − earlier and
//    "%" is against the earlier day, "—" from 0 or across 0. The URL keeps both days, and so does a reload.
// 3. At 375 px (local-narrow) nothing scrolls sideways, without and with the comparison; screenshots. Since UX-12
//    (D-552), compared at 375 px each account's, each total's and a person's figures stand under its name in two columns:
//    the earlier and the later day's balance on one line, Change and % on the next.
// Delete all my data at the end.

test('accounts on a day, and compared', async ({ as }) => {
  const a = await as('A')
  const { page } = a
  const narrow = page.viewportSize()?.width === 375
  await deleteAllMyData(a)
  await page.goto('/')
  await loadDemoData(page)
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible({ timeout: 30_000 })

  // 1. The columns line up (not at 375 px, where each account is a block of lines).
  await page.goto('/accounts')
  await expect(page.getByRole('rowgroup', { name: 'Assets', exact: true })).toBeVisible()
  if (!narrow) {
    for (const width of [1280, 800]) {
      await page.setViewportSize({ width, height: 900 })
      await expectColumnsLineUp(page, `${width} px`)
    }
  }
  await shot(page, 'ux11-04-accounts')
  await expectNoSidewaysScroll(page, '/accounts')

  // 2. A day of last month, then the comparison.
  const day = today()
  const [year, month] = day.split('-').map(Number)
  const on = new Date(Date.UTC(year, month - 2, 15)).toISOString().slice(0, 10)
  const before = new Date(Date.UTC(year, month - 2, 0)).toISOString().slice(0, 10)
  await fillDate(page.getByLabel('Balances on'), on)
  await expect(page).toHaveURL(new RegExp(`[?&]on=${on}(&|$)`))
  await expectFigures(a, [on])
  await page.getByRole('button', { name: 'Compare', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`[?&]compare=${before}(&|$)`))
  await expect(page).toHaveURL(new RegExp(`[?&]on=${on}(&|$)`))
  await expectFigures(a, [before, on])
  await page.reload()
  await expect(page).toHaveURL(new RegExp(`[?&]on=${on}&compare=${before}$`))
  await expectFigures(a, [before, on])
  if (!narrow) {
    for (const width of [1280, 800]) {
      await page.setViewportSize({ width, height: 900 })
      await expectColumnsLineUp(page, `${width} px with a comparison`)
    }
  }
  // 3. At 375 px.
  if (narrow) await expectTwoColumnsUnderTheName(page)
  await shot(page, 'ux11-05-accounts-compared')
  await expectNoSidewaysScroll(page, '/accounts with a comparison')
  await page.getByRole('button', { name: 'End the comparison' }).click()
  await expect(page).not.toHaveURL(/compare=/)

  await deleteAllMyData(a)
})

/**
 * UX-12 (D-552): at 375 px with a comparison, every account row and type total has its name on a line of its own,
 * then the earlier and the later day's balance side by side, then Change and % side by side below them, the first
 * column's left edge the name's.
 */
async function expectTwoColumnsUnderTheName(page: Page) {
  const rows = await page.locator('table.accounts > tbody.account-type > tr:not(.type-head):not(.details):not(.row-error)')
    .evaluateAll((trs) => trs.map((tr) => {
      const box = (el: Element | null) => {
        const r = el!.getBoundingClientRect()
        return { left: Math.round(r.left), top: Math.round(r.top), bottom: Math.round(r.bottom) }
      }
      const figures = [...tr.querySelectorAll(':scope > td[data-label]')].map(box)
      return { text: tr.textContent ?? '', name: box(tr.firstElementChild), figures }
    }))
  expect(rows.length).toBeGreaterThan(3)
  for (const { text, name, figures } of rows) {
    const what = `compared at 375 px: ${text.slice(0, 40)}`
    expect(figures, what).toHaveLength(4)
    const [earlier, later, change, percent] = figures
    expect(earlier.top, `${what}: the balances under the name`).toBeGreaterThanOrEqual(name.bottom)
    expect(later.top, `${what}: the two balances on one line`).toBe(earlier.top)
    expect(later.left, `${what}: the later balance right of the earlier`).toBeGreaterThan(earlier.left)
    expect(change.top, `${what}: Change below the balances`).toBeGreaterThanOrEqual(earlier.bottom)
    expect(percent.top, `${what}: Change and % on one line`).toBe(change.top)
    expect(change.left, `${what}: Change under the earlier balance`).toBe(earlier.left)
    expect(percent.left, `${what}: % under the later balance`).toBe(later.left)
    expect(earlier.left, `${what}: the first column at the name's edge`).toBe(name.left)
  }
}

/**
 * Each type's first account row has the same cells at the same left edges and widths as every other type's, so that
 * the columns line up (D-525).
 */
async function expectColumnsLineUp(page: Page, what: string) {
  const boxes = await page.locator('table.accounts > tbody.account-type').evaluateAll((groups) => groups.map((g) => {
    const row = g.querySelector(':scope > tr:not(.type-head):not(.type-total)')!
    return [...row.children].map((cell) => {
      const box = cell.getBoundingClientRect()
      return `${Math.round(box.left)}+${Math.round(box.width)}`
    })
  }))
  expect(boxes.length, `${what}: the three types`).toBe(3)
  expect(boxes[1], `${what}: Liabilities’ columns as Assets’`).toEqual(boxes[0])
  expect(boxes[2], `${what}: Equity’s columns as Assets’`).toEqual(boxes[0])
}

/** An amount as shown ("€1,234.50", "-$3.00", "+€0.01") or as the API sends it, in ten-thousandths. */
function units(text: string): bigint {
  const match = /^([+\-−]?)[€$]?(-?)([\d,]+)(?:\.(\d{1,4}))?$/.exec(text.replace(/[  \s]/g, ''))
  if (!match) throw new Error(`Not an amount: ${text}`)
  const value = BigInt(match[3].replace(/,/g, '') + (match[4] ?? '').padEnd(4, '0'))
  return match[1] === '-' || match[1] === '−' || match[2] === '-' ? -value : value
}

/** "%" as D-526 has it: against the earlier day, one decimal half up; "—" from 0, across 0, or with nothing before. */
function percentText(earlier: bigint | null, later: bigint | null): string {
  if (earlier === null || earlier === 0n) return '—'
  const now = later ?? 0n
  if ((now > 0n && earlier < 0n) || (now < 0n && earlier > 0n)) return '—'
  const magnitude = earlier < 0n ? -earlier : earlier
  const tenths = ((now - earlier) * 2000n) / magnitude
  const rounded = tenths >= 0n ? (tenths + 1n) / 2n : -((-tenths + 1n) / 2n)
  const sign = rounded > 0n ? '+' : rounded < 0n ? '−' : ''
  const abs = rounded < 0n ? -rounded : rounded
  return `${sign}${abs / 10n}.${abs % 10n}%`
}

/**
 * Every account's cells equal the API's balances for each day, by currency, in date order; while comparing, Change and
 * % follow D-526.
 */
async function expectFigures({ page, context }: Session, days: string[]) {
  const answers = await Promise.all(days.map(async (d) =>
    (await (await context.request.get(`/api/reports/balances?asOf=${d}`)).json()) as
      { accountName: string; currency: string; balance: string }[]))
  const table = page.locator('table.accounts')
  await expect(table).not.toHaveClass(/stale/)
  if (days.length === 2) {
    await expect(table.locator('thead th')).toHaveCount(6)
  } else {
    await expect(table.locator('thead th')).toHaveCount(3)
  }
  const rows = await table.locator('tbody.account-type > tr:not(.type-head):not(.type-total)').evaluateAll((trs) =>
    trs.filter((tr) => tr.querySelector('a')).map((tr) => ({
      name: tr.querySelector('a')!.textContent!,
      cells: [...tr.querySelectorAll(':scope > td.amount')].map((td) => [...td.children].map((c) => c.textContent ?? '')),
    })))
  expect(rows.length, 'the accounts listed').toBeGreaterThan(0)
  for (const row of rows) {
    const own = answers.map((answer) => answer.filter((b) => b.accountName === row.name))
    const currencies = [...new Set(own.flat().map((b) => b.currency))].sort()
    if (days.length === 1) {
      expect(row.cells[0].filter((t) => t !== '—').map(units), `${row.name} on ${days[0]}`)
        .toEqual(own[0].sort((x, y) => x.currency.localeCompare(y.currency)).map((b) => units(b.balance)))
      continue
    }
    if (currencies.length === 0) continue
    currencies.forEach((currency, i) => {
      const earlier = own[0].find((b) => b.currency === currency)
      const later = own[1].find((b) => b.currency === currency)
      const e = earlier ? units(earlier.balance) : null
      const l = later ? units(later.balance) : null
      const what = `${row.name} in ${currency}`
      expect(row.cells[0][i] === '—' ? null : units(row.cells[0][i]), `${what} on ${days[0]}`).toEqual(e)
      expect(row.cells[1][i] === '—' ? null : units(row.cells[1][i]), `${what} on ${days[1]}`).toEqual(l)
      expect(units(row.cells[2][i]), `${what}: the change`).toEqual((l ?? 0n) - (e ?? 0n))
      expect(row.cells[3][i].replace(/[  ]/g, ''), `${what}: %`).toBe(percentText(e, l))
    })
  }
}

/** At 375 px, the page is no wider than the window. */
async function expectNoSidewaysScroll(page: Page, what: string) {
  if (page.viewportSize()?.width !== 375) return
  const [scroll, client] = await page.evaluate(() => [document.documentElement.scrollWidth, document.documentElement.clientWidth])
  expect(scroll, `${what}: no sideways scroll at 375 px`).toBeLessThanOrEqual(client)
}
