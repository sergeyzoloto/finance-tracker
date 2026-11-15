import type { Page } from '@playwright/test'
import { deleteAllMyData, expect, loadDemoData, mainMenu, shot, test, unfoldSummary, type Session } from '../fixtures.ts'

// UX-11's Entries (D-522, D-524) and Categories (D-527) with A, on the demo:
// 1. Entries' Period list holds "All time", the dashboard's Period list in its order (its "Custom" last), and "Custom".
// 2. For filters whose matches fit on one page (the previous month; since UX-17 an account, a category and a payee's
//    text within the last 3 months), the summary row equals what the visible rows give: the count and the first and
//    last date (the dates from the list's own answer, put in words by the test with Intl), the distinct payees and the
//    entries without one, the distinct
//    categories and the entries without one, the distinct accounts the Accounts cells name, and per currency In, Out,
//    Net and Transfers summed from the Amount cells. Since UX-12 also a payee by name (D-548). Since UX-18 (D-800) a
//    family record counts once, through its share's row, by its full amount (its record's answer): Spent or Received
//    the full amount, Others' shares the full amount less the share's cell; its payment's row isn't counted again.
// 3. Categories: a category renamed through its pencil, archived and restored through its boxes, each found by its
//    accessible name, and renamed back; with E2E_FAMILY=on a family category's link opens its budget's categories.
//    Since UX-12 (D-552), each category's name is centred on its buttons, on Categories and the family budget's.
// 4. Screenshots at 375 px (local-narrow); nothing scrolls sideways. Delete all my data at the end.
// ENT-1 (D-869 to D-876): step 1 opens Entries by the header's link on the month of the latest entry, the summary
// panel folded at 375 px and unfolded at 1280; step 2 reads the panel's counts line and amount tables; after it a
// filter is kept going to the dashboard and back by the header's links. ENT-2 (D-873): two accounts as chips,
// Direction and Origin toggles in the URL, the summary against the rows, a chip removed; Income and Transfers.
// ENT-3 (D-873, D-881): the breakdown's "only", checkbox, chips and the other tabs. ENT-4 (D-882, D-883): the monthly
// result's columns against /api/entries/monthly, per currency.
// (A family record's payee on a share, D-523, and Entries' family payees, D-547, are 09-payees' steps 2 to 4.)

const ROWS = 'table.entry-list > tbody > tr'

test('entries: periods, the summary panel and remembered state; categories: icon buttons', async ({ as, family }) => {
  const a = await as('A')
  const { page } = a
  await deleteAllMyData(a)
  await page.goto('/')
  await loadDemoData(page)
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible({ timeout: 30_000 })

  // 1. The Period lists.
  // The demo opens "Past year" (D-692; its whole period, a custom range, until UX-17).
  await expect(page.getByRole('combobox', { name: 'Period' })).toHaveValue('past-year')
  const dashboard = await page.getByRole('combobox', { name: 'Period' }).locator('option').allTextContents()
  // ENT-1 (D-869): Entries opens on the month of the latest entry, by the header's link with nothing remembered.
  const opening = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/entries/summary')
  const latestAnswer = page.waitForResponse((r) => {
    const url = new URL(r.url())
    return url.pathname === '/api/entries' && url.searchParams.get('size') === '1' && url.searchParams.has('to')
  })
  await (await mainMenu(page)).getByRole('link', { name: 'Entries' }).click()
  // allTextContents doesn't wait: the list first (UX-12, D-557; UX-11's run read the page before it rendered once).
  await expect(page.getByRole('combobox', { name: 'Period' })).toHaveValue('latest-entry-month')
  const latest = (await (await latestAnswer).json() as { content: { entryDate: string }[] }).content[0].entryDate
  const asked = new URL((await opening).url()).searchParams
  const [y, m] = latest.split('-').map(Number)
  expect(asked.get('from'), 'the latest entry’s month').toBe(`${latest.slice(0, 7)}-01`)
  expect(asked.get('to'), 'the latest entry’s month').toBe(`${latest.slice(0, 7)}-${String(new Date(y, m, 0).getDate()).padStart(2, '0')}`)
  // D-872: the summary panel starts folded under 40rem and unfolded from it, before any choice.
  const fold = page.getByRole('region', { name: 'Summary of all matching entries' }).locator('button.summary-toggle')
  await expect(fold).toHaveAttribute('aria-expanded', narrow(page) ? 'false' : 'true')
  await expectNoSidewaysScroll(page, 'Entries with its summary')
  await shot(page, 'ent1-01-entries-default')
  await page.getByRole('combobox', { name: 'Period' }).selectOption('all')
  await expect(page).toHaveURL(/period=all/)
  await expect(page.getByRole('combobox', { name: 'Period' })).toHaveValue('all')
  const entries = await page.getByRole('combobox', { name: 'Period' }).locator('option').allTextContents()
  expect(dashboard.at(-1)).toBe('Custom')
  expect(entries, 'All time, the dashboard’s presets in its order, Custom').toEqual(['All time', ...dashboard])
  // Its list and summary answered, so that step 2's waits take none of them for their own (D-557).
  await expect(page.locator(ROWS).first()).toBeVisible()
  await expect(page.getByRole('region', { name: 'Summary of all matching entries' })).not.toHaveClass(/stale/)
  await page.waitForLoadState('networkidle')
  await shot(page, 'ux11-01-entries-all-time')

  // 2. The summary row against the visible rows.
  const api = async <T,>(path: string) => (await (await a.context.request.get(path)).json()) as T
  const accounts = await api<{ id: number; code: string }[]>('/api/accounts')
  const categories = await api<{ id: number; code: string; type: string }[]>('/api/categories')
  const counterparties = await api<{ id: number; name: string }[]>('/api/counterparties')
  const usd = accounts.find((x) => x.code === 'USD_ACCOUNT')!
  const groceries = categories.find((c) => c.code === 'GROCERIES')!
  // Since UX-17 (D-689) the demo has 36 months: each filter within the last 3 months, so that it fits on one page.
  const recent = 'period=last-3-months'
  const filters = [
    'period=previous-month',
    `${recent}&accountId=${usd.id}`,
    `${recent}&categoryId=${groceries.id}`,
    `${recent}&q=${encodeURIComponent(counterparties[0].name.slice(0, 5))}`,
    // UX-12 (D-548): a payee by name, in other case.
    `${recent}&payee=${encodeURIComponent(counterparties[0].name.toUpperCase())}`,
  ]
  for (const filter of filters) await expectSummaryMatchesRows(a, filter)
  await page.goto('/entries?period=previous-month')
  await shot(page, 'ux11-02-entries-month')

  // ENT-1 (D-870): a filter is kept after going to the dashboard and back by the header's links.
  await page.goto(`/entries?period=previous-month&accountId=${usd.id}`)
  await expect(page.locator(ROWS).first()).toBeVisible()
  await (await mainMenu(page)).getByRole('link', { name: 'Dashboard' }).click()
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()
  await (await mainMenu(page)).getByRole('link', { name: 'Entries' }).click()
  await expect(page).toHaveURL(new RegExp(`/entries\\?period=previous-month&accountId=${usd.id}$`))
  await expect(page.getByRole('combobox', { name: 'Period' })).toHaveValue('previous-month')
  await shot(page, 'ent1-02-entries-kept')

  // ENT-2 (D-873, D-880): a second account added through the select becomes a chip, Direction and Origin through their
  // toggles, each in the URL as repeated values; the summary still equals the visible rows; a chip's × removes it.
  const cash = accounts.find((x) => x.code === 'CASH')!
  await page.goto(`/entries?${recent}&accountId=${usd.id}`)
  await expect(page.locator(ROWS).first()).toBeVisible()
  await page.getByRole('combobox', { name: 'Account' }).selectOption(String(cash.id))
  await expect(page).toHaveURL(new RegExp(`accountId=${usd.id}&accountId=${cash.id}`))
  const chosen = page.getByRole('list', { name: 'Chosen filters' }).getByRole('listitem')
  await expect(chosen).toHaveCount(2)
  const direction = page.getByRole('group', { name: 'Direction' })
  await direction.getByRole('button', { name: 'Expenses' }).click()
  await expect(direction.getByRole('button', { name: 'Expenses' })).toHaveAttribute('aria-pressed', 'true')
  await expect(page).toHaveURL(/direction=EXPENSES/)
  await page.getByRole('group', { name: 'Origin' }).getByRole('button', { name: 'Personal' }).click()
  await expect(page).toHaveURL(/origin=PERSONAL/)
  await expectNoSidewaysScroll(page, 'Entries with chips and toggles')
  // After the last request has answered, so that the rows and the summary match what is pressed.
  await page.waitForLoadState('networkidle')
  await expect(page.getByRole('region', { name: 'Summary of all matching entries' })).not.toHaveClass(/stale/)
  await shot(page, 'ent2-01-entries-chips')
  const both = new URL(page.url()).search.slice(1)
  await expectSummaryMatchesRows(a, both)
  // Every row an expense: its amount below 0.
  for (const amount of await page.locator(`${ROWS} td.amount`).allTextContents()) {
    expect(amount.trim(), 'an expense').toMatch(/^[-−]/)
  }
  await chosen.first().getByRole('button').click()
  await expect(chosen).toHaveCount(1)
  await expect(page).not.toHaveURL(new RegExp(`accountId=${usd.id}(&|$)`))
  for (const filter of [`${recent}&direction=INCOME`, `${recent}&direction=TRANSFERS`]) {
    await expectSummaryMatchesRows(a, filter)
  }

  // ENT-3 (D-873, D-881): the breakdown under the amount table. "Only" makes a category the filter's single value and
  // the list then has exactly the entries its row counted; the other rows stay listed (the tab's own filter is left
  // out); a second row's checkbox adds it, a chip appears, and unchecking removes it; By payee and By account too.
  await page.goto(`/entries?${recent}`)
  await expect(page.locator(ROWS).first()).toBeVisible()
  await unfoldSummary(page)
  const byCategory = page.getByRole('table', { name: 'By category' })
  await expect(byCategory).toBeVisible()
  const rowsWithBoxes = byCategory.locator('tbody tr').filter({ has: page.getByRole('checkbox') })
  const firstRow = rowsWithBoxes.first()
  const counted = Number((await firstRow.locator('td.amount').first().textContent())!.trim())
  const listed = page.waitForResponse((r) => {
    const url = new URL(r.url())
    return url.pathname === '/api/entries' && url.searchParams.get('size') === '50' && url.searchParams.has('categoryId')
  })
  await firstRow.getByRole('button', { name: /^Only / }).click()
  expect((await (await listed).json() as { totalElements: number }).totalElements, 'the row’s count').toBe(counted)
  await expect(page).toHaveURL(/categoryId=\d+(&|$)/)
  expect(new URL(page.url()).searchParams.getAll('categoryId')).toHaveLength(1)
  await expect(byCategory.locator('tbody tr').nth(1)).toBeVisible()
  await expect(rowsWithBoxes.first().getByRole('checkbox')).toBeChecked()
  // A checkbox the URL controls: click, then wait for its new state (check() would read it before the URL changes).
  await rowsWithBoxes.nth(1).getByRole('checkbox').click()
  await expect(rowsWithBoxes.nth(1).getByRole('checkbox')).toBeChecked()
  await expect(page).toHaveURL(/categoryId=\d+&categoryId=\d+/)
  await expect(page.getByRole('list', { name: 'Chosen filters' }).getByRole('listitem')).toHaveCount(2)
  await rowsWithBoxes.nth(1).getByRole('checkbox').click()
  await expect(rowsWithBoxes.nth(1).getByRole('checkbox')).not.toBeChecked()
  await expect(page.getByRole('list', { name: 'Chosen filters' }).getByRole('listitem')).toHaveCount(1)
  for (const tab of ['By payee', 'By account']) {
    await page.getByRole('tab', { name: tab }).click()
    const table = page.getByRole('table', { name: tab })
    await expect(table).toBeVisible()
    await table.getByRole('button', { name: /^Only / }).first().click()
    await expect(page.getByRole('list', { name: 'Chosen filters' }).getByRole('listitem').last()).toBeVisible()
  }
  await page.waitForLoadState('networkidle')
  await expect(page.getByRole('region', { name: 'Summary of all matching entries' })).not.toHaveClass(/stale/)
  await expectNoSidewaysScroll(page, 'Entries with the breakdown')
  await shot(page, 'ent3-01-entries-breakdown')

  // ENT-4 (D-882, D-883): the monthly result. "Previous month" (its From ignored): 12 months ending with its To month, a
  // column for each and a Total; each column's change equals what /api/entries/monthly says (In less Out) and the Total
  // the span's sum, per currency; nothing scrolls sideways.
  await page.goto('/entries?period=previous-month')
  await expect(page.locator(ROWS).first()).toBeVisible()
  await unfoldSummary(page)
  const chart = page.locator('.monthly-currency').first()
  await expect(chart).toBeVisible()
  await page.waitForLoadState('networkidle')
  const cents = (text: string) => {
    const [whole, part = ''] = text.replace('-', '').split('.')
    return (text.startsWith('-') ? -1n : 1n) * BigInt(whole + part.padEnd(2, '0').slice(0, 2))
  }
  const monthsOf = (first: string, last: string) => {
    const out: string[] = []
    let [y, m] = first.split('-').map(Number)
    const [ly, lm] = last.split('-').map(Number)
    while (y < ly || (y === ly && m <= lm)) {
      out.push(`${y}-${String(m).padStart(2, '0')}`)
      if (++m === 13) { m = 1; y++ }
    }
    return out
  }
  // The previous month by the app's own today (the user's zone), as the page's period computes it: its From and To are
  // what the page sends, and the API leaves the From out.
  const today = (await api<{ today: string }>('/api/me')).today
  const [ty, tm] = today.split('-').map(Number)
  const [py, pm] = tm === 1 ? [ty - 1, 12] : [ty, tm - 1]
  const pad = (n: number) => String(n).padStart(2, '0')
  const lastDay = new Date(py, pm, 0).getDate()
  const monthlyAnswer = await api<{ firstMonth: string; lastMonth: string;
    months: { month: string; currency: string; incoming: string; outgoing: string }[] }>(
    `/api/entries/monthly?from=${py}-${pad(pm)}-01&to=${py}-${pad(pm)}-${pad(lastDay)}`)
  expect(monthlyAnswer.lastMonth, 'the To month ends the span').toBe(`${py}-${pad(pm)}`)
  const span = monthsOf(monthlyAnswer.firstMonth, monthlyAnswer.lastMonth)
  expect(span.length, 'at most 12 months').toBeLessThanOrEqual(12)
  const currencies = [...new Set(monthlyAnswer.months.map((m) => m.currency))]
  expect(currencies.length).toBeGreaterThan(0)
  for (const currency of currencies) {
    const waterfall = page.locator(`.monthly-currency[data-currency="${currency}"]`)
    await expect(waterfall.locator('.monthly-column')).toHaveCount(span.length + 1)
    const shown = (await waterfall.locator('.monthly-value').allTextContents()).map((t) => {
      const match = /^([+\-−]?)\D*([\d,]+)\.(\d{2})$/.exec(t.replace(/[\u00a0\u2009\u202f]/g, '').trim())
      if (!match) throw new Error(`Not an amount: ${t}`)
      return (match[1] === '' || match[1] === '+' ? 1n : -1n) * BigInt(match[2].replace(/,/g, '') + match[3])
    })
    const expected = span.map((month) => {
      const row = monthlyAnswer.months.find((m) => m.month === month && m.currency === currency)
      return row ? cents(row.incoming) - cents(row.outgoing) : 0n
    })
    expect(shown.slice(0, -1), `${currency}: each month's change`).toEqual(expected)
    expect(shown.at(-1), `${currency}: the Total`).toBe(expected.reduce((a, b) => a + b, 0n))
    await expect(waterfall.locator('.monthly-label').last()).toHaveText('Total')
  }
  await expectNoSidewaysScroll(page, 'Entries with the monthly result')
  await expect(page.getByRole('region', { name: 'Summary of all matching entries' })).not.toHaveClass(/stale/)
  await shot(page, 'ent4-01-entries-monthly')

  // 3. Categories through their icon buttons.
  await page.goto('/categories')
  const pencil = page.getByRole('button', { name: /^Rename / }).first()
  const name = (await pencil.getAttribute('aria-label'))!.slice('Rename '.length)
  expect(await pencil.getAttribute('title')).toBe(`Rename ${name}`)
  await shot(page, 'ux11-03-categories')
  await pencil.click()
  await page.getByLabel('Category name').fill(`${name} UX11`)
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  const renamed = `${name} UX11`
  await expect(page.getByRole('button', { name: `Rename ${renamed}`, exact: true })).toBeVisible()
  page.once('dialog', (dialog) => void dialog.accept())
  await page.getByRole('button', { name: `Archive ${renamed}`, exact: true }).click()
  await expect(page.getByRole('button', { name: `Archive ${renamed}`, exact: true })).toHaveCount(0)
  await page.getByLabel(/^Show archived/).check()
  await page.getByRole('button', { name: `Restore ${renamed}`, exact: true }).click()
  await expect(page.getByRole('button', { name: `Archive ${renamed}`, exact: true })).toBeVisible()
  await page.getByRole('button', { name: `Rename ${renamed}`, exact: true }).click()
  await page.getByLabel('Category name').fill(name)
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByRole('button', { name: `Rename ${name}`, exact: true })).toBeVisible()
  await expectNoSidewaysScroll(page, '/categories')
  // UX-12 (D-552): each category's name is centred on the line of its buttons.
  await expectNamesCentred(page, 'table.categories', '/categories')
  if (family === 'on') {
    const link = page.getByRole('link', { name: /^Change .+ in the family budget Demo household$/ }).first()
    expect(await link.getAttribute('title')).toBe(await link.getAttribute('aria-label'))
    await link.click()
    await expect(page).toHaveURL(/\/family\/\d+\/categories$/)
    await expect(page.getByRole('heading', { name: 'Demo household' })).toBeVisible()
    await expectNamesCentred(page, 'table.categories', 'the family budget’s categories')
    await shot(page, 'ux12-04-family-categories')
  }

  await deleteAllMyData(a)
})

/**
 * UX-12 (D-552): in each row of the table, the name (the first cell's first line of text) is vertically centred on its
 * buttons, within 2 px.
 */
async function expectNamesCentred(page: Page, table: string, what: string) {
  await expect(page.locator(`${table} tbody tr`).first()).toBeVisible()
  const rows = await page.locator(`${table} tbody tr`).evaluateAll((trs) => trs.flatMap((tr) => {
    const button = tr.querySelector('td.actions button, td.actions a')
    const cell = tr.querySelector('td')!
    const text = [...cell.childNodes].find((n) => (n.textContent ?? '').trim() !== '')
    if (!button || !text) return []
    const range = document.createRange()
    range.selectNodeContents(text)
    const line = range.getClientRects()[0]
    const b = button.getBoundingClientRect()
    return [{ name: (text.textContent ?? '').trim(), name_middle: line.top + line.height / 2, button_middle: b.top + b.height / 2 }]
  }))
  expect(rows.length, `${what}: rows with buttons`).toBeGreaterThan(0)
  for (const row of rows) {
    expect(Math.abs(row.name_middle - row.button_middle), `${what}: ${row.name} centred on its buttons`).toBeLessThanOrEqual(2)
  }
}

/** Cents of an amount as the app writes it, "+€1,234.50", "-$3.00", "€0.40": its sign, currency and cents. */
function amountOf(text: string): { sign: '+' | '-' | ''; currency: string; cents: bigint } {
  const match = /^([+\-−]?)([€$])([\d,]+)\.(\d{2})$/.exec(text.replace(/[  ]/g, '').trim())
  if (!match) throw new Error(`Not an amount: ${text}`)
  const sign = match[1] === '' ? '' : match[1] === '+' ? '+' : '-'
  return { sign, currency: match[2] === '€' ? 'EUR' : 'USD', cents: BigInt(match[3].replace(/,/g, '') + match[4]) }
}

/**
 * The summary row of the filter equals what its rows give, all on one page (D-524). The rows' cells are read as shown:
 * the Payee cell's first line, the Category cell's name (muted: none), the Accounts cell less the row's categories,
 * the Amount cell per currency by its sign.
 */
async function expectSummaryMatchesRows({ page }: Session, filter: string) {
  // The answers to this filter's requests, not a late one to the page before it: every parameter of the filter other
  // than the period (which the page turns into From and To) must be in the request.
  const wanted = [...new URLSearchParams(filter)].filter(([key]) => key !== 'period')
  const asked = (url: URL) => wanted.every(([key, value]) => url.searchParams.getAll(key).includes(value))
  const list = page.waitForResponse((r) => {
    const url = new URL(r.url())
    return url.pathname === '/api/entries' && url.searchParams.get('size') === '50' && asked(url)
  })
  const summary = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/entries/summary' && asked(new URL(r.url())))
  await page.goto(`/entries?${filter}`)
  const answer = await (await list).json() as { totalElements: number; content: ListedEntry[] }
  await summary
  expect(answer.totalElements, `${filter}: fits on one page`).toBeGreaterThan(0)
  expect(answer.totalElements, `${filter}: fits on one page`).toBeLessThanOrEqual(50)
  const row = page.getByRole('region', { name: 'Summary of all matching entries' })
  await expect(row).toBeVisible()
  await expect(row).not.toHaveClass(/stale/)
  await unfoldSummary(page)
  await expect(page.locator(ROWS)).toHaveCount(answer.totalElements)

  const cells = await page.locator(ROWS).evaluateAll((rows) => rows.map((tr) => {
    const payeeCell = tr.querySelector('td.payee')!
    const first = payeeCell.firstChild
    const payee = first && first.nodeType === Node.TEXT_NODE ? first.textContent ?? '' : ''
    const categoryName = tr.querySelector('.category-name')!
    const category = categoryName.querySelector('.muted') ? '' : categoryName.textContent ?? ''
    return { payee, category, flow: tr.querySelector('td.flow')!.textContent ?? '',
      amount: tr.querySelector('td.amount')!.textContent ?? '' }
  }))
  const split = (text: string) => (text === '' ? [] : text.split(', '))
  const payees = new Set(cells.flatMap((c) => split(c.payee).map((p) => p.toLocaleLowerCase())))
  const withoutPayee = cells.filter((c) => c.payee === '').length
  const categories = new Set(cells.flatMap((c) => split(c.category)))
  const withoutCategory = cells.filter((c) => c.category === '').length
  const accounts = new Set(cells.flatMap((c) => c.flow.split(' → ').flatMap(split)
    .map((label) => label.replace(/ \([^)]*\)$/, ''))
    .filter((label) => label !== '—' && !split(c.category).includes(label))))
  // D-800: the records counted through a share's row, with their full amount from the record's own answer.
  const counted = new Map<number, bigint>()
  for (const e of answer.content) {
    const f = e.family
    if (e.kind !== 'FAMILY_SHARE' || !f || f.link !== 'SHARE' || f.recordId == null) continue
    if (f.recordType !== 'EXPENSE' && f.recordType !== 'INCOME') continue
    if (counted.has(f.recordId)) continue
    const record = await (await page.context().request
      .get(`/api/family-ledgers/${f.ledgerId}/records/${f.recordId}`)).json() as { amount: string }
    const [whole, part = ''] = record.amount.replace('-', '').split('.')
    counted.set(f.recordId, BigInt(whole + part.padEnd(2, '0').slice(0, 2)))
  }
  const totals = new Map<string, { in: bigint; out: bigint; transfers: bigint; spentOthers: bigint;
    receivedOthers: bigint }>()
  const seen = new Set<number>()
  cells.forEach((c, i) => {
    const e = answer.content[i]
    const record = e.family?.recordId ?? null
    const paysCounted = e.kind === 'FAMILY_PAYMENT' && e.family?.link === 'PAYMENT' && record != null
      && counted.has(record)
    const share = e.kind === 'FAMILY_SHARE' && record != null && counted.has(record) && !seen.has(record)
    if (share) seen.add(record)
    for (const part of c.amount.split(' → ')) {
      const m = amountOf(part)
      const t = totals.get(m.currency) ?? { in: 0n, out: 0n, transfers: 0n, spentOthers: 0n, receivedOthers: 0n }
      if (m.sign === '+') t.in += m.cents
      else if (m.sign === '-') t.out += m.cents
      else if (!paysCounted) t.transfers += m.cents
      if (share && m.sign === '+') t.receivedOthers += counted.get(record)! - m.cents
      if (share && m.sign === '-') t.spentOthers += counted.get(record)! - m.cents
      totals.set(m.currency, t)
    }
  })

  const dates = answer.content.map((e) => e.entryDate).sort()
  const range = await page.evaluate(([from, to]) => {
    const format = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
    const day = (iso: string) => new Date(`${iso}T00:00`)
    return from === to ? format.format(day(from)) : format.formatRange(day(from), day(to))
  }, [dates[0], dates.at(-1)!])
  const plainText = (t: string | null) => (t ?? '').replace(/[\u00a0\u2009\u202f]/g, ' ').trim()
  const plainRange = plainText(range)
  const n = answer.totalElements
  const counts = [`${n} ${n === 1 ? 'entry' : 'entries'} · ${plainRange}`]
  const without = (k: number) => (k > 0 ? ` · ${k} without` : '')
  counts.push(`${payees.size} ${payees.size === 1 ? 'payee' : 'payees'}${without(withoutPayee)}`)
  counts.push(`${categories.size} ${categories.size === 1 ? 'category' : 'categories'}${without(withoutCategory)}`)
  counts.push(`${accounts.size} ${accounts.size === 1 ? 'account' : 'accounts'}`)
  expect(plainText(await row.locator('.summary-counts').textContent()), filter).toBe(counts.join(' · '))

  // D-871: each currency's table, read back into the lines it replaced: Amount or My share is In and Out; with
  // others' shares, Full amount is Received and Spent and the middle column their others' shares.
  const tables = await row.locator('table.summary-amounts').evaluateAll((list) => list.map((t) => ({
    currency: t.getAttribute('data-currency')!,
    columns: [...t.querySelectorAll('thead th')].map((th) => th.textContent ?? ''),
    rows: [...t.querySelectorAll('tbody tr')].map((tr) => ({
      label: tr.querySelector('th')!.textContent ?? '',
      cells: [...tr.querySelectorAll('td')].map((td) => td.textContent ?? ''),
    })),
  })))
  const shown = new Map<string, Record<string, bigint>>()
  for (const t of tables) {
    const own: Record<string, bigint> = {}
    const put = (name: string, cell: string | undefined) => {
      if (!cell) return
      const m = amountOf(cell)
      expect(m.currency, `${filter}: ${name}`).toBe(t.currency)
      own[name] = m.sign === '-' ? -m.cents : m.cents
    }
    const shared = t.columns.length === 3
    for (const r of t.rows) {
      const mine = r.cells.at(-1)
      if (r.label === 'Income') {
        if (shared && r.cells[1]) { put('Received', r.cells[0]); put('Others’ shares (Received)', r.cells[1]) }
        put('In', mine)
      } else if (r.label === 'Expenses') {
        if (shared && r.cells[1]) { put('Spent', r.cells[0]); put('Others’ shares (Spent)', r.cells[1]) }
        put('Out', mine)
      } else put(r.label, mine)
    }
    if (Object.keys(own).length > 0) shown.set(t.currency, own)
  }
  const expected = new Map<string, Record<string, bigint>>()
  for (const [currency, t] of totals) {
    const own: Record<string, bigint> = {}
    if (t.receivedOthers !== 0n) {
      own.Received = t.in + t.receivedOthers
      own['Others’ shares (Received)'] = -t.receivedOthers
    }
    if (t.in !== 0n) own.In = t.in
    if (t.spentOthers !== 0n) {
      own.Spent = -(t.out + t.spentOthers)
      own['Others’ shares (Spent)'] = t.spentOthers
    }
    if (t.out !== 0n) own.Out = -t.out
    if (t.in !== 0n && t.out !== 0n) own.Net = t.in - t.out
    if (t.transfers !== 0n) own.Transfers = t.transfers
    if (Object.keys(own).length > 0) expected.set(currency, own)
  }
  expect(Object.fromEntries(shown), `${filter}: the Amount column`).toEqual(Object.fromEntries(expected))
}

/** What the test reads of a listed entry: its date, kind and family link. */
interface ListedEntry {
  entryDate: string
  kind: string
  family?: { ledgerId: number; recordId: number | null; link: string; recordType?: string }
}

/** Whether the window is narrower than 40rem. */
const narrow = (page: Page) => (page.viewportSize()?.width ?? 1280) < 640

/** At 375 px, the page is no wider than the window. */
async function expectNoSidewaysScroll(page: Page, what: string) {
  if (page.viewportSize()?.width !== 375) return
  const [scroll, client] = await page.evaluate(() => [document.documentElement.scrollWidth, document.documentElement.clientWidth])
  expect(scroll, `${what}: no sideways scroll at 375 px`).toBeLessThanOrEqual(client)
}
