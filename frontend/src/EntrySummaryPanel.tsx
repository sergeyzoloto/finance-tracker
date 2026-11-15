import { useId, useState, type ReactNode } from 'react'
import type { EntrySummary } from './api'
import { amountTable, myNet, summaryTexts } from './entrySummary'

/** The user's fold choice, on this device (D-876): 'open', 'folded', or none. */
export const SUMMARY_FOLD_KEY = 'finance-tracker.entries-summary'

function storedOpen(): boolean | undefined {
  try {
    const value = localStorage.getItem(SUMMARY_FOLD_KEY)
    return value === 'open' ? true : value === 'folded' ? false : undefined
  } catch {
    return undefined
  }
}

/** Unfolded from 40rem, folded below it (D-872), unless the user chose (D-876). */
function initiallyOpen() {
  const stored = storedOpen()
  if (stored !== undefined) return stored
  return typeof matchMedia === 'function' ? matchMedia('(min-width: 40rem)').matches : true
}

/**
 * Entries' summary panel (D-875), between the filters and the list: the counts line, then per currency its amount
 * table (D-871), headed by its code when there is more than one. A button folds it to one line: the count, the dates
 * and per currency My share's net (D-872). `children` (the breakdown, the chart) fold with it.
 */
export default function EntrySummaryPanel({ summary, loading, children }: {
  summary: EntrySummary
  loading: boolean
  children?: ReactNode
}) {
  const [open, setOpen] = useState(initiallyOpen)
  const bodyId = useId()
  const texts = summaryTexts(summary)
  const many = summary.currencies.length > 1
  const toggle = () => {
    const next = !open
    setOpen(next)
    try {
      localStorage.setItem(SUMMARY_FOLD_KEY, next ? 'open' : 'folded')
    } catch { /* the choice lasts for this page only */ }
  }
  return (
    <section className={`entry-summary-panel card ${loading ? 'stale' : ''}`} aria-label="Summary of all matching entries">
      <div className="summary-head">
        <button type="button" className="summary-toggle" aria-expanded={open} aria-controls={bodyId} onClick={toggle}>
          {open ? 'Fold the summary' : 'Unfold the summary'}
        </button>
        <p className="summary-counts">
          <span className="sum-date">{texts.date}</span>
          {open ? (
            <>
              {' · '}<span className="sum-payee">{texts.payee}</span>
              {' · '}<span className="sum-category">{texts.category}</span>
              {' · '}<span className="sum-accounts">{texts.accounts}</span>
            </>
          ) : summary.currencies.map((c) => (
            <span key={c.currency} className="sum-net">
              {' · '}<span className="muted">Net</span> <span className="amount">{myNet(c)}</span>
            </span>
          ))}
        </p>
      </div>
      <div id={bodyId} className="summary-body" hidden={!open}>
        {summary.currencies.map((c) => {
          const table = amountTable(c)
          if (table.rows.length === 0) return null
          return (
            <table key={c.currency} className="summary-amounts" data-currency={c.currency}
              aria-label={`Amounts in ${c.currency}`}>
              {many && <caption>{c.currency}</caption>}
              <thead>
                <tr><td />{table.columns.map((col) => <th key={col} scope="col" className="amount">{col}</th>)}</tr>
              </thead>
              <tbody>
                {table.rows.map((row) => (
                  <tr key={row.label}>
                    <th scope="row">{row.label}</th>
                    {row.cells.map((cell, i) => (
                      <td key={i} className={`amount ${i === row.cells.length - 1 ? row.tone : ''}`}>{cell}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          )
        })}
        {children}
      </div>
    </section>
  )
}
