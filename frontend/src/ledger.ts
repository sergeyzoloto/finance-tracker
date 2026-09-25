import { useMemo } from 'react'
import { useApi, type Account, type AccountType, type Category, type Counterparty, type Entry, type Settings } from './api'
import { abs, signOf, sum } from './money'

/** The user's reference data, which entry screens need to show and build entries. */
export interface Ledger {
  accounts: Account[]
  categories: Category[]
  counterparties: Counterparty[]
  settings: Settings
}

/** Loads the reference data; `ledger` is there once all of it is. */
export function useLedger() {
  const accounts = useApi<Account[]>('/accounts')
  const categories = useApi<Category[]>('/categories')
  const counterparties = useApi<Counterparty[]>('/counterparties')
  const settings = useApi<Settings>('/settings')
  const ledger = useMemo(
    () => accounts.data && categories.data && counterparties.data && settings.data
      ? { accounts: accounts.data, categories: categories.data, counterparties: counterparties.data, settings: settings.data }
      : undefined,
    [accounts.data, categories.data, counterparties.data, settings.data])
  return {
    ledger,
    error: accounts.error ?? categories.error ?? counterparties.error ?? settings.error,
    reloadCounterparties: counterparties.reload,
  }
}

// Accounts that commands post to on their own (AccountRole on the backend), by code.
export const UNALLOCATED = 'UNALLOCATED'
export const FX_EXCHANGE = 'FX_EXCHANGE'
export const LOANS = 'LOANS_ASSET'
const FAMILY_DEBT = 'FAMILY_DEBT'

export const TYPE_LABELS: Record<AccountType, string> = { ASSET: 'Assets', LIABILITY: 'Liabilities', EQUITY: 'Equity' }
export const ACCOUNT_TYPES: AccountType[] = ['ASSET', 'LIABILITY', 'EQUITY']

export const accountById = (ledger: Ledger, id: number | null | undefined) => ledger.accounts.find((a) => a.id === id)
export const accountWithCode = (ledger: Ledger, code: string) => ledger.accounts.find((a) => a.code === code)
export const categoryById = (ledger: Ledger, id: number | null | undefined) => ledger.categories.find((c) => c.id === id)
export const counterpartyById = (ledger: Ledger, id: number | null | undefined) =>
  ledger.counterparties.find((c) => c.id === id)

/** The counterparty with this name, in any case, as the backend matches names. */
export function counterpartyNamed(ledger: Ledger, name: string) {
  const wanted = name.trim().toLocaleLowerCase()
  return wanted === '' ? undefined : ledger.counterparties.find((c) => c.name.toLocaleLowerCase() === wanted)
}

/** The account that receives the other part of a shared expense (rule 7): the settings', or FAMILY_DEBT. */
export const sharedAccount = (ledger: Ledger) => ledger.settings.sharedAccountId !== null
  ? accountById(ledger, ledger.settings.sharedAccountId)
  : accountWithCode(ledger, FAMILY_DEBT)

/** Currencies to suggest: the base currency and the accounts' default currencies. */
export function knownCurrencies(ledger: Ledger) {
  const codes = new Set([ledger.settings.baseCurrency])
  ledger.accounts.forEach((a) => a.defaultCurrency && codes.add(a.defaultCurrency))
  return [...codes].sort()
}

/** An amount in one currency. */
export interface Money { currency: string; amount: string }

/** How an entry reads in a list, without debit and credit. */
export interface EntrySummary {
  /** The payee, or else whoever the postings are with, such as a borrower. */
  payee: string
  category: string
  /** Where the money went, such as "ING → Cash" or "Current account → Groceries". */
  flow: string
  /** The money moved, per currency: the sum of what went in, which equals what went out. */
  amounts: Money[]
  /** −1 for money spent, 1 for money received, 0 for money moved between accounts. */
  direction: -1 | 0 | 1
}

/**
 * Reads an entry from its postings alone, whatever its kind (rule 6). Categorized postings stand for the income or
 * expense they record, so an expense reads "account → category" and an income "category → account". Postings to
 * FX_EXCHANGE, which only let each currency of an exchange balance, are left out.
 */
export function describeEntry(entry: Entry, ledger: Ledger): EntrySummary {
  const fx = accountWithCode(ledger, FX_EXCHANGE)?.id
  const shown = entry.postings.some((p) => p.accountId !== fx)
    ? entry.postings.filter((p) => p.accountId !== fx) : entry.postings
  const label = (p: Entry['postings'][number]) => {
    if (p.categoryId !== null) return categoryById(ledger, p.categoryId)?.name ?? 'Unknown category'
    const account = accountById(ledger, p.accountId)?.name ?? 'Unknown account'
    const counterparty = counterpartyById(ledger, p.counterpartyId)?.name
    return counterparty ? `${account} (${counterparty})` : account
  }
  const unique = (items: string[]) => [...new Set(items)]
  const from = unique(shown.filter((p) => signOf(p.amount) < 0).map(label))
  const to = unique(shown.filter((p) => signOf(p.amount) > 0).map(label))

  const currencies = unique(entry.postings.map((p) => p.currency))
  const amounts = currencies.map((currency) => ({
    currency,
    amount: sum(entry.postings.filter((p) => p.currency === currency && signOf(p.amount) > 0).map((p) => p.amount)),
  }))
  // An expense is a positive categorized posting (rule 5); a refund, with the opposite sign, brings money back.
  const categorized = entry.postings.filter((p) => p.categoryId !== null)
  const net = categorized.length === 0 ? '0' : sum(categorized.map((p) => p.amount))
  const direction = (0 - signOf(net)) as -1 | 0 | 1

  const payee = counterpartyById(ledger, entry.payeeId)?.name
    ?? unique(entry.postings.flatMap((p) => counterpartyById(ledger, p.counterpartyId)?.name ?? [])).join(', ')
  return {
    payee,
    category: unique(categorized.map(label)).join(', '),
    flow: `${from.join(', ') || '—'} → ${to.join(', ') || '—'}`,
    amounts: amounts.map((m) => ({ ...m, amount: abs(m.amount) })),
    direction,
  }
}
