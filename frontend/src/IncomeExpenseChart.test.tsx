import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { cashFlowTables, compareColumns, compareTables, comparisons, MISSING, rangeBars, type Cell } from './dashboard'
import IncomeExpenseChart, { at, BEHIND, reach, scaleOf, valueRoom, type ChartData } from './IncomeExpenseChart'

// UX-16 (D-647 to D-652): the chart "Income and expenses" in horizontal bars, plain HTML and CSS.

afterEach(cleanup)

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')

const draw = (data: ChartData, { currency = 'EUR', rounded = false } = {}) =>
  render(<IncomeExpenseChart data={data} currency={currency} rounded={rounded} />).container

/** The drawn rows' labels, top to bottom by their place in the DOM. */
const labels = (container: HTMLElement) =>
  [...container.querySelectorAll('.ie-row .ie-main')].map((e) => e.textContent)

/** A row's line of one series. */
const lineOf = (container: HTMLElement, key: string, which: 'income' | 'expense') =>
  container.querySelector<HTMLElement>(`.ie-row[data-key="${key}"] .ie-line.${which}`)!

/** A length as the browser's style serializes it, so that two ways of writing one calc() compare equal. */
const css_ = (value: string) => {
  const element = document.createElement('span')
  element.style.left = value
  return element.style.left
}

const months = (role: 'year' | 'previous' | null, before = true): ChartData => ({
  form: 'months', role,
  months: [
    { month: '2026-08', income: '1000', expense: '400', ...(before ? { before: { month: '2025-08', income: '800', expense: '500' } } : {}) },
    { month: '2026-09', income: '1200', expense: '600', ...(before ? { before: { month: '2025-09', income: null, expense: '0' } } : {}) },
    { month: '2026-10', note: 'to Oct 13', income: null, expense: '200', ...(before ? { before: { month: '2025-10', income: '700', expense: '2000' } } : {}) },
  ],
})

describe('the rows (D-649)', () => {
  it('are oldest first by range: the year earlier, the previous period, the period, which alone is 600', () => {
    const c = comparisons({ from: '2026-10-01', to: '2026-10-31' }, '2026-11-13')!
    const row = (month: string, total: string) =>
      ({ month, categoryCode: 'SALARY', categoryName: 'Salary', categoryType: 'INCOME' as const, currency: 'EUR', total })
    const [table] = compareTables(compareColumns(c), [
      cashFlowTables([row('2026-10', '30')], ['2026-10']),
      cashFlowTables([row('2025-10', '10')], ['2025-10']),
      cashFlowTables([row('2026-09', '20')], ['2026-09']),
    ], false)
    const container = draw({ form: 'ranges', bars: rangeBars(table) })
    expect(labels(container)).toEqual(['Year earlier', 'Previous period', 'Period'])
    expect([...container.querySelectorAll('.ie-row .ie-sub')].map((e) => e.textContent)).toEqual(['Oct 2025', 'Sep 2026', 'Oct 2026'])
    expect([...container.querySelectorAll('.ie-label.own .ie-main')].map((e) => e.textContent)).toEqual(['Period'])
    expect(css).toMatch(/\.ie-label\.own \.ie-main \{ font-weight: 600; \}/)
  })

  it('are oldest first by month, with a month’s note, and by year, with a year’s', () => {
    expect(labels(draw(months(null, false)))).toEqual(['Aug 2026', 'Sep 2026', 'Oct 2026'])
    expect(draw(months(null, false)).querySelector('.ie-row[data-key="2026-10"] .ie-sub')!.textContent).toBe('to Oct 13')
    const years = draw({ form: 'years', years: [
      { year: '2024', note: 'from Dec 1', income: '1', expense: '2' }, { year: '2025', income: '3', expense: '4' },
    ] })
    expect(labels(years)).toEqual(['2024', '2025'])
    expect(years.querySelector('.ie-row[data-key="2024"] .ie-sub')!.textContent).toBe('from Dec 1')
  })

  it('put income above expenses in each row, from one baseline', () => {
    const container = draw(months(null, false))
    for (const row of container.querySelectorAll('.ie-row')) {
      expect([...row.querySelectorAll('.ie-line')].map((l) => l.className)).toEqual(['ie-line income', 'ie-line expense'])
    }
    expect(css).toMatch(/\.ie-bars \{[^}]*border-left: 1px solid var\(--chart-grid\);/)
    expect(css).toMatch(/\.ie-rows \{ display: grid; grid-template-columns: fit-content\(40%\) minmax\(0, 1fr\);/)
    // 12px by range, 10px by month and by year; 2px between a row's bars; rows 10px and 6px apart.
    expect(css).toMatch(/\.ie-line \{ position: relative; height: 12px; \}/)
    expect(css).toMatch(/\.ie-months \.ie-line, \.ie-years \.ie-line \{ height: 10px; \}/)
    expect(css).toMatch(/\.ie-bars \{[^}]*gap: 2px;/)
    expect(css).toMatch(/\.ie-row > \* \{ padding-block: 5px; \}/)
    expect(css).toMatch(/\.ie-months \.ie-row > \*, \.ie-years \.ie-row > \* \{ padding-block: 3px; \}/)
    expect(css).toMatch(/\.ie-bar \{[^}]*border-radius: 0 4px 4px 0;/)
  })
})

describe('the scale (D-649)', () => {
  it('is the largest value above 0 among the bars and the markers', () => {
    expect(scaleOf(['10', '-50', null, MISSING, '2000.5'])).toBe(2000.5)
    expect(scaleOf([null, MISSING, '0', '-3'])).toBe(0)
  })

  it('keeps the room of the widest value text: its characters × 0.6 × 12px + 8px', () => {
    expect(valueRoom(['€1,000.00', '€5.00', '—'])).toBeCloseTo(9 * 7.2 + 8)
    expect(valueRoom([])).toBe(8)
  })

  it('gives each bar its share of the column less the room', () => {
    expect(reach('500', 2000)).toBe(0.25)
    expect(reach('0', 2000)).toBe(0)
    expect(reach('-5', 2000)).toBe(0)
    expect(reach(null, 2000)).toBe(0)
    expect(reach(MISSING, 2000)).toBe(0)
    expect(at(0.25, 72.8)).toBe('calc((100% - 72.8px) * 0.25)')
  })

  it('draws each bar by its value against the chart’s largest, markers included', () => {
    const container = draw(months('year'))
    // The largest is the comparison's 2000 on Oct's expenses; the widest text "€1,200.00", 9 characters.
    const room = 9 * 7.2 + 8
    const width = (key: string, which: 'income' | 'expense') =>
      lineOf(container, key, which).querySelector<HTMLElement>('.ie-bar')?.style.width
    expect(width('2026-09', 'income')).toBe(css_(`calc((100% - ${room}px) * 0.6)`))
    expect(width('2026-08', 'expense')).toBe(css_(`calc((100% - ${room}px) * 0.2)`))
    // The value 6px after its bar, in the tables' format.
    const value = lineOf(container, '2026-09', 'income').querySelector<HTMLElement>('.ie-value')!
    expect(value.textContent).toBe('€1,200.00')
    expect(value.style.left).toBe(css_(`calc(calc((100% - ${room}px) * 0.6) + 6px)`))
  })

  it('formats converted values with the currency’s usual decimals, as the tables do', () => {
    const container = draw({ form: 'ranges', bars: [{ key: 'period', role: 'Period', label: 'Oct 2026', income: '10.555', expense: '3' }] },
      { rounded: true })
    expect([...container.querySelectorAll('.ie-value')].map((v) => v.textContent)).toEqual(['€10.56', '€3.00'])
  })
})

describe('what draws no bar (D-649)', () => {
  const container = () => draw({ form: 'ranges', bars: [
    { key: 'year', role: 'Year earlier', label: 'Oct 2025', income: null, expense: MISSING },
    { key: 'period', role: 'Period', label: 'Oct 2026', income: '0', expense: '-12.5' },
  ] })

  it('shows "—" in --muted where nothing was posted', () => {
    const line = lineOf(container(), 'year', 'income')
    expect(line.querySelector('.ie-bar')).toBeNull()
    expect(line.querySelector('.ie-value.none')!.textContent).toBe('—')
    expect(css).toMatch(/\.ie-value\.none \{ color: var\(--muted\); \}/)
  })

  it('shows "rate missing" as the tables do where a figure can’t be converted', () => {
    const line = lineOf(container(), 'year', 'expense')
    expect(line.querySelector('.ie-bar')).toBeNull()
    expect(line.querySelector('.ie-value.missing')!.textContent).toBe('rate missing')
  })

  it('draws a 2px stub at the baseline and the signed value for 0 or below', () => {
    const c = container()
    for (const [which, text] of [['income', '€0.00'], ['expense', '-€12.50']] as const) {
      const line = lineOf(c, 'period', which)
      expect(line.querySelector('.ie-bar')).toBeNull()
      expect(line.querySelector('.ie-stub')).not.toBeNull()
      expect(line.querySelector<HTMLElement>('.ie-value')!.textContent).toBe(text)
      expect(line.querySelector<HTMLElement>('.ie-value')!.style.left).toBe('8px')
    }
    expect(css).toMatch(/\.ie-stub \{[^}]*width: 2px;[^}]*background: var\(--border\);/)
  })
})

describe('the comparison’s markers (D-650)', () => {
  const room = 9 * 7.2 + 8

  it('stand at the comparison month’s value on each bar', () => {
    const container = draw(months('year'))
    const marker = lineOf(container, '2026-08', 'income').querySelector<HTMLElement>('.ie-marker')!
    expect(marker.style.left).toBe(css_(`calc(calc((100% - ${room}px) * 0.4) - 1px)`))
    expect(container.querySelectorAll('.ie-marker')).toHaveLength(4)
  })

  it('are left out where the comparison has nothing, no rate, or 0 or below', () => {
    const container = draw(months('year'))
    expect(lineOf(container, '2026-09', 'income').querySelector('.ie-marker')).toBeNull() // null
    expect(lineOf(container, '2026-09', 'expense').querySelector('.ie-marker')).toBeNull() // 0
    const missing = draw({ form: 'months', role: 'previous', months: [
      { month: '2026-10', income: '5', expense: '5', before: { month: '2026-09', income: MISSING, expense: '-1' } },
    ] })
    expect(missing.querySelectorAll('.ie-marker')).toHaveLength(0)
  })

  it('push the value after them when they lie past the bar’s end, never across it', () => {
    const container = draw(months('year'))
    // Oct's expenses: 200 now, 2000 a year earlier: the value follows the marker.
    const behind = lineOf(container, '2026-10', 'expense').querySelector<HTMLElement>('.ie-value')!
    expect(behind.style.left).toBe(css_(`calc(calc((100% - ${room}px) * 1) + 7px)`))
    // Oct's income: nothing now, 700 a year earlier: "—" after the marker too.
    const none = lineOf(container, '2026-10', 'income').querySelector<HTMLElement>('.ie-value')!
    expect(none.textContent).toBe('—')
    expect(none.style.left).toBe(css_(`calc(calc((100% - ${room}px) * 0.35) + 7px)`))
    // Aug's income: 1000 now, 800 before: the marker inside the bar, the value after the bar.
    expect(lineOf(container, '2026-08', 'income').querySelector<HTMLElement>('.ie-value')!.style.left)
      .toBe(css_(`calc(calc((100% - ${room}px) * 0.5) + 6px)`))
  })

  it('are a 2px --text line 4px taller than the bar, edged with --surface', () => {
    expect(css).toMatch(/\.ie-marker \{ position: absolute; top: -2px; bottom: -2px; width: 2px; background: var\(--text\);\s*box-shadow: -1px 0 0 var\(--surface\), 1px 0 0 var\(--surface\); \}/)
  })
})

describe('the legend (D-649, D-650)', () => {
  const legend = (container: HTMLElement) => container.querySelector('.ie-legend')!.textContent

  it('names income and expenses in every form, and by month the comparison drawn', () => {
    expect(legend(draw({ form: 'ranges', bars: [] }))).toBe('IncomeExpenses')
    expect(legend(draw(months(null, false)))).toBe('IncomeExpenses')
    expect(legend(draw({ form: 'years', years: [] }))).toBe('IncomeExpenses')
    expect(legend(draw(months('year')))).toBe(`IncomeExpenses${BEHIND.year}`)
    expect(legend(draw(months('previous')))).toBe(`IncomeExpenses${BEHIND.previous}`)
    expect(draw(months('year')).querySelector('.ie-legend .ie-swatch.marker')).not.toBeNull()
  })

  it('is hidden from screen readers, which read the table', () => {
    expect(draw(months('year')).querySelector('.ie-legend')!.getAttribute('aria-hidden')).toBe('true')
  })
})

describe('the hover panel (D-651)', () => {
  const panel = (container: HTMLElement) => container.querySelector('.ie-panel')

  it('shows nothing by range, where every figure is on the chart', () => {
    const container = draw({ form: 'ranges', bars: [{ key: 'period', role: 'Period', label: 'Oct 2026', income: '1', expense: '2' }] })
    fireEvent.pointerEnter(container.querySelector('.ie-row')!)
    fireEvent.click(container.querySelector('.ie-row')!)
    expect(panel(container)).toBeNull()
  })

  it('shows a month’s figures with its note while pointed at, and hides when the pointer leaves', () => {
    const container = draw(months(null, false))
    const row = container.querySelector('.ie-row[data-key="2026-10"]')!
    fireEvent.pointerEnter(row)
    expect(row.className).toBe('ie-row active')
    expect(panel(container)!.className).toBe('chart-tooltip ie-panel')
    expect([...panel(container)!.children].map((e) => e.textContent)).toEqual([
      'Oct 2026, to Oct 13', '— Income', '€200.00 Expenses'])
    fireEvent.pointerLeave(container.querySelector('.ie-chart')!, { pointerType: 'mouse' })
    expect(panel(container)).toBeNull()
    expect(row.className).toBe('ie-row')
  })

  it('adds the comparison’s month, its figures and each change in %', () => {
    const container = draw(months('year'))
    fireEvent.pointerEnter(container.querySelector('.ie-row[data-key="2026-08"]')!)
    expect([...panel(container)!.children].map((e) => e.textContent)).toEqual([
      'Aug 2026', '€1,000.00 Income', '€400.00 Expenses',
      'Aug 2025, a year earlier', '€800.00 Income (+25.0%)', '€500.00 Expenses (−20.0%)'])
  })

  it('opens on a tap and closes on a tap outside the chart', () => {
    const container = draw({ form: 'years', years: [{ year: '2025', income: '3', expense: '4' }] })
    fireEvent.click(container.querySelector('.ie-row')!)
    expect([...panel(container)!.children].map((e) => e.textContent)).toEqual(['2025', '€3.00 Income', '€4.00 Expenses'])
    fireEvent.pointerDown(container.querySelector('.ie-row')!)
    expect(panel(container)).not.toBeNull()
    fireEvent.pointerDown(document.body)
    expect(panel(container)).toBeNull()
  })

  it('has no title attributes', () => {
    const container = draw(months('year'))
    fireEvent.pointerEnter(container.querySelector('.ie-row')!)
    expect(container.querySelector('[title]')).toBeNull()
  })
})

describe('the table for screen readers (D-651)', () => {
  const table = (container: HTMLElement) => within(container).getByRole('table', { name: 'Income and expenses in EUR' })
  const cells = (t: HTMLElement) =>
    [...t.querySelectorAll('tr')].map((tr) => [...tr.querySelectorAll('th, td')].map((c) => c.textContent))

  it('carries every range, and the drawn rows are hidden', () => {
    const container = draw({ form: 'ranges', bars: [
      { key: 'previous', role: 'Previous period', label: 'Sep 2026', income: null, expense: MISSING as Cell },
      { key: 'period', role: 'Period', label: 'Oct 2026', income: '30', expense: '-5' },
    ] })
    expect(cells(table(container))).toEqual([
      ['Range', 'Income', 'Expenses'],
      ['Previous period, Sep 2026', '—', 'rate missing'],
      ['Period, Oct 2026', '€30.00', '-€5.00'],
    ])
    expect(table(container).parentElement!.className).toBe('visually-hidden')
    expect(container.querySelector('.ie-rows')!.getAttribute('aria-hidden')).toBe('true')
  })

  it('carries every month with the comparison’s month and figures', () => {
    expect(cells(table(draw(months('year'))))).toEqual([
      ['Month', 'Income', 'Expenses', 'Month, a year earlier', 'Income, a year earlier', 'Expenses, a year earlier'],
      ['Aug 2026', '€1,000.00', '€400.00', 'Aug 2025', '€800.00', '€500.00'],
      ['Sep 2026', '€1,200.00', '€600.00', 'Sep 2025', '—', '€0.00'],
      ['Oct 2026, to Oct 13', '—', '€200.00', 'Oct 2025', '€700.00', '€2,000.00'],
    ])
  })

  it('carries every month without a comparison, and every year', () => {
    expect(cells(table(draw(months(null, false))))[0]).toEqual(['Month', 'Income', 'Expenses'])
    expect(cells(table(draw({ form: 'years', years: [{ year: '2024', note: 'from Dec 1', income: null, expense: '2' }] })))).toEqual([
      ['Year', 'Income', 'Expenses'], ['2024, from Dec 1', '—', '€2.00']])
  })

  it('is the figure’s only table: the figure keeps its name in the dashboard', () => {
    draw(months('year'))
    expect(screen.getAllByRole('table')).toHaveLength(1)
  })
})
