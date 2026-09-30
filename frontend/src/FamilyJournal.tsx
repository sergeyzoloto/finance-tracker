import { Link, useSearchParams } from 'react-router'
import { formatInstant, type FamilyChange, type FamilyJournalPage } from './api'
import { Errors, Loading } from './components'
import { journalLine } from './family'
import { useFamilyApi, type FamilyData } from './familyData'

const PAGE_SIZE = 50

/**
 * Changes of the journal as sentences, newest first, with members named as the server names them now. `links` adds a
 * link to each expense that isn't deleted.
 */
export function JournalList({ changes, family, links = true }: { changes: FamilyChange[]; family: FamilyData; links?: boolean }) {
  if (changes.length === 0) return <p className="empty">No changes yet.</p>
  return (
    <ol className="journal">
      {changes.map((change) => {
        const line = journalLine(change, family.ledger.baseCurrency)
        const open = links && change.recordId !== null && change.record && !change.record.deleted
        return (
          <li key={change.id}>
            <small className="muted">{formatInstant(change.at)}</small>
            <p>
              {line.text}
              {change.record?.deleted && <span className="badge">Deleted</span>}
              {open && <> <Link to={`${family.page}/expenses/${change.recordId}`}>Open</Link></>}
            </p>
            {line.details.length > 0 && <ul>{line.details.map((d) => <li key={d}>{d}</li>)}</ul>}
          </li>
        )
      })}
    </ol>
  )
}

/** Who changed what in the family budget, newest first, page by page (D-16; the page in the URL). */
export default function FamilyJournal({ family }: { family: FamilyData }) {
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const journal = useFamilyApi<FamilyJournalPage>(family, `${family.path}/journal?page=${page}&size=${PAGE_SIZE}`)
  const data = journal.data
  const setPage = (p: number) => setParams(p === 0 ? {} : { page: String(p) })
  return (
    <section>
      <h3>Journal</h3>
      <p className="muted">Every member sees who added, changed or deleted which expense, and when.</p>
      <Errors messages={[journal.error]} />
      {!data && !journal.error && <Loading what="the journal" />}
      {data && <JournalList changes={data.content} family={family} />}
      {data && data.totalPages > 1 && (
        <nav className="pager" aria-label="Pages">
          <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>← Newer</button>
          <span>{data.page * data.size + 1}–{data.page * data.size + data.content.length} of {data.totalElements}</span>
          <button type="button" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Older →</button>
        </nav>
      )}
    </section>
  )
}
