import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type {
  Account, Category, Entry, FamilyBalances, FamilyLedger, FamilyMember, FamilyRecord, FamilyRecordPage, Me,
} from './api'
import { testLedger } from './testLedger'

// Incomes and settlements (F4d): the activity's labels, settling up from the balances, the income and settlement
// forms, a settlement's page for its other side, the family option of a new income in the personal editor, a
// settlement side's personal entry, and an expense's split preview after the budget's rule changed (the F4c review).

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

describe('the activity', () => {
  it('labels expenses, incomes and settlements from the reader’s side', async () => {
    const page: FamilyRecordPage = { content: [settlement, income, expense], page: 0, size: 20, totalElements: 3, totalPages: 1 }
    app([anna, sam], { 'GET /api/family-ledgers/7/records?page=0&size=20': { status: 200, body: page } })
    renderApp('/family/7/expenses')
    expect(await screen.findByRole('heading', { name: 'Activity' })).toBeDefined()
    expect(screen.getByRole('link', { name: 'Activity' }).getAttribute('href')).toBe('/family/7/expenses')
    const rows = within(await screen.findByRole('table')).getAllByRole('row').slice(1)
    expect(rows.map((r) => r.textContent)).toEqual([
      'Sep 14, 2026SettlementSam paid you €36.20€36.20',
      'Sep 13, 2026SalaryIncomeReceived by Sam€1,000.00Yours €500.00',
      'Sep 12, 2026GroceriesPaid by you€72.40Yours €36.20',
    ])
    expect(screen.getByRole('link', { name: 'Add an income' }).getAttribute('href')).toBe('/family/7/incomes/new')
    expect(screen.getByRole('link', { name: 'Record a settlement' }).getAttribute('href')).toBe('/family/7/settle')
  })
})

describe('settling up', () => {
  const balances: FamilyBalances = {
    currency: 'EUR',
    members: [
      { memberId: 70, displayName: 'Anna', status: 'ACTIVE', hasAccount: true, balance: '-10.00', you: true },
      { memberId: 71, displayName: 'Sam', status: 'ACTIVE', hasAccount: false, balance: '50.00', you: false },
      { memberId: 72, displayName: 'Ben', status: 'ACTIVE', hasAccount: true, balance: '-40.00', you: false },
    ],
  }

  it('lists the reader’s own debts first, each they may record with “Settle up”, and prefills the settlement', async () => {
    const calls = app([anna, sam, ben], {
      'GET /api/family-ledgers/7/balances': { status: 200, body: balances },
      'POST /api/family-ledgers/7/settlements': { status: 201, body: { ...settlement, amount: '10.00' } },
      'GET /api/family-ledgers/7/records/7': { status: 200, body: settlement },
      'GET /api/family-ledgers/7/journal?recordId=7&size=200': { status: 200, body: noJournal },
    })
    renderApp('/family/7/balances')
    await screen.findByRole('heading', { name: 'Balances' })
    const items = within(screen.getByRole('list')).getAllByRole('listitem')
    // Sam pays the largest creditor, Ben, first; Anna's own line comes first all the same. Sam and Ben settle
    // between themselves: Anna is neither, and Ben has an account.
    expect(items.map((li) => li.textContent)).toEqual(['Sam owes you €10.00 Settle up', 'Sam owes Ben €40.00'])
    fireEvent.click(within(items[0]).getByRole('link', { name: 'Settle up' }))

    await waitFor(() => expect(where()).toBe('/family/7/settle?payer=71&payee=70&amount=10.00'))
    expect(await screen.findByRole('heading', { name: 'Record a settlement' })).toBeDefined()
    expect(screen.getByLabelText('Paid by')).toHaveProperty('value', '71')
    expect(screen.getByLabelText('Received by')).toHaveProperty('value', '70')
    expect(screen.getByLabelText(/^Amount/)).toHaveProperty('value', '10.00')
    expect(screen.getByLabelText(/^Date/)).toHaveProperty('value', '2026-09-30')
    // Anna receives it: her account, or "Specify later", is hers to name.
    const into = screen.getByLabelText(/^Received into/)
    expect([...into.querySelectorAll('option')].map((o) => o.textContent)).not.toContain('Debt to family budget: Home')
    const record = screen.getByRole('button', { name: 'Record the settlement' })
    expect(record).toHaveProperty('disabled', true)
    fireEvent.change(into, { target: { value: '2' } })
    fireEvent.change(screen.getByLabelText(/^Comment/), { target: { value: 'Cash' } })
    fireEvent.click(record)

    await waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toEqual([{
      method: 'POST', url: '/api/family-ledgers/7/settlements', body: {
        date: '2026-09-30', amount: '10.00', payerMemberId: 71, payeeMemberId: 70, comment: 'Cash', paymentAccountId: 2,
      },
    }]))
    await waitFor(() => expect(where()).toBe('/family/7/expenses/7'))
  })

  it('says who may record a settlement, and puts the server’s objections at the receiver', async () => {
    app([anna, sam, ben], {
      'POST /api/family-ledgers/7/settlements': {
        status: 422, body: { status: 422, detail: 'Invalid', violations: ['x'],
          violationDetails: [{ code: 'JOINED_AFTER', memberId: 72, message: 'Ben joined on 2026-10-01, after the settlement’s date 2026-09-30' }] },
      },
    }, { ...home, role: 'MEMBER' })
    renderApp('/family/7/settle?payer=71&payee=72&amount=5')
    await screen.findByRole('heading', { name: 'Record a settlement' })
    // Sam and Ben: Anna is neither side, and isn't an owner here.
    expect((await screen.findByRole('alert')).textContent).toContain('You record a settlement you pay or receive.')
    expect(screen.queryByLabelText(/^Paid from/)).toBeNull()
    expect(screen.queryByLabelText(/^Received into/)).toBeNull()

    // Anna pays Ben: her side's account, and Ben's part goes to his placeholder until he chooses.
    fireEvent.change(screen.getByLabelText('Paid by'), { target: { value: '70' } })
    fireEvent.change(await screen.findByLabelText(/^Paid from/), { target: { value: 'later' } })
    expect(screen.getByText(/Ben’s part goes to their “Payments without a specified account”/)).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Record the settlement' }))
    const receiver = screen.getByText('Received by', { selector: '.label' }).closest('label')!
    await waitFor(() => expect(receiver.textContent).toContain('Ben joined on 2026-10-01, after the settlement’s date 2026-09-30.'))
  })
})

describe('adding an income', () => {
  it('mirrors the expense form with “Received by” and “Received into”, and sends an INCOME', async () => {
    const calls = app([anna, sam], {
      'POST /api/family-ledgers/7/records': { status: 201, body: { ...income, id: 8 } },
    })
    renderApp('/family/7/incomes/new')
    expect(await screen.findByRole('heading', { name: 'Add an income' })).toBeDefined()
    const category = await screen.findByLabelText('Category')
    expect([...category.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['Choose a category', 'Salary'])
    expect(screen.getByLabelText('Received by')).toHaveProperty('value', '70')
    fireEvent.change(category, { target: { value: '33' } })
    fireEvent.change(screen.getByLabelText(/^Amount \(/), { target: { value: '10.01' } })
    fireEvent.change(screen.getByLabelText(/^Received into/), { target: { value: '2' } })
    // D-12: the odd cent to the member who received it.
    expect(screen.getByTestId('share-70').textContent).toContain('€5.01')
    expect(screen.getByTestId('share-71').textContent).toContain('€5.00')
    fireEvent.click(screen.getByRole('button', { name: 'Add the income' }))

    await waitFor(() => expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      type: 'INCOME', date: '2026-09-30', categoryId: 33, amount: '10.01', comment: null, payerMemberId: 70,
      paymentAccountId: 2, split: { method: 'RULE' },
    }))
    await waitFor(() => expect(where()).toBe('/family/7/expenses/8'))
  })
})

describe('a settlement’s page', () => {
  it('lets its other side put their part on an account, and nothing else', async () => {
    const theirs: FamilyRecord = {
      ...settlement, payer: ref(ben), author: ref(ben), canEdit: false, canDelete: false, canEditPayment: false,
      yourPayment: { entryId: 95, accountId: null, accountName: null, later: true, amount: '1.00', currency: 'EUR' },
    }
    const calls = app([anna, sam, ben], {
      'GET /api/family-ledgers/7/records/7': { status: 200, body: theirs },
      'GET /api/family-ledgers/7/journal?recordId=7&size=200': { status: 200, body: noJournal },
      'PATCH /api/family-ledgers/7/records/7?version=0': { status: 200, body: { ...theirs, yourPayment: { entryId: 95, accountId: 2, accountName: 'Current account', later: false, amount: '1.00', currency: 'EUR' } } },
    })
    renderApp('/family/7/expenses/7')
    expect(await screen.findByRole('heading', { name: 'Settlement, Sep 14, 2026' })).toBeDefined()
    expect(screen.getByText('Ben paid you €36.20.')).toBeDefined()
    expect(screen.queryByRole('heading', { name: 'Shares' })).toBeNull()
    expect(screen.getByRole('heading', { name: 'Your side of this settlement' })).toBeDefined()
    expect(screen.queryByLabelText(/^Date/)).toBeNull()
    expect(screen.queryByLabelText(/^Amount/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Delete the settlement' })).toBeNull()
    expect(screen.getByText(/Only Ben, who recorded it, changes its date, amount and comment/)).toBeDefined()
    fireEvent.change(await screen.findByLabelText(/^Received into/), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the changes' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'PATCH')).toEqual([{
      method: 'PATCH', url: '/api/family-ledgers/7/records/7?version=0', body: { paymentAccountId: 2 },
    }]))
  })

  it('lets its recorder change the date, amount and comment while the other side’s part waits', async () => {
    const mine: FamilyRecord = {
      ...settlement, payer: ref(anna), payee: ref(ben), yourPayment: { entryId: 96, accountId: 1, accountName: 'Cash', later: false, amount: '1.00', currency: 'EUR' },
    }
    const calls = app([anna, sam, ben], {
      'GET /api/family-ledgers/7/records/7': { status: 200, body: mine },
      'GET /api/family-ledgers/7/journal?recordId=7&size=200': { status: 200, body: noJournal },
      'PATCH /api/family-ledgers/7/records/7?version=0': { status: 200, body: { ...mine, amount: '40.00', version: 1 } },
      'DELETE /api/family-ledgers/7/records/7?version=0': { status: 204 },
    })
    renderApp('/family/7/expenses/7')
    expect(await screen.findByText('You paid Ben €36.20.')).toBeDefined()
    expect(screen.queryByText(/moves the other side’s part back/)).toBeNull()
    fireEvent.change(screen.getByLabelText(/^Amount/), { target: { value: '40' } })
    expect(screen.queryByRole('note')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Save the changes' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'PATCH').map((c) => c.body)).toEqual([{ amount: '40.00' }]))
    expect(screen.getByRole('button', { name: 'Delete the settlement' })).toBeDefined()
  })

  it('tells its recorder why the date, amount and deleting are locked once the other side placed its part (D-28)', async () => {
    const locked: FamilyRecord = {
      ...settlement, payer: ref(anna), payee: ref(ben), canEditPayment: false, canDelete: false, lockedBy: ref(ben),
      yourPayment: { entryId: 96, accountId: 1, accountName: 'Cash', later: false, amount: '1.00', currency: 'EUR' },
    }
    const calls = app([anna, sam, ben], {
      'GET /api/family-ledgers/7/records/7': { status: 200, body: locked },
      'GET /api/family-ledgers/7/journal?recordId=7&size=200': { status: 200, body: noJournal },
      'PATCH /api/family-ledgers/7/records/7?version=0': { status: 200, body: { ...locked, comment: 'Jar', version: 1 } },
    })
    renderApp('/family/7/expenses/7')
    const note = await screen.findByRole('note')
    expect(note.textContent).toBe('Ben has put their side of this settlement on an account of theirs, so its date and amount '
      + 'can’t change and it can’t be deleted. Ben can move it back to “Specify later” to allow it.')
    expect(screen.queryByLabelText(/^Date/)).toBeNull()
    expect(screen.queryByLabelText(/^Amount/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Delete the settlement' })).toBeNull()
    expect(screen.getByRole('heading', { name: 'Change this settlement' })).toBeDefined()
    fireEvent.change(screen.getByLabelText(/^Comment/), { target: { value: 'Jar' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the changes' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'PATCH').map((c) => c.body)).toEqual([{ comment: 'Jar' }]))
  })

  it('tells the other side that their part on an account locks it, and offers “Specify later”', async () => {
    const theirs: FamilyRecord = {
      ...settlement, payer: ref(ben), author: ref(ben), canEdit: false, canDelete: false, canEditPayment: false,
      yourPayment: { entryId: 95, accountId: 2, accountName: 'Current account', later: false, amount: '1.00', currency: 'EUR' },
    }
    const calls = app([anna, sam, ben], {
      'GET /api/family-ledgers/7/records/7': { status: 200, body: theirs },
      'GET /api/family-ledgers/7/journal?recordId=7&size=200': { status: 200, body: noJournal },
      'PATCH /api/family-ledgers/7/records/7?version=0': { status: 200, body: { ...theirs, yourPayment: { entryId: 95, accountId: null, accountName: null, later: true, amount: '1.00', currency: 'EUR' } } },
    })
    renderApp('/family/7/expenses/7')
    expect(await screen.findByText('While your side is on an account of yours, Ben can’t change the settlement’s date or '
      + 'amount, or delete it. Choose “Specify later” to let them.')).toBeDefined()
    fireEvent.change(await screen.findByLabelText(/^Received into/), { target: { value: 'later' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the changes' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'PATCH').map((c) => c.body)).toEqual([{ paymentLater: true }]))
  })
})

describe('an expense split equally, after the budget’s rule became custom (the F4c review)', () => {
  it('previews the stored equal split among its own members, and sends no split with a new amount', async () => {
    const custom: FamilyLedger = { ...home, splitRule: 'CUSTOM' }
    const later: FamilyMember = { ...ben, joinDate: '2026-09-20', hasAccount: false, share: 2000 }
    const calls = app([{ ...anna, share: 4000 }, { ...sam, share: 4000 }, later], {
      'GET /api/family-ledgers/7/records/5': { status: 200, body: expense },
      'GET /api/family-ledgers/7/journal?recordId=5&size=200': { status: 200, body: noJournal },
      'PATCH /api/family-ledgers/7/records/5?version=0': { status: 200, body: { ...expense, version: 1 } },
    }, custom)
    renderApp('/family/7/expenses/5')
    const keep = await screen.findByRole('radio', { name: 'Equal shares, as it is split now' })
    expect(keep).toHaveProperty('checked', true)
    expect(screen.getByTestId('share-70').textContent).toContain('€36.20')
    expect(screen.getByTestId('share-71').textContent).toContain('€36.20')
    // Ben, added since without an account, shares nothing of it, whatever the rule says now (D-18).
    expect(screen.queryByTestId('share-72')).toBeNull()

    fireEvent.change(screen.getByLabelText(/^Amount \(/), { target: { value: '20.01' } })
    expect(screen.getByTestId('share-70').textContent).toContain('€10.01')
    expect(screen.getByTestId('share-71').textContent).toContain('€10.00')
    expect(screen.getByText('When you save, the shares are split again as the expense is split now.')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Save the changes' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'PATCH').map((c) => c.body)).toEqual([{ amount: '20.01' }]))
  })
})

describe('the personal side', () => {
  it('offers a new income the family option, and creates a family income received into the entry’s account', async () => {
    const calls = app([anna, sam], {
      'POST /api/family-ledgers/7/records': { status: 201, body: { ...income, id: 8 } },
    })
    renderApp('/entries/new?tab=income')
    fireEvent.click(await screen.findByRole('switch', { name: 'Family income' }))
    const category = await screen.findByLabelText(/^Family category/)
    await waitFor(() => expect([...category.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['Choose a category', 'Salary']))
    expect(screen.queryByLabelText(/^Payer/)).toBeNull()
    expect(screen.queryByText('Reversal: the money went back')).toBeNull()
    fireEvent.change(category, { target: { value: '33' } })
    const into = screen.getByLabelText(/^Received into/)
    expect([...into.querySelectorAll('option')].map((o) => o.textContent)).not.toContain('Unallocated')
    fireEvent.change(into, { target: { value: '2' } })
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '100' } })
    fireEvent.change(screen.getByLabelText(/^Note, only you see it/), { target: { value: 'Bonus' } })
    expect(await screen.findByTestId('share-71')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toEqual([{
      method: 'POST', url: '/api/family-ledgers/7/records', body: {
        type: 'INCOME', date: '2026-09-30', categoryId: 33, amount: '100.00', comment: null, payerMemberId: 70,
        paymentAccountId: 2, split: { method: 'RULE' }, privateNote: 'Bonus',
      },
    }]))
  })

  it('opens the other side of a settlement with only its account to change, linked to the settlement', async () => {
    const side: Entry = {
      id: 95, version: 1, entryDate: '2026-09-14', kind: 'FAMILY_SETTLEMENT', payeeId: null, memo: null,
      postings: [
        { accountId: 41, currency: 'EUR', amount: '36.20', categoryId: null, counterpartyId: null },
        { accountId: 40, currency: 'EUR', amount: '-36.20', categoryId: null, counterpartyId: null },
      ],
      family: { ledgerId: 7, ledgerName: 'Home', recordId: 7, link: 'SETTLEMENT', readOnly: true, recordType: 'SETTLEMENT' },
    }
    const theirs: FamilyRecord = {
      ...settlement, payer: ref(ben), author: ref(ben), canEdit: false, canDelete: false, canEditPayment: false,
      yourPayment: { entryId: 95, accountId: null, accountName: null, later: true, amount: '1.00', currency: 'EUR' },
    }
    const calls = app([anna, sam, ben], {
      'GET /api/entries/95': { status: 200, body: side },
      'GET /api/family-ledgers/7/records/7': { status: 200, body: theirs },
      'PATCH /api/entries/95/family-payment?version=1': { status: 200, body: { ...side, version: 2 } },
    })
    renderApp('/entries/95')
    expect(await screen.findByRole('heading', { name: 'Your side of a family settlement' })).toBeDefined()
    expect(screen.getByRole('link', { name: 'Open the settlement' }).getAttribute('href')).toBe('/family/7/expenses/7')
    await screen.findByText('Only Ben, who recorded it, changes its date and amount.')
    expect(screen.getByLabelText(/^Date/)).toHaveProperty('disabled', true)
    expect(screen.getByLabelText(/^Amount/)).toHaveProperty('disabled', true)
    expect(screen.getByLabelText(/^Amount/)).toHaveProperty('value', '36.20')
    expect(screen.queryByLabelText(/^Note/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull()
    const into = screen.getByLabelText(/^Received into/)
    expect(into).toHaveProperty('value', 'later')
    fireEvent.change(into, { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'PATCH')).toEqual([{
      method: 'PATCH', url: '/api/entries/95/family-payment?version=1', body: { accountId: 2 },
    }]))
  })

  it('locks a settlement side’s date and amount for its recorder while the other side’s part is on their account', async () => {
    const side: Entry = {
      id: 96, version: 0, entryDate: '2026-09-14', kind: 'FAMILY_SETTLEMENT', payeeId: null, memo: null,
      postings: [
        { accountId: 1, currency: 'EUR', amount: '-36.20', categoryId: null, counterpartyId: null },
        { accountId: 40, currency: 'EUR', amount: '36.20', categoryId: null, counterpartyId: null },
      ],
      family: { ledgerId: 7, ledgerName: 'Home', recordId: 7, link: 'SETTLEMENT', readOnly: true, recordType: 'SETTLEMENT' },
    }
    const locked: FamilyRecord = {
      ...settlement, payer: ref(anna), payee: ref(ben), canEditPayment: false, canDelete: false, lockedBy: ref(ben),
      yourPayment: { entryId: 96, accountId: 1, accountName: 'Cash', later: false, amount: '1.00', currency: 'EUR' },
    }
    app([anna, sam, ben], {
      'GET /api/entries/96': { status: 200, body: side },
      'GET /api/family-ledgers/7/records/7': { status: 200, body: locked },
    })
    renderApp('/entries/96')
    expect(await screen.findByText(/Ben has put their side of this settlement on an account of theirs/)).toBeDefined()
    expect(screen.getByLabelText(/^Date/)).toHaveProperty('disabled', true)
    expect(screen.getByLabelText(/^Amount/)).toHaveProperty('disabled', true)
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull()
    expect(screen.getByLabelText(/^Paid from/)).toHaveProperty('disabled', false)
  })

  it('tells the other side on their own entry that their account locks the settlement', async () => {
    const side: Entry = {
      id: 95, version: 2, entryDate: '2026-09-14', kind: 'FAMILY_SETTLEMENT', payeeId: null, memo: null,
      postings: [
        { accountId: 2, currency: 'EUR', amount: '36.20', categoryId: null, counterpartyId: null },
        { accountId: 40, currency: 'EUR', amount: '-36.20', categoryId: null, counterpartyId: null },
      ],
      family: { ledgerId: 7, ledgerName: 'Home', recordId: 7, link: 'SETTLEMENT', readOnly: false, recordType: 'SETTLEMENT' },
    }
    const theirs: FamilyRecord = {
      ...settlement, payer: ref(ben), author: ref(ben), canEdit: false, canDelete: false, canEditPayment: false,
      yourPayment: { entryId: 95, accountId: 2, accountName: 'Current account', later: false, amount: '1.00', currency: 'EUR' },
    }
    app([anna, sam, ben], {
      'GET /api/entries/95': { status: 200, body: side },
      'GET /api/family-ledgers/7/records/7': { status: 200, body: theirs },
    })
    renderApp('/entries/95')
    expect(await screen.findByText(/While your side is on an account of yours, Ben can’t change them or delete the settlement; choose “Specify later” to let them\./)).toBeDefined()
    expect(screen.getByLabelText(/^Received into/)).toHaveProperty('value', '2')
  })

  it('names a family income’s receipt and shares in the entry list', async () => {
    const receipt: Entry = {
      id: 97, version: 0, entryDate: '2026-09-13', kind: 'FAMILY_PAYMENT', payeeId: null, memo: null,
      postings: [
        { accountId: 2, currency: 'EUR', amount: '1000.00', categoryId: null, counterpartyId: null },
        { accountId: 40, currency: 'EUR', amount: '-1000.00', categoryId: null, counterpartyId: null },
      ],
      family: { ledgerId: 7, ledgerName: 'Home', recordId: 6, link: 'PAYMENT', readOnly: true, recordType: 'INCOME' },
    }
    app([anna, sam], {
      'GET /api/entries?page=0&size=50': { status: 200, body: { content: [receipt], page: 0, size: 50, totalElements: 1, totalPages: 1 } },
      'GET /api/entries/97': { status: 200, body: receipt },
      'GET /api/family-ledgers/7/records/6': { status: 200, body: { ...income, payer: ref(anna), yourPayment: { entryId: 97, accountId: 2, accountName: 'Current account', later: false, amount: '1.00', currency: 'EUR' } } },
    })
    renderApp('/entries/97')
    expect(await screen.findByRole('heading', { name: 'Family income received' })).toBeDefined()
    expect(screen.getByLabelText(/^Received into/)).toHaveProperty('value', '2')
    expect(screen.getByRole('link', { name: 'Open the income' }).getAttribute('href')).toBe('/family/7/expenses/6')
  })
})
