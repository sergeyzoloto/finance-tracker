import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis, type TooltipContentProps } from 'recharts'
import { MISSING, monthLabel, type CashFlowTable, type Cell } from './dashboard'
import { formatMoney, toChartNumber } from './money'

// Categorical slots 1 and 2 of a colour-blind-safe palette, checked against the white page. The page's green and
// red text colours would be hard to tell apart for many readers.
const INCOME = '#2a78d6'
const EXPENSE = '#eb6834'
const GRID = '#e6e8eb'

interface MonthBars {
  label: string
  income: Cell
  expense: Cell
  /** Bar heights only; the tooltip shows the decimal strings. */
  incomeHeight: number
  expenseHeight: number
}

/**
 * Income against expenses per month in one currency: the subtotals of the cash flow table, which is also the
 * chart's table view.
 */
export default function IncomeExpenseChart({ table }: { table: CashFlowTable }) {
  const data: MonthBars[] = table.months.map((month, i) => {
    const income: Cell = table.income.subtotal.months[i]
    const expense: Cell = table.expense.subtotal.months[i]
    return {
      label: monthLabel(month),
      income,
      expense,
      incomeHeight: height(income),
      expenseHeight: height(expense),
    }
  })
  const ticks = tickFormat(table.currency)
  return (
    <ResponsiveContainer width="100%" height={260}>
      <BarChart data={data} barGap={2} barCategoryGap="25%" margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
        <CartesianGrid vertical={false} stroke={GRID} />
        <XAxis dataKey="label" tickLine={false} axisLine={{ stroke: GRID }} tick={{ fill: 'var(--muted)', fontSize: 12 }} />
        <YAxis tickFormatter={(value: number) => ticks.format(value)} tickLine={false} axisLine={false} width={64}
          tick={{ fill: 'var(--muted)', fontSize: 12 }} />
        <Tooltip cursor={{ fill: '#f2f4f6' }} content={<MonthTooltip currency={table.currency} rounded={table.converted} />} />
        {/* In the bars' order, with the names in the text colour and the series colour on the swatch. */}
        <Legend iconType="square" iconSize={10} itemSorter={null} wrapperStyle={{ fontSize: 13 }}
          formatter={legendText} />
        <Bar dataKey="incomeHeight" name="Income" fill={INCOME} maxBarSize={24} radius={[4, 4, 0, 0]} />
        <Bar dataKey="expenseHeight" name="Expenses" fill={EXPENSE} maxBarSize={24} radius={[4, 4, 0, 0]} />
      </BarChart>
    </ResponsiveContainer>
  )
}

/** No bar where nothing was posted, or where a figure can't be converted; the tooltip says which. */
const height = (cell: Cell) => (typeof cell === 'string' ? toChartNumber(cell) : 0)

const legendText = (name: string) => <span className="muted">{name}</span>

/** Recharts fills in the hovered month's props. */
function MonthTooltip({ active, payload, currency, rounded }: Partial<TooltipContentProps> & { currency: string; rounded: boolean }) {
  const bars = payload?.[0]?.payload as MonthBars | undefined
  if (!active || !bars) return null
  const series = [['Income', bars.income, INCOME], ['Expenses', bars.expense, EXPENSE]] as const
  return (
    <div className="chart-tooltip">
      <div className="muted">{bars.label}</div>
      {series.map(([name, amount, color]) => (
        <div key={name}>
          <span className="key" style={{ background: color }} />
          <strong>{amount === null ? '—' : amount === MISSING ? 'rate missing' : formatMoney(amount, currency, { rounded })}</strong>{' '}
          <span className="muted">{name}</span>
        </div>
      ))}
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
