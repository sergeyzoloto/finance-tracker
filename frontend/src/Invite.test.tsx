import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { CreatedInvite, FamilyInvite, FamilyLedger, FamilyMember, InviteLookup, Me } from './api'
import type { FamilyData } from './familyData'
import { FamilyMembers } from './FamilyMembers'
import { pendingInvite, savePendingInvite } from './invite'
import { WithMe } from './testMe'

// Invites in the interface (F5; D-17, D-18, D-11): the invite page and its outcomes, and the owners' side.

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

const ON: Me = { name: 'Carol', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }
const TOKEN = 'hdzuQFKxP6EGQ-9rAQCHxEOs0iXpnIkxdLhB9WhL0h0'
const INVALID = { status: 404, body: { status: 404, detail: 'This invite is not valid. Ask for a new one.' } }
const home: FamilyLedger = { id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'MEMBER', memberId: 71, createdAt: '2026-09-29T10:00:00Z', startDate: '2026-09-01' }
const claim: InviteLookup = {
  ledgerName: 'Home', baseCurrency: 'EUR', invitedBy: 'Mum', kind: 'CLAIM', seatName: 'Sam', joinDate: '2026-09-15',
  expiresAt: '2026-10-04T10:00:00Z',
  categories: [{ code: 'GROCERIES', name: 'Groceries', type: 'EXPENSE' }, { code: 'GIFTS', name: 'Presents', type: 'INCOME' }],
  merges: [{ categoryId: 11, code: 'GROCERIES', name: 'Food', familyName: 'Groceries', type: 'EXPENSE' }],
  keptPrivate: [{ categoryId: 12, code: 'GIFTS', name: 'Gifts', type: 'EXPENSE', familyType: 'INCOME' }],
  mayBring: [{ categoryId: 13, code: 'HEALTH', name: 'Health', type: 'EXPENSE' },
    { categoryId: 14, code: 'SALARY', name: 'Salary', type: 'INCOME' }],
  displayName: 'Carol Smith',
}

function renderApp(me: Me, path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App me={me} />
      <Routes><Route path="*" element={<Where />} /></Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.stubGlobal('confirm', () => true)
  savePendingInvite(TOKEN)
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('the invite page', () => {
  it('shows who invites, to which budget and place, from when, what others see, and the categories', async () => {
    stubApi({ 'POST /api/invites/lookup': { status: 200, body: claim }, 'GET /api/family-ledgers': { status: 200, body: [] } })
    renderApp(ON, '/invite')

    expect(await screen.findByRole('heading', { name: 'Join the family budget “Home”' })).toBeDefined()
    const page = screen.getByRole('heading', { name: 'Join the family budget “Home”' }).closest('section')!
    expect(page.textContent).toContain('Mum invites you to the family budget “Home”, kept in EUR, to take the place of Sam.')
    expect(page.textContent).toContain('From Sep 15, 2026, your share of each family expense and income appears in your personal budget.')
    expect(page.textContent).toContain('arrives as one opening balance on that day')
    expect(within(page).getByRole('heading', { name: 'What the other members will see' })).toBeDefined()
    expect(within(page).getByText('Your accounts, and which one you paid with')).toBeDefined()
    expect(within(page).getByText('Your “Food” becomes “Groceries”')).toBeDefined()
    expect(within(page).getByText('“Gifts”')).toBeDefined()
    expect((screen.getByLabelText(/^Your name in this budget/) as HTMLInputElement).value).toBe('Carol Smith')
    expect(within(page).getByRole('checkbox', { name: 'Health' })).toBeDefined()
    expect(within(page).getByRole('checkbox', { name: 'Salary' })).toBeDefined()
  })

  it('accepts with the name and the categories brought, erases the token and opens the budget', async () => {
    const calls = stubApi({
      'POST /api/invites/lookup': { status: 200, body: claim },
      'POST /api/invites/accept': { status: 200, body: home },
      'GET /api/family-ledgers': [{ status: 200, body: [] }, { status: 200, body: [home] }],
      'GET /api/family-ledgers/7': { status: 200, body: home },
      'GET /api/family-ledgers/7/members': { status: 200, body: [] },
    })
    renderApp(ON, '/invite')
    fireEvent.change(await screen.findByLabelText(/^Your name in this budget/), { target: { value: ' Carol ' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Health' }))
    fireEvent.click(screen.getByRole('button', { name: 'Accept' }))

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/family/7'))
    expect(calls.find((c) => c.url === '/api/invites/accept')!.body)
      .toEqual({ token: TOKEN, displayName: 'Carol', categoryIds: [13] })
    expect(pendingInvite()).toBeNull()
    // The switcher's list is loaded again, now with the budget.
    await waitFor(() => expect(calls.filter((c) => c.url === '/api/family-ledgers')).toHaveLength(2))
    // The token went in request bodies only.
    expect(calls.filter((c) => c.url.includes(TOKEN))).toEqual([])
  })

  it('declines, erases the token and says so', async () => {
    const calls = stubApi({
      'POST /api/invites/lookup': { status: 200, body: { ...claim, kind: 'NEW_MEMBER', seatName: null } },
      'POST /api/invites/decline': { status: 204 },
    })
    renderApp(ON, '/invite')
    expect(await screen.findByText(/invites you to the family budget “Home”, kept in EUR, as a new member/)).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Decline' }))

    expect(await screen.findByRole('heading', { name: 'Invite declined' })).toBeDefined()
    expect(calls.find((c) => c.url === '/api/invites/decline')!.body).toEqual({ token: TOKEN })
    expect(pendingInvite()).toBeNull()
  })

  it.each<[string, Answer]>([
    ['invalid', INVALID],
    ['the user’s own budget already', { status: 409, body: { status: 409, detail: 'You are a member of this family budget already.' } }],
  ])('shows the server’s one message for a token that is %s, erases it, and offers Personal', async (_, answer) => {
    stubApi({ 'POST /api/invites/lookup': answer })
    renderApp(ON, '/invite')

    expect(await screen.findByRole('heading', { name: 'This invite can’t be used' })).toBeDefined()
    expect(screen.getByRole('alert').textContent).toBe((answer.body as { detail: string }).detail)
    expect(screen.getByRole('link', { name: 'Back to Personal' }).getAttribute('href')).toBe('/')
    expect(pendingInvite()).toBeNull()
  })

  it('says to try again later on a 429, keeps the token, and tries again', async () => {
    const calls = stubApi({ 'POST /api/invites/lookup': [
      { status: 429, body: { status: 429, detail: 'Too many attempts with invite links. Try again in a few minutes.' } },
      { status: 200, body: claim },
    ] })
    renderApp(ON, '/invite')

    expect(await screen.findByRole('heading', { name: 'Try again later' })).toBeDefined()
    expect(screen.getByText('Too many attempts with invite links. Try again in a few minutes.')).toBeDefined()
    expect(pendingInvite()).toBe(TOKEN)
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: 'Join the family budget “Home”' })).toBeDefined()
    expect(calls.filter((c) => c.url === '/api/invites/lookup')).toHaveLength(2)
  })

  it('shows a taken name at the field and keeps the token, but leaves when the place was taken meanwhile', async () => {
    const taken = { status: 409, body: { status: 409, detail: 'The family budget has a member named Mum already.' } }
    stubApi({
      'POST /api/invites/lookup': [{ status: 200, body: claim }, { status: 200, body: claim },
        { status: 409, body: { status: 409, detail: 'Someone has taken this place in the family budget already. Ask for a new invite.' } }],
      'POST /api/invites/accept': [taken, taken],
    })
    renderApp(ON, '/invite')
    fireEvent.change(await screen.findByLabelText(/^Your name in this budget/), { target: { value: 'Mum' } })
    fireEvent.click(screen.getByRole('button', { name: 'Accept' }))

    expect(await screen.findByText('The family budget has a member named Mum already.')).toBeDefined()
    expect(screen.getByLabelText(/^Your name in this budget/).getAttribute('aria-invalid')).toBe('true')
    expect(pendingInvite()).toBe(TOKEN)

    fireEvent.click(screen.getByRole('button', { name: 'Accept' }))
    expect(await screen.findByRole('heading', { name: 'This invite can’t be used' })).toBeDefined()
    expect(screen.getByRole('alert').textContent).toBe('Someone has taken this place in the family budget already. Ask for a new invite.')
    expect(pendingInvite()).toBeNull()
  })

  it('without a token asks for the link again', () => {
    localStorage.clear()
    const calls = stubApi({})
    renderApp(ON, '/invite')
    expect(screen.getByRole('heading', { name: 'No invite to open' })).toBeDefined()
    expect(calls.filter((c) => c.url.includes('invites'))).toEqual([])
  })

  it('doesn’t exist with the switch off: /invite ends on the dashboard and asks nothing', async () => {
    const calls = stubApi({})
    renderApp({ name: 'Carol', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: false } }, '/invite')
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/'))
    expect(calls.filter((c) => c.url.includes('invite'))).toEqual([])
  })
})

describe('the owners’ invites', () => {
  const anna: FamilyMember = { id: 70, displayName: 'Anna', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null }
  const sam: FamilyMember = { id: 72, displayName: 'Sam', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-20', hasAccount: false, share: null }
  const owned: FamilyLedger = { ...home, role: 'OWNER', memberId: 70 }
  const mum = { memberId: 70, displayName: 'Anna' }
  const invites: FamilyInvite[] = [
    { id: 3, kind: 'CLAIM', seat: { memberId: 72, displayName: 'Sam' }, joinDate: '2026-09-15', createdBy: mum, createdAt: '2026-10-01T09:00:00Z', expiresAt: '2026-10-04T09:00:00Z', status: 'PENDING' },
    { id: 2, kind: 'NEW_MEMBER', joinDate: '2026-09-30', createdBy: mum, createdAt: '2026-09-29T09:00:00Z', expiresAt: '2026-10-02T09:00:00Z', status: 'ACCEPTED', acceptedBy: { memberId: 73, displayName: 'Ben' }, acceptedAt: '2026-09-30T08:00:00Z' },
    { id: 1, kind: 'NEW_MEMBER', createdBy: mum, createdAt: '2026-09-28T09:00:00Z', expiresAt: '2026-10-01T09:00:00Z', status: 'DECLINED', declinedAt: '2026-09-28T10:00:00Z' },
  ]

  function familyData(ledger: FamilyLedger, members: FamilyMember[]): FamilyData {
    return { ledger, members, path: '/family-ledgers/7', page: '/family/7', owner: ledger.role === 'OWNER', reload: () => {} }
  }

  function renderMembers(family: FamilyData) {
    render(<WithMe><MemoryRouter><FamilyMembers family={family} /></MemoryRouter></WithMe>)
  }

  it('lists the invites with their status, and revokes a pending one', async () => {
    const calls = stubApi({
      'GET /api/family-ledgers/7/invites': [{ status: 200, body: invites }, { status: 200, body: [{ ...invites[0], status: 'REVOKED', revokedAt: '2026-10-01T10:00:00Z' }, ...invites.slice(1)] }],
      'DELETE /api/family-ledgers/7/invites/3': { status: 204 },
    })
    renderMembers(familyData(owned, [anna, sam]))

    const list = (await screen.findByText('To take Sam’s place')).closest('ul')!
    const items = within(list).getAllByRole('listitem')
    expect(items[0].textContent).toContain('from Sep 15, 2026')
    expect(items[0].textContent).toContain('pending until')
    expect(items[1].textContent).toContain('accepted by Ben')
    expect(items[2].textContent).toContain('declined on')
    expect(within(items[1]).queryByRole('button', { name: 'Revoke' })).toBeNull()
    fireEvent.click(within(items[0]).getByRole('button', { name: 'Revoke' }))

    await waitFor(() => expect(screen.getAllByText(/revoked on/)).toHaveLength(1))
    expect(calls.filter((c) => c.method === 'DELETE').map((c) => c.url)).toEqual(['/api/family-ledgers/7/invites/3'])
  })

  it('creates a link for someone new and one to take a place from a date, each shown once with a copy button', async () => {
    const created: CreatedInvite = { ...invites[0], id: 4, kind: 'NEW_MEMBER', seat: undefined, joinDate: undefined, link: 'https://app.finance-nl.com/invite#' + TOKEN }
    const write = vi.fn(async () => {})
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: write } })
    const calls = stubApi({
      'GET /api/family-ledgers/7/invites': { status: 200, body: [] },
      'POST /api/family-ledgers/7/invites': [{ status: 201, body: created },
        { status: 201, body: { ...created, id: 5, kind: 'CLAIM', seat: { memberId: 72, displayName: 'Sam' }, joinDate: '2026-09-15' } }],
    })
    renderMembers(familyData(owned, [anna, sam]))

    fireEvent.click(await screen.findByRole('button', { name: 'Invite someone new' }))
    const link = await screen.findByLabelText('Invite link') as HTMLInputElement
    expect(link.value).toBe(created.link)
    expect(screen.getByText(/It works once, for one person, until/)).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }))
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeDefined()
    expect(write).toHaveBeenCalledWith(created.link)

    const samsRow = screen.getByText('Sam').closest('tr')!
    fireEvent.click(within(samsRow).getByRole('button', { name: 'Invite to take this place' }))
    const date = screen.getByLabelText(/Invite someone to take Sam’s place, from/) as HTMLInputElement
    expect(date.min).toBe('2026-09-01')
    fireEvent.change(date, { target: { value: '2026-09-15' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create the link' }))

    expect(await screen.findByText('The link to take Sam’s place:')).toBeDefined()
    expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([
      { kind: 'NEW_MEMBER' }, { kind: 'CLAIM', seatMemberId: 72, joinDate: '2026-09-15' },
    ])
  })

  it('shows a member nothing of the invites, and asks for none', () => {
    const calls = stubApi({})
    renderMembers(familyData(home, [anna, sam]))
    expect(screen.queryByRole('heading', { name: 'Invites' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Invite to take this place' })).toBeNull()
    // The members page loads the balances for its confirmations (F6a), and nothing of the invites.
    expect(calls.filter((c) => c.url.includes('invites'))).toEqual([])
  })
})
