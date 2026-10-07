import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { UNEXPECTED_ANSWER, type FamilyLedger, type FamilyMember, type Me } from './api'
import { Safe } from './components'
import { isBalances, isMe, isRecordPage, isReport } from './guards'
import { euroBalances } from './testLedger'
import { workedReport as testFamilyReport } from './testFamilyReport'

// F8c: a wrong answer from the api shows an error where it is read, and never a blank page. The guards of `useApi` keep
// an answer of another shape from being read; the error boundaries catch what still throws in render (`Safe`), around
// the app's routes and around the family pages' widgets.

type Answer = { status: number; body?: unknown }

// What the guards let through and the pages still can't read can't be built from JSON alone (the pages are tolerant),
// so a test makes the sentence of the reader's balance throw, as a wrong answer would.
const explode = vi.hoisted(() => ({ on: false }))
vi.mock('./family', async (importOriginal) => {
  const original = await importOriginal<typeof import('./family')>()
  return {
    ...original,
    yourBalance: (...args: Parameters<typeof original.yourBalance>) => {
      if (explode.on) throw new TypeError('Cannot read properties of undefined')
      return original.yourBalance(...args)
    },
  }
})

function stubApi(answers: Record<string, Answer>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const answer = answers[`${init?.method ?? 'GET'} ${url}`]
      ?? { status: 404, body: { status: 404, detail: `No static resource ${url.slice(1)}.` } }
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
      status: answer.status,
      headers: { 'Content-Type': answer.status >= 400 ? 'application/problem+json' : 'application/json' },
    })
  }))
}

const ON: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }
const home: FamilyLedger = {
  id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
  createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01',
}
const anna: FamilyMember = { id: 70, displayName: 'Anna', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null }
const sam: FamilyMember = { id: 71, displayName: 'Sam', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: false, share: null }
const empty = { content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 }
const member = (memberId: number, displayName: string, balance: string, you = false) =>
  ({ memberId, displayName, status: 'ACTIVE' as const, hasAccount: memberId === 70, balance, you })
const good = euroBalances([member(70, 'Anna', '5.00', true), member(71, 'Sam', '-5.00')])

const budget = (extra: Record<string, Answer> = {}) => stubApi({
  'GET /api/family-ledgers': { status: 200, body: [home] },
  'GET /api/family-ledgers/7': { status: 200, body: home },
  'GET /api/family-ledgers/7/members': { status: 200, body: [anna, sam] },
  'GET /api/family-ledgers/7/balances': { status: 200, body: good },
  'GET /api/family-ledgers/7/records?size=5': { status: 200, body: empty },
  ...extra,
})

function renderApp(path: string) {
  render(<MemoryRouter initialEntries={[path]}><App me={ON} /></MemoryRouter>)
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  explode.on = false
})

/** React logs a render error it caught; the tests that cause one expect that. */
const quiet = () => vi.spyOn(console, 'error').mockImplementation(() => {})

describe('the guards', () => {
  it('accept what the api answers, and refuse the shapes the pages would break on', () => {
    expect(isBalances(good)).toBe(true)
    expect(isBalances({ currency: 'EUR', members: [] })).toBe(false) // F8b-fix's pre-F8 answer
    expect(isBalances({ ...good, byCurrency: 'EUR' })).toBe(false)
    expect(isBalances({ ...good, byCurrency: [{ currency: 'EUR' }] })).toBe(false)
    expect(isBalances({ byCurrency: good.byCurrency })).toBe(false)
    expect(isBalances({ ...good, total: { ...good.total, rates: undefined } })).toBe(false)
    expect(isBalances(null)).toBe(false)
    expect(isBalances([])).toBe(false)

    expect(isReport(testFamilyReport)).toBe(true)
    expect(isReport({ ...testFamilyReport, members: undefined })).toBe(false)
    expect(isReport({ ...testFamilyReport, byCurrency: [{ currency: 'EUR', rows: [{ month: '2026-09' }], totals: [] }] })).toBe(false)
    expect(isReport({ ...testFamilyReport, total: null })).toBe(false)

    expect(isRecordPage(empty)).toBe(true)
    expect(isRecordPage({ content: [{ id: 1 }], totalElements: 1 })).toBe(false)
    const record = { id: 1, date: '2026-09-12', amount: '1.00', payer: { memberId: 70 }, category: null, shares: [] }
    expect(isRecordPage({ content: [record], totalElements: 1 })).toBe(true)
    expect(isRecordPage({ content: [{ ...record, shares: [{}] }], totalElements: 1 })).toBe(false)
    expect(isRecordPage({ content: 5, totalElements: 0 })).toBe(false)
    expect(isRecordPage('nothing')).toBe(false)

    expect(isMe({ name: 'Anna', today: '2026-10-07' })).toBe(true)
    expect(isMe({ name: 'Anna', today: '2026-10-07', timeZone: null })).toBe(true)
    expect(isMe({ name: 'Anna' })).toBe(false)
    expect(isMe({ name: 'Anna', today: '7 October' })).toBe(false)
    expect(isMe({ name: 'Anna', today: '2026-10-07', timeZone: 3 })).toBe(false)
    expect(isMe('<html>')).toBe(false)
  })
})

describe('a malformed balances answer', () => {
  const stale = { currency: 'EUR', members: [] }

  it('leaves the budget’s page up, and the widget says what it got', async () => {
    budget({ 'GET /api/family-ledgers/7/balances': { status: 200, body: stale } })
    renderApp('/family/7')

    expect(await screen.findByRole('heading', { name: 'Home' })).toBeDefined()
    expect(await screen.findByText(UNEXPECTED_ANSWER)).toBeDefined()
    // The rest of the overview, and the tabs, are as ever.
    expect(await screen.findByText('Nothing recorded yet.')).toBeDefined()
    expect(screen.getByRole('link', { name: 'Balances' })).toBeDefined()
    expect(screen.getByRole('link', { name: 'Members' })).toBeDefined()
    expect(screen.getByText('Start date')).toBeDefined()
  })

  it('stays on the balances tab, and the other tabs still work', async () => {
    budget({ 'GET /api/family-ledgers/7/balances': { status: 200, body: stale } })
    renderApp('/family/7/balances')
    expect(await screen.findByText(UNEXPECTED_ANSWER)).toBeDefined()
    expect(screen.getByRole('heading', { name: 'Home' })).toBeDefined()

    fireEvent.click(screen.getByRole('link', { name: 'Members' }))
    expect(await screen.findByText('Sam')).toBeDefined()
  })

  it('is no different on the members page, which reads the balances for the departure notes', async () => {
    budget({ 'GET /api/family-ledgers/7/balances': { status: 200, body: stale } })
    renderApp('/family/7/members')
    expect(await screen.findByText('Sam')).toBeDefined()
    expect(screen.getByRole('heading', { name: 'Home' })).toBeDefined()
  })
})

describe('a malformed report and records', () => {
  it('shows the report page’s error, with the budget around it', async () => {
    budget({ 'GET /api/family-ledgers/7/report': { status: 200, body: { from: null, to: null } } })
    renderApp('/family/7/report')
    expect(await screen.findByText(UNEXPECTED_ANSWER)).toBeDefined()
    expect(screen.getByRole('heading', { name: 'Home' })).toBeDefined()
  })

  it('shows the activity page’s error for a page of records of another shape', async () => {
    budget({ 'GET /api/family-ledgers/7/records?page=0&size=20': { status: 200, body: { content: [{ id: 1 }], totalElements: 1 } } })
    renderApp('/family/7/expenses')
    expect(await screen.findByText(UNEXPECTED_ANSWER)).toBeDefined()
    expect(screen.getByRole('heading', { name: 'Home' })).toBeDefined()
  })

  it('shows the overview’s widget the same for records', async () => {
    budget({ 'GET /api/family-ledgers/7/records?size=5': { status: 200, body: { content: 'none', totalElements: 0 } } })
    renderApp('/family/7')
    expect(await screen.findByText(UNEXPECTED_ANSWER)).toBeDefined()
    expect(await screen.findByText('Anna owes Sam', { exact: false }).catch(() => null)).toBeNull()
  })
})

describe('the error boundaries', () => {
  it('shows a widget that throws in render as a short error with a reload link, and the rest stays', async () => {
    quiet()
    explode.on = true
    budget()
    renderApp('/family/7')

    const alert = await screen.findByText(/Couldn’t show your balance/)
    expect(within(alert.closest('p')!).getByRole('link', { name: 'Reload the page' }).getAttribute('href'))
      .toBe(window.location.href)
    // The records beside it, the facts and the tabs are as ever.
    expect(await screen.findByText('Nothing recorded yet.')).toBeDefined()
    expect(screen.getByText('Start date')).toBeDefined()
    expect(screen.getByRole('heading', { name: 'Home' })).toBeDefined()
  })

  it('shows a family tab that throws as an error inside the budget’s page', async () => {
    quiet()
    explode.on = true
    budget()
    renderApp('/family/7/balances')

    expect(await screen.findByText(/Couldn’t show this page of the family budget/)).toBeDefined()
    expect(screen.getByRole('heading', { name: 'Home' })).toBeDefined()
    // Another tab tries again: the failure was the page's, not the budget's.
    fireEvent.click(screen.getByRole('link', { name: 'Members' }))
    expect(await screen.findByText('Sam')).toBeDefined()
    expect(screen.queryByText(/Couldn’t show/)).toBeNull()
  })

  it('shows a page of the app that throws as an error with the header in place, never a blank page', async () => {
    quiet()
    // A page without its list: the entries page reads its length.
    stubApi({
      'GET /api/entries?page=0&size=50': { status: 200, body: { content: null, totalElements: 5, totalPages: 1 } },
      'GET /api/accounts': { status: 200, body: [] },
      'GET /api/categories': { status: 200, body: [] },
      'GET /api/counterparties': { status: 200, body: [] },
      'GET /api/settings': { status: 200, body: { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' } },
    })
    render(<MemoryRouter initialEntries={['/entries']}><App me={{ ...ON, features: { familyLedgers: false } }} /></MemoryRouter>)

    expect(await screen.findByText(/Couldn’t show this page/)).toBeDefined()
    expect(screen.getByRole('link', { name: 'Dashboard' })).toBeDefined()
    expect(screen.getByRole('button', { name: 'Log out' })).toBeDefined()
    expect(document.body.textContent).not.toBe('')
  })

  it('tries the children again when its key changes', async () => {
    quiet()
    let broken = true
    function Child() {
      if (broken) throw new Error('boom')
      return <p>fine</p>
    }
    const { rerender } = render(<Safe what="the thing" resetKey="a"><Child /></Safe>)
    expect(screen.getByText(/Couldn’t show the thing/)).toBeDefined()
    broken = false
    rerender(<Safe what="the thing" resetKey="a"><Child /></Safe>)
    expect(screen.getByText(/Couldn’t show the thing/)).toBeDefined()
    rerender(<Safe what="the thing" resetKey="b"><Child /></Safe>)
    await waitFor(() => expect(screen.getByText('fine')).toBeDefined())
  })
})
