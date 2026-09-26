import { useState, type FormEvent } from 'react'
import {
  api, ApiError, fieldMessages, formatDate, isoDate, useApi, useMutation, type LatestRate, type ManualRate,
  type RatesOverview,
} from './api'
import { CurrencyInput, Errors, Field, Loading } from './components'
import { daysBetween, missingDays, STALE_AFTER_DAYS } from './dashboard'
import { formatRate, parseAmount, rateProblem } from './money'

/**
 * Exchange rates for the dashboard in the base currency: the latest rate of each currency, what the user's entries
 * lack, and the user's own manual rates, for currencies the ECB doesn't publish, such as RUB since March 2022. Rates
 * are units of a currency for one euro, as the ECB quotes them.
 */
export default function Rates() {
  const overview = useApi<RatesOverview>('/rates')
  const manual = useApi<ManualRate[]>('/rates/manual')
  const reload = () => { overview.reload(); manual.reload() }
  const today = isoDate(new Date())

  const mine = overview.data?.latest.filter((r) => r.inLedger) ?? []
  const others = overview.data?.latest.filter((r) => !r.inLedger) ?? []
  return (
    <>
      <h2>Exchange rates</h2>
      <p className="muted">
        The dashboard converts an amount at the latest rate on or before its day. The ECB’s euro reference rates are
        loaded every working day; add your own for currencies it doesn’t publish. Yours take precedence on the same day,
        and only you see them.
      </p>
      <Errors messages={[overview.error, manual.error]} />
      {overview.data && overview.data.missing.length > 0 && (
        <div className="notice" role="status">
          <p>Some of your entries can’t be converted to {overview.data.baseCurrency}. There is no rate for:</p>
          <ul>
            {overview.data.missing.map((m) => (
              <li key={m.currency}>
                {missingDays(m)} ({m.days} {m.days === 1 ? 'day' : 'days'}). A rate dated {formatDate(m.from)} or
                earlier covers them.
              </li>
            ))}
          </ul>
        </div>
      )}

      <section>
        <h3>Latest rates</h3>
        {!overview.data && !overview.error && <Loading what="rates" />}
        {overview.data && (
          <>
            {mine.length === 0 ? <p className="empty">Your entries are all in euros.</p> : <RateTable rates={mine} today={today} />}
            {others.length > 0 && (
              <details>
                <summary>Other currencies ({others.length})</summary>
                <RateTable rates={others} today={today} />
              </details>
            )}
          </>
        )}
      </section>

      <section>
        <h3>Add a rate</h3>
        <AddRate currencies={mine.map((r) => r.currency)} today={today} onSaved={reload} />
        <UploadRates onSaved={reload} />
      </section>

      <section>
        <h3>Your rates</h3>
        {!manual.data && !manual.error && <Loading what="your rates" />}
        {manual.data && (manual.data.length === 0
          ? <p className="empty">You haven’t added any rates.</p>
          : <ManualRates rates={manual.data} onDeleted={reload} />)}
      </section>
    </>
  )
}

function RateTable({ rates, today }: { rates: LatestRate[]; today: string }) {
  return (
    <table className="rates">
      <thead>
        <tr>
          <th scope="col">Currency</th>
          <th scope="col" className="amount">1 EUR =</th>
          <th scope="col">Date</th>
          <th scope="col">Source</th>
        </tr>
      </thead>
      <tbody>
        {rates.map((r) => {
          const age = r.date === null ? 0 : daysBetween(r.date, today)
          return (
            <tr key={r.currency}>
              <th scope="row">{r.currency}</th>
              <td className="amount nowrap">{r.perEuro === null ? <span className="missing">no rate</span> : `${formatRate(r.perEuro)} ${r.currency}`}</td>
              <td className="nowrap">
                {r.date && formatDate(r.date)}
                {age > STALE_AFTER_DAYS && <span className="badge stale-rate" title="Newer amounts are converted at this rate too">{age} days old</span>}
              </td>
              <td>{r.source === 'MANUAL' ? 'Yours' : r.source ?? ''}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

/** One rate, as units of the currency for one euro. */
function AddRate({ currencies, today, onSaved }: { currencies: string[]; today: string; onSaved: () => void }) {
  const [date, setDate] = useState(today)
  const [currency, setCurrency] = useState('')
  const [rate, setRate] = useState('')
  const [problem, setProblem] = useState<string>()
  const save = useMutation(onSaved)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const typed = rateProblem(rate)
    setProblem(typed)
    if (typed) return
    const saved = await save.run(() => api('/rates/manual', 'POST', { date, base: 'EUR', quote: currency, rate: parseAmount(rate) }))
    if (saved) setRate('')
  }

  const failure = save.failure
  const fieldErrors = ['date', 'quote', 'rate'].flatMap((f) => fieldMessages(failure, f))
  return (
    <form className="add" onSubmit={submit}>
      <Field label="Date" errors={fieldMessages(failure, 'date')}>
        <input type="date" value={date} required onChange={(e) => { setDate(e.target.value); save.clear() }} />
      </Field>
      <Field label="Currency" errors={fieldMessages(failure, 'quote')}>
        <CurrencyInput currencies={currencies} value={currency} required aria-label="Currency"
          onChange={(code) => { setCurrency(code); save.clear() }} />
      </Field>
      <Field label={`1 EUR = … ${currency || 'units'}`} errors={[...(problem ? [problem] : []), ...fieldMessages(failure, 'rate')]}>
        <input className="amount" inputMode="decimal" value={rate} required placeholder="95.50" aria-label="Rate"
          onChange={(e) => { setRate(e.target.value); setProblem(undefined); save.clear() }} />
      </Field>
      <button className="primary" disabled={save.pending}>Save rate</button>
      <Errors messages={fieldErrors.length > 0 ? [] : [save.error]} />
    </form>
  )
}

/** A CSV file of rates with the columns date, base, quote and rate: all of it is saved, or none. */
function UploadRates({ onSaved }: { onSaved: () => void }) {
  const [file, setFile] = useState<File>()
  const [saved, setSaved] = useState<number>()
  const upload = useMutation(onSaved)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!file) return
    const body = new FormData()
    body.append('file', file)
    setSaved(undefined)
    await upload.run(async () => setSaved((await api<{ saved: number }>('/rates/manual/csv', 'POST', body)).saved))
  }

  const violations = upload.failure instanceof ApiError ? upload.failure.violations : []
  return (
    <form className="add" onSubmit={submit}>
      <Field label="Or upload a CSV file" hint={<>Columns <code>date,base,quote,rate</code>, such as <code>2026-09-01,EUR,RUB,95.50</code></>}>
        <input type="file" accept=".csv,text/csv" required
          onChange={(e) => { setFile(e.target.files?.[0]); setSaved(undefined); upload.clear() }} />
      </Field>
      <button disabled={!file || upload.pending}>Upload</button>
      {saved !== undefined && <p className="success" role="status">Saved {saved} {saved === 1 ? 'rate' : 'rates'}.</p>}
      {violations.length > 0
        ? <div className="error-box" role="alert"><p>Nothing was saved. Fix these rows:</p><ul>{violations.map((v) => <li key={v}>{v}</li>)}</ul></div>
        : <Errors messages={[upload.error]} />}
    </form>
  )
}

function ManualRates({ rates, onDeleted }: { rates: ManualRate[]; onDeleted: () => void }) {
  const remove = useMutation(onDeleted)
  return (
    <details open={rates.length <= 10}>
      <summary>{rates.length} {rates.length === 1 ? 'rate' : 'rates'}</summary>
      <Errors messages={[remove.error]} />
      <table className="rates">
        <thead>
          <tr><th scope="col">Date</th><th scope="col">Currency</th><th scope="col" className="amount">1 EUR =</th><th /></tr>
        </thead>
        <tbody>
          {rates.map((r) => (
            <tr key={`${r.date}:${r.quote}`}>
              <td className="nowrap">{formatDate(r.date)}</td>
              <td>{r.quote}</td>
              <td className="amount nowrap">{formatRate(r.rate)} {r.quote}</td>
              <td>
                <button type="button" disabled={remove.pending}
                  onClick={() => confirm(`Delete your ${r.quote} rate of ${formatDate(r.date)}?`)
                    && void remove.run(() => api(`/rates/manual?date=${r.date}&currency=${r.quote}`, 'DELETE'))}>
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </details>
  )
}
