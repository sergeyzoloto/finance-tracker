import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Category, Entry, FamilyLedger, FamilyMember, FamilyRecord, Me } from './api'
import { testLedger } from './testLedger'

// The payer's side (F4c): a new personal expense marked as family (C2), the payer's own payment entry, and the payment
// fields on an expense's page, with the requests each sends.

type Answer = { status: number; body?: unknown }
type Call = { method: string; url: string; body: unknown }

/** Answers each request by "METHOD /api/path?query", or with 404; records every request with its JSON body. */
function stubApi(answers: Record<string, Answer>) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ method, url, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    const answer = answers[`${method} ${url}`] ?? { status: 404, body: { status: 404, detail: `No static resource ${url.slice(1)}.` } }
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
      status: answer.status,
      headers: { 'Content-Type': answer.status >= 400 ? 'application/problem+json' : 'application/json' },
    })
  }))
  return calls
}

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

const ON: Me = { name: 'Anna', features: { familyLedgers: true } }
const OFF: Me = { name: 'Anna', features: { familyLedgers: false } }
const home: FamilyLedger = {
  id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
  createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01',
}
const trip: FamilyLedger = { ...home, id: 8, name: 'Trip', memberId: 80 }
const anna: FamilyMember = { id: 70, displayName: 'Anna', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null }
const sam: FamilyMember = { id: 71, displayName: 'Sam', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: false, share: null }
const ben: FamilyMember = { id: 72, displayName: 'Ben', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null }
const familyCategories: Category[] = [
  { id: 30, code: 'GROCERIES', name: 'Groceries', type: 'EXPENSE', archived: false },
  { id: 33, code: 'SALARY', name: 'Salary', type: 'INCOME', archived: false },
]
const accounts: Account[] = [
  ...testLedger.accounts,
  { id: 40, code: 'FAMILY_DEBT_7', name: 'Debt to family budget: Home', type: 'LIABILITY', defaultCurrency: 'EUR', requiresCounterparty: false, system: true, archived: false },
  { id: 41, code: 'UNSPECIFIED_PAYMENTS', name: 'Payments without a specified account', type: 'ASSET', defaultCurrency: null, requiresCounterparty: false, system: true, archived: false },
]
const ref = (member: FamilyMember) => ({ memberId: member.id, displayName: member.displayName })
const record: FamilyRecord = {
  id: 5, type: 'EXPENSE', date: '2026-09-12', category: { id: 30, code: 'GROCERIES', name: 'Groceries', archived: false },
  amount: '10.01', currency: 'EUR', originalAmount: '10.01', originalCurrency: 'EUR', comment: null, payer: ref(anna),
  splitMethod: 'EQUAL',
  shares: [
    { member: ref(anna), amount: '5.01', basisPoints: null, updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z' },
    { member: ref(sam), amount: '5.00', basisPoints: null, updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z' },
  ],
  author: ref(anna), createdAt: '2026-09-12T10:00:00Z', updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z',
  version: 0, frozen: false, canEdit: true, canDelete: true, canEditPayment: true,
  yourPayment: { entryId: 91, accountId: 1, accountName: 'Cash', later: false, amount: '1.00', currency: 'EUR' },
}
const payment: Entry = {
  id: 91, version: 2, entryDate: '2026-09-12', kind: 'FAMILY_PAYMENT', payeeId: null, memo: 'Old card',
  postings: [
    { accountId: 1, currency: 'EUR', amount: '-10.01', categoryId: null, counterpartyId: null },
    { accountId: 40, currency: 'EUR', amount: '10.01', categoryId: null, counterpartyId: null },
  ],
  family: { ledgerId: 7, ledgerName: 'Home', recordId: 5, link: 'PAYMENT', readOnly: true },
}
const noEntries = { content: [], page: 0, size: 1, totalElements: 0, totalPages: 0 }

/** The personal ledger's reference data, the family budgets, and Home's members and categories. */
function ledger(families: FamilyLedger[], answers: Record<string, Answer> = {}) {
  return stubApi({
    'GET /api/me': { status: 200, body: ON },
    'GET /api/accounts': { status: 200, body: accounts },
    'GET /api/categories': { status: 200, body: testLedger.categories },
    'GET /api/counterparties': { status: 200, body: testLedger.counterparties },
    'GET /api/settings': { status: 200, body: testLedger.settings },
    'GET /api/entries?size=50': { status: 200, body: noEntries },
    'GET /api/family-ledgers': { status: 200, body: families },
    'GET /api/family-ledgers/7': { status: 200, body: home },
    'GET /api/family-ledgers/7/members': { status: 200, body: [anna, sam] },
    'GET /api/family-ledgers/7/categories': { status: 200, body: familyCategories },
    'GET /api/family-ledgers/7/journal?recordId=5&size=200': { status: 200, body: { content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 } },
    ...answers,
  })
}

function renderApp(path: string, me: Me = ON) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App me={me} />
      <Routes><Route path="*" element={<Where />} /></Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 8, 30, 12))
  vi.stubGlobal('confirm', () => true)
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const familyOption = () => screen.queryByRole('switch', { name: 'Family expense' })

describe('a new expense marked as family (C2)', () => {
  it('is not offered with the switch off, nor without a family budget', async () => {
    const calls = ledger([home])
    renderApp('/entries/new', OFF)
    await screen.findByLabelText(/^Paid from/)
    expect(familyOption()).toBeNull()
    expect(calls.some((c) => c.url.startsWith('/api/family-ledgers'))).toBe(false)
    cleanup()

    ledger([])
    renderApp('/entries/new')
    await screen.findByLabelText(/^Paid from/)
    await waitFor(() => expect(screen.getByLabelText(/^Category/)).toBeDefined())
    expect(familyOption()).toBeNull()
    // With the switch on, the old "Split with family" isn't offered for a new expense either (H4, F6c).
    expect(screen.queryByRole('switch', { name: 'Split with family' })).toBeNull()
  })

  it('creates the expense in the family budget, paid from the entry’s account, with the note on the payment only', async () => {
    const calls = ledger([home], { 'POST /api/family-ledgers/7/records': { status: 201, body: { ...record, id: 6 } } })
    renderApp('/entries/new')
    fireEvent.click(await screen.findByRole('switch', { name: 'Family expense' }))

    fireEvent.change(await screen.findByLabelText(/^Family category/), { target: { value: '30' } })
    // One family budget: no selector (D-5); no payee, refund or personal category; the memo is the private note.
    expect(screen.queryByLabelText(/^Family budget/)).toBeNull()
    expect(screen.queryByLabelText(/^Payee/)).toBeNull()
    expect(screen.queryByLabelText(/^Category/)).toBeNull()
    expect(screen.queryByText('Refund: the money came back')).toBeNull()
    expect(screen.getByText('Split', { selector: 'legend' })).toBeDefined()
    // Only accounts of the user's own money or credit pay it.
    const paidFrom = screen.getByLabelText(/^Paid from/)
    expect([...paidFrom.querySelectorAll('option')].map((o) => o.textContent)).not.toContain('Unallocated')
    fireEvent.change(paidFrom, { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '12,50' } })
    fireEvent.change(screen.getByLabelText(/^Comment for the family budget/), { target: { value: 'Market' } })
    fireEvent.change(screen.getByLabelText(/^Note, only you see it/), { target: { value: 'Card ending 4' } })
    expect(await screen.findByTestId('share-71')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toEqual([{
      method: 'POST', url: '/api/family-ledgers/7/records', body: {
        type: 'EXPENSE', date: '2026-09-30', categoryId: 30, amount: '12.50', comment: 'Market', payerMemberId: 70,
        paymentAccountId: 1, split: { method: 'RULE' }, privateNote: 'Card ending 4',
      },
    }]))
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/entries'))
    // Not an ordinary expense: nothing went to /api/entries.
    expect(calls.some((c) => c.method === 'POST' && c.url === '/api/entries')).toBe(false)
  })

  it('offers a choice of family budget only with more than one', async () => {
    ledger([home, trip], {
      'GET /api/family-ledgers/8/members': { status: 200, body: [{ ...anna, id: 80 }] },
      'GET /api/family-ledgers/8/categories': { status: 200, body: familyCategories },
    })
    renderApp('/entries/new')
    fireEvent.click(await screen.findByRole('switch', { name: 'Family expense' }))
    const budget = await screen.findByLabelText(/^Family budget/)
    expect([...budget.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['Home', 'Trip'])
    fireEvent.change(budget, { target: { value: '8' } })
    expect(await screen.findByText('Every member of Trip sees it.')).toBeDefined()
  })

  it('converts an entry in another currency than the budget’s, and sends the base amount only when typed in (F4e)', async () => {
    const calls = ledger([home], {
      'GET /api/family-ledgers/7/conversion?amount=56.00&currency=USD&date=2026-09-30': {
        status: 200, body: { amount: '56.00', currency: 'USD', baseAmount: '50.00', baseCurrency: 'EUR', rate: '0.892857142857', rateSource: 'ECB', rateDate: '2026-09-29' },
      },
      'POST /api/family-ledgers/7/records': { status: 201, body: { ...record, id: 6 } },
    })
    renderApp('/entries/new')
    const option = await screen.findByRole('switch', { name: 'Family expense' })
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: '9' } })
    expect(option).toHaveProperty('disabled', false)
    fireEvent.click(option)
    fireEvent.change(await screen.findByLabelText(/^Family category/), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '56' } })
    // The base amount, with its rate and source; the split is of the base amount.
    const base = await screen.findByLabelText(/^Amount in EUR/)
    await waitFor(() => expect(base).toHaveProperty('value', '50.00'))
    expect(screen.getByText('1 USD = 0.892857 EUR, ECB rate of Sep 29, 2026')).toBeDefined()
    await waitFor(() => expect(screen.getByTestId('share-71').textContent).toContain('€25.00'))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([{
      type: 'EXPENSE', date: '2026-09-30', categoryId: 30, amount: '56.00', currency: 'USD', comment: null,
      payerMemberId: 70, paymentAccountId: 9, split: { method: 'RULE' }, privateNote: null,
    }]))
  })

  it('takes a typed base amount, and puts a missing rate at the base amount', async () => {
    const calls = ledger([home], {
      'GET /api/family-ledgers/7/conversion?amount=9000.00&currency=RUB&date=2026-09-30': {
        status: 200, body: { amount: '9000.00', currency: 'RUB', baseAmount: null, baseCurrency: 'EUR' },
      },
      'POST /api/family-ledgers/7/records': { status: 201, body: { ...record, id: 6 } },
    })
    renderApp('/entries/new')
    fireEvent.click(await screen.findByRole('switch', { name: 'Family expense' }))
    fireEvent.change(await screen.findByLabelText(/^Family category/), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText('Currency'), { target: { value: 'RUB' } })
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '9000' } })
    expect(await screen.findByText('There is no exchange rate for RUB on or before Sep 30, 2026: enter the amount in EUR, '
      + 'or add your own rate on the Rates page.')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
    fireEvent.change(screen.getByLabelText(/^Amount in EUR/), { target: { value: '92.15' } })
    expect(screen.getByText(/Entered by you/)).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([{
      type: 'EXPENSE', date: '2026-09-30', categoryId: 30, amount: '9000.00', currency: 'RUB', baseAmount: '92.15',
      comment: null, payerMemberId: 70, paymentAccountId: 1, split: { method: 'RULE' }, privateNote: null,
    }]))
  })
})

describe('the payer’s own payment entry', () => {
  it('changes the date and amount as a payment, sending only what changed', async () => {
    const calls = ledger([home], {
      'GET /api/entries/91': { status: 200, body: payment },
      'GET /api/family-ledgers/7/records/5': { status: 200, body: record },
      'PATCH /api/entries/91/family-payment?version=2': { status: 200, body: { ...payment, version: 3 } },
    })
    renderApp('/entries/91')
    expect(await screen.findByRole('heading', { name: 'Family payment' })).toBeDefined()
    expect(screen.getByLabelText(/^Amount/)).toHaveProperty('value', '10.01')
    expect(screen.getByLabelText(/^Note, only you see it/)).toHaveProperty('value', 'Old card')
    expect(await screen.findByText('Groceries, €10.01, shared by Anna, Sam.')).toBeDefined()
    expect(screen.getByRole('link', { name: 'Open the expense' }).getAttribute('href')).toBe('/family/7/expenses/5')

    fireEvent.change(screen.getByLabelText(/^Date/), { target: { value: '2026-09-13' } })
    fireEvent.change(screen.getByLabelText(/^Amount/), { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'PATCH')).toEqual([{
      method: 'PATCH', url: '/api/entries/91/family-payment?version=2', body: { date: '2026-09-13', amount: '12.00' },
    }]))
  })

  it('moves a payment from “Specify later” to an account', async () => {
    const later = { ...payment, memo: null, postings: [{ ...payment.postings[0], accountId: 41 }, payment.postings[1]] }
    const calls = ledger([home], {
      'GET /api/entries/91': { status: 200, body: later },
      'GET /api/family-ledgers/7/records/5': { status: 200, body: record },
      'PATCH /api/entries/91/family-payment?version=2': { status: 200, body: { ...payment, version: 3 } },
    })
    renderApp('/entries/91')
    const paidFrom = await screen.findByLabelText(/^Paid from/)
    expect(paidFrom).toHaveProperty('value', 'later')
    fireEvent.change(paidFrom, { target: { value: '1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'PATCH')?.body).toEqual({ accountId: 1 }))
  })

  it('says where the amount of an expense split by amounts changes', async () => {
    ledger([home], {
      'GET /api/entries/91': { status: 200, body: payment },
      'GET /api/family-ledgers/7/records/5': { status: 200, body: { ...record, splitMethod: 'AMOUNT' } },
      'PATCH /api/entries/91/family-payment?version=2': { status: 422, body: {
        status: 422, detail: 'The change breaks the family budget’s rules.',
        violations: ['the expense is split by amounts: send the new amounts with the new amount'],
        violationDetails: [{ code: 'AMOUNTS_NEEDED', memberId: null, message: 'the expense is split by amounts: send the new amounts with the new amount' }],
      } },
    })
    renderApp('/entries/91')
    fireEvent.change(await screen.findByLabelText(/^Amount/), { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('This expense is split by amounts: change its amount on the expense’s page, with the new amounts.'))
      .toBeDefined()
  })

  it('deletes the expense after a confirmation that names the family budget and the other members’ shares', async () => {
    const asked: string[] = []
    vi.stubGlobal('confirm', (message: string) => { asked.push(message); return true })
    const calls = ledger([home], {
      'GET /api/entries/91': { status: 200, body: payment },
      'GET /api/family-ledgers/7/records/5': { status: 200, body: record },
      'DELETE /api/entries/91?version=2': { status: 204 },
    })
    renderApp('/entries/91')
    await screen.findByText('Groceries, €10.01, shared by Anna, Sam.')
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/entries'))
    expect(asked).toEqual(['Delete your payment and the expense “Groceries” of Sep 12, 2026 in the family budget “Home”? '
      + 'The other members’ shares of it go too.'])
    expect(calls.filter((c) => c.method === 'DELETE').map((c) => c.url)).toEqual(['/api/entries/91?version=2'])
  })

  it('opens read-only with the switch off', async () => {
    ledger([home], { 'GET /api/entries/91': { status: 200, body: payment } })
    renderApp('/entries/91', OFF)
    expect(await screen.findByText(/To change it, change or delete the expense there/)).toBeDefined()
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull()
  })
})

describe('the payment on an expense’s page', () => {
  it('changes the date and amount, and moves “Specify later” to an account', async () => {
    const calls = ledger([home], {
      'GET /api/family-ledgers/7/records/5': { status: 200, body: record },
      'PATCH /api/family-ledgers/7/records/5?version=0': { status: 200, body: { ...record, version: 1 } },
    })
    renderApp('/family/7/expenses/5')
    fireEvent.change(await screen.findByLabelText(/^Amount \(/), { target: { value: '12' } })
    fireEvent.change(screen.getByLabelText(/^Date/), { target: { value: '2026-09-13' } })
    expect(screen.getByText('When you save, the shares are split again as the expense is split now.')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Save the changes' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'PATCH').map((c) => c.body)).toEqual([
      { date: '2026-09-13', amount: '12.00' }]))
    cleanup()

    const moved = ledger([home], {
      'GET /api/family-ledgers/7/records/5': { status: 200, body: { ...record, yourPayment: { entryId: 91, accountId: null, accountName: null, later: true, amount: '1.00', currency: 'EUR' } } },
      'PATCH /api/family-ledgers/7/records/5?version=0': { status: 200, body: { ...record, version: 0 } },
    })
    renderApp('/family/7/expenses/5')
    const paidFrom = await screen.findByLabelText(/^Paid from/)
    expect(paidFrom).toHaveProperty('value', 'later')
    await waitFor(() => expect([...paidFrom.querySelectorAll('option')].map((o) => o.textContent)).toContain('Cash'))
    fireEvent.change(paidFrom, { target: { value: '1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the changes' }))
    await waitFor(() => expect(moved.filter((c) => c.method === 'PATCH').map((c) => c.body)).toEqual([{ paymentAccountId: 1 }]))
  })

  it('needs the new amounts with a new amount of an expense split by amounts', async () => {
    ledger([home], {
      'GET /api/family-ledgers/7/records/5': { status: 200, body: {
        ...record, splitMethod: 'AMOUNT',
        shares: record.shares.map((s) => ({ ...s, amount: s.member.memberId === 70 ? '5.01' : '5.00' })),
      } },
    })
    renderApp('/family/7/expenses/5')
    fireEvent.change(await screen.findByLabelText(/^Amount \(/), { target: { value: '12' } })
    expect(screen.getByText('This expense is split by amounts: enter the new amounts with the new amount.')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Save the changes' })).toHaveProperty('disabled', true)
    fireEvent.change(screen.getByLabelText('Amount of Sam'), { target: { value: '6.99' } })
    expect(screen.getByRole('button', { name: 'Save the changes' })).toHaveProperty('disabled', false)
  })

  it('says who changes the payment of an expense that another member with an account paid', async () => {
    ledger([home], {
      'GET /api/family-ledgers/7/members': { status: 200, body: [anna, sam, ben] },
      'GET /api/family-ledgers/7/records/5': { status: 200, body: {
        ...record, payer: ref(ben), canDelete: false, canEditPayment: false, yourPayment: undefined,
      } },
    })
    renderApp('/family/7/expenses/5')
    expect(await screen.findByText('Only Ben, who paid it, changes its date, amount and payer.')).toBeDefined()
    expect(screen.queryByLabelText(/^Paid by/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Save the changes' })).toBeDefined()
  })
})
