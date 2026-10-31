import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { formatDate, formatInstant, type Me } from './api'
import DateInput from './DateInput'
import DateFormatSettings from './DateFormatSettings'
import { DATE_FORMATS, datePattern, parseInPattern, setDateFormat } from './dates'
import { picker, TEST_ME, WithMe } from './testMe'

// D-304: day dates shown and typed in the user's pattern; the values stay ISO dates.

afterEach(() => {
  cleanup()
  setDateFormat(null)
  vi.unstubAllGlobals()
})

describe('formatDate and formatInstant', () => {
  it('show a day in every format, and in the browser’s own way without one', () => {
    expect(formatDate('2026-10-31')).toBe('Oct 31, 2026')
    const shown = DATE_FORMATS.map((format) => { setDateFormat(format); return formatDate('2026-10-31') })
    expect(shown).toEqual(['31.10.2026', '31/10/2026', '10/31/2026', '2026-10-31'])
  })

  it('keep month labels and a long date’s weekday whatever is chosen', () => {
    setDateFormat('DD.MM.YYYY')
    expect(formatDate('2026-10-01', { month: 'short', year: 'numeric' })).toBe('Oct 2026')
    expect(formatDate('2026-10-31', { dateStyle: 'full' })).toBe('Saturday, 31.10.2026')
    expect(formatInstant('2026-09-30T08:00:00Z')).toBe('30.09.2026, 8:00 AM')
    setDateFormat(null)
    expect(formatInstant('2026-09-30T08:00:00Z')).toBe('Sep 30, 2026, 8:00 AM')
  })

  it('take an unknown format from the api as the browser’s', () => {
    setDateFormat('dd/mm/yy')
    expect(formatDate('2026-10-31')).toBe('Oct 31, 2026')
  })
})

describe('the patterns', () => {
  it('are the chosen one, or the browser locale’s numeric order', () => {
    expect(datePattern('YYYY-MM-DD').placeholder).toBe('YYYY-MM-DD')
    expect(datePattern(null, 'en-US').placeholder).toBe('MM/DD/YYYY')
    expect(datePattern(null, 'de-DE').placeholder).toBe('DD.MM.YYYY')
    expect(datePattern(null, 'en-GB').placeholder).toBe('DD/MM/YYYY')
    expect(datePattern(null, 'sv-SE').placeholder).toBe('YYYY-MM-DD')
  })

  it('take one or two digits for days and months, and any of . / - between them', () => {
    const p = datePattern('DD.MM.YYYY')
    for (const text of ['1.2.2026', '01.02.2026', '1/2/2026', '01-02-2026', ' 1.02.2026 ']) {
      expect(parseInPattern(text, p)).toBe('2026-02-01')
    }
    expect(parseInPattern('2/1/2026', datePattern('MM/DD/YYYY'))).toBe('2026-02-01')
    expect(parseInPattern('2026-2-1', datePattern('YYYY-MM-DD'))).toBe('2026-02-01')
  })

  it('refuse what isn’t a real day', () => {
    const p = datePattern('DD.MM.YYYY')
    for (const text of ['31.02.2026', '29.02.2027', '1.13.2026', '0.1.2026', '1.1.26', '1.1', 'Oct 31', '123.1.2026', '']) {
      expect(parseInPattern(text, p)).toBeNull()
    }
    expect(parseInPattern('29.02.2028', p)).toBe('2028-02-29')
  })
})

/** A DateInput whose value is kept, and every value it reported. */
function Harness({ initial = '2026-10-31', min, max, seen }: { initial?: string; min?: string; max?: string; seen: string[] }) {
  const [value, setValue] = useState(initial)
  return (
    <form aria-label="form">
      <label>Date <DateInput value={value} min={min} max={max} onChange={(day) => { seen.push(day); setValue(day) }} /></label>
      <output data-testid="value">{value}</output>
    </form>
  )
}

describe('DateInput', () => {
  it('shows the value in each format with its pattern as the placeholder, and gives back an ISO date', () => {
    for (const format of DATE_FORMATS) {
      setDateFormat(format)
      const seen: string[] = []
      render(<Harness seen={seen} />)
      const field = screen.getByLabelText('Date') as HTMLInputElement
      expect(field.placeholder).toBe(format)
      expect(field.value).toBe(formatDate('2026-10-31'))
      const typed = { 'DD.MM.YYYY': '5.1.2027', 'DD/MM/YYYY': '05/01/2027', 'MM/DD/YYYY': '1/5/2027', 'YYYY-MM-DD': '2027-1-5' }[format]
      fireEvent.change(field, { target: { value: typed } })
      expect(seen).toEqual(['2027-01-05'])
      expect(screen.getByTestId('value').textContent).toBe('2027-01-05')
      cleanup()
    }
  })

  it('types in the browser locale’s order by default, and takes a whole ISO date in any format', () => {
    const seen: string[] = []
    render(<Harness seen={seen} />)
    const field = screen.getByLabelText('Date') as HTMLInputElement
    expect(field.placeholder).toBe('MM/DD/YYYY')
    expect(field.value).toBe('10/31/2026')
    fireEvent.change(field, { target: { value: '3/4/2026' } })
    fireEvent.change(field, { target: { value: '2026-12-24' } })
    expect(seen).toEqual(['2026-03-04', '2026-12-24'])
    fireEvent.blur(field)
    expect(field.value).toBe('12/24/2026')
  })

  it('says why text that isn’t a date changes nothing, and holds the form back', () => {
    setDateFormat('DD.MM.YYYY')
    const seen: string[] = []
    render(<Harness seen={seen} />)
    const field = screen.getByLabelText(/^Date/) as HTMLInputElement
    fireEvent.change(field, { target: { value: '31.02.2026' } })
    expect(seen).toEqual([])
    expect(screen.getByTestId('value').textContent).toBe('2026-10-31')
    expect(screen.getByRole('alert').textContent).toBe('Type a date as DD.MM.YYYY.')
    expect(field.getAttribute('aria-invalid')).toBe('true')
    expect(field.validity.valid).toBe(false)
    fireEvent.change(field, { target: { value: '28.02.2026' } })
    expect(seen).toEqual(['2026-02-28'])
    expect(screen.queryByRole('alert')).toBeNull()
    expect(field.validity.valid).toBe(true)
  })

  it('keeps min and max: on the picker, and as the field’s own check', () => {
    setDateFormat('DD.MM.YYYY')
    const seen: string[] = []
    render(<Harness seen={seen} min="2026-09-01" max="2026-10-31" />)
    const field = screen.getByLabelText(/^Date/) as HTMLInputElement
    expect(picker(field).min).toBe('2026-09-01')
    expect(picker(field).max).toBe('2026-10-31')
    fireEvent.change(field, { target: { value: '31.08.2026' } })
    expect(screen.getByRole('alert').textContent).toBe('The date can’t be before 01.09.2026.')
    expect(field.validity.valid).toBe(false)
    fireEvent.change(field, { target: { value: '1.11.2026' } })
    expect(screen.getByRole('alert').textContent).toBe('The date can’t be after 31.10.2026.')
  })

  it('opens the browser’s picker from its button, falling back to focusing it, and takes the date picked', () => {
    const seen: string[] = []
    render(<Harness seen={seen} />)
    const field = screen.getByLabelText('Date') as HTMLInputElement
    const native = picker(field)
    const showPicker = vi.fn()
    Object.defineProperty(native, 'showPicker', { value: showPicker, configurable: true })
    fireEvent.click(screen.getByRole('button', { name: 'Choose a date' }))
    expect(showPicker).toHaveBeenCalledOnce()
    Object.defineProperty(native, 'showPicker', { value: () => { throw new DOMException('no', 'NotAllowedError') }, configurable: true })
    fireEvent.click(screen.getByRole('button', { name: 'Choose a date' }))
    expect(document.activeElement).toBe(native)
    expect(native.tabIndex).toBe(-1)
    expect(native.getAttribute('aria-hidden')).toBe('true')
    fireEvent.change(native, { target: { value: '2026-11-05' } })
    expect(seen).toEqual(['2026-11-05'])
    expect(field.value).toBe('11/05/2026')
  })

  it('shows a new value from outside, and clears to an empty value', () => {
    const seen: string[] = []
    function Outside() {
      const [value, setValue] = useState('2026-10-31')
      return (
        <>
          <label>Date <DateInput value={value} onChange={(day) => { seen.push(day); setValue(day) }} /></label>
          <button type="button" onClick={() => setValue('2026-01-02')}>Reset</button>
        </>
      )
    }
    render(<Outside />)
    const field = screen.getByLabelText('Date') as HTMLInputElement
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }))
    expect(field.value).toBe('01/02/2026')
    fireEvent.change(field, { target: { value: '' } })
    expect(seen).toEqual([''])
  })
})

describe('the date format in Settings', () => {
  it('lists the browser’s way and the four patterns, each with today, and saves the one chosen', async () => {
    const requests: { url: string; body: unknown }[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      requests.push({ url, body: init?.body ? JSON.parse(init.body as string) : undefined })
      return new Response(JSON.stringify({ dateFormat: 'DD.MM.YYYY' }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const me: Me = { ...TEST_ME, today: '2026-10-31' }
    render(<MemoryRouter><WithMe me={me}><DateFormatSettings /></WithMe></MemoryRouter>)
    const select = screen.getByLabelText(/^Show and type dates as/) as HTMLSelectElement
    expect([...select.options].map((o) => o.textContent)).toEqual(['As the browser shows it (MM/DD/YYYY, Oct 31, 2026)',
      'DD.MM.YYYY (31.10.2026)', 'DD/MM/YYYY (31/10/2026)', 'MM/DD/YYYY (10/31/2026)', 'YYYY-MM-DD (2026-10-31)'])
    expect(select.value).toBe('browser')
    fireEvent.change(select, { target: { value: 'DD.MM.YYYY' } })
    expect(await screen.findByRole('status')).toHaveProperty('textContent', 'Saved. Today shows as 31.10.2026.')
    expect(requests).toEqual([{ url: '/api/settings/date-format', body: { dateFormat: 'DD.MM.YYYY' } }])
    await waitFor(() => expect(select.value).toBe('DD.MM.YYYY'))
  })
})
