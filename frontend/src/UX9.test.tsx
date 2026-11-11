import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Category, Entry, EntryPage, FamilyLedger, FamilyMember, FamilyRecord, FamilyRecordPage, Me } from './api'
import { testLedger } from './testLedger'

// UX-9: a family payment's and receipt's category in Entries (D-477); the payees a member chooses to show in a family
// budget (D-479 to D-482): the Settings section, the Activity line, the record's page and the hints that follow it.

type Answer = { status: number; body?: unknown }
type Call = { method: string; url: string; body: unknown }

/** Answers `METHOD url` exactly, else by the path without its query; anything else 404. */
function stubApi(answers: Record<string, Answer | Answer[]>) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ method, url, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    const listed = answers[`${method} ${url}`] ?? answers[`${method} ${url.split('?')[0]}`]
    const answer = (Array.isArray(listed) ? (listed.length > 1 ? listed.shift() : listed[0]) : listed)
      ?? { status: 404, body: { status: 404, detail: `No static resource ${url.slice(1)}.` } }
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
      status: answer.status,
      headers: { 'Content-Type': answer.status >= 400 ? 'application/problem+json' : 'application/json' },
    })
  }))
  return calls
}

const ON: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }
const home: FamilyLedger = {
  id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
  createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01', showsPayees: false,
}
const anna: FamilyMember = { id: 70, displayName: 'Anna', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null, showsPayees: false }
const ben: FamilyMember = { id: 71, displayName: 'Ben', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null, showsPayees: true }
const sam: FamilyMember = { id: 72, displayName: 'Sam', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: false, share: null }
const left: FamilyMember = { id: 73, displayName: 'Lea', role: 'MEMBER', status: 'LEFT', joinDate: '2026-09-01', hasAccount: true, share: null, leftDate: '2026-09-20' }
const familyCategories: Category[] = [{ id: 30, code: 'GROCERIES', name: 'Groceries', type: 'EXPENSE', archived: false }]
const accounts: Account[] = testLedger.accounts

const ref = (member: FamilyMember) => ({ memberId: member.id, displayName: member.displayName })
const at = '2026-09-12T10:00:00Z'
/** Anna's expense, paid from her cash at Odido. */
const annas: FamilyRecord = {
  id: 5, type: 'EXPENSE', date: '2026-09-12', category: { id: 30, code: 'GROCERIES', name: 'Groceries', archived: false },
  amount: '10.00', currency: 'EUR', comment: null, payer: ref(anna), splitMethod: 'EQUAL',
  shares: [
    { member: ref(anna), amount: '5.00', basisPoints: null, updatedBy: ref(anna), updatedAt: at },
    { member: ref(ben), amount: '5.00', basisPoints: null, updatedBy: ref(anna), updatedAt: at },
  ],
  author: ref(anna), createdAt: at, updatedBy: ref(anna), updatedAt: at,
  version: 0, frozen: false, canEdit: true, canDelete: true, canEditPayment: true, refund: false,
  yourPayment: { entryId: 90, accountId: 1, accountName: 'Cash', later: false, amount: '10.00', currency: 'EUR', payeeId: 1 },
  paymentPayee: 'Odido',
}
/** Ben's expense as Anna reads it: no `yourPayment`, and his payee only while he shows it. */
const bens: FamilyRecord = {
  ...annas, id: 6, payer: ref(ben), canEditPayment: false, canDelete: false, yourPayment: undefined,
  paymentPayee: 'Bakery',
}
const empty = { content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }

function budget(ledger: FamilyLedger, answers: Record<string, Answer | Answer[]> = {}) {
  return stubApi({
    'GET /api/family-ledgers': { status: 200, body: [ledger] },
    'GET /api/family-ledgers/7': { status: 200, body: ledger },
    'GET /api/family-ledgers/7/members': { status: 200, body: [anna, ben, sam, left] },
    'GET /api/family-ledgers/7/categories': { status: 200, body: familyCategories },
    'GET /api/family-ledgers/7/journal': { status: 200, body: empty },
    'GET /api/family-ledgers/7/balances': { status: 404 },
    'GET /api/accounts': { status: 200, body: accounts },
    'GET /api/counterparties': { status: 200, body: testLedger.counterparties },
    'GET /api/categories': { status: 200, body: testLedger.categories },
    'GET /api/settings': { status: 200, body: testLedger.settings },
    ...answers,
  })
}

function renderApp(path: string) {
  render(<MemoryRouter initialEntries={[path]}><App me={ON} /></MemoryRouter>)
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 8, 30, 12))
  try { localStorage.clear() } catch { /* not in this environment */ }
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('a family payment’s category in Entries (D-477)', () => {
  const debt = { id: 30, code: 'FAMILY_DEBT_7', name: 'Debt to family budget: Home', type: 'LIABILITY' as const,
    defaultCurrency: 'EUR', requiresCounterparty: false, system: true, archived: false }
  const family = { ledgerId: 7, ledgerName: 'Home', recordId: 3, readOnly: true }
  const payment: Entry = {
    id: 91, version: 0, entryDate: '2026-09-10', kind: 'FAMILY_PAYMENT', payeeId: null, memo: null,
    postings: [
      { accountId: 1, currency: 'EUR', amount: '-10.00', categoryId: null, counterpartyId: null },
      { accountId: 30, currency: 'EUR', amount: '10.00', categoryId: null, counterpartyId: null },
    ],
    family: { ...family, link: 'PAYMENT', recordType: 'EXPENSE', recordCategory: { id: 52, name: 'Электроника' } },
  }
  const receipt: Entry = {
    ...payment, id: 92,
    postings: [
      { accountId: 1, currency: 'EUR', amount: '90.00', categoryId: null, counterpartyId: null },
      { accountId: 30, currency: 'EUR', amount: '-90.00', categoryId: null, counterpartyId: null },
    ],
    family: { ...family, recordId: 4, link: 'PAYMENT', recordType: 'INCOME', recordCategory: { id: 53, name: 'Salary' } },
  }
  const settlement: Entry = {
    ...payment, id: 93, kind: 'FAMILY_SETTLEMENT',
    family: { ...family, recordId: 5, link: 'SETTLEMENT', recordType: 'SETTLEMENT' },
  }
  // An answer of the api before UX-9: no recordCategory.
  const older: Entry = { ...payment, id: 94, family: { ...family, link: 'PAYMENT', recordType: 'EXPENSE' } }

  /** An element's text as the eye reads it: the icon's name left out. */
  const seen = (element: Element) => {
    const copy = element.cloneNode(true) as Element
    copy.querySelectorAll('svg').forEach((svg) => svg.remove())
    return copy.textContent ?? ''
  }
  const cell = (row: HTMLElement) => {
    const category = within(row).getAllByRole('cell')[2]
    const name = category.querySelector('.category-name')!
    return {
      category: name.textContent, muted: name.querySelector('.muted') !== null,
      family: seen(category.querySelector('.family-line')!), amount: within(row).getAllByRole('cell')[4].textContent,
    }
  }

  it('shows the record’s category, not muted, with the kind and the budget on the line under it', async () => {
    const page: EntryPage = { content: [payment, receipt, settlement, older], page: 0, size: 50, totalElements: 4, totalPages: 1 }
    stubApi({
      'GET /api/family-ledgers': { status: 200, body: [home] },
      'GET /api/accounts': { status: 200, body: [...accounts, debt] },
      'GET /api/categories': { status: 200, body: testLedger.categories },
      'GET /api/counterparties': { status: 200, body: testLedger.counterparties },
      'GET /api/settings': { status: 200, body: testLedger.settings },
      'GET /api/entries': { status: 200, body: page },
    })
    renderApp('/entries')
    const rows = within(await screen.findByRole('table')).getAllByRole('row').slice(1)
    const [paymentRow, receiptRow, settlementRow, olderRow] = rows
    expect(cell(paymentRow)).toEqual({ category: 'Электроника', muted: false, family: 'Family payment · Home', amount: '€10.00' })
    expect(within(paymentRow).getByRole('img', { name: 'Shared: Home' })).toBeDefined()
    expect(cell(receiptRow)).toEqual({ category: 'Salary', muted: false, family: 'Family income received · Home', amount: '€90.00' })
    // A settlement and an answer without the field: as before, the kind muted in the cell, the line the budget alone.
    expect(cell(settlementRow)).toMatchObject({ category: 'Family settlement', muted: true, family: 'Home' })
    expect(cell(olderRow)).toMatchObject({ category: 'Family payment', muted: true, family: 'Home' })
  })
})

describe('“Your payees” on the budget’s Settings tab (D-482)', () => {
  const checkbox = () => screen.getByRole('checkbox', { name: 'Show the other members where I paid' }) as HTMLInputElement
  const patches = (calls: Call[]) => calls.filter((c) => c.method === 'PATCH')

  it('asks first, saves on OK and says so, and lists who shows payees', async () => {
    const asked: string[] = []
    vi.stubGlobal('confirm', (question: string) => { asked.push(question); return true })
    const calls = budget(home, {
      'PATCH /api/family-ledgers/7/me': { status: 200, body: { ...home, showsPayees: true } },
    })
    renderApp('/family/7/settings')
    expect(await screen.findByRole('heading', { name: 'Your payees' })).toBeDefined()
    expect(checkbox().checked).toBe(false)
    expect(screen.getByText(/The payee of each family expense and income you paid or received, past ones included\. Your accounts, notes and paying amounts stay private\./)).toBeDefined()
    // Each ACTIVE member with an account, the reader marked; not Sam (no account) nor Lea (left).
    const who = screen.getByRole('heading', { name: 'Who shows payees' }).nextElementSibling!
    expect(within(who as HTMLElement).getAllByRole('listitem').map((li) => li.textContent))
      .toEqual(['Anna (you): Hidden', 'Ben: Shown'])

    fireEvent.click(checkbox())
    expect(asked).toEqual(['Show your payees to the other members of “Home”? They will see where you paid for every family expense and income you paid or received in this budget, past ones included.'])
    await waitFor(() => expect(patches(calls)).toHaveLength(1))
    expect(patches(calls)[0]).toEqual({ method: 'PATCH', url: '/api/family-ledgers/7/me', body: { showsPayees: true } })
    expect(await screen.findByText('Saved.')).toBeDefined()
    expect(checkbox().checked).toBe(true)
  })

  it('a member who isn’t an owner has it too, below the facts, and switches off after asking', async () => {
    const asked: string[] = []
    vi.stubGlobal('confirm', (question: string) => { asked.push(question); return true })
    const asMember = { ...home, role: 'MEMBER' as const, showsPayees: true }
    const calls = budget(asMember, {
      'PATCH /api/family-ledgers/7/me': { status: 200, body: { ...asMember, showsPayees: false } },
    })
    renderApp('/family/7/settings')
    expect(await screen.findByText('Only an owner of the family budget changes its name and base currency.')).toBeDefined()
    expect(checkbox().checked).toBe(true)
    fireEvent.click(checkbox())
    expect(asked).toEqual(['Hide your payees from the other members of “Home”? They will no longer see them, past records included.'])
    await waitFor(() => expect(patches(calls)).toEqual([{ method: 'PATCH', url: '/api/family-ledgers/7/me', body: { showsPayees: false } }]))
    expect(await screen.findByText('Saved.')).toBeDefined()
    expect(checkbox().checked).toBe(false)
  })

  it('Cancel sends nothing and keeps the box as it was, both ways', async () => {
    vi.stubGlobal('confirm', () => false)
    for (const shown of [false, true]) {
      const calls = budget({ ...home, showsPayees: shown })
      renderApp('/family/7/settings')
      await screen.findByRole('heading', { name: 'Your payees' })
      fireEvent.click(checkbox())
      expect(checkbox().checked).toBe(shown)
      await new Promise((resolve) => setTimeout(resolve, 0))
      expect(patches(calls)).toEqual([])
      expect(screen.queryByText('Saved.')).toBeNull()
      cleanup()
    }
  })

  it('a failure says so and puts the box back', async () => {
    vi.stubGlobal('confirm', () => true)
    budget(home, {
      'PATCH /api/family-ledgers/7/me': { status: 500, body: { status: 500, detail: 'Something went wrong.' } },
    })
    renderApp('/family/7/settings')
    await screen.findByRole('heading', { name: 'Your payees' })
    fireEvent.click(checkbox())
    expect(await screen.findByText(/Something went wrong/)).toBeDefined()
    expect(checkbox().checked).toBe(false)
    expect(screen.queryByText('Saved.')).toBeNull()
  })
})

describe('the payee on Activity and the record’s page (D-481)', () => {
  const page = (records: FamilyRecord[]): FamilyRecordPage =>
    ({ content: records, page: 0, size: 20, totalElements: records.length, totalPages: 1 })
  const facts = () => {
    const list = document.querySelector('dl.facts')!
    const pairs: Record<string, string> = {}
    list.querySelectorAll('dt').forEach((dt) => { pairs[dt.textContent!] = dt.nextElementSibling!.textContent! })
    return pairs
  }

  it('lists the payee after who paid, for the payer and for a payer who shows it; nothing without it', async () => {
    const hidden: FamilyRecord = { ...bens, id: 8, paymentPayee: undefined }
    budget(home, { 'GET /api/family-ledgers/7/records': { status: 200, body: page([annas, bens, hidden]) } })
    renderApp('/family/7/expenses')
    const rows = within(await screen.findByRole('table')).getAllByRole('row').slice(1)
    expect(rows.map((r) => r.querySelector('.small.muted')!.textContent)).toEqual([
      'Paid by you · Odido', 'Paid by Ben · Bakery', 'Paid by Ben',
    ])
  })

  it('the payer’s own payee: “Only you see it.” while hidden, “The other members see it.” while shown', async () => {
    for (const [shown, note] of [[false, 'Only you see it.'], [true, 'The other members see it.']] as const) {
      budget({ ...home, showsPayees: shown }, { 'GET /api/family-ledgers/7/records/5': { status: 200, body: annas } })
      renderApp('/family/7/expenses/5')
      await screen.findByText('Paid from', { selector: 'dt' })
      expect(facts().Payee).toBe('Odido' + note)
      // Once: the record's line, not a second one under the account.
      expect(screen.getAllByText('Payee', { selector: 'dt' })).toHaveLength(1)
      cleanup()
    }
  })

  it('another member’s payee: “Shown by <payer>.”, and no line without it', async () => {
    budget(home, {
      'GET /api/family-ledgers/7/records/6': { status: 200, body: bens },
      'GET /api/family-ledgers/7/records/8': { status: 200, body: { ...bens, id: 8, paymentPayee: undefined } },
    })
    renderApp('/family/7/expenses/6')
    await screen.findByText('Paid by', { selector: 'dt' })
    expect(facts().Payee).toBe('BakeryShown by Ben.')
    cleanup()
    renderApp('/family/7/expenses/8')
    await screen.findByText('Paid by', { selector: 'dt' })
    expect(facts().Payee).toBeUndefined()
  })
})

describe('the texts that promise the payee stays private follow the reader’s switch (D-482)', () => {
  const hint = () => screen.getByText('Payee (optional)', { selector: '.label' }).closest('label')!.textContent

  it('the new expense’s payee hint', async () => {
    for (const [shown, words] of [
      [false, 'the other members never see it.'],
      [true, 'the other members of this budget see its name, as you chose in its settings.'],
    ] as const) {
      budget({ ...home, showsPayees: shown })
      renderApp('/family/7/expenses/new')
      await screen.findByText('Payee (optional)', { selector: '.label' })
      expect(hint()).toContain(words)
      cleanup()
    }
  })

  it('the record page’s payment form', async () => {
    budget({ ...home, showsPayees: true }, { 'GET /api/family-ledgers/7/records/5': { status: 200, body: annas } })
    renderApp('/family/7/expenses/5')
    await screen.findByText('Payee (optional)', { selector: '.label' })
    expect(hint()).toContain('the other members of this budget see its name')
  })
})

describe('New entry’s family path and the payment’s own page follow the switch (D-482)', () => {
  const payment: Entry = {
    id: 91, version: 2, entryDate: '2026-09-12', kind: 'FAMILY_PAYMENT', payeeId: null, memo: null,
    postings: [
      { accountId: 1, currency: 'EUR', amount: '-10.00', categoryId: null, counterpartyId: null },
      { accountId: 40, currency: 'EUR', amount: '10.00', categoryId: null, counterpartyId: null },
    ],
    family: { ledgerId: 7, ledgerName: 'Home', recordId: 5, link: 'PAYMENT', readOnly: true },
  }
  const noEntries = { content: [], page: 0, size: 1, totalElements: 0, totalPages: 0 }

  it('New entry’s family expense names the payee as only the reader’s, or as the others’ too', async () => {
    for (const [shown, label] of [[false, /^Payee \(only you see it\)/], [true, /^Payee \(the other members see it\)/]] as const) {
      budget({ ...home, showsPayees: shown }, { 'GET /api/entries?size=50': { status: 200, body: noEntries } })
      renderApp('/entries/new')
      fireEvent.click(await screen.findByRole('switch', { name: 'Family expense' }))
      await waitFor(() => expect(screen.queryByLabelText(label)).not.toBeNull())
      cleanup()
    }
  })

  it('the payment’s own page says whether the others see the payee', async () => {
    for (const [shown, words] of [
      [false, 'the other members never see it.'],
      [true, 'the other members of this budget see its name, as you chose in its settings.'],
    ] as const) {
      budget({ ...home, showsPayees: shown }, {
        'GET /api/entries/91': { status: 200, body: payment },
        'GET /api/family-ledgers/7/records/5': { status: 200, body: annas },
      })
      renderApp('/entries/91')
      const label = await screen.findByText('Payee (optional)', { selector: '.label' })
      expect(label.closest('label')!.textContent).toContain(words)
      cleanup()
    }
  })
})
