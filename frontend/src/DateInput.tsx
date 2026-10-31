import { useEffect, useId, useRef, useState } from 'react'
import { formatDate } from './api'
import { datePattern, formatInPattern, parseInPattern, type DatePattern } from './dates'

/**
 * A day date typed in the user's own pattern (D-304): "DD.MM.YYYY" and the like, or for "As the browser shows it" the
 * browser locale's numeric order. Days and months of one or two digits are taken, between any of `.`, `/` and `-`, and
 * so is a whole ISO date ("2026-10-31") whatever the pattern (D-310). The calendar button opens the browser's own
 * picker, so phones keep theirs.
 *
 * The value is the ISO date, or '' for an empty field, as a native date input's. Text that isn't a real date, or is
 * outside `min` and `max`, shows why under the field and holds the form back (the field's validity); text that isn't
 * a date changes nothing.
 */
export default function DateInput({ value, onChange, min, max, required, disabled, name, id, 'aria-label': ariaLabel }: {
  value: string
  onChange: (iso: string) => void
  min?: string
  max?: string
  required?: boolean
  disabled?: boolean
  name?: string
  id?: string
  'aria-label'?: string
}) {
  const pattern = datePattern()
  const [text, setText] = useState(() => shown(value, pattern))
  const [seen, setSeen] = useState({ value, placeholder: pattern.placeholder })
  // A new value from outside (a reset, a preset) or a new pattern: show it, unless it is what the text says already.
  if (seen.value !== value || seen.placeholder !== pattern.placeholder) {
    setSeen({ value, placeholder: pattern.placeholder })
    if (seen.placeholder !== pattern.placeholder || read(text, pattern) !== value) setText(shown(value, pattern))
  }
  const problem = problemOf(text, pattern, min, max)

  const field = useRef<HTMLInputElement>(null)
  const native = useRef<HTMLInputElement>(null)
  useEffect(() => { field.current?.setCustomValidity(problem ?? '') }, [problem])
  const messageId = useId()

  function type(next: string) {
    setText(next)
    if (next.trim() === '') onChange('')
    else {
      const iso = read(next, pattern)
      if (iso !== null && iso !== value) onChange(iso)
    }
  }

  function openPicker() {
    const picker = native.current
    if (!picker) return
    try {
      if (typeof picker.showPicker === 'function') picker.showPicker()
      else picker.focus()
    } catch {
      picker.focus()
    }
  }

  return (
    <span className="date-input">
      <span className="date-input-row">
        <input ref={field} id={id} name={name} value={text} placeholder={pattern.placeholder} required={required}
          disabled={disabled} aria-label={ariaLabel} autoComplete="off" inputMode="text" size={10}
          aria-invalid={problem !== null || undefined} aria-describedby={problem ? messageId : undefined}
          onChange={(e) => type(e.target.value)}
          onBlur={() => { const iso = read(text, pattern); if (iso !== null) setText(shown(iso, pattern)) }} />
        <button type="button" className="date-picker-button" disabled={disabled} aria-label="Choose a date"
          title="Choose a date" onClick={openPicker}>
          <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false">
            <rect x="1.5" y="3" width="13" height="11.5" rx="1.5" fill="none" stroke="currentColor" />
            <path d="M1.5 6.5h13M5 1.5v3M11 1.5v3" stroke="currentColor" fill="none" />
          </svg>
        </button>
        <input ref={native} type="date" className="date-native" tabIndex={-1} aria-hidden="true" value={value}
          min={min} max={max} disabled={disabled} onChange={(e) => { setText(shown(e.target.value, pattern)); onChange(e.target.value) }} />
      </span>
      {problem && text.trim() !== '' && <small id={messageId} className="error" role="alert">{problem}</small>}
    </span>
  )
}

const ISO = /^\s*\d{4}-\d{1,2}-\d{1,2}\s*$/

/** The ISO date the text names: a whole ISO date as it is, otherwise in the pattern's order. */
function read(text: string, pattern: DatePattern): string | null {
  if (ISO.test(text)) return parseInPattern(text, datePattern('YYYY-MM-DD'))
  return parseInPattern(text, pattern)
}

const shown = (iso: string, pattern: DatePattern) => (iso ? formatInPattern(iso, pattern) : '')

/** Why the text can't be the field's value, or null. */
function problemOf(text: string, pattern: DatePattern, min?: string, max?: string): string | null {
  if (text.trim() === '') return null
  const iso = read(text, pattern)
  if (iso === null) return `Type a date as ${pattern.placeholder}.`
  if (min && iso < min) return `The date can’t be before ${formatDate(min)}.`
  if (max && iso > max) return `The date can’t be after ${formatDate(max)}.`
  return null
}
