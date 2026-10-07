import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Category, FamilyLedger, FamilyMember, FamilyRecord, InviteLookup, Me } from './api'
import { inOpeningBalance, sharers, takesPart } from './expenseForm'
import { savePendingInvite } from './invite'
import { euroBalances, testLedger } from './testLedger'

// F6b in the interface: a claimed seat in the record forms before its claim's date (D-35), the leave confirmation of
// the last member with an account (D-36), a return's own entries on the invite page (D-37), and the switcher right
// after accepting an invite or leaving.

type Answer = { status: number; body?: unknown } | 'pending'
type Call = { method: string; url: string; body: unknown }

/**
 * Answers each request by "METHOD /api/path", or with 404; a list answers in turn, its last one from then on, and
 * 'pending' never answers. Records every request with its JSON body.
 */
function stubApi(answers: Record<string, Answer | Answer[]>) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ method, url, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    const listed = answers[`${method} ${url}`]
    const answer = (Array.isArray(listed) ? (listed.length > 1 ? listed.shift() : listed[0]) : listed)
      ?? { status: 404, body: { status: 404, detail: `No static resource ${url.slice(1)}.` } }
    if (answer === 'pending') return new Promise<Response>(() => {})
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

function renderApp(me: Me, path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App me={me} />
      <Routes><Route path="*" element={<Where />} /></Routes>
    </MemoryRouter>,
  )
}

const ON: Me = { name: 'Carol', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }
const home: FamilyLedger = { id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'MEMBER', memberId: 71, createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01' }
const anna: FamilyMember = { id: 70, displayName: 'Anna', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null, leftDate: null, claimedSeat: false }
// Carol took Kid's place from 09-20; Ben joined as a new member on 09-25.
const carol: FamilyMember = { id: 71, displayName: 'Carol', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-20', hasAccount: true, share: null, leftDate: null, claimedSeat: true }
const ben: FamilyMember = { id: 72, displayName: 'Ben', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-25', hasAccount: true, share: null, leftDate: null, claimedSeat: false }
const gran: FamilyMember = { id: 73, displayName: 'Gran', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-30', hasAccount: false, share: null, leftDate: null }
const categories: Category[] = [{ id: 30, code: 'GROCERIES', name: 'Groceries', type: 'EXPENSE', archived: false }]
const accounts: Account[] = testLedger.accounts
const ref = (m: FamilyMember) => ({ memberId: m.id, displayName: m.displayName })
const created: FamilyRecord = {
  id: 6, type: 'EXPENSE', date: '2026-09-15', category: { id: 30, code: 'GROCERIES', name: 'Groceries', archived: false },
  amount: '10.00', currency: 'EUR', comment: null, payer: ref(carol),
  splitMethod: 'EQUAL', shares: [], author: ref(carol), createdAt: '2026-09-30T10:00:00Z', updatedBy: ref(carol),
  updatedAt: '2026-09-30T10:00:00Z', version: 0, frozen: false, canEdit: true, canDelete: true, canEditPayment: true,
}
const EMPTY_PAGE = { status: 200, body: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } }

function budget(members: FamilyMember[], answers: Record<string, Answer | Answer[]> = {}, ledger = home) {
  return stubApi({
    'GET /api/family-ledgers': { status: 200, body: [ledger] },
    'GET /api/family-ledgers/7': { status: 200, body: ledger },
    'GET /api/family-ledgers/7/members': { status: 200, body: members },
    'GET /api/family-ledgers/7/categories': { status: 200, body: categories },
    'GET /api/accounts': { status: 200, body: accounts },
    ...answers,
  })
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
  localStorage.clear()
})

describe('who takes part on a date (D-18, D-35, D-39)', () => {
  it('is a seat without an account and a claimed seat from the start, others from their join date', () => {
    expect(takesPart(carol, '2026-09-02')).toBe(true)
    expect(takesPart(gran, '2026-09-02')).toBe(true)
    expect(takesPart(ben, '2026-09-24')).toBe(false)
    expect(takesPart(ben, '2026-09-25')).toBe(true)
    // A member of before F6b, whose answer has no claimedSeat, counts as not claimed.
    const { claimedSeat: _, ...older } = carol
    expect(takesPart(older, '2026-09-19')).toBe(false)
    expect(sharers([anna, carol, ben, gran], '2026-09-15').map((m) => m.displayName)).toEqual(['Anna', 'Carol', 'Gran'])
    expect(inOpeningBalance(carol, '2026-09-19')).toBe(true)
    expect(inOpeningBalance(carol, '2026-09-20')).toBe(false)
    expect(inOpeningBalance(ben, '2026-09-19')).toBe(false)
    expect(inOpeningBalance(undefined, '2026-09-19')).toBe(false)
  })
})

describe('a claimed seat in the record form (D-35)', () => {
  it('takes part before the claim’s date, and pays such a record without an account of theirs', async () => {
    const calls = budget([anna, carol, ben], { 'POST /api/family-ledgers/7/records': { status: 201, body: created } })
    renderApp(ON, '/family/7/expenses/new')
    fireEvent.change(await screen.findByLabelText('Category'), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText(/^Amount \(/), { target: { value: '10.00' } })
    await waitFor(() => expect(screen.getByLabelText(/^Paid from/)).toBeDefined())

    fireEvent.change(screen.getByLabelText(/^Date/), { target: { value: '2026-09-15' } })
    expect(screen.queryByLabelText(/^Paid from/)).toBeNull()
    expect(screen.getByText(/This expense is dated before you took your place on Sep 20, 2026, so it is part of your opening balance/)).toBeDefined()
    // Anna and Carol share it; Ben joined after its date.
    expect(screen.getByTestId('share-70').textContent).toContain('€5.00')
    expect(screen.getByTestId('share-71').textContent).toContain('€5.00')
    expect(screen.queryByTestId('share-72')).toBeNull()
    const save = screen.getByRole('button', { name: 'Add the expense' })
    expect(save).toHaveProperty('disabled', false)
    fireEvent.click(save)

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/family/7/expenses/6'))
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      type: 'EXPENSE', date: '2026-09-15', categoryId: 30, amount: '10.00', comment: null, payerMemberId: 71,
      split: { method: 'RULE' },
    })
  })

  it('asks for the account again from the claim’s date on', async () => {
    budget([anna, carol, ben])
    renderApp(ON, '/family/7/expenses/new')
    fireEvent.change(await screen.findByLabelText('Category'), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText(/^Amount \(/), { target: { value: '9.00' } })
    fireEvent.change(screen.getByLabelText(/^Date/), { target: { value: '2026-09-25' } })
    await waitFor(() => expect(screen.getByLabelText(/^Paid from/)).toBeDefined())
    expect(screen.getByRole('button', { name: 'Add the expense' })).toHaveProperty('disabled', true)
    expect(screen.getByTestId('share-72').textContent).toContain('€3.00')
  })
})

describe('the last member with an account leaving (D-36)', () => {
  it('says the budget and its records will be deleted, and leaves the switcher without it at once', async () => {
    const owner: FamilyLedger = { ...home, role: 'OWNER', memberId: 70 }
    const kid: FamilyMember = { ...gran, id: 71, displayName: 'Kid', joinDate: '2026-09-01' }
    const calls = budget([anna, kid], {
      // The switcher's list: Home, then never again, so that what it shows after leaving is the app's own.
      'GET /api/family-ledgers': [{ status: 200, body: [owner] }, 'pending'],
      'GET /api/family-ledgers/7/balances': { status: 200, body: euroBalances([
        { memberId: 70, displayName: 'Anna', status: 'ACTIVE', hasAccount: true, balance: '0.00', you: true },
        { memberId: 71, displayName: 'Kid', status: 'ACTIVE', hasAccount: false, balance: '0.00', you: false },
      ]) },
      'DELETE /api/family-ledgers/7/members/me': { status: 204 },
    }, owner)
    renderApp({ ...ON, name: 'Anna' }, '/family/7/members')

    fireEvent.click(within((await screen.findByText('You')).closest('tr')!).getByRole('button', { name: 'Leave' }))
    const confirmation = screen.getByRole('region', { name: 'Leave the family budget' })
    expect(within(confirmation).getByText('Nobody else here has an account, so the family budget “Home” and its '
      + 'records will be deleted: its members without an account, categories, journal and invites go with it.')).toBeDefined()
    expect(confirmation.textContent).not.toContain('The others keep seeing your name')
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Leave and delete the family budget' }))

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/'))
    const switcher = screen.getByLabelText('Budget')
    expect(within(switcher).getAllByRole('option').map((o) => o.textContent)).toEqual(['Personal', 'New family budget…'])
    expect(calls.filter((c) => c.method === 'DELETE').map((c) => c.url)).toEqual(['/api/family-ledgers/7/members/me'])
  })
})

describe('the members table at 375 px (F6b)', () => {
  it('labels each fact, so that a phone shows a member as one block', async () => {
    budget([anna, carol, ben, gran], { 'GET /api/family-ledgers/7/balances': { status: 200, body: euroBalances([]) } })
    renderApp(ON, '/family/7/members')
    const row = (await screen.findByText('Ben')).closest('tr')!
    expect(row.closest('table')!.className).toBe('members')
    expect([...row.querySelectorAll('td[data-label]')].map((td) => td.getAttribute('data-label')))
      .toEqual(['Role', 'Status', 'Joined'])
  })
})

const TOKEN = 'hdzuQFKxP6EGQ-9rAQCHxEOs0iXpnIkxdLhB9WhL0h0'
const back: InviteLookup = {
  ledgerName: 'Home', baseCurrency: 'EUR', invitedBy: 'Anna', kind: 'NEW_MEMBER', seatName: null, joinDate: '2026-09-30',
  expiresAt: '2026-10-03T10:00:00Z', categories: [], merges: [], keptPrivate: [], mayBring: [], displayName: 'Ben',
  returning: true, corrections: [],
  entriesAfterReturn: [{ entryId: 90, date: '2026-10-05', amount: '25.00', currency: 'EUR', memo: 'Lent to the family' }],
}

describe('a return with entries of its own after it (D-37)', () => {
  it('lists them first, with links, and waits for them to go before accepting', async () => {
    savePendingInvite(TOKEN)
    const refused = { status: 409, body: { status: 409, code: 'ENTRIES_AFTER_RETURN', detail: 'Your former debt to this '
      + 'family budget has 1 entry dated after today that belong to none of its records. Move it to another account or '
      + 'delete it, then accept again.' } }
    const calls = stubApi({
      'GET /api/family-ledgers': { status: 200, body: [] },
      'POST /api/invites/lookup': [{ status: 200, body: back }, { status: 200, body: { ...back, entriesAfterReturn: [] } },
        { status: 200, body: back }],
      'POST /api/invites/accept': refused,
    })
    renderApp({ ...ON, name: 'Ben' }, '/invite')

    const list = await screen.findByRole('region', { name: 'Entries to move first' })
    expect(list.textContent).toContain('adds €25.00 to what you owe · Lent to the family')
    expect(within(list).getByRole('link', { name: 'Oct 5, 2026' }).getAttribute('href')).toBe('/entries/90')
    expect(screen.getByRole('button', { name: 'Accept' })).toHaveProperty('disabled', true)

    fireEvent.click(within(list).getByRole('button', { name: 'Check again' }))
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Entries to move first' })).toBeNull())
    const accept = screen.getByRole('button', { name: 'Accept' })
    expect(accept).toHaveProperty('disabled', false)

    // Another one came meanwhile: the 409 says so, and the list is back.
    fireEvent.click(accept)
    expect(await screen.findByText(/has 1 entry dated after today/)).toBeDefined()
    expect(await screen.findByRole('region', { name: 'Entries to move first' })).toBeDefined()
    expect(screen.getByRole('button', { name: 'Accept' })).toHaveProperty('disabled', true)
    expect(screen.getByLabelText(/^Your name in this budget/).getAttribute('aria-invalid')).toBe('false')
    expect(calls.filter((c) => c.url === '/api/invites/lookup')).toHaveLength(3)
  })
})

describe('the switcher right after accepting an invite (F6b)', () => {
  it('lists the family budget at once, with no placeholder', async () => {
    savePendingInvite(TOKEN)
    const joined: FamilyLedger = { ...home, memberId: 72 }
    const calls = stubApi({
      'GET /api/family-ledgers': [{ status: 200, body: [] }, 'pending'],
      'POST /api/invites/lookup': { status: 200, body: { ...back, returning: false, correction: null, entriesAfterReturn: null } },
      'POST /api/invites/accept': { status: 200, body: joined },
      'GET /api/family-ledgers/7': { status: 200, body: joined },
      'GET /api/family-ledgers/7/members': { status: 200, body: [anna, { ...ben, joinDate: '2026-09-30' }] },
      'GET /api/family-ledgers/7/records?page=0&size=5': EMPTY_PAGE,
    })
    renderApp({ ...ON, name: 'Ben' }, '/invite')
    // The page's own list has been answered (a user sees the switcher before they accept); a late answer would
    // otherwise replace what accepting added.
    await waitFor(() => expect(calls.some((c) => c.url === '/api/family-ledgers')).toBe(true))
    await screen.findByLabelText('Budget')
    fireEvent.click(await screen.findByRole('button', { name: 'Accept' }))

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/family/7'))
    const switcher = screen.getByLabelText('Budget') as HTMLSelectElement
    expect(switcher.value).toBe('7')
    expect(within(switcher).getAllByRole('option').map((o) => o.textContent))
      .toEqual(['Personal', 'Home', 'New family budget…'])
  })
})
