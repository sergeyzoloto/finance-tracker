import { Link } from 'react-router'
import { type FamilyBalances as Balances } from './api'
import { Errors, Loading } from './components'
import { balancePhrase, debtSentence, whoOwesWhom, yourBalance } from './family'
import { useFamilyApi, type FamilyData } from './familyData'
import { formatMoney, sum } from './money'

/** The reader's balance in words, member by member: "Sam owes you €40.00", or "You are settled". */
export function YourBalance({ balances }: { balances: Balances }) {
  return <>{yourBalance(balances).map((line) => <p key={line} className="sentence">{line}.</p>)}</>
}

/**
 * Every member's balance in words, marking the reader, members without an account and former members; together they
 * are zero. Then who owes whom (D1).
 */
export default function FamilyBalances({ family }: { family: FamilyData }) {
  const balances = useFamilyApi<Balances>(family, `${family.path}/balances`)
  const data = balances.data
  if (!data) return balances.error ? <Errors messages={[balances.error]} /> : <Loading what="the balances" />
  const debts = whoOwesWhom(data)
  return (
    <section>
      <h3>Balances</h3>
      <div className="settlement"><YourBalance balances={data} /></div>
      <table className="shares balances-table">
        <tbody>
          {data.members.map((m) => (
            <tr key={m.memberId} className={m.status === 'ACTIVE' ? '' : 'archived'}>
              <th scope="row">
                {m.displayName}
                {m.you && <span className="badge">You</span>}
                {!m.hasAccount && m.status !== 'FORMER' && <span className="badge">No account</span>}
                {m.status === 'LEFT' && <span className="badge">Left</span>}
                {m.status === 'FORMER' && <span className="badge">Deleted their data</span>}
              </th>
              <td className="nowrap">{balancePhrase(m, data.currency)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">All together</th>
            <td className="nowrap" data-testid="balances-sum">{formatMoney(sum(data.members.map((m) => m.balance)), data.currency)}</td>
          </tr>
        </tfoot>
      </table>
      <p className="muted small">
        What some members owe, the others are owed, so the balances always add up to zero. A member owes when their
        shares of the expenses are more than they paid.
      </p>
      <h4>Who owes whom</h4>
      {debts.length === 0 ? <p>Everyone is settled.</p> : (
        <ul>{debts.map((d) => <li key={`${d.from.memberId}-${d.to.memberId}`}>{debtSentence(d, data.currency)}</li>)}</ul>
      )}
      <p className="muted small">
        Recording a settlement comes in a later version. <Link to={`${family.page}/expenses`}>See the expenses</Link>
      </p>
    </section>
  )
}
