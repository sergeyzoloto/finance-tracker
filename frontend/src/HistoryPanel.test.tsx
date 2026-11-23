import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Accounts from './Accounts'
import type { FamilyLedger, FamilyMember, FamilyRecord, Me } from './api'
import Categories from './Categories'
import Entries from './Entries'
import EntryEditor from './EntryEditor'
import { RecordDetail } from './FamilyExpenses'
import HistoryPanel from './HistoryPanel'
import type { HistoryPage, HistoryVersion } from './history'
import { testLedger } from './testLedger'
import { TEST_ME, WithMe } from './testMe'

// The History panel: its states, its paging, its texts, and the actions that open it. Today is 2026-09-30 (TEST_ME).

type Answer = { status: number; body?: unknown }
let calls: string[] = []

/** Answers each request by its path and query, or with 404; every request is recorded. */
function stubApi(answers: Record<string, Answer | (() => Promise<Answer>)>) {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    const found = answers[url] ?? answers[Object.keys(answers).find((k) => k.endsWith('*') && url.startsWith(k.slice(0, -1))) ?? '']
    const answer = typeof found === 'function' ? await found() : found ?? { status: 404, body: { status: 404, detail: 'Not found' } }
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
      status: answer.status, headers: { 'Content-Type': answer.status >= 400 ? 'application/problem+json' : 'application/json' },
    })
  }))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const user = { kind: 'USER', memberId: null, name: null }
const version = (seq: number, extra: Partial<HistoryVersion> = {}): HistoryVersion => ({
  seq, revision: seq, op: 'UPDATE', at: '2026-09-30T10:00:00Z', cause: 'EDIT', actor: user, changes: [], ...extra,
})
const page = (content: HistoryVersion[], p = 0, total = content.length, totalPages = 1): Answer =>
  ({ status: 200, body: { content, page: p, size: 20, total, totalPages } satisfies HistoryPage })
const path = (kind: string, id: number, p = 0) => `/api/history/${kind}/${id}?page=${p}&size=20`

const open = (kind: 'ENTRY' | 'ACCOUNT' = 'ACCOUNT', id = 1) =>
  render(<HistoryPanel kind={kind} id={id} subject="Cash" ledger={testLedger} onClose={() => {}} />)

describe('the History panel', () => {
  it('says it is loading until the first page arrives, then lists the versions newest first', async () => {
    let release: (a: Answer) => void = () => {}
    stubApi({ [path('ACCOUNT', 1)]: () => new Promise<Answer>((resolve) => { release = resolve }) })
    open()
    expect(screen.getByText('Loading the history…')).toBeTruthy()
    release(page([version(2, { changes: [{ field: 'name', old: 'Cash', new: 'Wallet' }] }), version(1, { op: 'CREATE' })]))
    expect(await screen.findByText('Name: Cash → Wallet')).toBeTruthy()
    const items = screen.getAllByRole('listitem').filter((li) => li.classList.contains('history-version'))
    expect(items.map((li) => within(li).getAllByText(/Changed|Created/)[0].textContent)).toEqual(['Changed', 'Created'])
    expect(screen.getAllByText('Sep 30, 2026, 10:00 AM')).toHaveLength(2)
    expect(screen.queryByText('Load more')).toBeNull()
  })

  it('says plainly that nothing is recorded for a page with no versions', async () => {
    stubApi({ [path('ACCOUNT', 1)]: page([], 0, 0, 0) })
    open()
    expect(await screen.findByText('No changes recorded.')).toBeTruthy()
  })

  it('says plainly that a record without history has none', async () => {
    stubApi({})
    open()
    expect(await screen.findByText('There is no history for this item.')).toBeTruthy()
  })

  it('shows an error with a retry that loads again', async () => {
    let fail = true
    stubApi({ [path('ACCOUNT', 1)]: async () => (fail ? { status: 500, body: { detail: 'The database is busy' } } : page([version(1, { op: 'CREATE' })])) })
    open()
    expect(await screen.findByText('The database is busy')).toBeTruthy()
    fail = false
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Created')).toBeTruthy()
    expect(screen.queryByText('The database is busy')).toBeNull()
  })

  it('loads more on request, disables the button while it loads, and hides it at the end', async () => {
    const first = Array.from({ length: 20 }, (_, i) => version(25 - i))
    let release: (a: Answer) => void = () => {}
    stubApi({
      [path('ACCOUNT', 1)]: page(first, 0, 25, 2),
      [path('ACCOUNT', 1, 1)]: () => new Promise<Answer>((resolve) => { release = resolve }),
    })
    open()
    const more = await screen.findByRole('button', { name: 'Load more' })
    fireEvent.click(more)
    const waiting = await screen.findByRole('button', { name: 'Loading…' })
    expect((waiting as HTMLButtonElement).disabled).toBe(true)
    expect(document.querySelectorAll('.history-version')).toHaveLength(20)
    release(page([5, 4, 3, 2, 1].map((n) => version(n)), 1, 25, 2))
    await waitFor(() => expect(document.querySelectorAll('.history-version')).toHaveLength(25))
    expect(screen.queryByRole('button', { name: /Load more|Loading/ })).toBeNull()
  })

  it('keeps the loaded versions when a later page fails, with the error and a retry', async () => {
    let fail = true
    stubApi({
      [path('ACCOUNT', 1)]: page([version(2), version(1)], 0, 3, 2),
      [path('ACCOUNT', 1, 1)]: async () => (fail ? { status: 500, body: { detail: 'Try later' } } : page([version(0, { op: 'CREATE' })], 1, 3, 2)),
    })
    open()
    fireEvent.click(await screen.findByRole('button', { name: 'Load more' }))
    expect(await screen.findByText('Try later')).toBeTruthy()
    expect(document.querySelectorAll('.history-version')).toHaveLength(2)
    fail = false
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(document.querySelectorAll('.history-version')).toHaveLength(3))
    expect(screen.queryByText('Try later')).toBeNull()
  })

  it('marks the first state as the state when history began, and names Import and System', async () => {
    stubApi({
      [path('ACCOUNT', 1)]: page([
        version(3, { actor: { kind: 'IMPORT', memberId: null, name: null }, cause: 'IMPORT' }),
        version(2, { actor: { kind: 'SYSTEM', memberId: null, name: null }, cause: 'WHATEVER' }),
        version(1, { op: 'BASELINE', cause: 'BASELINE', actor: { kind: 'SYSTEM', memberId: null, name: null } }),
      ]),
    })
    open()
    expect(await screen.findByText('State when history began')).toBeTruthy()
    const items = [...document.querySelectorAll('.history-version')].map((li) => li.textContent)
    expect(items[0]).toContain('Import')
    expect(items[1]).toContain('System')
    expect(items[1]).toContain('Other change')
    // An ordinary edit, and the baseline's own cause, add no cause line.
    expect(items[2]).not.toMatch(/BASELINE/)
    expect(screen.getByText('Other change')).toBeTruthy()
  })

  it('writes an entry’s change as field: old → new with money formatted', async () => {
    stubApi({
      [path('ENTRY', 4)]: page([version(2, {
        changes: [
          { field: 'postings', old: [{ account: 1, currency: 'EUR', amount: '-12.00' }], new: [{ account: 1, currency: 'EUR', amount: '-15.50' }] },
          { field: 'payee', old: 21, new: 404 },
          { field: 'memo', old: null, new: 'Lunch' },
          { field: 'surprise', old: 1, new: 2 },
        ],
      })]),
    })
    open('ENTRY', 4)
    const text = (await screen.findByText(/^Accounts and amounts:/)).textContent!.replace(/[\u00a0\u2009\u202f]/g, ' ')
    expect(text).toBe('Accounts and amounts: Cash, -€12.00 → Cash, -€15.50')
    expect(screen.getByText('Payee: Albert Heijn → Removed item')).toBeTruthy()
    expect(screen.getByText('Memo: — → Lunch')).toBeTruthy()
    expect(screen.getByText('Other detail: 1 → 2')).toBeTruthy()
    expect(document.body.textContent).not.toMatch(/\b(revision|seq|BASELINE|FAMILY_RECORD|JSON)\b/i)
  })

  it('is a side panel on a wide screen and a full-screen sheet on a phone', async () => {
    stubApi({ [path('ACCOUNT', 1)]: page([version(1)]) })
    const width = (wide: boolean) => vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: wide, addEventListener() {}, removeEventListener() {} })))
    width(true)
    const wide = open()
    expect(screen.getByRole('dialog').classList.contains('sheet')).toBe(false)
    wide.unmount()
    width(false)
    open()
    expect(screen.getByRole('dialog').classList.contains('sheet')).toBe(true)
    expect(screen.getByRole('button', { name: 'Close' })).toBeTruthy()
  })

  it('closes with its Close button and with Escape', async () => {
    stubApi({ [path('ACCOUNT', 1)]: page([version(1)]) })
    const onClose = vi.fn()
    render(<HistoryPanel kind="ACCOUNT" id={1} onClose={onClose} />)
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(2)
  })
})

describe('the actions that open it', () => {
  it('opens an account’s history from its row, without a change of the page', async () => {
    stubApi({
      '/api/accounts': { status: 200, body: testLedger.accounts },
      '/api/reports/balances?asOf=2026-09-30': { status: 200, body: [] },
      [path('ACCOUNT', 9)]: page([version(1, { op: 'CREATE' })]),
    })
    render(<WithMe><MemoryRouter><Accounts /></MemoryRouter></WithMe>)
    fireEvent.click(await screen.findByRole('button', { name: 'History of Wise' }))
    expect(await screen.findByRole('dialog')).toBeTruthy()
    expect(await screen.findByText('Created')).toBeTruthy()
    expect(calls).toContain(path('ACCOUNT', 9))
  })

  it('opens a category’s history from its row', async () => {
    stubApi({ '/api/categories': { status: 200, body: testLedger.categories }, [path('CATEGORY', 12)]: page([version(1, { op: 'CREATE' })]) })
    render(<MemoryRouter><Categories /></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: 'History of Groceries' }))
    expect(await screen.findByText('Created')).toBeTruthy()
  })

  it('opens an entry’s history from its row in Entries, and does not open the entry', async () => {
    const entry = {
      id: 31, version: 0, entryDate: '2026-09-12', kind: 'EXPENSE', payeeId: null, memo: null,
      postings: [
        { accountId: 1, currency: 'EUR', amount: '-12.00', categoryId: null, counterpartyId: null },
        { accountId: 6, currency: 'EUR', amount: '12.00', categoryId: 12, counterpartyId: null },
      ],
    }
    stubApi({
      '/api/accounts': { status: 200, body: testLedger.accounts },
      '/api/categories': { status: 200, body: testLedger.categories },
      '/api/counterparties': { status: 200, body: testLedger.counterparties },
      '/api/settings': { status: 200, body: testLedger.settings },
      '/api/entries?*': { status: 200, body: { content: [entry], page: 0, size: 1, totalElements: 1, totalPages: 1 } },
      '/api/entries/summary?*': { status: 200, body: { count: 0 } },
      [path('ENTRY', 31)]: page([version(1, { op: 'CREATE' })]),
    })
    function Probe() { return <p>Opened an entry</p> }
    render(
      <WithMe><MemoryRouter initialEntries={['/entries?period=all']}>
        <Routes><Route path="/entries" element={<Entries />} /><Route path="/entries/:id" element={<Probe />} /></Routes>
      </MemoryRouter></WithMe>,
    )
    const history = await screen.findByRole('button', { name: 'History of the entry of Sep 12, 2026' }, { timeout: 3000 })
    fireEvent.click(history)
    expect(await screen.findByText('Created')).toBeTruthy()
    expect(screen.queryByText('Opened an entry')).toBeNull()
  })
})

describe('the entry editor’s History action', () => {
  const reference = {
    '/api/accounts': { status: 200, body: testLedger.accounts },
    '/api/categories': { status: 200, body: testLedger.categories },
    '/api/counterparties': { status: 200, body: testLedger.counterparties },
    '/api/settings': { status: 200, body: testLedger.settings },
  }
  const editor = (at: string) => render(
    <WithMe><MemoryRouter initialEntries={[at]}>
      <Routes><Route path="/entries/new" element={<EntryEditor />} /><Route path="/entries/:id" element={<EntryEditor />} /></Routes>
    </MemoryRouter></WithMe>,
  )

  it('is offered for a saved entry and opens its history', async () => {
    stubApi({
      ...reference,
      '/api/entries/31': { status: 200, body: {
        id: 31, version: 0, entryDate: '2026-09-12', kind: 'EXPENSE', payeeId: null, memo: null,
        postings: [
          { accountId: 1, currency: 'EUR', amount: '-12.00', categoryId: null, counterpartyId: null },
          { accountId: 6, currency: 'EUR', amount: '12.00', categoryId: 12, counterpartyId: null },
        ],
      } },
      [path('ENTRY', 31)]: page([version(1, { op: 'CREATE' })]),
    })
    editor('/entries/31')
    fireEvent.click(await screen.findByRole('button', { name: 'History' }))
    expect(await screen.findByText('Created')).toBeTruthy()
  })

  it('is not offered for a new entry', async () => {
    stubApi(reference)
    editor('/entries/new')
    await screen.findByRole('heading', { name: 'New entry' })
    await screen.findByText('Save')
    expect(screen.queryByRole('button', { name: 'History' })).toBeNull()
  })
})

describe('a family record’s history', () => {
  const home: FamilyLedger = {
    id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
    createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01',
  }
  const anna: FamilyMember = { id: 70, displayName: 'Anna', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null }
  const ref = { memberId: 70, displayName: 'Anna' }
  const record: FamilyRecord = {
    id: 5, type: 'EXPENSE', date: '2026-09-12', category: { id: 30, code: 'GROCERIES', name: 'Groceries', archived: false },
    amount: '72.40', currency: 'EUR', payer: ref, splitMethod: 'EQUAL', comment: null, author: ref,
    createdAt: '2026-09-12T10:00:00Z', updatedBy: ref, updatedAt: '2026-09-12T10:00:00Z', version: 0, frozen: false,
    canEdit: true, canDelete: true, canEditPayment: true,
    shares: [{ member: ref, amount: '72.40', basisPoints: null, updatedBy: ref, updatedAt: '2026-09-12T10:00:00Z' }],
  }
  const detail = (me: Me) => render(
    <WithMe me={me}>
      <MemoryRouter initialEntries={['/family/7/expenses/5']}>
        <Routes>
          <Route path="/family/7/expenses/:recordId" element={
            <RecordDetail family={{ ledger: home, members: [anna], path: '/family-ledgers/7', page: '/family/7', owner: true, reload: () => {} }} />} />
        </Routes>
      </MemoryRouter>
    </WithMe>,
  )
  const answers = {
    '/api/family-ledgers/7/records/5': { status: 200, body: record },
    '/api/family-ledgers/7/journal?recordId=5&size=200': { status: 200, body: { content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 } },
    [path('FAMILY_RECORD', 5)]: page([version(1, {
      op: 'CREATE', actor: { kind: 'USER', memberId: 70, name: 'Anna' },
      changes: [{ field: 'amount', old: null, new: '72.40', newCurrency: 'EUR' }, { field: 'payer', old: null, new: 'Anna' }],
    })]),
  }

  it('opens from the record with the switch on, naming members as the answer does', async () => {
    stubApi(answers)
    detail(TEST_ME)
    fireEvent.click(await screen.findByRole('button', { name: 'History' }))
    expect(await screen.findByText('Amount: — → €72.40')).toBeTruthy()
    expect(screen.getByText('Paid by: — → Anna')).toBeTruthy()
    expect(calls).toContain(path('FAMILY_RECORD', 5))
  })

  it('makes no history request and offers no action with the switch off', async () => {
    stubApi(answers)
    detail({ ...TEST_ME, features: { familyLedgers: false } })
    await screen.findByText(/Groceries, /)
    expect(screen.queryByRole('button', { name: 'History' })).toBeNull()
    expect(calls.filter((c) => c.startsWith('/api/history'))).toEqual([])
  })
})
