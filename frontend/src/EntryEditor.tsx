import { useRef } from 'react'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import { api, formatDate, isoDate, useApi, type Counterparty, type Entry, type FamilyLedger } from './api'
import { Errors, Loading } from './components'
import {
  formFromEntry, newCounterpartyNames, newForm, serverErrors, TABS, tabOf, toCommand, type EntryForm, type Tab,
} from './entryForm'
import { EntryFormView } from './EntryForms'
import { describeEntry, kindLabel, useLedger, type Ledger } from './ledger'
import { formatMoney } from './money'
import PaymentEntry from './PaymentEntry'

/**
 * `/entries/new?tab=…` writes a new entry; `/entries/:id` edits one in the form of its kind.
 *
 * @param families the user's family budgets while the family budget is switched on (D-25), else undefined: a new
 *        expense can then be a family expense (C2), and the user's payment for a family expense changes as one (F4c)
 */
export default function EntryEditor({ families }: { families?: FamilyLedger[] }) {
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
  if (entry.data?.family?.link === 'PAYMENT' && families) {
    return <PaymentEntry key={`${entry.data.id}:${entry.data.version}`} entry={entry.data} ledger={ledger}
      onSaved={entry.reload} onDeleted={() => navigate(back)} />
  }
  if (entry.data?.family?.readOnly) {
    return <ReadOnlyEntry entry={entry.data} ledger={ledger} onBack={() => navigate(back)} />
  }

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

  /** A family expense (C2), created through the family budget's endpoint as the family pages create one. */
  async function saveFamily(ledgerId: number, request: object, andNew: boolean) {
    await api(`/family-ledgers/${ledgerId}/records`, 'POST', request)
    if (!andNew) navigate(back)
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
        families={entry.data ? undefined : families}
        onSaveFamily={entry.data || !families ? undefined : saveFamily}
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

/**
 * An entry that a family budget posted, or the payer's payment for a family record: it changes only through the
 * record (D-8), so it shows without a form, a save or a delete, and links to the record's page, "the expense".
 */
function ReadOnlyEntry({ entry, ledger, onBack }: { entry: Entry; ledger: Ledger; onBack: () => void }) {
  const family = entry.family!
  const summary = describeEntry(entry, ledger)
  const budget = <Link to={`/family/${family.ledgerId}`}>{family.ledgerName}</Link>
  const expense = family.recordId === null ? null : `/family/${family.ledgerId}/expenses/${family.recordId}`
  return (
    <>
      <h2>{kindLabel(entry.kind)}</h2>
      <p className="notice">
        {family.link === 'PAYMENT'
          ? <>Your payment for an expense of the family budget {budget}. To change it, change or delete the expense there.</>
          : <>Posted by the family budget {budget}. It changes only there.</>}
        {expense && <> <Link to={expense}>Open the expense</Link></>}
      </p>
      <dl className="read-only">
        <dt>Date</dt>
        <dd>{formatDate(entry.entryDate)}</dd>
        {summary.category && <><dt>Category</dt><dd>{summary.category}</dd></>}
        <dt>Accounts</dt>
        <dd>{summary.flow}</dd>
        <dt>Amount</dt>
        <dd>{summary.amounts.map((m) => formatMoney(m.amount, m.currency)).join(' → ')}</dd>
        {entry.memo && <><dt>Memo</dt><dd>{entry.memo}</dd></>}
      </dl>
      <div className="actions">
        <button type="button" onClick={onBack}>Back</button>
      </div>
    </>
  )
}
