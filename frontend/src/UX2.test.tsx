import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type {
  AccountBalance, Category, ConvertedBalance, ConvertedCounterpartyBalance, Entry, EntryPage, FamilyLedger, Me,
} from './api'
import Categories from './Categories'
import { belowMinorUnit } from './dashboard'
import Entries from './Entries'
import { testLedger } from './testLedger'
import { WithMe } from './testMe'

// UX-2: the Balances card's totals and the headline agree (D-348), archived accounts with money listed (D-355), open
// loans without amounts below a minor unit (D-349), "Owed to you" (D-350), the entry list and the category lists on
// a phone (D-351, D-352).

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
const ME: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }
const home: FamilyLedger = {
  id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
  createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01',
}

const own = (accountId: number, accountCode: string, accountName: string, accountType: AccountBalance['accountType'],
  currency: string, balance: string, extra: Partial<AccountBalance> = {}): AccountBalance =>
  ({ accountId, accountCode, accountName, accountType, currency, balance, ...extra })
const base = (b: AccountBalance, balance: string | null): ConvertedBalance => ({
  accountId: b.accountId, accountCode: b.accountCode, accountName: b.accountName, accountType: b.accountType,
  currency: 'EUR', balance, missingRates: [], rates: [],
  ...(b.familyLedgerId === undefined ? {} : { familyLedgerId: b.familyLedgerId, familyLedgerName: b.familyLedgerName }),
  ...(b.archived ? { archived: true } : {}),
})

// Two cash accounts of 0.004 EUR each above a cent once converted: each row rounds to x.01, their total once.
const cash = own(1, 'CASH', 'Cash', 'ASSET', 'USD', '125.0175')
const card = own(2, 'CARD', 'Card', 'ASSET', 'USD', '125.0175')
const wallet = own(3, 'OLD_WALLET', 'Old wallet', 'ASSET', 'EUR', '5.00', { archived: true })
const debt = own(4, 'FAMILY_DEBT_7', 'Debt to family budget: Home', 'LIABILITY', 'EUR', '0.003',
  { familyLedgerId: 7, familyLedgerName: 'Home' })
const opening = own(5, 'OPENING_BALANCE', 'Opening balance', 'EQUITY', 'EUR', '205.03')
const accountBalances = [cash, card, wallet, debt, opening]
const baseBalances = [base(cash, '100.01'), base(card, '100.01'), base(wallet, '5.00'), base(debt, '0.00'),
  base(opening, '205.03')]
const worth = { currency: 'EUR', assets: '205.03', liabilities: '0.00', netWorth: '205.03', unrealizedRevaluation: '0.00',
  realizedExchangeResult: '0.00', rates: [], missingRates: [] }

interface Answers {
  worth?: unknown
  worthEach?: unknown
  balances?: AccountBalance[]
  baseBalances?: ConvertedBalance[]
  lent?: unknown[]
  baseLent?: ConvertedCounterpartyBalance[]
  borrowed?: unknown[]
  baseBorrowed?: ConvertedCounterpartyBalance[]
  shared?: unknown[]
}

function stubApi(answers: Answers = {}) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const [path, query = ''] = url.split('?')
    const params = new URLSearchParams(query)
    const inBase = params.get('currency') === 'BASE'
    const lent = params.get('accountCode') === 'LOANS_ASSET'
    const bodies: Record<string, unknown> = {
      '/api/entries': { content: [], page: 0, size: 1, totalElements: 12, totalPages: 12 },
      '/api/settings': { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' },
      '/api/family-ledgers': [home],
      '/api/reports/net-worth': inBase ? (answers.worth ?? worth)
        : (answers.worthEach ?? [{ currency: 'EUR', assets: '5.00', liabilities: '0.003', netWorth: '4.997' },
          { currency: 'USD', assets: '250.035', liabilities: '0.00', netWorth: '250.035' }]),
      '/api/reports/balances': inBase ? (answers.baseBalances ?? baseBalances) : (answers.balances ?? accountBalances),
      '/api/reports/shared-settlement': answers.shared ?? [],
      '/api/reports/counterparty-balances': lent
        ? (inBase ? answers.baseLent ?? [] : answers.lent ?? [])
        : (inBase ? answers.baseBorrowed ?? [] : answers.borrowed ?? []),
      '/api/reports/cash-flow': inBase ? { currency: 'EUR', rows: [], exchangeResults: [] } : [],
    }
    const body = bodies[path]
    return new Response(JSON.stringify(body ?? { status: 404 }), {
      status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' },
    })
  }))
}

const renderAt = (path: string) => render(<MemoryRouter initialEntries={[path]}><App me={ME} /></MemoryRouter>)

/** The Balances card's total of a type, as shown. */
async function typeTotal(type: string) {
  const card = (await screen.findByRole('heading', { name: /^Balances on / })).closest('section')!
  const table = await within(card).findByRole('columnheader', { name: type })
  return table.closest('table')!.querySelector('tfoot td')!.textContent
}
/** A headline figure, by its term. */
async function headline(term: string) {
  const row = await screen.findByRole('region', { name: 'What you have and owe' })
  const dt = (await within(row).findAllByRole('term')).find((t) => t.textContent === term)
  return dt?.nextElementSibling?.textContent
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the Balances card’s totals in the base currency (D-348)', () => {
  it('are net worth’s figures, each rounded once: Assets is “You have”, Equity net worth', async () => {
    stubApi()
    renderAt('/')
    // The rows €100.01 + €100.01 + €5.00 add up to €205.02; net worth’s assets, rounded once, are €205.03.
    expect(await typeTotal('Assets')).toBe('€205.03')
    expect(await headline('You have')).toBe('€205.03')
    expect(await typeTotal('Liabilities')).toBe(await headline('You owe'))
    expect(await typeTotal('Equity')).toBe(await headline('Net worth'))
    const card = screen.getByRole('heading', { name: /^Balances on / }).closest('section')!
    expect(within(card).getByText(/a total may differ slightly from the sum of its rows/)).toBeDefined()
  })

  it('lists an archived account that holds money, marked (D-355)', async () => {
    stubApi()
    renderAt('/')
    const card = (await screen.findByRole('heading', { name: /^Balances on / })).closest('section')!
    const row = (await within(card).findByRole('link', { name: 'Old wallet' })).closest('tr')!
    expect(within(row).getByText('archived')).toBeDefined()
    expect(within(card).getByRole('link', { name: 'Cash' }).closest('tr')!.textContent).not.toContain('archived')
  })

  it('shows a total “rate missing” as the headline does when a rate is missing', async () => {
    stubApi({
      worth: { ...worth, assets: null, netWorth: null, missingRates: [{ currency: 'USD', from: '2026-09-30', to: '2026-09-30', days: 1 }] },
      baseBalances: [base(cash, null), base(card, null), base(wallet, '5.00'), base(debt, '0.00'), base(opening, '205.03')],
    })
    renderAt('/')
    expect(await typeTotal('Assets')).toBe('rate missing')
    expect(await headline('You have')).toBe('rate missing')
  })

  it('has no note in each currency, whose totals are exact', async () => {
    stubApi()
    renderAt('/?currency=each')
    expect(await typeTotal('Assets')).toBe('€5.00$250.035')
    expect(screen.queryByText(/a total may differ slightly/)).toBeNull()
  })
})

describe('open loans below a minor unit (D-349)', () => {
  const lent = [
    { counterpartyId: 11, counterpartyName: 'Ivan', currency: 'RUB', balance: '0.004' },
    { counterpartyId: 12, counterpartyName: 'Olga', currency: 'RUB', balance: '-1.4626' },
  ]
  const borrowed = [{ counterpartyId: 13, counterpartyName: 'Petr', currency: 'RUB', balance: '-0.0024' }]
  const loans = async () => (await screen.findByRole('heading', { name: 'Open loans' })).closest('section')!

  it('hides a line that rounds to 0 in its currency, keeps -RUB 1.4626, and a group left empty', async () => {
    stubApi({ lent, borrowed })
    renderAt('/?currency=each')
    const section = await loans()
    // No loan account among the balances, so the names are text, not links.
    await within(section).findByText('Olga')
    expect(within(section).queryByText('Ivan')).toBeNull()
    expect(within(section).queryByText('Money borrowed')).toBeNull()
    expect(within(section).getByText('Money lent')).toBeDefined()
    // The family budget's debt of 0.003 EUR: settled.
    const home = within(section).getByRole('link', { name: 'Home' }).closest('tr')!
    expect(home.textContent).toContain('Settled')
  })

  it('hides a counterparty in the base currency when each of its own amounts rounds to 0', async () => {
    const converted = (counterpartyId: number, counterpartyName: string, balance: string,
      amounts: { currency: string; balance: string }[]): ConvertedCounterpartyBalance =>
      ({ counterpartyId, counterpartyName, currency: 'EUR', balance, missingRates: [], rates: [], amounts })
    stubApi({
      baseLent: [converted(11, 'Ivan', '0.00', [{ currency: 'RUB', balance: '0.004' }, { currency: 'USD', balance: '0.001' }]),
        converted(12, 'Olga', '-0.02', [{ currency: 'RUB', balance: '-1.4626' }, { currency: 'USD', balance: '0.003' }])],
    })
    renderAt('/')
    const section = await loans()
    const olga = (await within(section).findByText('Olga')).closest('tr')!
    expect(within(section).queryByText('Ivan')).toBeNull()
    // Her own amounts small below, without the one below a cent.
    expect(olga.textContent).toMatch(/-RUB\s1\.4626/)
    expect(olga.textContent).not.toContain('$0.003')
    expect(within(section).getByRole('link', { name: 'Home' }).closest('tr')!.textContent).toContain('Settled')
  })

  it('says “No open loans.” when nothing is left', async () => {
    stubApi({ lent: [lent[0]], borrowed, balances: accountBalances.filter((b) => b !== debt),
      baseBalances: baseBalances.filter((b) => b.accountId !== debt.accountId) })
    renderAt('/?currency=each')
    expect(await within(await loans()).findByText('No open loans.')).toBeDefined()
  })

  it('tests each currency’s own minor unit', () => {
    expect(belowMinorUnit('0.004', 'RUB')).toBe(true)
    expect(belowMinorUnit('-0.0024', 'RUB')).toBe(true)
    expect(belowMinorUnit('0.005', 'EUR')).toBe(false)
    expect(belowMinorUnit('-1.4626', 'RUB')).toBe(false)
    expect(belowMinorUnit('0.4', 'JPY')).toBe(true)
    expect(belowMinorUnit('0.0004', 'BHD')).toBe(true)
    expect(belowMinorUnit('0.0005', 'BHD')).toBe(false)
  })
})

describe('a negative “You owe” (D-350)', () => {
  it('reads “Owed to you”, the amount above zero, in the base currency', async () => {
    stubApi({ worth: { ...worth, liabilities: '-9.50', netWorth: '214.53' } })
    renderAt('/')
    expect(await headline('Owed to you')).toBe('€9.50')
    expect(await headline('You owe')).toBeUndefined()
    expect(await headline('You have')).toBe('€205.03')
  })

  it('reads “Owed to you” in each currency’s own row only', async () => {
    stubApi({ worthEach: [{ currency: 'EUR', assets: '5.00', liabilities: '9.50', netWorth: '-4.50' },
      { currency: 'USD', assets: '0.00', liabilities: '-120.00', netWorth: '120.00' }] })
    renderAt('/?currency=each')
    const region = await screen.findByRole('region', { name: 'What you have and owe' })
    await within(region).findAllByText('$120.00')
    const terms = within(region).getAllByRole('term').map((t) => `${t.textContent} ${t.nextElementSibling!.textContent}`)
    expect(terms).toEqual(['You have €5.00', 'You owe €9.50', 'Net worth -€4.50',
      'You have $0.00', 'Owed to you $120.00', 'Net worth $120.00'])
  })
})

/** The rules of `index.css` inside its media query for screens up to 40rem, as text. */
const narrow = () => css.split('@media (max-width: 40rem)').slice(1).join('\n')

describe('the entry list at 375 and 1280 px (D-351)', () => {
  const entry: Entry = {
    id: 90, version: 0, entryDate: '2026-09-10', kind: 'FAMILY_SHARE', payeeId: null, memo: null,
    postings: [
      { accountId: 6, currency: 'EUR', amount: '50.00', categoryId: 12, counterpartyId: null },
      { accountId: 1, currency: 'EUR', amount: '-50.00', categoryId: null, counterpartyId: null },
    ],
    family: { ledgerId: 7, ledgerName: 'Home', recordId: 3, link: 'SHARE', readOnly: true },
  }
  const page: EntryPage = { content: [entry], page: 0, size: 50, totalElements: 1, totalPages: 1 }

  for (const width of [375, 1280]) {
    it(`has each entry’s lines in their cells at ${width} px`, async () => {
      window.innerWidth = width
      vi.stubGlobal('fetch', vi.fn(async (url: string) => {
        const path = url.replace(/^\/api/, '').split('?')[0]
        const body = { '/accounts': testLedger.accounts, '/categories': testLedger.categories,
          '/counterparties': testLedger.counterparties, '/settings': testLedger.settings, '/entries': page }[path]
        return new Response(JSON.stringify(body), { status: body ? 200 : 404, headers: { 'Content-Type': 'application/json' } })
      }))
      render(<WithMe><MemoryRouter initialEntries={['/entries']}><Routes><Route path="/entries" element={<Entries />} /></Routes></MemoryRouter></WithMe>)
      const row = (await screen.findAllByRole('row')).find((r) => r.classList.contains('clickable'))!
      for (const part of ['td.date', 'td.payee', 'td.category .category-name', 'td.flow', 'td.amount', 'td.category .family-line']) {
        expect(row.querySelector(part), part).not.toBeNull()
      }
      expect(row.querySelector('.family-line')!.textContent).toContain('Home')
    })
  }

  it('turns each entry into a block of lines under 40rem, in the order date, description, amount, family line', () => {
    const rules = narrow()
    expect(rules).toContain('table.entry-list thead { display: none; }')
    expect(rules).toContain('table.entry-list tr { display: flex; flex-direction: column;')
    const order = ['td.date', 'td.payee', '.category-name', 'td.flow', 'td.amount', '.family-line']
      .map((part) => Number(new RegExp(`table\\.entry-list ${part.replace('.', '\\.')} \\{[^}]*order: (\\d)`).exec(rules)![1]))
    expect(order).toEqual([1, 2, 3, 4, 5, 6])
    // Only the personal list: a family budget's activity, a table.entries too, keeps its header (F6b).
    expect(rules).not.toMatch(/table\.entries[ ,{]/)
    // The filters and the pager wrap.
    expect(css).toMatch(/\.pager \{[^}]*flex-wrap: wrap/)
    expect(css).toMatch(/\.filters \{[^}]*min-width: 0/)
  })
})

describe('the category lists’ buttons (D-352)', () => {
  it('keeps the form’s .actions rule off a table’s actions cell', () => {
    expect(css).toContain('.actions:not(td) {')
    expect(css).not.toMatch(/^\.actions \{/m)
    expect(narrow()).toMatch(/table\.categories td\.actions \{[^}]*flex-wrap: wrap/)
  })

  for (const width of [375, 1280]) {
    it(`renders a category’s buttons side by side in one cell at ${width} px`, async () => {
      window.innerWidth = width
      const categories: Category[] = testLedger.categories
      vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(categories), {
        status: 200, headers: { 'Content-Type': 'application/json' } })))
      render(<WithMe><MemoryRouter><Categories /></MemoryRouter></WithMe>)
      // UX-11 (D-527): icon buttons, named by the action and the category.
      const rename = (await screen.findAllByRole('button', { name: /^Rename / }))[0]
      const cell = rename.closest('td')!
      expect(cell.className).toContain('actions')
      expect(cell.closest('table')!.className).toBe('categories')
      const name = rename.getAttribute('aria-label')!.slice('Rename '.length)
      expect(within(cell).getAllByRole('button').map((b) => b.getAttribute('aria-label')))
        .toEqual([`Rename ${name}`, `Archive ${name}`])
    })
  }
})
