import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { DemoLedger, Entry, FamilyLedger, FamilyMember, Me } from './api'
import { formatMoney } from './money'
import { workedReport } from './testFamilyReport'
import { testLedger } from './testLedger'

// F6c in the interface: the family report (E1), the old shared expense no longer created with the switch on (H4), and
// the demo's family budget in the switcher right after loading it (H1).

type Answer = { status: number; body?: unknown }
type Call = { method: string; url: string; body: unknown }

/** Answers each request by "METHOD /api/path?query", or with 404; a list answers in turn, its last one from then on. */
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
  const location = useLocation()
  return <p data-testid="where">{location.pathname + location.search}</p>
}

function renderApp(me: Me, path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App me={me} />
      <Routes><Route path="*" element={<Where />} /></Routes>
    </MemoryRouter>,
  )
}

const ON: Me = { name: 'Anna', features: { familyLedgers: true } }
const OFF: Me = { name: 'Anna', features: { familyLedgers: false } }
const home: FamilyLedger = { id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 1, createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01' }
const mum: FamilyMember = { id: 1, displayName: 'Mum', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null, leftDate: null, claimedSeat: false }
const eur = (amount: string) => formatMoney(amount, 'EUR')
const personal = {
  'GET /api/accounts': { status: 200, body: testLedger.accounts },
  'GET /api/categories': { status: 200, body: testLedger.categories },
  'GET /api/counterparties': { status: 200, body: testLedger.counterparties },
  'GET /api/settings': { status: 200, body: testLedger.settings },
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 9, 2, 12))
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('the family report (E1)', () => {
  function budget(answers: Record<string, Answer | Answer[]> = {}) {
    return stubApi({
      'GET /api/family-ledgers': { status: 200, body: [home] },
      'GET /api/family-ledgers/7': { status: 200, body: home },
      'GET /api/family-ledgers/7/members': { status: 200, body: [mum] },
      'GET /api/family-ledgers/7/report': { status: 200, body: workedReport },
      ...answers,
    })
  }

  it('shows each member’s totals, then each month’s categories with every member’s part, in words', async () => {
    budget()
    renderApp(ON, '/family/7/report')

    expect(await screen.findByRole('heading', { name: 'Report' })).toBeDefined()
    const totals = (await screen.findByRole('heading', { name: 'By member' })).nextElementSibling as HTMLElement
    const [you, dad, kid] = within(totals).getAllByRole('listitem')
    expect(you.textContent).toContain(`Expenses: share ${eur('58.33')}, paid ${eur('90.00')}`)
    expect(you.textContent).toContain(`Settlements: received ${eur('20.00')}`)
    expect(you.textContent).toContain(`You owe ${eur('188.33')} more.`)
    expect(dad.textContent).toContain('Left')
    expect(dad.textContent).toContain(`Dad is owed ${eur('111.67')} more.`)
    expect(kid.textContent).toContain(`Kid is owed ${eur('76.66')} more.`)

    const september = screen.getByRole('heading', { name: 'September 2026' }).closest('section')!
    expect(september.textContent).toContain(`Expenses ${eur('140')} · Incomes ${eur('300')}`)
    const groceries = within(september).getByText('Groceries').closest('.report-row')!
    expect(groceries.textContent).toContain(eur('90.00'))
    expect(within(groceries as HTMLElement).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      `You: share ${eur('30.00')} · paid ${eur('90.00')}`, `Dad: share ${eur('30.00')}`, `Kid: share ${eur('30.00')}`])
    const salary = within(september).getByText('Salary').closest('.report-row')!
    expect(salary.textContent).toContain('Income')
    expect(salary.textContent).toContain(`You: share ${eur('100.00')} · received ${eur('300.00')}`)
    const october = screen.getByRole('heading', { name: 'October 2026' }).closest('section')!
    expect(october.textContent).toContain(`Kid: share ${eur('3.35')} · paid ${eur('10.01')}`)
    // No table that could be wider than a phone.
    expect(document.querySelector('.family-report table')).toBeNull()
    expect(screen.getByRole('link', { name: 'Report' }).getAttribute('href')).toBe('/family/7/report')
  })

  it('asks for the period in the URL, and says when it has no record', async () => {
    const calls = budget({
      'GET /api/family-ledgers/7/report?from=2026-11-01': { status: 200, body: { ...workedReport, from: '2026-11-01', rows: [],
        totals: workedReport.totals.map((t) => ({ ...t, expenseShares: '0.00', expensesPaid: '0.00', incomeShares: '0.00',
          incomesReceived: '0.00', settlementsPaid: '0.00', settlementsReceived: '0.00', net: '0.00' })) } },
    })
    renderApp(ON, '/family/7/report')
    await screen.findByRole('heading', { name: 'By member' })

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-11-01' } })

    expect(await screen.findByText(/No records in this period/)).toBeDefined()
    expect(screen.getByTestId('where').textContent).toBe('/family/7/report?from=2026-11-01')
    expect(calls.map((c) => c.url)).toContain('/api/family-ledgers/7/report?from=2026-11-01')
    fireEvent.click(screen.getByRole('button', { name: 'All records' }))
    expect(await screen.findByRole('heading', { name: 'By member' })).toBeDefined()
  })

  it('is no route with the switch off', async () => {
    const calls = stubApi({ ...personal, 'GET /api/entries?size=1': { status: 200, body: { content: [], page: 0, size: 1, totalElements: 0, totalPages: 0 } } })
    renderApp(OFF, '/family/7/report')
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/'))
    expect(calls.some((c) => c.url.startsWith('/api/family-ledgers'))).toBe(false)
  })
})

describe('the old shared expense (H4)', () => {
  const split = () => screen.queryByRole('switch', { name: 'Split with family' })
  const shared: Entry = {
    id: 5, version: 0, entryDate: '2026-09-25', kind: 'SHARED_EXPENSE', payeeId: null, memo: null,
    postings: [
      { accountId: 2, currency: 'EUR', amount: '-725.55', categoryId: null, counterpartyId: null },
      { accountId: 6, currency: 'EUR', amount: '362.77', categoryId: 12, counterpartyId: null },
      { accountId: 5, currency: 'EUR', amount: '362.78', categoryId: null, counterpartyId: null },
    ],
  }

  it('is no longer created with the switch on: a new expense has no “Split with family”', async () => {
    stubApi({ ...personal, 'GET /api/family-ledgers': { status: 200, body: [] } })
    renderApp(ON, '/entries/new')
    await screen.findByLabelText(/^Paid from/)
    await waitFor(() => expect(screen.getByLabelText(/^Category/)).toBeDefined())
    expect(split()).toBeNull()
  })

  it('opens an existing one as before, with its split, with the switch on', async () => {
    stubApi({ ...personal, 'GET /api/family-ledgers': { status: 200, body: [] }, 'GET /api/entries/5': { status: 200, body: shared } })
    renderApp(ON, '/entries/5')
    const toggle = await screen.findByRole('switch', { name: 'Split with family' })
    expect(toggle).toHaveProperty('checked', true)
    expect(screen.getByLabelText(/^Family's share/)).toHaveProperty('value', '50')
  })

  it('is created as before with the switch off', async () => {
    const calls = stubApi(personal)
    renderApp(OFF, '/entries/new')
    expect(await screen.findByRole('switch', { name: 'Split with family' })).toBeDefined()
    expect(calls.some((c) => c.url.startsWith('/api/family-ledgers'))).toBe(false)
  })
})

describe('the demo’s family budget (H1)', () => {
  const empty = { status: 200, body: { content: [], page: 0, size: 1, totalElements: 0, totalPages: 0 } }
  const demo: DemoLedger = { entriesByKind: { EXPENSE: 73 }, accounts: 2, categories: 3, counterparties: 10, from: '2026-04-01', to: '2026-10-02' }
  const household: FamilyLedger = { ...home, id: 9, name: 'Demo household', startDate: '2026-04-01' }

  it('says the demo comes with a family budget, and lists it in the switcher at once', async () => {
    const calls = stubApi({
      'GET /api/entries?size=1': empty,
      'GET /api/family-ledgers': [{ status: 200, body: [] }, { status: 200, body: [household] }],
      'POST /api/demo-data': { status: 200, body: { ...demo, familyLedgerId: 9 } },
    })
    renderApp(ON, '/')
    expect(await screen.findByText(/It also creates the family budget “Demo household”/)).toBeDefined()

    fireEvent.click(screen.getByRole('button', { name: 'Load demo data' }))

    await waitFor(() => expect(within(screen.getByRole('combobox', { name: 'Budget' })).getByRole('option', { name: 'Demo household' })).toBeDefined())
    expect(calls.filter((c) => c.method === 'GET' && c.url === '/api/family-ledgers')).toHaveLength(2)
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/?from=2026-04-01&to=2026-10-02'))
  })

  it('says nothing of a family budget with the switch off', async () => {
    const calls = stubApi({ 'GET /api/entries?size=1': empty, 'POST /api/demo-data': { status: 200, body: demo } })
    renderApp(OFF, '/')
    await screen.findByRole('button', { name: 'Load demo data' })
    expect(screen.queryByText(/Demo household/)).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Load demo data' }))

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/?from=2026-04-01&to=2026-10-02'))
    expect(calls.some((c) => c.url.startsWith('/api/family-ledgers'))).toBe(false)
  })
})
