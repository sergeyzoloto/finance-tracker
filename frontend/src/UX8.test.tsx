import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { formatDate, type Category, type Entry, type FamilyLedger, type FamilyMember, type FamilyRecord, type Me } from './api'
import { newForm, type EntryForm } from './entryForm'
import SimilarEntries, { SIMILAR_DELAY_MS } from './SimilarEntries'
import {
  copyFamily, copyPersonal, copySplit, criteriaOf, matchesQuery, type Criteria, type EntryMatch, type EntryMatchPage,
} from './similar'
import { testLedger } from './testLedger'

// UX-8: New entry's similar entries (D-453 to D-460), Copy (D-461), "Save and add another" keeping the form (D-462),
// and the user's button under 40rem (D-464).

type Answer = { status: number; body?: unknown }
type Call = { method: string; url: string; body: unknown }

const ON: Me = { name: 'Anna Example', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }
const OFF: Me = { ...ON, features: { familyLedgers: false } }
const home: FamilyLedger = {
  id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
  createdAt: '2026-09-01T10:00:00Z', startDate: '2026-09-01',
}
const anna: FamilyMember = { id: 70, displayName: 'Anna', role: 'OWNER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: true, share: null }
const sam: FamilyMember = { id: 71, displayName: 'Sam', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: false, share: null }
const kim: FamilyMember = { id: 72, displayName: 'Kim', role: 'MEMBER', status: 'ACTIVE', joinDate: '2026-09-01', hasAccount: false, share: null }
const familyCategories: Category[] = [
  { id: 30, code: 'GROCERIES_F', name: 'Food', type: 'EXPENSE', archived: false },
  { id: 31, code: 'OLD_F', name: 'Old family', type: 'EXPENSE', archived: true },
  { id: 33, code: 'SALARY_F', name: 'Family salary', type: 'INCOME', archived: false },
]
const accounts = [
  ...testLedger.accounts,
  { id: 40, code: 'FAMILY_DEBT_7', name: 'Debt to family budget: Home', type: 'LIABILITY' as const, defaultCurrency: 'EUR', requiresCounterparty: false, system: true, archived: false },
]
const categories: Category[] = [
  ...testLedger.categories,
  { id: 14, code: 'OLD', name: 'Old category', type: 'EXPENSE', archived: true },
  // The personal list holds the family categories of the user's budgets (D-11).
  { ...familyCategories[0], familyLedgerId: 7, familyLedgerName: 'Home' } as Category,
]
const ref = (member: FamilyMember) => ({ memberId: member.id, displayName: member.displayName })

/** A personal expense: Cash → Eating out, 11.35, at Albert Heijn. */
const expense: Entry = {
  id: 50, version: 0, entryDate: '2026-09-20', kind: 'EXPENSE', payeeId: 21, memo: 'Weekly',
  postings: [
    { accountId: 1, currency: 'EUR', amount: '-11.35', categoryId: null, counterpartyId: null },
    { accountId: 6, currency: 'EUR', amount: '11.35', categoryId: 13, counterpartyId: null },
  ],
  family: null,
}
const expenseMatch: EntryMatch = { entry: expense, amount: '11.35', currency: 'EUR', categoryId: 13, refund: false }
/** A personal income: Salary → ING, 2000. */
const income: Entry = {
  id: 51, version: 0, entryDate: '2026-09-25', kind: 'INCOME', payeeId: 23, memo: 'September',
  postings: [
    { accountId: 6, currency: 'EUR', amount: '-2000', categoryId: 11, counterpartyId: null },
    { accountId: 2, currency: 'EUR', amount: '2000', categoryId: null, counterpartyId: null },
  ],
  family: null,
}
/** An entry whose postings don't fit its tab (a third line): it opens in Advanced. */
const odd: Entry = {
  ...expense, id: 52, postings: [
    { accountId: 1, currency: 'EUR', amount: '-5', categoryId: null, counterpartyId: null },
    { accountId: 2, currency: 'EUR', amount: '-5', categoryId: null, counterpartyId: null },
    { accountId: 6, currency: 'EUR', amount: '10', categoryId: 13, counterpartyId: null },
  ],
}
/** An expense from an archived account in an archived category. */
const archived: Entry = {
  ...expense, id: 53, postings: [
    { accountId: 10, currency: 'EUR', amount: '-3', categoryId: null, counterpartyId: null },
    { accountId: 6, currency: 'EUR', amount: '3', categoryId: 14, counterpartyId: null },
  ],
}
/** The user's payment of Home's record 5: ING → the debt account, 30.00, paid at Albert Heijn. */
const payment: Entry = {
  id: 91, version: 0, entryDate: '2026-09-12', kind: 'FAMILY_PAYMENT', payeeId: 21, memo: 'Card 4',
  postings: [
    { accountId: 2, currency: 'EUR', amount: '-30', categoryId: null, counterpartyId: null },
    { accountId: 40, currency: 'EUR', amount: '30', categoryId: null, counterpartyId: null },
  ],
  family: { ledgerId: 7, ledgerName: 'Home', recordId: 5, link: 'PAYMENT', readOnly: true, recordType: 'EXPENSE' },
}
const paymentMatch: EntryMatch = { entry: payment, amount: '30.00', currency: 'EUR', categoryId: 30, refund: false }
const share = (member: FamilyMember, amount: string, basisPoints: number | null = null) => ({
  member: ref(member), amount, basisPoints, updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z',
})
const record: FamilyRecord = {
  id: 5, type: 'EXPENSE', date: '2026-09-12', category: { id: 30, code: 'GROCERIES_F', name: 'Food', archived: false },
  amount: '30.00', currency: 'EUR', comment: 'Market', payer: ref(anna), splitMethod: 'EQUAL',
  shares: [share(anna, '15.00'), share(sam, '15.00')],
  author: ref(anna), createdAt: '2026-09-12T10:00:00Z', updatedBy: ref(anna), updatedAt: '2026-09-12T10:00:00Z',
  version: 0, frozen: false, canEdit: true, canDelete: true, canEditPayment: true,
  yourPayment: { entryId: 91, accountId: 2, accountName: 'ING', later: false, amount: '30.00', currency: 'EUR', payeeId: 21 },
}
const page = (content: EntryMatch[], total = content.length, number = 0): EntryMatchPage =>
  ({ content, page: number, size: 10, totalElements: total, totalPages: Math.ceil(total / 10) })

/**
 * Answers the app's requests: the ledger, Home and the similar entries (by `matches`, from the request's query), and
 * records every request with its JSON body.
 */
function stubApi({ me = ON, matches = () => ({ status: 200, body: page([]) }), answers = {}, members = [anna, sam], families = [home] }: {
  me?: Me
  matches?: (query: URLSearchParams) => Answer | Promise<Answer>
  answers?: Record<string, Answer>
  members?: FamilyMember[]
  families?: FamilyLedger[]
} = {}) {
  const calls: Call[] = []
  const fixed: Record<string, Answer> = {
    'GET /api/me': { status: 200, body: me },
    'GET /api/accounts': { status: 200, body: accounts },
    'GET /api/categories': { status: 200, body: categories },
    'GET /api/counterparties': { status: 200, body: testLedger.counterparties },
    'GET /api/settings': { status: 200, body: testLedger.settings },
    'GET /api/family-ledgers': { status: 200, body: families },
    'GET /api/family-ledgers/7/members': { status: 200, body: members },
    'GET /api/family-ledgers/7/categories': { status: 200, body: familyCategories },
    'GET /api/family-ledgers/7/records/5': { status: 200, body: record },
    'POST /api/entries': { status: 201, body: expense },
    'POST /api/family-ledgers/7/records': { status: 201, body: record },
    ...answers,
  }
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ method, url, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    const answer = url.startsWith('/api/entries/matches?')
      ? await matches(new URLSearchParams(url.slice(url.indexOf('?') + 1)))
      : fixed[`${method} ${url}`] ?? { status: 404, body: { status: 404, detail: `No static resource ${url.slice(1)}.` } }
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
      status: answer.status,
      headers: { 'Content-Type': answer.status >= 400 ? 'application/problem+json' : 'application/json' },
    })
  }))
  return calls
}

const matchCalls = (calls: Call[]) => calls.filter((c) => c.url.startsWith('/api/entries/matches?'))
  .map((c) => Object.fromEntries(new URLSearchParams(c.url.slice(c.url.indexOf('?') + 1))))

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function renderApp(path = '/entries/new', me: Me = ON) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App me={me} />
      <Routes><Route path="*" element={<Where />} /></Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 8, 30, 12))
  vi.stubGlobal('confirm', () => true)
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const form = (patch: Partial<EntryForm> = {}): EntryForm => ({ ...newForm(testLedger, '2026-09-30'), ...patch })

describe('the criteria (D-456, D-458, D-459)', () => {
  it('counts a text from 2 characters, trimmed, and the amount as parseAmount writes it, without its sign', () => {
    expect(criteriaOf(form(), false)).toBeUndefined()
    expect(criteriaOf(form({ payee: ' A ' }), false)).toBeUndefined()
    expect(criteriaOf(form({ payee: ' Al ' }), false)).toEqual({ type: 'EXPENSE', payee: 'Al' })
    expect(criteriaOf(form({ memo: 'пр' }), false)).toEqual({ type: 'EXPENSE', memo: 'пр' })
    expect(criteriaOf(form({ amount: '12,50' }), false)).toEqual({ type: 'EXPENSE', amount: '12.5', currency: 'EUR' })
    expect(criteriaOf(form({ amount: '-3' }), false)).toMatchObject({ amount: '3' })
    expect(criteriaOf(form({ amount: '1 001' }), false)).toMatchObject({ amount: '1001' })
    // Text that isn't an amount is no criterion; nor is a currency without an amount, nor a code that isn't one.
    expect(criteriaOf(form({ amount: 'abc' }), false)).toBeUndefined()
    expect(criteriaOf(form({ amount: '5', currency: 'E' }), false)).toEqual({ type: 'EXPENSE', amount: '5' })
    expect(criteriaOf(form({ accountId: '1', tab: 'income' }), false)).toEqual({ type: 'INCOME', accountId: '1' })
  })

  it('looks on Expense and Income only', () => {
    for (const tab of ['transfer', 'loan', 'exchange', 'advanced'] as const) {
      expect(criteriaOf(form({ tab, payee: 'Albert', amount: '5' }), true)).toBeUndefined()
    }
  })

  it('counts the category once the user picked it, not while it is the payee’s last one', () => {
    expect(criteriaOf(form({ categoryId: '12' }), false)).toBeUndefined()
    expect(criteriaOf(form({ categoryId: '12' }), true)).toEqual({ type: 'EXPENSE', categoryId: '12' })
  })

  it('takes the family fields as the form shows them: the family category, the note, no payer of a family income', () => {
    const family = form({ familyId: '7', familyCategoryId: '30', categoryId: '12', memo: 'Card 4', payee: 'Albert' })
    expect(criteriaOf(family, true)).toEqual({ type: 'EXPENSE', payee: 'Albert', memo: 'Card 4', categoryId: '30' })
    expect(criteriaOf({ ...family, tab: 'income' }, true)).toEqual({ type: 'INCOME', memo: 'Card 4', categoryId: '30' })
  })

  it('asks for 10 at a time', () => {
    expect(matchesQuery({ type: 'EXPENSE', payee: 'Albert Heijn', amount: '12.5', currency: 'EUR' }, 2))
      .toBe('/entries/matches?type=EXPENSE&payee=Albert+Heijn&amount=12.5&currency=EUR&page=2&size=10')
  })
})

describe('what Copy puts in (D-461)', () => {
  it('copies a personal expense as formFromEntry opens it, keeping the form’s date', () => {
    const copied = copyPersonal(form({ familyId: '7' }), expense, testLedger)
    expect(copied.simple).toBe(true)
    expect(copied.form).toMatchObject({
      tab: 'expense', date: '2026-09-30', payee: 'Albert Heijn', accountId: '1', categoryId: '13', amount: '11.35',
      currency: 'EUR', memo: 'Weekly', refund: false, familyId: '',
    })
  })

  it('keeps the form’s amount and currency when Amount holds a valid amount', () => {
    expect(copyPersonal(form({ amount: '12,40', currency: 'USD' }), expense, testLedger).form)
      .toMatchObject({ amount: '12,40', currency: 'USD', payee: 'Albert Heijn' })
    expect(copyPersonal(form({ amount: 'x' }), expense, testLedger).form).toMatchObject({ amount: '11.35', currency: 'EUR' })
  })

  it('copies an income', () => {
    expect(copyPersonal(form(), income, testLedger).form).toMatchObject({
      tab: 'income', payee: 'Employer', accountId: '2', categoryId: '11', amount: '2000', memo: 'September',
    })
  })

  it('opens an entry whose postings don’t fit its tab in Advanced, keeping only the date', () => {
    const copied = copyPersonal(form({ amount: '7' }), odd, testLedger)
    expect(copied.simple).toBe(false)
    expect(copied.form.tab).toBe('advanced')
    expect(copied.form.date).toBe('2026-09-30')
    expect(copied.form.postings.map((p) => p.amount)).toEqual(['-5', '-5', '10'])
  })

  it('leaves an archived account and category out, saying why', () => {
    const ledger = { ...testLedger, categories }
    const copied = copyPersonal(form(), archived, ledger)
    expect(copied.form.accountId).toBe('')
    expect(copied.form.categoryId).toBe('')
    expect(copied.notes.accountId).toMatch(/archived/)
    expect(copied.notes.categoryId).toMatch(/archived/)
  })

  it('splits a copied family record as a new one: each method as stored, EQUAL by the budget’s rule now', () => {
    const members = [anna, sam, kim]
    expect(copySplit({ ...record, splitMethod: 'ONE_MEMBER', shares: [share(sam, '30.00'), share(anna, '0.00')] }, members, 'EQUAL', '2026-09-30'))
      .toMatchObject({ mode: 'ONE_MEMBER', member: '71' })
    expect(copySplit({ ...record, splitMethod: 'PERCENT', shares: [share(anna, '18.00', 6000), share(sam, '12.00', 4000)] }, members, 'EQUAL', '2026-09-30'))
      .toMatchObject({ mode: 'PERCENT', percents: { 70: '60.00', 71: '40.00', 72: '0.00' } })
    expect(copySplit({ ...record, splitMethod: 'AMOUNT', shares: [share(anna, '20.00'), share(sam, '10.00')] }, members, 'EQUAL', '2026-09-30'))
      .toMatchObject({ mode: 'AMOUNT', amounts: { 70: '20.00', 71: '10.00', 72: '0.00' } })
    expect(copySplit(record, members, 'EQUAL', '2026-09-30')).toMatchObject({ mode: 'RULE' })
    // Under a custom rule now, equal percentages among those who share the form's date: 33.34, 33.33, 33.33.
    expect(copySplit(record, members, 'CUSTOM', '2026-09-30'))
      .toMatchObject({ mode: 'PERCENT', percents: { 70: '33.34', 71: '33.33', 72: '33.33' } })
  })

  it('copies a family row into the family expense: budget, category, comment, account, payee, note, refund', () => {
    const ledger = { ...testLedger, accounts, categories }
    const copied = copyFamily(form({ date: '2026-09-29' }), paymentMatch, { ...record, refund: true }, home, [anna, sam], ledger)
    expect(copied.form).toMatchObject({
      tab: 'expense', date: '2026-09-29', familyId: '7', familyCategoryId: '30', familyComment: 'Market', accountId: '2',
      payee: 'Albert Heijn', memo: 'Card 4', refund: true, amount: '30.00', currency: 'EUR', categoryId: '', split: false,
    })
    expect(copied.form.familySplit.mode).toBe('RULE')
    // "Specify later" leaves the form's account; the form's amount is kept; an archived family category isn't copied.
    const later = copyFamily(form({ accountId: '1', amount: '9', currency: 'USD' }), paymentMatch, {
      ...record, category: { id: 31, code: 'OLD_F', name: 'Old family', archived: true },
      yourPayment: { ...record.yourPayment!, accountId: null, later: true },
    }, home, [anna, sam], ledger)
    expect(later.form).toMatchObject({ accountId: '1', amount: '9', currency: 'USD', familyCategoryId: '' })
    expect(later.notes.familyCategoryId).toMatch(/archived/)
  })
})

describe('the list under New entry (D-460)', () => {
  it('asks 400 ms after the last change, and drops an answer to an older question', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    const pending: { query: URLSearchParams; resolve: (a: Answer) => void }[] = []
    const calls = stubApi({ matches: (query) => new Promise((resolve) => pending.push({ query, resolve })) })
    const ledger = { ...testLedger, accounts, categories }
    const view = (criteria: Criteria) => <SimilarEntries criteria={criteria} refresh={0} ledger={ledger} onCopy={() => {}} />
    const { rerender } = render(view({ type: 'EXPENSE', payee: 'Al' }))
    act(() => vi.advanceTimersByTime(SIMILAR_DELAY_MS - 50))
    rerender(view({ type: 'EXPENSE', payee: 'Alb' }))
    act(() => vi.advanceTimersByTime(SIMILAR_DELAY_MS - 1))
    expect(matchCalls(calls)).toEqual([])
    act(() => vi.advanceTimersByTime(1))
    expect(matchCalls(calls).map((q) => q.payee)).toEqual(['Alb'])

    // A newer question is asked before the older answer comes: the older one is dropped.
    rerender(view({ type: 'EXPENSE', payee: 'Albe' }))
    act(() => vi.advanceTimersByTime(SIMILAR_DELAY_MS))
    expect(matchCalls(calls).map((q) => q.payee)).toEqual(['Alb', 'Albe'])
    await act(async () => pending[1].resolve({ status: 200, body: page([expenseMatch]) }))
    await act(async () => pending[0].resolve({ status: 200, body: page([expenseMatch, { ...expenseMatch, entry: { ...expense, id: 60 } }]) }))
    expect(screen.getByRole('heading', { name: 'Similar entries · 1' })).toBeDefined()
    expect(screen.getAllByRole('button', { name: /^Copy the entry of/ })).toHaveLength(1)
  })

  it('shows the count, the rows, a family row’s mark, and 10 more while there are more', async () => {
    const many = Array.from({ length: 10 }, (_, i) => ({ ...expenseMatch, entry: { ...expense, id: 100 + i } }))
    const calls = stubApi({
      matches: (query) => query.get('page') === '0'
        ? { status: 200, body: page([paymentMatch, ...many.slice(0, 9)], 12) }
        : { status: 200, body: page([many[9], { ...expenseMatch, refund: true, entry: { ...expense, id: 120 } }], 12, 1) },
    })
    renderApp()
    fireEvent.change(await screen.findByLabelText(/^Payee/), { target: { value: 'alb' } })
    const heading = await screen.findByRole('heading', { name: 'Similar entries · 12' }, { timeout: 2000 })
    expect(heading.getAttribute('aria-live')).toBe('polite')
    expect(matchCalls(calls)).toEqual([{ type: 'EXPENSE', payee: 'alb', page: '0', size: '10' }])
    const section = heading.closest('section')!
    expect(within(section).getAllByRole('listitem')).toHaveLength(10)
    // The family row: its budget as the Entries list's family line, with the shared icon.
    const family = within(section).getAllByRole('listitem')[0]
    expect(within(family).getByRole('img', { name: 'Shared: Home' })).toBeDefined()
    expect(family.textContent).toContain('Family payment')
    expect(family.textContent).toContain('Food')
    expect(family.textContent).toContain('-€30.00')
    const row = within(section).getAllByRole('listitem')[1]
    expect(row.textContent).toContain('Albert Heijn')
    expect(row.textContent).toContain('Cash')
    expect(row.textContent).toContain('Eating out')
    expect(row.textContent).toContain('Weekly')
    expect(within(row).getByRole('button', { name: `Copy the entry of ${formatDate('2026-09-20')}, Albert Heijn, €11.35` })).toBeDefined()

    fireEvent.click(within(section).getByRole('button', { name: 'Show 10 more' }))
    await waitFor(() => expect(within(section).getAllByRole('listitem')).toHaveLength(12))
    // A refund shows as the Entries list shows one: money back, with a plus.
    expect(within(section).getAllByRole('listitem')[11].textContent).toContain('+€11.35')
    expect(within(section).queryByRole('button', { name: 'Show 10 more' })).toBeNull()
  })

  it('shows one muted line when the request fails, and the form still saves', async () => {
    const calls = stubApi({ matches: () => ({ status: 500, body: { status: 500, detail: 'Boom' } }) })
    renderApp()
    fireEvent.change(await screen.findByLabelText('Amount'), { target: { value: '5' } })
    expect(await screen.findByText('Couldn’t look for similar entries.', {}, { timeout: 2000 })).toBeDefined()
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText(/^Category/), { target: { value: '13' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url === '/api/entries')).toBe(true))
  })

  it('doesn’t count the payee’s category until the user picks one, and isn’t shown on an entry’s own page', async () => {
    const calls = stubApi({ answers: { 'GET /api/entries/50': { status: 200, body: expense } } })
    renderApp()
    fireEvent.change(await screen.findByLabelText(/^Payee/), { target: { value: 'Albert Heijn' } })
    // withPayee put in Groceries, the payee's last category.
    expect((screen.getByLabelText(/^Category/) as HTMLSelectElement).value).toBe('12')
    await screen.findByRole('heading', { name: /^Similar entries/ }, { timeout: 2000 })
    await waitFor(() => expect(matchCalls(calls).at(-1)).toEqual({ type: 'EXPENSE', payee: 'Albert Heijn', page: '0', size: '10' }))
    fireEvent.change(screen.getByLabelText(/^Category/), { target: { value: '13' } })
    await waitFor(() => expect(matchCalls(calls).at(-1)).toMatchObject({ categoryId: '13' }), { timeout: 2000 })
    cleanup()

    const again = stubApi({ answers: { 'GET /api/entries/50': { status: 200, body: expense } } })
    renderApp('/entries/50')
    await screen.findByRole('button', { name: 'Delete' })
    expect(screen.queryByRole('heading', { name: /^Similar entries/ })).toBeNull()
    expect(matchCalls(again)).toEqual([])
  })
})

describe('Copy in the form (D-461)', () => {
  it('fills the form from a personal row, says so, focuses Amount and starts no new search', async () => {
    const calls = stubApi({ matches: () => ({ status: 200, body: page([expenseMatch]) }) })
    renderApp()
    fireEvent.change(await screen.findByLabelText('Amount'), { target: { value: '12,40' } })
    const copy = await screen.findByRole('button', { name: /^Copy the entry of/ }, { timeout: 2000 })
    const asked = matchCalls(calls).length
    fireEvent.click(copy)
    expect(await screen.findByText(`Copied from the entry of ${formatDate('2026-09-20')}. Check the date and the amount.`)).toBeDefined()
    expect((screen.getByLabelText(/^Payee/) as HTMLInputElement).value).toBe('Albert Heijn')
    expect((screen.getByLabelText(/^Paid from/) as HTMLSelectElement).value).toBe('1')
    expect((screen.getByLabelText(/^Category/) as HTMLSelectElement).value).toBe('13')
    expect((screen.getByLabelText('Memo') as HTMLInputElement).value).toBe('Weekly')
    // The amount typed is kept: the user knows what this one cost.
    expect((screen.getByLabelText('Amount') as HTMLInputElement).value).toBe('12,40')
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('Amount')))
    await new Promise((resolve) => setTimeout(resolve, 600))
    expect(matchCalls(calls)).toHaveLength(asked)
    // A change after it searches again, with what Copy put in.
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '12,41' } })
    await waitFor(() => expect(matchCalls(calls)).toHaveLength(asked + 1), { timeout: 2000 })
    expect(matchCalls(calls).at(-1)).toMatchObject({ payee: 'Albert Heijn', categoryId: '13', accountId: '1', memo: 'Weekly', amount: '12.41' })
  })

  it('opens a row whose postings don’t fit in Advanced, with the entry page’s notice', async () => {
    stubApi({ matches: () => ({ status: 200, body: page([{ ...expenseMatch, entry: odd }]) }) })
    renderApp()
    fireEvent.change(await screen.findByLabelText(/^Payee/), { target: { value: 'Albert' } })
    fireEvent.click(await screen.findByRole('button', { name: /^Copy the entry of/ }, { timeout: 2000 }))
    expect(await screen.findByText(/doesn’t fit the Expense form, so it opens as raw postings/)).toBeDefined()
    expect(screen.getByRole('tab', { name: 'Advanced' }).getAttribute('aria-selected')).toBe('true')
  })

  it('leaves an archived account and category empty, with a line under each', async () => {
    stubApi({ matches: () => ({ status: 200, body: page([{ ...expenseMatch, entry: archived, categoryId: 14 }]) }) })
    renderApp()
    fireEvent.change(await screen.findByLabelText(/^Payee/), { target: { value: 'Albert' } })
    fireEvent.click(await screen.findByRole('button', { name: /^Copy the entry of/ }, { timeout: 2000 }))
    expect(await screen.findByText('The entry’s account is archived, so it isn’t copied. Choose another.')).toBeDefined()
    expect(screen.getByText('The entry’s category is archived, so it isn’t copied. Choose another.')).toBeDefined()
    expect((screen.getByLabelText(/^Paid from/) as HTMLSelectElement).value).toBe('')
  })

  for (const [method, stored, rule, expected] of [
    ['ONE_MEMBER', { splitMethod: 'ONE_MEMBER', shares: [share(sam, '30.00'), share(anna, '0.00')] }, 'EQUAL', { method: 'ONE_MEMBER', memberId: 71 }],
    ['PERCENT', { splitMethod: 'PERCENT', shares: [share(anna, '18.00', 6000), share(sam, '12.00', 4000)] }, 'EQUAL',
      { method: 'PERCENT', shares: [{ memberId: 70, basisPoints: 6000 }, { memberId: 71, basisPoints: 4000 }] }],
    ['AMOUNT', { splitMethod: 'AMOUNT', shares: [share(anna, '20.00'), share(sam, '10.00')] }, 'EQUAL',
      { method: 'AMOUNT', shares: [{ memberId: 70, amount: '20.00' }, { memberId: 71, amount: '10.00' }] }],
    ['EQUAL under an EQUAL rule', {}, 'EQUAL', { method: 'RULE' }],
    ['EQUAL under a CUSTOM rule', {}, 'CUSTOM',
      { method: 'PERCENT', shares: [{ memberId: 70, basisPoints: 5000 }, { memberId: 71, basisPoints: 5000 }] }],
  ] as const) {
    it(`copies a family expense split ${method} and saves it as a new record`, async () => {
      const budget = { ...home, splitRule: rule }
      const members = rule === 'CUSTOM' ? [{ ...anna, share: 7000 }, { ...sam, share: 3000 }] : [anna, sam]
      const calls = stubApi({
        families: [budget], members,
        matches: () => ({ status: 200, body: page([paymentMatch]) }),
        answers: { 'GET /api/family-ledgers/7/records/5': { status: 200, body: { ...record, ...stored } } },
      })
      renderApp()
      fireEvent.change(await screen.findByLabelText(/^Payee/), { target: { value: 'Albert' } })
      fireEvent.click(await screen.findByRole('button', { name: /^Copy the entry of/ }, { timeout: 2000 }))
      expect(await screen.findByText(/^Copied from the entry of/)).toBeDefined()
      expect(screen.getByRole('switch', { name: 'Family expense' })).toHaveProperty('checked', true)
      await waitFor(() => expect((screen.getByLabelText(/^Family category/) as HTMLSelectElement).value).toBe('30'))
      expect((screen.getByLabelText(/^Comment for the family budget/) as HTMLInputElement).value).toBe('Market')
      expect((screen.getByLabelText(/^Note, only you see it/) as HTMLInputElement).value).toBe('Card 4')
      expect(await screen.findByTestId('share-71')).toBeDefined()
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
      await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/records'))).toBe(true))
      expect(calls.find((c) => c.method === 'POST' && c.url.endsWith('/records'))!.body).toMatchObject({
        type: 'EXPENSE', date: '2026-09-30', categoryId: 30, amount: '30.00', comment: 'Market', paymentAccountId: 2,
        payeeId: 21, privateNote: 'Card 4', split: expected,
      })
    })
  }

  it('shows the split editor’s own message for a split by amounts that no longer adds up', async () => {
    stubApi({
      matches: () => ({ status: 200, body: page([paymentMatch]) }),
      answers: { 'GET /api/family-ledgers/7/records/5': { status: 200, body: { ...record, splitMethod: 'AMOUNT', shares: [share(anna, '20.00'), share(sam, '10.00')] } } },
    })
    renderApp()
    fireEvent.change(await screen.findByLabelText('Amount'), { target: { value: '40' } })
    fireEvent.click(await screen.findByRole('button', { name: /^Copy the entry of/ }, { timeout: 2000 }))
    expect((await screen.findByLabelText('Amount') as HTMLInputElement).value).toBe('40')
    fireEvent.click(await screen.findByRole('button', { name: 'Save' }))
    expect(await screen.findAllByText(/add up/)).not.toHaveLength(0)
  })
})

describe('Save and add another (D-462)', () => {
  it('keeps every field on each tab, says so, focuses Amount, and asks before saving the same entry again', async () => {
    let answer = true
    vi.stubGlobal('confirm', vi.fn(() => answer))
    const calls = stubApi()
    renderApp('/entries/new?tab=transfer')
    fireEvent.change(await screen.findByLabelText(/^From/), { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText(/^To/), { target: { value: '2' } })
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '25' } })
    fireEvent.change(screen.getByLabelText('Memo'), { target: { value: 'To the bank' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save and add another' }))
    expect(await screen.findByText('Saved. The form keeps its values: change what differs.')).toBeDefined()
    expect(screen.getByRole('tab', { name: 'Transfer' }).getAttribute('aria-selected')).toBe('true')
    expect((screen.getByLabelText(/^From/) as HTMLSelectElement).value).toBe('1')
    expect((screen.getByLabelText(/^To/) as HTMLSelectElement).value).toBe('2')
    expect((screen.getByLabelText('Memo') as HTMLInputElement).value).toBe('To the bank')
    const amount = screen.getByLabelText('Amount') as HTMLInputElement
    expect(amount.value).toBe('25')
    await waitFor(() => expect(document.activeElement).toBe(amount))
    expect([amount.selectionStart, amount.selectionEnd]).toEqual([0, 2])
    const posts = () => calls.filter((c) => c.method === 'POST' && c.url === '/api/entries').length
    expect(posts()).toBe(1)

    // Unchanged: it asks, from either button; declined, nothing is sent.
    answer = false
    fireEvent.click(screen.getByRole('button', { name: 'Save and add another' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(confirm).toHaveBeenCalledWith('Save the same entry again?')
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(posts()).toBe(1)
    // Accepted, it saves again.
    answer = true
    fireEvent.click(screen.getByRole('button', { name: 'Save and add another' }))
    await waitFor(() => expect(posts()).toBe(2))
    // Changed, it doesn't ask.
    fireEvent.change(amount, { target: { value: '26' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save and add another' }))
    await waitFor(() => expect(posts()).toBe(3))
    expect(confirm).toHaveBeenCalledTimes(3)
  })

  for (const tab of ['expense', 'income', 'loan', 'exchange'] as const) {
    it(`keeps the ${tab} tab's fields`, async () => {
      stubApi()
      renderApp(`/entries/new?tab=${tab}`)
      const amount = await screen.findAllByLabelText(/^Amount/)
      fireEvent.change(amount[0], { target: { value: '7' } })
      if (tab === 'exchange') {
        fireEvent.change(screen.getByLabelText('Amount bought'), { target: { value: '8' } })
        fireEvent.change(screen.getAllByLabelText(/^Currency/)[1], { target: { value: 'USD' } })
        fireEvent.change(screen.getByLabelText(/^From/), { target: { value: '1' } })
        fireEvent.change(screen.getByLabelText(/^To/), { target: { value: '9' } })
      } else if (tab === 'loan') {
        fireEvent.change(screen.getByLabelText(/^Borrower/), { target: { value: 'Ivan' } })
        fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: '1' } })
      } else {
        fireEvent.change(screen.getByLabelText(tab === 'income' ? /^Received into/ : /^Paid from/), { target: { value: '1' } })
        fireEvent.change(screen.getByLabelText(/^Category/), { target: { value: tab === 'income' ? '11' : '13' } })
        fireEvent.change(screen.getByLabelText(tab === 'income' ? /^Payer/ : /^Payee/), { target: { value: 'Ivan' } })
      }
      fireEvent.change(screen.getByLabelText('Memo'), { target: { value: 'Kept' } })
      fireEvent.click(screen.getByRole('button', { name: 'Save and add another' }))
      expect(await screen.findByText('Saved. The form keeps its values: change what differs.')).toBeDefined()
      expect((screen.getAllByLabelText(/^Amount/)[0] as HTMLInputElement).value).toBe('7')
      expect((screen.getByLabelText('Memo') as HTMLInputElement).value).toBe('Kept')
      if (tab === 'expense' || tab === 'income') {
        expect((screen.getByLabelText(/^Category/) as HTMLSelectElement).value).toBe(tab === 'income' ? '11' : '13')
        expect((screen.getByLabelText(tab === 'income' ? /^Payer/ : /^Payee/) as HTMLInputElement).value).toBe('Ivan')
      }
      if (tab === 'exchange') expect((screen.getByLabelText('Amount bought') as HTMLInputElement).value).toBe('8')
      if (tab === 'loan') expect((screen.getByLabelText(/^Borrower/) as HTMLInputElement).value).toBe('Ivan')
      expect(screen.getByRole('tab', { name: { expense: 'Expense', income: 'Income', loan: 'Loan', exchange: 'Currency exchange' }[tab] })
        .getAttribute('aria-selected')).toBe('true')
    })
  }

  it('creates a new payee once, then reuses it, on the personal path', async () => {
    const calls = stubApi({
      answers: { 'POST /api/counterparties': { status: 201, body: { id: 99, name: 'New shop', kind: null, archived: false, lastCategoryId: null } } },
    })
    renderApp()
    fireEvent.change(await screen.findByLabelText(/^Payee/), { target: { value: 'New shop' } })
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText(/^Category/), { target: { value: '13' } })
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save and add another' }))
    await screen.findByText('Saved. The form keeps its values: change what differs.')
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '4' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save and add another' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST' && c.url === '/api/entries')).toHaveLength(2))
    expect(calls.filter((c) => c.method === 'POST' && c.url === '/api/counterparties')).toHaveLength(1)
    expect(calls.filter((c) => c.method === 'POST' && c.url === '/api/entries').map((c) => (c.body as { payeeId: number }).payeeId)).toEqual([99, 99])
  })

  it('creates a new payee once, then reuses it, on the family path', async () => {
    const calls = stubApi({
      answers: { 'POST /api/counterparties': { status: 201, body: { id: 99, name: 'New shop', kind: null, archived: false, lastCategoryId: null } } },
    })
    renderApp()
    fireEvent.click(await screen.findByRole('switch', { name: 'Family expense' }))
    const select = await screen.findByLabelText(/^Family category/)
    await waitFor(() => expect(within(select).getAllByRole('option').length).toBeGreaterThan(1))
    fireEvent.change(select, { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText(/^Payee/), { target: { value: 'New shop' } })
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '3' } })
    expect(await screen.findByTestId('share-71')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Save and add another' }))
    await screen.findByText('Saved. The form keeps its values: change what differs.')
    expect(screen.getByRole('switch', { name: 'Family expense' })).toHaveProperty('checked', true)
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '4' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save and add another' }))
    const records = () => calls.filter((c) => c.method === 'POST' && c.url.endsWith('/records'))
    await waitFor(() => expect(records()).toHaveLength(2))
    expect(calls.filter((c) => c.method === 'POST' && c.url === '/api/counterparties')).toHaveLength(1)
    expect(records().map((c) => (c.body as { payeeId: number }).payeeId)).toEqual([99, 99])
  })

  it('asks for the similar entries again after a save', async () => {
    const calls = stubApi()
    renderApp()
    fireEvent.change(await screen.findByLabelText(/^Payee/), { target: { value: 'Ivan' } })
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText(/^Category/), { target: { value: '13' } })
    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '3' } })
    await waitFor(() => expect(matchCalls(calls).length).toBeGreaterThan(0), { timeout: 2000 })
    const before = matchCalls(calls).length
    fireEvent.click(screen.getByRole('button', { name: 'Save and add another' }))
    await screen.findByText('Saved. The form keeps its values: change what differs.')
    await waitFor(() => expect(matchCalls(calls)).toHaveLength(before + 1))
  })
})

describe('the user’s button under 40rem (D-464)', () => {
  it('has the whole name as its name and title, an icon drawn for the app, and the name first in its panel', async () => {
    stubApi({ me: OFF })
    renderApp('/entries', OFF)
    const button = await screen.findByRole('button', { name: 'Anna Example' })
    expect(button.getAttribute('title')).toBe('Anna Example')
    expect(button.querySelector('svg.icon.person')).not.toBeNull()
    expect(button.querySelector('svg.icon.person')!.getAttribute('aria-hidden')).toBe('true')
    fireEvent.click(button)
    const panel = document.getElementById(button.getAttribute('aria-controls')!)!
    const first = panel.firstElementChild as HTMLElement
    expect(first.tagName).toBe('P')
    expect(first.textContent).toBe('Anna Example')
    expect(first.querySelector('a')).toBeNull()
  })
})
