import { dateFormatOf, expectDate, fillDate, inPattern, shownDay } from '../dates.ts'
import { createTheBudget, inviteSomeoneNew, joinThroughTheLink } from '../family-steps.ts'
import type { Locator } from '@playwright/test'
import { deleteAllMyData, expect, shot, test, today, wholeNumber, type Session } from '../fixtures.ts'

// UX-1's dashboard with A (and B with the family budget on), every entry dated today:
// 1. A's personal expense of 12.34 on Groceries; the dashboard opens in the base currency (D-297): no currency in the
//    URL, the base currency's button pressed, and every report asked in BASE.
// 2. A preset button (D-296) changes the URL and the range shown in From and To.
// 3. With E2E_FAMILY=on: A creates "Dashboard check" (Groceries merged in), invites B, who joins; A pays a family expense
//    of 30.00. A's share of 15.00 is under "Family: my records" and not under "Personal", which keeps A's own 12.34; B's
//    share is under "Family: others' records", not under "Family: my records" nor "Personal" (D-302).
// 4. A chooses DD.MM.YYYY in Settings (D-304): a date in the entry list and the new entry's date field follow it, a date
//    typed in it is taken; A goes back to "As the browser shows it".
// 5. At 375 px the dashboard doesn't scroll sideways.
// 6. UX-2 (D-351, D-352, D-356): with the demo loaded (and "Demo household" with E2E_FAMILY=on), at 375 px the entry
//    list, the categories and the family budget's categories don't scroll sideways; in the base currency the
//    Balances' Assets total reads as "You have" (D-348), as it does in step 1.
// Delete all my data for both at the end.

const BUDGET = 'Dashboard check'

test('dashboard', async ({ as, family, target }) => {
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
  await expect(page.getByRole('region', { name: 'What you have and owe' }).getByText('You have')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Shared budget' })).toHaveCount(0)
  await expectAssetsTotalIsYouHave(a)
  await shot(page, 'dashboard-01-base')

  // 2. A preset: the URL and the range.
  const period = page.getByRole('group', { name: 'Period' })
  await period.getByRole('button', { name: 'Previous month' }).click()
  await expect(page).toHaveURL(/[?&]period=previous-month(&|$)/)
  await expect(period.getByRole('button', { name: 'Previous month' })).toHaveAttribute('aria-pressed', 'true')
  const [year, month] = day.split('-').map(Number)
  const first = new Date(Date.UTC(year, month - 2, 1)).toISOString().slice(0, 10)
  const last = new Date(Date.UTC(year, month - 1, 0)).toISOString().slice(0, 10)
  await expectDate(page.getByLabel('From', { exact: true }), first)
  await expectDate(page.getByLabel('To', { exact: true }), last)
  await period.getByRole('button', { name: 'This month' }).click()
  await expect(page).not.toHaveURL(/period=/)

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

    await expectScope(a, 'Family: my records', '15.00', true)
    await expectScope(a, 'Personal', '15.00', false)
    await expect(page.getByRole('table', { name: 'Cash flow in EUR' })).toContainText(wholeNumber('12.34'))
    await expect(page.getByRole('link', { name: 'Whole family budget →' })).toHaveCount(0)
    await expectScope(a, 'Family: my records', '15.00', true)
    await expect(page.getByRole('link', { name: 'Whole family budget →' }))
      .toHaveAttribute('href', new RegExp(`^/family/${ledgerId}/report\\?from=`))
    await shot(page, 'dashboard-02-family-mine')
    await expectScope(b, 'Family: others’ records', '15.00', true)
    await expectScope(b, 'Family: my records', '15.00', false)
    await expectScope(b, 'Personal', '15.00', false)
    await shot(b.page, 'dashboard-03-family-others')
    await deleteAllMyData(b)
  }

  // 4. The date format, shown and typed.
  await page.goto('/settings')
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
  await page.goto('/settings')
  await format.selectOption('browser')
  await expect(page.getByRole('status').filter({ hasText: 'Saved. Today shows as' }))
    .toHaveText(`Saved. Today shows as ${shownDay(day, null)}.`)
  expect(await dateFormatOf(page)).toBeNull()

  // 5. No sideways scroll at a phone's width.
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/')
  await expect(page.getByRole('region', { name: 'What you have and owe' }).getByText('You have')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Open loans' })).toBeVisible()
  const { scroll, client } = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth,
  }))
  expect(scroll, 'the dashboard at 375 px is no wider than the screen').toBeLessThanOrEqual(client)
  await shot(page, 'dashboard-05-narrow')

  // 6. The demo at a phone's width.
  await deleteAllMyData(a)
  await page.goto('/')
  await page.getByRole('button', { name: 'Load demo data' }).click()
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible({ timeout: 30_000 })
  await expectAssetsTotalIsYouHave(a)
  await expectNoSidewaysScroll(a, '/entries', page.getByRole('heading', { name: 'Entries' }))
  if (family === 'on') {
    await expect(page.locator('table.entries .family-line').first(), 'the demo’s family rows in the list').toBeVisible()
  }
  await shot(page, 'dashboard-06-entries-narrow')
  await expectNoSidewaysScroll(a, '/categories', page.getByRole('button', { name: 'Rename' }).first())
  await shot(page, 'dashboard-07-categories-narrow')
  if (family === 'on') {
    const id = /\/family\/(\d+)/.exec((await page.getByRole('link', { name: 'Change it in the family budget' }).first()
      .getAttribute('href')) ?? '')![1]
    await expectNoSidewaysScroll(a, `/family/${id}/categories`, page.getByRole('button', { name: 'Rename' }).first())
    await shot(page, 'dashboard-08-family-categories-narrow')
  }

  await deleteAllMyData(a)
})

/** In the base currency, the Balances card's Assets total reads as "You have" (D-348). */
async function expectAssetsTotalIsYouHave({ page }: Session) {
  if (new URL(page.url()).pathname !== '/') await page.goto('/')
  const headline = page.getByRole('region', { name: 'What you have and owe' })
  const youHave = headline.getByRole('term').filter({ hasText: /^You have$/ }).locator('xpath=following-sibling::dd')
  const balances = page.locator('section.card').filter({ has: page.getByRole('heading', { name: 'Balances' }) })
  const assets = balances.locator('table.by-type').filter({ has: page.getByRole('columnheader', { name: 'Assets' }) })
    .locator('tfoot td')
  await expect(youHave).not.toHaveText('')
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

/** The dashboard of this month under a scope holds the amount, or doesn't. */
async function expectScope({ page }: Session, scope: string, amount: string, holds: boolean) {
  if (new URL(page.url()).pathname !== '/') await page.goto('/')
  const show = page.getByRole('group', { name: 'Show' })
  const answered = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/reports/cash-flow')
  await show.getByRole('button', { name: scope, exact: true }).click()
  await answered
  await expect(show.getByRole('button', { name: scope, exact: true })).toHaveAttribute('aria-pressed', 'true')
  const flows = page.getByRole('region', { name: /^Income and expenses/ })
  await expect(flows.locator('.stale')).toHaveCount(0)
  if (holds) await expect(flows.getByRole('table', { name: 'Cash flow in EUR' })).toContainText(wholeNumber(amount))
  else await expect(flows).not.toContainText(wholeNumber(amount))
}
