import { useState } from 'react'
import { dateOfIso, errorMessage, formatDate } from './api'
import { Errors } from './components'
import { DATE_FORMATS, datePattern, formatInPattern, isDateFormat, type DateFormat } from './dates'
import { useMe } from './me'

/** "As the browser shows it": the select's value for no chosen format. */
const BROWSER = 'browser'

/**
 * How the app shows and takes day dates (D-304): as the browser shows them, the default, or one of four numeric
 * patterns; each option shows today in it. Saved for the user, on every device, as soon as it is chosen. Month names
 * ("Oct 2026") stay as they are, and the import and export files keep their own format.
 */
export default function DateFormatSettings() {
  const { me, today, setDateFormat } = useMe()
  const current = isDateFormat(me.dateFormat) ? me.dateFormat : null
  const [pending, setPending] = useState(false)
  const [saved, setSaved] = useState(false)
  const [failure, setFailure] = useState<string>()

  async function choose(value: string) {
    const format: DateFormat | null = isDateFormat(value) ? value : null
    setPending(true)
    setSaved(false)
    setFailure(undefined)
    try {
      await setDateFormat(format)
      setSaved(true)
    } catch (e) {
      setFailure(errorMessage(e))
    } finally {
      setPending(false)
    }
  }

  // The browser's own way, whatever is chosen now: what this option would show.
  const browser = `${datePattern(null).placeholder}, ${dateOfIso(today).toLocaleDateString(undefined, { dateStyle: 'medium' })}`
  return (
    <section className="card">
      <h3>Date format</h3>
      <label className="field">
        <span className="label">Show and type dates as</span>
        <select value={current ?? BROWSER} disabled={pending} onChange={(e) => void choose(e.target.value)}>
          <option value={BROWSER}>As the browser shows it ({browser})</option>
          {DATE_FORMATS.map((format) => (
            <option key={format} value={format}>{format} ({formatInPattern(today, datePattern(format))})</option>
          ))}
        </select>
        <small className="hint">Months keep their names, such as “Oct 2026”. Import and export files keep their own format.</small>
      </label>
      {saved && <p className="success" role="status">Saved. Today shows as {formatDate(today)}.</p>}
      <Errors messages={[failure]} />
    </section>
  )
}
