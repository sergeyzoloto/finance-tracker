import {
  changeOf, changeText, effectOf, MISSING, shareOf, shareText, type Cell, type CompareLine, type CompareSection,
  type CompareTable as Table,
} from './dashboard'
import { formatMoney, signOf } from './money'
import { SharedIcon } from './components'

/**
 * One currency's income and expenses compared (D-393): the categories as rows; the period's total, then each
 * comparison's total with the change in % (D-398), each column headed by its role above its range's name. A dash
 * marks a range in which nothing was posted. Right after the period's column, each category's share of Total income
 * or Total expenses in the period, with a bar (D-595); the subtotals, the net and the exchange lines leave it empty. In the base currency the results of exchange rates follow the net,
 * amounts only, and "rate missing" marks a figure that can't be converted, its % left empty.
 */
export default function CashFlowTable({ table }: { table: Table }) {
  const [period, ...comparisons] = table.columns
  // D-419: the budget's name follows the icon only when this table holds more than one family budget's categories.
  const budgets = new Set([...table.income.lines, ...table.expense.lines].flatMap((l) => (l.family ? [l.family] : [])))
  const width = 3 + comparisons.length * 2
  const row = (line: CompareLine, className = '', signed = false, of?: { section: CompareSection; kind: Kind }) => (
    <tr key={line.key} className={className}>
      <th scope="row">
        {line.label}
        {line.family && <SharedMark budget={line.family} named={budgets.size > 1} />}
      </th>
      <Amount amount={line.amounts[0]} currency={table.currency} rounded={table.converted} signed={signed} />
      <td className="amount nowrap share">
        {of && <Share line={line.amounts[0]} subtotal={of.section.subtotal.amounts[0]} kind={of.kind} />}
      </td>
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
            <th scope="col" className="amount share"><span className="col-role">Share</span> %</th>
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
        {([['Income', table.income, 'income'], ['Expenses', table.expense, 'expense']] as const).map(([title, section, kind]) => (
          <tbody key={title}>
            <tr className="section"><th scope="rowgroup" colSpan={width}>{title}</th></tr>
            {section.lines.map((line) => row(line, '', false, { section, kind }))}
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

type Kind = 'income' | 'expense'

/**
 * D-595: a line's share of its section, "33.8%" beside a bar in the chart's colour of income or expenses, hidden from
 * screen readers; "—" without a bar where there is no share.
 */
function Share({ line, subtotal, kind }: { line: Cell; subtotal: Cell; kind: Kind }) {
  const share = shareOf(line, subtotal)
  if (share === null) return <span className="muted">—</span>
  return (
    <span className="share-cell">
      <span className="share-track" aria-hidden="true">
        <span className={`share-fill ${kind}`} style={{ width: `${share.width}%` }} />
      </span>
      <span className="share-text">{shareText(share.percent)}</span>
    </span>
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
  return (
    <span className="shared-mark">
      <SharedIcon label={`Shared: ${budget}`} />
      {named && <span className="shared-name">{budget}</span>}
    </span>
  )
}
