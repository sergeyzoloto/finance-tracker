import { describe, expect, it } from 'vitest'
import { familyMessage, violationsByMember } from './family'

describe('familyMessage', () => {
  it('says family budget and shows percentages', () => {
    expect(familyMessage('The family ledger has a member named Kid already.'))
      .toBe('The family budget has a member named Kid already.')
    expect(familyMessage('Only an owner of the family ledger can do this: owners manage its settings'))
      .toBe('Only an owner of the family budget can do this: owners manage its settings')
    expect(familyMessage('Kid has a share of 3333 basis points in the custom split rule; change the rule to give them 0 first.'))
      .toBe('Kid has a share of 33.33 % in the custom split rule; change the rule to give them 0 first.')
    expect(familyMessage('the shares sum to 9000 basis points, not 10000')).toBe('the shares sum to 90.00 %, not 100.00 %')
    expect(familyMessage('Category 5 not found.')).toBe('Category 5 not found.')
  })
})

describe('violationsByMember', () => {
  it('sorts the violations of a 422 by the member they name', () => {
    const { byMember, other } = violationsByMember([
      'Kid (member 71) has no share',
      'member 99 is not an active member of the family ledger',
      'the shares sum to 6000 basis points, not 10000',
    ])
    expect([...byMember]).toEqual([
      [71, ['Kid has no share']],
      [99, ['member 99 is not an active member of the family budget']],
    ])
    expect(other).toEqual(['the shares sum to 60.00 %, not 100.00 %'])
  })
})
