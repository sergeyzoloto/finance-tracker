import { Link } from 'react-router'
import { type FamilyBalances as Balances } from './api'
import { Errors, Loading } from './components'
import { balancePhrase, debtSentence, maySettle, settleUpOrder, whoOwesWhom, yourBalance, type Debt } from './family'
import { RateNotes, TotalAmount } from './FamilyCurrency'
import { useFamilyApi, type FamilyData } from './familyData'
import { formatMoney, sum } from './money'

/** The reader's balance in words, member by member and currency by currency: "Sam owes you €40.00", or "You are settled". */
export function YourBalance({ balances }: { balances: Balances }) {
  return <>{yourBalance(balances).map((line) => <p key={line} className="sentence">{line}.</p>)}</>
}

/**
 * Every member's balance in each currency (D-45), in words, marking the reader, members without an account and former
 * members; in each currency they are zero together. Then D-47's total in the main currency, "≈", for display only,
 * with its rates' dates and sources, or "No RUB rate" with the way to enter one. Then who owes whom in each currency
 * (D1), the reader's own debts first, each with "Settle up" where the reader may record that settlement: a settlement is
 * in one currency, the debt's (D-46), and opens the form with its members, amount and currency (D2, D-24).
 */
export default function FamilyBalances({ family }: { family: FamilyData }) {
  const balances = useFamilyApi<Balances>(family, `${family.path}/balances`)
  const data = balances.data
  if (!data) return balances.error ? <Errors messages={[balances.error]} /> : <Loading what="the balances" />
  const me = family.ledger.memberId
  const settleUp = (d: Debt) => maySettle(d.from, d.to, me, family.owner)
    ? `${family.page}/settle?payer=${d.from.memberId}&payee=${d.to.memberId}&amount=${d.amount}&currency=${d.currency}`
    : undefined
  const members = data.byCurrency[0]?.members ?? []
  const total = data.total
  const several = data.byCurrency.length > 1
  return (
    <section>
      <h3>Balances</h3>
      <div className="settlement"><YourBalance balances={data} /></div>
      <table className="shares balances-table">
        <thead>
          <tr>
            <th scope="col">Member</th>
            {data.byCurrency.map((c) => <th key={c.currency} scope="col" className="amount">{c.currency}</th>)}
            {several && <th scope="col" className="amount">Total in {total.currency}</th>}
          </tr>
        </thead>
        <tbody>
          {members.map((m) => (
            <tr key={m.memberId} className={m.status === 'ACTIVE' ? '' : 'archived'}>
              <th scope="row">
                {m.displayName}
                {m.you && <span className="badge">You</span>}
                {!m.hasAccount && m.status !== 'FORMER' && <span className="badge">No account</span>}
                {m.status === 'LEFT' && <span className="badge">Left</span>}
                {m.status === 'FORMER' && <span className="badge">Deleted their data</span>}
              </th>
              {data.byCurrency.map((c) => {
                const balance = c.members.find((b) => b.memberId === m.memberId)
                return <td key={c.currency} className="amount nowrap">{balance ? balancePhrase(balance, c.currency) : '—'}</td>
              })}
              {several && (
                <td className="amount nowrap" data-testid={`total-${m.memberId}`}>
                  <TotalAmount amount={total.members.find((t) => t.memberId === m.memberId)?.balance}
                    currency={total.currency} rates={total.rates} missing={total.missingCurrencies} />
                </td>
              )}
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">All together</th>
            {data.byCurrency.map((c) => (
              <td key={c.currency} className="amount nowrap" data-testid={`balances-sum-${c.currency}`}>
                {formatMoney(sum(c.members.map((m) => m.balance)), c.currency)}
              </td>
            ))}
            {several && <td />}
          </tr>
        </tfoot>
      </table>
      {several && (
        <div className="total-note small muted">
          <p>
            The total in {total.currency} is approximate, for display only: it is never posted or settled. It uses your own
            rates where you have entered them, else the ECB’s, as of today.
          </p>
          <RateNotes rates={total.rates} />
        </div>
      )}
      <p className="muted small">
        What some members owe, the others are owed, so the balances in each currency always add up to zero. A member owes
        when their shares of the expenses are more than they paid, less what they received or were paid back.
      </p>
      <h4>Who owes whom</h4>
      {data.byCurrency.every((c) => whoOwesWhom(c).length === 0) ? <p>Everyone is settled.</p> : (
        data.byCurrency.map((c) => {
          const debts = settleUpOrder(whoOwesWhom(c))
          if (debts.length === 0) return null
          return (
            <div key={c.currency} className="debts-in">
              {several && <h5>In {c.currency}</h5>}
              <ul className="debts">
                {debts.map((d) => {
                  const to = settleUp(d)
                  return (
                    <li key={`${d.from.memberId}-${d.to.memberId}`}>
                      {debtSentence(d, d.currency)}
                      {to && <> <Link className="button" to={to}>Settle up</Link></>}
                    </li>
                  )
                })}
              </ul>
            </div>
          )
        })
      )}
      <p className="muted small">
        A settlement records one member paying another, in one currency, which it settles.{' '}
        <Link to={`${family.page}/settle`}>Record a settlement</Link> · <Link to={`${family.page}/expenses`}>See the activity</Link>
      </p>
    </section>
  )
}
