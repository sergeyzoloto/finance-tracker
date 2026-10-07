import type { Account, Counterparty } from './api'
import { Field } from './components'

// The reader's own counterparties on their payment (F8d; D-80, D-81): the counterparty of an account that requires one
// and the payee. Both are counterparties of their personal ledger, and only they see them: no other member's answer
// names either, and the family journal doesn't.

/** The counterparties the reader may name, those not archived first, with the one chosen already always listed. */
function options(counterparties: Counterparty[], chosen: string) {
  return counterparties.filter((c) => !c.archived || String(c.id) === chosen)
    .sort((a, b) => a.name.localeCompare(b.name))
}

/**
 * The counterparty of an account that requires one, required (a loan given, a debt to a creditor: who it is with), and
 * the payee, optional (D-80, D-81). `counterparties` is undefined while they load.
 *
 * @param account the account the reader pays with, or undefined for "Specify later" and the like
 * @param showPayee whether the payee is offered: for a payer with an account, not for a settlement's side
 */
export function CounterpartyFields({ account, counterparties, counterpartyId, payeeId, onCounterparty, onPayee, showPayee,
  errors }: {
  account: Account | undefined
  counterparties: Counterparty[] | undefined
  counterpartyId: string
  payeeId: string
  onCounterparty: (id: string) => void
  onPayee: (id: string) => void
  showPayee: boolean
  errors: string[]
}) {
  return (
    <>
      {account?.requiresCounterparty && (
        <Field label="Counterparty" errors={errors}
          hint={<>The account {account.name} is kept per counterparty: who it is with. Only you see it.</>}>
          <select value={counterpartyId} onChange={(e) => onCounterparty(e.target.value)} required>
            <option value="">Choose a counterparty</option>
            {options(counterparties ?? [], counterpartyId).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </Field>
      )}
      {showPayee && (
        <Field label="Payee (optional)" errors={account?.requiresCounterparty ? [] : errors}
          hint="Where it was paid, such as a shop. It stays on your own entry; the other members never see it.">
          <select value={payeeId} onChange={(e) => onPayee(e.target.value)}>
            <option value="">None</option>
            {options(counterparties ?? [], payeeId).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </Field>
      )}
    </>
  )
}
