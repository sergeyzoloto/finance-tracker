import { Link } from 'react-router'
import { api, useMutation, type DemoLedger } from './api'
import { Errors } from './components'

/**
 * The dashboard of a ledger without entries: load the demo ledger (POST /api/demo-data), or start from scratch.
 *
 * @param deleted whether the user just deleted all their data
 */
export default function EmptyLedger({ onLoaded, deleted = false }: { onLoaded: (demo: DemoLedger) => void; deleted?: boolean }) {
  const demo = useMutation()
  const load = () => demo.run(async () => onLoaded(await api<DemoLedger>('/demo-data', 'POST')))
  return (
    <>
      <h2>Dashboard</h2>
      {deleted && <p className="success" role="status">All your data has been deleted.</p>}
      <p className="lead">Your ledger is empty. How would you like to start?</p>
      <div className="choices">
        <section className="card choice">
          <h3>Load demo data</h3>
          <p>
            Fills your ledger with six months of invented entries in euros and US dollars, up to today — salary, rent,
            groceries shared with a partner, a loan to a friend, a currency exchange and more — which you can delete
            again in Settings.
          </p>
          <button type="button" className="primary" onClick={() => void load()} disabled={demo.pending}>
            {demo.pending ? 'Loading demo data…' : 'Load demo data'}
          </button>
          <Errors messages={[demo.error]} />
        </section>
        <section className="card choice">
          <h3>Start from scratch</h3>
          <p>
            Enter your own money: your accounts’ balances today, then each expense, income and transfer as it happens.
            The dashboard fills in as you go.
          </p>
          <p className="actions">
            <Link className="button primary" to="/entries/new">Add an entry</Link>
            <Link className="button" to="/accounts">See your accounts</Link>
            <Link className="button" to="/import">Import a spreadsheet</Link>
          </p>
        </section>
      </div>
    </>
  )
}
