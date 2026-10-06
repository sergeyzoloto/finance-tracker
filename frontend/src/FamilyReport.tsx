import { Link, useSearchParams } from 'react-router'
import { formatDate, type FamilyReport as Report } from './api'
import { Errors, Field, Loading } from './components'
import { useFamilyApi, type FamilyData } from './familyData'
import { contributionWords, netWords, reportMonths, reportQuery, tookPart, totalLines } from './familyReport'
import { RateNotes, TotalAmount } from './FamilyCurrency'
import { abs, formatMoney, signOf } from './money'

const monthName = (month: string) => formatDate(`${month}-01`, { month: 'long', year: 'numeric' })

/**
 * The family report (E1, F6c): what the family spent and received, month by month and category by category, with each
 * member's share and what they paid or received, then each member's totals for the period, in each currency on its
 * own (D-45), with D-47's "≈" total in the main currency where there are several. The period is in the URL
 * (`from`, `to`), every record by default. No table: each line wraps, so it fits a phone's 375 px.
 */
export default function FamilyReport({ family }: { family: FamilyData }) {
  const [params, setParams] = useSearchParams()
  const from = params.get('from') ?? ''
  const to = params.get('to') ?? ''
  const report = useFamilyApi<Report>(family, `${family.path}/report${reportQuery(from, to)}`)
  const setPeriod = (next: { from?: string; to?: string }) => {
    const query = reportQuery(next.from ?? from, next.to ?? to)
    setParams(new URLSearchParams(query.slice(1)), { replace: true })
  }

  const data = report.data
  return (
    <section className="family-report">
      <h3>Report</h3>
      <div className="report-period">
        <Field label="From">
          <input type="date" value={from} onChange={(e) => setPeriod({ from: e.target.value })} />
        </Field>
        <Field label="To">
          <input type="date" value={to} onChange={(e) => setPeriod({ to: e.target.value })} />
        </Field>
        {(from || to) && <button type="button" onClick={() => setParams(new URLSearchParams(), { replace: true })}>All records</button>}
      </div>
      {!data ? (report.error ? <Errors messages={[report.error]} /> : <Loading what="the report" />) : (
        <ReportBody report={data} family={family} />
      )}
    </section>
  )
}

function ReportBody({ report, family }: { report: Report; family: FamilyData }) {
  const members = new Map(report.members.map((m) => [m.memberId, m]))
  const name = (id: number) => {
    const member = members.get(id)
    return member ? (member.you ? 'You' : member.displayName) : 'A member'
  }
  const sections = report.byCurrency.filter((c) => c.rows.length > 0 || c.totals.some(tookPart))
  if (sections.length === 0) {
    return (
      <p>
        No records {report.from || report.to ? 'in this period' : 'yet'}.{' '}
        <Link to={`${family.page}/expenses/new`}>Add an expense</Link>
      </p>
    )
  }
  const total = report.total
  const several = sections.length > 1 || sections[0].currency !== total.currency
  const ids = report.members.map((m) => m.memberId)
    .filter((id) => sections.some((c) => c.totals.some((t) => t.memberId === id && tookPart(t))))
  return (
    <>
      <p className="muted small">
        Each currency on its own, from the family budget’s records that aren’t deleted. A share is what a member’s part of
        the records came to; “paid” and “received” are what went through their hands.
      </p>
      <h4>By member</h4>
      <ul className="report-totals">
        {ids.map((id) => {
          const member = members.get(id)!
          const inMain = total.totals.find((t) => t.memberId === id)
          return (
            <li key={id}>
              <strong>{name(id)}</strong>
              {member.status === 'LEFT' && <span className="badge">Left</span>}
              {member.status === 'FORMER' && <span className="badge">Deleted their data</span>}
              {sections.map((c) => {
                const t = c.totals.find((x) => x.memberId === id)
                if (!t || !tookPart(t)) return null
                return (
                  <span key={c.currency} className="report-currency">
                    {several && <span className="report-line"><em>In {c.currency}</em></span>}
                    {totalLines(t, c.currency).map((line) => <span key={line} className="report-line">{line}</span>)}
                    <span className="report-line sentence">{netWords(t, member, c.currency)}.</span>
                  </span>
                )
              })}
              {several && (
                <span className="report-line" data-testid={`report-total-${id}`}>
                  Together in {total.currency}:{' '}
                  {total.missingCurrencies.length > 0 || !inMain
                    ? <TotalAmount amount={undefined} currency={total.currency} rates={total.rates} missing={total.missingCurrencies} />
                    : signOf(inMain.net) === 0 ? netWords(inMain, member, total.currency)
                      : <>{netWords(inMain, member, total.currency).replace(/ \S+ more$/, '')}{' '}
                        <TotalAmount amount={abs(inMain.net)} currency={total.currency} rates={total.rates} missing={[]} /> more</>}
                </span>
              )}
            </li>
          )
        })}
      </ul>
      {several && total.missingCurrencies.length === 0 && (
        <div className="total-note small muted">
          <p>
            The totals in {total.currency} are approximate, for display only: each month at its month-end rate, the
            current month at today’s, by your own rates where you have entered them, else the ECB’s.
          </p>
          <RateNotes rates={total.rates} />
        </div>
      )}
      {sections.map((c) => (
        <div key={c.currency} className="report-section">
          {several && <h4 className="report-section-title">In {c.currency}</h4>}
          {reportMonths(c.rows).map((month) => (
            <section key={month.month} className="report-month">
              <h4>{monthName(month.month)}{several ? ` · ${c.currency}` : ''}</h4>
              <p className="muted small">
                {month.expenses !== '0' && <>Expenses {formatMoney(month.expenses, c.currency)}</>}
                {month.expenses !== '0' && month.incomes !== '0' && ' · '}
                {month.incomes !== '0' && <>Incomes {formatMoney(month.incomes, c.currency)}</>}
              </p>
              {month.rows.map((row) => (
                <div key={row.categoryId} className="report-row">
                  <div className="report-category">
                    <span>
                      {row.categoryName}
                      {row.categoryType === 'INCOME' && <span className="badge">Income</span>}
                      {row.archived && <span className="badge">Archived</span>}
                    </span>
                    <strong className="nowrap">{formatMoney(row.total, c.currency)}</strong>
                  </div>
                  <ul className="report-members">
                    {row.members.map((m) => (
                      <li key={m.memberId}>{name(m.memberId)}: {contributionWords(row, m, c.currency)}</li>
                    ))}
                  </ul>
                </div>
              ))}
            </section>
          ))}
        </div>
      ))}
    </>
  )
}
