import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { api, ApiError, formatInstant } from './api'
import { Errors, Loading } from './components'
import {
  actorLabel, causeLabel, changeLine, historyPath, isLastPage, namesOf, opLabel, withMore, type HistoryPage,
  type HistoryVersion, type RecordKind,
} from './history'
import type { Ledger } from './ledger'

/** The sheet's own breakpoint is the app's: under 40rem the panel fills the screen. */
const WIDE = '(min-width: 40rem)'

function useSheet(): boolean {
  const query = () => (typeof matchMedia === 'function' ? matchMedia(WIDE) : null)
  const [wide, setWide] = useState(() => query()?.matches ?? true)
  useEffect(() => {
    const list = query()
    if (!list) return
    const change = () => setWide(list.matches)
    list.addEventListener('change', change)
    return () => list.removeEventListener('change', change)
  }, [])
  return !wide
}

type Load = 'loading' | 'ready'

/** A record's versions, newest first: the first page on mount, more on request; the loaded ones stay on an error. */
function useHistory(kind: RecordKind, id: number) {
  const [versions, setVersions] = useState<HistoryVersion[]>([])
  const [last, setLast] = useState<HistoryPage>()
  const [state, setState] = useState<Load>('loading')
  const [error, setError] = useState<string>()
  const [missing, setMissing] = useState(false)
  const latest = useRef(0)

  const fetchPage = useCallback((page: number, first: boolean) => {
    const request = ++latest.current
    setState('loading')
    setError(undefined)
    api<HistoryPage>(historyPath(kind, id, page)).then(
      (answer) => {
        if (request !== latest.current) return
        setVersions((loaded) => (first ? answer.content : withMore(loaded, answer.content)))
        setLast(answer)
        setState('ready')
      },
      (e) => {
        if (request !== latest.current) return
        if (e instanceof ApiError && e.status === 404) setMissing(true)
        else setError(e instanceof Error ? e.message : 'The history could not be loaded.')
        setState('ready')
      },
    )
  }, [kind, id])

  useEffect(() => {
    setVersions([])
    setLast(undefined)
    setMissing(false)
    fetchPage(0, true)
    return () => { latest.current++ }
  }, [fetchPage])

  const nextPage = last ? last.page + 1 : 0
  return {
    versions, state, error, missing,
    more: last !== undefined && !isLastPage(last),
    loadMore: () => fetchPage(nextPage, false),
    retry: () => fetchPage(nextPage, last === undefined),
  }
}

/**
 * A record's history in a side panel (a full-screen sheet under 40rem), opened from the record and closed with its
 * Close button or Escape. Its state is the component's own: nothing of it is in the URL.
 *
 * @param subject what the record is called, shown under the title
 * @param ledger what names a personal entry's accounts, categories and payee; an id it can't name shows as removed
 */
export default function HistoryPanel({ kind, id, subject, ledger, onClose }: {
  kind: RecordKind
  id: number
  subject?: string
  ledger?: Ledger
  onClose: () => void
}) {
  const sheet = useSheet()
  const history = useHistory(kind, id)
  const names = useMemo(() => namesOf(ledger), [ledger])
  const close = useRef<HTMLButtonElement>(null)
  useEffect(() => close.current?.focus(), [])
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  const first = history.versions.length === 0
  const loading = history.state === 'loading'
  return createPortal(
    <div className="history-backdrop" onClick={onClose}>
      <aside className={`history-panel ${sheet ? 'sheet' : ''}`} role="dialog" aria-modal="true" aria-labelledby="history-title"
        onClick={(e) => e.stopPropagation()}>
        <header className="history-head">
          <div>
            <h3 id="history-title">History</h3>
            {subject && <p className="muted small">{subject}</p>}
          </div>
          <button type="button" ref={close} onClick={onClose}>Close</button>
        </header>
        {history.missing && <p className="empty">There is no history for this item.</p>}
        {!history.missing && first && loading && <Loading what="the history" />}
        {!history.missing && first && !loading && history.error === undefined && (
          <p className="empty">No changes recorded.</p>
        )}
        {!history.missing && first && history.error !== undefined && (
          <>
            <Errors messages={[history.error]} />
            <button type="button" onClick={history.retry}>Try again</button>
          </>
        )}
        {!history.missing && !first && (
          <ol className="history-list">
            {history.versions.map((v) => <VersionItem key={v.seq} version={v} kind={kind} names={names} />)}
          </ol>
        )}
        {!history.missing && !first && history.error !== undefined && (
          <>
            <Errors messages={[history.error]} />
            <button type="button" onClick={history.retry}>Try again</button>
          </>
        )}
        {!history.missing && !first && history.more && history.error === undefined && (
          <button type="button" className="history-more" disabled={loading} onClick={history.loadMore}>
            {loading ? 'Loading…' : 'Load more'}
          </button>
        )}
      </aside>
    </div>,
    document.body,
  )
}

function VersionItem({ version, kind, names }: { version: HistoryVersion; kind: RecordKind; names: ReturnType<typeof namesOf> }) {
  const cause = causeLabel(version.cause)
  return (
    <li className="history-version">
      <div className="history-line">
        <strong>{opLabel(version.op)}</strong>
        <span className="muted small">{formatInstant(version.at)}</span>
      </div>
      <p className="small">{actorLabel(version.actor)}{cause && <span className="badge">{cause}</span>}</p>
      {version.changes.length > 0 && (
        <ul className="history-changes">
          {version.changes.map((c, i) => <li key={`${c.field}:${i}`}>{changeLine(kind, c, names)}</li>)}
        </ul>
      )}
    </li>
  )
}
