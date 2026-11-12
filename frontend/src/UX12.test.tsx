import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Counterparty, Entry, EntrySummary, FamilyLedger, FamilyMember, FamilyPayee, FamilyRecord, Me } from './api'
import Entries from './Entries'
import { newForm } from './entryForm'
import { EntryFormView } from './EntryForms'
import { payeeChange, payeeFilterOf, payeeFilterOptions, payeeFilterValue } from './familyPayees'
import { payeeSuggestions } from './payeeNames'
import { testLedger } from './testLedger'
import { TEST_ME, WithMe } from './testMe'

// UX-12: Entries' "Payee or person" with every payee the page can show (D-547, D-548), the family forms' payee typed
// freely (D-549), New entry's payee suggestions (D-550), and the two layout fixes (D-552).

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')

type Answer = { status: number; body?: unknown }
type Call = { method: string; url: string; body: unknown }

/** The api by "METHOD url"; a list answers in turn, its last one for ever. Unknown requests are 404. */
function stubApi(answers: Record<string, Answer | Answer[]>, dynamic: (method: string, url: string) => Answer | undefined = () => undefined) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ method, url, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    const listed = answers[`${method} ${url}`]
    const answer = dynamic(method, url)
      ?? (Array.isArray(listed) ? (listed.length > 1 ? listed.shift() : listed[0]) : listed)
      ?? { status: 404, body: { status: 404, detail: 'Not found' } }
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
      status: answer.status,
      headers: { 'Content-Type': answer.status >= 400 ? 'application/problem+json' : 'application/json' },
    })
  }))
  return calls
}

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const counterparties: Counterparty[] = [
  ...testLedger.counterparties,
  { id: 24, name: 'Old bakery', kind: 'MERCHANT', archived: true, lastCategoryId: null },
]
const familyPayees: FamilyPayee[] = [
  { name: 'ALBERT HEIJN', budgets: [{ id: 7, name: 'Home' }] },
  { name: 'Trefpunt Coffeeshop', budgets: [{ id: 8, name: 'Away' }, { id: 7, name: 'Home' }] },
  { name: 'bakker Bart', budgets: [{ id: 7, name: 'Home' }] },
]

describe('Entries’ payee options (D-547)', () => {
  it('merges the counterparties and the family payees, one per name, labelled and sorted ignoring case', () => {
    expect(payeeFilterOptions(counterparties, familyPayees, '').map((o) => [o.value, o.label])).toEqual([
      ['c:21', 'Albert Heijn'],
      ['p:bakker Bart', 'bakker Bart · Home'],
      ['c:23', 'Employer'],
      ['c:22', 'Ivan'],
      ['c:24', 'Old bakery (archived)'],
      ['p:Trefpunt Coffeeshop', 'Trefpunt Coffeeshop · Away, Home'],
    ])
  })

  it('keeps the chosen payee, as its name alone, once no family payee is it', () => {
    const options = payeeFilterOptions(counterparties, [], 'Trefpunt Coffeeshop')
    expect(options.find((o) => o.value === 'p:Trefpunt Coffeeshop')?.label).toBe('Trefpunt Coffeeshop')
    expect(payeeFilterOptions(counterparties, familyPayees, 'Trefpunt Coffeeshop')
      .filter((o) => o.name === 'Trefpunt Coffeeshop')).toHaveLength(1)
  })

  it('sets one filter and drops the other; a counterparty wins when the URL has both (D-556)', () => {
    expect(payeeFilterOf('p:Trefpunt')).toEqual({ counterpartyId: '', payee: 'Trefpunt' })
    expect(payeeFilterOf('c:21')).toEqual({ counterpartyId: '21', payee: '' })
    expect(payeeFilterOf('')).toEqual({ counterpartyId: '', payee: '' })
    expect(payeeFilterValue('21', 'Trefpunt')).toBe('c:21')
    expect(payeeFilterValue('', 'Trefpunt')).toBe('p:Trefpunt')
  })
})

describe('Entries’ payee filter in the page (D-547)', () => {
  let where = ''
  function Where() {
    where = useLocation().search
    return null
  }
  const summary: EntrySummary = {
    count: 1, firstDate: '2026-09-12', lastDate: '2026-09-12', payees: 1, withoutPayee: 0, categories: 1,
    withoutCategory: 0, accounts: 1, currencies: [{ currency: 'EUR', incoming: '0.00', outgoing: '5.00', transfers: '0.00' }],
  }
  const share: Entry = {
    id: 90, version: 0, entryDate: '2026-09-12', kind: 'FAMILY_SHARE', payeeId: null, memo: null,
    postings: [
      { accountId: 6, currency: 'EUR', amount: '5.00', categoryId: 12, counterpartyId: null },
      { accountId: 1, currency: 'EUR', amount: '-5.00', categoryId: null, counterpartyId: null },
    ],
    family: { ledgerId: 7, ledgerName: 'Home', recordId: 3, link: 'SHARE', readOnly: true, recordPayee: { name: 'Trefpunt Coffeeshop' } },
  }

  function renderEntries(path: string, me: Me = TEST_ME) {
    const calls = stubApi({
      'GET /api/accounts': { status: 200, body: testLedger.accounts },
      'GET /api/categories': { status: 200, body: testLedger.categories },
      'GET /api/counterparties': { status: 200, body: counterparties },
      'GET /api/settings': { status: 200, body: testLedger.settings },
      'GET /api/entries/family-payees': { status: 200, body: familyPayees },
    }, (method, url) => {
      if (url.startsWith('/api/entries/summary')) return { status: 200, body: summary }
      if (url.startsWith('/api/entries?')) return { status: 200, body: { content: [share], page: 0, size: 50, totalElements: 1, totalPages: 1 } }
      return undefined
    })
    render(
      <WithMe me={me}>
        <MemoryRouter initialEntries={[path]}>
          <Routes><Route path="/entries" element={<><Entries /><Where /></>} /></Routes>
        </MemoryRouter>
      </WithMe>,
    )
    return calls
  }
  const payeeSelect = () => screen.getByRole('combobox', { name: 'Payee or person' }) as HTMLSelectElement

  it('lists a family payee with its budgets, and choosing it filters the list and the summary by name', async () => {
    const calls = renderEntries('/entries?counterpartyId=22&page=2')
    await waitFor(() => expect([...payeeSelect().options].map((o) => o.textContent)).toContain('Trefpunt Coffeeshop · Away, Home'))
    expect(payeeSelect().value).toBe('c:22')
    fireEvent.change(payeeSelect(), { target: { value: 'p:Trefpunt Coffeeshop' } })
    expect(new URLSearchParams(where).get('payee')).toBe('Trefpunt Coffeeshop')
    expect(new URLSearchParams(where).has('counterpartyId')).toBe(false)
    expect(new URLSearchParams(where).has('page')).toBe(false)
    await waitFor(() => expect(calls.map((c) => c.url))
      .toContain('/api/entries/summary?payee=Trefpunt+Coffeeshop'))
    expect(calls.map((c) => c.url)).toContain('/api/entries?payee=Trefpunt+Coffeeshop&page=0&size=50')

    fireEvent.change(payeeSelect(), { target: { value: 'c:21' } })
    expect(new URLSearchParams(where).get('counterpartyId')).toBe('21')
    expect(new URLSearchParams(where).has('payee')).toBe(false)

    fireEvent.change(payeeSelect(), { target: { value: 'p:Trefpunt Coffeeshop' } })
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(where).toBe('')
  })

  it('keeps a chosen name the list no longer has, and sends only the counterparty when the URL has both', async () => {
    const calls = renderEntries('/entries?payee=Gone%20shop')
    await waitFor(() => expect(payeeSelect().value).toBe('p:Gone shop'))
    expect(payeeSelect().selectedOptions[0].textContent).toBe('Gone shop')
    cleanup()

    const both = renderEntries('/entries?payee=Gone%20shop&counterpartyId=21')
    await waitFor(() => expect(payeeSelect().value).toBe('c:21'))
    await waitFor(() => expect(both.some((c) => c.url.startsWith('/api/entries?'))).toBe(true))
    expect(both.filter((c) => c.url.startsWith('/api/entries?') || c.url.startsWith('/api/entries/summary'))
      .every((c) => !c.url.includes('payee='))).toBe(true)
    expect(calls.length).toBeGreaterThan(0)
  })

  it('chooses a counterparty whose name the URL’s payee has in another case, listed once, the URL kept (D-568)', async () => {
    const calls = renderEntries('/entries?payee=ALBERT%20HEIJN')
    await waitFor(() => expect(payeeSelect().value).toBe('c:21'))
    expect([...payeeSelect().options].filter((o) => o.textContent!.toLowerCase().startsWith('albert heijn')))
      .toHaveLength(1)
    expect(new URLSearchParams(where).get('payee')).toBe('ALBERT HEIJN')
    await waitFor(() => expect(calls.map((c) => c.url)).toContain('/api/entries/summary?payee=ALBERT+HEIJN'))
    expect(calls.some((c) => c.url.includes('counterpartyId='))).toBe(false)
  })

  it('asks no family payees with the family switch off', async () => {
    const calls = renderEntries('/entries', { ...TEST_ME, features: { familyLedgers: false } })
    await waitFor(() => expect([...payeeSelect().options].length).toBeGreaterThan(1))
    expect(calls.some((c) => c.url === '/api/entries/family-payees')).toBe(false)
    expect([...payeeSelect().options].map((o) => o.value)).not.toContain('p:Trefpunt Coffeeshop')
  })
})

describe('the payee a family form saves (D-549)', () => {
  it('sends nothing, removes, takes a counterparty or creates one, by the trimmed name against the payee it had', () => {
    expect(payeeChange('', '', counterparties)).toEqual({ kind: 'keep' })
    expect(payeeChange('  ', 'Ivan', counterparties)).toEqual({ kind: 'remove' })
    expect(payeeChange(' IVAN ', 'Ivan', counterparties)).toEqual({ kind: 'keep' })
    expect(payeeChange('albert heijn', 'Ivan', counterparties)).toEqual({ kind: 'id', id: 21 })
    expect(payeeChange('old BAKERY', '', counterparties)).toEqual({ kind: 'id', id: 24 })
    expect(payeeChange(' Trefpunt Coffeeshop ', '', counterparties)).toEqual({ kind: 'create', name: 'Trefpunt Coffeeshop' })
  })

  it('suggests the counterparties not archived and the family payees, one per name, sorted', () => {
    expect(payeeSuggestions(counterparties, familyPayees))
      .toEqual(['Albert Heijn', 'bakker Bart', 'Employer', 'Ivan', 'Trefpunt Coffeeshop'])
  })
})

describe('the payee field on a family record’s page (D-549)', () => {
  const ON: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }
  const home: FamilyLedger = {
    id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
    createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01',
  }
  const anna: FamilyMember = { id: 70, displayName: 'Anna', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null }
  const sam: FamilyMember = { id: 71, displayName: 'Sam', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: false, share: null }
  const ref = (member: FamilyMember) => ({ memberId: member.id, displayName: member.displayName })
  const record: FamilyRecord = {
    id: 5, type: 'EXPENSE', date: '2026-09-12', category: { id: 30, code: 'GROCERIES', name: 'Groceries', archived: false },
    amount: '10.01', currency: 'EUR', comment: null, payer: ref(anna), splitMethod: 'EQUAL',
    shares: [
      { member: ref(anna), amount: '5.01', basisPoints: null, updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z' },
      { member: ref(sam), amount: '5.00', basisPoints: null, updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z' },
    ],
    author: ref(anna), createdAt: '2026-09-12T10:00:00Z', updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z',
    version: 0, frozen: false, canEdit: true, canDelete: true, canEditPayment: true, refund: false,
    yourPayment: { entryId: 90, accountId: 1, accountName: 'Cash', later: false, amount: '10.01', currency: 'EUR', payeeId: 22 },
  }

  function page(answers: Record<string, Answer | Answer[]> = {}) {
    const calls = stubApi({
      'GET /api/family-ledgers': { status: 200, body: [home] },
      'GET /api/family-ledgers/7': { status: 200, body: home },
      'GET /api/family-ledgers/7/members': { status: 200, body: [anna, sam] },
      'GET /api/family-ledgers/7/categories': { status: 200, body: [{ id: 30, code: 'GROCERIES', name: 'Groceries', type: 'EXPENSE', archived: false }] },
      'GET /api/accounts': { status: 200, body: testLedger.accounts },
      'GET /api/counterparties': { status: 200, body: counterparties },
      'GET /api/entries/family-payees': { status: 200, body: familyPayees },
      'GET /api/family-ledgers/7/records/5': { status: 200, body: record },
      'GET /api/family-ledgers/7/journal?recordId=5&size=200': { status: 200, body: { content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 } },
      ...answers,
    })
    render(<MemoryRouter initialEntries={['/family/7/expenses/5']}><App me={ON} /></MemoryRouter>)
    return calls
  }
  const payeeField = () => screen.getByLabelText(/^Payee/) as HTMLInputElement
  const save = () => screen.getByRole('button', { name: 'Save the changes' })
  const patches = (calls: Call[]) => calls.filter((c) => c.method === 'PATCH').map((c) => c.body)

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 8, 30, 12))
    vi.stubGlobal('confirm', () => true)
  })

  it('opens with the current payee’s name, suggests the family payees, and sends nothing for the same name', async () => {
    page()
    await waitFor(() => expect(payeeField().value).toBe('Ivan'))
    const list = document.getElementById(payeeField().getAttribute('list')!)!
    await waitFor(() => expect([...list.querySelectorAll('option')].map((o) => o.value))
      .toEqual(['Albert Heijn', 'bakker Bart', 'Employer', 'Ivan', 'Trefpunt Coffeeshop']))
    fireEvent.change(payeeField(), { target: { value: ' ivan ' } })
    expect(save()).toHaveProperty('disabled', true)
    expect(screen.queryByText(/New payee: it is added when you save/)).toBeNull()
  })

  it('removes the payee when emptied, and takes a counterparty of the user’s by its id', async () => {
    const calls = page({ 'PATCH /api/family-ledgers/7/records/5?version=0': { status: 200, body: record } })
    await waitFor(() => expect(payeeField().value).toBe('Ivan'))
    fireEvent.change(payeeField(), { target: { value: '' } })
    fireEvent.click(save())
    await waitFor(() => expect(patches(calls)).toEqual([{ payeeId: null }]))
    fireEvent.change(payeeField(), { target: { value: 'OLD BAKERY' } })
    fireEvent.click(save())
    await waitFor(() => expect(patches(calls)).toEqual([{ payeeId: null }, { payeeId: 24 }]))
  })

  it('creates a new name as the user’s counterparty first, once, also when the save after it fails', async () => {
    const calls = page({
      'POST /api/counterparties': { status: 201, body: { id: 40, name: 'Trefpunt Coffeeshop', kind: null, archived: false, lastCategoryId: null } },
      'PATCH /api/family-ledgers/7/records/5?version=0': [
        { status: 409, body: { status: 409, detail: 'Someone changed it' } },
        { status: 200, body: record },
      ],
    })
    await waitFor(() => expect(payeeField().value).toBe('Ivan'))
    fireEvent.change(payeeField(), { target: { value: ' Trefpunt Coffeeshop ' } })
    expect(screen.getByText(/New payee: it is added when you save\./)).toBeDefined()
    fireEvent.click(save())
    await waitFor(() => expect(patches(calls)).toEqual([{ payeeId: 40 }]))
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ name: 'Trefpunt Coffeeshop' })
    await screen.findByText(/Someone changed it/)
    // The form keeps the typed name; now a counterparty of the user's, it is sent by its id again, not created twice.
    expect(payeeField().value).toBe(' Trefpunt Coffeeshop ')
    fireEvent.click(save())
    await waitFor(() => expect(patches(calls)).toEqual([{ payeeId: 40 }, { payeeId: 40 }]))
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1)
  })
})

describe('New entry’s payee suggestions (D-550)', () => {
  it('suggests the family payees too, one per name', async () => {
    stubApi({ 'GET /api/entries/family-payees': { status: 200, body: familyPayees } })
    render(
      <WithMe>
        <EntryFormView ledger={{ ...testLedger, counterparties }} initial={newForm(testLedger, '2026-09-25', 'expense')} onSave={async () => {}} />
      </WithMe>,
    )
    const payee = screen.getByLabelText('Payee') as HTMLInputElement
    const list = document.getElementById(payee.getAttribute('list')!)!
    await waitFor(() => expect([...list.querySelectorAll('option')].map((o) => o.value))
      .toEqual(['Albert Heijn', 'bakker Bart', 'Employer', 'Ivan', 'Trefpunt Coffeeshop']))
    fireEvent.change(payee, { target: { value: 'Trefpunt Coffeeshop' } })
    expect(screen.getByText('New payee: it is added when you save.')).toBeDefined()
  })
})

/** The rules of `index.css` inside its media query for screens up to 40rem, as text. */
const narrow = () => css.split('@media (max-width: 40rem)').slice(1).join('\n')

describe('the layout fixes (D-552), as far as the stylesheet tells', () => {
  it('puts a compared account’s, total’s and person’s figures in two columns under the name under 40rem', () => {
    expect(narrow()).toMatch(/table\.accounts\.compared > tbody > tr, table\.accounts\.compared > tfoot > tr,\s+table\.accounts\.compared table\.nested > tbody > tr \{ display: grid; grid-template-columns: minmax\(0, 1fr\) minmax\(0, 1fr\);/)
    expect(narrow()).toMatch(/> tr > :not\(\[data-label\]\)[^{]*\{ grid-column: 1 \/ -1; \}/)
    // At 1280 px nothing of it: the grid is only under 40rem.
    expect(css.split('@media (max-width: 40rem)')[0]).not.toContain('table.accounts.compared')
  })

  it('centres a category’s name on the line of its buttons', () => {
    expect(css.split('@media (max-width: 40rem)')[0]).toContain('table.categories td { vertical-align: middle; }')
    expect(narrow()).toMatch(/table\.categories tr \{ display: flex; flex-wrap: wrap; align-items: center;/)
  })
})
