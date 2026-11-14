import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import {
  changeOf, changeText, MISSING, monthLabel, type Cell, type CompareRole, type MonthPair, type RangeBars, type YearRow,
} from './dashboard'
import { formatMoney, signOf, toChartNumber } from './money'

/** How the legend, the hover panel and the hidden table name the comparison marked on the months' bars (D-650). */
export const BEHIND: Record<CompareRole, string> = {
  year: 'a year earlier',
  previous: 'previous period',
  'previous-year': 'previous year',
}

export type ChartData =
  | { form: 'ranges'; bars: RangeBars[] }
  | { form: 'months'; months: MonthPair[]; role: CompareRole | null }
  | { form: 'years'; years: YearRow[] }

/** The values' font size in px (D-649: Mono 12px). */
export const VALUE_FONT = 12
/** Plex Mono's advance, 0.6em for every glyph of a currency's format (D-649). */
const ADVANCE = 0.6
/** Between a bar's end, or a marker, and its value (D-649, D-650). */
const AFTER_BAR = 6
const AFTER_MARKER = 7

/**
 * The room the bar column keeps for the widest value text (D-649): its characters × 0.6em of the values' 12px, plus
 * 8px, with no measuring. Characters are code points, as the font draws them.
 */
export function valueRoom(texts: string[]): number {
  const widest = Math.max(0, ...texts.map((t) => [...t].length))
  return widest * ADVANCE * VALUE_FONT + 8
}

/** The chart's scale (D-649): the largest value above 0 among its bars and its markers; 0 when none is. */
export function scaleOf(cells: Cell[]): number {
  return Math.max(0, ...cells.filter((c): c is string => typeof c === 'string').map(toChartNumber))
}

/** A value's share of the scale, 0 for anything that draws no bar (null, MISSING, 0 or below). */
export function reach(cell: Cell, scale: number): number {
  if (typeof cell !== 'string' || scale <= 0 || signOf(cell) <= 0) return 0
  return Math.min(1, toChartNumber(cell) / scale)
}

/** A place in the bar column as CSS: `share` of the column less the values' room. */
export const at = (share: number, room: number) => `calc((100% - ${room}px) * ${share})`

/** A value as the tables show it: "—" where nothing was posted, "rate missing" where it can't be converted. */
export const shown = (amount: Cell, currency: string, rounded: boolean) =>
  amount === null ? '—' : amount === MISSING ? 'rate missing' : formatMoney(amount, currency, { rounded })

/** One row of the chart, whatever its form. */
interface Row {
  key: string
  income: Cell
  expense: Cell
  label: { main: string; sub?: string; strong?: boolean; kind: 'range' | 'month' | 'year' }
  /** By month with a comparison: its month and figures, marked on the bars (D-650). */
  before?: { month: string; income: Cell; expense: Cell }
  /** By month: the month's ISO key, by year the year, for the hover panel. */
  note?: string
}

function rowsOf(data: ChartData): Row[] {
  if (data.form === 'ranges') {
    return data.bars.map((b) => ({
      key: b.key, income: b.income, expense: b.expense,
      label: { main: b.role, sub: b.label, strong: b.key === 'period', kind: 'range' },
    }))
  }
  if (data.form === 'years') {
    return data.years.map((y) => ({
      key: y.year, income: y.income, expense: y.expense, note: y.note,
      label: { main: y.year, sub: y.note, kind: 'year' },
    }))
  }
  return data.months.map((m) => ({
    key: m.month, income: m.income, expense: m.expense, note: m.note,
    label: { main: monthLabel(m.month), sub: m.note, kind: 'month' },
    ...(data.role !== null && m.before ? { before: m.before } : {}),
  }))
}

/** A comparison's figure is marked only where it has one above 0 (D-650). */
const marked = (cell: Cell | undefined): cell is string => typeof cell === 'string' && signOf(cell) > 0

/**
 * Income against expenses in one currency (D-647 to D-652), in plain HTML and CSS: a row per range (fewer than 3
 * calendar months), per month (3 to 24) or per calendar year (more), oldest first; in each row an income bar above an
 * expense bar from one baseline, each with its value after it. By month a drawn comparison is a marker on each bar at
 * the comparison month's value. The drawn rows are hidden from screen readers, which read a table of every row instead.
 */
export default function IncomeExpenseChart({ data, currency, rounded }: { data: ChartData; currency: string; rounded: boolean }) {
  const rows = rowsOf(data)
  const behind = data.form === 'months' && data.role !== null ? BEHIND[data.role] : null
  const scale = scaleOf(rows.flatMap((r): Cell[] => r.before
    ? [r.income, r.expense, r.before.income, r.before.expense] : [r.income, r.expense]))
  const room = valueRoom(rows.flatMap((r) => [shown(r.income, currency, rounded), shown(r.expense, currency, rounded)]))
  const hover = data.form !== 'ranges'
  const [active, setActive] = useState<{ key: string; top?: number; bottom?: number } | null>(null)
  const box = useRef<HTMLDivElement>(null)

  // A tap outside the chart hides the panel (D-651).
  useEffect(() => {
    if (active === null) return
    const outside = (event: PointerEvent) => {
      if (box.current && !box.current.contains(event.target as Node)) setActive(null)
    }
    document.addEventListener('pointerdown', outside)
    return () => document.removeEventListener('pointerdown', outside)
  }, [active])

  const point = (key: string) => (event: ReactPointerEvent<HTMLDivElement>) => {
    const row = event.currentTarget
    const chart = box.current
    if (!chart) return
    // Below the row, or above it for a row in the chart's lower half, so the panel stays inside the card.
    const lower = row.offsetTop + row.offsetHeight / 2 > chart.offsetHeight / 2
    setActive(lower ? { key, bottom: chart.offsetHeight - row.offsetTop } : { key, top: row.offsetTop + row.offsetHeight })
  }
  const shownRow = active && rows.find((r) => r.key === active.key)

  return (
    <div className={`ie-chart ie-${data.form}`} ref={box}
      onPointerLeave={hover ? (event) => { if (event.pointerType === 'mouse') setActive(null) } : undefined}>
      <div className="ie-legend" aria-hidden="true">
        <span><span className="ie-swatch income" />Income</span>
        <span><span className="ie-swatch expense" />Expenses</span>
        {behind !== null && <span><span className="ie-swatch marker" /><span className="ie-behind">{behind}</span></span>}
      </div>
      <div className="ie-rows" aria-hidden="true">
        {rows.map((row) => (
          <div key={row.key} className={`ie-row${active?.key === row.key ? ' active' : ''}`} data-key={row.key}
            onPointerEnter={hover ? point(row.key) : undefined} onClick={hover ? point(row.key) : undefined}>
            <div className={`ie-label ${row.label.kind}${row.label.strong ? ' own' : ''}`}>
              <span className="ie-main">{row.label.main}</span>
              {row.label.sub !== undefined && <span className="ie-sub">{row.label.sub}</span>}
            </div>
            <div className="ie-bars">
              <Line which="income" value={row.income} before={row.before?.income} scale={scale} room={room}
                currency={currency} rounded={rounded} />
              <Line which="expense" value={row.expense} before={row.before?.expense} scale={scale} room={room}
                currency={currency} rounded={rounded} />
            </div>
          </div>
        ))}
      </div>
      {hover && shownRow && (
        <div className="chart-tooltip ie-panel" role="presentation" aria-hidden="true"
          style={active!.top !== undefined ? { top: active!.top } : { bottom: active!.bottom }}>
          <Panel row={shownRow} behind={behind} currency={currency} rounded={rounded} />
        </div>
      )}
      <HiddenTable rows={rows} form={data.form} behind={behind} currency={currency} rounded={rounded} />
    </div>
  )
}

/**
 * One bar and its value (D-649): a bar from the baseline for a value above 0; a 2px stub for 0 or below; nothing for
 * null or MISSING. A comparison's marker (D-650) where it has a figure above 0; the value follows the marker when the
 * marker lies past the bar's end, so that a marker never crosses a value.
 */
export function Line({ which, value, before, scale, room, currency, rounded }: {
  which: 'income' | 'expense'; value: Cell; before?: Cell; scale: number; room: number; currency: string; rounded: boolean
}) {
  const bar = reach(value, scale)
  const stub = typeof value === 'string' && signOf(value) <= 0
  const mark = marked(before) ? reach(before, scale) : null
  const left = mark !== null && mark > bar ? `calc(${at(mark, room)} + ${AFTER_MARKER}px)`
    : bar > 0 ? `calc(${at(bar, room)} + ${AFTER_BAR}px)`
      : stub ? `${2 + AFTER_BAR}px` : `${AFTER_BAR}px`
  const text = shown(value, currency, rounded)
  return (
    <div className={`ie-line ${which}`}>
      {bar > 0 && <span className={`ie-bar ${which}`} style={{ width: at(bar, room) }} />}
      {stub && <span className="ie-stub" />}
      {mark !== null && <span className="ie-marker" style={{ left: `calc(${at(mark, room)} - 1px)` }} />}
      <span className={`ie-value${value === null ? ' none' : value === MISSING ? ' missing' : ''}`} style={{ left }}>
        {text}
      </span>
    </div>
  )
}

/**
 * The pointed row's figures (D-651): the month or year with its note, then the comparison's month and the change in %.
 * D-687: a comparison month with no figure in either series is left out; a series without a figure shows "—" alone.
 */
function Panel({ row, behind, currency, rounded }: { row: Row; behind: string | null; currency: string; rounded: boolean }) {
  const percent = (now: Cell, before: Cell) => {
    if (before === null) return ''
    const text = changeText(changeOf(now, before))
    return text === '' ? '' : ` (${text})`
  }
  const compared = row.before !== undefined && (row.before.income !== null || row.before.expense !== null)
  return (
    <>
      <div className="muted">{row.label.main}{row.note && `, ${row.note}`}</div>
      <Series name="Income" amount={row.income} kind="income" currency={currency} rounded={rounded} />
      <Series name="Expenses" amount={row.expense} kind="expense" currency={currency} rounded={rounded} />
      {behind !== null && row.before && compared && (
        <>
          <div className="muted tooltip-before">{monthLabel(row.before.month)}, {behind}</div>
          <Series name={`Income${percent(row.income, row.before.income)}`} amount={row.before.income} kind="marker"
            currency={currency} rounded={rounded} />
          <Series name={`Expenses${percent(row.expense, row.before.expense)}`} amount={row.before.expense} kind="marker"
            currency={currency} rounded={rounded} />
        </>
      )}
    </>
  )
}

function Series({ name, amount, kind, currency, rounded }: {
  name: string; amount: Cell; kind: 'income' | 'expense' | 'marker'; currency: string; rounded: boolean
}) {
  return (
    <div>
      <span className={`key ${kind}`} />
      <strong>{shown(amount, currency, rounded)}</strong>{' '}
      <span className="muted">{name}</span>
    </div>
  )
}

/** The chart for screen readers (D-651): every row with its income and expenses, and a comparison's month and figures. */
function HiddenTable({ rows, form, behind, currency, rounded }: {
  rows: Row[]; form: ChartData['form']; behind: string | null; currency: string; rounded: boolean
}) {
  const head = form === 'ranges' ? 'Range' : form === 'years' ? 'Year' : 'Month'
  const value = (cell: Cell) => shown(cell, currency, rounded)
  return (
    // A table sizes to its content whatever its width says, so the clipping box is a div around it.
    <div className="visually-hidden">
    <table>
      <caption>Income and expenses in {currency}</caption>
      <thead>
        <tr>
          <th scope="col">{head}</th><th scope="col">Income</th><th scope="col">Expenses</th>
          {behind !== null && (
            <><th scope="col">Month, {behind}</th><th scope="col">Income, {behind}</th><th scope="col">Expenses, {behind}</th></>
          )}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.key}>
            <th scope="row">{row.label.main}{row.label.sub !== undefined && `, ${row.label.sub}`}</th>
            <td>{value(row.income)}</td>
            <td>{value(row.expense)}</td>
            {behind !== null && (row.before ? (
              <><td>{monthLabel(row.before.month)}</td><td>{value(row.before.income)}</td><td>{value(row.before.expense)}</td></>
            ) : <><td>—</td><td>—</td><td>—</td></>)}
          </tr>
        ))}
      </tbody>
    </table>
    </div>
  )
}
