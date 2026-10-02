import type { FamilyReport } from './api'

// The backend's worked example (FamilyReportApiTests): Mum, Dad, who left, and Kid.
export const workedReport: FamilyReport = {
  currency: 'EUR', from: null, to: null,
  members: [
    { memberId: 1, displayName: 'Mum', status: 'ACTIVE', hasAccount: true, you: true },
    { memberId: 2, displayName: 'Dad', status: 'LEFT', hasAccount: true, you: false },
    { memberId: 3, displayName: 'Kid', status: 'ACTIVE', hasAccount: false, you: false },
  ],
  rows: [
    { month: '2026-09', categoryId: 10, categoryName: 'Groceries', categoryType: 'EXPENSE', archived: false, total: '90.00',
      members: [{ memberId: 1, share: '30.00', paid: '90.00' }, { memberId: 2, share: '30.00', paid: '0.00' }, { memberId: 3, share: '30.00', paid: '0.00' }] },
    { month: '2026-09', categoryId: 12, categoryName: 'Rent', categoryType: 'EXPENSE', archived: false, total: '50.00',
      members: [{ memberId: 1, share: '25.00', paid: '0.00' }, { memberId: 2, share: '25.00', paid: '50.00' }] },
    { month: '2026-09', categoryId: 11, categoryName: 'Salary', categoryType: 'INCOME', archived: false, total: '300.00',
      members: [{ memberId: 1, share: '100.00', paid: '300.00' }, { memberId: 2, share: '100.00', paid: '0.00' }, { memberId: 3, share: '100.00', paid: '0.00' }] },
    { month: '2026-10', categoryId: 10, categoryName: 'Groceries', categoryType: 'EXPENSE', archived: false, total: '10.01',
      members: [{ memberId: 1, share: '3.33', paid: '0.00' }, { memberId: 2, share: '3.33', paid: '0.00' }, { memberId: 3, share: '3.35', paid: '10.01' }] },
  ],
  totals: [
    { memberId: 1, expenseShares: '58.33', expensesPaid: '90.00', incomeShares: '100.00', incomesReceived: '300.00', settlementsPaid: '0.00', settlementsReceived: '20.00', net: '188.33' },
    { memberId: 2, expenseShares: '58.33', expensesPaid: '50.00', incomeShares: '100.00', incomesReceived: '0.00', settlementsPaid: '20.00', settlementsReceived: '0.00', net: '-111.67' },
    { memberId: 3, expenseShares: '33.35', expensesPaid: '10.01', incomeShares: '100.00', incomesReceived: '0.00', settlementsPaid: '0.00', settlementsReceived: '0.00', net: '-76.66' },
  ],
}
