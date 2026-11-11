import { useEffect, useRef, useState } from 'react'
import { api, formatDate } from './api'
import { SharedMark } from './CashFlowTable'
import { categoryById, counterpartyById, entryKindLabel, type Ledger } from './ledger'
import { formatMoney, negate } from './money'
import {
  MATCHES_PAGE, matchAccount, matchesQuery, matchSign, type Criteria, type EntryMatch, type EntryMatchPage,
} from './similar'

/** How long the list waits after the last change of a criterion before it asks (D-460). */
export const SIMILAR_DELAY_MS = 400

interface Shown {
  items: EntryMatch[]
  total: number
  page: number
  pages: number
}

/**
 * "Similar entries" under New entry's form (UX-8, D-453, D-460): the user's saved entries like the one being typed,
 * newest first, 10 at a time. It asks 400 ms after the last change of a criterion and drops an answer to an older
 * question; without a criterion there is no list and no request. While `hold` is the criteria's key, as right after a
 * copy, it doesn't ask (D-456); each new `refresh`, as after a save, asks again at once. A failed request is one muted
 * line, and never blocks the form.
 */
export default function SimilarEntries({ criteria, hold, refresh, ledger, onCopy }: {
  criteria: Criteria | undefined
  hold?: string
  refresh: number
  ledger: Ledger
  onCopy: (match: EntryMatch) => void
}) {
  const key = criteria ? JSON.stringify(criteria) : ''
  const [shown, setShown] = useState<Shown>()
  const [failed, setFailed] = useState(false)
  const [loading, setLoading] = useState(false)
  // The number of the latest question: an answer to an older one is dropped.
  const asked = useRef(0)
  const lastRefresh = useRef(refresh)

  useEffect(() => {
    const id = ++asked.current
    if (!criteria) {
      setShown(undefined)
      setFailed(false)
      setLoading(false)
      return
    }
    const now = lastRefresh.current !== refresh
    lastRefresh.current = refresh
    if (!now && key === hold) return
    const timer = setTimeout(() => {
      setLoading(true)
      api<EntryMatchPage>(matchesQuery(criteria, 0))
        .then((page) => {
          if (id !== asked.current) return
          setShown({ items: page.content, total: page.totalElements, page: 0, pages: page.totalPages })
          setFailed(false)
        })
        .catch(() => {
          if (id !== asked.current) return
          setShown(undefined)
          setFailed(true)
        })
        .finally(() => { if (id === asked.current) setLoading(false) })
    }, now ? 0 : SIMILAR_DELAY_MS)
    return () => clearTimeout(timer)
    // `criteria` is read through `key`, its JSON.
  }, [key, hold, refresh])

  function more() {
    if (!criteria || !shown) return
    const id = asked.current
    const next = shown.page + 1
    setLoading(true)
    api<EntryMatchPage>(matchesQuery(criteria, next))
      .then((page) => {
        if (id !== asked.current) return
        setShown({ items: [...shown.items, ...page.content], total: page.totalElements, page: next, pages: page.totalPages })
      })
      .catch(() => { if (id === asked.current) setFailed(true) })
      .finally(() => { if (id === asked.current) setLoading(false) })
  }

  if (!criteria) return null
  return (
    <section className="similar" aria-labelledby="similar-title">
      <h3 id="similar-title" aria-live="polite">
        Similar entries{shown ? ` · ${shown.total}` : ''}
      </h3>
      {failed && <p className="muted">Couldn’t look for similar entries.</p>}
      {shown && shown.items.length === 0 && !failed && <p className="muted">No saved entry matches.</p>}
      {shown && shown.items.length > 0 && (
        <ul className={`similar-list ${loading ? 'stale' : ''}`}>
          {shown.items.map((match) => (
            <SimilarRow key={match.entry.id} match={match} type={criteria.type} ledger={ledger} onCopy={onCopy} />
          ))}
        </ul>
      )}
      {shown && shown.items.length < shown.total && !failed && (
        <button type="button" onClick={more} disabled={loading}>Show {MATCHES_PAGE} more</button>
      )}
    </section>
  )
}

/**
 * One saved entry: the date, the payee and the account; the category, and for a family row the Entries list's family
 * line (D-442); the amount, a refund as the Entries list shows one; the memo, muted; and Copy, named by the row.
 */
function SimilarRow({ match, type, ledger, onCopy }: {
  match: EntryMatch
  type: Criteria['type']
  ledger: Ledger
  onCopy: (match: EntryMatch) => void
}) {
  const { entry } = match
  const date = formatDate(entry.entryDate)
  const payee = counterpartyById(ledger, entry.payeeId)?.name
  const account = matchAccount(match, ledger)
  const category = categoryById(ledger, match.categoryId)
  const sign = matchSign(match, type)
  const amount = match.amount && match.currency
    ? formatMoney(sign < 0 ? negate(match.amount) : match.amount, match.currency, { signed: true }) : ''
  const plain = match.amount && match.currency ? formatMoney(match.amount, match.currency) : ''
  const name = ['Copy the entry of ' + date, payee, plain].filter(Boolean).join(', ')
  return (
    <li className="similar-row">
      <span className="date nowrap">{date}</span>
      <span className="payee">{payee || <span className="muted">—</span>}</span>
      <span className="account muted">{account?.name ?? ''}</span>
      <span className="category">
        {category?.name ?? <span className="muted">—</span>}
        {entry.family && (
          <span className="muted small family-line">
            {`${entryKindLabel(entry)} · `}
            <SharedMark budget={entry.family.ledgerName} named />
          </span>
        )}
      </span>
      <span className={`amount nowrap ${sign < 0 ? 'expense' : 'income'}`}>{amount}</span>
      {entry.memo && <span className="memo muted small">{entry.memo}</span>}
      <button type="button" className="copy" aria-label={name} onClick={() => onCopy(match)}>Copy</button>
    </li>
  )
}
