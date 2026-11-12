import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type {
  CashFlowRow, ConvertedCashFlow, ConvertedDailyCashFlow, Counterparty, DailyCashFlowRow, FamilyLedger, Me,
} from './api'
import { dailyRange, dailySpending, dailyTitle, dayMarks, type DailyRow } from './daily'
import DailySpending from './DailySpending'
import { MISSING } from './dashboard'
import { payeeFilterOptions, payeeFilterValue } from './familyPayees'
import { testLedger } from './testLedger'
import { WithMe } from './testMe'

// UX-13: the dashboard's headline strip (D-569), daily spending (D-571) and Entries' payee option in another case
// (D-568). Today is Thursday 12 November 2026.

const TODAY = '2026-11-12'
const ME: Me = { name: 'Anna', timeZone: 'UTC', today: TODAY, features: { familyLedgers: true } }
const home: FamilyLedger = {
  id: 7, name: 'Demo household', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70,
  createdAt: '2026-01-01T10:00:00Z', startDate: '2026-01-01',
}
/** Narrow and thin spaces as plain ones, for `formatRange`'s output. */
const plain = (text: string | null | undefined) => (text ?? '').replace(/[   ]/g, ' ')

describe('the days daily spending shows (D-571)', () => {
  const shown = (from: string, to: string, today = TODAY) => {
    const section = { from, to: from <= today && today < to ? today : to }
    const range = dailyRange({ from, to }, section, today)
    return { from: range.from, to: range.to, asked: range.asked, title: plain(dailyTitle(range)) }
  }

  it('shows a short period’s own days, a running one’s asked up to today (D-584)', () => {
    expect(shown('2026-11-02', '2026-11-08')).toEqual({ from: '2026-11-02', to: '2026-11-08',
      asked: { from: '2026-11-02', to: '2026-11-08' }, title: 'Daily spending, Nov 2 – 8, 2026' })
    // A running month: the whole month, asked up to today, its days after today to come.
    expect(shown('2026-11-01', '2026-11-30')).toEqual({ from: '2026-11-01', to: '2026-11-30',
      asked: { from: '2026-11-01', to: TODAY }, title: 'Daily spending, November 2026' })
    // A running week.
    expect(shown('2026-11-09', '2026-11-15')).toEqual({ from: '2026-11-09', to: '2026-11-15',
      asked: { from: '2026-11-09', to: TODAY }, title: 'Daily spending, Nov 9 – 15, 2026' })
    // A custom range of at most 31 days across months, running.
    expect(shown('2026-10-20', '2026-11-15')).toEqual({ from: '2026-10-20', to: '2026-11-15',
      asked: { from: '2026-10-20', to: TODAY }, title: 'Daily spending, Oct 20 – Nov 15, 2026' })
  })

  it('names a past calendar month by its month', () => {
    expect(shown('2026-10-01', '2026-10-31')).toEqual({ from: '2026-10-01', to: '2026-10-31',
      asked: { from: '2026-10-01', to: '2026-10-31' }, title: 'Daily spending, October 2026' })
    expect(shown('2026-02-01', '2026-02-28').title).toBe('Daily spending, February 2026')
  })

  it('shows a longer period’s month of its last day, its days after today still to come', () => {
    // A quarter running: November, asked up to today.
    expect(shown('2026-10-01', '2026-12-31')).toEqual({ from: '2026-11-01', to: '2026-11-30',
      asked: { from: '2026-11-01', to: TODAY }, title: 'Daily spending, November 2026' })
    // A past quarter: its last month.
    expect(shown('2026-07-01', '2026-09-30')).toEqual({ from: '2026-09-01', to: '2026-09-30',
      asked: { from: '2026-09-01', to: '2026-09-30' }, title: 'Daily spending, September 2026' })
    // A year running: this month.
    expect(shown('2026-01-01', '2026-12-31').title).toBe('Daily spending, November 2026')
  })

  it('keeps a custom range’s month within the period, From and To (D-574)', () => {
    // Across months, more than 31 days: the month of its last day, cut at To.
    expect(shown('2026-08-20', '2026-10-15')).toEqual({ from: '2026-10-01', to: '2026-10-15',
      asked: { from: '2026-10-01', to: '2026-10-15' }, title: 'Daily spending, Oct 1 – 15, 2026' })
    // Across months, at most 31 days: its own days.
    expect(shown('2026-10-20', '2026-11-10')).toEqual({ from: '2026-10-20', to: '2026-11-10',
      asked: { from: '2026-10-20', to: '2026-11-10' }, title: 'Daily spending, Oct 20 – Nov 10, 2026' })
    // A long period that starts within its last month: from its From.
    expect(shown('2026-11-05', '2027-01-04')).toMatchObject({ from: '2026-11-05', to: '2026-11-30',
      asked: { from: '2026-11-05', to: TODAY } })
    // A period that starts after today: its own days, every one to come, nothing asked (D-584).
    expect(shown('2026-12-01', '2026-12-31')).toEqual({ from: '2026-12-01', to: '2026-12-31', asked: null,
      title: 'Daily spending, December 2026' })
  })

  it('marks the first day, today while the range runs, and the last day', () => {
    expect(dayMarks({ from: '2026-11-01', to: '2026-11-30' }, TODAY).map((m) => m.label)).toEqual(['1', '12', '30'])
    expect(dayMarks({ from: '2026-10-01', to: '2026-10-31' }, TODAY).map((m) => m.label)).toEqual(['1', '31'])
    expect(dayMarks({ from: '2026-11-01', to: '2026-11-30' }, TODAY)[1].at).toBeCloseTo(11 / 30)
  })
})

const day = (date: string, categoryName: string, total: string | null, categoryType: DailyRow['categoryType'] = 'EXPENSE',
  familyLedgerName?: string): DailyRow => ({
  day: date, categoryName, categoryType, total, ...(familyLedgerName ? { familyLedgerName } : {}),
})

describe('each day’s spending and the biggest day (D-571)', () => {
  const october = { from: '2026-10-01', to: '2026-10-31' }

  it('adds up a day’s expenses, a refund lowering it, and leaves out income', () => {
    const { slots, biggest, max } = dailySpending([
      day('2026-10-03', 'Groceries', '40.00'), day('2026-10-03', 'Transport', '15.50'),
      day('2026-10-04', 'Groceries', '-5.00'), day('2026-10-04', 'Salary', '900.00', 'INCOME'),
      day('2026-10-05', 'Groceries', '10.00'), day('2026-10-05', 'Groceries', '-4.00', 'EXPENSE', 'Demo household'),
    ], october, TODAY)
    expect(slots).toHaveLength(31)
    expect(slots.find((s) => s.day === '2026-10-03')!.amount).toBe('55.5')
    expect(slots.find((s) => s.day === '2026-10-04')!.amount).toBe('-5')
    expect(slots.find((s) => s.day === '2026-10-05')!.amount).toBe('6')
    expect(slots.find((s) => s.day === '2026-10-06')!.amount).toBeNull()
    expect(max).toBe('55.5')
    expect(biggest).toEqual({ day: '2026-10-03', amount: '55.5', category: { name: 'Groceries' } })
  })

  it('takes the earlier of two equal days, and of two equal categories the first by name', () => {
    const { biggest } = dailySpending([
      day('2026-10-08', 'Travel', '30.00'), day('2026-10-08', 'Books', '30.00'),
      day('2026-10-02', 'Rent', '45.00'), day('2026-10-02', 'Groceries', '15.00'),
    ], october, TODAY)
    expect(biggest?.day).toBe('2026-10-02')
    const tie = dailySpending([day('2026-10-08', 'Travel', '30.00'), day('2026-10-08', 'Books', '30.00')], october, TODAY)
    expect(tie.biggest?.category).toEqual({ name: 'Books' })
    // A family budget's category keeps its budget, and with one name of each, the user's own comes first.
    const family = dailySpending([day('2026-10-08', 'Groceries', '30.00', 'EXPENSE', 'Demo household'),
      day('2026-10-08', 'Groceries', '30.00')], october, TODAY)
    expect(family.biggest?.category).toEqual({ name: 'Groceries' })
    expect(dailySpending([day('2026-10-08', 'Groceries', '31.00', 'EXPENSE', 'Demo household')], october, TODAY)
      .biggest?.category).toEqual({ name: 'Groceries', family: 'Demo household' })
  })

  it('has no biggest day without spending above 0, and a day without a rate is missing', () => {
    const none = dailySpending([day('2026-10-04', 'Groceries', '-5.00')], october, TODAY)
    expect(none.biggest).toBeNull()
    expect(none.max).toBeNull()
    const missing = dailySpending([day('2026-10-04', 'Travel', null), day('2026-10-04', 'Groceries', '5.00'),
      day('2026-10-06', 'Groceries', '2.00')], october, TODAY)
    expect(missing.slots.find((s) => s.day === '2026-10-04')!.amount).toBe(MISSING)
    expect(missing.biggest?.day).toBe('2026-10-06')
  })

  it('leaves the days after today to come', () => {
    const { slots } = dailySpending([], { from: '2026-11-01', to: '2026-11-30' }, TODAY)
    expect(slots.filter((s) => s.future).map((s) => s.day)).toHaveLength(18)
    expect(slots.find((s) => s.day === TODAY)!.future).toBe(false)
  })
})

describe('the daily spending panel (D-571)', () => {
  const range = { from: '2026-11-01', to: '2026-11-30', asked: { from: '2026-11-01', to: TODAY }, wholeMonth: true }
  const renderPanel = (rows: DailyRow[] | undefined, extra: { error?: string } = {}) => render(
    <WithMe><DailySpending range={range} rows={rows} loading={false} currency="EUR" rounded={false} today={TODAY}
      budgetsNamed={false} {...extra} /></WithMe>)

  it('draws a slot per shown day, hidden from screen readers, and tells them every day that spent in a table', () => {
    const { container } = renderPanel([day('2026-11-03', 'Groceries', '40.00'), day('2026-11-05', 'Groceries', '-3.00'),
      day('2026-11-09', 'Groceries', '10.00', 'EXPENSE', 'Demo household')])
    expect(screen.getByRole('heading', { name: 'Daily spending, November 2026' })).toBeDefined()
    const bars = container.querySelector('.daily-bars')!
    expect(bars.getAttribute('aria-hidden')).toBe('true')
    const slots = [...bars.querySelectorAll('.daily-day')]
    expect(slots).toHaveLength(30)
    expect(slots.filter((s) => s.classList.contains('future'))).toHaveLength(18)
    expect(slots.filter((s) => s.classList.contains('spent'))).toHaveLength(2)
    // A day at or below 0 is a line, and the largest day the full height.
    expect(slots[4].classList.contains('none')).toBe(true)
    expect((slots[2].querySelector('.daily-bar') as HTMLElement).style.height).toBe('100%')
    expect((slots[8].querySelector('.daily-bar') as HTMLElement).style.height).toBe('25%')
    expect(slots[2].getAttribute('title')).toBe('Nov 3, 2026: €40.00')
    expect(slots[20].getAttribute('title')).toBe('Nov 21, 2026: still to come')
    expect([...container.querySelectorAll('.daily-axis span')].map((s) => s.textContent)).toEqual(['1', '12', '30'])
    const table = screen.getByRole('table')
    expect(within(table).getAllByRole('row').slice(1).map((r) => r.textContent)).toEqual(['Nov 3, 2026€40.00',
      'Nov 9, 2026€10.00'])
    expect(plain(container.querySelector('.daily-biggest')!.textContent)).toBe('Biggest day: Nov 3, 2026, €40.00, Groceries')
  })

  it('says when nothing was spent, and marks a day without a rate', () => {
    const { container } = renderPanel([day('2026-11-04', 'Travel', null), day('2026-11-05', 'Groceries', '-3.00')])
    const missing = container.querySelector('.daily-day[data-day="2026-11-04"]')!
    expect(missing.classList.contains('missing')).toBe(true)
    expect(missing.getAttribute('title')).toBe('Nov 4, 2026: rate missing')
    expect(missing.querySelector('.daily-bar')).toBeNull()
    expect(container.querySelector('.daily-biggest')!.textContent).toBe('No spending in this period.')
    expect(within(screen.getByRole('table')).getByText('rate missing')).toBeDefined()
  })

  it('names a family category’s budget by its icon', () => {
    const { container } = renderPanel([day('2026-11-03', 'Groceries', '40.00', 'EXPENSE', 'Demo household')])
    expect(within(container.querySelector('.daily-biggest') as HTMLElement)
      .getByRole('img', { name: 'Shared: Demo household' })).toBeDefined()
  })

  it('shows its own load and error', () => {
    renderPanel(undefined)
    expect(screen.getByText('Loading…')).toBeDefined()
    cleanup()
    renderPanel(undefined, { error: 'Something went wrong.' })
    expect(screen.getByRole('alert').textContent).toBe('Something went wrong.')
  })
})

// The page: the headline strip and the panel against a stubbed api.

/** October 2026 per currency: income and expenses by category, and each day's expenses. */
const OCTOBER: CashFlowRow[] = [
  { month: '2026-10', categoryCode: 'SALARY', categoryName: 'Salary', categoryType: 'INCOME', currency: 'EUR', total: '2000.00' },
  { month: '2026-10', categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', currency: 'EUR', total: '85.00' },
  { month: '2026-10', categoryCode: 'TRAVEL', categoryName: 'Travel', categoryType: 'EXPENSE', currency: 'USD', total: '20.00' },
]
const OCTOBER_DAYS: DailyCashFlowRow[] = [
  { day: '2026-10-03', categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', currency: 'EUR', total: '50.00' },
  { day: '2026-10-07', categoryCode: 'GROCERIES', categoryName: 'Groceries', categoryType: 'EXPENSE', currency: 'EUR', total: '35.00' },
  { day: '2026-10-12', categoryCode: 'SALARY', categoryName: 'Salary', categoryType: 'INCOME', currency: 'EUR', total: '2000.00' },
  { day: '2026-10-05', categoryCode: 'TRAVEL', categoryName: 'Travel', categoryType: 'EXPENSE', currency: 'USD', total: '20.00' },
]

/** What the api answers, and which of its loads never answer. */
interface Stub { hang?: ('net-worth' | 'cash-flow')[]; dailyFails?: boolean; missingRate?: boolean }
let calls: string[] = []
function stubApi({ hang = [], dailyFails = false, missingRate = false }: Stub = {}) {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    const [path, query = ''] = url.split('?')
    const params = new URLSearchParams(query)
    const inBase = params.get('currency') === 'BASE'
    const october = params.get('from') === '2026-10-01'
    if (hang.some((h) => path === `/api/reports/${h}`)) return new Promise<Response>(() => {})
    if (path === '/api/reports/daily-cash-flow' && dailyFails) {
      return new Response(JSON.stringify({ status: 500, detail: 'Something went wrong' }),
        { status: 500, headers: { 'Content-Type': 'application/problem+json' } })
    }
    const rows = october ? OCTOBER : []
    const converted: ConvertedCashFlow = {
      currency: 'EUR', exchangeResults: [],
      rows: rows.map(({ currency, total, ...r }) => ({
        ...r, total: currency === 'USD' ? (missingRate ? null : '18.00') : total,
        missingRates: currency === 'USD' && missingRate ? [{ currency: 'USD', from: '2026-10-05', to: '2026-10-05', days: 1 }] : [],
      })),
    }
    const days = october ? OCTOBER_DAYS : []
    const convertedDays: ConvertedDailyCashFlow = {
      currency: 'EUR',
      rows: days.map(({ currency, total, ...r }) => ({
        ...r, total: currency === 'USD' ? (missingRate ? null : '18.00') : total,
        missingRates: currency === 'USD' && missingRate ? [{ currency: 'USD', from: '2026-10-05', to: '2026-10-05', days: 1 }] : [],
      })),
    }
    const bodies: Record<string, unknown> = {
      '/api/entries': { content: [], page: 0, size: 1, totalElements: 12, totalPages: 12 },
      '/api/settings': { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' },
      '/api/family-ledgers': [home],
      '/api/reports/net-worth': inBase
        ? { currency: 'EUR', assets: '155.00', liabilities: '9.50', netWorth: '145.50', unrealizedRevaluation: '1.20',
          realizedExchangeResult: '-0.40', rates: [], missingRates: [] }
        : [{ currency: 'EUR', assets: '155.00', liabilities: '9.50', netWorth: '145.50' },
          { currency: 'RUB', assets: '1000.00', liabilities: '0.00', netWorth: '1000.00' }],
      '/api/reports/balances': [],
      '/api/reports/shared-settlement': [],
      '/api/reports/counterparty-balances': [],
      '/api/reports/cash-flow': inBase ? converted : rows,
      '/api/reports/daily-cash-flow': inBase ? convertedDays : days,
    }
    const body = bodies[path]
    return new Response(JSON.stringify(body ?? { status: 404 }), {
      status: body === undefined ? 404 : 200, headers: { 'Content-Type': 'application/json' },
    })
  }))
}
const renderAt = (path: string) => render(<MemoryRouter initialEntries={[path]}><App me={ME} /></MemoryRouter>)

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

/** Each strip's figures as "term amount", the currency's label first in each currency. */
async function strip(currency: string) {
  const dl = await screen.findByLabelText(`Headline figures in ${currency}`)
  return [...dl.querySelectorAll('.figure')].map((f) => plain(`${f.querySelector('dt')!.textContent} ${f.querySelector('dd')!.textContent}`))
}
const notes = (currency: string) =>
  [...screen.getByLabelText(`Headline figures in ${currency}`).querySelectorAll('dd.scope-note')].map((d) => d.textContent)

describe('the headline strip (D-569)', () => {
  it('shows five figures in the base currency, the exchange results under Net worth', async () => {
    stubApi()
    renderAt('/?period=previous-month')
    const headline = await screen.findByRole('region', { name: 'Headline figures' })
    await within(headline).findByText('€2,000.00')
    expect(await strip('EUR')).toEqual(['You have €155.00', 'You owe €9.50', 'Net worth €145.50', 'Income €2,000.00',
      'Expenses €103.00'])
    const worth = within(headline).getByText('Net worth').parentElement!
    expect(plain(worth.querySelector('dd.fx')!.textContent)).toBe('Realized on exchanges -€0.40Revaluation of balances +€1.20')
    // Income and Expenses are the table's totals; the card shows no totals of its own.
    const table = screen.getByRole('table', { name: 'Cash flow in EUR' })
    expect(within(table).getByRole('rowheader', { name: 'Total income' }).closest('tr')!.querySelector('td')!.textContent)
      .toBe('€2,000.00')
    expect(within(table).getByRole('rowheader', { name: 'Total expenses' }).closest('tr')!.querySelector('td')!.textContent)
      .toBe('€103.00')
    expect(headline.querySelector('.figure.income dd')!.className).toBe('income')
    expect(headline.querySelector('.figure.expense dd')!.className).toBe('expense')
    expect(notes('EUR')).toEqual([])
  })

  it('shows one strip per currency of either part, “—” where a currency has nothing', async () => {
    stubApi()
    renderAt('/?period=previous-month&currency=each')
    await screen.findByLabelText('Headline figures in USD')
    await waitFor(async () => expect(await strip('RUB')).toContain('Net worth RUB 1,000.00'))
    expect(await strip('EUR')).toEqual(['You have €155.00', 'You owe €9.50', 'Net worth €145.50', 'Income €2,000.00',
      'Expenses €85.00'])
    expect(await strip('RUB')).toEqual(['You have RUB 1,000.00', 'You owe RUB 0.00', 'Net worth RUB 1,000.00',
      'Income —', 'Expenses —'])
    expect(await strip('USD')).toEqual(['You have —', 'You owe —', 'Net worth —', 'Income —', 'Expenses $20.00'])
    expect([...document.querySelectorAll('.strip .headline-currency')].map((d) => d.textContent)).toEqual(['EUR', 'RUB', 'USD'])
  })

  it('names the scope under Income and Expenses, as Show and Records do', async () => {
    stubApi()
    renderAt('/?period=previous-month&scope=family-mine&family=7')
    await screen.findAllByText('€2,000.00')
    expect(notes('EUR')).toEqual(['Demo household · My records', 'Demo household · My records'])
    cleanup()
    renderAt('/?period=previous-month&scope=personal')
    await screen.findAllByText('€2,000.00')
    expect(notes('EUR')).toEqual(['Personal', 'Personal'])
    cleanup()
    renderAt('/?period=previous-month&scope=family')
    await screen.findAllByText('€2,000.00')
    expect(notes('EUR')).toEqual(['Demo household · All records', 'Demo household · All records'])
    // The daily spending follows the scope too.
    expect(calls).toContain('/api/reports/daily-cash-flow?from=2026-10-01&to=2026-10-31&scope=FAMILY&familyLedgerId=7&currency=BASE')
  })

  it('shows the balances while the cash flow loads, and the cash flow while the balances load', async () => {
    stubApi({ hang: ['cash-flow'] })
    renderAt('/?period=previous-month')
    await waitFor(async () => expect(await strip('EUR')).toEqual(['You have €155.00', 'You owe €9.50',
      'Net worth €145.50', 'Income …', 'Expenses …']))
    cleanup()
    stubApi({ hang: ['net-worth'] })
    renderAt('/?period=previous-month')
    await waitFor(async () => expect(await strip('EUR')).toEqual(['You have …', 'You owe …', 'Net worth …',
      'Income €2,000.00', 'Expenses €103.00']))
  })
})

describe('daily spending on the dashboard (D-571)', () => {
  it('asks the shown days with the page’s currency, and draws them beside the chart', async () => {
    stubApi()
    const { container } = renderAt('/?period=previous-month')
    const panel = await screen.findByRole('region', { name: 'Daily spending, October 2026' })
    expect(calls).toContain('/api/reports/daily-cash-flow?from=2026-10-01&to=2026-10-31&currency=BASE')
    await within(panel).findByText(/^Biggest day/)
    expect(panel.querySelectorAll('.daily-day')).toHaveLength(31)
    expect(plain(panel.querySelector('.daily-biggest')!.textContent)).toBe('Biggest day: Oct 3, 2026, €50.00, Groceries')
    expect(panel.closest('.chart-row')!.querySelector('figure.chart')).not.toBeNull()
    expect(container.querySelectorAll('section.daily')).toHaveLength(1)
  })

  it('has one panel per currency in each currency', async () => {
    stubApi()
    const { container } = renderAt('/?period=previous-month&currency=each')
    await screen.findByRole('table', { name: 'Cash flow in USD' })
    expect(calls).toContain('/api/reports/daily-cash-flow?from=2026-10-01&to=2026-10-31')
    await waitFor(() => expect(container.querySelectorAll('.daily-biggest')).toHaveLength(2))
    expect([...container.querySelectorAll('section.daily')].map((s) => s.getAttribute('data-currency'))).toEqual(['EUR', 'USD'])
    const usd = container.querySelector('section.daily[data-currency="USD"]')!
    expect(plain(usd.querySelector('.daily-biggest')!.textContent)).toBe('Biggest day: Oct 5, 2026, $20.00, Travel')
  })

  it('shows a running month’s and a running quarter’s month, asked up to today, its days to come (D-584)', async () => {
    stubApi()
    const { container } = renderAt('/?period=this-month')
    await screen.findByRole('region', { name: 'Daily spending, November 2026' })
    expect(calls).toContain('/api/reports/daily-cash-flow?from=2026-11-01&to=2026-11-12&currency=BASE')
    await waitFor(() => expect(container.querySelectorAll('.daily-day')).toHaveLength(30))
    expect(container.querySelectorAll('.daily-day.future')).toHaveLength(18)
    cleanup()
    stubApi()
    const quarter = renderAt('/?period=this-quarter')
    await screen.findByRole('region', { name: 'Daily spending, November 2026' })
    expect(calls).toContain('/api/reports/daily-cash-flow?from=2026-11-01&to=2026-11-12&currency=BASE')
    await waitFor(() => expect(quarter.container.querySelectorAll('.daily-day')).toHaveLength(30))
    expect(quarter.container.querySelectorAll('.daily-day.future')).toHaveLength(18)
  })

  it('puts a day without a rate in the page’s notice', async () => {
    stubApi({ missingRate: true })
    const { container } = renderAt('/?period=previous-month')
    const panel = await screen.findByRole('region', { name: 'Daily spending, October 2026' })
    await waitFor(() => expect(panel.querySelector('.daily-day[data-day="2026-10-05"]')!.classList.contains('missing')).toBe(true))
    expect(container.querySelector('.notice')!.textContent).toContain('USD')
  })

  it('never holds up the chart and the table', async () => {
    stubApi({ dailyFails: true })
    renderAt('/?period=previous-month')
    await screen.findByRole('table', { name: 'Cash flow in EUR' })
    const panel = await screen.findByRole('region', { name: 'Daily spending, October 2026' })
    await within(panel).findByRole('alert')
    expect(screen.getByRole('figure', { name: 'Income and expenses in EUR' })).toBeDefined()
  })
})

describe('Entries’ payee option in another case (D-568)', () => {
  const counterparties: Counterparty[] = testLedger.counterparties
  const familyPayees = [{ name: 'ODIDO', budgets: [{ id: 7, name: 'Home' }] }]

  it('chooses the counterparty of the same name in any case, listed once', () => {
    const name = counterparties[0].name
    const options = payeeFilterOptions(counterparties, familyPayees, name.toUpperCase())
    expect(options.filter((o) => o.name.toLowerCase() === name.toLowerCase())).toHaveLength(1)
    expect(payeeFilterValue('', name.toUpperCase(), options)).toBe(`c:${counterparties[0].id}`)
    // A family payee's name in another case is that payee's option, once.
    const family = payeeFilterOptions(counterparties, familyPayees, 'odido')
    expect(family.filter((o) => o.name.toLowerCase() === 'odido')).toHaveLength(1)
    expect(payeeFilterValue('', 'odido', family)).toBe('p:ODIDO')
    // A name nobody has stays its own option.
    expect(payeeFilterValue('', 'Nobody', payeeFilterOptions(counterparties, [], 'Nobody'))).toBe('p:Nobody')
  })
})
