import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type {
  AccountBalance, CashFlowRow, ConvertedBalance, ConvertedCashFlow, ConvertedCounterpartyBalance, FamilyLedger, Me,
} from './api'

// UX-1: the dashboard in the base currency by default (D-297), its headline row (D-301), open loans with the family
// budgets (D-298, D-299), no "Shared budget" card (D-300) and the scopes of income and expenses (D-302).

const ON: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }
const OFF: Me = { ...ON, features: { familyLedgers: false } }
const home: FamilyLedger = {
  id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
  createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01',
}
const cottage: FamilyLedger = { ...home, id: 9, name: 'Cottage', memberId: 90 }

const balance = (accountId: number, accountCode: string, accountName: string, accountType: AccountBalance['accountType'],
  currency: string, amount: string, family?: FamilyLedger): AccountBalance => ({
  accountId, accountCode, accountName, accountType, currency, balance: amount,
  ...(family ? { familyLedgerId: family.id, familyLedgerName: family.name } : {}),
})
const balances: AccountBalance[] = [
  balance(1, 'CASH', 'Cash', 'ASSET', 'EUR', '100.00'),
  balance(1, 'CASH', 'Cash', 'ASSET', 'USD', '50.00'),
  balance(1, 'CASH', 'Cash', 'ASSET', 'RUB', '1000.00'),
  balance(2, 'FAMILY_DEBT_7', 'Debt to family budget: Home', 'LIABILITY', 'EUR', '30.00', home),
  balance(3, 'FAMILY_DEBT_9', 'Debt to family budget: Cottage', 'LIABILITY', 'EUR', '-12.50', cottage),
  balance(4, 'FAMILY_DEBT', 'Family budget', 'LIABILITY', 'EUR', '-8.00'),
  balance(5, 'LOANS_ASSET', 'Loans given', 'ASSET', 'EUR', '0.00'),
]
const converted = (b: AccountBalance, amount: string): ConvertedBalance => ({
  accountId: b.accountId, accountCode: b.accountCode, accountName: b.accountName, accountType: b.accountType,
  currency: 'EUR', balance: amount, missingRates: [], rates: [],
  ...(b.familyLedgerId === undefined ? {} : { familyLedgerId: b.familyLedgerId, familyLedgerName: b.familyLedgerName }),
})
const baseBalances = [converted(balances[0], '155.00'), converted(balances[3], '30.00'), converted(balances[4], '-12.50'),
  converted(balances[5], '-8.00'), converted(balances[6], '0.00')]
const row = (currency: string, categoryCode: string, total: string, categoryType: CashFlowRow['categoryType'] = 'EXPENSE'): CashFlowRow =>
  ({ month: '2026-09', categoryCode, categoryName: categoryCode, categoryType, currency, total })
const cashFlow = [row('EUR', 'GROCERIES', '40.00'), row('USD', 'TRAVEL', '20.00'), row('RUB', 'GIFTS', '900.00'),
  row('EUR', 'SALARY', '1000.00', 'INCOME')]
const baseCashFlow: ConvertedCashFlow = {
  currency: 'EUR',
  rows: [
    { month: '2026-09', categoryCode: 'GIFTS', categoryName: 'Gifts', categoryType: 'EXPENSE', total: '9.00', missingRates: [] },
    { month: '2026-09', categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', total: '40.00', missingRates: [] },
    { month: '2026-09', categoryCode: 'TRAVEL', categoryName: 'Travel', categoryType: 'EXPENSE', total: '18.00', missingRates: [] },
    { month: '2026-09', categoryCode: 'SALARY', categoryName: 'Salary', categoryType: 'INCOME', total: '1000.00', missingRates: [] },
  ],
  exchangeResults: [{ month: '2026-09', realized: '0.00', unrealized: '0.00', missingRates: [] }],
}
const friend: ConvertedCounterpartyBalance = {
  counterpartyId: 11, counterpartyName: 'Ivan', currency: 'EUR', balance: '140.00', missingRates: [], rates: [],
  amounts: [{ currency: 'EUR', balance: '100.00' }, { currency: 'USD', balance: '50.00' }],
}

/**
 * The api, by path; every request it saw. `latest` is the day of the latest entry up to today (D-521), which the
 * entry list with `to` answers; none by default.
 */
function stubApi(me: Me, families: FamilyLedger[], latest: string | null = null) {
  const seen: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    seen.push(url)
    const [path, query = ''] = url.split('?')
    const params = new URLSearchParams(query)
    const base = params.get('currency') === 'BASE'
    const family = me.features?.familyLedgers === true
    const bodies: Record<string, unknown> = {
      '/api/entries': params.get('to') && latest
        ? { content: [{ id: 1, version: 0, entryDate: latest, kind: 'EXPENSE', payeeId: null, memo: null, postings: [] }],
          page: 0, size: 1, totalElements: 9, totalPages: 9 }
        : { content: [], page: 0, size: 1, totalElements: 12, totalPages: 12 },
      '/api/settings': { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' },
      '/api/family-ledgers': families,
      '/api/reports/net-worth': base
        ? { currency: 'EUR', assets: '155.00', liabilities: '9.50', netWorth: '145.50', unrealizedRevaluation: '1.20',
          realizedExchangeResult: '0.00', rates: [], missingRates: [] }
        : [{ currency: 'EUR', assets: '100.00', liabilities: '9.50', netWorth: '90.50' },
          { currency: 'RUB', assets: '1000.00', liabilities: '0.00', netWorth: '1000.00' },
          { currency: 'USD', assets: '50.00', liabilities: '0.00', netWorth: '50.00' }],
      '/api/reports/balances': base ? baseBalances
        : balances.map((b) => (family ? b : { ...b, familyLedgerId: undefined, familyLedgerName: undefined })),
      '/api/reports/shared-settlement': [{ accountId: 4, accountCode: 'FAMILY_DEBT', currency: 'EUR', balance: '-8.00',
        direction: 'USER_IS_OWED' }],
      '/api/reports/counterparty-balances': params.get('accountCode') === 'LOANS_ASSET'
        ? (base ? [friend] : [{ counterpartyId: 11, counterpartyName: 'Ivan', currency: 'EUR', balance: '100.00' },
          { counterpartyId: 11, counterpartyName: 'Ivan', currency: 'USD', balance: '50.00' }])
        : [],
      '/api/reports/cash-flow': base ? baseCashFlow : cashFlow,
    }
    const body = bodies[path]
    return new Response(JSON.stringify(body ?? { status: 404 }), {
      status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' },
    })
  }))
  return seen
}

function renderAt(path: string, me: Me = ON) {
  return render(<MemoryRouter initialEntries={[path]}><App me={me} /></MemoryRouter>)
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the dashboard (UX-1)', () => {
  it('is in the base currency by default, and the header’s link opens the defaults', async () => {
    const seen = stubApi(ON, [home])
    renderAt('/')
    await screen.findByRole('table', { name: 'Cash flow in EUR' })
    // September, compared with September 2025 and August (D-395), asked at once (D-401).
    expect(seen.filter((u) => u.startsWith('/api/reports/cash-flow'))).toEqual([
      '/api/reports/cash-flow?from=2026-09-01&to=2026-09-30&currency=BASE',
      '/api/reports/cash-flow?from=2025-09-01&to=2025-09-30&currency=BASE',
      '/api/reports/cash-flow?from=2026-08-01&to=2026-08-31&currency=BASE'])
    expect(seen.some((u) => u.startsWith('/api/reports/net-worth') && u.endsWith('currency=BASE'))).toBe(true)
    expect(seen.some((u) => u.includes('accountCode=LOANS_ASSET') && u.endsWith('currency=BASE'))).toBe(true)
    const amounts = screen.getByRole('group', { name: 'Amounts' })
    expect(within(amounts).getByRole('button', { name: 'EUR (base)' }).getAttribute('aria-pressed')).toBe('true')
    expect(within(amounts).getByRole('button', { name: 'Each currency' }).getAttribute('aria-pressed')).toBe('false')
    // Row 2's Dashboard (D-498).
    expect(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Dashboard' })
      .getAttribute('href')).toBe('/')
  })

  it('shows one chart, one table and one row per account in the base currency, with three currencies', async () => {
    stubApi(ON, [home])
    const { container } = renderAt('/')
    await screen.findByRole('table', { name: 'Cash flow in EUR' })
    expect(container.querySelectorAll('figure.chart')).toHaveLength(1)
    expect(container.querySelectorAll('table.cash-flow')).toHaveLength(1)
    expect(screen.getByRole('link', { name: 'Cash' }).closest('tr')!.querySelectorAll('td.amount')[0].textContent)
      .toBe('€155.00')
    // Each currency on its own, on request.
    fireEvent.click(within(screen.getByRole('group', { name: 'Amounts' })).getByRole('button', { name: 'Each currency' }))
    await screen.findByRole('table', { name: 'Cash flow in RUB' })
    expect(container.querySelectorAll('table.cash-flow')).toHaveLength(3)
    expect(container.querySelectorAll('figure.chart')).toHaveLength(3)
  })

  it('heads the page with what you have, owe and are worth, in that order and of one size (D-301)', async () => {
    stubApi(ON, [home])
    renderAt('/')
    const headline = await screen.findByRole('region', { name: 'What you have and owe' })
    await within(headline).findByText('€145.50')
    const terms = within(headline).getAllByRole('term').map((t) => t.textContent)
    expect(terms).toEqual(['You have', 'You owe', 'Net worth'])
    const [have, , worth] = within(headline).getAllByRole('definition')
    expect(have.textContent).toBe('€155.00')
    // "You have" isn't muted, and is the same element as net worth's, so never smaller.
    expect(have.querySelector('.muted')).toBeNull()
    expect(have.className).toBe(worth.className)
    expect(have.firstElementChild!.className).toBe(worth.firstElementChild!.className)
    expect(headline.textContent).toContain('Revaluation of balances')
  })

  it('has no “Shared budget” card, and the period totals come from the table (D-300, D-305)', async () => {
    stubApi(ON, [home])
    renderAt('/')
    await screen.findByRole('table', { name: 'Cash flow in EUR' })
    expect(screen.queryByRole('heading', { name: 'Shared budget' })).toBeNull()
    expect(screen.queryByText(/Nothing is open with the shared budget/)).toBeNull()
    const income = screen.getAllByRole('term').find((t) => t.textContent === 'Income')!.nextElementSibling!
    const expense = screen.getAllByRole('term').find((t) => t.textContent === 'Expenses')!.nextElementSibling!
    const net = screen.getAllByRole('term').find((t) => t.textContent === 'Net')!.nextElementSibling!
    expect([income.textContent, expense.textContent, net.textContent]).toEqual(['€1,000.00', '€67.00', '+€933.00'])
  })

  it('lists the family budgets in open loans: a debt, a budget that owes, the old shared account (D-299)', async () => {
    stubApi(ON, [home, cottage])
    renderAt('/')
    const loans = (await screen.findByRole('heading', { name: 'Open loans' })).closest('section')!
    await within(loans).findByText('Family budgets')
    const line = (name: string) => within(loans).getByRole('link', { name }).closest('tr')!
    expect(line('Home').querySelectorAll('td')[2].textContent).toBe('€30.00')
    expect(line('Home').querySelector('a')!.getAttribute('href')).toBe('/family/7/balances')
    expect(line('Cottage').querySelectorAll('td')[1].textContent).toBe('€12.50')
    expect(line('Family budget').querySelectorAll('td')[1].textContent).toBe('€8.00')
    expect(line('Family budget').querySelector('a')!.getAttribute('href')).toBe('/entries?accountId=4')
    // A loan in two currencies: one line in the base currency, its own currencies small below.
    expect(line('Ivan').querySelectorAll('td')[1].textContent).toBe('€140.00')
    expect(line('Ivan').textContent).toContain('€100.00 · $50.00')
    // Never found by its name: the debt accounts aren't among the loans by their codes.
    expect(within(loans).queryByText('Debt to family budget: Home')).toBeNull()
  })

  it('with the switch off shows no family budget in open loans and no scope, the old shared account still', async () => {
    stubApi(OFF, [])
    renderAt('/', OFF)
    const loans = (await screen.findByRole('heading', { name: 'Open loans' })).closest('section')!
    await within(loans).findByRole('link', { name: 'Family budget' })
    expect(within(loans).queryByRole('link', { name: 'Home' })).toBeNull()
    expect(loans.querySelector('a[href^="/family/"]')).toBeNull()
    expect(screen.queryByRole('group', { name: 'Show' })).toBeNull()
  })

  it('offers the scopes only with a family budget, and asks for the one chosen (D-302, D-420)', async () => {
    stubApi(ON, [])
    renderAt('/')
    await screen.findByRole('table', { name: 'Cash flow in EUR' })
    expect(screen.queryByRole('group', { name: 'Show' })).toBeNull()
    cleanup()

    const seen = stubApi(ON, [home])
    renderAt('/?period=previous-month')
    const show = await screen.findByRole('group', { name: 'Show' })
    // One budget: its button alone, no "All family budgets", no selector.
    expect(within(show).getAllByRole('button').map((b) => b.textContent)).toEqual(['All', 'Personal', 'Home'])
    expect(within(show).getByRole('button', { name: 'All' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.queryByRole('group', { name: 'Records' })).toBeNull()
    // The scope sits in the income and expenses section, not in the page's controls.
    expect(show.closest('section')!.querySelector('h3')!.textContent).toMatch(/^Income and expenses/)
    fireEvent.click(within(show).getByRole('button', { name: 'Home' }))
    await waitFor(() => expect(seen).toContain(
      '/api/reports/cash-flow?from=2026-08-01&to=2026-08-31&scope=FAMILY&familyLedgerId=7&currency=BASE'))
    expect(within(show).getByRole('button', { name: 'Home' }).getAttribute('aria-pressed')).toBe('true')
    // "Records" appears below it, all records by default.
    const records = screen.getByRole('group', { name: 'Records' })
    expect(within(records).getAllByRole('button').map((b) => b.textContent))
      .toEqual(['All records', 'My records', 'Others’ records'])
    expect(within(records).getByRole('button', { name: 'All records' }).getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(within(records).getByRole('button', { name: 'My records' }))
    await waitFor(() => expect(seen).toContain(
      '/api/reports/cash-flow?from=2026-08-01&to=2026-08-31&scope=FAMILY_MINE&familyLedgerId=7&currency=BASE'))
    expect(within(show).getByRole('button', { name: 'Home' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('link', { name: 'Whole family budget →' }).getAttribute('href'))
      .toBe('/family/7/report?from=2026-08-01&to=2026-08-31')
    expect(screen.queryByRole('combobox', { name: /Family budget/ })).toBeNull()
    // Personal drops the budget and "Records".
    fireEvent.click(within(show).getByRole('button', { name: 'Personal' }))
    await waitFor(() => expect(seen).toContain(
      '/api/reports/cash-flow?from=2026-08-01&to=2026-08-31&scope=PERSONAL&currency=BASE'))
    expect(screen.queryByRole('group', { name: 'Records' })).toBeNull()
    // The balances stay the whole ledger's.
    expect(seen.filter((u) => u.startsWith('/api/reports/balances')).every((u) => !u.includes('scope'))).toBe(true)
  })

  it('opens an old link with scope=family-mine as the one budget pressed, with my records', async () => {
    const seen = stubApi(ON, [home])
    renderAt('/?scope=family-mine')
    const show = await screen.findByRole('group', { name: 'Show' })
    expect(within(show).getByRole('button', { name: 'Home' }).getAttribute('aria-pressed')).toBe('true')
    const records = screen.getByRole('group', { name: 'Records' })
    expect(within(records).getByRole('button', { name: 'My records' }).getAttribute('aria-pressed')).toBe('true')
    await waitFor(() => expect(seen).toContain(
      '/api/reports/cash-flow?from=2026-09-01&to=2026-09-30&scope=FAMILY_MINE&familyLedgerId=7&currency=BASE'))
  })

  it('with two budgets offers "All family budgets" and each budget by name (D-420)', async () => {
    const seen = stubApi(ON, [home, cottage])
    renderAt('/?scope=family-others')
    const show = await screen.findByRole('group', { name: 'Show' })
    expect(within(show).getAllByRole('button').map((b) => b.textContent))
      .toEqual(['All', 'Personal', 'All family budgets', 'Cottage', 'Home'])
    // An old link without a budget: all of them, others' records.
    expect(within(show).getByRole('button', { name: 'All family budgets' }).getAttribute('aria-pressed')).toBe('true')
    const records = screen.getByRole('group', { name: 'Records' })
    expect(within(records).getByRole('button', { name: 'Others’ records' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getAllByRole('link', { name: /^Whole family budget: / }).map((l) => l.textContent))
      .toEqual(['Whole family budget: Home →', 'Whole family budget: Cottage →'])
    // A budget's button keeps the records chosen.
    fireEvent.click(within(show).getByRole('button', { name: 'Cottage' }))
    await waitFor(() => expect(seen).toContain(
      '/api/reports/cash-flow?from=2026-09-01&to=2026-09-30&scope=FAMILY_OTHERS&familyLedgerId=9&currency=BASE'))
    expect(screen.getByRole('link', { name: 'Whole family budget: Cottage →' })).toBeDefined()
    fireEvent.click(within(screen.getByRole('group', { name: 'Records' })).getByRole('button', { name: 'All records' }))
    await waitFor(() => expect(seen).toContain(
      '/api/reports/cash-flow?from=2026-09-01&to=2026-09-30&scope=FAMILY&familyLedgerId=9&currency=BASE'))
    fireEvent.click(within(show).getByRole('button', { name: 'All family budgets' }))
    await waitFor(() => expect(seen).toContain(
      '/api/reports/cash-flow?from=2026-09-01&to=2026-09-30&scope=FAMILY&currency=BASE'))
    expect(screen.queryByRole('combobox', { name: /Family budget/ })).toBeNull()
  })

  it('chooses a period from the Period list, and shows Custom for a custom period (D-432)', async () => {
    const seen = stubApi(ON, [home])
    renderAt('/')
    const period = await screen.findByRole('combobox', { name: 'Period' }) as HTMLSelectElement
    expect([...period.options].map((o) => o.textContent)).toEqual(['Month of the latest entry', 'This week',
      'Previous week', 'Past month', 'Previous month', 'This month', 'This quarter', 'Previous quarter', 'Last 3 months',
      'Past year', 'This year', 'Previous year', 'Custom'])
    // D-521: first and chosen; with no entry up to today, it is this month.
    expect(period.value).toBe('latest-entry-month')
    // Custom can't be chosen.
    expect([...period.options].filter((o) => o.disabled).map((o) => o.value)).toEqual(['custom'])
    expect(screen.queryByRole('group', { name: 'Period' })).toBeNull()
    // A choice does what its button did: the preset by name in the URL, its From and To, its requests.
    fireEvent.change(period, { target: { value: 'previous-month' } })
    await waitFor(() => expect(period.value).toBe('previous-month'))
    expect((screen.getByLabelText('From') as HTMLInputElement).value).toBe('08/01/2026')
    expect((screen.getByLabelText('To') as HTMLInputElement).value).toBe('08/31/2026')
    await waitFor(() => expect(seen).toContain('/api/reports/cash-flow?from=2026-08-01&to=2026-08-31&currency=BASE'))
    expect(seen).toContain('/api/reports/balances?asOf=2026-08-31&currency=BASE')
    fireEvent.change(period, { target: { value: 'this-month' } })
    await waitFor(() => expect(period.value).toBe('this-month'))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-09-16' } })
    await waitFor(() => expect(period.value).toBe('custom'))
    // A range that isn't whole months is named by its days in the table (D-395).
    const table = await screen.findByRole('table', { name: 'Cash flow in EUR' })
    await waitFor(() => expect(within(table).getAllByRole('columnheader')[1].textContent?.replace(/[\u2009\u202f]/g, ' '))
      .toBe('Period Sep 16 – 30, 2026'))
  })

  it('opens the month of the latest entry, and asks for no other period before it knows it (D-521)', async () => {
    const seen = stubApi(ON, [home], '2026-07-14')
    renderAt('/')
    const period = await screen.findByRole('combobox', { name: 'Period' }) as HTMLSelectElement
    expect(period.value).toBe('latest-entry-month')
    expect((screen.getByLabelText('From') as HTMLInputElement).value).toBe('07/01/2026')
    expect((screen.getByLabelText('To') as HTMLInputElement).value).toBe('07/31/2026')
    await waitFor(() => expect(seen).toContain('/api/reports/cash-flow?from=2026-07-01&to=2026-07-31&currency=BASE'))
    // The latest entry was asked first, up to today; no report for this month, ever.
    const latestAt = seen.findIndex((u) => /^\/api\/entries\?size=1&to=\d{4}-\d{2}-\d{2}$/.test(u))
    const firstReport = seen.findIndex((u) => u.startsWith('/api/reports/'))
    expect(latestAt).toBeGreaterThanOrEqual(0)
    expect(latestAt).toBeLessThan(firstReport)
    expect(seen.filter((u) => u.startsWith('/api/reports/') && u.includes('2026-09'))).toEqual([])
    // An earlier month's balances are those of its last day (D-430, D-443).
    expect(seen).toContain('/api/reports/balances?asOf=2026-07-31&currency=BASE')
    // "This month" still works by its name.
    fireEvent.change(period, { target: { value: 'this-month' } })
    await waitFor(() => expect(period.value).toBe('this-month'))
    await waitFor(() => expect(seen.some((u) => u.startsWith('/api/reports/cash-flow?from=2026-09-01'))).toBe(true))
  })
})
