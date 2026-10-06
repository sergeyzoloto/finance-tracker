import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type {
  Account, Category, FamilyBalance, FamilyChange, FamilyLedger, FamilyMember, FamilyRecord, Me,
} from './api'
import NewFamily from './NewFamily'
import { euroBalances, testLedger } from './testLedger'

// A family budget's expenses, balances and journal (F4b): the add form with its payment and split, the server's 409s
// and 422s where they belong, an expense's page with what its reader may do, and the balances and journal in words.

type Answer = { status: number; body?: unknown }
type Call = { method: string; url: string; body: unknown }

/** Answers each request by "METHOD /api/path?query", or with 404; records every request with its JSON body. */
function stubApi(answers: Record<string, Answer | Answer[]>) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ method, url, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    const listed = answers[`${method} ${url}`]
    const answer = (Array.isArray(listed) ? (listed.length > 1 ? listed.shift() : listed[0]) : listed)
      ?? { status: 404, body: { status: 404, detail: `No static resource ${url.slice(1)}.` } }
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
const home: FamilyLedger = {
  id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
  createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01',
}
const anna: FamilyMember = { id: 70, displayName: 'Anna', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null }
const sam: FamilyMember = { id: 71, displayName: 'Sam', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: false, share: null }
const groceries: Category = { id: 30, code: 'GROCERIES', name: 'Groceries', type: 'EXPENSE', archived: false }
const familyCategories: Category[] = [
  groceries,
  { id: 31, code: 'RENT', name: 'Rent', type: 'EXPENSE', archived: false },
  { id: 32, code: 'OLD', name: 'Old', type: 'EXPENSE', archived: true },
  { id: 33, code: 'SALARY', name: 'Salary', type: 'INCOME', archived: false },
]
// The personal accounts, with a family budget's debt account and the placeholder, both system accounts.
const accounts: Account[] = [
  ...testLedger.accounts,
  { id: 40, code: 'FAMILY_DEBT_7', name: 'Debt to family budget: Home', type: 'LIABILITY', defaultCurrency: 'EUR', requiresCounterparty: false, system: true, archived: false },
  { id: 41, code: 'UNSPECIFIED_PAYMENTS', name: 'Payments without a specified account', type: 'ASSET', defaultCurrency: null, requiresCounterparty: false, system: true, archived: false },
]

const ref = (member: FamilyMember) => ({ memberId: member.id, displayName: member.displayName })
const record: FamilyRecord = {
  id: 5, type: 'EXPENSE', date: '2026-09-12', category: { id: 30, code: 'GROCERIES', name: 'Groceries', archived: false },
  amount: '10.01', currency: 'EUR', comment: 'Weekly shop', payer: ref(anna),
  splitMethod: 'EQUAL',
  shares: [
    { member: ref(anna), amount: '5.01', basisPoints: null, updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z' },
    { member: ref(sam), amount: '5.00', basisPoints: null, updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z' },
  ],
  author: ref(anna), createdAt: '2026-09-12T10:00:00Z', updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z',
  version: 0, frozen: false, canEdit: true, canDelete: true, canEditPayment: true,
  yourPayment: { entryId: 90, accountId: 1, accountName: 'Cash', later: false, amount: '1.00', currency: 'EUR' },
}

/** The family budget's own requests, with its members; and anything else. */
function budget(members: FamilyMember[], answers: Record<string, Answer | Answer[]> = {}, ledger = home) {
  return stubApi({
    'GET /api/family-ledgers': { status: 200, body: [ledger] },
    'GET /api/family-ledgers/7': { status: 200, body: ledger },
    'GET /api/family-ledgers/7/members': { status: 200, body: members },
    'GET /api/family-ledgers/7/categories': { status: 200, body: familyCategories },
    'GET /api/accounts': { status: 200, body: accounts },
    'GET /api/family-ledgers/7/journal?recordId=5&size=200': { status: 200, body: { content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 } },
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

/** The field a label names, as the form shows it with its hints and messages. */
const field = (label: string) => screen.getByText(label, { selector: '.label' }).closest('label')!
const save = () => screen.getByRole('button', { name: 'Add the expense' })

async function fillIn(amount = '10.01') {
  fireEvent.change(await screen.findByLabelText('Category'), { target: { value: '30' } })
  fireEvent.change(screen.getByLabelText(/^Amount \(/), { target: { value: amount } })
}

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

describe('adding an expense', () => {
  it('makes a caller who paid choose an account or “Specify later”, and hides the choice for another payer', async () => {
    const calls = budget([anna, sam], {
      'POST /api/family-ledgers/7/records': { status: 201, body: { ...record, id: 6 } },
    })
    renderApp('/family/7/expenses/new')
    await fillIn()

    expect(screen.getByLabelText(/^Date/)).toHaveProperty('value', '2026-09-30')
    expect(screen.getByLabelText(/^Date/).getAttribute('min')).toBe('2026-09-01')
    // The family's expense categories that aren't archived.
    expect(within(screen.getByLabelText('Category')).getAllByRole('option').map((o) => o.textContent))
      .toEqual(['Choose a category', 'Groceries', 'Rent'])
    // The accounts the backend takes for a payment: never a system account, one kept per counterparty, an equity or
    // an archived one.
    const paidFrom = screen.getByLabelText(/^Paid from/)
    await waitFor(() => expect(within(paidFrom).getAllByRole('option').map((o) => o.textContent))
      .toEqual(['Choose an account', 'Cash', 'ING', 'Wise', 'Family budget', 'Specify later']))
    expect(field('Paid from').textContent).toContain('“Specify later” keeps the payment under “Payments without a specified account”')
    expect(save()).toHaveProperty('disabled', true)

    fireEvent.change(paidFrom, { target: { value: 'later' } })
    expect(save()).toHaveProperty('disabled', false)

    // Sam pays: no account of theirs to choose.
    fireEvent.change(screen.getByLabelText('Paid by'), { target: { value: '71' } })
    expect(screen.queryByLabelText(/^Paid from/)).toBeNull()
    // The rule's equal shares, with the remainder to the largest share, on a tie to the payer.
    expect(screen.getByTestId('share-70').textContent).toContain('€5.00')
    expect(screen.getByTestId('share-71').textContent).toContain('€5.01')
    fireEvent.click(save())

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/family/7/expenses/6'))
    expect(calls.filter((c) => c.method === 'POST')).toEqual([{
      method: 'POST', url: '/api/family-ledgers/7/records', body: {
        type: 'EXPENSE', date: '2026-09-30', categoryId: 30, amount: '10.01', comment: null, payerMemberId: 71,
        split: { method: 'RULE' },
      },
    }])
  })

  it('sends the account the caller paid with, and preselects it the next time', async () => {
    const calls = budget([anna, sam], {
      'POST /api/family-ledgers/7/records': { status: 201, body: record },
    })
    renderApp('/family/7/expenses/new')
    await fillIn()
    const paidFrom = screen.getByLabelText(/^Paid from/)
    await waitFor(() => expect(within(paidFrom).getAllByRole('option')).toHaveLength(6))
    fireEvent.change(paidFrom, { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText(/^Comment/), { target: { value: ' Weekly shop ' } })
    fireEvent.click(save())

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/family/7/expenses/5'))
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      type: 'EXPENSE', date: '2026-09-30', categoryId: 30, amount: '10.01', comment: 'Weekly shop', payerMemberId: 70,
      paymentAccountId: 1, split: { method: 'RULE' },
    })
    cleanup()

    renderApp('/family/7/expenses/new')
    await waitFor(() => expect(screen.getByLabelText(/^Paid from/)).toHaveProperty('value', '1'))
  })

  it('shows each way of splitting with its total, and saves only when it adds up', async () => {
    budget([anna, sam])
    renderApp('/family/7/expenses/new')
    await fillIn()
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: 'later' } })
    expect(screen.getByTestId('share-70').textContent).toContain('€5.01')
    expect(screen.getByTestId('split-total').textContent).toBe('€10.01')
    expect(save()).toHaveProperty('disabled', false)

    // Percentages: equal ones to start with, a live total, the amounts as the server splits them.
    fireEvent.click(screen.getByLabelText('Percentages'))
    expect(screen.getByLabelText('Share of Anna in percent')).toHaveProperty('value', '50.00')
    fireEvent.change(screen.getByLabelText('Share of Sam in percent'), { target: { value: '40' } })
    expect(screen.getByTestId('percent-total').textContent).toBe('90.00 %')
    expect(screen.getByText('The percentages must add up to exactly 100.00 %.')).toBeDefined()
    expect(save()).toHaveProperty('disabled', true)
    fireEvent.change(screen.getByLabelText('Share of Anna in percent'), { target: { value: '60' } })
    expect(screen.getByTestId('percent-total').textContent).toBe('100.00 %')
    expect(screen.getByTestId('share-70').textContent).toContain('€6.01')
    expect(screen.getByTestId('share-71').textContent).toContain('€4.00')
    expect(save()).toHaveProperty('disabled', false)

    // Amounts start from those, and must add up to the expense.
    fireEvent.click(screen.getByLabelText('Amounts'))
    expect(screen.getByLabelText('Amount of Anna')).toHaveProperty('value', '6.01')
    expect(screen.getByTestId('amount-total').textContent).toBe('€10.01')
    fireEvent.change(screen.getByLabelText('Amount of Sam'), { target: { value: '5' } })
    expect(screen.getByTestId('amount-total').textContent).toBe('€11.01')
    expect(screen.getByText('The amounts add up to 11.01, not to 10.01.')).toBeDefined()
    expect(save()).toHaveProperty('disabled', true)
    fireEvent.change(screen.getByLabelText('Amount of Sam'), { target: { value: '5.001' } })
    expect(screen.getByText('EUR has at most 2 decimals.')).toBeDefined()
    fireEvent.change(screen.getByLabelText('Amount of Sam'), { target: { value: '4,00' } })
    expect(save()).toHaveProperty('disabled', false)

    // Entirely on one member, once one is chosen.
    fireEvent.click(screen.getByLabelText('Entirely on one member'))
    expect(save()).toHaveProperty('disabled', true)
    fireEvent.change(screen.getByLabelText('On whom'), { target: { value: '71' } })
    expect(screen.getByTestId('share-71').textContent).toContain('€10.01')
    expect(screen.getByTestId('share-70').textContent).toContain('€0.00')
    expect(save()).toHaveProperty('disabled', false)
  })

  it('splits by the custom rule, and in whole units for a currency without cents', async () => {
    const custom = { ...home, splitRule: 'CUSTOM' as const, baseCurrency: 'JPY' }
    budget([{ ...anna, share: 6000 }, { ...sam, share: 4000 }], {}, custom)
    renderApp('/family/7/expenses/new')
    await fillIn('1001')
    expect(screen.getByLabelText(/The budget’s rule/).closest('label')!.textContent).toContain('Anna 60.00 %, Sam 40.00 %')
    expect(screen.getByTestId('share-70').textContent).toContain('¥601')
    expect(screen.getByTestId('share-71').textContent).toContain('¥400')
    fireEvent.change(screen.getByLabelText(/^Amount \(JPY\)/), { target: { value: '10.5' } })
    expect(field('Amount (JPY)').textContent).toContain('JPY has no decimals.')
  })

  it('shows the server’s 422 next to the field and the member it names', async () => {
    budget([anna, sam], {
      'POST /api/family-ledgers/7/records': { status: 422, body: {
        status: 422, detail: 'The record breaks 4 rules',
        violations: ['a', 'b', 'c', 'd'],
        violationDetails: [
          { code: 'CATEGORY', memberId: null, message: 'the category GROCERIES is archived' },
          { code: 'PAYMENT', memberId: 70, message: 'the account CASH can’t pay a family record' },
          { code: 'SHARE', memberId: 71, message: 'Sam needs a share from 0 to 10000 basis points' },
          { code: 'SUM_NOT_WHOLE', memberId: null, message: 'the shares sum to 90.00 %, not 100.00 %' },
        ],
      } },
    })
    renderApp('/family/7/expenses/new')
    await fillIn()
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: 'later' } })
    fireEvent.click(save())

    expect(await screen.findByText('The category GROCERIES is archived.')).toBeDefined()
    expect(field('Category').textContent).toContain('The category GROCERIES is archived.')
    expect(field('Paid from').textContent).toContain('The account CASH can’t pay a family record.')
    const samRow = screen.getByRole('rowheader', { name: /^Sam/ })
    expect(samRow.textContent).toContain('Sam needs a share from 0 to 10000 basis points.')
    expect(screen.getByRole('group', { name: 'Split' }).textContent).toContain('The shares sum to 90.00 %, not 100.00 %.')
  })

  it('shows the start date’s 409 at the date, and never offers a day before it', async () => {
    budget([anna, sam], {
      'POST /api/family-ledgers/7/records': { status: 409, body: {
        status: 409, detail: 'The family budget starts on 2026-09-01, and a record can’t be dated before its start date',
      } },
    })
    renderApp('/family/7/expenses/new')
    await fillIn()
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: 'later' } })
    fireEvent.change(screen.getByLabelText(/^Date/), { target: { value: '2026-08-31' } })
    expect(field('Date').textContent).toContain('an expense can’t be earlier')
    expect(save()).toHaveProperty('disabled', true)

    fireEvent.change(screen.getByLabelText(/^Date/), { target: { value: '2026-09-01' } })
    fireEvent.click(save())
    await waitFor(() => expect(field('Date').textContent)
      .toContain('The family budget starts on 2026-09-01, and a record can’t be dated before its start date.'))
  })
})

describe('an expense’s page', () => {
  const detail = (changes: Partial<FamilyRecord>, answers: Record<string, Answer | Answer[]> = {}) => budget([anna, sam], {
    'GET /api/family-ledgers/7/records/5': { status: 200, body: { ...record, ...changes } },
    ...answers,
  })

  it('shows every field, the shares with percentages, and the payer’s own account', async () => {
    const calls = detail({})
    renderApp('/family/7/expenses/5')

    expect(await screen.findByRole('heading', { name: 'Groceries, Sep 12, 2026' })).toBeDefined()
    expect(screen.getByText('Weekly shop')).toBeDefined()
    expect(screen.getByText('Anna (you)', { selector: 'dd' })).toBeDefined()
    expect((await screen.findByRole('link', { name: 'Cash' })).getAttribute('href')).toBe('/entries/90')
    const shares = screen.getAllByRole('table')[0]
    expect(within(shares).getAllByRole('row').map((r) => r.textContent?.replace(/changed by.*?PM|changed by.*?AM/, '')))
      .toEqual(['MemberSharePercent', 'AnnaYou€5.0150.05 %', 'Sam€5.0049.95 %', 'Total€10.01'])
    // The payment's fields change here now (F4c): no advice to delete and enter it again.
    expect(screen.queryByText(/delete the expense and enter it again/)).toBeNull()
    // The account comes with the record, for the payer only: no search of the day's entries.
    expect(calls.some((c) => c.url.startsWith('/api/entries'))).toBe(false)
  })

  it('shows “Specify later” to the payer, and no account to anyone else', async () => {
    detail({ yourPayment: { entryId: 90, accountId: null, accountName: null, later: true, amount: '1.00', currency: 'EUR' } })
    renderApp('/family/7/expenses/5')
    expect((await screen.findByRole('link', { name: 'Specify later' })).getAttribute('href')).toBe('/entries/90')
    cleanup()

    detail({ payer: ref(sam), yourPayment: undefined })
    renderApp('/family/7/expenses/5')
    await screen.findByRole('heading', { name: 'Groceries, Sep 12, 2026' })
    expect(screen.queryByText('Paid from')).toBeNull()
  })

  it.each<[string, Partial<FamilyRecord>, boolean, boolean]>([
    ['the author, who paid', {}, true, true],
    ['an owner, not the payer with an account', { canDelete: false, canEditPayment: false }, true, false],
    ['the payer, who may change only the payment', { canEdit: false }, true, true],
    ['a member who may change nothing', { canEdit: false, canEditPayment: false, canDelete: false }, false, false],
    ['nobody, when frozen', { canEdit: false, canDelete: false, canEditPayment: false, frozen: true }, false, false],
  ])('offers changes and deletion as the server allows: %s', async (_, changes, edit, remove) => {
    detail(changes)
    renderApp('/family/7/expenses/5')
    await screen.findByRole('heading', { name: 'Groceries, Sep 12, 2026' })
    expect(screen.queryByRole('button', { name: 'Save the changes' }) !== null).toBe(edit)
    expect(screen.queryByRole('button', { name: 'Delete the expense' }) !== null).toBe(remove)
    expect(screen.queryByText(/nobody can change it/) !== null).toBe(changes.frozen === true)
  })

  it('sends only what changed, with the version it read', async () => {
    const calls = detail({}, {
      'PATCH /api/family-ledgers/7/records/5?version=0': { status: 200, body: { ...record, version: 1 } },
    })
    renderApp('/family/7/expenses/5')
    const change = await screen.findByRole('button', { name: 'Save the changes' })
    expect(change).toHaveProperty('disabled', true)

    fireEvent.click(screen.getByLabelText('Entirely on one member'))
    fireEvent.change(screen.getByLabelText('On whom'), { target: { value: '71' } })
    fireEvent.click(change)

    await waitFor(() => expect(calls.filter((c) => c.method === 'PATCH')).toEqual([{
      method: 'PATCH', url: '/api/family-ledgers/7/records/5?version=0', body: { split: { method: 'ONE_MEMBER', memberId: 71 } },
    }]))
    expect(await screen.findByText('Saved.')).toBeDefined()
  })

  it('says when the expense changed meanwhile, and shows it as it is now', async () => {
    const calls = detail({}, {
      'GET /api/family-ledgers/7/records/5': [
        { status: 200, body: record },
        { status: 200, body: { ...record, version: 1, comment: 'Changed by Sam' } },
      ],
      'PATCH /api/family-ledgers/7/records/5?version=0': { status: 409, body: {
        status: 409, title: 'Changed in the meantime', detail: 'Record 5 has changed since version 0. Reload it and try again.',
      } },
    })
    renderApp('/family/7/expenses/5')
    fireEvent.change(await screen.findByLabelText(/^Comment/), { target: { value: 'Mine' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the changes' }))

    expect(await screen.findByText('Record 5 has changed since version 0. Reload it and try again.')).toBeDefined()
    await waitFor(() => expect(screen.getByLabelText(/^Comment/)).toHaveProperty('value', 'Changed by Sam'))
    expect(calls.filter((c) => c.url === '/api/family-ledgers/7/records/5' && c.method === 'GET').length).toBeGreaterThanOrEqual(2)
  })

  it('deletes after a confirmation, and says so when it is gone', async () => {
    const calls = detail({}, {
      'DELETE /api/family-ledgers/7/records/5?version=0': { status: 204 },
      'GET /api/family-ledgers/7/records?page=0&size=20': { status: 200, body: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } },
    })
    renderApp('/family/7/expenses/5')
    fireEvent.click(await screen.findByRole('button', { name: 'Delete the expense' }))
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/family/7/expenses'))
    expect(calls.filter((c) => c.method === 'DELETE').map((c) => c.url)).toEqual(['/api/family-ledgers/7/records/5?version=0'])
    expect(await screen.findByText('Nothing recorded yet.')).toBeDefined()
    cleanup()

    budget([anna, sam], { 'GET /api/family-ledgers/7/records/5': { status: 404, body: { status: 404, detail: 'Record 5 not found' } } })
    renderApp('/family/7/expenses/5')
    expect(await screen.findByRole('heading', { name: 'Not found' })).toBeDefined()
    // The budget itself is still there.
    expect(screen.getByRole('heading', { name: 'Home' })).toBeDefined()
  })
})

describe('the list of expenses', () => {
  it('shows the date, category, payer, amount and the reader’s share, and marks a frozen one', async () => {
    budget([anna, sam], {
      'GET /api/family-ledgers/7/records?page=0&size=20': { status: 200, body: {
        content: [
          { ...record, id: 6, payer: ref(sam), frozen: true, canEdit: false, canDelete: false },
          record,
        ],
        page: 0, size: 20, totalElements: 2, totalPages: 1,
      } },
    })
    renderApp('/family/7/expenses')
    const rows = (await screen.findAllByRole('row')).slice(1)
    expect(rows.map((r) => r.textContent)).toEqual([
      'Sep 12, 2026GroceriesFrozenPaid by Sam€10.01Yours €5.01',
      'Sep 12, 2026GroceriesPaid by you€10.01Yours €5.01',
    ])
    fireEvent.click(rows[1])
    expect(screen.getByTestId('where').textContent).toBe('/family/7/expenses/5')
  })
})

describe('balances', () => {
  const balances = (members: FamilyBalance[]): Answer => ({ status: 200, body: euroBalances(members) })
  const b = (member: FamilyMember, balance: string, extra: object = {}) => ({
    memberId: member.id, displayName: member.displayName, status: member.status, hasAccount: member.hasAccount, balance,
    you: member.id === 70, ...extra,
  })

  it('says on the overview who owes the reader, with two members', async () => {
    budget([anna, sam], {
      'GET /api/family-ledgers/7/balances': balances([b(anna, '-40.00'), b(sam, '40.00')]),
      'GET /api/family-ledgers/7/records?size=5': { status: 200, body: { content: [record], page: 0, size: 5, totalElements: 1, totalPages: 1 } },
    })
    renderApp('/family/7')
    expect(await screen.findByText('Sam owes you €40.00.')).toBeDefined()
    expect(screen.getByText('Start date').nextElementSibling!.textContent).toBe('Sep 1, 2026')
    expect(screen.getByRole('link', { name: 'Add an expense' }).getAttribute('href')).toBe('/family/7/expenses/new')
    expect(screen.getByRole('link', { name: 'Groceries' })).toBeDefined()
  })

  it('reads every member’s balance in words, marks who they are, and adds up to zero', async () => {
    const ben: FamilyMember = { ...sam, id: 72, displayName: 'Ben', hasAccount: true }
    budget([anna, sam, ben], {
      'GET /api/family-ledgers/7/balances': balances([
        b(anna, '-60.00'), b(sam, '40.00'), b(ben, '20.00'),
        b({ ...sam, id: 73, displayName: 'Former member', status: 'FORMER' }, '0.00'),
      ]),
    })
    renderApp('/family/7/balances')
    expect(await screen.findByText('Sam owes you €40.00.')).toBeDefined()
    expect(screen.getByText('Ben owes you €20.00.')).toBeDefined()
    const rows = within(screen.getByRole('table')).getAllByRole('row')
    expect(rows.map((r) => r.textContent)).toEqual([
      'MemberEUR', 'AnnaYouis owed €60.00', 'SamNo accountowes €40.00', 'Benowes €20.00', 'Former memberDeleted their datais settled',
      'All together€0.00',
    ])
    expect(within(screen.getByRole('list')).getAllByRole('listitem').map((li) => li.textContent))
      .toEqual(['Sam owes you €40.00 Settle up', 'Ben owes you €20.00 Settle up'])
  })
})

describe('the journal', () => {
  it('reads as sentences, the system’s changes and former members included', async () => {
    const change = (id: number, c: Partial<FamilyChange>): FamilyChange => ({
      id, at: '2026-09-30T10:00:00Z', action: 'UPDATE', recordId: 5, author: ref(anna), about: null, changes: [],
      record: { date: '2026-09-12', category: 'Groceries', amount: '10.01', deleted: false }, ...c,
    })
    budget([anna, sam], {
      'GET /api/family-ledgers/7/journal?page=0&size=50': { status: 200, body: {
        content: [
          change(4, { action: 'SPLIT_RULE_RESET', author: null, recordId: null, record: null,
            about: { memberId: 72, displayName: 'Former member' },
            changes: [{ field: 'splitRule', member: null, old: 'CUSTOM', new: 'EQUAL' }] }),
          change(3, { action: 'DELETE', recordId: 6, author: { memberId: 72, displayName: 'Former member' },
            record: { date: '2026-09-10', category: 'Rent', amount: '500.00', deleted: true } }),
          change(2, { changes: [{ field: 'share', member: ref(sam), old: '5.00', new: '6.00' },
            { field: 'share', member: ref(anna), old: '5.01', new: '4.01' }] }),
        ],
        page: 0, size: 50, totalElements: 3, totalPages: 1,
      } },
    })
    renderApp('/family/7/journal')
    const items = within(await screen.findByRole('list')).getAllByRole('listitem')
    expect(items.map((li) => li.querySelector('p')!.textContent)).toEqual([
      'The split rule went back to equal shares when a member left: Former member.',
      'Former member deleted Rent, Sep 10, 2026, €500.00.Deleted',
      'Anna changed the split of Groceries, Sep 12, 2026: Sam €5.00 → €6.00, Anna €5.01 → €4.01. Open',
    ])
    expect(within(items[2]).getByRole('link', { name: 'Open' }).getAttribute('href')).toBe('/family/7/expenses/5')
    expect(within(items[1]).queryByRole('link')).toBeNull()
  })
})

describe('creating a family budget', () => {
  const reference = {
    'GET /api/settings': { status: 200, body: { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' } },
    'GET /api/accounts': { status: 200, body: [] },
    'GET /api/categories': { status: 200, body: [] },
  }

  function renderNewFamily() {
    render(
      <MemoryRouter initialEntries={['/family/new']}>
        <Routes>
          <Route path="/family/new" element={<NewFamily me={ON} onCreated={() => {}} />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>,
    )
  }

  it('starts today by default, never later, and sends an earlier start date', async () => {
    const calls = stubApi({ ...reference, 'POST /api/family-ledgers': { status: 201, body: home } })
    renderNewFamily()
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Home' } })
    const start = screen.getByLabelText(/^Start date/)
    expect(start).toHaveProperty('value', '2026-09-30')
    expect(start.getAttribute('max')).toBe('2026-09-30')
    expect(field('Start date').textContent).toContain('The first day expenses can have; it is also the day you join the budget.')

    fireEvent.change(start, { target: { value: '2026-10-01' } })
    expect(field('Start date').textContent).toContain('A family budget starts today or earlier.')
    expect(screen.getByRole('button', { name: 'Create family budget' })).toHaveProperty('disabled', true)

    fireEvent.change(start, { target: { value: '2026-08-30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create family budget' }))
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/family/7'))
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      name: 'Home', baseCurrency: 'EUR', displayName: 'Anna', categoryIds: [], startDate: '2026-08-30',
    })
  })

  it('shows the server’s refusal of a start date in its future at the field', async () => {
    stubApi({ ...reference, 'POST /api/family-ledgers': { status: 422, body: {
      status: 422, detail: 'The request breaks 1 rule', violations: ['x'],
      violationDetails: [{ code: 'START_DATE', memberId: null, message: 'the start date 2026-09-30 is in the future; a family budget starts today or earlier' }],
    } } })
    renderNewFamily()
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Home' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create family budget' }))
    await waitFor(() => expect(field('Start date').textContent)
      .toContain('The start date 2026-09-30 is in the future; a family budget starts today or earlier.'))
  })
})
