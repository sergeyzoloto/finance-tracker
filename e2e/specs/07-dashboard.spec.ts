import { dateFormatOf, expectDate, fillDate, inPattern, shownDay } from '../dates.ts'
import { createTheBudget, inviteSomeoneNew, joinThroughTheLink } from '../family-steps.ts'
import type { Locator } from '@playwright/test'
import { deleteAllMyData, expect, shot, test, today, wholeNumber, type Session, type Watch } from '../fixtures.ts'

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
// 7. UX-3 (D-366, D-367), on the demo at 1280 px, in the base currency from the 1st of the month to today: "Result for
//    the period" reads as the Net of "Income and expenses"; the equity lines ("Accumulated reserve", the result and
//    the system accounts) add up to Equity's total; on the Accounts page, archiving "Current account", which holds
//    money, is refused with its message by the account, and an empty account archives and is restored.
// 8. UX-4 (D-382): on the demo, an account archived empty and then given €0.01 (entries may still post to an archived
//    account, D-376) is listed with "Show archived" off, its €0.01 shown, and the Assets total matches the rows' cents;
//    at 375 px, with "Show archived" on, a "By person" account's details open and the refusal's row shown, the
//    Accounts page doesn't scroll sideways and every button is inside the screen.
// 9. UX-5 (D-393, D-396, D-399), on the demo at 1280 px in the base currency: "Previous month" heads the cash flow
//    table with the period, the year earlier and the previous period, and one category's period, previous period and
//    change in % equal the test's own computation from those two ranges' `/api/reports/cash-flow` answers; "This
//    month" says "to date" while it runs; "Last 3 months" offers "Compare with", whose "Previous period" sets
//    `compare=previous` and changes the chart's legend.
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

/** D-398's change in tenths of a percent from cents, rounded half away from zero; null with nothing to compare. */
function changeInTenths(now: bigint, before: bigint | null): bigint | null {
  if (before === null || before === 0n) return null
  const scaled = (now - before) * 2000n / (before < 0n ? -before : before)
  return (scaled + (scaled < 0n ? -1n : 1n)) / 2n
}

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
  expect(headers).toEqual(['Period', 'Year earlier', 'Change', 'Previous period', 'Change'])

  // One expense category of the demo in both months, from the API's own answers.
  type Flow = { rows: { categoryCode: string; categoryName: string; categoryType: string; total: string | null; familyLedgerName?: string }[] }
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
  const label = chosen!.categoryName + (chosen!.familyLedgerName ?? '')
  const exactly = new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`)
  const cells = await table.locator('tbody tr').filter({ has: page.locator('th[scope="row"]', { hasText: exactly }) })
    .locator('td').allTextContents()
  const p = sumOf(now, key(chosen!))!
  const q = sumOf(before, key(chosen!))
  expect(cents(cells[0]), `${chosen!.categoryCode} in the period`).toBe(p)
  expect(cents(cells[3]), `${chosen!.categoryCode} in the previous period`).toBe(q)
  expect(tenths(cells[4]), `${chosen!.categoryCode}'s change against the previous period`).toBe(changeInTenths(p, q))

  // A running month is compared to date (D-396), unless today is its last day.
  await page.goto('/?period=this-month')
  const heading = page.getByRole('heading', { name: /^Income and expenses/ })
  await expect(page.getByRole('table', { name: 'Cash flow in EUR' })).toBeVisible()
  const lastDay = iso(new Date(Date.UTC(year, month, 0))) === day
  if (lastDay) await expect(heading).not.toContainText('to date')
  else await expect(heading).toContainText('to date')

  // Three months or more: the chart by month, and "Compare with" in the URL (D-399).
  await page.goto('/?period=last-3-months')
  const compare = page.getByRole('group', { name: 'Compare with' })
  await expect(compare.getByRole('button', { name: 'A year earlier' })).toHaveAttribute('aria-pressed', 'true')
  const legend = page.locator('figure.chart .recharts-legend-wrapper')
  await expect(legend).toContainText('Income, a year earlier')
  await compare.getByRole('button', { name: 'Previous period' }).click()
  await expect(page).toHaveURL(/[?&]compare=previous(&|$)/)
  await expect(legend).toContainText('Income, previous period')
  await expect(legend).not.toContainText('a year earlier')
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
 * equity lines add up to Equity's total. The period ends on Balances on, so the result's days are the Net's.
 */
async function expectEquityByPeriod({ page }: Session, from: string, to: string) {
  await page.goto(`/?from=${from}&to=${to}`)
  const totals = page.getByLabel('Totals in EUR')
  // The first dd is the amount; a second holds its changes (D-400).
  const net = totals.getByRole('term').filter({ hasText: /^Net$/ }).locator('xpath=following-sibling::dd[1]')
  const balances = page.locator('section.card').filter({ has: page.getByRole('heading', { name: 'Balances' }) })
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
  const assets = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Assets', exact: true }) })
  const row = assets.getByRole('row').filter({ has: page.getByRole('link', { name: 'Cent account', exact: true }) })
  await expect(row.getByText('Archived', { exact: true })).toBeVisible()
  await expect(row.locator('td.amount')).toHaveText('€0.01')
  // The euro total is the sum of the euro amounts its rows show.
  const euros = (texts: string[]) => texts.map((t) => t.replace(/\u00a0/g, ' ').trim()).filter((t) => /€/.test(t))
  const shown = euros(await assets.locator('tbody td.amount > div').allTextContents())
  const total = euros(await assets.locator('tfoot td.amount > div').allTextContents())
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
