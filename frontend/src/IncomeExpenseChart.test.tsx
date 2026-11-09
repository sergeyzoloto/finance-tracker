import { cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { axisLines, measureText, RangeTick } from './IncomeExpenseChart'

// D-414: the chart by range's axis labels wrap to their own band, so that at 375 px no two touch.

afterEach(cleanup)

/** 6 px a character, as a 12 px label roughly is. */
const measure = (text: string) => text.length * 6
const thin = ' '
const narrow = ' '

describe('the axis labels of the chart by range (D-414)', () => {
  it('stay on two lines where they fit', () => {
    expect(axisLines({ role: 'Previous period', label: 'Sep 2026' }, 200, measure)).toEqual(['Previous period', 'Sep 2026'])
    expect(axisLines({ role: 'Year earlier', label: 'Oct 2025' }, Infinity, measure)).toEqual(['Year earlier', 'Oct 2025'])
  })

  it('wrap the role and the name word by word, each on lines of its own', () => {
    // A band of 70 px at 375 px: "Previous period" is 90 px.
    expect(axisLines({ role: 'Previous period', label: 'Sep 2026' }, 70, measure)).toEqual(['Previous', 'period', 'Sep 2026'])
    expect(axisLines({ role: 'Year earlier', label: `Oct 26${thin}–${thin}Nov 1, 2026` }, 70, measure))
      .toEqual(['Year', 'earlier', `Oct 26${thin}–`, 'Nov 1, 2026'])
  })

  it('break a range’s name after its dash first', () => {
    expect(axisLines({ role: 'Period', label: `Oct 10${thin}–${thin}Nov 9, 2026` }, 78, measure))
      .toEqual(['Period', `Oct 10${thin}–`, 'Nov 9, 2026'])
    expect(axisLines({ role: 'Period', label: `Nov 3${thin}–${thin}9, 2025` }, 78, measure))
      .toEqual(['Period', `Nov 3${thin}–`, '9, 2025'])
    expect(axisLines({ role: 'Period', label: `Nov 3${thin}–${thin}9, 2025` }, 120, measure))
      .toEqual(['Period', `Nov 3${thin}–${thin}9, 2025`])
  })

  it('break at a thin space but not at a no-break one, and keep a word wider than the band whole', () => {
    expect(axisLines({ role: 'Period', label: `Nov 2${thin}–${thin}8, 2026` }, 48, measure))
      .toEqual(['Period', `Nov 2${thin}–`, '8, 2026'])
    expect(axisLines({ role: 'To date', label: `9${narrow}Nov` }, 10, measure)).toEqual(['To', 'date', `9${narrow}Nov`])
  })

  it('estimate a line’s width without a canvas, as in tests', () => {
    expect(measureText('Previous period')).toBeCloseTo(15 * 12 * 0.6)
  })

  it('are drawn as one line each under their bars', () => {
    const lines = new Map([['previous', ['Previous', 'period', 'Sep 2026']]])
    const { container } = render(
      <svg><RangeTick x={100} y={10} payload={{ value: 'previous' }} lines={lines} /></svg>)
    const spans = [...container.querySelectorAll('tspan')]
    expect(spans.map((s) => s.textContent)).toEqual(['Previous', 'period', 'Sep 2026'])
    expect(spans.map((s) => s.getAttribute('x'))).toEqual(['100', '100', '100'])
    expect(spans.map((s) => s.getAttribute('dy'))).toEqual(['0.9em', '15px', '15px'])
    expect(container.querySelector('text')!.getAttribute('text-anchor')).toBe('middle')
  })
})
