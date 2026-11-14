import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Accounts from './Accounts'
import { accountDays, comparedLines, defaultCompare } from './accounts'
import type { Account, AccountBalance, Category, Entry, EntrySummary } from './api'
import Categories from './Categories'
import Entries, { entriesPeriod } from './Entries'
import { amountLines, summaryTexts } from './entrySummary'
import { PERIOD_LABELS, PRESETS } from './periods'

/** A currency without family records counted (UX-18): Spent and Received are Out and In. */
const noFamily = (received: string, spent: string) =>
  ({ spent, spentOthersShares: '0.00', received, receivedOthersShares: '0.00' })
import { testLedger } from './testLedger'
import { WithMe } from './testMe'

// UX-11: Entries' periods (D-522), summary row (D-524) and a family record's payee (D-523); Accounts on a date and
// compared, in one table (D-525, D-526); the Categories page's icon buttons (D-527). Today is 2026-09-30 (TEST_ME).

/** Text as shown, with no-break and thin spaces as spaces. */
const plain = (text: string | null | undefined) => text?.replace(/[   ]/g, ' ').trim()

let calls: string[] = []
/** The api by path; `answer` may answer a path first. */
function stubApi(bodies: Record<string, unknown>, answer: (path: string, params: URLSearchParams) => unknown = () => undefined) {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    const [path, query = ''] = url.split('?')
    const body = answer(path, new URLSearchParams(query)) ?? bodies[path]
    return new Response(JSON.stringify(body ?? { status: 404 }), {
      status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' },
    })
  }))
}

/** Where the router is, for the URL's assertions. */
let where = ''
function Where() {
  const location = useLocation()
  where = location.search
  return null
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const reference = {
  '/api/accounts': testLedger.accounts,
  '/api/categories': testLedger.categories,
  '/api/counterparties': testLedger.counterparties,
  '/api/settings': testLedger.settings,
}

const entry = (id: number, entryDate: string, extra: Partial<Entry> = {}): Entry => ({
  id, version: 0, entryDate, kind: 'EXPENSE', payeeId: null, memo: null,
  postings: [
    { accountId: 1, currency: 'EUR', amount: '-12.00', categoryId: null, counterpartyId: null },
    { accountId: 6, currency: 'EUR', amount: '12.00', categoryId: 12, counterpartyId: null },
  ],
  ...extra,
})
const summary: EntrySummary = {
  count: 214, firstDate: '2026-09-01', lastDate: '2026-09-30', payees: 12, withoutPayee: 3, categories: 7,
  withoutCategory: 0, accounts: 4,
  currencies: [
    { currency: 'EUR', incoming: '2000.00', outgoing: '1234.50', transfers: '0.00', ...noFamily('2000.00', '1234.50') },
    { currency: 'USD', incoming: '0.00', outgoing: '20.00', transfers: '300.00', ...noFamily('0.00', '20.00') },
  ],
}

function renderEntries(path: string, entries: Entry[] = [entry(1, '2026-09-12')], latest: string | null = '2026-08-20') {
  stubApi(reference, (path, params) => {
    if (path === '/api/entries/summary') return summary
    if (path !== '/api/entries') return undefined
    if (params.get('size') === '1') {
      return { content: latest ? [entry(99, latest)] : [], page: 0, size: 1, totalElements: latest ? 1 : 0, totalPages: 1 }
    }
    return { content: entries, page: 0, size: 50, totalElements: entries.length, totalPages: 1 }
  })
  return render(
    <WithMe>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path="/entries" element={<><Entries /><Where /></>} /></Routes>
      </MemoryRouter>
    </WithMe>,
  )
}

describe('Entries’ periods (D-522)', () => {
  it('lists All time, the dashboard’s presets in its order with its labels, and Custom', async () => {
    renderEntries('/entries')
    const period = await screen.findByRole('combobox', { name: 'Period' }) as HTMLSelectElement
    expect([...period.options].map((o) => o.value)).toEqual(['all', ...PRESETS, 'custom'])
    expect([...period.options].map((o) => o.textContent))
      .toEqual(['All time', ...PRESETS.map((p) => PERIOD_LABELS[p]), 'Custom'])
    expect(period.value).toBe('all')
    expect([...period.options].filter((o) => o.disabled).map((o) => o.value)).toEqual(['custom'])
    await waitFor(() => expect(calls).toContain('/api/entries?page=0&size=50'))
    expect(calls).toContain('/api/entries/summary?')
  })

  it('keeps a preset by name in the URL and sends its days', async () => {
    renderEntries('/entries?period=previous-month&accountId=1')
    const period = await screen.findByRole('combobox', { name: 'Period' }) as HTMLSelectElement
    expect(period.value).toBe('previous-month')
    await waitFor(() => expect(calls).toContain('/api/entries?from=2026-08-01&to=2026-08-31&accountId=1&page=0&size=50'))
    expect(calls).toContain('/api/entries/summary?from=2026-08-01&to=2026-08-31&accountId=1')
    fireEvent.change(period, { target: { value: 'this-year' } })
    await waitFor(() => expect(where).toBe('?period=this-year&accountId=1'))
    await waitFor(() => expect(calls).toContain('/api/entries?from=2026-01-01&to=2026-12-31&accountId=1&page=0&size=50'))
    fireEvent.change(period, { target: { value: 'all' } })
    await waitFor(() => expect(where).toBe('?accountId=1'))
  })

  it('makes the month of the latest entry the whole ledger’s, and asks for no other month first', async () => {
    renderEntries('/entries?period=latest-entry-month&q=shop')
    await waitFor(() => expect(calls).toContain('/api/entries?from=2026-08-01&to=2026-08-31&q=shop&page=0&size=50'))
    // The latest entry up to today, without the page's other filters, before any list request.
    expect(calls[calls.findIndex((c) => c.startsWith('/api/entries?size=1'))]).toBe('/api/entries?size=1&to=2026-09-30')
    expect(calls.filter((c) => c.startsWith('/api/entries?') && !c.includes('size=1')).every((c) => c.includes('2026-08-01')))
      .toBe(true)
  })

  it('turns typed dates into Custom, keeping the other end', async () => {
    renderEntries('/entries?period=previous-month')
    const period = await screen.findByRole('combobox', { name: 'Period' }) as HTMLSelectElement
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-08-15' } })
    await waitFor(() => expect(where).toBe('?from=2026-08-15&to=2026-08-31'))
    expect(period.value).toBe('custom')
    await waitFor(() => expect(calls).toContain('/api/entries?from=2026-08-15&to=2026-08-31&page=0&size=50'))
    expect(entriesPeriod(new URLSearchParams('to=2026-08-31'))).toBe('custom')
    expect(entriesPeriod(new URLSearchParams(''))).toBe('all')
    expect(entriesPeriod(new URLSearchParams('period=last-year'))).toBe('previous-year')
    expect(entriesPeriod(new URLSearchParams('period=forever'))).toBe('all')
  })
})

describe('Entries’ summary row (D-524)', () => {
  it('says each column’s figures for every matching entry', () => {
    const texts = summaryTexts(summary)
    expect(plain(texts.date)).toBe('214 entries · Sep 1 – 30, 2026')
    expect(texts.payee).toBe('12 payees · 3 without')
    expect(texts.category).toBe('7 categories')
    expect(texts.accounts).toBe('4 accounts')
    expect(texts.amounts.map((l) => `${l.label} ${plain(l.text)}`)).toEqual([
      'In +€2,000.00', 'Out -€1,234.50', 'Net +€765.50', 'Out -$20.00', 'Transfers $300.00'])
    const one = summaryTexts({ ...summary, count: 1, firstDate: '2026-09-05', lastDate: '2026-09-05', payees: 1,
      withoutPayee: 0, categories: 1, withoutCategory: 1, accounts: 1 })
    expect(plain(one.date)).toBe('1 entry · Sep 5, 2026')
    expect(one.payee).toBe('1 payee')
    expect(one.category).toBe('1 category · 1 without')
  })

  it('shows a currency’s lines only when not 0, the Net only with both In and Out', () => {
    const lines = (i: string, o: string, t: string) =>
      amountLines({ currency: 'EUR', incoming: i, outgoing: o, transfers: t, ...noFamily(i, o) })
        .map((l) => `${l.label} ${plain(l.text)}`)
    expect(lines('0', '0', '0')).toEqual([])
    expect(lines('5.00', '0', '0')).toEqual(['In +€5.00'])
    expect(lines('0', '5.00', '0')).toEqual(['Out -€5.00'])
    expect(lines('5.00', '5.00', '0')).toEqual(['In +€5.00', 'Out -€5.00', 'Net €0.00'])
    expect(lines('5.00', '7.25', '1.00')).toEqual(['In +€5.00', 'Out -€7.25', 'Net -€2.25', 'Transfers €1.00'])
  })

  it('stands under the column headers, labelled, and asks again when the filters change, not the page', async () => {
    renderEntries('/entries')
    const row = await screen.findByRole('rowgroup', { name: 'Summary of all matching entries' })
    expect(plain(row.textContent)).toContain('214 entries')
    expect(plain(row.querySelector('td.sum-payee')!.textContent)).toBe('Payees12 payees · 3 without')
    const amounts = row.querySelector('td.sum-amount')!
    expect([...amounts.querySelectorAll('.amount-line')].map((l) => plain(l.textContent)))
      .toEqual(['In +€2,000.00', 'Out -€1,234.50', 'Net +€765.50', 'Out -$20.00', 'Transfers $300.00'])
    // The labels of its lines under 40rem.
    expect([...row.querySelectorAll('.summary-label')].map((l) => l.textContent))
      .toEqual(['Entries', 'Payees', 'Categories', 'Accounts', 'Amount'])
    const before = calls.filter((c) => c.startsWith('/api/entries/summary')).length
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'rent' } })
    await waitFor(() => expect(calls).toContain('/api/entries/summary?q=rent'))
    expect(calls.filter((c) => c.startsWith('/api/entries/summary')).length).toBe(before + 1)
  })
})

describe('a family record’s payee on an entry (D-523)', () => {
  it('fills the Payee cell after the entry’s own payee and its lines’ counterparties, as any payee', async () => {
    const share = entry(5, '2026-09-10', {
      kind: 'FAMILY_SHARE',
      family: { ledgerId: 7, ledgerName: 'Home', recordId: 70, link: 'SHARE', readOnly: true, recordType: 'EXPENSE',
        recordPayee: { name: 'Albert Heijn' } },
    })
    const own = entry(6, '2026-09-11', { payeeId: 23, family: { ...share.family!, recordPayee: { name: 'Elsewhere' } } })
    const hidden = entry(7, '2026-09-12', { kind: 'FAMILY_SHARE', family: { ...share.family!, recordPayee: undefined } })
    renderEntries('/entries', [hidden, own, share])
    const rows = await screen.findAllByRole('row')
    const payee = (id: number) => rows.find((r) => r.querySelector(`a[href="/entries/${id}"]`))!.querySelector('td.payee')!
    expect(payee(5).textContent).toBe('Albert Heijn')
    expect(payee(5).querySelector('.muted')).toBeNull()
    expect(payee(6).textContent).toBe('Employer')
    expect(payee(7).textContent).toBe('—')
  })
})

describe('Accounts on a date, and compared (D-525, D-526)', () => {
  it('reads its days from the URL, today and the month before by default, in date order', () => {
    const days = (query: string) => accountDays(new URLSearchParams(query), '2026-09-30')
    expect(days('')).toEqual({ on: '2026-09-30', compare: null, days: ['2026-09-30'] })
    expect(days('on=2026-06-15')).toEqual({ on: '2026-06-15', compare: null, days: ['2026-06-15'] })
    expect(days('on=2026-02-30')).toEqual({ on: '2026-09-30', compare: null, days: ['2026-09-30'] })
    expect(days('compare=2026-08-31').days).toEqual(['2026-08-31', '2026-09-30'])
    // Whichever was typed first: the earlier day comes first.
    expect(days('on=2026-01-31&compare=2026-08-31').days).toEqual(['2026-01-31', '2026-08-31'])
    expect(defaultCompare('2026-09-30')).toBe('2026-08-31')
    expect(defaultCompare('2026-01-15')).toBe('2025-12-31')
    expect(defaultCompare('2026-03-31')).toBe('2026-02-28')
  })

  it('compares each currency: the change, −100%, and “—” across 0 and from 0', () => {
    const line = (earlier: string | null, later: string | null) => {
      const [l] = comparedLines(earlier === null ? [] : [{ currency: 'EUR', amount: earlier }],
        later === null ? [] : [{ currency: 'EUR', amount: later }])
      return `${l.change} ${plain(l.percent)}`
    }
    expect(line('100.00', '150.00')).toBe('50 +50.0%')
    expect(line('100.00', '0.00')).toBe('-100 −100.0%')
    expect(line('100.00', null)).toBe('-100 −100.0%')
    expect(line('-20.00', '30.00')).toBe('50 —')
    expect(line('0.00', '30.00')).toBe('30 —')
    expect(line(null, '30.00')).toBe('30 —')
    expect(comparedLines([{ currency: 'USD', amount: '1' }], [{ currency: 'EUR', amount: '2' }]).map((l) => l.currency))
      .toEqual(['EUR', 'USD'])
  })

  const acct = (id: number, code: string, name: string, type: Account['type'], extra: Partial<Account> = {}): Account =>
    ({ id, code, name, type, defaultCurrency: 'EUR', requiresCounterparty: false, system: false, archived: false,
      ...extra } as Account)
  const cash = acct(1, 'CASH', 'Cash', 'ASSET')
  const old = acct(2, 'OLD', 'Old account', 'ASSET', { archived: true })
  const card = acct(3, 'CARD', 'Card', 'LIABILITY')
  const bal = (a: Account, currency: string, amount: string): AccountBalance => ({
    accountId: a.id, accountCode: a.code, accountName: a.name, accountType: a.type, currency, balance: amount,
    ...(a.archived ? { archived: true } : {}),
  })
  const byDay: Record<string, AccountBalance[]> = {
    '2026-09-30': [bal(cash, 'EUR', '150.00'), bal(card, 'EUR', '-20.00')],
    '2026-08-31': [bal(cash, 'EUR', '100.00'), bal(old, 'EUR', '40.00'), bal(card, 'EUR', '30.00')],
    '2026-06-15': [bal(cash, 'EUR', '10.00')],
  }
  function renderAccounts(path: string) {
    stubApi({ '/api/accounts': [cash, old, card] }, (path, params) =>
      path === '/api/reports/balances' ? byDay[params.get('asOf')!] ?? [] : undefined)
    return render(
      <WithMe>
        <MemoryRouter initialEntries={[path]}>
          <Routes><Route path="/accounts" element={<><Accounts /><Where /></>} /></Routes>
        </MemoryRouter>
      </WithMe>,
    )
  }
  const cells = (row: Element) => [...row.querySelectorAll('td.amount')].map((td) =>
    [...td.children].map((c) => plain(c.textContent)).join(' | '))

  it('shows the balances on today by default, and on a day typed, kept in the URL', async () => {
    renderAccounts('/accounts')
    await screen.findByRole('link', { name: 'Cash' })
    expect(calls).toContain('/api/reports/balances?asOf=2026-09-30')
    fireEvent.change(screen.getByLabelText('Balances on'), { target: { value: '2026-06-15' } })
    await waitFor(() => expect(where).toBe('?on=2026-06-15'))
    await waitFor(() => expect(calls).toContain('/api/reports/balances?asOf=2026-06-15'))
    await waitFor(() => expect(cells(screen.getByRole('link', { name: 'Cash' }).closest('tr')!)).toEqual(['€10.00']))
  })

  it('compares with the month before by default: the earlier day, the later, the change and %, totals too', async () => {
    renderAccounts('/accounts')
    await screen.findByRole('link', { name: 'Cash' })
    // The archived account isn't listed on today alone.
    expect(screen.queryByRole('link', { name: 'Old account' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Compare' }))
    await waitFor(() => expect(where).toBe('?compare=2026-08-31'))
    await waitFor(() => expect(calls).toContain('/api/reports/balances?asOf=2026-08-31'))
    const table = await screen.findByRole('table')
    await waitFor(() => expect(within(table).getAllByRole('columnheader').map((h) => plain(h.textContent)))
      .toEqual(['Account', 'Aug 31, 2026', 'Sep 30, 2026', 'Change', '%', 'Actions']))
    expect(cells(screen.getByRole('link', { name: 'Cash' }).closest('tr')!))
      .toEqual(['€100.00', '€150.00', '+€50.00', '+50.0%'])
    // Listed while it is listed on either day: −100%.
    expect(cells(screen.getByRole('link', { name: 'Old account' }).closest('tr')!))
      .toEqual(['€40.00', '—', '-€40.00', '−100.0%'])
    // Across 0: "—", only signed, no colour.
    const cardRow = screen.getByRole('link', { name: 'Card' }).closest('tr')!
    expect(cells(cardRow)).toEqual(['€30.00', '-€20.00', '-€50.00', '—'])
    expect(cardRow.querySelector('.income, .expense')).toBeNull()
    const assets = screen.getByRole('rowgroup', { name: 'Assets' })
    expect(cells(assets.querySelector('tr.type-total')!)).toEqual(['€140.00', '€150.00', '+€10.00', '+7.1%'])
    fireEvent.click(screen.getByRole('button', { name: 'End the comparison' }))
    await waitFor(() => expect(where).toBe(''))
  })

  it('puts the earlier day first whichever was typed first, and keeps both in the URL', async () => {
    renderAccounts('/accounts?on=2026-06-15&compare=2026-08-31')
    const table = await screen.findByRole('table')
    await waitFor(() => expect(within(table).getAllByRole('columnheader').map((h) => plain(h.textContent)).slice(1, 3))
      .toEqual(['Jun 15, 2026', 'Aug 31, 2026']))
    expect((screen.getByLabelText('Balances on') as HTMLInputElement).value).toBe('06/15/2026')
    expect((screen.getByLabelText('Compared with') as HTMLInputElement).value).toBe('08/31/2026')
    expect(cells(screen.getByRole('link', { name: 'Cash' }).closest('tr')!))
      .toEqual(['€10.00', '€100.00', '+€90.00', '+900.0%'])
  })

  it('is one table with a group per type, each labelled by its heading', async () => {
    renderAccounts('/accounts')
    await screen.findByRole('link', { name: 'Cash' })
    expect(screen.getAllByRole('table')).toHaveLength(1)
    expect(screen.getAllByRole('rowgroup').filter((g) => g.classList.contains('account-type'))
      .map((g) => g.getAttribute('aria-labelledby'))).toEqual(['account-type-ASSET', 'account-type-LIABILITY'])
    expect(screen.getByRole('heading', { name: 'Assets', level: 3 })).toBeDefined()
  })
})

describe('the Categories page’s icon buttons (D-527)', () => {
  const categories: Category[] = [
    ...testLedger.categories,
    { id: 14, code: 'OLD', name: 'Old hobby', type: 'EXPENSE', archived: true },
    { id: 15, code: 'HOME_FOOD', name: 'Food', type: 'EXPENSE', archived: false, familyLedgerId: 7, familyLedgerName: 'Home' },
  ]

  it('names each by the action and the category, in aria-label and title, with an icon and no word', async () => {
    stubApi({ '/api/categories': categories })
    render(<WithMe><MemoryRouter><Categories /></MemoryRouter></WithMe>)
    const rename = await screen.findByRole('button', { name: 'Rename Groceries' })
    expect(rename.getAttribute('title')).toBe('Rename Groceries')
    expect(rename.textContent).toBe('')
    expect(rename.querySelector('svg')!.getAttribute('aria-hidden')).toBe('true')
    expect(rename.className).toContain('icon-button')
    const archive = screen.getByRole('button', { name: 'Archive Groceries' })
    expect(archive.getAttribute('title')).toBe('Archive Groceries')
    fireEvent.click(screen.getByRole('checkbox', { name: /Show archived/ }))
    const restore = await screen.findByRole('button', { name: 'Restore Old hobby' })
    expect(restore.getAttribute('title')).toBe('Restore Old hobby')
    const family = screen.getByRole('link', { name: 'Change Food in the family budget Home' })
    expect(family.getAttribute('href')).toBe('/family/7/categories')
    expect(family.getAttribute('title')).toBe('Change Food in the family budget Home')
    expect(family.className).toContain('icon-button')
    // The rename form keeps its words.
    fireEvent.click(rename)
    expect(screen.getByRole('button', { name: 'Save' })).toBeDefined()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDefined()
  })
})
