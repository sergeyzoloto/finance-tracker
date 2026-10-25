import { useState } from 'react'
import { formatDate, useApi, type ExportSummary } from './api'
import { Errors } from './components'
import { useToday } from './me'

export type Period = 'whole' | 'range'

/**
 * The query of an export's period (D-241): none for the whole period, `?from=…&to=…` for a range; null while a range
 * lacks a day or ends before it starts.
 */
export function exportQuery(period: Period, from: string, to: string): string | null {
  if (period === 'whole') return ''
  if (!from || !to || from > to) return null
  return `?${new URLSearchParams({ from, to })}`
}

/** The skipped records' kinds, in words; any other is its entry kind's name. */
const SKIPPED: Record<string, string> = {
  OPENING_BALANCE: 'opening balances',
  FAMILY_OPENING: 'opening balance of a place you took in a family budget',
  FAMILY_CORRECTION: 'correction on returning to a family budget',
  SETTLEMENT_GROUP: 'settlements matched to several rows of yours',
  FAMILY_RECORD: 'family records',
  FAMILY_SWITCHED_OFF: 'family budget entries (family budgets are off)',
  SHARED_EXPENSE: 'shared expenses',
}

const rows = (n: number) => (n === 1 ? '1 row' : `${n.toLocaleString()} rows`)

/**
 * The export of your ledger as one transactions file in the import's format (D4a): your personal entries and your own
 * family rows, never another member's records (D-239). The whole period, or the days from one date to another, both
 * included. The summary says how many rows the file has and what it leaves out as no single row; Download saves it.
 */
export default function Export() {
  const today = useToday()
  const [period, setPeriod] = useState<Period>('whole')
  const [from, setFrom] = useState(`${today.slice(0, 8)}01`)
  const [to, setTo] = useState(today)
  const query = exportQuery(period, from, to)
  const summary = useApi<ExportSummary>(query === null ? null : `/export/summary${query}`)
  const shown = query !== null && !summary.loading ? summary.data : undefined
  const skipped = shown?.skipped.reduce((n, s) => n + s.count, 0) ?? 0

  return (
    <>
      <h2>Export</h2>
      <p className="muted">
        Your ledger as one transactions file in the format the import reads: your personal entries and your own family
        rows, each with its Excel ID, or an ID starting with <code>app-</code> for one made here. Another member’s
        records are never in it.
      </p>
      <form className="export" onSubmit={(e) => e.preventDefault()}>
        <fieldset className="choice">
          <legend className="label">Period</legend>
          <label>
            <input type="radio" name="period" value="whole" checked={period === 'whole'}
              onChange={() => setPeriod('whole')} /> Whole period
          </label>
          <label>
            <input type="radio" name="period" value="range" checked={period === 'range'}
              onChange={() => setPeriod('range')} /> From–to
          </label>
          {period === 'range' && (
            <>
              <label className="field">
                <span className="label">From</span>
                <input type="date" name="from" value={from} required onChange={(e) => setFrom(e.target.value)} />
              </label>
              <label className="field">
                <span className="label">To</span>
                <input type="date" name="to" value={to} required onChange={(e) => setTo(e.target.value)} />
              </label>
            </>
          )}
        </fieldset>
        {query === null && <p className="error">Choose both dates, the first not after the last.</p>}
        {shown && (
          <p className="muted" role="status" data-testid="export-summary">
            {shown.rows === 0 ? 'No rows in this period.'
              : `${rows(shown.rows)}, ${formatDate(shown.first!)} to ${formatDate(shown.last!)}.`}
            {skipped > 0 && ` Left out, as no single row stands for them: ${shown.skipped.map((s) =>
              `${SKIPPED[s.kind] ?? s.kind.toLowerCase().replaceAll('_', ' ')} (${s.count})`).join(', ')}.`}
          </p>
        )}
        {shown && !shown.check.ok && (
          <p className="notice" role="alert">
            The file’s check doesn’t add up for {shown.check.accounts.filter((a) => !a.ok)
              .map((a) => `${a.name} ${a.currency}`).join(', ')}: please report it.
          </p>
        )}
        <div className="actions">
          {query === null
            ? <button type="button" className="primary" disabled>Download</button>
            : <a className="button primary" href={`/api/export/transactions${query}`} download={shown?.fileName ?? true}>
              Download</a>}
        </div>
      </form>
      <Errors messages={[summary.error]} />
    </>
  )
}
