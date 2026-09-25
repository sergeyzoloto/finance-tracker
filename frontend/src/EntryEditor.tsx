import { useRef } from 'react'
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import { api, isoDate, useApi, type Counterparty, type Entry } from './api'
import { Errors, Loading } from './components'
import {
  formFromEntry, newCounterpartyNames, newForm, serverErrors, TABS, tabOf, toCommand, type EntryForm, type Tab,
} from './entryForm'
import { EntryFormView } from './EntryForms'
import { useLedger, type Ledger } from './ledger'

/** `/entries/new?tab=…` writes a new entry; `/entries/:id` edits one in the form of its kind. */
export default function EntryEditor() {
  const { id } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const location = useLocation()
  const { ledger, error, reloadCounterparties } = useLedger()
  const entry = useApi<Entry>(id ? `/entries/${id}` : null)
  // Counterparties this page created, until the reloaded list has them: "Save and add another" may reuse one at once.
  const created = useRef<Counterparty[]>([])
  // Back to the list as the user left it, filters and page included.
  const back = (location.state as { from?: string } | null)?.from ?? '/entries'

  const title = <h2>{id ? 'Edit entry' : 'New entry'}</h2>
  if (error ?? entry.error) return <>{title}<Errors messages={[error ?? entry.error]} /></>
  if (!ledger || (id && !entry.data)) return <>{title}<Loading /></>

  const tab = TABS.some((t) => t.tab === params.get('tab')) ? params.get('tab') as Tab : 'expense'
  const { form, simple } = entry.data ? formFromEntry(entry.data, ledger) : { form: newForm(ledger, isoDate(new Date()), tab), simple: true }

  async function save(form: EntryForm, andNew: boolean) {
    const withCreated = (): Ledger => ({
      ...ledger!,
      counterparties: [...ledger!.counterparties, ...created.current.filter((c) => !ledger!.counterparties.some((k) => k.id === c.id))],
    })
    let current = withCreated()
    try {
      // Payees and borrowers typed for the first time become counterparties first.
      const names = newCounterpartyNames(form, current)
      for (const name of names) {
        created.current.push(await api<Counterparty>('/counterparties', 'POST', { name }))
      }
      if (names.length > 0) {
        current = withCreated()
        reloadCounterparties()
      }
      const command = toCommand(form, current)
      if (entry.data) {
        await api(`/entries/${entry.data.id}?version=${entry.data.version}`, 'PUT', command)
      } else {
        await api('/entries', 'POST', command)
      }
      if (!andNew) navigate(back)
    } catch (e) {
      return serverErrors(form, current, e)
    }
  }

  async function remove() {
    try {
      await api(`/entries/${entry.data!.id}?version=${entry.data!.version}`, 'DELETE')
      navigate(back)
    } catch (e) {
      return { '': [e instanceof Error ? e.message : 'Deleting failed.'] }
    }
  }

  return (
    <>
      {title}
      <EntryFormView
        key={entry.data ? `${entry.data.id}:${entry.data.version}` : 'new'}
        ledger={ledger}
        initial={form}
        onSave={save}
        onDelete={entry.data ? remove : undefined}
        onCancel={() => navigate(back)}
        notice={!simple && (
          <p className="notice">
            This entry doesn’t fit the {TABS.find((t) => t.tab === tabOf(entry.data!.kind))?.label} form, so it opens
            as raw postings. Saving it keeps the postings as they are here.
          </p>
        )}
      />
    </>
  )
}
