import type { Account, AccountType } from './api'
import type { Ledger } from './ledger'

// Reference data for tests, shaped like the starter ledger (backend: seed/starter-ledger.json).

const account = (id: number, code: string, name: string, type: AccountType, defaultCurrency: string | null,
  extra: Partial<Account> = {}): Account => ({
  id, code, name, type, defaultCurrency, requiresCounterparty: false, system: false, archived: false, ...extra,
})

export const testLedger: Ledger = {
  accounts: [
    account(1, 'CASH', 'Cash', 'ASSET', 'EUR'),
    account(2, 'CURRENT_ACCOUNT', 'ING', 'ASSET', 'EUR'),
    account(3, 'LOANS_ASSET', 'Loans given', 'ASSET', 'EUR', { requiresCounterparty: true }),
    account(4, 'CREDITOR_DEBT', 'Debts to creditors', 'LIABILITY', 'EUR', { requiresCounterparty: true }),
    account(5, 'FAMILY_DEBT', 'Family budget', 'LIABILITY', 'EUR'),
    account(6, 'UNALLOCATED', 'Unallocated', 'EQUITY', 'EUR'),
    account(7, 'OPENING_BALANCE', 'Opening balance', 'EQUITY', null, { system: true }),
    account(8, 'FX_EXCHANGE', 'Currency exchange', 'EQUITY', null, { system: true }),
    account(9, 'WISE_USD', 'Wise', 'ASSET', 'USD'),
    account(10, 'OLD_BANK', 'Old bank', 'ASSET', 'EUR', { archived: true }),
  ],
  categories: [
    { id: 11, code: 'SALARY', name: 'Salary', type: 'INCOME', archived: false },
    { id: 12, code: 'GROCERIES', name: 'Groceries', type: 'EXPENSE', archived: false },
    { id: 13, code: 'EATING_OUT', name: 'Eating out', type: 'EXPENSE', archived: false },
  ],
  counterparties: [
    { id: 21, name: 'Albert Heijn', kind: 'MERCHANT', archived: false, lastCategoryId: 12 },
    { id: 22, name: 'Ivan', kind: 'PERSON', archived: false, lastCategoryId: null },
    { id: 23, name: 'Employer', kind: 'ORGANIZATION', archived: false, lastCategoryId: 11 },
  ],
  settings: { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' },
}
