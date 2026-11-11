// A member's payees in a family budget (UX-9): the payee of each family expense and income they paid or received is
// their own data on their own entry, and the other members see its name only while the member shows their payees in
// that budget's settings. These texts follow the reader's own switch for the budget; no React here.

/** The payee field's hint on the payment forms. */
export function payeeHint(shown: boolean) {
  return shown
    ? 'Where it was paid, such as a shop. It stays on your own entry; the other members of this budget see its name, as you chose in its settings.'
    : 'Where it was paid, such as a shop. It stays on your own entry; the other members never see it.'
}

/** New entry's payee label for a family expense. */
export function payeeLabel(shown: boolean) {
  return shown ? 'Payee (the other members see it)' : 'Payee (only you see it)'
}

/** Under the payer's own payee on the record's page. */
export function ownPayeeNote(shown: boolean) {
  return shown ? 'The other members see it.' : 'Only you see it.'
}

/** Under another member's payee on the record's page. */
export function shownByNote(payer: string) {
  return `Shown by ${payer}.`
}

/** The confirmation before the reader switches their payees on or off in a budget. */
export function showPayeesQuestion(on: boolean, budget: string) {
  return on
    ? `Show your payees to the other members of “${budget}”? They will see where you paid for every family expense and income you paid or received in this budget, past ones included.`
    : `Hide your payees from the other members of “${budget}”? They will no longer see them, past records included.`
}

export const SHOW_PAYEES_LABEL = 'Show the other members where I paid'
export const SHOW_PAYEES_HINT = 'The payee of each family expense and income you paid or received, past ones included. Your accounts, notes and paying amounts stay private.'
