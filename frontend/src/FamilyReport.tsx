import { Link, useSearchParams } from 'react-router'
import { formatDate, type FamilyReport as Report } from './api'
import { Errors, Field, Loading } from './components'
import { useFamilyApi, type FamilyData } from './familyData'
import { contributionWords, netWords, reportMonths, reportQuery, tookPart, totalLines } from './familyReport'
import { formatMoney } from './money'

const monthName = (month: string) => formatDate(`${month}-01`, { month: 'long', year: 'numeric' })

/**
 * The family report (E1, F6c): what the family spent and received, month by month and category by category, with each
 * member's share and what they paid or received, then each member's totals for the period. The period is in the URL
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
  const months = reportMonths(report)
  const members = new Map(report.members.map((m) => [m.memberId, m]))
  const name = (id: number) => {
    const member = members.get(id)
    return member ? (member.you ? 'You' : member.displayName) : 'A member'
  }
  const money = (amount: string) => formatMoney(amount, report.currency)
  if (months.length === 0 && !report.totals.some(tookPart)) {
    return (
      <p>
        No records {report.from || report.to ? 'in this period' : 'yet'}.{' '}
        <Link to={`${family.page}/expenses/new`}>Add an expense</Link>
      </p>
    )
  }
  return (
    <>
      <p className="muted small">
        In {report.currency}, from the family budget’s records that aren’t deleted. A share is what a member’s part of
        the records came to; “paid” and “received” are what went through their hands.
      </p>
      <h4>By member</h4>
      <ul className="report-totals">
        {report.totals.filter(tookPart).map((total) => {
          const member = members.get(total.memberId)!
          return (
            <li key={total.memberId}>
              <strong>{name(total.memberId)}</strong>
              {member.status === 'LEFT' && <span className="badge">Left</span>}
              {member.status === 'FORMER' && <span className="badge">Deleted their data</span>}
              {totalLines(total, report.currency).map((line) => <span key={line} className="report-line">{line}</span>)}
              <span className="report-line sentence">{netWords(total, member, report.currency)}.</span>
            </li>
          )
        })}
      </ul>
      {months.map((month) => (
        <section key={month.month} className="report-month">
          <h4>{monthName(month.month)}</h4>
          <p className="muted small">
            {month.expenses !== '0' && <>Expenses {money(month.expenses)}</>}
            {month.expenses !== '0' && month.incomes !== '0' && ' · '}
            {month.incomes !== '0' && <>Incomes {money(month.incomes)}</>}
          </p>
          {month.rows.map((row) => (
            <div key={row.categoryId} className="report-row">
              <div className="report-category">
                <span>
                  {row.categoryName}
                  {row.categoryType === 'INCOME' && <span className="badge">Income</span>}
                  {row.archived && <span className="badge">Archived</span>}
                </span>
                <strong className="nowrap">{money(row.total)}</strong>
              </div>
              <ul className="report-members">
                {row.members.map((c) => (
                  <li key={c.memberId}>{name(c.memberId)}: {contributionWords(row, c, report.currency)}</li>
                ))}
              </ul>
            </div>
          ))}
        </section>
      ))}
    </>
  )
}
