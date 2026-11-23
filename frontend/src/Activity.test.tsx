import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Activity from './Activity'
import App from './App'
import { formatInstant, type Me } from './api'
import { SESSION_NOTE, type ActivityRow } from './activity'
import { TEST_ME, WithMe } from './testMe'
import { userMenu } from './testHeader'

const row = (id: number, kind: string, at = '2026-11-12T10:30:00Z', extra: Partial<ActivityRow> = {}): ActivityRow => ({
  id, at, kind, ledgerId: null, actor: { kind: 'USER', memberId: null, name: null }, subject: null, details: null, ...extra,
})
const answer = (content: ActivityRow[], page = 0, totalPages = 1, total = content.length) =>
  ({ content, page, size: 50, total, totalPages })

function stub(respond: (url: string) => unknown, status = 200) {
  const fetchMock = vi.fn(async (url: string) =>
    new Response(JSON.stringify(respond(url)), { status, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function Where() {
  const { pathname, search } = useLocation()
  return <p data-testid="where">{pathname + search}</p>
}
const where = () => screen.getByTestId('where').textContent

function renderPage(path = '/activity') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <WithMe><Routes><Route path="/activity" element={<Activity />} /></Routes></WithMe>
      <Where />
    </MemoryRouter>,
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the Activity page (D-1119)', () => {
  it('asks for page 0 of 50, titles itself, says the session note once and lists sentences with times', async () => {
    const f = stub(() => answer([
      row(2, 'SIGN_IN', '2026-11-12T10:30:00Z'),
      row(1, 'MEMBER_LEFT', '2026-11-11T08:00:00Z', {
        ledgerId: 7, actor: { kind: 'USER', memberId: 70, name: 'Anna' }, subject: { memberId: 80, name: null },
      }),
    ]))
    renderPage()
    expect(await screen.findByText('You signed in')).toBeTruthy()
    expect(f.mock.calls[0][0]).toBe('/api/activity?page=0&size=50')
    expect(screen.getByRole('heading', { name: 'Activity' })).toBeTruthy()
    expect(screen.getAllByText(SESSION_NOTE)).toHaveLength(1)
    expect(screen.getByText('Former member left the family budget')).toBeTruthy()
    const items = screen.getAllByRole('listitem')
    expect(within(items[0]).getByText(formatInstant('2026-11-12T10:30:00Z'))).toBeTruthy()
    expect(within(items[1]).getByText(formatInstant('2026-11-11T08:00:00Z'))).toBeTruthy()
  })

  it('shows the time in the user’s zone and format, not a raw instant', async () => {
    stub(() => answer([row(1, 'SIGN_OUT', '2026-11-12T23:30:00Z')]))
    renderPage()
    const shown = formatInstant('2026-11-12T23:30:00Z')
    expect(await screen.findByText(shown)).toBeTruthy()
    expect(shown).not.toContain('2026-11-12T')
  })

  it('shows an unknown kind as “Other activity”, never its name', async () => {
    stub(() => answer([row(1, 'BRAND_NEW_KIND')]))
    renderPage()
    expect(await screen.findByText('Other activity')).toBeTruthy()
    expect(screen.queryByText(/BRAND_NEW_KIND/)).toBeNull()
  })

  it('shows Loading… first, then “No activity yet.” for an empty list', async () => {
    stub(() => answer([], 0, 0, 0))
    renderPage()
    expect(screen.getByText('Loading…')).toBeTruthy()
    expect(await screen.findByText('No activity yet.')).toBeTruthy()
  })

  it('shows the error with “Try again”, which asks again', async () => {
    const f = stub(() => ({ status: 400, detail: 'Nope.' }), 400)
    renderPage()
    expect(await screen.findByRole('alert')).toBeTruthy()
    f.mockImplementation(async () => new Response(JSON.stringify(answer([row(1, 'SIGN_IN')])), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }))
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('You signed in')).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('pages through Newer and Older, the page number in the address', async () => {
    const f = stub((url) => {
      const page = Number(new URL(url, 'http://x').searchParams.get('page'))
      return answer([row(page + 1, 'SIGN_IN')], page, 3, 150)
    })
    renderPage()
    await screen.findByText('You signed in')
    const newer = () => screen.getByRole('button', { name: '← Newer' }) as HTMLButtonElement
    const older = () => screen.getByRole('button', { name: 'Older →' }) as HTMLButtonElement
    expect(newer().disabled).toBe(true)
    expect(older().disabled).toBe(false)
    fireEvent.click(older())
    await waitFor(() => expect(where()).toBe('/activity?page=1'))
    await waitFor(() => expect(f.mock.calls.at(-1)![0]).toBe('/api/activity?page=1&size=50'))
    await waitFor(() => expect(newer().disabled).toBe(false))
    fireEvent.click(older())
    await waitFor(() => expect(where()).toBe('/activity?page=2'))
    await waitFor(() => expect(older().disabled).toBe(true))
    fireEvent.click(newer())
    await waitFor(() => expect(where()).toBe('/activity?page=1'))
    fireEvent.click(newer())
    await waitFor(() => expect(where()).toBe('/activity'))
  })

  it('says so, with a way back, for a page past the end', async () => {
    stub(() => answer([], 99, 3, 150))
    renderPage('/activity?page=99')
    expect(await screen.findByText('No activity on this page.')).toBeTruthy()
    expect(screen.queryByText('No activity yet.')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Back to the first page' }))
    await waitFor(() => expect(where()).toBe('/activity'))
  })

  it('opens at the page in the address', async () => {
    const f = stub(() => answer([row(51, 'SIGN_IN')], 2, 3, 150))
    renderPage('/activity?page=2')
    await screen.findByText('You signed in')
    expect(f.mock.calls[0][0]).toBe('/api/activity?page=2&size=50')
    expect((screen.getByRole('button', { name: 'Older →' }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: '← Newer' }) as HTMLButtonElement).disabled).toBe(false)
  })
})

describe('the Activity entry in the user’s menu (D-1130)', () => {
  const ME: Me = { ...TEST_ME, features: { familyLedgers: false } }

  it('sits below Settings and directly above Log out, and leads to /activity', async () => {
    stub(() => answer([row(1, 'SIGN_IN')]))
    render(
      <MemoryRouter initialEntries={['/accounts']}>
        <App me={ME} />
        <Routes><Route path="*" element={<Where />} /></Routes>
      </MemoryRouter>,
    )
    const panel = userMenu('Anna')
    const items = within(panel).getAllByRole('listitem').map((li) => li.textContent)
    expect(items.indexOf('Activity')).toBe(items.indexOf('Settings') + 1)
    expect(items.indexOf('Log out')).toBe(items.indexOf('Activity') + 1)
    fireEvent.click(within(panel).getByRole('link', { name: 'Activity' }))
    expect(await screen.findByRole('heading', { name: 'Activity' })).toBeTruthy()
    expect(await screen.findByText('You signed in')).toBeTruthy()
  })
})
