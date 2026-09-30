import { describe, expect, it } from 'vitest'
import type { FamilyBalance, FamilyBalances, FamilyChange, MemberRef } from './api'
import { balanceWords, debtSentence, journalLine, violationsByMember, whoOwesWhom, yourBalance } from './family'

describe('violationsByMember', () => {
  it('sorts the violations of a 422 by the member each names', () => {
    const { byMember, other } = violationsByMember([
      { code: 'NO_SHARE', memberId: 71, message: 'Kid has no share' },
      { code: 'NOT_ACTIVE_MEMBER', memberId: 99, message: 'Member 99 is not an active member of the family budget' },
      { code: 'SUM_NOT_WHOLE', memberId: null, message: 'the shares sum to 60.00 %, not 100.00 %' },
      { code: 'DUPLICATE_SHARE', memberId: 71, message: 'Member 71 has more than one share' },
    ])
    expect([...byMember]).toEqual([
      [71, ['Kid has no share', 'Member 71 has more than one share']],
      [99, ['Member 99 is not an active member of the family budget']],
    ])
    expect(other).toEqual(['the shares sum to 60.00 %, not 100.00 %'])
  })

  it('shows messages as the server words them, with nothing rewritten', () => {
    const { other } = violationsByMember([{ code: 'X', memberId: null, message: 'The family ledger of 3333 basis points' }])
    expect(other).toEqual(['The family ledger of 3333 basis points'])
  })
})

const balance = (memberId: number, displayName: string, amount: string, extra: Partial<FamilyBalance> = {}): FamilyBalance =>
  ({ memberId, displayName, status: 'ACTIVE', hasAccount: false, balance: amount, you: false, ...extra })
const budget = (...members: FamilyBalance[]): FamilyBalances => ({ currency: 'EUR', members })

describe('balances in words', () => {
  it('reads two members from the reader’s side', () => {
    const owedToYou = budget(balance(1, 'Anna', '-40.00', { you: true, hasAccount: true }), balance(2, 'Sam', '40.00'))
    expect(yourBalance(owedToYou)).toEqual(['Sam owes you €40.00'])
    expect(owedToYou.members.map((m) => balanceWords(m, 'EUR'))).toEqual(['You are owed €40.00', 'Sam owes €40.00'])

    const youOwe = budget(balance(1, 'Anna', '12.50', { you: true, hasAccount: true }), balance(2, 'Sam', '-12.50'))
    expect(yourBalance(youOwe)).toEqual(['You owe Sam €12.50'])
    expect(balanceWords(youOwe.members[0], 'EUR')).toBe('You owe €12.50')

    const settled = budget(balance(1, 'Anna', '0.00', { you: true, hasAccount: true }), balance(2, 'Sam', '0.00'))
    expect(yourBalance(settled)).toEqual(['You are settled'])
    expect(settled.members.map((m) => balanceWords(m, 'EUR'))).toEqual(['You are settled', 'Sam is settled'])
  })

  it('pairs three members, the one who owes most with the one owed most', () => {
    const three = budget(balance(1, 'Anna', '-60.00', { you: true, hasAccount: true }), balance(2, 'Sam', '40.00'),
      balance(3, 'Kid', '20.00'))
    expect(whoOwesWhom(three).map((d) => debtSentence(d, 'EUR'))).toEqual(['Sam owes you €40.00', 'Kid owes you €20.00'])
    expect(yourBalance(three)).toEqual(['Sam owes you €40.00', 'Kid owes you €20.00'])

    // Two owed by one; the reader is owed, and sees only their own part.
    const other = budget(balance(1, 'Anna', '-30.00', { you: true, hasAccount: true }), balance(2, 'Sam', '-10.00'),
      balance(3, 'Kid', '40.00'))
    expect(whoOwesWhom(other).map((d) => debtSentence(d, 'EUR'))).toEqual(['Kid owes you €30.00', 'Kid owes Sam €10.00'])
    expect(yourBalance(other)).toEqual(['Kid owes you €30.00'])

    // Settled among the others: nothing about the reader.
    const theirs = budget(balance(1, 'Anna', '0.00', { you: true, hasAccount: true }), balance(2, 'Sam', '-0.01'),
      balance(3, 'Kid', '0.01'))
    expect(yourBalance(theirs)).toEqual(['You are settled'])
    expect(whoOwesWhom(theirs).map((d) => debtSentence(d, 'EUR'))).toEqual(['Kid owes Sam €0.01'])
  })

  it('settles every balance, whatever their order', () => {
    const many = budget(balance(1, 'A', '15.00'), balance(2, 'B', '-7.50'), balance(3, 'C', '-7.50'),
      balance(4, 'D', '3.33'), balance(5, 'E', '-3.33'))
    const debts = whoOwesWhom(many)
    expect(debts.map((d) => `${d.from.displayName}→${d.to.displayName} ${d.amount}`))
      .toEqual(['A→B 7.50', 'A→C 7.50', 'D→E 3.33'])
  })
})

describe('the journal in words', () => {
  const anna: MemberRef = { memberId: 70, displayName: 'Anna' }
  const sam: MemberRef = { memberId: 71, displayName: 'Sam' }
  const record = { date: '2026-09-12', category: 'Groceries', amount: '20.00', deleted: false }
  const entry = (change: Partial<FamilyChange>): FamilyChange => ({
    id: 1, at: '2026-09-30T10:00:00Z', action: 'UPDATE', recordId: 5, author: anna, about: null, changes: [], record, ...change,
  })

  it('says who added an expense, how it is split, and the comment', () => {
    expect(journalLine(entry({
      action: 'CREATE',
      changes: [
        { field: 'date', member: null, old: null, new: '2026-09-12' },
        { field: 'category', member: null, old: null, new: 'Groceries' },
        { field: 'amount', member: null, old: null, new: '20.00' },
        { field: 'payer', member: null, old: null, new: 'Sam' },
        { field: 'splitMethod', member: null, old: null, new: 'EQUAL' },
        { field: 'share', member: anna, old: null, new: '10.00' },
        { field: 'share', member: sam, old: null, new: '10.00' },
        { field: 'comment', member: null, old: null, new: 'Weekly shop' },
      ],
    }), 'EUR')).toEqual({
      text: 'Anna added Groceries, Sep 12, 2026: €20.00, paid by Sam.',
      details: ['Split: equal shares: Anna €10.00, Sam €10.00', 'Comment: “Weekly shop”'],
    })
  })

  it('says what changed in one sentence, or line by line', () => {
    expect(journalLine(entry({
      changes: [
        { field: 'splitMethod', member: null, old: 'EQUAL', new: 'ONE_MEMBER' },
        { field: 'share', member: anna, old: '10.00', new: null },
        { field: 'share', member: sam, old: '10.00', new: '20.00' },
      ],
    }), 'EUR').text).toBe('Anna changed the split of Groceries, Sep 12, 2026: equal shares → one member; '
      + 'Anna €10.00 → no share, Sam €10.00 → €20.00.')

    expect(journalLine(entry({
      changes: [
        { field: 'category', member: null, old: 'Rent', new: 'Groceries' },
        { field: 'share', member: sam, old: '10.00', new: '12.00' },
        { field: 'share', member: anna, old: '10.00', new: '8.00' },
        { field: 'comment', member: null, old: null, new: 'Split differently' },
      ],
    }), 'EUR')).toEqual({
      text: 'Anna changed the category, the split and the comment of Groceries, Sep 12, 2026:',
      details: ['Category: Rent → Groceries', 'Split: Sam €10.00 → €12.00, Anna €10.00 → €8.00', 'Comment: none → “Split differently”'],
    })
  })

  it('names a deleted expense and a member who deleted their data as the server does', () => {
    expect(journalLine(entry({
      action: 'DELETE', author: { memberId: 72, displayName: 'Former member' }, record: { ...record, deleted: true },
    }), 'EUR')).toEqual({ text: 'Former member deleted Groceries, Sep 12, 2026, €20.00.', details: [] })

    // An erased comment reads as none.
    expect(journalLine(entry({
      author: { memberId: 72, displayName: 'Former member' },
      changes: [{ field: 'comment', member: null, old: null, new: null }],
    }), 'EUR').text).toBe('Former member changed the comment of Groceries, Sep 12, 2026: none → none.')
  })

  it('says what the system changed, in plain words', () => {
    expect(journalLine(entry({
      action: 'SPLIT_RULE_RESET', author: null, recordId: null, record: null,
      about: { memberId: 72, displayName: 'Former member' },
      changes: [{ field: 'splitRule', member: null, old: 'CUSTOM', new: 'EQUAL' }],
    }), 'EUR')).toEqual({ text: 'The split rule went back to equal shares when a member left: Former member.', details: [] })
  })
})
