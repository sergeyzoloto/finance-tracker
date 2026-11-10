import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CashFlowRow, Category, ConvertedCashFlow, Entry, EntryPage } from './api'
import CashFlowTable from './CashFlowTable'
import Categories from './Categories'
import { CategorySelect } from './components'
import { cashFlowTables, compareColumns, compareTable, comparisons, convertedCashFlowTable } from './dashboard'
import Entries from './Entries'
import EntryEditor from './EntryEditor'
import { categoryLabel, kindLabel } from './ledger'
import { testLedger } from './testLedger'
import { WithMe } from './testMe'

// The personal pages with a family budget's rows (F4a parts 5 and 6): its entry kinds labelled, its entries read-only,
// its categories marked in the lists, the pickers and the cash flow.

const familyGroceries: Category = {
  id: 52, code: 'GROCERIES', name: 'Groceries', type: 'EXPENSE', archived: false, familyLedgerId: 7,
  familyLedgerName: 'Home',
}
const categories = [...testLedger.categories, familyGroceries]
const debt = { id: 30, code: 'FAMILY_DEBT_7', name: 'Debt to family budget: Home', type: 'LIABILITY' as const,
  defaultCurrency: 'EUR', requiresCounterparty: false, system: true, archived: false }
const accounts = [...testLedger.accounts, debt]

const share: Entry = {
  id: 90, version: 0, entryDate: '2026-09-10', kind: 'FAMILY_SHARE', payeeId: null, memo: null,
  postings: [
    { accountId: 6, currency: 'EUR', amount: '50.00', categoryId: 52, counterpartyId: null },
    { accountId: 30, currency: 'EUR', amount: '-50.00', categoryId: null, counterpartyId: null },
  ],
  family: { ledgerId: 7, ledgerName: 'Home', recordId: 3, link: 'SHARE', readOnly: true },
}
const payment: Entry = {
  ...share, id: 91, kind: 'FAMILY_PAYMENT',
  postings: [
    { accountId: 1, currency: 'EUR', amount: '-100.00', categoryId: null, counterpartyId: null },
    { accountId: 30, currency: 'EUR', amount: '100.00', categoryId: null, counterpartyId: null },
  ],
  family: { ...share.family!, link: 'PAYMENT' },
}
const own: Entry = {
  id: 92, version: 0, entryDate: '2026-09-09', kind: 'EXPENSE', payeeId: null, memo: 'Market', family: null,
  postings: [
    { accountId: 1, currency: 'EUR', amount: '-4.00', categoryId: 12, counterpartyId: null },
    { accountId: 6, currency: 'EUR', amount: '4.00', categoryId: 12, counterpartyId: null },
  ],
}

/** Answers a GET by its path, a path's prefix up to "?" included; anything else with 404. */
function stubApi(answers: Record<string, unknown>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const path = url.replace(/^\/api/, '')
    const body = answers[path] ?? answers[path.split('?')[0]]
    return new Response(JSON.stringify(body ?? { status: 404, detail: 'Not found.' }), {
      status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' },
    })
  }))
}

const reference = {
  '/accounts': accounts, '/categories': categories, '/counterparties': testLedger.counterparties,
  '/settings': testLedger.settings,
}

function renderAt(path: string, element: React.ReactNode, route = path) {
  render(<WithMe><MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} /></Routes></MemoryRouter></WithMe>)
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('labels', () => {
  it('names the kinds a family budget posts, and marks its categories', () => {
    expect(kindLabel('FAMILY_SHARE')).toBe('Family share')
    expect(kindLabel('FAMILY_PAYMENT')).toBe('Family payment')
    expect(kindLabel('FAMILY_SETTLEMENT')).toBe('Family settlement')
    expect(kindLabel('FAMILY_OPENING')).toBe('Family opening balance')
    expect(kindLabel('FAMILY_CORRECTION')).toBe('Family correction')
    expect(kindLabel('TRANSFER')).toBe('Transfer')
    expect(categoryLabel(familyGroceries)).toBe('Groceries · Home')
    expect(categoryLabel(testLedger.categories[1])).toBe('Groceries')
  })
})

describe('the entries', () => {
  /** An element's text as the eye reads it: the icon's name, a tooltip only, left out. */
  const seen = (element: Element) => {
    const copy = element.cloneNode(true) as Element
    copy.querySelectorAll('svg').forEach((svg) => svg.remove())
    return copy.textContent ?? ''
  }
  /** A row's cells by column, as shown; the family line is the category cell's second line. */
  const cells = (row: HTMLElement) => {
    const [, payee, category, flow, amount] = within(row).getAllByRole('cell')
    const line = category.querySelector('.family-line')
    return {
      payee: payee.textContent, category: category.querySelector('.category-name')!.textContent,
      family: line ? seen(line) : null, flow: flow.textContent, amount: amount.textContent,
    }
  }
  /** How many times `text` shows in a row. */
  const shown = (row: HTMLElement, text: string) => seen(row).split(text).length - 1

  it('names the row’s family budget once, under the category, after the shared icon (D-441, D-442)', async () => {
    const page: EntryPage = { content: [share, payment, own], page: 0, size: 50, totalElements: 3, totalPages: 1 }
    stubApi({ ...reference, '/entries': page })
    renderAt('/entries', <Entries />)

    const rows = (await screen.findAllByRole('row')).slice(1)
    const [shareRow, paymentRow, ownRow] = rows
    // The category without " · Home", the kind and the budget on the line under it, the debt account's short name.
    expect(cells(shareRow)).toMatchObject({
      category: 'Groceries', family: 'Family share · Home', flow: 'Debt to family budget → Groceries',
    })
    expect(within(shareRow).getByRole('img', { name: 'Shared: Home' })).toBeDefined()
    expect(shown(shareRow, 'Home')).toBe(1)
    // Without a category: the kind in its place, the line the icon and the name; Cash keeps its name.
    expect(cells(paymentRow)).toMatchObject({
      category: 'Family payment', family: 'Home', flow: 'Cash → Debt to family budget',
    })
    expect(shown(paymentRow, 'Home')).toBe(1)
    expect(cells(ownRow)).toMatchObject({ category: 'Groceries', family: null })
    expect(ownRow.textContent).not.toContain('Home')
  })

  it('shortens only the row’s own budget’s debt account, its code with a suffix too, with two budgets', async () => {
    const trip: Category = { ...familyGroceries, id: 53, code: 'TRAVEL', name: 'Travel', familyLedgerId: 8, familyLedgerName: 'Trip' }
    // A second debt account of budget 8, as `CrossLedgerWriter.debtAccount` names one when its code is taken.
    const tripDebt = { ...debt, id: 31, code: 'FAMILY_DEBT_8_2', name: 'Debt to family budget: Trip' }
    // Not a debt account: a user's account whose code only looks like one.
    const lookalike = { ...debt, id: 32, code: 'FAMILY_DEBT_8_X', name: 'Savings for the trip', system: false, type: 'ASSET' as const }
    const tripShare: Entry = {
      ...share, id: 93,
      postings: [
        { accountId: 6, currency: 'EUR', amount: '20.00', categoryId: 53, counterpartyId: null },
        { accountId: 31, currency: 'EUR', amount: '-20.00', categoryId: null, counterpartyId: null },
      ],
      family: { ledgerId: 8, ledgerName: 'Trip', recordId: 4, link: 'SHARE', readOnly: true },
    }
    const tripPayment: Entry = {
      ...payment, id: 94,
      postings: [
        { accountId: 32, currency: 'EUR', amount: '-40.00', categoryId: null, counterpartyId: null },
        { accountId: 31, currency: 'EUR', amount: '40.00', categoryId: null, counterpartyId: null },
      ],
      family: { ledgerId: 8, ledgerName: 'Trip', recordId: 4, link: 'PAYMENT', readOnly: true },
    }
    // A row of Home that also touches Trip's debt account keeps that one's full name: only its own is shortened.
    const odd: Entry = {
      ...share, id: 95,
      postings: [
        { accountId: 30, currency: 'EUR', amount: '5.00', categoryId: null, counterpartyId: null },
        { accountId: 31, currency: 'EUR', amount: '-5.00', categoryId: null, counterpartyId: null },
      ],
      family: { ...share.family!, link: 'CORRECTION' }, kind: 'FAMILY_CORRECTION',
    }
    const page: EntryPage = { content: [share, tripShare, tripPayment, odd], page: 0, size: 50, totalElements: 4, totalPages: 1 }
    stubApi({ ...reference, '/accounts': [...accounts, tripDebt, lookalike], '/categories': [...categories, trip], '/entries': page })
    renderAt('/entries', <Entries />)

    const [homeRow, tripRow, tripPaymentRow, oddRow] = (await screen.findAllByRole('row')).slice(1)
    expect(cells(homeRow)).toMatchObject({ category: 'Groceries', family: 'Family share · Home' })
    expect(cells(tripRow)).toMatchObject({
      category: 'Travel', family: 'Family share · Trip', flow: 'Debt to family budget → Travel',
    })
    expect(shown(tripRow, 'Trip')).toBe(1)
    expect(cells(tripPaymentRow)).toMatchObject({ family: 'Trip', flow: 'Savings for the trip → Debt to family budget' })
    expect(cells(oddRow).flow).toBe('Debt to family budget: Trip → Debt to family budget')
    // The filters keep the budget's name: two budgets' categories told apart (D-442).
    const filter = screen.getByLabelText(/^Category/) as HTMLSelectElement
    expect([...filter.options].map((o) => o.textContent)).toEqual(expect.arrayContaining(['Groceries · Home', 'Travel · Trip']))
  })

  it('opens a posted share read-only: no form, no save, no delete', async () => {
    stubApi({ ...reference, '/entries/90': share })
    renderAt('/entries/90', <EntryEditor />, '/entries/:id')

    expect(await screen.findByRole('heading', { name: 'Family share' })).toBeDefined()
    expect(screen.getByText(/Posted by the family budget/).textContent).toContain('It changes only there.')
    expect(screen.getByRole('link', { name: 'Home' }).getAttribute('href')).toBe('/family/7')
    expect(screen.getByText('Groceries · Home')).toBeDefined()
    expect(screen.getByText('Debt to family budget: Home → Groceries · Home')).toBeDefined()
    expect(screen.getByText('€50.00')).toBeDefined()
    expect(screen.queryByRole('button', { name: /Save/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Delete/ })).toBeNull()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.getByRole('button', { name: 'Back' })).toBeDefined()
  })

  it('opens a payment for a family record read-only, pointing to the record', async () => {
    stubApi({ ...reference, '/entries/91': payment })
    renderAt('/entries/91', <EntryEditor />, '/entries/:id')

    expect(await screen.findByRole('heading', { name: 'Family payment' })).toBeDefined()
    expect(screen.getByText(/Your payment for an expense of the family budget/).textContent)
      .toContain('change or delete the expense there')
    expect(screen.queryByRole('button', { name: /Delete/ })).toBeNull()
  })

  it('opens the opening balance of a place taken in a family budget read-only, without a record (F5)', async () => {
    const opening: Entry = {
      ...share, id: 93, kind: 'FAMILY_OPENING', entryDate: '2026-09-15',
      postings: [
        { accountId: 30, currency: 'EUR', amount: '10.00', categoryId: null, counterpartyId: null },
        { accountId: 7, currency: 'EUR', amount: '-10.00', categoryId: null, counterpartyId: null },
      ],
      family: { ledgerId: 7, ledgerName: 'Home', recordId: null, link: 'OPENING_BALANCE', readOnly: true },
    }
    stubApi({ ...reference, '/entries/93': opening })
    renderAt('/entries/93', <EntryEditor />, '/entries/:id')

    expect(await screen.findByRole('heading', { name: 'Family opening balance' })).toBeDefined()
    expect(screen.getByText(/Your balance in the family budget/).textContent)
      .toContain('from before the day you took your place in it')
    expect(screen.queryByText(/Posted by the family budget/)).toBeNull()
    expect(screen.queryByRole('link', { name: /Open the/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Delete/ })).toBeNull()
  })

  it('links a posted share and a payment to their expense in the family budget', async () => {
    stubApi({ ...reference, '/entries/90': share, '/entries/91': payment })
    renderAt('/entries/90', <EntryEditor />, '/entries/:id')
    expect((await screen.findByRole('link', { name: 'Open the expense' })).getAttribute('href')).toBe('/family/7/expenses/3')
    cleanup()

    renderAt('/entries/91', <EntryEditor />, '/entries/:id')
    expect((await screen.findByRole('link', { name: 'Open the expense' })).getAttribute('href')).toBe('/family/7/expenses/3')
  })

  it('opens an entry of the user’s own in the form, as before', async () => {
    stubApi({ ...reference, '/entries/92': own })
    renderAt('/entries/92', <EntryEditor />, '/entries/:id')

    expect(await screen.findByRole('heading', { name: 'Edit entry' })).toBeDefined()
    // The form comes with the accounts, categories, payees and settings, which may arrive after the heading.
    expect(await screen.findByRole('button', { name: /Delete/ })).toBeDefined()
  })
})

describe('the categories', () => {
  it('marks a family budget’s category, without the personal controls', async () => {
    stubApi({ '/categories': categories })
    renderAt('/categories', <Categories />)

    const familyRow = (await screen.findAllByRole('row')).find((r) => within(r).queryByText('Home'))!
    expect(within(familyRow).getByText('Groceries')).toBeDefined()
    expect(within(familyRow).queryByRole('button')).toBeNull()
    expect(within(familyRow).getByRole('link', { name: 'Change it in the family budget' }).getAttribute('href'))
      .toBe('/family/7/categories')
    const ownRow = screen.getAllByRole('row').find((r) => r.textContent?.startsWith('Eating out'))!
    expect(within(ownRow).getByRole('button', { name: 'Rename' })).toBeDefined()
    expect(within(ownRow).getByRole('button', { name: 'Archive' })).toBeDefined()
  })

  it('offers a family budget’s category in the pickers, marked', () => {
    render(<CategorySelect categories={categories} type="EXPENSE" value="" onChange={() => {}} />)
    expect(screen.getAllByRole('option').map((o) => o.textContent))
      .toEqual(['Choose a category', 'Groceries', 'Eating out', 'Groceries · Home'])
  })
})

describe('the cash flow', () => {
  const rows: CashFlowRow[] = [
    { month: '2026-09', categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', currency: 'EUR', total: '4.00' },
    { month: '2026-09', categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', currency: 'EUR', total: '32.25', familyLedgerId: 7, familyLedgerName: 'Home' },
    { month: '2026-09', categoryCode: 'SALARY', categoryName: 'Salary', categoryType: 'INCOME', currency: 'EUR', total: '100.00' },
  ]

  it('keeps a family budget’s category apart from the user’s own of the same code, marked', () => {
    const [table] = cashFlowTables(rows, ['2026-09'])
    expect(table.expense.lines.map((l) => [l.key, l.label, l.family, l.total])).toEqual([
      ['category:GROCERIES', 'Groceries', undefined, '4'],
      ['category:GROCERIES@7', 'Groceries', 'Home', '32.25'],
    ])
    expect(table.expense.subtotal.total).toBe('36.25')
    expect(table.net.total).toBe('63.75')

    const c = comparisons({ from: '2026-09-01', to: '2026-09-30' }, '2026-10-05')!
    render(<CashFlowTable table={compareTable('EUR', compareColumns(c), [table, null, null], false)} />)
    // D-419: the shared icon, named by the budget, and with one budget in the table no name after it.
    const familyLine = screen.getByRole('img', { name: 'Shared: Home' }).closest('tr')!
    expect(within(familyLine).getByText('Groceries')).toBeDefined()
    expect(within(familyLine).queryByText('Home')).toBeNull()
    expect(within(familyLine).getAllByRole('cell').map((c) => c.textContent)).toEqual(['€32.25', '—', '—', '—', '—'])
  })

  it('does the same in the base currency', () => {
    const report: ConvertedCashFlow = {
      currency: 'EUR', exchangeResults: [],
      rows: rows.map(({ currency, ...row }) => ({ ...row, missingRates: [] })),
    }
    const table = convertedCashFlowTable(report, ['2026-09'])
    expect(table.expense.lines.map((l) => [l.label, l.family ?? null, l.total])).toEqual([
      ['Groceries', null, '4'], ['Groceries', 'Home', '32.25'],
    ])
  })
})
