import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Category, Entry, FamilyLedger, FamilyMember, FamilyRecord, FamilyRecordPage, Me } from './api'
import { testLedger } from './testLedger'

// Currencies (F8b; D-45, D-46, D-47, D-88, D-89): the forms in a record's own currency with no rate, the paying side
// on an account in another currency, balances and settling up per currency, D-47's total with its rates, "No RUB rate",
// the lists in each record's currency, and the other side of a settlement naming what it got on an account in another
// currency, on its record's page and its own entry.

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

const ON: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }
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
  amount: '72.40', payer: ref(anna), splitMethod: 'EQUAL', shares: [share(anna, '36.20'), share(sam, '36.20')],
  yourPayment: { entryId: 90, accountId: 1, accountName: 'Cash', later: false, amount: '1.00', currency: 'EUR' },
}
const income: FamilyRecord = {
  ...base, id: 6, type: 'INCOME', date: '2026-09-13', category: { id: 33, code: 'SALARY', name: 'Salary', archived: false },
  amount: '1000.00', payer: ref(sam), splitMethod: 'PERCENT', shares: [share(anna, '500.00', 5000), share(sam, '500.00', 5000)],
}
const settlement: FamilyRecord = {
  ...base, id: 7, type: 'SETTLEMENT', date: '2026-09-14', category: null, amount: '36.20', payer: ref(sam), payee: ref(anna),
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


const dollars: FamilyRecord = {
  ...expense, id: 8, amount: '56.00', currency: 'USD', shares: [share(anna, '28.00'), share(sam, '28.00')],
  yourPayment: undefined, payer: ref(sam),
}

describe('the forms in a record’s own currency (D-45, D-89)', () => {
  it('takes an expense in dollars paid by a member without an account, split in dollars, with no rate', async () => {
    const calls = app([anna, sam], {
      'POST /api/family-ledgers/7/records': { status: 201, body: dollars },
      'GET /api/family-ledgers/7/records/8': { status: 200, body: dollars },
      'GET /api/family-ledgers/7/journal?recordId=8&size=200': { status: 200, body: noJournal },
    })
    renderApp('/family/7/expenses/new')
    fireEvent.change(await screen.findByLabelText(/^Category/), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText(/^Paid by/), { target: { value: '71' } })
    fireEvent.change(screen.getByLabelText(/^Currency/), { target: { value: 'usd' } })
    fireEvent.change(screen.getByLabelText('Amount (USD)'), { target: { value: '56' } })
    await waitFor(() => expect(screen.getByTestId('share-71').textContent).toContain('$28.00'))
    expect(screen.queryByLabelText(/^Amount in EUR/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Add the expense' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([{
      type: 'EXPENSE', date: '2026-09-30', categoryId: 30, amount: '56.00', currency: 'USD', comment: null,
      payerMemberId: 71, split: { method: 'RULE' },
    }]))
    expect(calls.some((c) => c.url.includes('/conversion'))).toBe(false)
  })

  it('asks what went from an account in another currency, and nothing when it paid in the record’s', async () => {
    const calls = app([anna, sam], {
      'POST /api/family-ledgers/7/records': { status: 201, body: expense },
      'GET /api/family-ledgers/7/records/5': { status: 200, body: expense },
      'GET /api/family-ledgers/7/journal?recordId=5&size=200': { status: 200, body: noJournal },
    })
    renderApp('/family/7/expenses/new')
    fireEvent.change(await screen.findByLabelText(/^Category/), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText('Amount (EUR)'), { target: { value: '50' } })
    const from = screen.getByLabelText(/^Paid from/)
    await waitFor(() => expect(from.querySelector('option[value="42"]')).not.toBeNull())
    fireEvent.change(from, { target: { value: '42' } })
    // The rouble account pays in roubles by default: the roubles are asked for.
    expect((screen.getByLabelText(/^Paid in/) as HTMLInputElement).value).toBe('RUB')
    const roubles = screen.getByLabelText(/^Amount paid in RUB/)
    expect(screen.getByRole('button', { name: 'Add the expense' })).toHaveProperty('disabled', true)
    fireEvent.change(roubles, { target: { value: '5000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add the expense' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([{
      type: 'EXPENSE', date: '2026-09-30', categoryId: 30, amount: '50.00', accountAmount: '5000.00',
      comment: null, payerMemberId: 70, paymentAccountId: 42, split: { method: 'RULE' },
    }]))
  })

  it('takes a record paid in its own currency from an account whose default is another, with no amount to name', async () => {
    const calls = app([anna, sam], {
      'POST /api/family-ledgers/7/records': { status: 201, body: expense },
      'GET /api/family-ledgers/7/records/5': { status: 200, body: expense },
      'GET /api/family-ledgers/7/journal?recordId=5&size=200': { status: 200, body: noJournal },
    })
    renderApp('/family/7/expenses/new')
    fireEvent.change(await screen.findByLabelText(/^Category/), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText('Amount (EUR)'), { target: { value: '50' } })
    const from = screen.getByLabelText(/^Paid from/)
    await waitFor(() => expect(from.querySelector('option[value="42"]')).not.toBeNull())
    fireEvent.change(from, { target: { value: '42' } })
    fireEvent.change(screen.getByLabelText(/^Paid in/), { target: { value: 'eur' } })
    expect(screen.queryByLabelText(/^Amount paid in/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Add the expense' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)[0]).toMatchObject({
      amount: '50.00', accountCurrency: 'EUR', paymentAccountId: 42,
    }))
    expect(calls.filter((c) => c.method === 'POST')[0].body).not.toHaveProperty('accountAmount')
  })
})

describe('balances per currency and D-47’s total', () => {
  const debts = {
    byCurrency: [
      { currency: 'EUR', members: [
        { memberId: 70, displayName: 'Anna', status: 'ACTIVE', hasAccount: true, balance: '-40.00', you: true },
        { memberId: 71, displayName: 'Sam', status: 'ACTIVE', hasAccount: false, balance: '40.00', you: false }] },
      { currency: 'USD', members: [
        { memberId: 70, displayName: 'Anna', status: 'ACTIVE', hasAccount: true, balance: '12.00', you: true },
        { memberId: 71, displayName: 'Sam', status: 'ACTIVE', hasAccount: false, balance: '-12.00', you: false }] },
    ],
    total: {
      currency: 'EUR', asOf: '2026-09-30',
      members: [{ memberId: 70, balance: '-29.09' }, { memberId: 71, balance: '29.09' }],
      rates: [{ currency: 'USD', date: '2026-08-01', perEuro: '1.10', source: 'MANUAL', stale: true }],
      missingCurrencies: [],
    },
  }

  it('settles each currency on its own, and shows the total with its rate, manual and stale', async () => {
    app([anna, sam], { 'GET /api/family-ledgers/7/balances': { status: 200, body: debts } })
    renderApp('/family/7/balances')
    expect(await screen.findByText('Sam owes you €40.00.')).toBeDefined()
    expect(screen.getByText('You owe Sam $12.00.')).toBeDefined()
    const links = screen.getAllByRole('link', { name: 'Settle up' }).map((l) => l.getAttribute('href'))
    expect(links).toEqual(['/family/7/settle?payer=71&payee=70&amount=40.00&currency=EUR',
      '/family/7/settle?payer=70&payee=71&amount=12.00&currency=USD'])
    expect(screen.getByTestId('total-70').textContent).toBe('≈ -€29.09manualrate stale')
    expect(screen.getByText('1 EUR = 1.1 USD, manual rate of Aug 1, 2026, rate stale')).toBeDefined()
    expect(screen.getByTestId('balances-sum-USD').textContent).toBe('$0.00')
  })

  it('says “No RUB rate” with the way to enter one, and shows no total', async () => {
    app([anna, sam], {
      'GET /api/family-ledgers/7/balances': {
        status: 200,
        body: { ...debts, byCurrency: [debts.byCurrency[0], { ...debts.byCurrency[1], currency: 'RUB' }],
          total: { ...debts.total, members: [], rates: [], missingCurrencies: ['RUB'] } },
      },
    })
    renderApp('/family/7/balances')
    const none = await screen.findByTestId('total-70')
    expect(none.textContent).toBe('No RUB rate: enter a rate')
    expect(none.querySelector('a')!.getAttribute('href')).toBe('/rates')
  })

  it('opens the settlement in the debt’s currency', async () => {
    const calls = app([anna, sam], {
      'POST /api/family-ledgers/7/settlements': { status: 201, body: settlement },
      'GET /api/family-ledgers/7/records/7': { status: 200, body: settlement },
      'GET /api/family-ledgers/7/journal?recordId=7&size=200': { status: 200, body: noJournal },
    })
    renderApp('/family/7/settle?payer=70&payee=71&amount=12.00&currency=USD')
    expect(await screen.findByLabelText('Amount (USD)')).toHaveProperty('value', '12.00')
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: 'later' } })
    fireEvent.click(screen.getByRole('button', { name: 'Record the settlement' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)[0]).toMatchObject({
      amount: '12.00', currency: 'USD', payerMemberId: 70, payeeMemberId: 71, paymentLater: true,
    }))
  })
})

describe('a record’s amount in its own currency', () => {
  it('lists each record in its own currency', async () => {
    app([anna, sam], {
      'GET /api/family-ledgers/7/records?page=0&size=20': {
        status: 200, body: { content: [dollars, expense], page: 0, size: 20, totalElements: 2, totalPages: 1 },
      },
    })
    renderApp('/family/7/expenses')
    const rows = await screen.findAllByRole('row')
    expect(rows[1].textContent).toContain('$56.00')
    expect(rows[2].textContent).toContain('€72.40')
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

describe('the paying side on a record’s page (D-88, D-89)', () => {
  const paidInRoubles: FamilyRecord = {
    ...expense, amount: '50.00', currency: 'EUR', shares: [share(anna, '25.00'), share(sam, '25.00')],
    yourPayment: { entryId: 90, accountId: 42, accountName: 'Rouble account', later: false, amount: '5000.00', currency: 'RUB' },
  }
  const page = (record: FamilyRecord) => app([anna, sam], {
    'GET /api/family-ledgers/7/records/5': { status: 200, body: record },
    'GET /api/family-ledgers/7/journal?recordId=5&size=200': { status: 200, body: noJournal },
    'PATCH /api/family-ledgers/7/records/5?version=0': { status: 200, body: { ...record, version: 1 } },
  })

  it('keeps what went from the account on a new date, and asks for it again with a new amount', async () => {
    const calls = page(paidInRoubles)
    renderApp('/family/7/expenses/5')
    const yours = await screen.findByRole('link', { name: 'Rouble account' })
    expect(yours.closest('dd')!.textContent).toMatch(/Rouble account, RUB\s5,000\.00/)
    fireEvent.change(await screen.findByLabelText(/^Date/), { target: { value: '2026-09-13' } })
    const save = screen.getByRole('button', { name: 'Save the changes' })
    expect(save).toHaveProperty('disabled', false)
    fireEvent.change(screen.getByLabelText('Amount (EUR)'), { target: { value: '60' } })
    expect(save).toHaveProperty('disabled', true)
    fireEvent.change(screen.getByLabelText(/^Amount paid in RUB/), { target: { value: '6000' } })
    fireEvent.click(save)
    await waitFor(() => expect(calls.filter((c) => c.method === 'PATCH').map((c) => c.body)).toEqual([
      { date: '2026-09-13', amount: '60.00', accountAmount: '6000.00' },
    ]))
  })

  it('changes the paying currency alone: in the record’s, with nothing to name', async () => {
    const calls = page(paidInRoubles)
    renderApp('/family/7/expenses/5')
    const paidIn = await screen.findByLabelText(/^Paid in/)
    await waitFor(() => expect((paidIn as HTMLInputElement).value).toBe('RUB'))
    fireEvent.change(paidIn, { target: { value: 'eur' } })
    expect(screen.queryByLabelText(/^Amount paid in/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Save the changes' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'PATCH').map((c) => c.body)).toEqual([
      { accountCurrency: 'EUR' },
    ]))
  })

  it('shows another member only the record’s own amount and currency', async () => {
    page({ ...paidInRoubles, payer: ref(ben), yourPayment: undefined, canEditPayment: false, canDelete: false })
    renderApp('/family/7/expenses/5')
    const amount = (await screen.findByText('Amount')).nextElementSibling!
    expect(amount.textContent).toBe('€50.00')
    expect(screen.queryByText(/RUB/)).toBeNull()
  })
})

describe('the report per currency, with D-47’s total', () => {
  const member = (memberId: number, displayName: string, you = false) =>
    ({ memberId, displayName, status: 'ACTIVE' as const, hasAccount: memberId !== 71, you })
  const zero = { expenseShares: '0.00', expensesPaid: '0.00', incomeShares: '0.00', incomesReceived: '0.00', settlementsPaid: '0.00', settlementsReceived: '0.00' }
  const report = {
    from: null, to: null, members: [member(70, 'Anna', true), member(71, 'Sam')],
    byCurrency: [
      { currency: 'EUR', rows: [{ month: '2026-09', categoryId: 30, categoryName: 'Groceries', categoryType: 'EXPENSE', archived: false, total: '90.00',
        members: [{ memberId: 70, share: '45.00', paid: '90.00' }, { memberId: 71, share: '45.00', paid: '0.00' }] }],
      totals: [{ memberId: 70, ...zero, expenseShares: '45.00', expensesPaid: '90.00', net: '-45.00' },
        { memberId: 71, ...zero, expenseShares: '45.00', net: '45.00' }] },
      { currency: 'USD', rows: [{ month: '2026-09', categoryId: 30, categoryName: 'Groceries', categoryType: 'EXPENSE', archived: false, total: '22.00',
        members: [{ memberId: 70, share: '11.00', paid: '0.00' }, { memberId: 71, share: '11.00', paid: '22.00' }] }],
      totals: [{ memberId: 70, ...zero, expenseShares: '11.00', net: '11.00' },
        { memberId: 71, ...zero, expenseShares: '11.00', expensesPaid: '22.00', net: '-11.00' }] },
    ],
    total: { currency: 'EUR', totals: [{ memberId: 70, ...zero, net: '-35.00' }, { memberId: 71, ...zero, net: '35.00' }],
      rates: [{ currency: 'USD', date: '2026-09-30', perEuro: '1.10', source: 'ECB', stale: false }], missingCurrencies: [] },
  }

  it('shows each currency on its own, and the approximate total with its rate', async () => {
    app([anna, sam], { 'GET /api/family-ledgers/7/report': { status: 200, body: report } })
    renderApp('/family/7/report')
    expect(await screen.findByRole('heading', { name: 'In USD' })).toBeDefined()
    expect(screen.getByTestId('report-total-70').textContent).toBe('Together in EUR: You are owed ≈ €35.00 more')
    expect(screen.getByText('1 EUR = 1.1 USD, ECB rate of Sep 30, 2026')).toBeDefined()
    expect(screen.getByText('$22.00')).toBeDefined()
  })

  it('says “No USD rate” instead of a total', async () => {
    app([anna, sam], { 'GET /api/family-ledgers/7/report': { status: 200, body: { ...report,
      total: { currency: 'EUR', totals: [], rates: [], missingCurrencies: ['USD'] } } } })
    renderApp('/family/7/report')
    expect((await screen.findByTestId('report-total-70')).textContent).toBe('Together in EUR: No USD rate: enter a rate')
  })
})

describe('the rates page (D-49)', () => {
  const overview = {
    baseCurrency: 'EUR', missing: [],
    latest: [
      { currency: 'RUB', date: '2026-08-01', perEuro: '95.50', source: 'MANUAL', inLedger: true, applies: true, stale: true },
      { currency: 'USD', date: '2026-09-29', perEuro: '1.1', source: 'ECB', inLedger: true, applies: true, stale: false },
      { currency: 'CHF', date: '2026-09-01', perEuro: '0.93', source: 'ECB', inLedger: true, applies: false, stale: false },
    ],
  }

  it('labels each rate, and takes "1 EUR = 95,50 RUB" with a comma', async () => {
    const calls = app([anna], {
      'GET /api/rates': { status: 200, body: overview },
      'GET /api/rates/manual': { status: 200, body: [] },
      'POST /api/rates/manual': { status: 200, body: { date: '2026-09-30', base: 'EUR', quote: 'RUB', rate: '95.50' } },
    })
    renderApp('/rates')
    expect((await screen.findByTestId('rate-RUB')).textContent).toContain('manualrate stale')
    expect(screen.getByTestId('rate-USD').textContent).not.toContain('manual')
    expect(screen.getByTestId('rate-CHF').textContent).toContain('not used: more than 7 days old')
    fireEvent.change(screen.getByLabelText('Rate'), { target: { value: '95,50' } })
    fireEvent.change(screen.getByLabelText('Currency'), { target: { value: 'RUB' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save rate' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([
      { date: '2026-09-30', base: 'EUR', quote: 'RUB', rate: '95.50' },
    ]))
  })

  it('refuses a rate that isn’t a number', async () => {
    const calls = app([anna], {
      'GET /api/rates': { status: 200, body: overview }, 'GET /api/rates/manual': { status: 200, body: [] },
    })
    renderApp('/rates')
    fireEvent.change(await screen.findByLabelText('Rate'), { target: { value: '95,5,0' } })
    fireEvent.change(screen.getByLabelText('Currency'), { target: { value: 'RUB' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save rate' }))
    expect(await screen.findByText('Enter the rate as a number, such as 95,50 or 95.50.')).toBeDefined()
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })
})
