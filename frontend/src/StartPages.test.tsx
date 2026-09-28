import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DemoLedger } from './api'
import EmptyLedger from './EmptyLedger'
import Landing from './Landing'
import Settings from './Settings'

// The landing page, the dashboard of an empty ledger, and deleting all data: what visitors and new users meet first.

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

/** Answers every fetch with this status and JSON body, and records the requests. */
function stubFetch(status: number, body?: unknown) {
  const fetch = vi.fn(async (_url: string, _init?: RequestInit) => new Response(
    body === undefined ? null : JSON.stringify(body),
    { status, headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' } }))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

describe('Landing', () => {
  it('says what the app is and links to the login, the source and the privacy policy', () => {
    render(<Landing />)

    expect(screen.getByRole('heading', { level: 1 })).toBeDefined()
    expect(screen.getByText(/double-entry bookkeeping underneath/)).toBeDefined()
    const logins = screen.getAllByRole('link', { name: /Sign in/ })
    expect(logins.length).toBeGreaterThan(0)
    logins.forEach((link) => expect(link.getAttribute('href')).toBe('/oauth2/authorization/keycloak'))
    expect(screen.getByRole('link', { name: 'Privacy policy' }).getAttribute('href')).toBe('/privacy')
    expect(screen.getByRole('link', { name: /Source code on GitHub/ }).getAttribute('href'))
      .toBe('https://github.com/sergeyzoloto/finance-tracker')
    expect(screen.getByText(/Load demo data/)).toBeDefined()
    expect(screen.getByRole('heading', { name: 'How it’s built' })).toBeDefined()
    expect(screen.getByText(/personal portfolio project, provided as is/)).toBeDefined()
  })
})

describe('EmptyLedger', () => {
  const demo: DemoLedger = {
    entriesByKind: { EXPENSE: 73 }, accounts: 2, categories: 3, counterparties: 10, from: '2026-03-28', to: '2026-09-28',
  }

  it('loads the demo data and hands over what it created', async () => {
    const fetch = stubFetch(200, demo)
    const onLoaded = vi.fn()
    render(<MemoryRouter><EmptyLedger onLoaded={onLoaded} /></MemoryRouter>)

    fireEvent.click(screen.getByRole('button', { name: 'Load demo data' }))

    await waitFor(() => expect(onLoaded).toHaveBeenCalledWith(demo))
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls[0][0]).toBe('/api/demo-data')
    expect(fetch.mock.calls[0][1]?.method).toBe('POST')
    expect(screen.getByRole('link', { name: 'Add an entry' }).getAttribute('href')).toBe('/entries/new')
  })

  it('shows why the server refused', async () => {
    stubFetch(409, { status: 409, detail: 'The demo data can only go into an empty ledger.' })
    const onLoaded = vi.fn()
    render(<MemoryRouter><EmptyLedger onLoaded={onLoaded} /></MemoryRouter>)

    fireEvent.click(screen.getByRole('button', { name: 'Load demo data' }))

    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'The demo data can only go into an empty ledger.')
    expect(onLoaded).not.toHaveBeenCalled()
  })
})

describe('Settings', () => {
  function Where() {
    const location = useLocation()
    return <p data-testid="where">{location.pathname} {JSON.stringify(location.state)}</p>
  }

  function renderSettings() {
    render(
      <MemoryRouter initialEntries={['/settings']}>
        <Routes>
          <Route path="/settings" element={<Settings />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>,
    )
  }

  it('deletes all data only after DELETE is typed exactly, then goes to the empty dashboard', async () => {
    const fetch = stubFetch(204)
    renderSettings()
    const button = screen.getByRole('button', { name: 'Delete all my data' })
    const confirmation = screen.getByLabelText(/to confirm/)

    expect(button).toHaveProperty('disabled', true)
    for (const almost of ['delete', 'DELETE ', 'DELET']) {
      fireEvent.change(confirmation, { target: { value: almost } })
      expect(button).toHaveProperty('disabled', true)
    }
    fireEvent.submit(confirmation.closest('form')!)
    expect(fetch).not.toHaveBeenCalled()

    fireEvent.change(confirmation, { target: { value: 'DELETE' } })
    expect(button).toHaveProperty('disabled', false)
    fireEvent.click(button)

    expect((await screen.findByTestId('where')).textContent).toBe('/ {"dataDeleted":true}')
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls[0][0]).toBe('/api/me/data')
    expect(fetch.mock.calls[0][1]?.method).toBe('DELETE')
  })

  it('says that the login account stays and how to have it deleted', () => {
    renderSettings()

    expect(screen.getByText(/lives on auth.finance-nl.com/)).toBeDefined()
    expect(screen.getByRole('link', { name: 'serge@finance-nl.com' }).getAttribute('href')).toBe('mailto:serge@finance-nl.com')
    expect(screen.getByRole('link', { name: 'privacy policy' }).getAttribute('href')).toBe('/privacy')
  })
})
