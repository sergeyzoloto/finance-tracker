import { MISSING, monthLabel, type CashFlowLine, type CashFlowTable as Table, type Cell } from './dashboard'
import { formatMoney, signOf } from './money'

/**
 * One currency's income and expenses as the owner's Excel pivot: categories as rows, months as columns, a total
 * column, a subtotal per section and the net. A dash marks a month in which nothing was posted. In the base currency,
 * the results of exchange rates follow the net, and "rate missing" marks a figure that can't be converted.
 */
export default function CashFlowTable({ table }: { table: Table }) {
  const width = table.months.length + 2
  const row = (line: CashFlowLine, className = '', signed = false) => (
    <tr key={line.key} className={className}>
      <th scope="row">{line.label}</th>
      {line.months.map((amount, i) => (
        <Amount key={table.months[i]} amount={amount} currency={table.currency} rounded={table.converted}
          className={signed ? sign(amount) : ''} />
      ))}
      <Amount amount={line.total} currency={table.currency} rounded={table.converted}
        className={`total ${signed ? sign(line.total) : ''}`} />
    </tr>
  )
  return (
    <div className="scroll-x">
      <table className="cash-flow" aria-label={`Cash flow in ${table.currency}`}>
        <thead>
          <tr>
            <th scope="col">{table.currency}</th>
            {table.months.map((month) => <th key={month} scope="col" className="amount">{monthLabel(month)}</th>)}
            <th scope="col" className="amount">Total</th>
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

function Amount({ amount, currency, rounded, className = '' }: { amount: Cell; currency: string; rounded: boolean; className?: string }) {
  return (
    <td className={`amount nowrap ${className}`}>
      {amount === null ? <span className="muted">—</span>
        : amount === MISSING ? <span className="missing" title="No exchange rate for a currency on some day">rate missing</span>
          : formatMoney(amount, currency, { rounded })}
    </td>
  )
}
