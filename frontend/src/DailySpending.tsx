import { formatDate } from './api'
import { SharedMark } from './CashFlowTable'
import { Errors, Loading } from './components'
import { dailySpending, dailyTitle, dayMarks, type DailyRange, type DailyRow } from './daily'
import { MISSING, type Cell } from './dashboard'
import { formatMoney, signOf, toChartNumber } from './money'

/**
 * Daily spending (D-571, UX-13): a bar per shown day of one currency's expenses, in plain HTML and CSS. A day still to
 * come is an empty dashed slot; a day at or below 0 a thin line; a day the base currency can't convert no bar, titled
 * "rate missing". Under the bars the first day's number, today's while the range runs and the last day's, then the
 * biggest day. The bars are hidden from screen readers, which get a table of every day that spent something instead.
 *
 * @param rows the daily cash flow's rows of this currency, undefined while they load
 * @param budgetsNamed whether the currency's table names a family category's budget after its icon (D-419)
 */
export default function DailySpending({ range, rows, error, loading, currency, rounded, today, budgetsNamed }: {
  range: DailyRange
  rows: DailyRow[] | undefined
  error?: string
  loading: boolean
  currency: string
  rounded: boolean
  today: string
  budgetsNamed: boolean
}) {
  const title = dailyTitle(range)
  const id = `daily-${currency}`
  return (
    <section className="daily" aria-labelledby={id} data-currency={currency}>
      <h4 id={id}>{title}</h4>
      {error ? <Errors messages={[error]} />
        : rows === undefined ? <Loading />
          : <Days range={range} rows={rows} currency={currency} rounded={rounded} today={today} stale={loading}
            budgetsNamed={budgetsNamed} />}
    </section>
  )
}

function Days({ range, rows, currency, rounded, today, stale, budgetsNamed }: {
  range: DailyRange
  rows: DailyRow[]
  currency: string
  rounded: boolean
  today: string
  stale: boolean
  budgetsNamed: boolean
}) {
  const { slots, max, biggest } = dailySpending(rows, range, today)
  const money = (amount: string) => formatMoney(amount, currency, { rounded })
  const height = (amount: Cell) => {
    if (typeof amount !== 'string' || max === null || signOf(amount) <= 0) return undefined
    return `${Math.max(2, (toChartNumber(amount) / toChartNumber(max)) * 100)}%`
  }
  const spent = slots.filter((s) => s.amount === MISSING || (typeof s.amount === 'string' && signOf(s.amount) > 0))
  return (
    <div className={stale ? 'stale' : undefined}>
      <div className="daily-bars" aria-hidden="true">
        {slots.map((slot) => {
          const kind = slot.future ? 'future' : slot.amount === MISSING ? 'missing'
            : typeof slot.amount === 'string' && signOf(slot.amount) > 0 ? 'spent' : 'none'
          const what = slot.future ? 'still to come' : slot.amount === MISSING ? 'rate missing'
            : slot.amount === null ? 'no spending' : money(slot.amount)
          return (
            <span key={slot.day} className={`daily-day ${kind}`} title={`${formatDate(slot.day)}: ${what}`}
              data-day={slot.day}>
              {kind === 'spent' && <span className="daily-bar" style={{ height: height(slot.amount) }} />}
            </span>
          )
        })}
      </div>
      <div className="daily-axis" aria-hidden="true">
        {dayMarks(range, today).map((m, i, all) => (
          <span key={m.day} className={i === 0 ? 'first' : i === all.length - 1 ? 'last' : 'today'}
            style={i === 0 || i === all.length - 1 ? undefined : { left: `${m.at * 100}%` }}>{m.label}</span>
        ))}
      </div>
      <p className="small daily-biggest">
        {biggest === null ? 'No spending in this period.' : (
          <>
            Biggest day: {formatDate(biggest.day)}, <span className="amount-inline">{money(biggest.amount)}</span>,{' '}
            {biggest.category.name}
            {biggest.category.family !== undefined && <SharedMark budget={biggest.category.family} named={budgetsNamed} />}
          </>
        )}
      </p>
      <table className="visually-hidden">
        <caption>Each day’s spending in {currency}</caption>
        <thead><tr><th scope="col">Day</th><th scope="col">Spending</th></tr></thead>
        <tbody>
          {spent.length === 0
            ? <tr><td colSpan={2}>No spending in this period.</td></tr>
            : spent.map((slot) => (
              <tr key={slot.day}>
                <th scope="row">{formatDate(slot.day)}</th>
                <td>{slot.amount === MISSING ? 'rate missing' : money(slot.amount as string)}</td>
              </tr>
            ))}
        </tbody>
      </table>
    </div>
  )
}
