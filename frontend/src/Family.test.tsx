import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Category, FamilyLedger, FamilyMember, Me } from './api'
import FamilyCategories from './FamilyCategories'
import type { FamilyData } from './familyData'
import { FamilyMembers, FamilySplitRule } from './FamilyMembers'
import NewFamily from './NewFamily'
import { euroBalances } from './testLedger'
import { WithMe } from './testMe'

// The family budget's screens (F3b): hidden while the switch is off, the switcher, creation, and the family pages.

type Answer = { status: number; body?: unknown }
type Call = { method: string; url: string; body: unknown }

/** Answers each request by "METHOD /api/path", or with 404; records every request with its JSON body. */
function stubApi(answers: Record<string, Answer | Answer[]>) {
  const calls: Call[] = []
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ method, url, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    const listed = answers[`${method} ${url}`]
    const answer = (Array.isArray(listed) ? listed.shift() : listed)
      ?? { status: 404, body: { status: 404, detail: `No static resource ${url.slice(1)}.` } }
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
      status: answer.status,
      headers: { 'Content-Type': answer.status >= 400 ? 'application/problem+json' : 'application/json' },
    })
  })
  vi.stubGlobal('fetch', fetch)
  return calls
}

/** The current location, with its router state, for the assertions. */
function Where() {
  const location = useLocation()
  return <p data-testid="where">{location.pathname}{location.state ? ` ${JSON.stringify(location.state)}` : ''}</p>
}

const ON: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }
const home: FamilyLedger = { id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70, createdAt: '2026-09-29T10:00:00Z', startDate: '2026-09-01' }
const allotment: FamilyLedger = { ...home, id: 8, name: 'Allotment', role: 'MEMBER', memberId: 80 }
const anna: FamilyMember = { id: 70, displayName: 'Anna', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-29', hasAccount: true, share: null }
const ben: FamilyMember = { id: 72, displayName: 'Ben', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-29', hasAccount: true, share: null }
const kid: FamilyMember = { id: 71, displayName: 'Kid', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-29', hasAccount: false, share: null }

function renderApp(me: Me, path: string | { pathname: string; state: unknown }) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App me={me} />
      <Routes><Route path="*" element={<Where />} /></Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => vi.stubGlobal('confirm', () => true))
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('with the switch off', () => {
  it.each<[string, Me]>([
    ['false', { name: 'Anna', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: false } }],
    ['missing', { name: 'Anna', timeZone: 'UTC', today: '2026-09-30' }],
    ['without features', { name: 'Anna', timeZone: 'UTC', today: '2026-09-30', features: {} }],
  ])('shows no switcher and asks for no family budgets when the field is %s', (_, me) => {
    const calls = stubApi({})
    renderApp(me, '/settings')

    expect(screen.getByRole('heading', { name: 'Settings' })).toBeDefined()
    expect(screen.queryByRole('combobox', { name: 'Budget' })).toBeNull()
    expect(screen.queryByText(/family budget/i)).toBeNull()
    expect(calls.filter((c) => c.url.includes('family'))).toEqual([])
  })

  it.each(['/family/new', '/family/7', '/family/7/members'])('treats %s as any unknown path', async (path) => {
    const calls = stubApi({})
    renderApp({ name: 'Anna', timeZone: 'UTC', today: '2026-09-30' }, path)

    // The app's answer to an unknown path: the dashboard.
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/'))
    expect(screen.queryByText(/family budget/i)).toBeNull()
    expect(calls.filter((c) => c.url.includes('family'))).toEqual([])
  })
})

describe('the switcher', () => {
  it('lists Personal, the family budgets by name, and a new one, and shows the current choice', async () => {
    stubApi({ 'GET /api/family-ledgers': { status: 200, body: [allotment, home] } })
    renderApp(ON, '/settings')

    const switcher = await screen.findByRole('combobox', { name: 'Budget' })
    await waitFor(() => expect(within(switcher).getAllByRole('option').map((o) => o.textContent))
      .toEqual(['Personal', 'Allotment', 'Home', 'New family budget…']))
    expect(switcher).toHaveProperty('value', 'personal')

    fireEvent.change(switcher, { target: { value: '7' } })
    expect(screen.getByTestId('where').textContent).toBe('/family/7')
    expect(switcher).toHaveProperty('value', '7')

    fireEvent.change(switcher, { target: { value: 'personal' } })
    expect(screen.getByTestId('where').textContent).toBe('/')

    fireEvent.change(switcher, { target: { value: 'new' } })
    expect(screen.getByTestId('where').textContent).toBe('/family/new')
    expect(switcher).toHaveProperty('value', 'new')
  })

  it('loads the list again after all the user’s data is deleted', async () => {
    const calls = stubApi({
      'GET /api/family-ledgers': [{ status: 200, body: [home] }, { status: 200, body: [] }],
      'DELETE /api/me/data': { status: 204 },
    })
    renderApp(ON, '/settings')
    const switcher = await screen.findByRole('combobox', { name: 'Budget' })
    await waitFor(() => expect(within(switcher).getAllByRole('option')).toHaveLength(3))

    fireEvent.change(screen.getByLabelText(/to confirm/), { target: { value: 'DELETE' } })
    fireEvent.click(screen.getByRole('button', { name: 'Delete all my data' }))

    await waitFor(() => expect(within(switcher).getAllByRole('option').map((o) => o.textContent))
      .toEqual(['Personal', 'New family budget…']))
    expect(calls.filter((c) => c.url === '/api/family-ledgers')).toHaveLength(2)
  })

  it('shows the not-found page for a budget the user is not in, and loads the list again', async () => {
    const calls = stubApi({
      'GET /api/family-ledgers': { status: 200, body: [home] },
      'GET /api/family-ledgers/9': { status: 404, body: { status: 404, detail: 'Ledger 9 not found.' } },
      'GET /api/family-ledgers/9/members': { status: 404, body: { status: 404, detail: 'Ledger 9 not found.' } },
    })
    renderApp(ON, '/family/9')

    expect(await screen.findByRole('heading', { name: 'Family budget not found' })).toBeDefined()
    expect(screen.getByRole('link', { name: 'Back to Personal' }).getAttribute('href')).toBe('/')
    await waitFor(() => expect(calls.filter((c) => c.url === '/api/family-ledgers')).toHaveLength(2))
  })
})

describe('creating a family budget', () => {
  const categories: Category[] = [
    { id: 11, code: 'GROCERIES', name: 'Groceries', type: 'EXPENSE', archived: false },
    { id: 12, code: 'RENT', name: 'Rent', type: 'EXPENSE', archived: false },
    { id: 13, code: 'SALARY', name: 'Salary', type: 'INCOME', archived: false },
    { id: 14, code: 'OLD', name: 'Old', type: 'EXPENSE', archived: true },
  ]
  const reference = {
    'GET /api/settings': { status: 200, body: { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' } },
    'GET /api/accounts': { status: 200, body: [] },
    'GET /api/categories': { status: 200, body: categories },
    'POST /api/family-ledgers': { status: 201, body: home },
  }

  function renderNewFamily() {
    const onCreated = vi.fn()
    render(
      <WithMe><MemoryRouter initialEntries={['/family/new']}>
        <Routes>
          <Route path="/family/new" element={<NewFamily me={ON} onCreated={onCreated} />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter></WithMe>,
    )
    return onCreated
  }

  async function fillIn() {
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: ' Home ' } })
    expect(screen.getByLabelText(/^Your name in this budget/)).toHaveProperty('value', 'Anna')
    expect(screen.getByLabelText(/^Base currency/)).toHaveProperty('value', 'EUR')
    expect(screen.getAllByRole('checkbox').filter((c) => (c as HTMLInputElement).checked)).toHaveLength(0)
    expect(screen.queryByLabelText('Old')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Select all expenses' }))
    fireEvent.change(screen.getByLabelText(/^Name of a member/), { target: { value: 'Kid' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add member' }))
    fireEvent.click(screen.getByLabelText('Custom percentages'))
    fireEvent.change(screen.getByLabelText('Share of Anna in percent'), { target: { value: '60' } })
    fireEvent.change(screen.getByLabelText('Share of Kid in percent'), { target: { value: '40' } })
  }

  it('creates the budget, then adds the members, then sets the custom split rule', async () => {
    const calls = stubApi({
      ...reference,
      'POST /api/family-ledgers/7/members': { status: 201, body: kid },
      'PUT /api/family-ledgers/7/split-rule': { status: 200, body: [] },
    })
    const onCreated = renderNewFamily()
    await fillIn()
    fireEvent.click(screen.getByRole('button', { name: 'Create family budget' }))

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/family/7'))
    expect(calls.filter((c) => c.method !== 'GET')).toEqual([
      { method: 'POST', url: '/api/family-ledgers', body: { name: 'Home', baseCurrency: 'EUR', displayName: 'Anna', categoryIds: [11, 12] } },
      { method: 'POST', url: '/api/family-ledgers/7/members', body: { displayName: 'Kid' } },
      { method: 'PUT', url: '/api/family-ledgers/7/split-rule', body: { rule: 'CUSTOM', shares: [{ memberId: 70, share: 6000 }, { memberId: 71, share: 4000 }] } },
    ])
    expect(onCreated).toHaveBeenCalledTimes(1)
  })

  it('opens the new budget and says what failed when a later call fails', async () => {
    const calls = stubApi({
      ...reference,
      'POST /api/family-ledgers/7/members': { status: 409, body: { status: 409, detail: 'The family budget has a member named Kid already.' } },
    })
    const onCreated = renderNewFamily()
    await fillIn()
    fireEvent.click(screen.getByRole('button', { name: 'Create family budget' }))

    await waitFor(() => expect(screen.getByTestId('where').textContent).toMatch(/^\/family\/7 /))
    const state = JSON.parse(screen.getByTestId('where').textContent!.slice('/family/7 '.length))
    expect(state.creationProblems).toEqual([
      'Kid wasn’t added: The family budget has a member named Kid already.',
      'The split rule stayed equal, since not every member was added. Add them on the Members page, then set the '
        + 'percentages on the Split rule page.',
    ])
    expect(calls.filter((c) => c.method === 'PUT')).toEqual([])
    expect(onCreated).toHaveBeenCalledTimes(1)
  })

  it('shows why the budget itself was refused, and stays', async () => {
    stubApi({
      ...reference,
      'POST /api/family-ledgers': { status: 400, body: { status: 400, detail: 'Invalid request', errors: [{ field: 'baseCurrency', message: 'must be an ISO 4217 currency code' }] } },
    })
    renderNewFamily()
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Home' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create family budget' }))

    expect(await screen.findByText('Must be an ISO 4217 currency code.')).toBeDefined()
    expect(screen.queryByTestId('where')).toBeNull()
  })

  it('keeps Create disabled until custom shares add up to 100.00 %', async () => {
    stubApi(reference)
    renderNewFamily()
    await fillIn()
    const create = screen.getByRole('button', { name: 'Create family budget' })
    expect(create).toHaveProperty('disabled', false)

    fireEvent.change(screen.getByLabelText('Share of Kid in percent'), { target: { value: '39.99' } })
    expect(screen.getByTestId('share-total').textContent).toBe('99.99 %')
    expect(create).toHaveProperty('disabled', true)
  })
})

describe('a family budget’s page', () => {
  it('shows the budget with the user’s role, and what its creation could not finish', async () => {
    stubApi({
      'GET /api/family-ledgers': { status: 200, body: [home] },
      'GET /api/family-ledgers/7': { status: 200, body: home },
      'GET /api/family-ledgers/7/members': { status: 200, body: [anna] },
      // As the API answers a budget without records (D-45): its main currency, the reader at zero.
      'GET /api/family-ledgers/7/balances': { status: 200, body: euroBalances([
        { memberId: 70, displayName: 'Anna', status: 'ACTIVE', hasAccount: true, balance: '0.00', you: true },
      ]) },
      'GET /api/family-ledgers/7/records?size=5': { status: 200, body: { content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 } },
    })
    renderApp(ON, { pathname: '/family/7', state: { creationProblems: ['Kid wasn’t added: No.'] } })

    expect(await screen.findByRole('heading', { name: 'Home' })).toBeDefined()
    // The overview's own loads, answered and shown before anything else is checked, so that none outlives the test.
    expect(await screen.findByText('You are settled.')).toBeDefined()
    expect(await screen.findByText('Nothing recorded yet.')).toBeDefined()
    expect(screen.getByText('Family budget · EUR · Owner')).toBeDefined()
    expect(screen.getByRole('alert').textContent).toContain('The family budget was created, but not everything was set up:')
    expect(screen.getByRole('alert').textContent).toContain('Kid wasn’t added: No.')
    const tabs = within(screen.getByRole('navigation', { name: 'Family budget' })).getAllByRole('link')
    expect(tabs.map((t) => [t.textContent, t.getAttribute('href')])).toEqual([
      ['Overview', '/family/7'], ['Activity', '/family/7/expenses'], ['Balances', '/family/7/balances'],
      ['Journal', '/family/7/journal'], ['Report', '/family/7/report'], ['Members', '/family/7/members'], ['Split rule', '/family/7/split-rule'],
      ['Categories', '/family/7/categories'], ['Settings', '/family/7/settings'],
    ])
    expect(await screen.findByRole('option', { name: 'Home', selected: true })).toBeDefined()
  })

  it('links its pages by their full path from any of them', async () => {
    stubApi({
      'GET /api/family-ledgers': { status: 200, body: [home] },
      'GET /api/family-ledgers/7': { status: 200, body: home },
      'GET /api/family-ledgers/7/members': { status: 200, body: [anna] },
    })
    renderApp(ON, '/family/7/split-rule')

    const nav = within(await screen.findByRole('navigation', { name: 'Family budget' }))
    expect(nav.getAllByRole('link').map((t) => t.getAttribute('href'))).toEqual(['/family/7', '/family/7/expenses',
      '/family/7/balances', '/family/7/journal', '/family/7/report', '/family/7/members', '/family/7/split-rule', '/family/7/categories',
      '/family/7/settings'])
    expect(nav.getAllByRole('link').filter((t) => t.getAttribute('aria-current') === 'page').map((t) => t.textContent))
      .toEqual(['Split rule'])
    fireEvent.click(nav.getByRole('link', { name: 'Categories' }))
    expect(screen.getByTestId('where').textContent).toBe('/family/7/categories')
  })
})

/** A family budget as its pages get it, with the members given. */
function familyData(ledger: FamilyLedger, members: FamilyMember[]): FamilyData {
  return {
    ledger, members, path: `/family-ledgers/${ledger.id}`, page: `/family/${ledger.id}`, owner: ledger.role === 'OWNER',
    reload: vi.fn(),
  }
}

function renderPage(element: React.ReactNode) {
  render(<WithMe><MemoryRouter initialEntries={['/family/7/page']}><Routes><Route path="/family/7/page" element={element} /></Routes></MemoryRouter></WithMe>)
}

describe('the split rule', () => {
  it('keeps a live total and saves only at exactly 100.00 %', async () => {
    const calls = stubApi({ 'PUT /api/family-ledgers/7/split-rule': { status: 200, body: [] } })
    renderPage(<FamilySplitRule family={familyData(home, [anna, kid])} />)

    fireEvent.click(screen.getByLabelText('Custom percentages'))
    const total = screen.getByTestId('share-total')
    const save = screen.getByRole('button', { name: 'Save' })
    expect(total.textContent).toBe('100.00 %')
    expect(screen.getByLabelText('Share of Anna in percent')).toHaveProperty('value', '50.00')

    fireEvent.change(screen.getByLabelText('Share of Kid in percent'), { target: { value: '33.33' } })
    expect(total.textContent).toBe('83.33 %')
    expect(save).toHaveProperty('disabled', true)

    fireEvent.change(screen.getByLabelText('Share of Kid in percent'), { target: { value: '33.333' } })
    expect(total.textContent).toBe('—')
    expect(save).toHaveProperty('disabled', true)
    expect(screen.getByText('A percentage from 0 to 100, with at most two decimals.')).toBeDefined()

    fireEvent.change(screen.getByLabelText('Share of Anna in percent'), { target: { value: '66.67' } })
    fireEvent.change(screen.getByLabelText('Share of Kid in percent'), { target: { value: '33.33' } })
    expect(total.textContent).toBe('100.00 %')
    expect(save).toHaveProperty('disabled', false)
    fireEvent.click(save)

    expect(await screen.findByText('Saved.')).toBeDefined()
    expect(calls).toEqual([{ method: 'PUT', url: '/api/family-ledgers/7/split-rule',
      body: { rule: 'CUSTOM', shares: [{ memberId: 70, share: 6667 }, { memberId: 71, share: 3333 }] } }])
  })

  it('shows a 422 by the member it names', async () => {
    stubApi({
      'PUT /api/family-ledgers/7/split-rule': { status: 422, body: {
        status: 422, detail: 'Kid has no share; the shares sum to 50.00 %, not 100.00 %.',
        violations: ['Kid has no share', 'the shares sum to 50.00 %, not 100.00 %'],
        violationDetails: [
          { code: 'NO_SHARE', memberId: 71, message: 'Kid has no share' },
          { code: 'SUM_NOT_WHOLE', memberId: null, message: 'the shares sum to 50.00 %, not 100.00 %' },
        ],
      } },
    })
    renderPage(<FamilySplitRule family={familyData(home, [anna, kid])} />)
    fireEvent.click(screen.getByLabelText('Custom percentages'))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    const kidsRow = (await screen.findByText('Kid has no share.')).closest('tr')!
    expect(within(kidsRow).getByRole('rowheader').textContent).toBe('Kid')
    expect(screen.getByText('The shares sum to 50.00 %, not 100.00 %.')).toBeDefined()
  })

  it('is read only for a member who is not an owner', () => {
    renderPage(<FamilySplitRule family={familyData(allotment, [anna, ben])} />)

    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull()
    expect(screen.queryByLabelText('Custom percentages')).toBeNull()
    expect(screen.getByText('Only an owner changes the split rule.')).toBeDefined()
  })
})

describe('the members', () => {
  it('lets an owner manage members without an account, and shows the server’s 409', async () => {
    const calls = stubApi({
      'DELETE /api/family-ledgers/7/members/71': { status: 409, body: { status: 409,
        detail: 'Kid has left the family budget already.' } },
      'PATCH /api/family-ledgers/7/members/me': { status: 409, body: { status: 409,
        detail: 'The family budget has a member named kid already.' } },
    })
    const custom = { ...home, splitRule: 'CUSTOM' as const }
    renderPage(<FamilyMembers family={familyData(custom, [{ ...anna, share: 6667 }, { ...kid, share: 3333 }])} />)

    const kidsRow = screen.getByText('Kid').closest('tr')!
    expect(within(kidsRow).getByText('No account')).toBeDefined()
    expect(within(kidsRow).getByText('33.33 %')).toBeDefined()
    expect(screen.getByLabelText('Add a member without an account')).toBeDefined()
    // Removing asks first, saying what happens (F6a); the split rule goes back to equal shares with Kid's 33.33 %.
    fireEvent.click(within(kidsRow).getByRole('button', { name: 'Remove' }))
    const confirmation = screen.getByRole('region', { name: 'Remove Kid' })
    expect(within(confirmation).getByText('The split rule goes back to equal shares.')).toBeDefined()
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Remove Kid' }))
    expect(await within(confirmation).findByText('Kid has left the family budget already.')).toBeDefined()

    // A clash of her own name is about her name.
    const own = screen.getByText('You').closest('tr')!
    fireEvent.click(within(own).getByRole('button', { name: 'Change my name' }))
    fireEvent.change(within(own).getByLabelText('Your name in this budget'), { target: { value: 'kid' } })
    fireEvent.click(within(own).getByRole('button', { name: 'Save' }))
    expect(await within(own).findByText('The family budget has a member named kid already.')).toBeDefined()
    // The writes; an owner's page also loads the invites (F5).
    expect(calls.filter((c) => c.method !== 'GET').map((c) => [c.method, c.url])).toEqual([
      ['DELETE', '/api/family-ledgers/7/members/71'], ['PATCH', '/api/family-ledgers/7/members/me'],
    ])
  })

  it('hides the owners’ actions from a member, who changes only their own name', async () => {
    const calls = stubApi({ 'PATCH /api/family-ledgers/8/members/me': [
      { status: 409, body: { status: 409, detail: 'The family budget has a member named anna already.' } },
      { status: 200, body: { ...ben, displayName: 'Dad' } },
    ] })
    const kidInAllotment = { ...kid, id: 81 }
    renderPage(<FamilyMembers family={familyData(allotment, [anna, { ...ben, id: 80 }, kidInAllotment])} />)

    expect(screen.queryByRole('button', { name: 'Rename' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull()
    expect(screen.queryByLabelText('Add a member without an account')).toBeNull()
    expect(screen.getByText('Only an owner adds, renames and removes members.')).toBeDefined()

    const own = screen.getByText('You').closest('tr')!
    expect(within(own).getByText('Ben')).toBeDefined()
    fireEvent.click(within(own).getByRole('button', { name: 'Change my name' }))
    fireEvent.change(within(own).getByLabelText('Your name in this budget'), { target: { value: 'anna' } })
    fireEvent.click(within(own).getByRole('button', { name: 'Save' }))
    expect(await within(own).findByText('The family budget has a member named anna already.')).toBeDefined()

    fireEvent.change(within(own).getByLabelText('Your name in this budget'), { target: { value: 'Dad' } })
    fireEvent.click(within(own).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(within(own).queryByLabelText('Your name in this budget')).toBeNull())
    // The writes; the page also loads the balances, for its confirmations (F6a).
    expect(calls.filter((c) => c.method !== 'GET').map((c) => [c.method, c.url, c.body])).toEqual([
      ['PATCH', '/api/family-ledgers/8/members/me', { displayName: 'anna' }],
      ['PATCH', '/api/family-ledgers/8/members/me', { displayName: 'Dad' }],
    ])
  })
})

describe('the family categories', () => {
  const holidays: Category = { id: 31, code: 'HOLIDAYS', name: 'Holidays', type: 'EXPENSE', archived: false }

  it('lets a member add a category but not rename, archive or delete one', async () => {
    const calls = stubApi({
      'GET /api/family-ledgers/8/categories': { status: 200, body: [holidays] },
      'POST /api/family-ledgers/8/categories': { status: 201, body: { ...holidays, id: 32, code: 'GIFTS', name: 'Gifts' } },
    })
    renderPage(<FamilyCategories family={familyData(allotment, [anna, ben])} />)

    expect(await screen.findByText('Holidays')).toBeDefined()
    for (const action of ['Rename', 'Archive', 'Delete']) expect(screen.queryByRole('button', { name: action })).toBeNull()
    expect(screen.getByText(/Only an owner renames, archives and deletes them/)).toBeDefined()

    fireEvent.change(screen.getByLabelText('New family category'), { target: { value: 'Gifts' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toEqual([
      { method: 'POST', url: '/api/family-ledgers/8/categories', body: { code: 'GIFTS', name: 'Gifts', type: 'EXPENSE' } },
    ]))
  })

  it('shows an owner the server’s 409 next to the category', async () => {
    stubApi({
      'GET /api/family-ledgers/7/categories': { status: 200, body: [holidays] },
      'DELETE /api/family-ledgers/7/categories/31': { status: 409, body: { status: 409,
        detail: 'The category HOLIDAYS is used, so it can only be archived.' } },
    })
    renderPage(<FamilyCategories family={familyData(home, [anna])} />)

    const row = (await screen.findByText('Holidays')).closest('tr')!
    fireEvent.click(within(row).getByRole('button', { name: 'Delete' }))
    expect(await within(row).findByText('The category HOLIDAYS is used, so it can only be archived.')).toBeDefined()
  })
})
