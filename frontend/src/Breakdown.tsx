import { useId, useState } from 'react'
import { useSearchParams } from 'react-router'
import { useApi, type Counterparty, type EntryBreakdown } from './api'
import { isChosen, myShareTexts, TABS, withChosen, withOnly, type Tab } from './breakdown'

/**
 * Entries' breakdown (ENT-3, D-873, D-881), under the amount table: tabs By category, By payee and By account. A row is
 * the name, its entries' count and My share per currency; its checkbox adds or removes the value in its filter and
 * "only" makes it the filter's single value. The server leaves the tab's own filters out, so the other values stay
 * listed while some are chosen. Entries without a category or payee are one row with neither.
 *
 * @param filters the page's filters as the list sends them, which the breakdown sends as they are
 */
export default function Breakdown({ filters, counterparties }: { filters: string; counterparties: Counterparty[] }) {
  const [tab, setTab] = useState<Tab>('category')
  const [params, setParams] = useSearchParams()
  const id = useId()
  const by = useApi<EntryBreakdown>(`/entries/breakdown?by=${tab}${filters ? `&${filters}` : ''}`)
  const rows = by.data && by.data.by.toLowerCase() === tab ? by.data.rows : undefined
  return (
    <div className="breakdown">
      <div role="tablist" aria-label="Break the entries down" className="tabs breakdown-tabs">
        {TABS.map((t) => (
          <button key={t.value} type="button" role="tab" id={`${id}-${t.value}`} aria-selected={tab === t.value}
            aria-controls={`${id}-panel`} className={tab === t.value ? 'current' : ''} onClick={() => setTab(t.value)}>
            {t.label}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`${id}-panel`} aria-labelledby={`${id}-${tab}`} className={by.loading ? 'stale' : ''}>
        {by.error && <p role="alert" className="error">{by.error}</p>}
        {rows && rows.length === 0 && <p className="muted">Nothing to break down.</p>}
        {rows && rows.length > 0 && (
          <table className="breakdown-rows" aria-label={TABS.find((t) => t.value === tab)!.label}>
            <thead>
              <tr><th><span className="visually-hidden">Chosen</span></th><th scope="col">Name</th>
                <th scope="col" className="amount">Entries</th><th scope="col" className="amount">My share</th><th /></tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const chosen = isChosen(params, tab, row, counterparties)
                const shares = myShareTexts(row)
                return (
                  <tr key={row.key ?? 'none'}>
                    <td className="check">
                      {row.key !== null && (
                        <input type="checkbox" checked={chosen} aria-label={row.name}
                          onChange={() => setParams(withChosen(params, tab, row, counterparties, !chosen))} />
                      )}
                    </td>
                    <td className="name">{row.name}</td>
                    <td className="amount">{row.entries}</td>
                    <td className="amount shares">
                      {shares.length === 0 ? <span className="muted">—</span> : shares.map((s) => (
                        <span key={s.currency} className={s.sign > 0 ? 'income' : s.sign < 0 ? 'expense' : ''}>{s.text}</span>
                      ))}
                    </td>
                    <td className="only">
                      {row.key !== null && (
                        <button type="button" className="link" aria-label={`Only ${row.name}`}
                          onClick={() => setParams(withOnly(params, tab, row))}>only</button>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
