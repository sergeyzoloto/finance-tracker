import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetAcceptance } from './acceptance'
import App from './App'
import type { Category, FamilyLedger, FamilyMember, FamilyRecord, Me } from './api'
import Import from './Import'
import type { NotificationRow } from './notifications'
import { euroBalances, testLedger } from './testLedger'

// Family acceptance on the screens (HIST-3 part 5): the banner, the mark, the Accept offer of a refused action, the
// notification's Accept, the import's note, and that with acceptance off (the pending summary answers 404) nothing is
// drawn and the summary is asked once per session.

const ON: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }
const home: FamilyLedger = {
  id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
  createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01',
}
const anna: FamilyMember = { id: 70, displayName: 'Anna', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null }
const sam: FamilyMember = { id: 71, displayName: 'Sam', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null }
const categories: Category[] = [{ id: 30, code: 'GROCERIES', name: 'Groceries', type: 'EXPENSE', archived: false }]
const ref = (m: FamilyMember) => ({ memberId: m.id, displayName: m.displayName })
const at = '2026-09-12T10:00:00Z'
const share = (m: FamilyMember, amount: string) => ({ member: ref(m), amount, basisPoints: null, updatedBy: ref(sam), updatedAt: at })
const record: FamilyRecord = {
  id: 5, type: 'EXPENSE', date: '2026-09-12', category: { id: 30, code: 'GROCERIES', name: 'Groceries', archived: false },
  amount: '20.00', currency: 'EUR', comment: null, payer: ref(sam), splitMethod: 'EQUAL', shares: [share(anna, '10.00'), share(sam, '10.00')],
  author: ref(sam), createdAt: at, updatedBy: ref(sam), updatedAt: at, version: 0, frozen: false, canEdit: true, canDelete: true,
  canEditPayment: false, pendingForMe: true,
}
const page = (content: FamilyRecord[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })
const json = (body: unknown, status = 200) => new Response(body === undefined ? null : JSON.stringify(body), {
  status, headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
})
const refusal = (n: number) => json({
  status: 409, title: 'Conflict', detail: `Accept the ${n} pending ${n === 1 ? 'change' : 'changes'} first.`,
  code: 'FAMILY_PENDING_CHANGES', pendingChanges: n,
}, 409)

interface Server {
  /** Changes waiting for the reader; an accept sets it to 0. */
  pending: number
  /** Acceptance on: the summary answers; off: it is an unknown path. */
  on: boolean
  notifications: NotificationRow[]
  calls: { method: string; url: string; body: unknown }[]
}

/** A server for one family budget whose writes are refused while `pending` is above 0. */
function server(init: Partial<Server> = {}) {
  const state: Server = { pending: 0, on: true, notifications: [], calls: [], ...init }
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    const method = options?.method ?? 'GET'
    state.calls.push({ method, url, body: typeof options?.body === 'string' ? JSON.parse(options.body) : undefined })
    const path = url.split('?')[0]
    if (path === '/api/me') return json(ON)
    if (path === '/api/accounts') return json(testLedger.accounts)
    if (path === '/api/categories') return json(testLedger.categories)
    if (path === '/api/counterparties') return json(testLedger.counterparties)
    if (path === '/api/settings') return json(testLedger.settings)
    if (path === '/api/family-ledgers') return json([home])
    if (path === '/api/family-ledgers/7') return json(home)
    if (path === '/api/family-ledgers/7/members') return json([anna, sam])
    if (path === '/api/family-ledgers/7/categories') return json(categories)
    if (path === '/api/family-ledgers/7/balances') return json(euroBalances([]))
    if (path === '/api/family-ledgers/7/pending') {
      return state.on ? json({ count: state.pending, recordIds: state.pending > 0 ? [5] : [] }) : json({ status: 404, detail: 'No static resource.' }, 404)
    }
    if (path === '/api/family-ledgers/7/accept') {
      const accepted = state.pending
      state.pending = 0
      state.notifications = state.notifications.map((n) => ({ ...n, read: true }))
      return json({ accepted, revision: 9 })
    }
    if (path === '/api/family-ledgers/7/records' && method === 'GET') return json(page([{ ...record, pendingForMe: state.on && state.pending > 0 }]))
    if (path === '/api/family-ledgers/7/records/5' && method === 'GET') return json({ ...record, pendingForMe: state.on && state.pending > 0 })
    if (path === '/api/family-ledgers/7/journal') return json({ content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 })
    if (state.pending > 0 && (method === 'POST' || method === 'PATCH' || method === 'DELETE')) return refusal(state.pending)
    if (path === '/api/family-ledgers/7/records' && method === 'POST') return json({ ...record, id: 6 }, 201)
    if (path === '/api/family-ledgers/7/settlements' && method === 'POST') return json({ ...record, id: 6, type: 'SETTLEMENT' }, 201)
    if (path === '/api/notifications/unread-count') return json({ count: state.notifications.filter((n) => !n.read).length })
    if (path === '/api/notifications') {
      return json({ content: state.notifications, page: 0, size: 50, total: state.notifications.length, totalPages: 1 })
    }
    return json({ status: 404, detail: `No static resource ${path}.` }, 404)
  }))
  return state
}

const count = (s: Server, part: string, method = 'GET') => s.calls.filter((c) => c.method === method && c.url.includes(part)).length

let go: (to: string) => void
function Where() {
  const location = useLocation()
  const navigate = useNavigate()
  go = (to) => navigate(to)
  return <p data-testid="where">{location.pathname}</p>
}
function renderApp(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App me={ON} />
      <Routes><Route path="*" element={<Where />} /></Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  resetAcceptance()
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

describe('the banner', () => {
  it('shows the count above a family page and goes after Accept, which loads the list again and says how many', async () => {
    const s = server({ pending: 2 })
    renderApp('/family/7/expenses')
    const banner = await screen.findByText('2 changes by others are waiting for you')
    expect(banner.closest('.pending-banner')).not.toBeNull()
    await screen.findByRole('table')
    expect(count(s, '/records?')).toBe(1)

    fireEvent.click(screen.getByRole('button', { name: 'Accept' }))
    expect((await screen.findByText('Accepted 2 changes.')).getAttribute('role')).toBe('status')
    expect(screen.queryByText(/waiting for you/)).toBeNull()
    expect(s.calls.filter((c) => c.method === 'POST').map((c) => c.url)).toEqual(['/api/family-ledgers/7/accept'])
    await waitFor(() => expect(count(s, '/records?')).toBe(2))
  })

  it('says “1 change … is” for one and shows nothing at 0', async () => {
    server({ pending: 1 })
    renderApp('/family/7')
    expect(await screen.findByText('1 change by others is waiting for you')).toBeDefined()
    cleanup()
    server({ pending: 0 })
    renderApp('/family/7')
    await screen.findByRole('heading', { name: 'Latest activity' })
    expect(screen.queryByText(/waiting for you/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Accept' })).toBeNull()
  })

  it('is asked when a family page opens and on each move between them, never by a timer', async () => {
    const s = server({ pending: 1 })
    renderApp('/family/7')
    await waitFor(() => expect(count(s, '/pending')).toBe(1))
    vi.useFakeTimers()
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000)
    expect(count(s, '/pending')).toBe(1)
    vi.useRealTimers()
    go('/family/7/balances')
    await waitFor(() => expect(count(s, '/pending')).toBe(2))
  })
})

describe('the mark', () => {
  it('is on a record waiting for the reader, in the list and on its page, with what it means', async () => {
    server({ pending: 1 })
    renderApp('/family/7/expenses')
    const row = (await screen.findAllByRole('row'))[1]
    const mark = within(row).getByText('Waiting for you')
    expect(mark.getAttribute('title')).toContain('latest version')
    expect(mark.getAttribute('title')).toContain('own books follow once you accept')

    cleanup()
    server({ pending: 1 })
    renderApp('/family/7/expenses/5')
    expect((await screen.findAllByText('Waiting for you')).length).toBe(1)
    expect(screen.getByRole('note').textContent).toContain('own books follow once you accept')
  })
})

describe('a refused action', () => {
  async function fillExpense() {
    fireEvent.change(await screen.findByLabelText('Category'), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText(/^Amount \(/), { target: { value: '12.50' } })
    fireEvent.change(await screen.findByLabelText(/^Paid from/), { target: { value: 'later' } })
  }

  it('offers Accept next to the server’s message for a new expense; one click accepts, keeps the form and retries nothing', async () => {
    const s = server({ pending: 3 })
    renderApp('/family/7/expenses/new')
    await fillExpense()
    fireEvent.click(screen.getByRole('button', { name: 'Add the expense' }))

    const offer = (await screen.findByText('Accept the 3 pending changes first.')).closest('[role="alert"]') as HTMLElement
    fireEvent.click(within(offer).getByRole('button', { name: 'Accept 3 changes' }))
    await screen.findByText(/Accepted 3 changes\. Nothing was saved yet\. Press the same button again\./)
    // Nothing retried: the one POST of records is the refused one. The form keeps what was typed.
    expect(count(s, '/records', 'POST')).toBe(1)
    expect((screen.getByLabelText(/^Amount \(/) as HTMLInputElement).value).toBe('12.50')
    expect((screen.getByLabelText('Category') as HTMLSelectElement).value).toBe('30')
    expect(screen.getByTestId('where').textContent).toBe('/family/7/expenses/new')

    fireEvent.click(screen.getByRole('button', { name: 'Add the expense' }))
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/family/7/expenses/6'))
    expect(count(s, '/records', 'POST')).toBe(2)
  })

  it('offers Accept for a settlement and does not record it by itself', async () => {
    const s = server({ pending: 1 })
    renderApp('/family/7/settle?payer=70&payee=71&amount=5')
    fireEvent.change(await screen.findByLabelText(/^Paid from/), { target: { value: 'later' } })
    fireEvent.click(screen.getByRole('button', { name: 'Record the settlement' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Accept 1 change' }))
    await screen.findByText(/Accepted 1 change\. Nothing was saved yet/)
    expect(count(s, '/settlements', 'POST')).toBe(1)
    expect(screen.getByTestId('where').textContent).toBe('/family/7/settle')
  })

  it('offers Accept for an edit and for a delete on a record’s page, and retries neither', async () => {
    const s = server({ pending: 2 })
    renderApp('/family/7/expenses/5')
    fireEvent.change(await screen.findByLabelText('Comment (optional)'), { target: { value: 'Market' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save the changes' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Accept 2 changes' }))
    await screen.findByText(/Accepted 2 changes\. Nothing was saved yet/)
    expect(count(s, '/records/5', 'PATCH')).toBe(1)
    expect((screen.getByLabelText('Comment (optional)') as HTMLInputElement).value).toBe('Market')

    s.pending = 1
    fireEvent.click(screen.getByRole('button', { name: 'Delete the expense' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Accept 1 change' }))
    await screen.findByText(/Accepted 1 change\. Nothing was saved yet/)
    expect(count(s, '/records/5', 'DELETE')).toBe(1)
    expect(screen.getByTestId('where').textContent).toBe('/family/7/expenses/5')
  })

  it('leaves other errors as they were: no Accept button', async () => {
    const s = server()
    renderApp('/family/7/expenses/new')
    await fillExpense()
    const base = fetch as unknown as (url: string, init?: RequestInit) => Promise<Response>
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => (init?.method === 'POST'
      ? json({ status: 409, detail: 'Changed in the meantime.' }, 409) : base(url, init))))
    fireEvent.click(screen.getByRole('button', { name: 'Add the expense' }))
    await screen.findByText('Changed in the meantime.')
    expect(screen.queryByRole('button', { name: /^Accept/ })).toBeNull()
    expect(count(s, '/accept', 'POST')).toBe(0)
  })
})

describe('the notifications', () => {
  const row = (id: number, kind: string, extra: Partial<NotificationRow> = {}): NotificationRow => ({
    id, kind, at: '2026-11-12T10:30:00Z', read: false, ledgerId: 7, author: { memberId: 71, name: 'Sam' },
    fromRevision: 3, toRevision: 4, changesCount: 2, text: `Sam made changes ${id}`, ...extra,
  })

  it('give a row about changes an Accept button while changes wait; it accepts, reloads and refetches the badge', async () => {
    const s = server({ pending: 2, notifications: [row(1, 'FAMILY_CHANGES'), row(2, 'FAMILY_RESTORED'), row(3, 'MEMBER_JOINED')] })
    renderApp('/notifications')
    await screen.findByText('Sam made changes 1')
    const buttons = await screen.findAllByRole('button', { name: 'Accept' })
    expect(buttons).toHaveLength(2)
    expect(buttons[0].getAttribute('title')).toBe('Accept 2 changes')
    const unreadBefore = count(s, '/unread-count')
    const listBefore = count(s, '/api/notifications?')

    fireEvent.click(buttons[0])
    await screen.findByText('Accepted 2 changes.')
    await waitFor(() => expect(count(s, '/api/notifications?')).toBe(listBefore + 1))
    expect(count(s, '/unread-count')).toBeGreaterThan(unreadBefore)
    expect(screen.queryByRole('button', { name: 'Accept' })).toBeNull()
    // The server marked the rows read; no Unread label is left.
    expect(screen.queryByText('Unread')).toBeNull()
    expect(screen.getByTestId('where').textContent).toBe('/notifications')
  })

  it('give no Accept button when nothing waits, and none for a membership row', async () => {
    server({ pending: 0, notifications: [row(1, 'FAMILY_CHANGES'), row(3, 'MEMBER_JOINED')] })
    renderApp('/notifications')
    await screen.findByText('Sam made changes 1')
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Accept' })).toBeNull())
  })
})

describe('with acceptance off', () => {
  it('draws nothing new and asks the pending summary once per session', async () => {
    const s = server({ on: false, pending: 0, notifications: [{
      id: 1, kind: 'FAMILY_CHANGES', at: '2026-11-12T10:30:00Z', read: false, ledgerId: 7, author: null,
      fromRevision: 1, toRevision: 2, changesCount: 1, text: 'Sam made a change',
    }] })
    renderApp('/family/7')
    await screen.findByRole('heading', { name: 'Latest activity' })
    await waitFor(() => expect(count(s, '/pending')).toBe(1))
    go('/family/7/expenses')
    await screen.findByRole('table')
    go('/family/7/balances')
    go('/notifications')
    await screen.findByText('Sam made a change')
    go('/family/7/expenses')
    await screen.findByRole('table')
    expect(count(s, '/pending')).toBe(1)
    expect(screen.queryByText(/waiting for you/)).toBeNull()
    expect(screen.queryByText('Waiting for you')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Accept' })).toBeNull()
    go('/notifications')
    await screen.findByText('Sam made a change')
    expect(screen.queryByRole('button', { name: 'Accept' })).toBeNull()
    expect(count(s, '/pending')).toBe(1)
  })
})

describe('the import', () => {
  const member = { ...home, role: 'MEMBER' as const, memberId: 71 }
  const report = (extra: object, totals: object = {}) => ({
    commit: false, outcome: 'DRY_RUN', files: [], family: { ledgerId: 7, ledgerName: 'Home' }, period: null, upTo: null,
    afterCutoff: [], currencies: ['EUR'], counts: [],
    totals: { created: 0, updated: 0, unchanged: 0, missing: 0, conflicts: 0, failed: 0, skipped: 0, notImported: 0, ...totals },
    missing: [], conflicts: [], errors: [], warnings: [], errorSummary: [], warningSummary: [], skippedSummary: [],
    referenceData: { accountsCreated: 0, accountsUpdated: 0, categoriesCreated: 0, categoriesUpdated: 0,
      familyCategoriesCreated: 0, categoriesMerged: 0, counterpartiesCreated: 0 },
    chart: { created: [], renamed: [] }, idGaps: [], zeroFxRowsSkipped: 0, fxRowsToReview: [], reconciliation: null,
    integrityViolations: [], ...extra,
  })
  const NOTE = '3 family changes are waiting for you; accept them before committing'

  function importServer(commitAnswer: () => Response) {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
      calls.push(`${options?.method ?? 'GET'} ${url}`)
      if (url === '/api/family-ledgers/7/members') return json([{ ...anna, id: 71, claimedSeat: true }])
      if (url === '/api/family-ledgers/7/accept') return json({ accepted: 3, revision: 9 })
      if (url.startsWith('/api/import?dryRun=true')) {
        return json(report({ warnings: [{ file: null, row: null, excelId: null, message: NOTE }] }, { notCheckedUntilAccepted: 4 }))
      }
      if (url.startsWith('/api/import')) return commitAnswer()
      return json({}, 404)
    }))
    return calls
  }

  async function check() {
    render(<MemoryRouter><Import families={[member]} /></MemoryRouter>)
    const input = document.querySelector('input[name="transactions"]') as HTMLInputElement
    fireEvent.change(input, { target: { files: [new File(['x'], 't.csv', { type: 'text/csv' })] } })
    fireEvent.change(screen.getByRole('combobox', { name: 'Family budget' }), { target: { value: '7' } })
    await waitFor(() => expect((screen.getByRole('button', { name: 'Check (dry run)' }) as HTMLButtonElement).disabled).toBe(false))
    fireEvent.submit(document.querySelector('form.import')!)
    await screen.findByText(/Dry run: nothing was saved/)
  }

  it('shows the dry run’s note and the count of rows not checked until the uploader accepts', async () => {
    importServer(() => json({}))
    await check()
    expect(screen.getAllByText(NOTE).length).toBeGreaterThan(0)
    expect(screen.getByTestId('not-checked').textContent).toBe('4 family rows not checked until you accept')
  })

  it('offers Accept for a refused commit, keeps the files and commits nothing by itself', async () => {
    const calls = importServer(() => refusal(3))
    await check()
    fireEvent.click(screen.getByRole('button', { name: 'Commit import' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Accept 3 changes' }))
    await screen.findByText(/Accepted 3 changes\. Nothing was saved yet/)
    expect(calls.filter((c) => c.startsWith('POST /api/import?') && !c.includes('dryRun=true'))).toHaveLength(1)
    expect(calls).toContain('POST /api/family-ledgers/7/accept')
    expect((screen.getByRole('button', { name: 'Commit import' }) as HTMLButtonElement).disabled).toBe(false)
  })
})
