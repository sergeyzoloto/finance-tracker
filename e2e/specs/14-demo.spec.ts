import type { Locator, Page } from '@playwright/test'
import { deleteAllMyData, expect, loadDemoData, shot, test, today, type Session } from '../fixtures.ts'

// UX-17 with A, on the demo of 36 months (D-684 to D-692):
// 1. After the demo's load (loadDemoData) the dashboard opens "Past year" (D-692): a row per month on the chart, with markers named
//    "previous year", and the table's "Previous year" column with figures.
// 2. "This month" and "Previous month": the "Year earlier" and "Previous period" columns with figures.
// 3. The demo's first month (the calendar month of its first day): neither comparison column, and the chart by range
//    with the period's row only (D-685).
// 4. In each currency, a day the test picks from `/api/reports/daily-cash-flow`'s answer where dollars have something
//    and the day before none, but euros some: dollars' table without "Previous period", euros' with it (D-685).
// 5. In each currency, a day with euros spent and no dollars, the day before with dollars: no daily spending for
//    dollars, euros' shown (D-686); if the demo has no such day, the spec says so and the unit tests cover it.
// 6. Screenshots at 375 px (local-narrow); Delete all my data at the end.

interface DailyRow { day: string; currency: string; categoryType: 'INCOME' | 'EXPENSE' }

test('demo: three years, the past year after loading, comparisons with nothing left out', async ({ as }) => {
  const a = await as('A')
  const { page } = a
  await deleteAllMyData(a)
  await page.goto('/')
  await loadDemoData(page)

  // 1. "Past year", 13 months by month, each with the previous year's marker.
  await expect(page).toHaveURL(/\/\?period=past-year$/)
  await expect(page.getByRole('combobox', { name: 'Period' })).toHaveValue('past-year')
  await settled(page)
  const chart = page.getByRole('figure', { name: 'Income and expenses in EUR' })
  await expect(chart.locator('.ie-months .ie-row')).toHaveCount(13)
  await expect(chart.locator('.ie-legend')).toHaveText(/previous year$/)
  expect(await chart.locator('.ie-marker').count(), 'markers of the previous year').toBeGreaterThan(0)
  await expect(page.getByRole('table', { name: 'Income and expenses in EUR' }).locator('thead th'))
    .toHaveText(['Month', 'Income', 'Expenses', 'Month, previous year', 'Income, previous year', 'Expenses, previous year'])
  const table = page.getByRole('table', { name: 'Cash flow in EUR' })
  await expect(roles(table)).toHaveText(['Period', 'Share', 'Previous year', 'Change'])
  await expectFigures(table, 2)
  await shot(page, 'demo-1-past-year')

  // 2. This month and the previous month, each comparison with figures.
  for (const [label, name] of [['This month', 'this-month'], ['Previous month', 'previous-month']]) {
    await page.getByRole('combobox', { name: 'Period' }).selectOption({ label })
    await expect(page).toHaveURL(new RegExp(`[?&]period=${name}(&|$)`))
    await settled(page)
    await expect(roles(table)).toHaveText([/^(Period|To date)$/, 'Share', 'Year earlier', 'Change', 'Previous period', 'Change'])
    await expectFigures(table, 2, ['Total expenses'])
    await expectFigures(table, 4, ['Total expenses'])
    await shot(page, `demo-2-${name}`)
  }

  // 3. The demo's first month: nothing to compare with.
  const first = await firstDay(a)
  const [y, m] = first.split('-').map(Number)
  const end = new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10)
  await page.goto(`/?from=${first}&to=${end}`)
  await settled(page)
  await expect(roles(table)).toHaveText(['Period', 'Share'])
  await expect(chart.locator('.ie-ranges .ie-row .ie-main')).toHaveText(['Period'])
  await shot(page, 'demo-3-first-month')

  // 4. and 5. From the daily cash flow of the past year, in each currency.
  const from = new Date(Date.now() - 365 * 86_400_000).toISOString().slice(0, 10)
  const rows = await api<DailyRow[]>(a, `/api/reports/daily-cash-flow?from=${from}&to=${today()}`)
  const has = (day: string, currency: string, type?: DailyRow['categoryType']) =>
    rows.some((r) => r.day === day && r.currency === currency && (type === undefined || r.categoryType === type))
  const before = (day: string) => new Date(Date.parse(day) - 86_400_000).toISOString().slice(0, 10)
  const days = [...new Set(rows.map((r) => r.day))].sort()

  const dollars = days.find((d) => has(d, 'USD') && has(before(d), 'EUR') && !has(before(d), 'USD'))
  expect(dollars, 'a day with dollars whose day before has euros and none').toBeDefined()
  await page.goto(`/?from=${dollars}&to=${dollars}&currency=each`)
  await settled(page)
  await expect(roles(page.getByRole('table', { name: 'Cash flow in USD' }))).not.toContainText(['Previous period'])
  await expect(roles(page.getByRole('table', { name: 'Cash flow in EUR' }))).toContainText(['Previous period'])
  await expect(page.getByRole('figure', { name: 'Income and expenses in USD' }).locator('.ie-ranges .ie-row .ie-main'))
    .not.toContainText(['Previous period'])
  await shot(page, 'demo-4-each-without-previous')

  const euros = days.find((d) => has(d, 'EUR', 'EXPENSE') && !has(d, 'USD') && has(before(d), 'USD'))
  if (euros === undefined) {
    test.info().annotations.push({ type: 'not covered', description: 'no day with euros spent and no dollars after a day with dollars; the unit tests cover D-686' })
  } else {
    await page.goto(`/?from=${euros}&to=${euros}&currency=each`)
    await settled(page)
    await expect(page.getByRole('table', { name: 'Cash flow in USD' })).toBeVisible()
    await expect(page.locator('section.daily[data-currency="EUR"]')).toHaveCount(1)
    await expect(page.locator('section.daily[data-currency="USD"]')).toHaveCount(0)
    await shot(page, 'demo-5-each-no-dollar-days')
  }

  await deleteAllMyData(a)
})

/** The page with every load answered: no part loading or reloading. */
async function settled(page: Page) {
  await expect(page.getByRole('table', { name: /^Cash flow in / }).first()).toBeVisible({ timeout: 30_000 })
  await expect(page.getByText('Loading…')).toHaveCount(0)
  await expect(page.locator('.stale')).toHaveCount(0)
}

/** A cash flow table's column roles, as the header names them above each range. */
const roles = (table: Locator) => table.locator('thead th .col-role')

/**
 * The totals' cells in the column at `index` (0 the period's) are figures, not "—": Total income's and Total
 * expenses' by default. A month to date may have no income yet in a comparison (the salary comes later in the month).
 */
async function expectFigures(table: Locator, index: number, names = ['Total income', 'Total expenses']) {
  for (const name of names) {
    const cell = table.locator('tr').filter({ has: table.page().getByRole('rowheader', { name, exact: true }) })
      .locator('td').nth(index)
    await expect(cell, `${name}, column ${index}`).toHaveText(/\d/)
  }
}

/** The demo's first day: the oldest entry's, its opening balances (the list is newest first). */
async function firstDay(session: Session) {
  const { totalElements } = await api<{ totalElements: number }>(session, '/api/entries?size=1')
  const oldest = await api<{ content: { entryDate: string }[] }>(session, `/api/entries?size=1&page=${totalElements - 1}`)
  return oldest.content[0].entryDate
}

async function api<T>({ page }: Session, path: string): Promise<T> {
  return page.evaluate(async (path) => {
    const response = await fetch(path)
    if (!response.ok) throw new Error(`${path} answered ${response.status}`)
    return response.json()
  }, path) as Promise<T>
}
