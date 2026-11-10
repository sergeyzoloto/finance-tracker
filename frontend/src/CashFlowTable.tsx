import {
  changeOf, changeText, effectOf, MISSING, type Cell, type CompareLine, type CompareTable as Table,
} from './dashboard'
import { formatMoney, signOf } from './money'

/**
 * One currency's income and expenses compared (D-393): the categories as rows; the period's total, then each
 * comparison's total with the change in % (D-398), each column headed by its role above its range's name. A dash
 * marks a range in which nothing was posted. In the base currency the results of exchange rates follow the net,
 * amounts only, and "rate missing" marks a figure that can't be converted, its % left empty.
 */
export default function CashFlowTable({ table }: { table: Table }) {
  const [period, ...comparisons] = table.columns
  // D-419: the budget's name follows the icon only when this table holds more than one family budget's categories.
  const budgets = new Set([...table.income.lines, ...table.expense.lines].flatMap((l) => (l.family ? [l.family] : [])))
  const width = 2 + comparisons.length * 2
  const row = (line: CompareLine, className = '', signed = false) => (
    <tr key={line.key} className={className}>
      <th scope="row">
        {line.label}
        {line.family && <SharedMark budget={line.family} named={budgets.size > 1} />}
      </th>
      <Amount amount={line.amounts[0]} currency={table.currency} rounded={table.converted} signed={signed} />
      {comparisons.map((column, i) => {
        const before = line.amounts[i + 1]
        const change = changeOf(line.amounts[0], before, line.key === 'net')
        const effect = line.good === null ? null : effectOf(change, line.good)
        return [
          <Amount key={column.key} amount={before} currency={table.currency} rounded={table.converted} signed={signed}
            className="compared" />,
          <td key={`${column.key}%`} className={`amount nowrap change ${effect ?? ''}`}>
            {line.good === null ? '' : changeText(change)}
          </td>,
        ]
      })}
    </tr>
  )
  return (
    <div className="scroll-x">
      <table className="cash-flow" aria-label={`Cash flow in ${table.currency}`}>
        <thead>
          <tr>
            <th scope="col">{table.currency}</th>
            <th scope="col" className="amount"><span className="col-role">{period.role}</span> {period.label}</th>
            {comparisons.map((column) => [
              <th key={column.key} scope="col" className="amount compared">
                <span className="col-role">{column.role}</span> {column.label}
              </th>,
              <th key={`${column.key}%`} scope="col" className="amount change">
                <span className="col-role">Change</span> %
              </th>,
            ])}
          </tr>
        </thead>
        {([['Income', table.income], ['Expenses', table.expense]] as const).map(([title, section]) => (
          <tbody key={title}>
            <tr className="section"><th scope="rowgroup" colSpan={width}>{title}</th></tr>
            {section.lines.map((line) => row(line))}
            {row(section.subtotal, 'subtotal')}
          </tbody>
        ))}
        <tbody>{row(table.net, 'net', true)}</tbody>
        {table.exchange.length > 0 && (
          <tbody>
            <tr className="section"><th scope="rowgroup" colSpan={width}>Exchange rates (not in the net)</th></tr>
            {table.exchange.map((line) => row(line, '', true))}
          </tbody>
        )}
      </table>
    </div>
  )
}

const sign = (amount: Cell) => (typeof amount !== 'string' ? '' : signOf(amount) < 0 ? 'expense' : 'income')

function Amount({ amount, currency, rounded, signed, className = '' }: {
  amount: Cell
  currency: string
  rounded: boolean
  signed: boolean
  className?: string
}) {
  return (
    <td className={`amount nowrap ${className} ${signed ? sign(amount) : ''}`}>
      {amount === null ? <span className="muted">—</span>
        : amount === MISSING ? <span className="missing" title="No exchange rate for a currency on some day">rate missing</span>
          : formatMoney(amount, currency, { rounded })}
    </td>
  )
}

/**
 * D-419: a family budget's category is marked by a small "shared" icon after its name, two people drawn for the app
 * in the text's colour, named "Shared: <the budget>"; the budget's name follows it only where `named`.
 */
export function SharedMark({ budget, named }: { budget: string; named: boolean }) {
  const label = `Shared: ${budget}`
  return (
    <span className="shared-mark">
      <svg role="img" aria-label={label} viewBox="0 0 16 16" width="1em" height="1em" fill="currentColor">
        <title>{label}</title>
        <circle cx="5.5" cy="4.75" r="2.4" />
        <path d="M1 13.5c0-2.7 2-4.6 4.5-4.6s4.5 1.9 4.5 4.6z" />
        <circle cx="11.25" cy="5.5" r="2" />
        <path d="M10.4 9.1a4 4 0 0 1 .85-.1c2.1 0 3.75 1.7 3.75 4.1v.4h-3.9c0-1.75-.25-3.2-.7-4.4z" />
      </svg>
      {named && <span className="shared-name">{budget}</span>}
    </span>
  )
}
