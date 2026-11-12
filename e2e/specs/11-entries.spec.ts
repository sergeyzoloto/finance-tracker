import type { Page } from '@playwright/test'
import { deleteAllMyData, expect, loadDemoData, shot, test, type Session } from '../fixtures.ts'

// UX-11's Entries (D-522, D-524) and Categories (D-527) with A, on the demo:
// 1. Entries' Period list holds "All time", the dashboard's Period list in its order (its "Custom" last), and "Custom".
// 2. For filters whose matches fit on one page (the previous month, an account, a category, a payee's text), the
//    summary row equals what the visible rows give: the count and the first and last date (the dates from the list's own
//    answer, put in words by the test with Intl), the distinct payees and the entries without one, the distinct
//    categories and the entries without one, the distinct accounts the Accounts cells name, and per currency In, Out,
//    Net and Transfers summed from the Amount cells. Since UX-12 also a payee by name (D-548).
// 3. Categories: a category renamed through its pencil, archived and restored through its boxes, each found by its
//    accessible name, and renamed back; with E2E_FAMILY=on a family category's link opens its budget's categories.
//    Since UX-12 (D-552), each category's name is centred on its buttons, on Categories and the family budget's.
// 4. Screenshots at 375 px (local-narrow); nothing scrolls sideways. Delete all my data at the end.
// (A family record's payee on a share, D-523, and Entries' family payees, D-547, are 09-payees' steps 2 to 4.)

const ROWS = 'table.entry-list > tbody:not(.entry-summary) > tr'

test('entries: periods and the summary row; categories: icon buttons', async ({ as, family }) => {
  const a = await as('A')
  const { page } = a
  await deleteAllMyData(a)
  await page.goto('/')
  await loadDemoData(page)
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible({ timeout: 30_000 })

  // 1. The Period lists.
  // The demo opens its whole period, a custom range (D-521).
  await expect(page.getByRole('combobox', { name: 'Period' })).toHaveValue('custom')
  const dashboard = await page.getByRole('combobox', { name: 'Period' }).locator('option').allTextContents()
  await page.goto('/entries')
  // allTextContents doesn't wait: the list first (UX-12, D-557; UX-11's run read the page before it rendered once).
  await expect(page.getByRole('combobox', { name: 'Period' })).toHaveValue('all')
  const entries = await page.getByRole('combobox', { name: 'Period' }).locator('option').allTextContents()
  expect(dashboard.at(-1)).toBe('Custom')
  expect(entries, 'All time, the dashboard’s presets in its order, Custom').toEqual(['All time', ...dashboard])
  await expect(page.getByRole('combobox', { name: 'Period' })).toHaveValue('all')
  // Its list and summary answered, so that step 2's waits take none of them for their own (D-557).
  await expect(page.locator(ROWS).first()).toBeVisible()
  await expect(page.getByRole('rowgroup', { name: 'Summary of all matching entries' })).not.toHaveClass(/stale/)
  await page.waitForLoadState('networkidle')
  await shot(page, 'ux11-01-entries-all-time')

  // 2. The summary row against the visible rows.
  const api = async <T,>(path: string) => (await (await a.context.request.get(path)).json()) as T
  const accounts = await api<{ id: number; code: string }[]>('/api/accounts')
  const categories = await api<{ id: number; code: string; type: string }[]>('/api/categories')
  const counterparties = await api<{ id: number; name: string }[]>('/api/counterparties')
  const usd = accounts.find((x) => x.code === 'USD_ACCOUNT')!
  const groceries = categories.find((c) => c.code === 'GROCERIES')!
  const filters = [
    'period=previous-month',
    `accountId=${usd.id}`,
    `categoryId=${groceries.id}`,
    `q=${encodeURIComponent(counterparties[0].name.slice(0, 5))}`,
    // UX-12 (D-548): a payee by name, in other case.
    `payee=${encodeURIComponent(counterparties[0].name.toUpperCase())}`,
  ]
  for (const filter of filters) await expectSummaryMatchesRows(a, filter)
  await page.goto('/entries?period=previous-month')
  await shot(page, 'ux11-02-entries-month')

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
  const list = page.waitForResponse((r) => {
    const url = new URL(r.url())
    return url.pathname === '/api/entries' && url.searchParams.get('size') === '50'
  })
  const summary = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/entries/summary')
  await page.goto(`/entries?${filter}`)
  const answer = await (await list).json() as { totalElements: number; content: { entryDate: string }[] }
  await summary
  expect(answer.totalElements, `${filter}: fits on one page`).toBeGreaterThan(0)
  expect(answer.totalElements, `${filter}: fits on one page`).toBeLessThanOrEqual(50)
  const row = page.getByRole('rowgroup', { name: 'Summary of all matching entries' })
  await expect(row).toBeVisible()
  await expect(row).not.toHaveClass(/stale/)
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
  const totals = new Map<string, { in: bigint; out: bigint; transfers: bigint }>()
  for (const c of cells) {
    for (const part of c.amount.split(' → ')) {
      const m = amountOf(part)
      const t = totals.get(m.currency) ?? { in: 0n, out: 0n, transfers: 0n }
      if (m.sign === '+') t.in += m.cents
      else if (m.sign === '-') t.out += m.cents
      else t.transfers += m.cents
      totals.set(m.currency, t)
    }
  }

  const dates = answer.content.map((e) => e.entryDate).sort()
  const range = await page.evaluate(([from, to]) => {
    const format = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
    const day = (iso: string) => new Date(`${iso}T00:00`)
    return from === to ? format.format(day(from)) : format.formatRange(day(from), day(to))
  }, [dates[0], dates.at(-1)!])
  const text = async (cell: string) => (await row.locator(`td.${cell}`).textContent())!.replace(/[   ]/g, ' ')
  const plainRange = range.replace(/[   ]/g, ' ')
  const n = answer.totalElements
  expect(await text('sum-date'), filter).toBe(`Entries${n} ${n === 1 ? 'entry' : 'entries'} · ${plainRange}`)
  const without = (k: number) => (k > 0 ? ` · ${k} without` : '')
  expect(await text('sum-payee'), filter)
    .toBe(`Payees${payees.size} ${payees.size === 1 ? 'payee' : 'payees'}${without(withoutPayee)}`)
  expect(await text('sum-category'), filter)
    .toBe(`Categories${categories.size} ${categories.size === 1 ? 'category' : 'categories'}${without(withoutCategory)}`)
  expect(await text('sum-accounts'), filter).toBe(`Accounts${accounts.size} ${accounts.size === 1 ? 'account' : 'accounts'}`)

  const lines = await row.locator('.amount-line').allTextContents()
  const shown = new Map<string, Record<string, bigint>>()
  for (const line of lines) {
    const [label, amount] = line.replace(/[  ]/g, '').split(/\s+(?=[+\-−]?[€$])/)
    const m = amountOf(amount)
    const own = shown.get(m.currency) ?? {}
    own[label.trim()] = m.sign === '-' ? -m.cents : m.cents
    shown.set(m.currency, own)
  }
  const expected = new Map<string, Record<string, bigint>>()
  for (const [currency, t] of totals) {
    const own: Record<string, bigint> = {}
    if (t.in !== 0n) own.In = t.in
    if (t.out !== 0n) own.Out = -t.out
    if (t.in !== 0n && t.out !== 0n) own.Net = t.in - t.out
    if (t.transfers !== 0n) own.Transfers = t.transfers
    if (Object.keys(own).length > 0) expected.set(currency, own)
  }
  expect(Object.fromEntries(shown), `${filter}: the Amount column`).toEqual(Object.fromEntries(expected))
}

/** At 375 px, the page is no wider than the window. */
async function expectNoSidewaysScroll(page: Page, what: string) {
  if (page.viewportSize()?.width !== 375) return
  const [scroll, client] = await page.evaluate(() => [document.documentElement.scrollWidth, document.documentElement.clientWidth])
  expect(scroll, `${what}: no sideways scroll at 375 px`).toBeLessThanOrEqual(client)
}
