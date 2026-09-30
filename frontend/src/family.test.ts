import { describe, expect, it } from 'vitest'
import { violationsByMember } from './family'

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
