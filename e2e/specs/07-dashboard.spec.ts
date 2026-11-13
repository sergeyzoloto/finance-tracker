import { dateFormatOf, expectDate, fillDate, inPattern, shownDay } from '../dates.ts'
import { createTheBudget, inviteSomeoneNew, joinThroughTheLink } from '../family-steps.ts'
import type { Locator } from '@playwright/test'
import {
  deleteAllMyData, expect, loadDemoData, mainMenu, openSettings, shot, test, today, userMenu, wholeNumber, type Session, type Watch,
} from '../fixtures.ts'

// UX-1's dashboard with A (and B with the family budget on), every entry dated today:
// 1. A's personal expense of 12.34 on Groceries; the dashboard opens in the base currency (D-297): no currency in the
//    URL, the base currency's button pressed, and every report asked in BASE.
// 2. The Period list opens at "Month of the latest entry", this month here (D-521); a preset (D-296, D-432) changes the
//    URL and the range shown in From and To; the Balances card names its day, To (D-430, D-431); "This month" is kept
//    by name (`period=this-month`).
// 3. With E2E_FAMILY=on: A creates "Dashboard check" (Groceries merged in), invites B, who joins; A pays a family expense
//    of 30.00. Since UX-6 (D-420) "Show" chooses the budget and "Records" the records: A's share of 15.00 is under the
//    budget's "All records" and "My records", and not under "Personal", which keeps A's own 12.34; B's share is under
//    the budget's "Others' records", not under "My records" nor "Personal" (D-302).
// 4. A chooses DD.MM.YYYY in Settings (D-304): a date in the entry list and the new entry's date field follow it, a date
//    typed in it is taken; A goes back to "As the browser shows it".
// 5. At 375 px the dashboard doesn't scroll sideways.
// 6. UX-2 (D-351, D-352, D-356): with the demo loaded (and "Demo household" with E2E_FAMILY=on), `/` opens the month of
//    the latest entry, this month (UX-11, D-521); at 375 px the entry
//    list, the categories and the family budget's categories don't scroll sideways; in the base currency the
//    Balances' Assets total reads as "You have" (D-348), as it does in step 1.
// 7. UX-3 (D-366, D-367), on the demo at 1280 px, in the base currency from the 1st of the month to today: "Result for
//    the period" reads as the Net of "Income and expenses"; the equity lines ("Accumulated reserve", the result and
//    the system accounts) add up to Equity's total; on the Accounts page, archiving "Current account", which holds
//    money, is refused with its message by the account, and an empty account archives and is restored.
// 8. UX-4 (D-382): on the demo, an account archived empty and then given €0.01 (entries may still post to an archived
//    account, D-376) is listed with "Show archived" off, its €0.01 shown, and the Assets total matches the rows' cents;
//    at 375 px, with "Show archived" on, a "By person" account's details open and the refusal's row shown, the
//    Accounts page doesn't scroll sideways and every button is inside the screen.
// 9. UX-5 (D-393, D-396, D-399), on the demo at 1280 px in the base currency: "Previous month" heads the cash flow
//    table with the period, the year earlier and the previous period, its chart by range is oldest first (D-413; since
//    UX-16, D-649, its rows' roles top to bottom by their place in the DOM), and one category's period, previous
//    period and change in % equal the test's own computation from those two ranges' `/api/reports/cash-flow` answers;
//    "This month" says "to date" while it runs; "Last 3 months" offers "Compare with", whose "Previous period" sets
//    `compare=previous` and changes the chart's legend from "a year earlier" to "previous period"; since UX-16
//    (D-650) each month's bars carry a marker exactly where the drawn comparison's month has a figure above 0 in that
//    range's own `/api/reports/cash-flow` answer.
// 10. UX-6 (D-419, D-420), on the demo at 1280 px with E2E_FAMILY=on: "Demo household"'s categories in the cash flow
//    table carry the shared icon, named "Shared: Demo household", with no name after it (one budget); choosing the
//    budget in "Show" sets `scope=family&family=<id>` and shows "Records" at "All records"; "My records" and
//    "Others’ records" change the scope and keep the budget; each asks the API for its scope; "Personal" hides
//    "Records" again. A screenshot at 1280 px and at 375 px with the budget chosen.
// 11. UX-7 (D-433 to D-438) and UX-10 (D-498), on the demo: the header's main navigation (row 2), its links and the
//    current one, Entries by its tab; the user's menu to Settings, which opens Time zone, and each of Settings' parts by
//    its own navigation; an old `/export` link lands on its part; with E2E_FAMILY=on, "Demo household" by its tab, which
//    is current inside the budget, with no "New entry" and no "← My finances". At 375 px the wordmark, "New entry" and
//    the user's button share one top, the user's panel is inside the screen, the navigation scrolls inside itself and
//    the page doesn't scroll sideways; screenshots.
// 12. UX-13 (D-569, D-571), on the demo with "Previous month", in the base currency and in each currency, at 1280 and
//    375 px: the headline strip's five figures in their order and layout (one row from 64rem; under 40rem Net worth
//    first across, then You have and You owe, then Income and Expenses); Income and Expenses equal to the table's
//    totals; daily spending's slots one per day of the month, its days' expenses in `/api/reports/daily-cash-flow`'s
//    answer adding up to Expenses, its biggest day as that answer gives it; "Personal" in "Show" changing the strip's
//    figures, its line under them and the daily request's scope. Since D-584 also "This month", running: a slot per day
//    of the month, those after today dashed, the days asked up to today adding up to Expenses.
// 13. UX-14 (D-593 to D-596), on the demo with "Previous month": at 1280 px Balances to the right of "Income and
//    expenses" with the same top, and daily spending beside the chart; since UX-16 (D-654) at 1024 px one column, in
//    D-593's order, with daily spending beside the chart, the card being wider than 40rem (D-594); at 375 px one
//    column, in D-593's order. In the base currency and in each currency, every income and expense line's
//    Share equals the spec's own computation from the period's `/api/reports/cash-flow` answer the page received; in
//    the base currency the Rates card's rows are that day's net worth's `rates`, and in each currency there is no card.
// 14. UX-16 (D-647 to D-652), on the demo in the base currency: a custom range of more than 24 months draws a row
//    per calendar year it touches, with no "Compare with" and no marker; at 375, 1024 and 1280 px, on "This month",
//    "Previous month", "Last 3 months" and "Past year", the dashboard doesn't scroll sideways, every label and value of
//    the chart is inside the chart's box, no two rows' boxes overlap, and no value's box overlaps a marker.
// Delete all my data for both at the end.

const BUDGET = 'Dashboard check'

test('dashboard', async ({ as, family, target, watch }) => {
  const a = await as('A')
  await deleteAllMyData(a)
  const { page } = a
  const day = today()

  // 1. A personal expense, then the dashboard in the base currency.
  await addExpense(a, '12.34', 'Dashboard personal')
  const cashFlow = page.waitForRequest((r) => new URL(r.url()).pathname === '/api/reports/cash-flow')
  await page.goto('/')
  expect(new URL((await cashFlow).url()).searchParams.get('currency')).toBe('BASE')
  const amounts = page.getByRole('group', { name: 'Amounts' })
  await expect(amounts.getByRole('button', { name: 'EUR (base)' })).toHaveAttribute('aria-pressed', 'true')
  await expect(amounts.getByRole('button', { name: 'Each currency' })).toHaveAttribute('aria-pressed', 'false')
  expect(new URL(page.url()).searchParams.has('currency')).toBe(false)
  await expect(page.getByRole('region', { name: 'Headline figures' }).getByText('You have')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Shared budget' })).toHaveCount(0)
  await expectAssetsTotalIsYouHave(a)
  await shot(page, 'dashboard-01-base')

  // 2. A preset: the URL and the range, and the day of the balances.
  const period = page.getByRole('combobox', { name: 'Period' })
  // UX-11 (D-521): the month of the latest entry by default, here this month, A's one entry being today's.
  await expect(period).toHaveValue('latest-entry-month')
  await expectDate(page.getByLabel('From', { exact: true }), `${day.slice(0, 8)}01`)
  await period.selectOption({ label: 'Previous month' })
  await expect(page).toHaveURL(/[?&]period=previous-month(&|$)/)
  await expect(period).toHaveValue('previous-month')
  const [year, month] = day.split('-').map(Number)
  const first = new Date(Date.UTC(year, month - 2, 1)).toISOString().slice(0, 10)
  const last = new Date(Date.UTC(year, month - 1, 0)).toISOString().slice(0, 10)
  await expectDate(page.getByLabel('From', { exact: true }), first)
  await expectDate(page.getByLabel('To', { exact: true }), last)
  await expect(page.getByRole('heading', { name: `Balances on ${shownDay(last, null)}`, exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: /Balances on/ })).toHaveCount(0)
  // "This month" stays a preset of its own, kept by name; the default leaves the URL without one.
  await period.selectOption({ label: 'This month' })
  await expect(page).toHaveURL(/[?&]period=this-month(&|$)/)
  await expectDate(page.getByLabel('From', { exact: true }), `${day.slice(0, 8)}01`)
  await fillDate(page.getByLabel('From', { exact: true }), `${day.slice(0, 8)}02`)
  await expect(period).toHaveValue('custom')
  await expect(page).toHaveURL(/[?&]from=/)
  await period.selectOption({ label: 'Month of the latest entry' })
  await expect(page).not.toHaveURL(/[?&](from|period)=/)

  // 3. The scopes, with a family budget.
  if (family === 'on') {
    const b = await as('B')
    await deleteAllMyData(b)
    const ledgerId = await createTheBudget(a, BUDGET, 'dashboard')
    const link = await inviteSomeoneNew(a, 'dashboard')
    await joinThroughTheLink(b, link, BUDGET, ledgerId, target.roles.B.name, 'dashboard')
    await page.goto(`/family/${ledgerId}/expenses/new`)
    await page.getByLabel('Category').selectOption({ label: 'Groceries' })
    await page.getByLabel('Paid from').selectOption({ label: 'Specify later' })
    await page.getByLabel('Amount (EUR)').fill('30.00')
    await page.getByRole('button', { name: 'Add the expense' }).click()
    await expect(page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()

    await expectScope(a, BUDGET, '15.00', true)
    await expectScope(a, BUDGET, '15.00', true, 'My records')
    await expectScope(a, 'Personal', '15.00', false)
    await expect(page.getByRole('table', { name: 'Cash flow in EUR' })).toContainText(wholeNumber('12.34'))
    await expect(page.getByRole('link', { name: 'Whole family budget →' })).toHaveCount(0)
    await expectScope(a, BUDGET, '15.00', true, 'My records')
    await expect(page.getByRole('link', { name: 'Whole family budget →' }))
      .toHaveAttribute('href', new RegExp(`^/family/${ledgerId}/report\\?from=`))
    await shot(page, 'dashboard-02-family-mine')
    await expectScope(b, BUDGET, '15.00', true, 'Others’ records')
    await expectScope(b, BUDGET, '15.00', false, 'My records')
    await expectScope(b, 'Personal', '15.00', false)
    await shot(b.page, 'dashboard-03-family-others')
    await deleteAllMyData(b)
  }

  // 4. The date format, shown and typed.
  await page.goto('/settings/date-format')
  const format = page.getByLabel(/^Show and type dates as/)
  await format.selectOption('DD.MM.YYYY')
  await expect(page.getByRole('status').filter({ hasText: 'Saved. Today shows as' }))
    .toHaveText(`Saved. Today shows as ${inPattern(day, 'DD.MM.YYYY')}.`)
  expect(await dateFormatOf(page)).toBe('DD.MM.YYYY')
  await page.goto('/entries')
  const row = page.getByRole('row').filter({ hasText: 'Dashboard personal' })
  await expect(row.getByRole('link', { name: shownDay(day, 'DD.MM.YYYY') })).toBeVisible()
  await page.goto('/entries/new')
  const date = page.getByLabel(/^Date/)
  await expect(date).toHaveAttribute('placeholder', 'DD.MM.YYYY')
  await expect(date).toHaveValue(inPattern(day, 'DD.MM.YYYY'))
  await fillDate(date, first)
  await expect(date).toHaveValue(inPattern(first, 'DD.MM.YYYY'))
  await date.fill('31.02.2026')
  await expect(page.getByRole('alert').filter({ hasText: 'Type a date as DD.MM.YYYY.' })).toBeVisible()
  await shot(page, 'dashboard-04-typed-date')
  await page.goto('/settings/date-format')
  await format.selectOption('browser')
  await expect(page.getByRole('status').filter({ hasText: 'Saved. Today shows as' }))
    .toHaveText(`Saved. Today shows as ${shownDay(day, null)}.`)
  expect(await dateFormatOf(page)).toBeNull()

  // 5. No sideways scroll at a phone's width.
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/')
  await expect(page.getByRole('region', { name: 'Headline figures' }).getByText('You have')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Open loans' })).toBeVisible()
  const { scroll, client } = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth,
  }))
  expect(scroll, 'the dashboard at 375 px is no wider than the screen').toBeLessThanOrEqual(client)
  await shot(page, 'dashboard-05-narrow')

  // 6. The demo at a phone's width.
  await deleteAllMyData(a)
  await page.goto('/')
  await loadDemoData(page)
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible({ timeout: 30_000 })
  await expectAssetsTotalIsYouHave(a)
  // UX-11 (D-521): a link to `/` opens the month of the latest entry, in the demo this month, which ends today.
  await page.goto('/')
  await expect(page.getByRole('combobox', { name: 'Period' })).toHaveValue('latest-entry-month')
  await expectDate(page.getByLabel('From', { exact: true }), `${day.slice(0, 8)}01`)
  await expectNoSidewaysScroll(a, '/entries', page.getByRole('heading', { name: 'Entries' }))
  if (family === 'on') {
    await expect(page.locator('table.entries .family-line').first(), 'the demo’s family rows in the list').toBeVisible()
  }
  await shot(page, 'dashboard-06-entries-narrow')
  await expectNoSidewaysScroll(a, '/categories', page.getByRole('button', { name: /^Rename / }).first())
  await shot(page, 'dashboard-07-categories-narrow')
  if (family === 'on') {
    const id = /\/family\/(\d+)/.exec((await page.getByRole('link', { name: /^Change .+ in the family budget / }).first()
      .getAttribute('href')) ?? '')![1]
    await expectNoSidewaysScroll(a, `/family/${id}/categories`, page.getByRole('button', { name: 'Rename' }).first())
    await shot(page, 'dashboard-08-family-categories-narrow')
  }

  // 7. Equity by period and archiving at zero (UX-3).
  await page.setViewportSize({ width: 1280, height: 900 })
  await expectEquityByPeriod(a, `${day.slice(0, 8)}01`, day)
  await shot(page, 'dashboard-09-equity')
  await expectArchivingAtZero(a, watch)
  await shot(page, 'dashboard-10-archive-refused')

  // 8. The Accounts page's archived amounts and its width at 375 px (UX-4).
  await expectArchivedAmounts(a, day)
  await shot(page, 'dashboard-11-accounts-archived')
  await expectAccountsAtANarrowWidth(a)

  // 9. The period compared (UX-5).
  await expectComparisons(a, day)
  await shot(page, 'dashboard-13-compared')

  // 10. The family budget chosen, and the shared icon (UX-6).
  if (family === 'on') await expectBudgetChosen(a)

  // 11. The header's menus and Settings' parts (UX-7).
  await expectHeaderMenus(a, family === 'on')

  // 12. The headline strip and daily spending (UX-13).
  for (const width of [1280, 375]) {
    await page.setViewportSize({ width, height: width === 375 ? 812 : 900 })
    for (const each of [false, true]) await expectStripAndDays(a, first, last, each, width)
    await expectRunningMonth(a, day)
    await shot(page, 'dashboard-14-strip-each')
  }
  await expectShowChangesTheStrip(a)
  await shot(page, 'dashboard-15-strip-personal')
  await page.setViewportSize({ width: 1280, height: 900 })

  // 13. The two columns, the categories' shares and the Rates card (UX-14).
  await expectColumns(a)
  for (const each of [false, true]) {
    await expectShares(a, first, last, each)
    await expectRatesCard(a, last, each)
  }
  await shot(page, 'dashboard-16-columns')
  await page.setViewportSize({ width: 1280, height: 900 })

  // 14. The chart in horizontal bars: by year, and its layout at three widths (UX-16).
  await expectChartByYear(a, day)
  await expectChartLayout(a)
  await page.setViewportSize({ width: 1280, height: 900 })

  await deleteAllMyData(a)
})

/** "+12.3%", "−4.0%" or "0.0%" in tenths of a percent; null for "—". */
function tenths(text: string | null): bigint | null {
  const shown = (text ?? '').trim()
  if (shown === '—') return null
  expect(shown, `a change in "${shown}"`).toMatch(/^[+\u2212]?\d[\d,]*\.\d%$/)
  const value = BigInt(shown.replace(/[^0-9]/g, ''))
  return shown.startsWith('\u2212') ? -value : value
}

/**
 * D-398's change in tenths of a percent from cents, rounded half away from zero; null with nothing to compare, and
 * (D-440) when the two figures have opposite signs.
 */
function changeInTenths(now: bigint, before: bigint | null): bigint | null {
  if (before === null || before === 0n) return null
  if (now !== 0n && (now < 0n) !== (before < 0n)) return null
  const scaled = (now - before) * 2000n / (before < 0n ? -before : before)
  return (scaled + (scaled < 0n ? -1n : 1n)) / 2n
}

/** A cash flow answer in the base currency. */
type Flow = { rows: { month?: string; categoryCode: string; categoryName: string; categoryType: string; total: string | null; familyLedgerName?: string }[] }

/** UX-5 on the demo: the table's columns and one category's figures, "to date", and "Compare with" (D-393 to D-399). */
async function expectComparisons(session: Session, day: string) {
  const { page } = session
  const [year, month] = day.split('-').map(Number)
  const iso = (d: Date) => d.toISOString().slice(0, 10)
  const range = (m: number) => ({ from: iso(new Date(Date.UTC(year, m - 1, 1))), to: iso(new Date(Date.UTC(year, m, 0))) })
  const period = range(month - 1)
  const previous = range(month - 2)
  const yearEarlier = { from: iso(new Date(Date.UTC(year - 1, month - 2, 1))), to: iso(new Date(Date.UTC(year - 1, month - 1, 0))) }

  const asked = new Set<string>()
  const listen = (r: { url: () => string }) => {
    const url = new URL(r.url())
    if (url.pathname === '/api/reports/cash-flow') asked.add(`${url.searchParams.get('from')} ${url.searchParams.get('to')}`)
  }
  page.on('request', listen)
  await page.goto('/?period=previous-month')
  const table = page.getByRole('table', { name: 'Cash flow in EUR' })
  await expect(table).toBeVisible()
  await expect(page.locator('.stale')).toHaveCount(0)
  page.off('request', listen)
  expect([...asked].sort(), 'the period and both comparisons are asked').toEqual(
    [`${period.from} ${period.to}`, `${previous.from} ${previous.to}`, `${yearEarlier.from} ${yearEarlier.to}`].sort())
  const headers = (await table.locator('thead th .col-role').allTextContents())
  expect(headers).toEqual(['Period', 'Share', 'Year earlier', 'Change', 'Previous period', 'Change'])
  // The chart by range is oldest first (D-413), a row per range top to bottom (D-649): read by their place in the DOM.
  const roles = page.locator('figure.chart .ie-ranges .ie-row .ie-main')
  await expect(roles).toHaveCount(3)
  expect(await roles.allTextContents(), 'the chart by range, oldest first').toEqual(['Year earlier', 'Previous period', 'Period'])

  // One expense category of the demo in both months, from the API's own answers.
  const flow = async (r: { from: string; to: string }) =>
    (await call(session, 'GET', `/api/reports/cash-flow?from=${r.from}&to=${r.to}&currency=BASE`)).body as Flow
  const [now, before] = [await flow(period), await flow(previous)]
  const key = (row: Flow['rows'][number]) => `${row.categoryCode}@${row.familyLedgerName ?? ''}`
  const sumOf = (answer: Flow, k: string) => {
    const rows = answer.rows.filter((r) => key(r) === k)
    return rows.length === 0 ? null : rows.reduce((total, r) => total + cents(`€${r.total}`), 0n)
  }
  const chosen = now.rows.find((r) => r.categoryType === 'EXPENSE' && r.total !== null
    && before.rows.some((b) => key(b) === key(r) && b.total !== null))
  expect(chosen, 'an expense category of the demo in both months').toBeDefined()
  // A family budget's category is its name and the shared icon (D-419), whose title is part of the header's text.
  const name = chosen!.categoryName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const header = page.locator('th[scope="row"]', { hasText: new RegExp(`^${name}(Shared: .+)?$`) })
  const shared = chosen!.familyLedgerName === undefined
    ? header.filter({ hasNot: page.getByRole('img') })
    : header.filter({ has: page.getByRole('img', { name: `Shared: ${chosen!.familyLedgerName}`, exact: true }) })
  const cells = await table.locator('tbody tr').filter({ has: shared }).locator('td').allTextContents()
  const p = sumOf(now, key(chosen!))!
  const q = sumOf(before, key(chosen!))
  expect(cents(cells[0]), `${chosen!.categoryCode} in the period`).toBe(p)
  expect(cents(cells[4]), `${chosen!.categoryCode} in the previous period`).toBe(q)
  expect(tenths(cells[5]), `${chosen!.categoryCode}'s change against the previous period`).toBe(changeInTenths(p, q))

  // A running month is compared to date (D-396), unless today is its last day.
  await page.goto('/?period=this-month')
  const heading = page.getByRole('heading', { name: /^Income and expenses/ })
  await expect(page.getByRole('table', { name: 'Cash flow in EUR' })).toBeVisible()
  const lastDay = iso(new Date(Date.UTC(year, month, 0))) === day
  if (lastDay) await expect(heading).not.toContainText('to date')
  else await expect(heading).toContainText('to date')

  // Three months or more: the chart by month, and "Compare with" in the URL (D-399); the legend names the comparison
  // marked on the bars (D-650).
  const answers = new Map<string, Promise<Flow>>()
  const keep = (r: { url: () => string; json: () => Promise<unknown> }) => {
    const url = new URL(r.url())
    if (url.pathname === '/api/reports/cash-flow' && url.searchParams.get('currency') === 'BASE' && !url.searchParams.has('scope')) {
      answers.set(`${url.searchParams.get('from')} ${url.searchParams.get('to')}`, r.json() as Promise<Flow>)
    }
  }
  page.on('response', keep)
  await page.goto('/?period=last-3-months')
  const compare = page.getByRole('group', { name: 'Compare with' })
  await expect(compare.getByRole('button', { name: 'A year earlier' })).toHaveAttribute('aria-pressed', 'true')
  const legend = page.locator('figure.chart .ie-legend')
  await expect(legend).toContainText('a year earlier')
  await expect(page.locator('.stale')).toHaveCount(0)
  await expectMarkers(page, answers, 3, 'a year earlier')
  await compare.getByRole('button', { name: 'Previous period' }).click()
  await expect(page).toHaveURL(/[?&]compare=previous(&|$)/)
  await expect(legend).toContainText('previous period')
  await expect(legend).not.toContainText('a year earlier')
  await expect(page.locator('.stale')).toHaveCount(0)
  const marked = await expectMarkers(page, answers, 3, 'previous period')
  // The demo's six months hold the previous period's: it has markers.
  expect(marked, 'markers where the demo has figures in the previous period').toBeGreaterThan(0)
  page.off('response', keep)
}

/**
 * D-650: on each month's row of the chart, a marker on the income and on the expense bar exactly where the drawn
 * comparison's month has a figure above 0, by that range's own `/api/reports/cash-flow` answer in the base currency
 * (the hidden table names each row's comparison month). Returns how many markers it found.
 */
async function expectMarkers(page: Session['page'], answers: Map<string, Promise<Flow>>, rows: number, behind: string) {
  const table = page.getByRole('table', { name: 'Income and expenses in EUR' })
  await expect(table.locator('thead th')).toHaveText(['Month', 'Income', 'Expenses', `Month, ${behind}`, `Income, ${behind}`, `Expenses, ${behind}`])
  await expect(table.locator('tbody tr')).toHaveCount(rows)
  // The comparison's range: the answer whose months are the hidden table's comparison months.
  const theirs = await table.locator('tbody tr td:nth-child(4)').allTextContents()
  let flow: Flow | undefined
  for (const [range, answer] of answers) {
    const [from, to] = range.split(' ')
    const months: string[] = []
    for (let d = new Date(`${from.slice(0, 7)}-01T00:00:00Z`); d.toISOString().slice(0, 7) <= to.slice(0, 7); d.setUTCMonth(d.getUTCMonth() + 1)) {
      months.push(d.toLocaleDateString('en-US', { month: 'short', year: 'numeric', timeZone: 'UTC' }))
    }
    if (months.join() === theirs.join()) flow = await answer
  }
  expect(flow, `the page asked the cash flow of ${theirs.join(', ')}`).toBeDefined()
  const byMonth = new Map<string, { INCOME: bigint | null; EXPENSE: bigint | null; missing: boolean }>()
  for (const row of flow!.rows) {
    const label = new Date(`${row.month ?? ''}-01T00:00:00Z`).toLocaleDateString('en-US', { month: 'short', year: 'numeric', timeZone: 'UTC' })
    const own = byMonth.get(label) ?? { INCOME: null, EXPENSE: null, missing: false }
    if (row.total === null) own.missing = true
    else {
      const type = row.categoryType as 'INCOME' | 'EXPENSE'
      own[type] = (own[type] ?? 0n) + units(row.total)
    }
    byMonth.set(label, own)
  }
  let found = 0
  const drawn = page.locator('figure.chart .ie-months .ie-row')
  for (let i = 0; i < rows; i++) {
    const figures = byMonth.get(theirs[i])
    for (const which of ['income', 'expense'] as const) {
      const amount = figures === undefined || figures.missing ? null : figures[which === 'income' ? 'INCOME' : 'EXPENSE']
      const expected = amount !== null && amount > 0n ? 1 : 0
      await expect(drawn.nth(i).locator(`.ie-line.${which} .ie-marker`), `${theirs[i]}'s ${which} marker`).toHaveCount(expected)
      found += expected
    }
  }
  return found
}

/** "€1,234.56", "-€0.50" or "+€3.00" in cents. */
function cents(text: string | null): bigint {
  const shown = (text ?? '').replace(/\u00a0/g, ' ').trim()
  const digits = shown.replace(/[^0-9.]/g, '')
  expect(digits, `an amount in "${shown}"`).toMatch(/^\d+\.\d{2}$/)
  const value = BigInt(digits.replace('.', ''))
  return shown.startsWith('-') ? -value : value
}

/**
 * UX-3 (D-367, D-369): in the base currency, "Result for the period" is the Net of "Income and expenses", and the
 * equity lines add up to Equity's total. The period ends today, its To, the balances' day, so the result's days are the
 * Net's (D-430).
 */
async function expectEquityByPeriod({ page }: Session, from: string, to: string) {
  await page.goto(`/?from=${from}&to=${to}`)
  // The period's Net: the cash flow table's Net row, its period's column (D-569 left the Net there alone).
  const net = page.getByRole('table', { name: 'Cash flow in EUR' }).locator('tr.net td.amount').first()
  const balances = page.locator('section.card').filter({ has: page.getByRole('heading', { name: /^Balances on / }) })
  const equity = balances.locator('table.by-type').filter({ has: page.getByRole('columnheader', { name: 'Equity' }) })
  const result = equity.locator('tr').filter({ has: page.getByRole('cell', { name: 'Result for the period', exact: true }) })
    .locator('td.amount')
  await expect(net).not.toHaveText('')
  await expect(result).not.toHaveText('')
  await expect(page.locator('.stale')).toHaveCount(0)
  expect(cents(await result.textContent()), 'the result is the Net').toBe(cents(await net.textContent()))
  await expect(equity.getByRole('cell', { name: 'Accumulated reserve', exact: true })).toBeVisible()
  const lines = await equity.locator('tbody td.amount').allTextContents()
  expect(lines.length, 'the reserve, the result and the system accounts').toBeGreaterThanOrEqual(2)
  const total = await equity.locator('tfoot td').textContent()
  expect(lines.map(cents).reduce((a, b) => a + b, 0n), 'the equity lines add up to its total').toBe(cents(total))
  await expect(balances.getByText(/the accumulated reserve is at the rates/i)).toBeVisible()
}

/**
 * UX-3 (D-366): archiving an account that holds money is refused, with the message by the account; an account that
 * holds nothing archives, and is restored.
 */
async function expectArchivingAtZero({ page }: Session, watch: Watch) {
  watch.expect({
    method: 'PATCH', path: /^\/api\/accounts\/\d+$/, status: 409,
    reason: 'D-366: archiving the demo’s current account, which holds money, is refused',
  })
  page.on('dialog', (dialog) => void dialog.accept())
  // An empty account of its own, through the API with the page's session.
  const created = await page.evaluate(async () => {
    const xsrf = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? ''
    const response = await fetch('/api/accounts', {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(xsrf) },
      body: JSON.stringify({ code: 'UX3_EMPTY', name: 'Empty account', type: 'ASSET', defaultCurrency: 'EUR' }),
    })
    return response.status
  })
  expect(created, 'the empty account is created').toBe(201)
  await page.goto('/accounts')
  const current = page.getByRole('row').filter({ has: page.getByRole('link', { name: 'Current account', exact: true }) })
  const refused = page.waitForResponse((r) => r.request().method() === 'PATCH' && new URL(r.url()).pathname.startsWith('/api/accounts/'))
  await current.getByRole('button', { name: 'Archive' }).click()
  expect((await refused).status()).toBe(409)
  // In a row of its own right under the account's.
  const message = current.locator('xpath=following-sibling::tr[1]').getByRole('alert')
  await expect(message.filter({ hasText: 'An account is archived only once it holds nothing' })).toBeVisible()
  await expect(message).toContainText('Current account still holds')
  await expect(current.getByRole('button', { name: 'Archive' })).toBeVisible()

  const empty = page.getByRole('row').filter({ has: page.getByRole('link', { name: 'Empty account', exact: true }) })
  await empty.getByRole('button', { name: 'Archive' }).click()
  await expect(empty).toHaveCount(0)
  await page.getByLabel(/^Show archived/).check()
  await expect(empty.getByText('Archived', { exact: true })).toBeVisible()
  await empty.getByRole('button', { name: 'Restore' }).click()
  await expect(empty.getByText('Archived', { exact: true })).toHaveCount(0)
  // The refusal stays shown for the next screenshot.
  await page.getByLabel(/^Show archived/).uncheck()
}

/** A request of the page's own session, with its CSRF token; the answer's status and body. */
async function call({ page }: Session, method: string, path: string, body?: unknown) {
  return page.evaluate(async ({ method, path, body }) => {
    const xsrf = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? ''
    const response = await fetch(path, {
      method, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(xsrf) },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    return { status: response.status, body: await response.json().catch(() => null) as unknown }
  }, { method, path, body })
}

/**
 * UX-4 (D-382): an archived account that holds €0.01 is listed with "Show archived" off, its €0.01 shown, and the
 * Assets total in euros is the sum of the rows shown. The account is archived empty and then given the cent, which an
 * entry may still do (D-376), since an account that holds money can't be archived (D-366).
 */
async function expectArchivedAmounts(session: Session, day: string) {
  const { page } = session
  const created = await call(session, 'POST', '/api/accounts',
    { code: 'UX4_CENT', name: 'Cent account', type: 'ASSET', defaultCurrency: 'EUR' })
  expect(created.status, 'the account is created').toBe(201)
  const id = (created.body as { id: number }).id
  expect((await call(session, 'PATCH', `/api/accounts/${id}`, { archived: true })).status, 'archived empty').toBe(200)
  const accounts = (await call(session, 'GET', '/api/accounts')).body as { id: number; code: string }[]
  const current = accounts.find((a) => a.code === 'CURRENT_ACCOUNT')
  expect(current, 'the demo’s current account').toBeDefined()
  const cent = await call(session, 'POST', '/api/entries', {
    kind: 'TRANSFER', entryDate: day, payeeId: null, memo: 'UX-4 cent', fromAccountId: current!.id, toAccountId: id,
    currency: 'EUR', amount: '0.01', counterpartyId: null,
  })
  expect(cent.status, 'the cent is posted to the archived account').toBe(201)

  await page.goto('/accounts')
  await expect(page.getByLabel(/^Show archived/)).not.toBeChecked()
  // UX-11 (D-525): one table, a group per type labelled by its heading.
  const assets = page.getByRole('rowgroup', { name: 'Assets', exact: true })
  const row = assets.getByRole('row').filter({ has: page.getByRole('link', { name: 'Cent account', exact: true }) })
  await expect(row.getByText('Archived', { exact: true })).toBeVisible()
  await expect(row.locator('td.amount')).toHaveText('€0.01')
  // The euro total is the sum of the euro amounts its rows show.
  const euros = (texts: string[]) => texts.map((t) => t.replace(/\u00a0/g, ' ').trim()).filter((t) => /€/.test(t))
  const shown = euros(await assets.locator('tr:not(.type-total) > td.amount > div').allTextContents())
  const total = euros(await assets.locator('tr.type-total > td.amount > div').allTextContents())
  expect(total, 'one euro total').toHaveLength(1)
  expect(shown.map(cents).reduce((x, y) => x + y, 0n), 'the euro rows add up to the total').toBe(cents(total[0]))
}

/**
 * UX-4 (D-382): at 375 px, with "Show archived" on, a "By person" account's details open and a refusal's row shown,
 * the Accounts page is no wider than the screen and every button is inside it.
 */
async function expectAccountsAtANarrowWidth({ page }: Session) {
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/accounts')
  await page.getByLabel(/^Show archived/).check()
  const byPerson = page.getByRole('button', { name: 'By person' }).first()
  await byPerson.click()
  await expect(byPerson).toHaveAttribute('aria-expanded', 'true')
  await expect(page.locator('tr.details table.nested, tr.details .muted').first()).toBeVisible()
  const current = page.getByRole('row').filter({ has: page.getByRole('link', { name: 'Current account', exact: true }) })
  const refused = page.waitForResponse((r) => r.request().method() === 'PATCH' && new URL(r.url()).pathname.startsWith('/api/accounts/'))
  await current.getByRole('button', { name: 'Archive' }).click()
  expect((await refused).status()).toBe(409)
  await expect(page.locator('tr.row-error').getByRole('alert')).toBeVisible()
  const { scroll, client } = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth,
  }))
  expect(scroll, '/accounts at 375 px is no wider than the screen').toBeLessThanOrEqual(client)
  const outside = await page.locator('main button, main [role="alert"]').evaluateAll((elements) => elements
    .map((e) => ({ text: e.textContent, box: e.getBoundingClientRect() }))
    .filter(({ box }) => box.width === 0 || box.left < 0 || box.right > document.documentElement.clientWidth)
    .map(({ text }) => text))
  expect(outside, 'buttons and messages inside the screen at 375 px').toEqual([])
  await shot(page, 'dashboard-12-accounts-narrow')
  await page.setViewportSize({ width: 1280, height: 900 })
}

/** In the base currency, the Balances card's Assets total reads as "You have" (D-348). */
async function expectAssetsTotalIsYouHave({ page }: Session) {
  if (new URL(page.url()).pathname !== '/') await page.goto('/')
  const headline = page.getByRole('region', { name: 'Headline figures' })
  const youHave = headline.getByRole('term').filter({ hasText: /^You have$/ }).locator('xpath=following-sibling::dd')
  const balances = page.locator('section.card').filter({ has: page.getByRole('heading', { name: /^Balances on / }) })
  const assets = balances.locator('table.by-type').filter({ has: page.getByRole('columnheader', { name: 'Assets' }) })
    .locator('tfoot td')
  await expect(youHave).not.toHaveText('')
  // The strip shows "…" while its part loads (D-569).
  await expect(youHave).not.toHaveText('…')
  await expect(assets).toHaveText((await youHave.textContent())!)
  await expect(balances.getByText(/a total may differ slightly from the sum of its rows/)).toBeVisible()
}

/** At 375 px the page is no wider than the screen once `ready` shows. */
async function expectNoSidewaysScroll({ page }: Session, path: string, ready: Locator) {
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto(path)
  await expect(ready).toBeVisible()
  const { scroll, client } = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth,
  }))
  expect(scroll, `${path} at 375 px is no wider than the screen`).toBeLessThanOrEqual(client)
}

async function addExpense({ page }: Session, amount: string, memo: string) {
  await page.goto('/entries/new')
  await page.getByLabel('Paid from').selectOption({ label: 'Current account' })
  await page.getByLabel('Amount', { exact: true }).fill(amount)
  await page.getByLabel('Category').selectOption({ label: 'Groceries' })
  await page.getByLabel('Memo').fill(memo)
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page).toHaveURL(/\/entries$/)
}

/**
 * The dashboard of this month under "Show" (All, Personal or a family budget's name) and, for a budget, "Records" (D-420)
 * holds the amount, or doesn't.
 */
async function expectScope({ page }: Session, scope: string, amount: string, holds: boolean, records?: string) {
  if (new URL(page.url()).pathname !== '/') await page.goto('/')
  const show = page.getByRole('group', { name: 'Show' })
  const button = show.getByRole('button', { name: scope, exact: true })
  if (await button.getAttribute('aria-pressed') !== 'true') {
    const answered = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/reports/cash-flow')
    await button.click()
    await answered
  }
  await expect(button).toHaveAttribute('aria-pressed', 'true')
  if (records !== undefined) {
    const group = page.getByRole('group', { name: 'Records' })
    const choice = group.getByRole('button', { name: records, exact: true })
    if (await choice.getAttribute('aria-pressed') !== 'true') {
      const answered = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/reports/cash-flow')
      await choice.click()
      await answered
    }
    await expect(choice).toHaveAttribute('aria-pressed', 'true')
  }
  // The tables are in "Cash flow by category" since UX-14 (D-595).
  const card = page.getByRole('region', { name: 'Cash flow by category' })
  await expect(page.locator('.stale')).toHaveCount(0)
  // Its tables, or with nothing in the scope its empty text (B's "My records").
  await expect(card.getByRole('table').or(card.getByText(/^No income or expenses/)).first()).toBeVisible()
  if (holds) await expect(card.getByRole('table', { name: 'Cash flow in EUR' })).toContainText(wholeNumber(amount))
  else await expect(card).not.toContainText(wholeNumber(amount))
}

/**
 * UX-6 (D-419, D-420) on the demo: "Demo household"'s rows carry the shared icon without the budget's name after it;
 * the budget's button and "Records" set the URL and the API's scope.
 */
async function expectBudgetChosen({ page }: Session) {
  const DEMO = 'Demo household'
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/?period=previous-month')
  const table = page.getByRole('region', { name: 'Cash flow by category' }).getByRole('table', { name: 'Cash flow in EUR' })
  await expect(table).toBeVisible()
  await expect(page.locator('.stale')).toHaveCount(0)
  const icon = table.getByRole('img', { name: `Shared: ${DEMO}`, exact: true }).first()
  await expect(icon).toBeVisible()
  await expect(icon.locator('title')).toHaveText(`Shared: ${DEMO}`)
  await expect(table.locator('.shared-name'), 'one budget: no name after the icon').toHaveCount(0)
  await expect(page.getByRole('group', { name: 'Records' })).toHaveCount(0)

  const show = page.getByRole('group', { name: 'Show' })
  await expect(show.getByRole('button')).toHaveText(['All', 'Personal', DEMO])
  const asked = (scope: string) => page.waitForRequest((r) => {
    const url = new URL(r.url())
    return url.pathname === '/api/reports/cash-flow' && url.searchParams.get('scope') === scope
      && url.searchParams.has('familyLedgerId')
  })
  let request = asked('FAMILY')
  await show.getByRole('button', { name: DEMO, exact: true }).click()
  await request
  await expect(page).toHaveURL(/[?&]scope=family(&|$)/)
  const id = new URL(page.url()).searchParams.get('family')
  expect(id, 'the budget’s button sets family').toMatch(/^\d+$/)
  const records = page.getByRole('group', { name: 'Records' })
  await expect(records.getByRole('button')).toHaveText(['All records', 'My records', 'Others’ records'])
  await expect(records.getByRole('button', { name: 'All records' })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.locator('.stale')).toHaveCount(0)
  await expect(table.getByRole('img', { name: `Shared: ${DEMO}`, exact: true }).first()).toBeVisible()
  await expect(page.getByRole('link', { name: 'Whole family budget →' }))
    .toHaveAttribute('href', new RegExp(`^/family/${id}/report\\?from=`))
  await shot(page, 'dashboard-14-budget-chosen')

  for (const [label, scope, param] of [['My records', 'FAMILY_MINE', 'family-mine'],
    ['Others’ records', 'FAMILY_OTHERS', 'family-others'], ['All records', 'FAMILY', 'family']] as const) {
    request = asked(scope)
    await records.getByRole('button', { name: label, exact: true }).click()
    await request
    await expect(page).toHaveURL(new RegExp(`[?&]scope=${param}(&|$)`))
    expect(new URL(page.url()).searchParams.get('family'), `${label} keeps the budget`).toBe(id)
    await expect(records.getByRole('button', { name: label, exact: true })).toHaveAttribute('aria-pressed', 'true')
    await expect(show.getByRole('button', { name: DEMO, exact: true })).toHaveAttribute('aria-pressed', 'true')
  }

  await page.setViewportSize({ width: 375, height: 812 })
  await expect(records).toBeVisible()
  const { scroll, client } = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth,
  }))
  expect(scroll, 'the dashboard with the budget chosen at 375 px is no wider than the screen').toBeLessThanOrEqual(client)
  await shot(page, 'dashboard-15-budget-chosen-narrow')
  await page.setViewportSize({ width: 1280, height: 900 })

  await show.getByRole('button', { name: 'Personal', exact: true }).click()
  await expect(page).toHaveURL(/[?&]scope=personal(&|$)/)
  expect(new URL(page.url()).searchParams.has('family')).toBe(false)
  await expect(page.getByRole('group', { name: 'Records' })).toHaveCount(0)
}

/**
 * UX-7 (D-433 to D-438) and UX-10 (D-498): the header's main navigation and the user's menu, Settings' parts, a family
 * budget's tab, and the header at 375 px: row 1 on one top, the user's panel inside the screen, the navigation scrolling
 * inside itself, no sideways scroll.
 */
async function expectHeaderMenus({ page }: Session, family: boolean) {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/')
  const header = page.locator('header')
  const nav = await mainMenu(page)
  const pages = ['Dashboard', 'Entries', 'Accounts', 'Categories', 'Rates']
  await expect(nav.getByRole('link')).toHaveText(family
    ? [...pages, 'Demo household', 'New family budget'] : pages)
  await expect(nav.locator('a[aria-current="page"]')).toHaveText(['Dashboard'])
  await expect(header.getByText('My finances')).toHaveCount(0)
  await shot(page, 'dashboard-16-main-nav')
  await nav.getByRole('link', { name: 'Entries', exact: true }).click()
  await expect(page).toHaveURL(/\/entries$/)
  await expect(page.getByRole('heading', { level: 2, name: 'Entries' })).toBeVisible()
  await expect(nav.locator('a[aria-current="page"]')).toHaveText(['Entries'])

  // The user's menu to Settings, which opens Time zone; then each part by Settings' own navigation (D-438, D-499).
  const user = await userMenu(page)
  await expect(user.getByRole('button', { name: 'Log out' })).toBeVisible()
  await shot(page, 'dashboard-17-user-menu')
  await user.getByRole('link', { name: 'Settings' }).click()
  await expect(page).toHaveURL(/\/settings\/time-zone$/)
  await expect(header.locator('.menu-panel:visible')).toHaveCount(0)
  await expect(page.getByRole('heading', { level: 3, name: 'Time zone' })).toBeVisible()
  await expect(nav.locator('a[aria-current="page"]')).toHaveCount(0)
  for (const [part, path] of [['Date format', 'date-format'], ['Appearance', 'appearance'], ['Import', 'import'],
    ['Export', 'export'], ['Delete all my data', 'delete'], ['Time zone', 'time-zone']] as const) {
    await openSettings(page, part)
    await expect(page).toHaveURL(new RegExp(`/settings/${path}$`))
    await expect(page.getByRole('heading', { level: 2 })).toHaveText(['Settings'])
  }
  // An old link to Export lands on its part, with its query (D-438).
  await page.goto('/export?from=2026-01-01')
  await expect(page).toHaveURL(/\/settings\/export\?from=2026-01-01$/)
  await expect(page.getByTestId('export-summary')).toBeVisible()

  if (family) {
    await page.goto('/')
    await nav.getByRole('link', { name: 'Demo household', exact: true }).click()
    await expect(page.getByRole('heading', { level: 2, name: 'Demo household' })).toBeVisible()
    // Inside the budget (D-435, D-498): its tab current, no "New entry", no "← My finances"; the user's menu stays.
    await expect(nav.locator('a[aria-current="page"]')).toHaveText(['Demo household'])
    await page.getByRole('navigation', { name: 'Family budget' }).getByRole('link', { name: 'Members' }).click()
    await expect(nav.locator('a[aria-current="page"]')).toHaveText(['Demo household'])
    await expect(header.getByRole('link', { name: 'New entry' })).toHaveCount(0)
    await expect(page.getByRole('link', { name: '← My finances' })).toHaveCount(0)
    await expect(header.locator('.user-menu')).toBeVisible()
    await shot(page, 'dashboard-18-family-header')
    await nav.getByRole('link', { name: 'Dashboard', exact: true }).click()
    await expect(page).toHaveURL(/\/$/)
    await expect(header.getByRole('link', { name: 'New entry' })).toBeVisible()
  }

  // At a phone's width: row 1 on one top, the user's panel inside the screen, row 2 scrolling inside itself.
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/')
  await expect(page.getByRole('heading', { level: 2, name: 'Dashboard' })).toBeVisible()
  const tops = await header.locator('.wordmark, .header-end > a, .user-menu > button')
    .evaluateAll((items) => items.map((item) => Math.round(item.getBoundingClientRect().top)))
  expect(tops, 'row 1: the wordmark, "New entry", the user’s button').toHaveLength(3)
  expect(new Set(tops).size, 'the wordmark, "New entry" and the user’s button share one top').toBe(1)
  const navBox = await nav.evaluate((n) => ({ scroll: n.scrollWidth, client: n.clientWidth, overflow: getComputedStyle(n).overflowX }))
  expect(navBox.overflow, 'row 2 scrolls inside itself').toBe('auto')
  if (family) expect(navBox.scroll, 'row 2 is wider than the screen with a family budget').toBeGreaterThan(navBox.client)
  const menu = await userMenu(page)
  const box = (await menu.boundingBox())!
  const width = await page.evaluate(() => document.documentElement.clientWidth)
  expect(box.x, 'the user menu’s left edge on the screen').toBeGreaterThanOrEqual(0)
  expect(box.x + box.width, 'the user menu’s right edge on the screen').toBeLessThanOrEqual(width)
  const page375 = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth,
  }))
  expect(page375.scroll, 'the page with the user menu open at 375 px').toBeLessThanOrEqual(page375.client)
  await shot(page, 'dashboard-19-user-menu-narrow')
  await page.keyboard.press('Escape')
  // The current link is scrolled into view (D-498): Rates, the last page, at the far end of the pages.
  await nav.getByRole('link', { name: 'Rates', exact: true }).click()
  await expect(page).toHaveURL(/\/rates$/)
  const rates = (await nav.getByRole('link', { name: 'Rates', exact: true }).boundingBox())!
  expect(rates.x, 'Rates in view').toBeGreaterThanOrEqual(0)
  expect(rates.x + rates.width, 'Rates in view').toBeLessThanOrEqual(width)
  await page.setViewportSize({ width: 1280, height: 900 })
}

/** A daily cash flow row as `/api/reports/daily-cash-flow` answers it, in either shape. */
interface DailyRow { day: string; categoryType: string; categoryName: string; currency?: string; total: string | null }

/**
 * UX-13 (D-569, D-571) on the demo's previous month, `from` to `last`: the strip's five figures in their order and
 * layout at `width`, Income and Expenses as the table's totals, and per currency daily spending's slots, the days'
 * expenses adding up to Expenses and the biggest day, against the daily report's answer.
 */
async function expectStripAndDays({ page }: Session, from: string, last: string, each: boolean, width: number) {
  const daily = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/reports/daily-cash-flow')
  await page.goto(`/?period=previous-month${each ? '&currency=each' : ''}`)
  const answer = await (await daily).json() as DailyRow[] | { currency: string; rows: DailyRow[] }
  const rows: (DailyRow & { currency: string })[] = Array.isArray(answer) ? answer.map((r) => ({ ...r, currency: r.currency! }))
    : answer.rows.map((r) => ({ ...r, currency: answer.currency }))
  const url = new URL((await daily).url())
  expect(url.searchParams.get('from')).toBe(from)
  expect(url.searchParams.get('to')).toBe(last)
  expect(url.searchParams.get('currency')).toBe(each ? null : 'BASE')

  const flows = page.getByRole('region', { name: 'Cash flow by category' })
  await expect(flows.getByRole('table').first()).toBeVisible()
  await expect(page.locator('.stale')).toHaveCount(0)
  const strips = page.locator('dl.strip-figures')
  await expect(strips.first().locator('.figure.expense dd').first()).not.toHaveText('…')
  const tables = flows.getByRole('table', { name: /^Cash flow in / })
  const currencies = (await tables.evaluateAll((all) => all.map((t) => t.getAttribute('aria-label')!.slice('Cash flow in '.length))))
  expect(currencies.length, 'a cash flow table').toBeGreaterThan(0)
  const days = Number(last.slice(8))

  for (const currency of currencies) {
    const strip = page.getByLabel(`Headline figures in ${currency}`)
    await expect(strip.getByRole('term')).toHaveText(['You have', /^(You owe|Owed to you)$/, 'Net worth', 'Income', 'Expenses'])
    const table = flows.getByRole('table', { name: `Cash flow in ${currency}` })
    const totalOf = (label: string) => table.locator('tr').filter({ has: page.getByRole('rowheader', { name: label, exact: true }) })
      .locator('td.amount').first()
    const income = strip.locator('.figure.income > dd').first()
    const expenses = strip.locator('.figure.expense > dd').first()
    await expect(income, `${currency}: Income is the table's Total income`).toHaveText((await totalOf('Total income').textContent())!)
    await expect(expenses, `${currency}: Expenses is the table's Total expenses`).toHaveText((await totalOf('Total expenses').textContent())!)

    // Daily spending: a slot per day of the month, all past; the days' expenses add up to Expenses.
    const panel = page.locator(`section.daily[data-currency="${currency}"]`)
    await expect(panel.locator('.daily-day')).toHaveCount(days)
    await expect(panel.locator('.daily-day.future')).toHaveCount(0)
    const own = rows.filter((r) => r.currency === currency && r.categoryType === 'EXPENSE')
    expect(own.every((r) => r.total !== null), `${currency}: every day converts`).toBe(true)
    const sum = own.reduce((a, r) => a + cents(r.total), 0n)
    const shown = await expenses.textContent()
    if (shown === '—') expect(sum, `${currency}: no expenses`).toBe(0n)
    else expect(sum, `${currency}: the days add up to Expenses`).toBe(cents(shown))

    // The biggest day, the earlier on a tie, and its largest category, the first by name on a tie.
    const byDay = new Map<string, bigint>()
    for (const r of own) byDay.set(r.day, (byDay.get(r.day) ?? 0n) + cents(r.total))
    let best: [string, bigint] | null = null
    for (const [d, amount] of [...byDay].sort(([a], [b]) => a.localeCompare(b))) {
      if (amount > 0n && (best === null || amount > best[1])) best = [d, amount]
    }
    const biggest = panel.locator('.daily-biggest')
    if (best === null) {
      await expect(biggest).toHaveText('No spending in this period.')
      continue
    }
    const top = own.filter((r) => r.day === best[0])
      .sort((a, b) => Number(cents(b.total) - cents(a.total)) || a.categoryName.localeCompare(b.categoryName))[0]
    const text = ((await biggest.textContent()) ?? '').replace(/\u00a0/g, ' ')
    expect(text.startsWith(`Biggest day: ${shownDay(best[0], null)}, `), `${currency}: the biggest day in "${text}"`).toBe(true)
    expect(text.endsWith(`, ${top.categoryName}`), `${currency}: its largest category in "${text}"`).toBe(true)
    expect(cents(text.slice(`Biggest day: ${shownDay(best[0], null)}, `.length, -`, ${top.categoryName}`.length)),
      `${currency}: its amount`).toBe(best[1])
  }

  // The layout: one row of five from 64rem; under 40rem Net worth first across, then two columns.
  const box = async (name: string) => (await page.getByLabel(`Headline figures in ${currencies[0]}`).locator(`.figure.${name}`).boundingBox())!
  const [have, owe, worth, income, expense] = await Promise.all(['have', 'owe', 'worth', 'income', 'expense'].map(box))
  if (width >= 1024) {
    for (const b of [owe, worth, income, expense]) {
      expect(Math.abs(b.y - have.y), 'one row').toBeLessThanOrEqual(1)
      expect(Math.abs(b.width - have.width), 'equal columns').toBeLessThanOrEqual(1)
    }
    expect(have.x < owe.x && owe.x < worth.x && worth.x < income.x && income.x < expense.x, 'in order').toBe(true)
  } else {
    expect(worth.y, 'Net worth first').toBeLessThan(have.y)
    expect(worth.width, 'Net worth across').toBeGreaterThan(have.width + owe.width)
    expect(Math.abs(have.y - owe.y), 'You have and You owe in a row').toBeLessThanOrEqual(1)
    expect(Math.abs(income.y - expense.y), 'Income and Expenses in a row').toBeLessThanOrEqual(1)
    expect(income.y, 'then Income and Expenses').toBeGreaterThan(have.y)
    const { scroll, client } = await page.evaluate(() => ({
      scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth,
    }))
    expect(scroll, 'the dashboard at 375 px is no wider than the screen').toBeLessThanOrEqual(client)
  }
}

/** UX-13 (D-569, D-571): "Personal" in "Show" changes the strip's Income and Expenses, its line, and the daily request. */
async function expectShowChangesTheStrip({ page }: Session) {
  await page.goto('/?period=previous-month')
  const show = page.getByRole('group', { name: 'Show' })
  if (await show.count() === 0) return
  const strip = page.getByLabel('Headline figures in EUR')
  const expenses = strip.locator('.figure.expense > dd').first()
  await expect(expenses).not.toHaveText('…')
  await expect(page.locator('.stale')).toHaveCount(0)
  await expect(strip.locator('dd.scope-note')).toHaveCount(0)
  const daily = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/reports/daily-cash-flow')
  await show.getByRole('button', { name: 'Personal', exact: true }).click()
  expect(new URL((await daily).url()).searchParams.get('scope')).toBe('PERSONAL')
  await expect(page.locator('.stale')).toHaveCount(0)
  await expect(strip.locator('dd.scope-note')).toHaveText(['Personal', 'Personal'])
  const table = page.getByRole('table', { name: 'Cash flow in EUR' })
  const total = table.locator('tr').filter({ has: page.getByRole('rowheader', { name: 'Total expenses', exact: true }) })
    .locator('td.amount').first()
  await expect(expenses).toHaveText((await total.textContent())!)
}

/**
 * D-584 on the demo: "This month" while it runs shows every day of the month, those after today dashed and not asked;
 * the days asked, from the 1st to today, add up to Expenses.
 */
async function expectRunningMonth({ page }: Session, day: string) {
  const daily = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/reports/daily-cash-flow')
  await page.goto('/?period=this-month')
  const answer = await (await daily).json() as { currency: string; rows: DailyRow[] }
  const url = new URL((await daily).url())
  expect(url.searchParams.get('from')).toBe(`${day.slice(0, 8)}01`)
  expect(url.searchParams.get('to'), 'asked up to today').toBe(day)
  const [year, month] = day.split('-').map(Number)
  const days = new Date(Date.UTC(year, month, 0)).getUTCDate()
  const panel = page.locator(`section.daily[data-currency="${answer.currency}"]`)
  await expect(panel.locator('.daily-day')).toHaveCount(days)
  await expect(panel.locator('.daily-day.future'), 'the days after today').toHaveCount(days - Number(day.slice(8)))
  await expect(page.locator('.stale')).toHaveCount(0)
  const expenses = page.getByLabel(`Headline figures in ${answer.currency}`).locator('.figure.expense > dd').first()
  await expect(expenses).not.toHaveText('…')
  const own = answer.rows.filter((r) => r.categoryType === 'EXPENSE')
  expect(own.every((r) => r.total !== null), 'every day converts').toBe(true)
  const sum = own.reduce((a, r) => a + cents(r.total), 0n)
  const shown = await expenses.textContent()
  if (shown === '—') expect(sum).toBe(0n)
  else expect(sum, 'the days asked add up to Expenses').toBe(cents(shown))
}

/** The cards below the strip, in D-593's order, by their names. */
const CARDS = [/^Income and expenses/, 'Cash flow by category', /^Balances on /, 'Open loans', /^Rates on /] as const

/**
 * UX-14 (D-593, D-594) on the demo's previous month in the base currency: at 1280 px Balances beside "Income and
 * expenses" from the same top, daily spending beside the chart; since UX-16 (D-654) at 1024 px every card in one column
 * in D-593's order with daily spending beside the chart; at 375 px every card in one column in D-593's order, nothing
 * wider than the screen.
 */
async function expectColumns({ page }: Session) {
  const settle = async (width: number) => {
    await page.setViewportSize({ width, height: width === 375 ? 812 : 900 })
    await page.goto('/?period=previous-month')
    await expect(page.getByRole('region', { name: 'Cash flow by category' }).getByRole('table').first()).toBeVisible()
    await expect(page.getByRole('region', { name: /^Rates on / })).toBeVisible()
    await expect(page.locator('section.daily .daily-biggest').first()).toBeVisible()
    await expect(page.locator('figure.chart .ie-row').first()).toBeVisible()
    await expect(page.locator('.stale')).toHaveCount(0)
  }
  const box = async (name: string | RegExp) => (await page.getByRole('region', { name }).boundingBox())!
  const chartAndDays = async () => {
    const chart = (await page.locator('figure.chart').first().boundingBox())!
    const days = (await page.locator('section.daily').first().boundingBox())!
    return { chart, days }
  }

  await settle(1280)
  const flows = await box(CARDS[0])
  const balances = await box(CARDS[2])
  expect(balances.x, 'Balances to the right of "Income and expenses"').toBeGreaterThanOrEqual(flows.x + flows.width)
  expect(Math.abs(balances.y - flows.y), 'Balances from the same top').toBeLessThanOrEqual(1)
  const cashFlow = await box(CARDS[1])
  expect(Math.abs(cashFlow.x - flows.x), 'Cash flow by category in the main column').toBeLessThanOrEqual(1)
  expect(cashFlow.y, 'under "Income and expenses"').toBeGreaterThan(flows.y + flows.height)
  for (const [upper, lower] of [[CARDS[2], CARDS[3]], [CARDS[3], CARDS[4]]] as const) {
    const [a, b] = [await box(upper), await box(lower)]
    expect(Math.abs(a.x - b.x), `${String(lower)} in the side column`).toBeLessThanOrEqual(1)
    expect(b.y, `${String(lower)} under ${String(upper)}`).toBeGreaterThan(a.y + a.height)
  }
  const expectOneColumn = async (width: number) => {
    const boxes = []
    for (const name of CARDS) boxes.push(await box(name))
    for (let i = 1; i < boxes.length; i++) {
      expect(Math.abs(boxes[i].x - boxes[0].x), `${String(CARDS[i])} in the one column at ${width} px`).toBeLessThanOrEqual(1)
      expect(boxes[i].y, `${String(CARDS[i])} after ${String(CARDS[i - 1])} at ${width} px`).toBeGreaterThan(boxes[i - 1].y + boxes[i - 1].height)
    }
    const { scroll, client } = await page.evaluate(() => ({
      scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth,
    }))
    expect(scroll, `the dashboard at ${width} px is no wider than the screen`).toBeLessThanOrEqual(client)
  }
  let { chart, days } = await chartAndDays()
  expect(days.x, 'daily spending beside the chart at 1280 px').toBeGreaterThanOrEqual(chart.x + chart.width)
  expect(Math.abs(days.y - chart.y), 'from the same top').toBeLessThanOrEqual(1)
  await shot(page, 'dashboard-16-columns-1280')

  // D-654: under 80rem one column, in D-593's order; at 1024 px the card is then wider than 40rem inside, so daily
  // spending sits beside the chart (D-594).
  await settle(1024)
  await expectOneColumn(1024)
  ;({ chart, days } = await chartAndDays())
  expect(days.x, 'daily spending beside the chart at 1024 px').toBeGreaterThanOrEqual(chart.x + chart.width)
  expect(Math.abs(days.y - chart.y), 'from the same top').toBeLessThanOrEqual(1)
  await shot(page, 'dashboard-16-columns-1024')

  await settle(375)
  await expectOneColumn(375)
  await shot(page, 'dashboard-16-columns-375')
  await page.setViewportSize({ width: 1280, height: 900 })
}

/** A decimal string in units of 0.0001, the precision of NUMERIC(19,4). */
function units(amount: string): bigint {
  const negative = amount.startsWith('-')
  const [whole, fraction = ''] = amount.replace(/^[-+]/, '').split('.')
  expect(fraction.length, `at most 4 decimals in ${amount}`).toBeLessThanOrEqual(4)
  const value = BigInt(whole) * 10000n + BigInt(fraction.padEnd(4, '0'))
  return negative ? -value : value
}

/**
 * D-595's share in the table's text: the line over its section's total in tenths of a percent, rounded half up, as
 * "33.8%"; "—" where either is missing, the section is 0 or below, or the line is below 0.
 */
function expectedShare(line: bigint | null, section: bigint | null): string {
  if (line === null || section === null || section <= 0n || line < 0n) return '—'
  const tenths = (line * 2000n / section + 1n) / 2n
  return `${(tenths / 10n).toLocaleString('en-US')}.${tenths % 10n}%`
}

/**
 * UX-14 (D-595) on the demo's previous month: every income and expense line's Share equals the spec's own computation
 * from the period's `/api/reports/cash-flow` answer that the page received; the subtotals and the net have none.
 */
async function expectShares({ page }: Session, from: string, last: string, each: boolean) {
  type Row = { categoryName: string; categoryType: string; total: string | null; currency?: string; familyLedgerName?: string }
  const received = page.waitForResponse((r) => {
    const url = new URL(r.url())
    return url.pathname === '/api/reports/cash-flow' && url.searchParams.get('from') === from
      && url.searchParams.get('to') === last
  })
  await page.goto(`/?period=previous-month${each ? '&currency=each' : ''}`)
  const answer = await (await received).json() as Row[] | { currency: string; rows: Row[] }
  const rows = Array.isArray(answer) ? answer : answer.rows.map((r) => ({ ...r, currency: answer.currency }))
  const card = page.getByRole('region', { name: 'Cash flow by category' })
  await expect(card.getByRole('table').first()).toBeVisible()
  await expect(page.locator('.stale')).toHaveCount(0)
  const currencies = [...new Set(rows.map((r) => r.currency!))].sort()

  let checked = 0
  for (const currency of currencies) {
    // The page's lines: a name (with "Shared: <budget>" for a family budget's category) and its share, per section.
    const table = card.getByRole('table', { name: `Cash flow in ${currency}` })
    await expect(table.locator('thead th .col-role').nth(1)).toHaveText('Share')
    const shown = await table.locator('tbody').evaluateAll((bodies) => bodies.slice(0, 2).map((body) =>
      [...body.querySelectorAll('tr')].filter((tr) => !tr.classList.contains('section'))
        .map((tr) => ({
          label: (tr.querySelector('th')!.textContent ?? '').trim(),
          share: (tr.querySelector('td.share')!.textContent ?? '').trim(),
          total: tr.classList.contains('subtotal'),
        }))))
    for (const [i, type] of (['INCOME', 'EXPENSE'] as const).entries()) {
      const own = rows.filter((r) => r.currency === currency && r.categoryType === type)
      const sums = new Map<string, bigint | null>()
      for (const r of own) {
        const label = `${r.categoryName}${r.familyLedgerName ? `Shared: ${r.familyLedgerName}` : ''}`
        const before = sums.has(label) ? sums.get(label)! : 0n
        sums.set(label, before === null || r.total === null ? null : before + units(r.total))
      }
      const section = [...sums.values()].reduce<bigint | null>((a, b) => (a === null || b === null ? null : a + b), 0n)
      const lines = shown[i].filter((l) => !l.total)
      expect(lines.map((l) => l.label), `${currency} ${type}: every category of the answer is a line`)
        .toEqual(expect.arrayContaining([...sums.keys()]))
      for (const line of lines) {
        // A line of a comparison only is "—" in the period, and so is its share.
        const amount = sums.has(line.label) ? sums.get(line.label)! : null
        expect(line.share, `${currency} ${line.label}'s share`).toBe(expectedShare(amount, section))
        if (amount !== null) checked++
      }
      expect(shown[i].filter((l) => l.total).map((l) => l.share), `${currency} ${type}: no share for the total`).toEqual([''])
    }
    const net = await table.locator('tr.net td.share').textContent()
    expect(net, `${currency}: no share for the net`).toBe('')
  }
  expect(checked, 'the demo has categories with shares').toBeGreaterThan(0)
}

/**
 * UX-14 (D-596): in the base currency the Rates card lists the rates of net worth's answer on the balances' day, each
 * "1 EUR" and the rate with its currency, its source and date, "rate stale" where the answer says so; in each currency
 * there is no Rates card.
 */
async function expectRatesCard({ page }: Session, last: string, each: boolean) {
  type Rate = { currency: string; date: string; perEuro: string; source: string; stale?: boolean }
  const received = page.waitForResponse((r) => {
    const url = new URL(r.url())
    return url.pathname === '/api/reports/net-worth' && url.searchParams.get('asOf') === last
  })
  await page.goto(`/?period=previous-month${each ? '&currency=each' : ''}`)
  const answer = await (await received).json() as { currency: string; rates: Rate[] } | unknown[]
  await expect(page.getByRole('region', { name: 'Open loans' })).toBeVisible()
  await expect(page.locator('.stale')).toHaveCount(0)
  const card = page.getByRole('region', { name: `Rates on ${shownDay(last, null)}`, exact: true })
  await expect(page.getByRole('region', { name: 'Headline figures' })).not.toContainText('At the rates')
  if (each || Array.isArray(answer)) {
    expect(each, 'net worth in each currency only in each currency').toBe(true)
    await expect(page.getByRole('region', { name: /^Rates on / })).toHaveCount(0)
    return
  }
  await expect(card).toBeVisible()
  await expect(card.getByRole('link', { name: 'Rates', exact: true })).toHaveAttribute('href', '/rates')
  if (answer.rates.length === 0) {
    await expect(card.getByText(`Every amount is in ${answer.currency}: no rate needed.`, { exact: true })).toBeVisible()
    return
  }
  const items = card.getByRole('listitem')
  await expect(items).toHaveCount(answer.rates.length)
  // A rate's figure as a decimal without grouping or trailing zeros, for comparing the shown one with the answer's.
  const plainRate = (text: string) => text.replace(/,/g, '').replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')
  for (const [i, rate] of answer.rates.entries()) {
    const item = items.nth(i)
    const [unit, shown] = await item.locator('.rate-pair .amount').allTextContents()
    expect(unit).toBe('1 EUR')
    expect(shown.endsWith(` ${rate.currency}`), `the rate of ${rate.currency} in "${shown}"`).toBe(true)
    expect(plainRate(shown.slice(0, -` ${rate.currency}`.length)), `the rate of ${rate.currency}`).toBe(plainRate(rate.perEuro))
    await expect(item.locator('.rate-pair + span')).toHaveText(
      `${rate.source === 'MANUAL' ? 'Manual' : 'ECB'} rate of ${shownDay(rate.date, null)}${rate.stale ? ' (rate stale)' : ''}`)
  }
}

/**
 * UX-16 (D-652) on the demo: a custom range of more than 24 months, from 30 months before today's month to today, is
 * drawn by calendar year, a row per year it touches, oldest first, with no "Compare with" and no marker; the cash flow
 * table keeps its comparison columns.
 */
async function expectChartByYear({ page }: Session, day: string) {
  const [year, month] = day.split('-').map(Number)
  const from = new Date(Date.UTC(year, month - 1 - 30, 1)).toISOString().slice(0, 10)
  await page.goto(`/?from=${from}&to=${day}`)
  await expect(page.getByRole('table', { name: 'Cash flow in EUR' })).toBeVisible()
  await expect(page.locator('.stale')).toHaveCount(0)
  const years: string[] = []
  for (let y = Number(from.slice(0, 4)); y <= year; y++) years.push(String(y))
  await expect(page.locator('figure.chart .ie-years .ie-row .ie-main')).toHaveText(years)
  await expect(page.getByRole('group', { name: 'Compare with' })).toHaveCount(0)
  await expect(page.locator('figure.chart .ie-marker')).toHaveCount(0)
  await expect(page.getByRole('table', { name: 'Income and expenses in EUR' }).locator('thead th')).toHaveText(['Year', 'Income', 'Expenses'])
  await expect(page.getByRole('table', { name: 'Cash flow in EUR' }).locator('thead th .col-role')).toContainText(['Period', 'Share', 'Previous period'])
  await shot(page, 'dashboard-17-by-year')
}

/**
 * UX-16 (D-649, D-650; D-414's goal kept): at 375, 1024 and 1280 px, on "This month", "Previous month", "Last 3
 * months" (with each comparison) and "Past year", the dashboard doesn't scroll sideways, every label and value of the
 * chart is inside the chart's box, no two rows' boxes overlap, and no value's box overlaps a marker.
 */
async function expectChartLayout({ page }: Session) {
  const pages = ['this-month', 'previous-month', 'last-3-months', 'last-3-months&compare=previous', 'past-year']
  for (const width of [375, 1024, 1280]) {
    await page.setViewportSize({ width, height: width === 375 ? 812 : 900 })
    for (const period of pages) {
      await page.goto(`/?period=${period}`)
      await expect(page.locator('figure.chart .ie-row').first()).toBeVisible()
      await expect(page.locator('.stale')).toHaveCount(0)
      const found = await page.evaluate(() => {
        const problems: string[] = []
        const doc = document.documentElement
        if (doc.scrollWidth > doc.clientWidth) {
          const name = (e: Element) => `${e.tagName.toLowerCase()}${e.classList.length ? `.${[...e.classList].join('.')}` : ''}`
          const wide = [...document.body.querySelectorAll('*')].filter((e) => e.getBoundingClientRect().right > doc.clientWidth + 1)
          const outermost = wide.filter((e) => !wide.some((o) => o !== e && o.contains(e))).map(name)
          problems.push(`the page scrolls sideways: ${doc.scrollWidth} > ${doc.clientWidth}, by ${outermost.join(', ')}`)
        }
        const overlap = (a: DOMRect, b: DOMRect) =>
          Math.min(a.right, b.right) - Math.max(a.left, b.left) > 0.5 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 0.5
        let rows = 0
        let values = 0
        for (const chart of document.querySelectorAll('.ie-chart')) {
          const box = chart.getBoundingClientRect()
          const inside = (e: Element, what: string) => {
            const r = e.getBoundingClientRect()
            if (r.left < box.left - 0.5 || r.right > box.right + 0.5 || r.top < box.top - 0.5 || r.bottom > box.bottom + 0.5) {
              problems.push(`${what} "${e.textContent}" outside the chart: ${r.left}-${r.right} of ${box.left}-${box.right}`)
            }
          }
          chart.querySelectorAll('.ie-label .ie-main, .ie-label .ie-sub').forEach((e) => inside(e, 'label'))
          const shown = [...chart.querySelectorAll('.ie-value')]
          shown.forEach((e) => inside(e, 'value'))
          values += shown.length
          const drawn = [...chart.querySelectorAll('.ie-row')].map((e) => e.getBoundingClientRect())
          rows += drawn.length
          for (let i = 0; i < drawn.length; i++) {
            for (let j = i + 1; j < drawn.length; j++) if (overlap(drawn[i], drawn[j])) problems.push(`rows ${i} and ${j} overlap`)
          }
          const markers = [...chart.querySelectorAll('.ie-marker')].map((e) => e.getBoundingClientRect())
          for (const value of shown) {
            const r = value.getBoundingClientRect()
            if (markers.some((m) => overlap(r, m))) problems.push(`the value "${value.textContent}" overlaps a marker`)
          }
          // A row's two values don't overlap either.
          for (let i = 0; i < shown.length; i++) {
            for (let j = i + 1; j < shown.length; j++) {
              if (overlap(shown[i].getBoundingClientRect(), shown[j].getBoundingClientRect())) problems.push(`values ${i} and ${j} overlap`)
            }
          }
        }
        return { problems, rows, values }
      })
      expect(found.rows, `rows on ${period} at ${width} px`).toBeGreaterThan(0)
      expect(found.values, `values on ${period} at ${width} px`).toBe(found.rows * 2)
      expect(found.problems, `the chart on ${period} at ${width} px`).toEqual([])
    }
    await shot(page, `dashboard-18-chart-${width}`)
  }
}
