import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Account, AccountBalance, CashFlowRow, ConvertedBalance, ConvertedCashFlow, Me } from './api'
import { balancesDay, baseEquitySplit, equitySplits, listedInBalances, resultRange, type CashFlowTable } from './dashboard'

// UX-3: an archived account below a minor unit not listed (D-364), the refusal to archive an account with money on the
// Accounts page (D-366), and equity by period in the Balances card (D-367 to D-369).

const ME: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: false } }

const own = (accountId: number, accountCode: string, accountName: string, accountType: AccountBalance['accountType'],
  currency: string, balance: string, extra: Partial<AccountBalance> = {}): AccountBalance =>
  ({ accountId, accountCode, accountName, accountType, currency, balance, ...extra })
const base = (b: AccountBalance, balance: string | null): ConvertedBalance => ({
  accountId: b.accountId, accountCode: b.accountCode, accountName: b.accountName, accountType: b.accountType,
  currency: 'EUR', balance, missingRates: [], rates: [], ...(b.archived ? { archived: true } : {}),
})

// Assets 1405.00 EUR and USD 100; the reserve accounts 1200.00 EUR (UNALLOCATED 900, RESERVE 300) and USD 100.
const cash = own(1, 'CASH', 'Cash', 'ASSET', 'EUR', '1405.00')
const dollars = own(2, 'USD_ACCOUNT', 'Dollar account', 'ASSET', 'USD', '100.00')
const wallet = own(3, 'OLD_WALLET', 'Old wallet', 'ASSET', 'RUB', '0.004', { archived: true })
const savings = own(4, 'OLD_SAVINGS', 'Old savings', 'ASSET', 'EUR', '0.01', { archived: true })
const unallocated = own(5, 'UNALLOCATED', 'Unallocated', 'EQUITY', 'EUR', '900.00')
const unallocatedUsd = own(5, 'UNALLOCATED', 'Unallocated', 'EQUITY', 'USD', '100.00')
const reserve = own(6, 'RESERVE', 'Reserve', 'EQUITY', 'EUR', '300.00')
const opening = own(7, 'OPENING_BALANCE', 'Opening balance', 'EQUITY', 'EUR', '200.00')
const fx = own(8, 'FX_EXCHANGE', 'Currency exchange', 'EQUITY', 'EUR', '5.01')
const balances = [cash, dollars, wallet, savings, unallocated, unallocatedUsd, reserve, opening, fx]
// USD 100 at 1.25 is €80.00. Net worth €1485.01: assets 1405.00 + 80.00 + 0.01; equity 980 + 300 + 200 + 5.01.
const baseBalances = [base(cash, '1405.00'), base(dollars, '80.00'), base(wallet, '0.00'), base(savings, '0.01'),
  base(unallocated, '980.00'), base(reserve, '300.00'), base(opening, '200.00'), base(fx, '5.01')]
const worth = { currency: 'EUR', assets: '1485.01', liabilities: '0.00', netWorth: '1485.01', unrealizedRevaluation: '1.50',
  realizedExchangeResult: '0.00', rates: [], missingRates: [] }

const flow = (month: string, categoryCode: string, categoryType: 'INCOME' | 'EXPENSE', currency: string,
  total: string): CashFlowRow => ({ month, categoryCode, categoryName: categoryCode, categoryType, currency, total })
// September to the 30th: EUR income 1000, expenses 400.25, USD expenses 20; to the 15th: EUR income 1000 only.
const september = [flow('2026-09', 'SALARY', 'INCOME', 'EUR', '1000.00'),
  flow('2026-09', 'GROCERIES', 'EXPENSE', 'EUR', '400.25'), flow('2026-09', 'TRAVEL', 'EXPENSE', 'USD', '20.00')]
const toThe15th = [flow('2026-09', 'SALARY', 'INCOME', 'EUR', '1000.00')]
const converted = (rows: CashFlowRow[]): ConvertedCashFlow => ({
  currency: 'EUR',
  rows: rows.map((r) => ({ month: r.month, categoryCode: r.categoryCode, categoryName: r.categoryName,
    categoryType: r.categoryType, total: r.currency === 'USD' ? '16.13' : r.total, missingRates: [], rates: [] })),
  exchangeResults: [],
} as unknown as ConvertedCashFlow)

let calls: string[] = []
function stubApi({ patchAnswer }: { patchAnswer?: { status: number; body: unknown } } = {}) {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push(`${init?.method ?? 'GET'} ${url}`)
    const [path, query = ''] = url.split('?')
    const params = new URLSearchParams(query)
    const inBase = params.get('currency') === 'BASE'
    const rows = params.get('to') === '2026-09-15' ? toThe15th : september
    const accounts: Account[] = balances.filter((b, i, all) => all.findIndex((o) => o.accountId === b.accountId) === i)
      .map((b) => ({ id: b.accountId, code: b.accountCode, name: b.accountName, type: b.accountType, defaultCurrency: b.currency,
        requiresCounterparty: false, system: b.accountCode === 'OPENING_BALANCE' || b.accountCode === 'FX_EXCHANGE',
        archived: b.archived === true } as Account))
    if (init?.method === 'PATCH' && patchAnswer) {
      return new Response(JSON.stringify(patchAnswer.body), {
        status: patchAnswer.status, headers: { 'Content-Type': 'application/problem+json' },
      })
    }
    const bodies: Record<string, unknown> = {
      '/api/entries': { content: [], page: 0, size: 1, totalElements: 12, totalPages: 12 },
      '/api/settings': { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' },
      '/api/accounts': accounts,
      '/api/reports/net-worth': inBase ? worth : [{ currency: 'EUR', assets: '1405.01', liabilities: '0.00', netWorth: '1405.01' },
        { currency: 'RUB', assets: '0.004', liabilities: '0.00', netWorth: '0.004' },
        { currency: 'USD', assets: '100.00', liabilities: '0.00', netWorth: '100.00' }],
      '/api/reports/balances': inBase ? baseBalances : balances,
      '/api/reports/shared-settlement': [],
      '/api/reports/counterparty-balances': [],
      '/api/reports/cash-flow': inBase ? converted(rows) : rows,
    }
    const body = bodies[path]
    return new Response(JSON.stringify(body ?? { status: 404 }), {
      status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' },
    })
  }))
}

const renderAt = (path: string, me: Me = ME) => render(<MemoryRouter initialEntries={[path]}><App me={me} /></MemoryRouter>)

async function balancesCard() {
  return (await screen.findByRole('heading', { name: /^Balances on / })).closest('section')!
}
/** A line of the Balances card, by its first cell's text, as shown. */
async function line(label: string) {
  const card = await balancesCard()
  const cell = await within(card).findByText(label, { selector: 'td' })
  return cell.closest('tr')!.querySelector('td.amount')!.textContent
}
const total = async (type: string) =>
  (await within(await balancesCard()).findByRole('columnheader', { name: type })).closest('table')!.querySelector('tfoot td')!.textContent
const net = async (currency?: string) => {
  const block = currency ? await screen.findByLabelText(`Totals in ${currency}`) : (await screen.findAllByLabelText(/^Totals in/))[0]
  const dt = within(block).getAllByRole('term').find((t) => t.textContent === 'Net')!
  return dt.nextElementSibling!.textContent
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** A cash flow table whose only figure periodTotals reads for the net is `net`. */
const table = (net: string, currency: string): CashFlowTable => {
  const empty = { key: 'x', label: 'x', months: [], total: null }
  return { currency, months: [], income: { lines: [], subtotal: empty }, expense: { lines: [], subtotal: empty },
    net: { ...empty, total: net }, converted: false, exchange: [] }
}
/** Text as shown, with no-break spaces as spaces. */
const plain = (text: string | null | undefined) => text?.replace(/\u00a0/g, ' ')

describe('the logic', () => {
  it('lists an archived account only while an amount of it does not round to 0 (D-364)', () => {
    expect(listedInBalances([{ currency: 'RUB', balance: '0.004' }], true)).toBe(false)
    expect(listedInBalances([{ currency: 'RUB', balance: '0.004' }, { currency: 'EUR', balance: '0.01' }], true)).toBe(true)
    expect(listedInBalances([{ currency: 'JPY', balance: '0.4' }], true)).toBe(false)
    expect(listedInBalances([{ currency: 'EUR', balance: '0.00' }], false)).toBe(true)
  })

  it('takes the balances as of To, or today while the period runs (D-430, D-443)', () => {
    expect(balancesDay({ to: '2026-09-30' }, '2026-11-10')).toBe('2026-09-30')
    expect(balancesDay({ to: '2026-11-10' }, '2026-11-10')).toBe('2026-11-10')
    expect(balancesDay({ to: '2026-11-30' }, '2026-11-10')).toBe('2026-11-10')
  })

  it('takes the result from From to the balances’ day, and none before From (D-367, D-430, D-443)', () => {
    expect(resultRange({ from: '2026-09-01', to: '2026-09-30' }, '2026-11-10')).toEqual({ from: '2026-09-01', to: '2026-09-30' })
    expect(resultRange({ from: '2026-11-01', to: '2026-11-30' }, '2026-11-10')).toEqual({ from: '2026-11-01', to: '2026-11-10' })
    // A period that starts after today, and one that ends before it starts.
    expect(resultRange({ from: '2026-12-01', to: '2026-12-31' }, '2026-11-10')).toBeNull()
    expect(resultRange({ from: '2026-09-01', to: '2026-08-15' }, '2026-11-10')).toBeNull()
  })

  it('splits each currency’s reserve accounts into the reserve and the result, which add up to them', () => {
    const tables = [
      { currency: 'EUR', net: '599.75' }, { currency: 'USD', net: '-20.00' },
    ].map(({ currency, net }) => table(net, currency))
    const splits = equitySplits(balances, tables)
    expect(splits).toEqual([
      { currency: 'EUR', result: '599.75', reserve: '600.25' },
      { currency: 'USD', result: '-20.00', reserve: '120' },
    ])
    expect(equitySplits(balances, null)).toEqual([
      { currency: 'EUR', result: '0', reserve: '1200' }, { currency: 'USD', result: '0', reserve: '100' },
    ])
  })

  it('in the base currency, the reserve is the rest of net worth at the day’s rates (D-369)', () => {
    const t = table('583.62', 'EUR')
    // 1485.01 - 200.00 - 5.01 - 583.62
    expect(baseEquitySplit(baseBalances, '1485.01', t)).toEqual({ result: '583.62', reserve: '696.38' })
    expect(baseEquitySplit(baseBalances, '1485.01', null)).toEqual({ result: '0', reserve: '1280' })
    const missing = baseEquitySplit([...baseBalances.slice(0, 6), { ...baseBalances[6], balance: null }], '1485.01', t)
    expect(typeof missing.reserve).toBe('symbol')
  })
})

describe('the Balances card in each currency', () => {
  it('shows the reserve and the result in place of the reserve accounts, Equity’s total unchanged (D-367)', async () => {
    stubApi()
    renderAt('/?currency=each&period=this-month')
    // A transfer of 300 to the reserve within the period stays in the reserve: 1200 - (1000 - 400.25).
    expect(await line('Accumulated reserve')).toBe('€600.25$120.00')
    expect(await line('Result for the period')).toBe('€599.75-$20.00')
    // The Net of income and expenses, shown signed there.
    expect(await net('EUR')).toBe('+€599.75')
    expect(await net('USD')).toBe('-$20.00')
    const card = await balancesCard()
    expect(within(card).queryByRole('link', { name: 'Unallocated' })).toBeNull()
    expect(within(card).queryByRole('link', { name: 'Reserve' })).toBeNull()
    expect(within(card).getByRole('link', { name: 'Opening balance' })).toBeDefined()
    expect(within(card).getByRole('link', { name: 'Currency exchange' })).toBeDefined()
    // 900 + 300 + 200 + 5.01, and USD 100: as before.
    expect(await total('Equity')).toBe('€1,405.01$100.00')
  })

  it('ignores an old link’s asOf: the result runs from From to To, every balance as of To (D-430)', async () => {
    stubApi()
    renderAt('/?currency=each&from=2026-09-01&to=2026-09-30&asOf=2026-09-15')
    expect(await line('Result for the period')).toBe('€599.75-$20.00')
    expect(await line('Accumulated reserve')).toBe('€600.25$120.00')
    expect(calls.filter((c) => c.includes('2026-09-15'))).toEqual([])
    expect(calls).toContain('GET /api/reports/balances?asOf=2026-09-30')
    expect(calls).toContain('GET /api/reports/net-worth?asOf=2026-09-30')
    expect(calls).toContain('GET /api/reports/shared-settlement?asOf=2026-09-30')
    expect(calls).toContain('GET /api/reports/counterparty-balances?accountCode=LOANS_ASSET&asOf=2026-09-30')
  })

  it('in a running period asks the balances as of today and the result to today, no day after it (D-443)', async () => {
    stubApi()
    // Today is 2026-09-30: income and expenses, the balances and the result all run to today, in one cash flow.
    renderAt('/?currency=each&from=2026-09-01&to=2026-10-31')
    expect(await line('Result for the period')).toBe('€599.75-$20.00')
    expect(calls).toContain('GET /api/reports/balances?asOf=2026-09-30')
    expect(calls).toContain('GET /api/reports/net-worth?asOf=2026-09-30')
    expect(calls.filter((c) => c.includes('2026-10-31'))).toEqual([])
    expect(calls.filter((c) => c.startsWith('GET /api/reports/cash-flow?from=2026-09-01&to=2026-09-30'))).toHaveLength(1)
  })

  it('names its day in its title, in the user’s date format, in both modes (D-431)', async () => {
    stubApi()
    renderAt('/?currency=each&from=2026-09-01&to=2026-10-31')
    const card = await balancesCard()
    const title = within(card).getByRole('heading', { level: 3 })
    // Without a format of the user's, as the browser shows dates, as "Income and expenses" shows its period; while
    // the period runs, today (D-443).
    expect(title.textContent).toBe('Balances on Sep 30, 2026')
    expect(title.querySelector('.muted.period')!.textContent).toBe('on Sep 30, 2026')
    cleanup()
    stubApi()
    renderAt('/?period=previous-month', { ...ME, dateFormat: 'DD.MM.YYYY' })
    expect((await balancesCard()).querySelector('h3')!.textContent).toBe('Balances on 31.08.2026')
    // No "Balances on" field any more (D-430).
    expect(screen.queryByLabelText(/Balances on/)).toBeNull()
  })

  it('hides an archived account whose amounts are all below a minor unit, and counts it in the total (D-364)', async () => {
    stubApi()
    renderAt('/?currency=each&period=this-month')
    const card = await balancesCard()
    expect(await within(card).findByRole('link', { name: 'Old savings' })).toBeDefined()
    expect(within(card).queryByRole('link', { name: 'Old wallet' })).toBeNull()
    expect(plain(await total('Assets'))).toBe('€1,405.01RUB 0.004$100.00')
  })
})

describe('the Balances card in the base currency', () => {
  it('shows the result as the Net above it, the reserve as the rest, and a note (D-367, D-369)', async () => {
    stubApi()
    renderAt('/')
    // The Net: 1000.00 - 400.25 - 16.13 at each day's rate.
    expect(await line('Result for the period')).toBe('€583.62')
    expect(await net()).toBe('+€583.62')
    // 1485.01 - 200.00 - 5.01 - 583.62: the lines add up to Equity's total, net worth.
    expect(await line('Accumulated reserve')).toBe('€696.38')
    expect(await total('Equity')).toBe('€1,485.01')
    const card = await balancesCard()
    expect(within(card).getByText(/includes the revaluation/)).toBeDefined()
    expect(within(card).queryByRole('link', { name: 'Unallocated' })).toBeNull()
    expect(within(card).queryByRole('link', { name: 'Old wallet' })).toBeNull()
    expect(within(card).getByRole('link', { name: 'Old savings' })).toBeDefined()
  })

  it('ignores an old link’s asOf: the result is the Net of the period, the balances as of To (D-430)', async () => {
    stubApi()
    renderAt('/?from=2026-09-01&to=2026-09-30&asOf=2026-09-15')
    expect(await line('Result for the period')).toBe('€583.62')
    expect(await line('Accumulated reserve')).toBe('€696.38')
    expect(calls.filter((c) => c.includes('2026-09-15'))).toEqual([])
    expect(calls).toContain('GET /api/reports/balances?asOf=2026-09-30&currency=BASE')
  })
})

describe('the Accounts page (D-366)', () => {
  it('shows the refusal to archive an account that holds money by the account', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const detail = 'Cash still holds 1405.00 EUR. An account is archived only once it holds nothing: move what remains to another account first.'
    stubApi({ patchAnswer: { status: 409, body: { status: 409, title: 'Conflict', detail } } })
    renderAt('/accounts')
    const row = (await screen.findByRole('link', { name: 'Cash' })).closest('tr')!
    fireEvent.click(within(row).getByRole('button', { name: 'Archive' }))
    // In a row of its own under the account's, across the table.
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe(detail)
    expect(alert.closest('tr')!.previousElementSibling).toBe(row)
    expect(alert.closest('td')!.colSpan).toBe(3)
    expect(calls).toContain('PATCH /api/accounts/1')
  })
})
