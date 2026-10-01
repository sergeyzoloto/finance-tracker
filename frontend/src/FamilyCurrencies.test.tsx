import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Category, Entry, FamilyLedger, FamilyMember, FamilyRecord, FamilyRecordPage, Me } from './api'
import { testLedger } from './testLedger'

// Other currencies (F4e): the forms with a currency and the base amount with its rate and source, a missing rate at
// the base amount, the lists and a record's page with both amounts, and the other side of a settlement naming what it
// got on an account in another currency, on its record's page and its own entry.

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
  const location = useLocation()
  return <p data-testid="where">{location.pathname + location.search}</p>
}

const ON: Me = { name: 'Anna', features: { familyLedgers: true } }
const home: FamilyLedger = {
  id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
  createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01',
}
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
  { id: 42, code: 'RUB_ACCOUNT', name: 'Rouble account', type: 'ASSET', defaultCurrency: 'RUB', requiresCounterparty: false, system: false, archived: false },
]
const ref = (member: FamilyMember) => ({ memberId: member.id, displayName: member.displayName })
const at = '2026-09-12T10:00:00Z'
const share = (member: FamilyMember, amount: string, basisPoints: number | null = null) =>
  ({ member: ref(member), amount, basisPoints, updatedBy: ref(anna), updatedAt: at })
const base = {
  currency: 'EUR', comment: null, author: ref(anna), createdAt: at, updatedBy: ref(anna), updatedAt: at, version: 0,
  frozen: false, canEdit: true, canDelete: true, canEditPayment: true,
}
const expense: FamilyRecord = {
  ...base, id: 5, type: 'EXPENSE', date: '2026-09-12', category: { id: 30, code: 'GROCERIES', name: 'Groceries', archived: false },
  amount: '72.40', originalAmount: '72.40', originalCurrency: 'EUR', payer: ref(anna), splitMethod: 'EQUAL', shares: [share(anna, '36.20'), share(sam, '36.20')],
  yourPayment: { entryId: 90, accountId: 1, accountName: 'Cash', later: false, amount: '1.00', currency: 'EUR' },
}
const income: FamilyRecord = {
  ...base, id: 6, type: 'INCOME', date: '2026-09-13', category: { id: 33, code: 'SALARY', name: 'Salary', archived: false },
  amount: '1000.00', originalAmount: '1000.00', originalCurrency: 'EUR', payer: ref(sam), splitMethod: 'PERCENT', shares: [share(anna, '500.00', 5000), share(sam, '500.00', 5000)],
}
const settlement: FamilyRecord = {
  ...base, id: 7, type: 'SETTLEMENT', date: '2026-09-14', category: null, amount: '36.20', originalAmount: '36.20', originalCurrency: 'EUR', payer: ref(sam), payee: ref(anna),
  splitMethod: null, shares: [],
}
const noJournal = { content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }
const noEntries = { content: [], page: 0, size: 1, totalElements: 0, totalPages: 0 }

/** The personal ledger's reference data, the family budget "Home" and its members, and anything else. */
function app(members: FamilyMember[], answers: Record<string, Answer> = {}, ledger = home) {
  return stubApi({
    'GET /api/me': { status: 200, body: ON },
    'GET /api/accounts': { status: 200, body: accounts },
    'GET /api/categories': { status: 200, body: testLedger.categories },
    'GET /api/counterparties': { status: 200, body: testLedger.counterparties },
    'GET /api/settings': { status: 200, body: testLedger.settings },
    'GET /api/entries?size=50': { status: 200, body: noEntries },
    'GET /api/family-ledgers': { status: 200, body: [ledger] },
    'GET /api/family-ledgers/7': { status: 200, body: ledger },
    'GET /api/family-ledgers/7/members': { status: 200, body: members },
    'GET /api/family-ledgers/7/categories': { status: 200, body: familyCategories },
    ...answers,
  })
}

function renderApp(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App me={ON} />
      <Routes><Route path="*" element={<Where />} /></Routes>
    </MemoryRouter>,
  )
}

const where = () => screen.getByTestId('where').textContent

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 8, 30, 12))
  vi.stubGlobal('confirm', () => true)
  try { localStorage.clear() } catch { /* not in this environment */ }
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})


const conversion = (amount: string, currency: string, date: string, answer: object) => ({
  [`GET /api/family-ledgers/7/conversion?amount=${amount}&currency=${currency}&date=${date}`]: { status: 200, body: answer },
})
const dollars: FamilyRecord = {
  ...expense, id: 8, amount: '50.00', originalAmount: '56.00', originalCurrency: 'USD', rate: '0.892857142857',
  rateSource: 'ECB', rateDate: '2026-09-10', shares: [share(anna, '25.00'), share(sam, '25.00')], yourPayment: undefined,
  payer: ref(sam),
}

describe('the forms in another currency', () => {
  it('takes an expense paid by a member without an account in dollars, converted with its rate, and splits the base amount', async () => {
    const calls = app([anna, sam], {
      ...conversion('56.00', 'USD', '2026-09-30', {
        amount: '56.00', currency: 'USD', baseAmount: '50.00', baseCurrency: 'EUR', rate: '0.892857142857', rateSource: 'ECB',
        rateDate: '2026-09-29',
      }),
      'POST /api/family-ledgers/7/records': { status: 201, body: dollars },
      'GET /api/family-ledgers/7/records/8': { status: 200, body: dollars },
      'GET /api/family-ledgers/7/journal?recordId=8&size=200': { status: 200, body: noJournal },
    })
    renderApp('/family/7/expenses/new')
    fireEvent.change(await screen.findByLabelText(/^Category/), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText(/^Paid by/), { target: { value: '71' } })
    fireEvent.change(screen.getByLabelText('Currency'), { target: { value: 'usd' } })
    fireEvent.change(screen.getByLabelText('Amount (USD)'), { target: { value: '56' } })
    const base = await screen.findByLabelText(/^Amount in EUR/)
    await waitFor(() => expect(base).toHaveProperty('value', '50.00'))
    expect(screen.getByText('1 USD = 0.892857 EUR, ECB rate of Sep 29, 2026')).toBeDefined()
    await waitFor(() => expect(screen.getByTestId('share-71').textContent).toContain('€25.00'))
    fireEvent.click(screen.getByRole('button', { name: 'Add the expense' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([{
      type: 'EXPENSE', date: '2026-09-30', categoryId: 30, amount: '56.00', currency: 'USD', comment: null,
      payerMemberId: 71, split: { method: 'RULE' },
    }]))
  })

  it('puts a missing rate at the base amount, and sends the base amount typed in', async () => {
    const calls = app([anna, sam], {
      ...conversion('9000.00', 'RUB', '2026-09-30', { amount: '9000.00', currency: 'RUB', baseAmount: null, baseCurrency: 'EUR' }),
      'POST /api/family-ledgers/7/records': {
        status: 422, body: {
          status: 422, detail: 'The record breaks a rule.', violations: [],
          violationDetails: [{ code: 'RATE_MISSING', memberId: null, message: 'there is no exchange rate from RUB to EUR on or before 2026-09-30: enter the amount in EUR, or add your own rate on the rates page' }],
        },
      },
    })
    renderApp('/family/7/expenses/new')
    fireEvent.change(await screen.findByLabelText(/^Category/), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText(/^Paid by/), { target: { value: '71' } })
    fireEvent.change(screen.getByLabelText('Currency'), { target: { value: 'RUB' } })
    fireEvent.change(screen.getByLabelText('Amount (RUB)'), { target: { value: '9000' } })
    expect(await screen.findByText('There is no exchange rate for RUB on or before Sep 30, 2026: enter the amount in EUR, '
      + 'or add your own rate on the Rates page.')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Add the expense' })).toHaveProperty('disabled', true)
    fireEvent.change(screen.getByLabelText(/^Amount in EUR/), { target: { value: '92.15' } })
    expect(screen.getByRole('button', { name: 'Use the exchange rate' })).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Add the expense' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([{
      type: 'EXPENSE', date: '2026-09-30', categoryId: 30, amount: '9000.00', currency: 'RUB', baseAmount: '92.15',
      comment: null, payerMemberId: 71, split: { method: 'RULE' },
    }]))
    // The server's RATE_MISSING (a rate deleted meanwhile) lands at the base amount, saying why.
    const field = screen.getByLabelText(/^Amount in EUR/).closest('.field')!
    await waitFor(() => expect(field.textContent).toContain('There is no exchange rate from RUB to EUR on or before 2026-09-30'))
  })

  it('records a settlement from a dollar account in dollars, the account deciding the currency', async () => {
    const calls = app([anna, sam], {
      ...conversion('40.00', 'USD', '2026-09-30', {
        amount: '40.00', currency: 'USD', baseAmount: '35.71', baseCurrency: 'EUR', rate: '0.892857142857', rateSource: 'MANUAL',
        rateDate: '2026-09-01',
      }),
      'POST /api/family-ledgers/7/settlements': { status: 201, body: { ...settlement, id: 9 } },
    })
    renderApp('/family/7/settle?payer=70&payee=71')
    const from = await screen.findByLabelText(/^Paid from/)
    await waitFor(() => expect(from.querySelector('option[value="9"]')).not.toBeNull())
    fireEvent.change(from, { target: { value: '9' } })
    expect(screen.queryByLabelText('Currency')).toBeNull()
    fireEvent.change(screen.getByLabelText('Amount (USD)'), { target: { value: '40' } })
    await waitFor(() => expect(screen.getByLabelText(/^Amount in EUR/)).toHaveProperty('value', '35.71'))
    expect(screen.getByText('1 USD = 0.892857 EUR, your own rate of Sep 1, 2026')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Record the settlement' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([{
      date: '2026-09-30', amount: '40.00', currency: 'USD', payerMemberId: 70, payeeMemberId: 71, comment: null,
      paymentAccountId: 9,
    }]))
  })
})

describe('settling up in another currency', () => {
  it('keeps what settles as the base amount, and asks for the dollars paid', async () => {
    const calls = app([anna, sam], {
      ...conversion('42.50', 'USD', '2026-09-30', {
        amount: '42.50', currency: 'USD', baseAmount: '36.17', baseCurrency: 'EUR', rate: '0.851', rateSource: 'ECB',
      }),
      'POST /api/family-ledgers/7/settlements': { status: 201, body: { ...settlement, id: 9 } },
    })
    renderApp('/family/7/settle?payer=70&payee=71&amount=36.20')
    const from = await screen.findByLabelText(/^Paid from/)
    await waitFor(() => expect(from.querySelector('option[value="9"]')).not.toBeNull())
    expect(screen.getByLabelText('Amount (EUR)')).toHaveProperty('value', '36.20')
    fireEvent.change(from, { target: { value: '9' } })
    expect(screen.getByLabelText('Amount (USD)')).toHaveProperty('value', '')
    expect(screen.getByLabelText(/^Amount in EUR/)).toHaveProperty('value', '36.20')
    fireEvent.change(screen.getByLabelText('Amount (USD)'), { target: { value: '42.50' } })
    fireEvent.click(screen.getByRole('button', { name: 'Record the settlement' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([{
      date: '2026-09-30', amount: '42.50', currency: 'USD', baseAmount: '36.20', payerMemberId: 70, payeeMemberId: 71,
      comment: null, paymentAccountId: 9,
    }]))
  })
})

describe('both amounts where the currencies differ', () => {
  it('lists “$56.00 → €50.00” and the base amount alone otherwise, and shows the rate on the record’s page', async () => {
    const page: FamilyRecordPage = { content: [dollars, expense], page: 0, size: 20, totalElements: 2, totalPages: 1 }
    app([anna, sam], {
      'GET /api/family-ledgers/7/records?page=0&size=20': { status: 200, body: page },
      'GET /api/family-ledgers/7/records/8': { status: 200, body: dollars },
      'GET /api/family-ledgers/7/journal?recordId=8&size=200': { status: 200, body: noJournal },
    })
    renderApp('/family/7/expenses')
    const rows = await screen.findAllByRole('row')
    expect(rows[1].textContent).toContain('$56.00 → €50.00')
    expect(rows[2].textContent).toContain('€72.40')
    expect(rows[2].textContent).not.toContain('→')
    cleanup()
    app([anna, sam], {
      'GET /api/family-ledgers/7/records/8': { status: 200, body: dollars },
      'GET /api/family-ledgers/7/journal?recordId=8&size=200': { status: 200, body: noJournal },
    })
    renderApp('/family/7/expenses/8')
    expect(await screen.findByText('$56.00 → €50.00')).toBeDefined()
    // On the amount, and again at the base amount of the payment fields, which the author may change.
    expect(screen.getAllByText('1 USD = 0.892857 EUR, ECB rate of Sep 10, 2026')).not.toHaveLength(0)
  })
})

describe('the other side’s own amount (F4e)', () => {
  const theirs: FamilyRecord = {
    ...settlement, payer: ref(ben), author: ref(ben), canEdit: false, canDelete: false, canEditPayment: false,
    yourPayment: { entryId: 95, accountId: null, accountName: null, later: true, amount: '36.20', currency: 'EUR' },
  }
  it('asks for what went into an account in another currency, on the settlement’s page', async () => {
    const calls = app([anna, sam, ben], {
      'GET /api/family-ledgers/7/records/7': { status: 200, body: theirs },
      'GET /api/family-ledgers/7/journal?recordId=7&size=200': { status: 200, body: noJournal },
      'PATCH /api/family-ledgers/7/records/7?version=0': {
        status: 200, body: { ...theirs, yourPayment: { entryId: 95, accountId: 42, accountName: 'Rouble account', later: false, amount: '3300.00', currency: 'RUB' } },
      },
    })
    renderApp('/family/7/expenses/7')
    const into = await screen.findByLabelText(/^Received into/)
    await waitFor(() => expect(into.querySelector('option[value="42"]')).not.toBeNull())
    fireEvent.change(into, { target: { value: '42' } })
    const own = await screen.findByLabelText(/^Amount received in RUB/)
    expect(screen.getByRole('button', { name: 'Save the changes' })).toHaveProperty('disabled', true)
    fireEvent.change(own, { target: { value: '3300' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the changes' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'PATCH').map((c) => c.body)).toEqual([
      { paymentAccountId: 42, accountAmount: '3300.00' },
    ]))
  })

  it('shows their own amount next to their account, for their eyes only', async () => {
    app([anna, sam, ben], {
      'GET /api/family-ledgers/7/records/7': {
        status: 200, body: { ...theirs, yourPayment: { entryId: 95, accountId: 42, accountName: 'Rouble account', later: false, amount: '3300.00', currency: 'RUB' } },
      },
      'GET /api/family-ledgers/7/journal?recordId=7&size=200': { status: 200, body: noJournal },
    })
    renderApp('/family/7/expenses/7')
    const yours = await screen.findByRole('link', { name: 'Rouble account' })
    expect(yours.closest('dd')!.textContent).toMatch(/Rouble account, RUB\s3,300\.00/)
  })

  it('asks for it on their own entry too', async () => {
    const side: Entry = {
      id: 95, version: 1, entryDate: '2026-09-14', kind: 'FAMILY_SETTLEMENT', payeeId: null, memo: null,
      postings: [
        { accountId: 41, currency: 'EUR', amount: '36.20', categoryId: null, counterpartyId: null },
        { accountId: 40, currency: 'EUR', amount: '-36.20', categoryId: null, counterpartyId: null },
      ],
      family: { ledgerId: 7, ledgerName: 'Home', recordId: 7, link: 'SETTLEMENT', readOnly: true, recordType: 'SETTLEMENT' },
    }
    const calls = app([anna, sam, ben], {
      'GET /api/entries/95': { status: 200, body: side },
      'GET /api/family-ledgers/7/records/7': { status: 200, body: theirs },
      'PATCH /api/entries/95/family-payment?version=1': { status: 200, body: { ...side, version: 2 } },
    })
    renderApp('/entries/95')
    fireEvent.change(await screen.findByLabelText(/^Received into/), { target: { value: '42' } })
    fireEvent.change(await screen.findByLabelText(/^Amount received in RUB/), { target: { value: '3300' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'PATCH').map((c) => c.body)).toEqual([
      { accountId: 42, accountAmount: '3300.00' },
    ]))
  })
})
