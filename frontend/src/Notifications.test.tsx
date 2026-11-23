import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { formatInstant, type Me } from './api'
import type { NotificationRow } from './notifications'
import { TEST_ME } from './testMe'

const ON: Me = { ...TEST_ME, features: { familyLedgers: true } }
const OFF: Me = { ...TEST_ME, features: { familyLedgers: false } }

const row = (id: number, extra: Partial<NotificationRow> = {}): NotificationRow => ({
  id, kind: 'FAMILY_CHANGES', at: '2026-11-12T10:30:00Z', read: false, ledgerId: 7,
  author: { memberId: 1, name: 'Boris' }, fromRevision: 3, toRevision: 4, changesCount: 2,
  text: `Boris made 2 changes ${id}`, ...extra,
})
const answer = (content: NotificationRow[], page = 0, totalPages = 1, total = content.length) =>
  ({ content, page, size: 50, total, totalPages })

const json = (body: unknown, status = 200) =>
  new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** A server: `state.count` and `state.rows` change with the marks, as a real one would. */
function server(init: { count?: number; rows?: NotificationRow[]; failMark?: boolean; failCount?: boolean; failReadAll?: boolean } = {}) {
  const state = { count: init.count ?? 0, rows: init.rows ?? [], failMark: init.failMark ?? false, failCount: init.failCount ?? false,
    failReadAll: init.failReadAll ?? false, gate: undefined as Promise<void> | undefined }
  const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
    const path = new URL(url, 'http://x').pathname
    const method = options?.method ?? 'GET'
    if (path === '/api/notifications/unread-count') return state.failCount ? json({ detail: 'No.' }, 500) : json({ count: state.count })
    if (path === '/api/notifications/read-all' && method === 'POST') {
      if (state.failReadAll) return json({ detail: 'Not now.' }, 400)
      state.rows = state.rows.map((r) => ({ ...r, read: true })); state.count = 0
      return json(null, 204)
    }
    const one = /^\/api\/notifications\/(\d+)\/read$/.exec(path)
    if (one && method === 'POST') {
      if (state.gate) await state.gate
      if (state.failMark) return json({ detail: 'Could not mark it.' }, 400)
      state.rows = state.rows.map((r) => (r.id === Number(one[1]) ? { ...r, read: true } : r))
      state.count = state.rows.filter((r) => !r.read).length
      return json(null, 204)
    }
    if (path === '/api/notifications') {
      const page = Number(new URL(url, 'http://x').searchParams.get('page'))
      return json(page === 0 ? answer(state.rows, 0, 1) : answer([], page, 1, state.rows.length))
    }
    if (path === '/api/family-ledgers') return json([])
    if (path === '/api/activity') return json(answer([]))
    return json({ detail: 'Not stubbed.' }, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { state, fetchMock }
}
const calls = (f: ReturnType<typeof server>['fetchMock'], part: string) =>
  f.mock.calls.filter(([url]) => String(url).includes(part))
const countCalls = (f: ReturnType<typeof server>['fetchMock']) => calls(f, '/api/notifications/unread-count').length
const listCalls = (f: ReturnType<typeof server>['fetchMock']) =>
  f.mock.calls.filter(([url]) => /^\/api\/notifications\?/.test(String(url))).length

function Where() {
  const { pathname, search } = useLocation()
  return <p data-testid="where">{pathname + search}</p>
}
const where = () => screen.getByTestId('where').textContent
let go: (to: string) => void
function Go() {
  const navigate = useNavigate()
  go = (to) => navigate(to)
  return null
}

function renderApp(path: string, me: Me = ON) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App me={me} />
      <Routes><Route path="*" element={<><Where /><Go /></>} /></Routes>
    </MemoryRouter>,
  )
}

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const badge = () => screen.queryByRole('link', { name: /unread notification/ })

describe('the family switch off (D-1140)', () => {
  it('has no badge and asks for no notifications, on a page and on /notifications, which goes to /', async () => {
    const s = server({ count: 4, rows: [row(1)] })
    renderApp('/activity', OFF)
    await screen.findByRole('navigation', { name: 'Main' })
    go('/notifications')
    await waitFor(() => expect(where()).toBe('/'))
    expect(where()).toBe('/')
    expect(screen.queryByRole('heading', { name: 'Notifications' })).toBeNull()
    expect(badge()).toBeNull()
    expect(calls(s.fetchMock, '/api/notifications')).toHaveLength(0)
  })

  it('redirects a first visit to /notifications and still asks for nothing', async () => {
    const s = server({ count: 4, rows: [row(1)] })
    renderApp('/notifications', OFF)
    await waitFor(() => expect(where()).toBe('/'))
    expect(calls(s.fetchMock, '/api/notifications')).toHaveLength(0)
  })
})

describe('the unread count (D-1134, D-1135, D-1139)', () => {
  it('is fetched on load and once more after each navigation, and never by a timer', async () => {
    const s = server({ count: 3 })
    vi.useFakeTimers({ toFake: ['setInterval', 'setTimeout'] })
    const interval = vi.spyOn(globalThis, 'setInterval')
    try {
      renderApp('/activity')
      await vi.waitFor(() => expect(countCalls(s.fetchMock)).toBe(1))
      await act(async () => { await vi.advanceTimersByTimeAsync(0) })
      const link = screen.getByRole('link', { name: '3 unread notifications' })
      expect(link.getAttribute('href')).toBe('/notifications')
      await act(async () => { await vi.advanceTimersByTimeAsync(10 * 60 * 1000) })
      expect(countCalls(s.fetchMock)).toBe(1)
      expect(interval).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
    s.state.count = 5
    go('/notifications')
    expect(await screen.findByRole('link', { name: '5 unread notifications' })).toBeTruthy()
    expect(countCalls(s.fetchMock)).toBe(2)
  })

  it('is hidden at 0, says "1 unread notification" at 1 and "99+" over 99', async () => {
    const s = server({ count: 0 })
    renderApp('/activity')
    await waitFor(() => expect(countCalls(s.fetchMock)).toBe(1))
    expect(badge()).toBeNull()
    s.state.count = 1
    go('/notifications')
    const one = await screen.findByRole('link', { name: '1 unread notification' })
    expect(one.textContent).toBe('1')
    s.state.count = 100
    go('/activity')
    const many = await screen.findByRole('link', { name: '100 unread notifications' })
    expect(many.textContent).toBe('99+')
  })

  it('hides the badge when the request fails, with no error text, until a later one succeeds', async () => {
    const s = server({ count: 3, failCount: true })
    renderApp('/activity')
    await waitFor(() => expect(countCalls(s.fetchMock)).toBe(1))
    expect(badge()).toBeNull()
    expect(within(screen.getByRole('banner')).queryByRole('alert')).toBeNull()
    s.state.failCount = false
    go('/notifications')
    expect(await screen.findByRole('link', { name: '3 unread notifications' })).toBeTruthy()
  })
})

describe('the Notifications page (D-1120, D-1136, D-1137, D-1138)', () => {
  const rows = () => [
    row(2, { text: 'Anna joined the family budget', kind: 'MEMBER_JOINED', ledgerId: 9, at: '2026-11-12T23:30:00Z' }),
    row(1, { read: true, text: 'Boris changed the budget' }),
  ]

  it('shows the text as sent, the time, and marks only the unread row', async () => {
    const s = server({ count: 1, rows: rows() })
    renderApp('/notifications')
    const items = await screen.findAllByRole('listitem')
    expect(within(items[0]).getByText('Anna joined the family budget')).toBeTruthy()
    expect(within(items[0]).getByText(formatInstant('2026-11-12T23:30:00Z'))).toBeTruthy()
    expect(within(items[0]).getByText('Unread')).toBeTruthy()
    expect(within(items[1]).queryByText('Unread')).toBeNull()
    expect(screen.getByRole('heading', { name: 'Notifications' })).toBeTruthy()
    expect(s.fetchMock.mock.calls.some(([u]) => u === '/api/notifications?page=0&size=50')).toBe(true)
  })

  it('opening a row sends the mark first, then goes to the members tab; the count is fetched again', async () => {
    const s = server({ count: 1, rows: rows() })
    let release = () => {}
    s.state.gate = new Promise<void>((resolve) => { release = resolve })
    renderApp('/notifications')
    fireEvent.click(await screen.findByText('Anna joined the family budget'))
    await waitFor(() => expect(s.fetchMock.mock.calls.some(([u]) => u === '/api/notifications/2/read')).toBe(true))
    expect(where()).toBe('/notifications')
    await act(async () => { await new Promise((r) => setTimeout(r, 30)) })
    expect(where()).toBe('/notifications')
    release()
    await waitFor(() => expect(where()).toBe('/family/9/members'))
    const sent = s.fetchMock.mock.calls.findIndex(([u, o]) => u === '/api/notifications/2/read' && o?.method === 'POST')
    expect(sent).toBeGreaterThan(-1)
    expect(s.state.rows[0].read).toBe(true)
    await waitFor(() => expect(screen.queryByRole('link', { name: /unread notification/ })).toBeNull())
    const after = s.fetchMock.mock.calls.slice(sent + 1).filter(([u]) => u === '/api/notifications/unread-count')
    expect(after.length).toBeGreaterThan(0)
  })

  it('opening a family-change row goes to the journal of its budget', async () => {
    server({ count: 1, rows: [row(5, { kind: 'FAMILY_IMPORTED', ledgerId: 4, text: 'Imported' })] })
    renderApp('/notifications')
    fireEvent.click(await screen.findByText('Imported'))
    await waitFor(() => expect(where()).toBe('/family/4/journal'))
  })

  it('does not navigate when the mark fails: the row stays unread and the error shows with Try again', async () => {
    const s = server({ count: 1, rows: rows(), failMark: true })
    renderApp('/notifications')
    fireEvent.click(await screen.findByText('Anna joined the family budget'))
    expect(await screen.findByText('Could not mark it.')).toBeTruthy()
    expect(where()).toBe('/notifications')
    expect(within(screen.getAllByRole('listitem')[0]).getByText('Unread')).toBeTruthy()
    expect(s.state.rows[0].read).toBe(false)
    expect(badge()).toBeTruthy()
    s.state.failMark = false
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(where()).toBe('/family/9/members'))
  })

  it('"Mark all as read" sends read-all, then asks for the list and the count again; the badge goes', async () => {
    const s = server({ count: 1, rows: rows() })
    renderApp('/notifications')
    await screen.findByText('Anna joined the family budget')
    expect(await screen.findByRole('link', { name: '1 unread notification' })).toBeTruthy()
    const lists = listCalls(s.fetchMock)
    const counts = countCalls(s.fetchMock)
    fireEvent.click(screen.getByRole('button', { name: 'Mark all as read' }))
    await waitFor(() => expect(screen.queryByText('Unread')).toBeNull())
    expect(listCalls(s.fetchMock)).toBe(lists + 1)
    expect(countCalls(s.fetchMock)).toBe(counts + 1)
    expect(badge()).toBeNull()
    expect(where()).toBe('/notifications')
  })

  it('keeps the rows as they were when "Mark all as read" fails', async () => {
    const s = server({ count: 1, rows: rows(), failReadAll: true })
    renderApp('/notifications')
    await screen.findByText('Anna joined the family budget')
    await screen.findByRole('link', { name: '1 unread notification' })
    const lists = listCalls(s.fetchMock)
    const counts = countCalls(s.fetchMock)
    fireEvent.click(screen.getByRole('button', { name: 'Mark all as read' }))
    expect(await screen.findByText('Not now.')).toBeTruthy()
    expect(listCalls(s.fetchMock)).toBe(lists)
    expect(countCalls(s.fetchMock)).toBe(counts)
    expect(screen.getAllByText('Unread')).toHaveLength(1)
    expect(screen.getByText('Anna joined the family budget')).toBeTruthy()
    expect(screen.getByText('Boris changed the budget')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy()
  })

  it('shows no stale error once a later mark works', async () => {
    const s = server({ count: 1, rows: rows(), failMark: true })
    renderApp('/notifications')
    fireEvent.click(await screen.findByText('Anna joined the family budget'))
    expect(await screen.findByText('Could not mark it.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Mark all as read' }))
    await waitFor(() => expect(screen.queryByText('Unread')).toBeNull())
    expect(screen.queryByText('Could not mark it.')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(s.state.count).toBe(0)
  })

  it('says "No notifications yet." for an empty first page', async () => {
    server({ rows: [] })
    renderApp('/notifications')
    expect(await screen.findByText('No notifications yet.')).toBeTruthy()
  })

  it('says so, with a way back, for a page past the end', async () => {
    server({ rows: [row(1)] })
    renderApp('/notifications?page=3')
    expect(await screen.findByText('No notifications on this page.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Back to the first page' }))
    await waitFor(() => expect(where()).toBe('/notifications'))
  })

  it('pages with Newer and Older, the page in the address', async () => {
    const s = server()
    s.fetchMock.mockImplementation(async (url: string) => {
      const u = new URL(url, 'http://x')
      if (u.pathname === '/api/notifications') {
        const page = Number(u.searchParams.get('page'))
        return json(answer([row(page + 1, { text: `Row on page ${page}` })], page, 3, 150))
      }
      return json(u.pathname.endsWith('unread-count') ? { count: 0 } : [])
    })
    renderApp('/notifications')
    await screen.findByText('Row on page 0')
    const older = () => screen.getByRole('button', { name: 'Older →' }) as HTMLButtonElement
    const newer = () => screen.getByRole('button', { name: '← Newer' }) as HTMLButtonElement
    expect(newer().disabled).toBe(true)
    fireEvent.click(older())
    await waitFor(() => expect(where()).toBe('/notifications?page=1'))
    await screen.findByText('Row on page 1')
    expect(s.fetchMock.mock.calls.some(([u]) => u === '/api/notifications?page=1&size=50')).toBe(true)
    fireEvent.click(newer())
    await waitFor(() => expect(where()).toBe('/notifications'))
  })
})
