import { useMemo, useState } from 'react'
import { errorMessage, formatDate } from './api'
import { Errors } from './components'
import { useMe } from './me'
import { allZones, browserZone, dismissHint, dismissedHint, hintKey, zoneMatches } from './timeZone'

/**
 * One line when the browser's time zone isn't the one saved (D-101): "Use <browser zone>", or dismiss it. The dismissal
 * is kept in this browser, for this pair of zones: a different browser zone, or a different saved one, asks again.
 */
export function ZoneHint() {
  const { me, setZone } = useMe()
  const browser = browserZone()
  const [dismissed, setDismissed] = useState(dismissedHint)
  const [failure, setFailure] = useState<string>()
  const saved = me.timeZone
  if (!saved || !browser || browser === saved || dismissed === hintKey(browser, saved)) return null
  return (
    <div className="notice zone-hint" role="note">
      <p>
        Your browser is in {browser}, and dates here follow {saved}.{' '}
        <button type="button" onClick={() => { setFailure(undefined); setZone(browser).catch((e) => setFailure(errorMessage(e))) }}>
          Use {browser}
        </button>{' '}
        <button type="button" onClick={() => { dismissHint(browser, saved); setDismissed(hintKey(browser, saved)) }}>
          Dismiss
        </button>
      </p>
      <Errors messages={[failure]} />
    </div>
  )
}

/** The user's time zone on the Settings page: where it stands, and a searchable list to choose another from. */
export function TimeZoneSettings() {
  const { me, today, setZone } = useMe()
  const zones = useMemo(allZones, [])
  const [typed, setTyped] = useState('')
  const [chosen, setChosen] = useState('')
  const [pending, setPending] = useState(false)
  const [saved, setSaved] = useState(false)
  const [failure, setFailure] = useState<string>()
  const matches = zoneMatches(zones, typed)

  async function save() {
    setPending(true)
    setSaved(false)
    setFailure(undefined)
    try {
      await setZone(chosen)
      setSaved(true)
    } catch (e) {
      setFailure(errorMessage(e))
    } finally {
      setPending(false)
    }
  }

  return (
    <section className="card">
      <h3>Time zone</h3>
      <p>
        {me.timeZone
          ? <>Your time zone is <strong>{me.timeZone}</strong>.</>
          : <>No time zone is saved yet, so dates follow UTC.</>}{' '}
        Today there is <strong>{formatDate(today, { dateStyle: 'full' })}</strong>. Every date the app fills in or checks against
        today is the date in this zone.
      </p>
      <label className="field">
        <span className="label">Find a time zone</span>
        <input type="search" value={typed} autoComplete="off" spellCheck={false} placeholder="Amsterdam, new york, Asia…"
          onChange={(e) => { setTyped(e.target.value); setSaved(false) }} />
      </label>
      <label className="field">
        <span className="label">Time zones</span>
        <select size={6} value={chosen} onChange={(e) => { setChosen(e.target.value); setSaved(false) }}>
          {matches.map((zone) => <option key={zone} value={zone}>{zone}</option>)}
        </select>
        {matches.length === 0 && <small className="hint">No time zone matches “{typed}”.</small>}
      </label>
      <button type="button" className="primary" disabled={!chosen || chosen === me.timeZone || pending} onClick={() => void save()}>
        {pending ? 'Saving…' : 'Save time zone'}
      </button>
      {saved && <p className="success" role="status">Saved. Today is now {formatDate(today)}.</p>}
      <Errors messages={[failure]} />
    </section>
  )
}
