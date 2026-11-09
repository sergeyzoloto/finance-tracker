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
  const width = 2 + comparisons.length * 2
  const row = (line: CompareLine, className = '', signed = false) => (
    <tr key={line.key} className={className}>
      <th scope="row">
        {line.label}
        {line.family && <span className="badge" title={`A category of the family budget “${line.family}”`}>{line.family}</span>}
      </th>
      <Amount amount={line.amounts[0]} currency={table.currency} rounded={table.converted} signed={signed} />
      {comparisons.map((column, i) => {
        const before = line.amounts[i + 1]
        const change = changeOf(line.amounts[0], before)
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
