import { describe, expect, it } from 'vitest'
import { actorLabel, causeLabel, changeLine, fieldLabel, formatValue, namesOf, opLabel, withMore, type HistoryVersion } from './history'
import { testLedger } from './testLedger'

const names = namesOf(testLedger)
const none = namesOf(undefined)

/** Text as shown, with no-break and thin spaces as spaces. */
const plain = (text: string) => text.replace(/[\u00a0\u2009\u202f]/g, ' ')

describe('what happened', () => {
  it('names each kind of version in plain words', () => {
    expect(['CREATE', 'UPDATE', 'DELETE', 'ARCHIVE', 'RESTORE', 'BASELINE'].map(opLabel)).toEqual([
      'Created', 'Changed', 'Deleted', 'Archived', 'Restored', 'State when history began'])
  })

  it('says a cause only when there is one worth saying', () => {
    expect(causeLabel(null)).toBeNull()
    expect(causeLabel('EDIT')).toBeNull()
    expect(causeLabel('IMPORT')).toBe('Import')
    expect(causeLabel('DEMO')).toBe('Demo data')
    expect(causeLabel('MERGE')).toBe('Merged with another item')
  })

  it('reads a cause it does not know as another change', () => {
    expect(causeLabel('SOMETHING_NEW')).toBe('Other change')
  })

  it('names the actor, with Import and System for those kinds', () => {
    expect(actorLabel({ kind: 'USER', memberId: 3, name: 'Sam' })).toBe('Sam')
    expect(actorLabel({ kind: 'USER', memberId: 3, name: 'Former member' })).toBe('Former member')
    expect(actorLabel({ kind: 'USER', memberId: null, name: null })).toBe('You')
    expect(actorLabel({ kind: 'IMPORT', memberId: null, name: null })).toBe('Import')
    expect(actorLabel({ kind: 'SYSTEM', memberId: null, name: null })).toBe('System')
  })
})

describe('fields and values', () => {
  it('labels a field in the words of the form, and an unknown one as other detail', () => {
    expect(fieldLabel('ENTRY', { field: 'memo' })).toBe('Memo')
    expect(fieldLabel('ACCOUNT', { field: 'archived' })).toBe('Archived')
    expect(fieldLabel('FAMILY_RECORD', { field: 'payer' })).toBe('Paid by')
    expect(fieldLabel('FAMILY_RECORD', { field: 'share', member: { memberId: 1, displayName: 'Sam' } })).toBe('Share of Sam')
    expect(fieldLabel('ENTRY', { field: 'secretInternalKey' })).toBe('Other detail')
  })

  it('shows null as a dash, a boolean as yes or no and a date in the user’s format', () => {
    expect(formatValue('ACCOUNT', 'name', null, names)).toBe('—')
    expect(formatValue('ACCOUNT', 'archived', true, names)).toBe('Yes')
    expect(formatValue('ACCOUNT', 'archived', false, names)).toBe('No')
    expect(formatValue('ENTRY', 'date', '2026-09-12', names)).toBe('Sep 12, 2026')
  })

  it('names a stored id and calls one it cannot name a removed item, never the id', () => {
    expect(formatValue('ENTRY', 'payee', 21, names)).toBe('Albert Heijn')
    expect(formatValue('ENTRY', 'payee', 999, names)).toBe('Removed item')
    expect(formatValue('ENTRY', 'payee', 21, none)).toBe('Removed item')
  })

  it('shows an entry’s postings with money formatted from the decimal strings', () => {
    const postings = [
      { account: 1, currency: 'EUR', amount: '-1234.50' },
      { account: 6, currency: 'EUR', amount: '1234.50', category: 12 },
      { account: 77, currency: 'EUR', amount: '0.10', counterparty: 22 },
    ]
    const text = plain(formatValue('ENTRY', 'postings', postings, names))
    expect(text).toBe('Cash, -€1,234.50; Unallocated, +€1,234.50, Groceries; Removed item, +€0.10, Ivan')
  })

  it('shows a family record’s amounts in their currency, old and new', () => {
    const line = changeLine('FAMILY_RECORD', { field: 'amount', old: '10.00', new: '1250.5', oldCurrency: 'EUR', newCurrency: 'USD' }, none)
    expect(plain(line)).toBe('Amount: €10.00 → $1,250.50')
    expect(plain(changeLine('FAMILY_RECORD', { field: 'originalAmount', old: null, new: '9000.00 RUB' }, none)))
      .toBe('Amount paid: — → RUB 9,000.00')
    expect(changeLine('FAMILY_RECORD', { field: 'splitMethod', old: 'EQUAL', new: 'PERCENT' }, none))
      .toBe('Split: equal shares → percentages')
  })

  it('writes a change as field: old → new, and an unknown field as other detail with its value', () => {
    expect(changeLine('ACCOUNT', { field: 'name', old: 'Cash', new: 'Wallet' }, names)).toBe('Name: Cash → Wallet')
    expect(changeLine('ENTRY', { field: 'newField', old: null, new: 'x' }, names)).toBe('Other detail: — → x')
    expect(changeLine('ENTRY', { field: 'newField', old: null, new: [1, 2] }, names)).toBe('Other detail: — → Several details')
  })
})

describe('paging', () => {
  const version = (seq: number) => ({ seq }) as HistoryVersion
  it('adds a later page and keeps a version that moved between pages once', () => {
    expect(withMore([version(5), version(4)], [version(4), version(3)]).map((v) => v.seq)).toEqual([5, 4, 3])
  })
})
