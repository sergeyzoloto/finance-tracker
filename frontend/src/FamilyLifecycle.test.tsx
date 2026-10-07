import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { euroBalances } from './testLedger'
import type { FamilyBalances, FamilyLedger, FamilyMember, FamilyMemberships, InviteLookup, Me } from './api'
import type { FamilyData } from './familyData'
import { FamilyMembers } from './FamilyMembers'
import { savePendingInvite } from './invite'
import Settings from './Settings'
import { TEST_ME, WithMe } from './testMe'

// A membership's lifecycle in the interface (F6a; D-19, D-20, D-26, D-34): leaving and removal with their
// confirmations, new owners, members who left, a claim's opening balance and a return's correction on the invite page,
// a claim's join date at 23:30 UTC, and what "Delete all my data" lists.

type Answer = { status: number; body?: unknown }
type Call = { method: string; url: string; body: unknown }

/** Answers each request by "METHOD /api/path", or with 404; records every request with its JSON body. */
function stubApi(answers: Record<string, Answer | Answer[]>) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ method, url, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    const listed = answers[`${method} ${url}`]
    const answer = (Array.isArray(listed) ? listed.shift() : listed)
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

const home: FamilyLedger = { id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70, createdAt: '2026-09-29T10:00:00Z', startDate: '2026-09-01' }
const anna: FamilyMember = { id: 70, displayName: 'Anna', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null, leftDate: null }
const ben: FamilyMember = { id: 72, displayName: 'Ben', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null, leftDate: null }
const kid: FamilyMember = { id: 71, displayName: 'Kid', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: false, share: null, leftDate: null }
const balances: FamilyBalances = euroBalances([
  { memberId: 70, displayName: 'Anna', status: 'ACTIVE', hasAccount: true, balance: '-40.00', you: true },
  { memberId: 72, displayName: 'Ben', status: 'ACTIVE', hasAccount: true, balance: '-10.00', you: false },
  { memberId: 71, displayName: 'Kid', status: 'ACTIVE', hasAccount: false, balance: '50.00', you: false },
])
const BALANCES = { 'GET /api/family-ledgers/7/balances': { status: 200, body: balances } }

function familyData(ledger: FamilyLedger, members: FamilyMember[]): FamilyData {
  return {
    ledger, members, path: `/family-ledgers/${ledger.id}`, page: `/family/${ledger.id}`, owner: ledger.role === 'OWNER',
    reload: vi.fn(), left: vi.fn(),
  }
}

function renderMembers(family: FamilyData, me?: Me) {
  render(<WithMe me={me}><MemoryRouter initialEntries={['/family/7/members']}><Routes>
    <Route path="/family/7/members" element={<FamilyMembers family={family} />} />
  </Routes></MemoryRouter></WithMe>)
}

beforeEach(() => vi.stubGlobal('confirm', () => true))
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  localStorage.clear()
})

describe('the members page', () => {
  it('lets an owner remove any other member, after saying what happens', async () => {
    const calls = stubApi({ ...BALANCES, 'DELETE /api/family-ledgers/7/members/72': { status: 204 } })
    const family = familyData(home, [anna, ben, kid])
    renderMembers(family)

    const bensRow = screen.getByText('Ben').closest('tr')!
    fireEvent.click(within(bensRow).getByRole('button', { name: 'Remove' }))
    const confirmation = screen.getByRole('region', { name: 'Remove Ben' })
    expect(within(confirmation).getByRole('heading', { name: 'Remove “Ben” from the family budget?' })).toBeDefined()
    expect(await within(confirmation).findByText('Ben is owed €10.00. That stays in their personal budget, on an '
      + 'account of theirs.')).toBeDefined()
    expect(within(confirmation).getByText(/Ben won’t see this family budget any more/)).toBeDefined()
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Remove Ben' }))
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Remove Ben' })).toBeNull())
    expect(family.reload).toHaveBeenCalled()
    expect(family.left).not.toHaveBeenCalled()
    expect(calls.filter((c) => c.method !== 'GET').map((c) => [c.method, c.url])).toEqual([
      ['DELETE', '/api/family-ledgers/7/members/72']])

    // Kid has no account: the confirmation says what that means; Cancel sends nothing.
    fireEvent.click(within(screen.getByText('Kid').closest('tr')!).getByRole('button', { name: 'Remove' }))
    expect(screen.getByText(/If a record names Kid, they stay in it as a member who left/)).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('region', { name: 'Remove Kid' })).toBeNull()
    expect(calls.filter((c) => c.method !== 'GET')).toHaveLength(1)
  })

  it('lets an owner make a member with an account an owner', async () => {
    const calls = stubApi({ ...BALANCES, 'POST /api/family-ledgers/7/members/72/owner': { status: 200,
      body: { ...ben, role: 'OWNER' } } })
    const family = familyData(home, [anna, ben, kid])
    renderMembers(family)

    // The table follows the members' answer, which may be late.
    expect(within((await screen.findByText('Kid')).closest('tr')!).queryByRole('button', { name: 'Make owner' })).toBeNull()
    expect(within(screen.getByText('You').closest('tr')!).queryByRole('button', { name: 'Make owner' })).toBeNull()
    fireEvent.click(within(screen.getByText('Ben').closest('tr')!).getByRole('button', { name: 'Make owner' }))
    // The request is answered, then the budget is loaded again; either may be late.
    await waitFor(() => expect(calls.filter((c) => c.method !== 'GET').map((c) => [c.method, c.url])).toEqual([
      ['POST', '/api/family-ledgers/7/members/72/owner']]))
    await waitFor(() => expect(family.reload).toHaveBeenCalled())
  })

  it('tells the last owner to make another owner first, and shows the server’s LAST_OWNER too', async () => {
    stubApi({ ...BALANCES, 'DELETE /api/family-ledgers/7/members/me': { status: 409, body: { status: 409,
      code: 'LAST_OWNER', detail: 'You are the family budget’s last owner: make another member with an account an owner first.' } } })
    renderMembers(familyData(home, [anna, ben, kid]))

    fireEvent.click(within(screen.getByText('You').closest('tr')!).getByRole('button', { name: 'Leave' }))
    const confirmation = screen.getByRole('region', { name: 'Leave the family budget' })
    expect(within(confirmation).getByText(/You are the family budget’s last owner/)).toBeDefined()
    expect(within(confirmation).queryByRole('button', { name: 'Leave the family budget' })).toBeNull()
    cleanup()

    // With another owner on the page, which the server sees otherwise, its 409 shows.
    renderMembers(familyData(home, [anna, { ...ben, role: 'OWNER' }, kid]))
    fireEvent.click(within(screen.getByText('You').closest('tr')!).getByRole('button', { name: 'Leave' }))
    fireEvent.click(screen.getByRole('button', { name: 'Leave the family budget' }))
    expect(await screen.findByText('You are the family budget’s last owner: make another member with an account an '
      + 'owner first.')).toBeDefined()
  })

  it('lets a member leave after saying what stays, and then takes them to their personal budget', async () => {
    const calls = stubApi({ 'GET /api/family-ledgers/8/balances': { status: 200, body: euroBalances(
      balances.byCurrency[0].members.map((b) => ({ ...b, you: b.memberId === 72 }))) },
    'DELETE /api/family-ledgers/8/members/me': { status: 204 } })
    const family = familyData({ ...home, id: 8, role: 'MEMBER', memberId: 72, splitRule: 'CUSTOM' },
      [anna, { ...ben, share: 3000 }, { ...kid, share: 0 }])
    family.path = '/family-ledgers/8'
    renderMembers(family)

    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull()
    fireEvent.click(within(screen.getByText('You').closest('tr')!).getByRole('button', { name: 'Leave' }))
    const confirmation = screen.getByRole('region', { name: 'Leave the family budget' })
    expect(within(confirmation).getByRole('heading', { name: 'Leave “Home”?' })).toBeDefined()
    expect(await within(confirmation).findByText(/You are owed €10.00. That stays in your personal budget, on “Debt to family budget: Home”/))
      .toBeDefined()
    expect(within(confirmation).getByText(/stays there as your own entries/)).toBeDefined()
    expect(within(confirmation).getByText('The split rule goes back to equal shares.')).toBeDefined()
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Leave the family budget' }))
    await waitFor(() => expect(family.left).toHaveBeenCalled())
    expect(calls.filter((c) => c.method !== 'GET').map((c) => [c.method, c.url])).toEqual([
      ['DELETE', '/api/family-ledgers/8/members/me']])
  })

  it('shows when a member left, and offers nothing for them', () => {
    stubApi(BALANCES)
    renderMembers(familyData(home, [anna, { ...ben, status: 'LEFT', leftDate: '2026-09-30' }, kid]))
    const bensRow = screen.getByText('Ben').closest('tr')!
    expect(within(bensRow).getByText(/Left on .*2026/)).toBeDefined()
    expect(within(bensRow).queryAllByRole('button')).toEqual([])
  })
})

describe('the invite page (D-34, D-26)', () => {
  const ON: Me = { name: 'Carol', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }
  const claim: InviteLookup = {
    ledgerName: 'Home', baseCurrency: 'EUR', invitedBy: 'Mum', kind: 'CLAIM', seatName: 'Sam', joinDate: '2026-09-15',
    expiresAt: '2026-10-04T10:00:00Z', categories: [], merges: [], keptPrivate: [], mayBring: [],
    displayName: 'Carol', openingBalances: [{ currency: 'EUR', amount: '-10.00' }], returning: false,
  }

  function renderInvite(lookup: InviteLookup) {
    savePendingInvite('hdzuQFKxP6EGQ-9rAQCHxEOs0iXpnIkxdLhB9WhL0h0')
    stubApi({ 'POST /api/invites/lookup': { status: 200, body: lookup }, 'GET /api/family-ledgers': { status: 200, body: [] } })
    render(<MemoryRouter initialEntries={['/invite']}><App me={ON} /><Routes><Route path="*" element={<Where />} /></Routes></MemoryRouter>)
  }

  it('shows a claim’s opening balance, which the user takes on', async () => {
    renderInvite(claim)
    const opening = await screen.findByTestId('opening-balance')
    expect(opening.textContent).toMatch(/Before .*2026, Sam is owed €10.00\. That becomes your opening balance/)
    expect(screen.queryByTestId('correction')).toBeNull()
  })

  it('shows a returning member the correction beforehand', async () => {
    renderInvite({ ...claim, kind: 'NEW_MEMBER', seatName: null, joinDate: '2026-10-01', openingBalances: undefined,
      returning: true, corrections: [{ currency: 'EUR', amount: '-60.00' }, { currency: 'USD', amount: '20.00' }] })
    expect((await screen.findByTestId('correction')).textContent).toMatch(
      /One correction on that day of €60.00, which takes from .* and of \$20.00, which adds to what your personal budget shows you owe/)
    expect(screen.getByText(/where you were a member before: you come back in your earlier place/)).toBeDefined()
    expect(screen.queryByTestId('opening-balance')).toBeNull()
  })
})

describe('a claim’s join date at 23:30 UTC (F6a, D-101)', () => {
  it('leaves the owner’s today out, as /api/me says it, whatever the browser’s clock says, and sends an earlier one', async () => {
    // The browser is in Los Angeles, on the 15th; the owner's zone is Amsterdam, where it is the 16th: /api/me's today.
    const zone = process.env.TZ
    process.env.TZ = 'America/Los_Angeles'
    try {
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(new Date('2026-07-15T23:30:00Z'))
      const created = { status: 201, body: { id: 1, kind: 'CLAIM', link: 'https://app.finance-nl.com/invite#x' } }
      const calls = stubApi({ ...BALANCES, 'GET /api/family-ledgers/7/invites': { status: 200, body: [] },
        'POST /api/family-ledgers/7/invites': [created, created] })
      renderMembers(familyData({ ...home, startDate: '2026-07-01' }, [anna, ben, kid]),
        { ...TEST_ME, timeZone: 'Europe/Amsterdam', today: '2026-07-16' })

      fireEvent.click(within(screen.getByText('Kid').closest('tr')!).getByRole('button', { name: 'Invite to take this place' }))
      const date = screen.getByLabelText(/Invite someone to take Kid’s place, from/) as HTMLInputElement
      expect(date.value).toBe('2026-07-16')
      fireEvent.click(screen.getByRole('button', { name: 'Create the link' }))
      await waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1))

      fireEvent.click(within(screen.getByText('Kid').closest('tr')!).getByRole('button', { name: 'Invite to take this place' }))
      fireEvent.change(screen.getByLabelText(/Invite someone to take Kid’s place, from/), { target: { value: '2026-07-15' } })
      fireEvent.click(screen.getByRole('button', { name: 'Create the link' }))
      await waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toHaveLength(2))
      expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([
        { kind: 'CLAIM', seatMemberId: 71 }, { kind: 'CLAIM', seatMemberId: 71, joinDate: '2026-07-15' }])
    } finally {
      process.env.TZ = zone
    }
  })
})

describe('Delete all my data', () => {
  const memberships: FamilyMemberships = { left: 1, memberships: [
    { ledgerId: 8, name: 'Allotment', role: 'OWNER', baseCurrency: 'EUR', balances: [{ currency: 'EUR', amount: '0.00' }], outcome: 'DELETED',
      newOwner: null, pendingInvites: 0, splitRuleReset: false },
    { ledgerId: 7, name: 'Home', role: 'OWNER', baseCurrency: 'EUR', balances: [{ currency: 'EUR', amount: '-40.00' }, { currency: 'USD', amount: '12.00' }], outcome: 'OWNERSHIP_PASSES',
      newOwner: 'Ben', pendingInvites: 1, splitRuleReset: false },
  ] }

  function renderSettings(familyOn: boolean) {
    render(<WithMe><MemoryRouter><Settings familyOn={familyOn} /></MemoryRouter></WithMe>)
  }

  it('lists every family budget the user is in, and what happens to it, before they confirm', async () => {
    const calls = stubApi({ 'GET /api/me/family-memberships': { status: 200, body: memberships } })
    renderSettings(true)

    const home = (await screen.findByText('Home')).closest('li')!
    expect(within(home).getByText('(Owner)')).toBeDefined()
    expect(within(home).getByText('You are owed €40.00 and owe $12.00.')).toBeDefined()
    expect(within(home).getByText('Ben becomes its owner.')).toBeDefined()
    expect(within(home).getByText('Your invite that wasn’t used yet stops working.')).toBeDefined()
    const allotment = screen.getByText('Allotment').closest('li')!
    expect(within(allotment).getByText('Nobody else in it has an account, so it is deleted with its records.')).toBeDefined()
    expect(screen.getByText(/In the family budget you left, your name becomes “Former member” too/)).toBeDefined()
    expect(calls.map((c) => c.url)).toEqual(['/api/me/family-memberships'])
  })

  it('is as before with the family budget switched off, and asks for nothing', () => {
    const calls = stubApi({})
    renderSettings(false)
    expect(screen.getByRole('heading', { name: 'Delete all my data' })).toBeDefined()
    expect(screen.queryByText(/family budget/)).toBeNull()
    expect(calls).toEqual([])
  })
})
