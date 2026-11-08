import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Accounts from './Accounts'
import type { Account, AccountBalance } from './api'
import { WithMe } from './testMe'

// UX-4: the Accounts page's archived accounts (D-382): their amounts that don't round to 0 at their minor unit, listed
// with "Show archived" off while they hold such an amount, each type's total matching its rows; and the page at 375 px.

const account = (id: number, code: string, name: string, type: Account['type'], extra: Partial<Account> = {}): Account =>
  ({ id, code, name, type, defaultCurrency: 'EUR', requiresCounterparty: false, system: false, archived: false,
    ...extra } as Account)
const balance = (a: Account, currency: string, amount: string): AccountBalance => ({
  accountId: a.id, accountCode: a.code, accountName: a.name, accountType: a.type, currency, balance: amount,
  ...(a.archived ? { archived: true } : {}),
})

const cash = account(1, 'CASH', 'Cash', 'ASSET')
const loans = account(2, 'LOANS_ASSET', 'Loans given', 'ASSET', { requiresCounterparty: true })
// €0.01: listed and shown.
const savings = account(3, 'OLD_SAVINGS', 'Old savings', 'ASSET', { archived: true })
// RUB 0.004 rounds to 0, $25.00 doesn't: listed, only the dollars shown.
const wallet = account(4, 'OLD_WALLET', 'Old wallet', 'ASSET', { archived: true })
// Less than a minor unit in RUB and in JPY: not listed with "Show archived" off, "—" with it on.
const crumbs = account(5, 'OLD_CARD', 'Old card', 'ASSET', { archived: true })
// Nothing at all.
const closed = account(6, 'CLOSED', 'Closed account', 'ASSET', { archived: true })
// A liability archived with €0.01 owed, and an open one.
const card = account(7, 'CARD', 'Credit card', 'LIABILITY')
const oldLoan = account(8, 'OLD_LOAN', 'Old loan', 'LIABILITY', { archived: true })

const accounts = [cash, loans, savings, wallet, crumbs, closed, card, oldLoan]
const balances = [
  balance(cash, 'EUR', '1405.00'), balance(loans, 'EUR', '50.00'),
  balance(savings, 'EUR', '0.01'),
  balance(wallet, 'RUB', '0.004'), balance(wallet, 'USD', '25.00'),
  balance(crumbs, 'JPY', '0.4'), balance(crumbs, 'RUB', '-0.003'),
  balance(card, 'EUR', '120.00'), balance(oldLoan, 'EUR', '0.01'),
]

let calls: string[] = []
function stubApi() {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push(`${init?.method ?? 'GET'} ${url}`)
    const path = url.split('?')[0]
    const bodies: Record<string, unknown> = {
      '/api/accounts': accounts,
      '/api/reports/balances': balances,
      '/api/reports/counterparty-balances': [
        { counterpartyId: 40, counterpartyName: 'Mark', currency: 'EUR', balance: '50.00' }],
    }
    const body = bodies[path]
    return new Response(JSON.stringify(body ?? { status: 404 }), {
      status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' },
    })
  }))
}

const renderAccounts = () => render(<WithMe><MemoryRouter><Accounts /></MemoryRouter></WithMe>)
/** Text as shown, with no-break spaces as spaces. */
const plain = (text: string | null | undefined) => text?.replace(/ /g, ' ').trim()
const section = async (type: string) => (await screen.findByRole('heading', { name: type })).closest('section')!
/** The account names listed in a type's table, in order. */
const listed = async (type: string) =>
  within(await section(type)).queryAllByRole('link').map((l) => l.textContent)
/** An account's balance cell, its amounts one per line. */
const cell = (name: string) =>
  [...screen.getByRole('link', { name }).closest('tr')!.querySelector('td.amount')!.children].map((c) => plain(c.textContent))
const totals = async (type: string) =>
  [...(await section(type)).querySelector('tfoot td.amount')!.children].map((c) => plain(c.textContent))
const showArchived = () => fireEvent.click(screen.getByRole('checkbox', { name: /Show archived/ }))

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the Accounts page’s archived accounts (D-382)', () => {
  it('with “Show archived” off, lists an archived account only while an amount of it doesn’t round to 0', async () => {
    stubApi()
    renderAccounts()
    expect(await listed('Assets')).toEqual(['Cash', 'Loans given', 'Old savings', 'Old wallet'])
    expect(await listed('Liabilities')).toEqual(['Credit card', 'Old loan'])
    // The count names every archived account, listed or not.
    expect(screen.getByRole('checkbox', { name: 'Show archived (5)' })).toBeTruthy()
  })

  it('shows an archived account’s amounts that don’t round to 0, in two currencies, and no tooltip', async () => {
    stubApi()
    renderAccounts()
    await screen.findByRole('link', { name: 'Old wallet' })
    expect(cell('Old savings')).toEqual(['€0.01'])
    expect(cell('Old wallet')).toEqual(['$25.00'])
    expect(cell('Old loan')).toEqual(['€0.01'])
    expect(cell('Cash')).toEqual(['€1,405.00'])
    expect(document.querySelector('[title="Balances are reported for open accounts only"]')).toBeNull()
  })

  it('with “Show archived” on, lists every archived account, “—” for one with nothing or less than a minor unit', async () => {
    stubApi()
    renderAccounts()
    await screen.findByRole('link', { name: 'Old wallet' })
    showArchived()
    expect(await listed('Assets')).toEqual(['Cash', 'Loans given', 'Old savings', 'Old wallet', 'Old card', 'Closed account'])
    for (const name of ['Old card', 'Closed account']) {
      const amount = screen.getByRole('link', { name }).closest('tr')!.querySelector('td.amount')!
      expect(amount.textContent, name).toBe('—')
    }
    expect(cell('Old wallet')).toEqual(['$25.00'])
  })

  it('has each type’s total, which counts every account, match its rows up to rounding', async () => {
    stubApi()
    renderAccounts()
    await screen.findByRole('link', { name: 'Old wallet' })
    // Assets: €1,405.00 + €50.00 + €0.01, $25.00, and below a minor unit JPY 0.4 and RUB 0.001 of hidden amounts.
    const assets = await totals('Assets')
    expect(assets).toContain('€1,455.01')
    expect(assets).toContain('$25.00')
    expect(await totals('Liabilities')).toEqual(['€120.01'])
  })

  it('opens a “By person” account’s details', async () => {
    stubApi()
    renderAccounts()
    const row = (await screen.findByRole('link', { name: 'Loans given' })).closest('tr')!
    fireEvent.click(within(row).getByRole('button', { name: 'By person' }))
    expect(await screen.findByRole('link', { name: 'Mark' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Mark' }).closest('tr.details, table.nested')).not.toBeNull()
  })
})

/** The rules of `index.css` inside its media query for screens up to 40rem, as text. */
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
const narrow = () => css.split('@media (max-width: 40rem)').slice(1).join('\n')

describe('the Accounts page at 375 px (D-382)', () => {
  it('turns each account into a wrapping line with its buttons below, the table’s own rows only', () => {
    const rules = narrow()
    expect(rules).toContain('table.accounts > thead { display: none; }')
    expect(rules).toMatch(/table\.accounts > tbody > tr, table\.accounts > tfoot > tr \{[^}]*display: flex; flex-wrap: wrap/)
    expect(rules).toMatch(/table\.accounts > tbody > tr > td\.actions \{[^}]*flex-wrap: wrap;[^}]*flex-basis: 100%;[^}]*white-space: normal/)
    // A refusal's row and the details across the width; the rename form wraps.
    expect(rules).toMatch(/tr\.details > td, table\.accounts > tbody > tr\.row-error > td \{ flex-basis: 100%; \}/)
    expect(rules).toMatch(/table\.accounts form\.inline \{[^}]*flex-wrap: wrap/)
    // At 1280 px the buttons stay on one line, as before.
    expect(css.split('@media')[0]).toContain('.nowrap { white-space: nowrap; }')
  })

  for (const width of [375, 1280]) {
    it(`keeps every button of an account in its actions cell at ${width} px`, async () => {
      window.innerWidth = width
      stubApi()
      renderAccounts()
      const row = (await screen.findByRole('link', { name: 'Loans given' })).closest('tr')!
      const actions = row.querySelector('td.actions')!
      expect(within(actions as HTMLElement).getAllByRole('button').map((b) => b.textContent))
        .toEqual(['By person', 'Rename', 'Archive'])
    })
  }
})
