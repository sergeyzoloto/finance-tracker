import { useId } from 'react'
import { useApi, type EntryMonthly } from './api'
import { Errors, Loading } from './components'
import { columnBox, direction, hasChange, monthLabel, monthlyCurrencies, waterfall, waterfallRange, zeroLine } from './monthly'
import { formatMoney } from './money'

/**
 * Entries' monthly result (ENT-4, D-882, D-883), inside the summary panel under the breakdown: one waterfall per
 * currency of the filtered entries' My share by month, over the 12 months ending with the period's To month, whatever its
 * From. Each month's column runs from the running total before it to the one after, green when it goes up and red when
 * down, its change beside it and its month under it; the last column, "Total", runs from 0 to the span's. Plain HTML and
 * CSS, no chart library. The drawing is hidden from screen readers, which get a table of every month instead.
 *
 * @param filters the page's filters as the list sends them; the server leaves their From out
 */
export default function MonthlyChart({ filters }: { filters: string }) {
  const monthly = useApi<EntryMonthly>(`/entries/monthly${filters ? `?${filters}` : ''}`)
  return (
    <section className="monthly" aria-label="Monthly result">
      <h4>Monthly result</h4>
      {monthly.error && <Errors messages={[monthly.error]} />}
      {monthly.data === undefined && !monthly.error && <Loading what="the monthly result" />}
      {monthly.data && (
        <div className={monthly.loading ? 'stale' : undefined}>
          {monthlyCurrencies(monthly.data).length === 0 && <p className="muted">Nothing to show by month.</p>}
          {monthlyCurrencies(monthly.data).map((currency, _, all) => (
            <Waterfall key={currency} data={monthly.data!} currency={currency} named={all.length > 1} />
          ))}
        </div>
      )}
    </section>
  )
}

function Waterfall({ data, currency, named }: { data: EntryMonthly; currency: string; named: boolean }) {
  const id = useId()
  const columns = waterfall(data, currency)
  if (!hasChange(columns)) return null
  const range = waterfallRange(columns)
  const zero = zeroLine(range)
  const money = (amount: string) => formatMoney(amount, currency, { signed: true, rounded: true })
  return (
    <div className="monthly-currency" data-currency={currency}>
      {named && <h5 id={id}>{currency}</h5>}
      <div className="monthly-chart" aria-hidden="true">
        <div className="monthly-plot">
          <div className="monthly-columns" style={{ gridTemplateColumns: `repeat(${columns.length}, minmax(0, 1fr))` }}>
            <div className="monthly-zero" style={{ bottom: `${zero}%` }} />
            {columns.map((column) => {
              const box = columnBox(column, range)
              const dir = direction(column)
              return (
                <div key={column.key} className={`monthly-column ${column.month === null ? 'total' : ''}`}
                  title={`${column.month ?? 'Total'}: ${money(column.change)}${column.month !== null ? ` (total ${money(column.after)})` : ''}`}>
                  <div className={`monthly-bar ${dir}`}
                    style={{ bottom: `${box.bottom}%`, height: `${box.height}%` }} />
                  <span className={`monthly-value amount ${dir}`} style={{ bottom: `${box.bottom + box.height}%` }}>
                    {money(column.change)}
                  </span>
                </div>
              )
            })}
          </div>
        </div>
        <div className="monthly-labels" style={{ gridTemplateColumns: `repeat(${columns.length}, minmax(0, 1fr))` }}>
          {columns.map((column, i) => {
            const label = column.month === null ? { name: 'Total', year: null } : monthLabel(column.month, i === 0)
            return (
              <span key={column.key} className="monthly-label">
                {label.name}{label.year && <span className="muted"> {label.year}</span>}
              </span>
            )
          })}
        </div>
      </div>
      <div className="visually-hidden">
        <table aria-label={`Monthly result in ${currency}`}>
          <thead><tr><th>Month</th><th>Change</th><th>Running total</th></tr></thead>
          <tbody>
            {columns.map((column) => (
              <tr key={column.key}>
                <th scope="row">{column.month ?? 'Total'}</th>
                <td>{money(column.change)}</td>
                <td>{money(column.after)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
