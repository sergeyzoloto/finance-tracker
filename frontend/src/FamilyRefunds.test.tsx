import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Category, Counterparty, FamilyChange, FamilyLedger, FamilyMember, FamilyRecord, Me } from './api'
import { journalLine, recordName, recordTitle, recordWho } from './family'
import { testLedger } from './testLedger'

// Refunds (D-79), payments from an account that requires a counterparty (D-80) and the payer's payee (D-81), on the
// family budget's forms and pages; and the journal's currencies (D-93) in words.

type Answer = { status: number; body?: unknown }
type Call = { method: string; url: string; body: unknown }

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

const ON: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }
const home: FamilyLedger = {
  id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
  createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01',
}
const anna: FamilyMember = { id: 70, displayName: 'Anna', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null }
const sam: FamilyMember = { id: 71, displayName: 'Sam', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: false, share: null }
const familyCategories: Category[] = [
  { id: 30, code: 'GROCERIES', name: 'Groceries', type: 'EXPENSE', archived: false },
  { id: 33, code: 'SALARY', name: 'Salary', type: 'INCOME', archived: false },
]
const counterparties: Counterparty[] = testLedger.counterparties
const accounts: Account[] = testLedger.accounts

const ref = (member: FamilyMember) => ({ memberId: member.id, displayName: member.displayName })
/** An expense paid by Anna from her cash, 10.01 split between her and Sam. */
const expense: FamilyRecord = {
  id: 5, type: 'EXPENSE', date: '2026-09-12', category: { id: 30, code: 'GROCERIES', name: 'Groceries', archived: false },
  amount: '10.01', currency: 'EUR', comment: null, payer: ref(anna), splitMethod: 'EQUAL',
  shares: [
    { member: ref(anna), amount: '5.01', basisPoints: null, updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z' },
    { member: ref(sam), amount: '5.00', basisPoints: null, updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z' },
  ],
  author: ref(anna), createdAt: '2026-09-12T10:00:00Z', updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z',
  version: 0, frozen: false, canEdit: true, canDelete: true, canEditPayment: true, refund: false,
  yourPayment: { entryId: 90, accountId: 1, accountName: 'Cash', later: false, amount: '10.01', currency: 'EUR' },
}
/** The same as a refund: what was refunded, and each share, above 0, with the flag. */
const refund: FamilyRecord = { ...expense, id: 6, refund: true }

function budget(answers: Record<string, Answer | Answer[]> = {}) {
  return stubApi({
    'GET /api/family-ledgers': { status: 200, body: [home] },
    'GET /api/family-ledgers/7': { status: 200, body: home },
    'GET /api/family-ledgers/7/members': { status: 200, body: [anna, sam] },
    'GET /api/family-ledgers/7/categories': { status: 200, body: familyCategories },
    'GET /api/accounts': { status: 200, body: accounts },
    'GET /api/counterparties': { status: 200, body: counterparties },
    'GET /api/family-ledgers/7/journal?recordId=5&size=200': { status: 200, body: { content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 } },
    'GET /api/family-ledgers/7/journal?recordId=6&size=200': { status: 200, body: { content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 } },
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

const field = (label: string) => screen.getByText(label, { selector: '.label' }).closest('label')!

async function fillIn(addLabel: string) {
  fireEvent.change(await screen.findByLabelText('Category'), { target: { value: '30' } })
  fireEvent.change(screen.getByLabelText(/^Amount \(/), { target: { value: '10.01' } })
  return () => screen.getByRole('button', { name: addLabel })
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

describe('adding a refund (D-79)', () => {
  it('turns the expense form into a refund: words, the flag and the same amount, split by the same shares', async () => {
    const calls = budget({ 'POST /api/family-ledgers/7/records': { status: 201, body: refund } })
    renderApp('/family/7/expenses/new')
    expect(await screen.findByRole('heading', { name: 'Add an expense' })).toBeDefined()
    expect(screen.queryByLabelText(/^Refund/)).not.toBeNull()
    expect(screen.getByLabelText('Paid by')).toBeDefined()

    fireEvent.click(screen.getByLabelText(/^Refund/))
    expect(screen.getByRole('heading', { name: 'Add a refund' })).toBeDefined()
    expect(screen.getByLabelText('Received by')).toBeDefined()
    expect(screen.queryByLabelText('Paid by')).toBeNull()
    const add = await fillIn('Add the refund')
    const receivedInto = screen.getByLabelText(/^Received into/)
    await waitFor(() => expect(within(receivedInto).getAllByRole('option').length).toBeGreaterThan(2))
    fireEvent.change(receivedInto, { target: { value: '1' } })
    // The shares of the refund, as of an expense: what each member gets back.
    expect(screen.getByTestId('share-70').textContent).toContain('€5.01')
    expect(add()).toHaveProperty('disabled', false)
    fireEvent.click(add())

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/family/7/expenses/6'))
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      type: 'EXPENSE', date: '2026-09-30', categoryId: 30, amount: '10.01', comment: null, payerMemberId: 70,
      paymentAccountId: 1, split: { method: 'RULE' }, refund: true,
    })
  })

  it('has no refund for an income', async () => {
    budget()
    renderApp('/family/7/incomes/new')
    expect(await screen.findByRole('heading', { name: 'Add an income' })).toBeDefined()
    expect(screen.queryByLabelText(/^Refund/)).toBeNull()
  })

  it('shows a refund with its minus in the list and on its page, and says who received the money back', async () => {
    budget({
      'GET /api/family-ledgers/7/records?page=0&size=20': { status: 200, body: { content: [refund, expense], page: 0, size: 20, totalElements: 2, totalPages: 1 } },
      'GET /api/family-ledgers/7/records/6': { status: 200, body: refund },
    })
    renderApp('/family/7/expenses')
    const rows = (await screen.findAllByRole('row')).slice(1).map((r) => r.textContent)
    expect(rows[0]).toContain('Groceries (refund)')
    expect(rows[0]).toContain('Received by you')
    expect(rows[0]).toContain('-€10.01')
    expect(rows[0]).toContain('Yours -€5.01')
    expect(rows[1]).not.toContain('(refund)')
    expect(rows[1]).toContain('Paid by you')
    expect(rows[1]).toContain('€10.01')
    cleanup()

    renderApp('/family/7/expenses/6')
    expect(await screen.findByRole('heading', { name: 'Groceries, Sep 12, 2026' })).toBeDefined()
    expect(screen.getByText('Refund', { selector: '.badge' })).toBeDefined()
    expect(screen.getByText('Received by', { selector: 'dt' })).toBeDefined()
    expect(screen.getByText('-€10.01', { selector: 'dd' })).toBeDefined()
    expect(screen.getByText('Received into', { selector: 'dt' })).toBeDefined()
    const shares = screen.getAllByRole('table')[0]
    expect(within(shares).getAllByRole('row').map((r) => r.textContent?.replace(/changed by.*?[AP]M/, '')))
      .toEqual(['MemberSharePercent', 'AnnaYou-€5.0150.05 %', 'Sam-€5.0049.95 %', 'Total-€10.01'])
  })

  it('names a refund in words, from the reader’s side', () => {
    expect(recordTitle(refund)).toBe('Groceries (refund)')
    expect(recordTitle(expense)).toBe('Groceries')
    expect(recordWho(refund, 70)).toBe('Received by you')
    expect(recordWho(refund, 71)).toBe('Received by Anna')
    expect(recordName({ type: 'EXPENSE', category: 'Groceries', date: '2026-09-12', refund: true }))
      .toBe('the refund Groceries, Sep 12, 2026')
  })
})

describe('an account that requires a counterparty, and the payee (D-80, D-81)', () => {
  it('offers such an account for an expense and not for an income, and asks for the counterparty', async () => {
    const calls = budget({ 'POST /api/family-ledgers/7/records': { status: 201, body: expense } })
    renderApp('/family/7/expenses/new')
    const add = await fillIn('Add the expense')
    const paidFrom = screen.getByLabelText(/^Paid from/)
    await waitFor(() => expect(within(paidFrom).getAllByRole('option').map((o) => o.textContent)).toEqual(
      ['Choose an account', 'Cash', 'ING', 'Loans given', 'Wise', 'Debts to creditors', 'Family budget', 'Specify later']))
    expect(screen.queryByLabelText(/^Counterparty/)).toBeNull()

    fireEvent.change(paidFrom, { target: { value: '4' } })
    const counterparty = screen.getByLabelText(/^Counterparty/)
    expect(add()).toHaveProperty('disabled', true)
    expect(within(counterparty).getAllByRole('option').map((o) => o.textContent))
      .toEqual(['Choose a counterparty', 'Albert Heijn', 'Employer', 'Ivan'])
    fireEvent.change(counterparty, { target: { value: '22' } })
    fireEvent.change(screen.getByLabelText(/^Payee/), { target: { value: '21' } })
    expect(add()).toHaveProperty('disabled', false)
    fireEvent.click(add())

    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')!.body).toMatchObject({
      paymentAccountId: 4, paymentCounterpartyId: 22, payeeId: 21,
    })
    cleanup()

    budget()
    renderApp('/family/7/incomes/new')
    const receivedInto = await screen.findByLabelText(/^Received into/)
    await waitFor(() => expect(within(receivedInto).getAllByRole('option').length).toBeGreaterThan(2))
    expect(within(receivedInto).queryByRole('option', { name: 'Loans given' })).toBeNull()
    expect(within(receivedInto).queryByRole('option', { name: 'Debts to creditors' })).toBeNull()
  })

  it('sends no counterparty for an account that has none, and a payee only when chosen', async () => {
    const calls = budget({ 'POST /api/family-ledgers/7/records': { status: 201, body: expense } })
    renderApp('/family/7/expenses/new')
    const add = await fillIn('Add the expense')
    await waitFor(() => expect(within(screen.getByLabelText(/^Paid from/)).getAllByRole('option').length).toBeGreaterThan(3))
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: '1' } })
    // A counterparty chosen for another account is gone with the account.
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: '3' } })
    fireEvent.change(screen.getByLabelText(/^Counterparty/), { target: { value: '22' } })
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: '1' } })
    expect(screen.queryByLabelText(/^Counterparty/)).toBeNull()
    fireEvent.click(add())
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    const body = calls.find((c) => c.method === 'POST')!.body as Record<string, unknown>
    expect(body.paymentCounterpartyId).toBeUndefined()
    expect(body.payeeId).toBeUndefined()
    expect(body.refund).toBeUndefined()
  })

  it('shows the server’s objection to a counterparty next to its field', async () => {
    budget({
      'POST /api/family-ledgers/7/records': { status: 422, body: {
        status: 422, detail: 'invalid', violations: ['x'],
        violationDetails: [{ code: 'COUNTERPARTY', memberId: 70, message: 'the counterparty 22 is not one of your counterparties' }],
      } },
    })
    renderApp('/family/7/expenses/new')
    const add = await fillIn('Add the expense')
    await waitFor(() => expect(within(screen.getByLabelText(/^Paid from/)).getAllByRole('option').length).toBeGreaterThan(3))
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: '4' } })
    fireEvent.change(screen.getByLabelText(/^Counterparty/), { target: { value: '22' } })
    fireEvent.click(add())
    await waitFor(() => expect(field('Counterparty').textContent)
      .toContain('The counterparty 22 is not one of your counterparties.'))
  })

  it('changes the payee and the counterparty on the record’s page, and only what changed', async () => {
    const mine = { ...expense, yourPayment: { ...expense.yourPayment!, accountId: 4, accountName: 'Debts to creditors', counterpartyId: 22, payeeId: 21 } }
    const calls = budget({
      'GET /api/family-ledgers/7/records/5': { status: 200, body: mine },
      'PATCH /api/family-ledgers/7/records/5?version=0': { status: 200, body: { ...mine, version: 0 } },
    })
    renderApp('/family/7/expenses/5')
    // The page names them for the reader: their own list, nobody else's.
    expect(await screen.findByText('Payee', { selector: 'dt' })).toBeDefined()
    expect((await screen.findByText(/^Albert Heijn/, { selector: 'dd' })).textContent).toContain('Only you see it')
    expect(await screen.findByText(/with Ivan/)).toBeDefined()

    const save = await screen.findByRole('button', { name: 'Save the changes' })
    await waitFor(() => expect(screen.getByLabelText(/^Counterparty/)).toHaveProperty('value', '22'))
    expect(save).toHaveProperty('disabled', true)
    fireEvent.change(screen.getByLabelText(/^Payee/), { target: { value: '' } })
    expect(save).toHaveProperty('disabled', false)
    fireEvent.click(save)
    await waitFor(() => expect(calls.some((c) => c.method === 'PATCH')).toBe(true))
    expect(calls.find((c) => c.method === 'PATCH')!.body).toEqual({ payeeId: null })
    cleanup()

    const again = budget({ 'GET /api/family-ledgers/7/records/5': { status: 200, body: mine },
      'PATCH /api/family-ledgers/7/records/5?version=0': { status: 200, body: mine } })
    renderApp('/family/7/expenses/5')
    await waitFor(() => expect(screen.getByLabelText(/^Counterparty/)).toHaveProperty('value', '22'))
    fireEvent.change(screen.getByLabelText(/^Counterparty/), { target: { value: '23' } })
    fireEvent.change(screen.getByLabelText(/^Payee/), { target: { value: '22' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the changes' }))
    await waitFor(() => expect(again.some((c) => c.method === 'PATCH')).toBe(true))
    expect(again.find((c) => c.method === 'PATCH')!.body).toEqual({ paymentCounterpartyId: 23, payeeId: 22 })
  })

  it('shows no counterparty and no payee to a member who didn’t pay', async () => {
    budget({ 'GET /api/family-ledgers/7/records/5': { status: 200, body: { ...expense, payer: ref(sam), yourPayment: undefined, canEditPayment: false } } })
    renderApp('/family/7/expenses/5')
    await screen.findByRole('heading', { name: 'Groceries, Sep 12, 2026' })
    expect(screen.queryByText('Payee', { selector: 'dt' })).toBeNull()
    expect(screen.queryByLabelText(/^Payee/)).toBeNull()
    expect(screen.queryByLabelText(/^Counterparty/)).toBeNull()
  })
})

describe('the journal’s currencies (D-93)', () => {
  const author = ref(anna)
  const row = (changes: FamilyChange['changes'], extra: Partial<FamilyChange> = {}): FamilyChange => ({
    id: 1, at: '2026-09-12T10:00:00Z', action: 'UPDATE', recordId: 5, author, about: null, changes,
    record: { date: '2026-09-12', category: 'Groceries', amount: '14.00', deleted: false, type: 'EXPENSE', currency: 'USD' },
    currency: 'USD', ...extra,
  })

  it('reads each side of a change of currency in its own currency, and a later row in the new one', () => {
    const toDollars = row([
      { field: 'amount', member: null, old: '12.00', new: '13.00', oldCurrency: 'EUR', newCurrency: 'USD' },
      { field: 'currency', member: null, old: 'EUR', new: 'USD' },
    ])
    expect(journalLine(toDollars, 'EUR').text).toContain('€12.00 → $13.00')
    // The next row's amounts are in dollars, though the main currency is euros and no row names the currency.
    const later = row([{ field: 'amount', member: null, old: '13.00', new: '14.00', oldCurrency: 'USD', newCurrency: 'USD' }])
    expect(journalLine(later, 'EUR').text).toBe('Anna changed the amount of Groceries, Sep 12, 2026: $13.00 → $14.00.')
    // The first row of that record, written when it was in euros, still reads in euros: its row says so.
    const first = row([{ field: 'amount', member: null, old: '9.00', new: '10.00', oldCurrency: 'EUR', newCurrency: 'EUR' }],
      { currency: 'EUR' })
    expect(journalLine(first, 'EUR').text).toBe('Anna changed the amount of Groceries, Sep 12, 2026: €9.00 → €10.00.')
  })

  it('reads a creation’s shares in the row’s currency, and a refund with its minus', () => {
    const created = row([
      { field: 'amount', member: null, old: null, new: '10.01', newCurrency: 'EUR' },
      { field: 'refund', member: null, old: null, new: 'true' },
      { field: 'payer', member: null, old: null, new: 'Anna' },
      { field: 'splitMethod', member: null, old: null, new: 'EQUAL' },
      { field: 'share', member: ref(anna), old: null, new: '5.01', newCurrency: 'EUR' },
      { field: 'share', member: ref(sam), old: null, new: '5.00', newCurrency: 'EUR' },
    ], { action: 'CREATE', currency: 'EUR',
      record: { date: '2026-09-12', category: 'Groceries', amount: '10.01', deleted: false, type: 'EXPENSE', currency: 'EUR', refund: true } })
    const line = journalLine(created, 'EUR')
    expect(line.text).toBe('Anna added the refund Groceries, Sep 12, 2026: -€10.01, received by Anna.')
    expect(line.details[0]).toBe('Split: equal shares: Anna -€5.01, Sam -€5.00')
    const deleted = row([], { action: 'DELETE', currency: 'EUR', record: { ...created.record!, deleted: true } })
    expect(journalLine(deleted, 'EUR').text).toBe('Anna deleted the refund Groceries, Sep 12, 2026, -€10.01.')
  })

  it('works for a row of an api before the currency was stored', () => {
    const old = row([{ field: 'amount', member: null, old: '9.00', new: '10.00' }], { currency: undefined,
      record: { date: '2026-09-12', category: 'Groceries', amount: '10.00', deleted: false, type: 'EXPENSE', currency: 'EUR' } })
    expect(journalLine(old, 'EUR').text).toBe('Anna changed the amount of Groceries, Sep 12, 2026: €9.00 → €10.00.')
  })
})
