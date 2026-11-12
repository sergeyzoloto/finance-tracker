import type { Account, Counterparty } from './api'
import { CounterpartyInput, Field } from './components'
import { NEW_PAYEE_HINT, useFamilyPayees } from './familyPayees'
import { counterpartyCalled } from './payeeNames'
import { payeeHint } from './payees'

// The reader's own counterparties on their payment (F8d; D-80, D-81): the counterparty of an account that requires one
// and the payee. Both are counterparties of their personal ledger, and only they see them: no other member's answer
// names either, and the family journal doesn't, unless the payer shows their payees (UX-9). Since UX-12 (D-549) the
// payee is typed freely, suggesting the user's counterparties and the family payees; a new name becomes the user's
// counterparty when the form is saved (`familyPayees.payeeIdFor`).

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
 * @param payeesShown whether the reader shows their payees to the other members of the budget (UX-9), which the hint
 *        says
 */
/** The payee field's hint: whom it shows to, and for a name no counterparty of the user's has that it is added. */
export function payeeFieldHint(payee: string, counterparties: Counterparty[], payeesShown: boolean) {
  const known = payee.trim() === '' || counterpartyCalled(counterparties, payee)
  return known ? payeeHint(payeesShown) : <>{payeeHint(payeesShown)} {NEW_PAYEE_HINT}</>
}

export function CounterpartyFields({ account, counterparties, counterpartyId, payee, onCounterparty, onPayee, showPayee,
  payeesShown = false, errors }: {
  account: Account | undefined
  counterparties: Counterparty[] | undefined
  counterpartyId: string
  /** The payee's name as typed (D-549). */
  payee: string
  onCounterparty: (id: string) => void
  onPayee: (name: string) => void
  showPayee: boolean
  payeesShown?: boolean
  errors: string[]
}) {
  const familyPayees = useFamilyPayees()
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
          hint={payeeFieldHint(payee, counterparties ?? [], payeesShown)}>
          <CounterpartyInput counterparties={counterparties ?? []} familyPayees={familyPayees} value={payee}
            onChange={onPayee} />
        </Field>
      )}
    </>
  )
}
