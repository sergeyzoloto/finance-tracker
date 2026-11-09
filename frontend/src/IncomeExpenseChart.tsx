import {
  Bar, BarChart, CartesianGrid, ComposedChart, Legend, Line, ResponsiveContainer, Tooltip, XAxis, YAxis,
  type TooltipContentProps,
} from 'recharts'
import {
  changeOf, changeText, MISSING, monthLabel, type Cell, type CompareRole, type MonthPair, type RangeBars,
} from './dashboard'
import { formatMoney, toChartNumber } from './money'

// Categorical slots 1 and 2 of a colour-blind-safe palette, checked against the white page. The page's green and
// red text colours would be hard to tell apart for many readers. The comparison drawn behind the months is the same
// hue, lighter, as a dashed line with dots (D-399); below 3:1 against the page, so the legend names it and the
// table and the tooltip give its figures.
const INCOME = '#2a78d6'
const EXPENSE = '#eb6834'
const INCOME_BEFORE = '#7aaae6'
const EXPENSE_BEFORE = '#f29b74'
const GRID = '#e6e8eb'

/** How the legend names a comparison drawn behind the months. */
export const BEHIND: Record<CompareRole, string> = {
  year: 'a year earlier',
  previous: 'previous period',
  'previous-year': 'previous year',
}

export type ChartData =
  | { form: 'ranges'; bars: RangeBars[] }
  | { form: 'months'; months: MonthPair[]; role: CompareRole | null }

/**
 * Income against expenses in one currency (D-399). For a section of fewer than 3 calendar months, a pair of bars per
 * range, oldest first: the year earlier, the previous period, the period, each named by its role and its range. For
 * more, a pair of bars per month, as the cash flow's subtotals, and the comparison drawn has its months paired with
 * them behind the bars. The comparison table below is the chart's table view.
 */
export default function IncomeExpenseChart({ data, currency, rounded }: { data: ChartData; currency: string; rounded: boolean }) {
  const ticks = tickFormat(currency)
  const yAxis = (
    <YAxis tickFormatter={(value: number) => ticks.format(value)} tickLine={false} axisLine={false} width={64}
      tick={{ fill: 'var(--muted)', fontSize: 12 }} />
  )
  const bars = [
    <Bar key="income" dataKey="incomeHeight" name="Income" fill={INCOME} maxBarSize={24} radius={[4, 4, 0, 0]} />,
    <Bar key="expense" dataKey="expenseHeight" name="Expenses" fill={EXPENSE} maxBarSize={24} radius={[4, 4, 0, 0]} />,
  ]
  // In the marks' order, with the names in the text colour and the series colour on the swatch.
  const legend = <Legend iconSize={10} itemSorter={null} wrapperStyle={{ fontSize: 13 }} formatter={legendText} />
  if (data.form === 'ranges') {
    const points = data.bars.map((b) => ({ ...b, incomeHeight: height(b.income), expenseHeight: height(b.expense) }))
    return (
      <ResponsiveContainer width="100%" height={280}>
        <BarChart data={points} barGap={2} barCategoryGap="25%" margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
          <CartesianGrid vertical={false} stroke={GRID} />
          <XAxis dataKey="key" tickLine={false} axisLine={{ stroke: GRID }} interval={0} height={42}
            tick={<RangeTick bars={data.bars} />} />
          {yAxis}
          <Tooltip cursor={{ fill: '#f2f4f6' }} content={<RangeTooltip currency={currency} rounded={rounded} />} />
          {legend}
          {bars}
        </BarChart>
      </ResponsiveContainer>
    )
  }
  const behind = data.role && BEHIND[data.role]
  const points = data.months.map((m) => ({
    ...m,
    label: monthLabel(m.month),
    incomeHeight: height(m.income),
    expenseHeight: height(m.expense),
    incomeBefore: m.before && height(m.before.income),
    expenseBefore: m.before && height(m.before.expense),
  }))
  return (
    <ResponsiveContainer width="100%" height={260}>
      <ComposedChart data={points} barGap={2} barCategoryGap="25%" margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
        <CartesianGrid vertical={false} stroke={GRID} />
        <XAxis dataKey="label" tickLine={false} axisLine={{ stroke: GRID }} tick={{ fill: 'var(--muted)', fontSize: 12 }} />
        {yAxis}
        <Tooltip cursor={{ fill: '#f2f4f6' }}
          content={<MonthTooltip currency={currency} rounded={rounded} behind={behind ?? null} />} />
        {legend}
        {bars}
        {behind && [
          <Line key="income-before" dataKey="incomeBefore" name={`Income, ${behind}`} stroke={INCOME_BEFORE}
            strokeWidth={2} strokeDasharray="5 4" dot={{ r: 4, fill: INCOME_BEFORE, stroke: 'white', strokeWidth: 2 }}
            activeDot={{ r: 5 }} isAnimationActive={false} legendType="plainline" />,
          <Line key="expense-before" dataKey="expenseBefore" name={`Expenses, ${behind}`} stroke={EXPENSE_BEFORE}
            strokeWidth={2} strokeDasharray="5 4" dot={{ r: 4, fill: EXPENSE_BEFORE, stroke: 'white', strokeWidth: 2 }}
            activeDot={{ r: 5 }} isAnimationActive={false} legendType="plainline" />,
        ]}
      </ComposedChart>
    </ResponsiveContainer>
  )
}

/** No bar where nothing was posted, or where a figure can't be converted; the tooltip says which. */
const height = (cell: Cell) => (typeof cell === 'string' ? toChartNumber(cell) : 0)

const legendText = (name: string) => <span className="muted">{name}</span>

const shown = (amount: Cell, currency: string, rounded: boolean) =>
  amount === null ? '—' : amount === MISSING ? 'rate missing' : formatMoney(amount, currency, { rounded })

/** A range's axis label: its role above its name. Recharts passes the tick's place and value. */
function RangeTick({ x, y, payload, bars }: { x?: number; y?: number; payload?: { value: string }; bars: RangeBars[] }) {
  const bar = bars.find((b) => b.key === payload?.value)
  if (!bar) return null
  return (
    <text x={x} y={y} textAnchor="middle" fontSize={12} fill="var(--muted)">
      <tspan x={x} dy="0.9em">{bar.role}</tspan>
      <tspan x={x} dy="1.2em">{bar.label}</tspan>
    </text>
  )
}

function Series({ name, amount, color, currency, rounded, dashed = false }: {
  name: string; amount: Cell; color: string; currency: string; rounded: boolean; dashed?: boolean
}) {
  return (
    <div>
      <span className={`key${dashed ? ' dashed' : ''}`} style={dashed ? { borderColor: color } : { background: color }} />
      <strong>{shown(amount, currency, rounded)}</strong>{' '}
      <span className="muted">{name}</span>
    </div>
  )
}

/** Recharts fills in the hovered range's props. */
function RangeTooltip({ active, payload, currency, rounded }: Partial<TooltipContentProps> & { currency: string; rounded: boolean }) {
  const bars = payload?.[0]?.payload as RangeBars | undefined
  if (!active || !bars) return null
  return (
    <div className="chart-tooltip">
      <div className="muted">{bars.role}: {bars.label}</div>
      <Series name="Income" amount={bars.income} color={INCOME} currency={currency} rounded={rounded} />
      <Series name="Expenses" amount={bars.expense} color={EXPENSE} currency={currency} rounded={rounded} />
    </div>
  )
}

/** The hovered month and its amounts, then the comparison's month, its amounts and the change in % (D-399). */
function MonthTooltip({ active, payload, currency, rounded, behind }: Partial<TooltipContentProps> & {
  currency: string; rounded: boolean; behind: string | null
}) {
  const m = payload?.[0]?.payload as (MonthPair & { label: string }) | undefined
  if (!active || !m) return null
  const percent = (now: Cell, before: Cell) => {
    const text = changeText(changeOf(now, before))
    return text === '' ? '' : ` (${text})`
  }
  return (
    <div className="chart-tooltip">
      <div className="muted">{m.label}{m.note && `, ${m.note}`}</div>
      <Series name="Income" amount={m.income} color={INCOME} currency={currency} rounded={rounded} />
      <Series name="Expenses" amount={m.expense} color={EXPENSE} currency={currency} rounded={rounded} />
      {behind && m.before && (
        <>
          <div className="muted tooltip-before">{monthLabel(m.before.month)}, {behind}</div>
          <Series name={`Income${percent(m.income, m.before.income)}`} amount={m.before.income} color={INCOME_BEFORE}
            currency={currency} rounded={rounded} dashed />
          <Series name={`Expenses${percent(m.expense, m.before.expense)}`} amount={m.before.expense}
            color={EXPENSE_BEFORE} currency={currency} rounded={rounded} dashed />
        </>
      )}
    </div>
  )
}

/** Axis ticks such as "₽85K": the scale's round numbers, not amounts, so a float is fine here. */
function tickFormat(currency: string) {
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency, notation: 'compact', maximumFractionDigits: 1 })
  } catch {
    return new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 })
  }
}
