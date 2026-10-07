import { useEffect, useMemo, useState } from 'react'
import { errorMessage, formatDate } from './api'
import { Errors } from './components'
import { useMe } from './me'
import { allZones, browserZone, dismissHint, dismissedHint, hintKey, zoneMatches } from './timeZone'

/**
 * The zone ids the api accepts (D-103): undefined while it is being asked, null if it couldn't say. Nothing is offered
 * on a guess, so a zone is shown only once it is known to be accepted.
 */
function useAcceptedZones(enabled = true): Set<string> | null | undefined {
  const { acceptedZones } = useMe()
  const [zones, setZones] = useState<Set<string> | null>()
  useEffect(() => {
    if (!enabled) return
    let current = true
    void acceptedZones().then((answer) => { if (current) setZones(answer) })
    return () => { current = false }
  }, [acceptedZones, enabled])
  return zones
}

/**
 * One line when the browser's time zone isn't the one saved (D-101): "Use <browser zone>", or dismiss it. The dismissal
 * is kept in this browser, for this pair of zones: a different browser zone, or a different saved one, asks again. The
 * browser's zone is offered only if the api would accept it (D-103): a name that this browser knows and the api doesn't
 * shows nothing, and so does an api that can't say.
 */
export function ZoneHint() {
  const { me } = useMe()
  const browser = browserZone()
  const [dismissed, setDismissed] = useState(dismissedHint)
  const saved = me.timeZone
  if (!saved || !browser || browser === saved || dismissed === hintKey(browser, saved)) return null
  return <OfferedZone browser={browser} saved={saved} onDismiss={() => { dismissHint(browser, saved); setDismissed(hintKey(browser, saved)) }} />
}

function OfferedZone({ browser, saved, onDismiss }: { browser: string; saved: string; onDismiss: () => void }) {
  const { setZone } = useMe()
  const accepted = useAcceptedZones()
  const [failure, setFailure] = useState<string>()
  if (!accepted?.has(browser)) return null
  return (
    <div className="notice zone-hint" role="note">
      <p>
        Your browser is in {browser}, and dates here follow {saved}.{' '}
        <button type="button" onClick={() => { setFailure(undefined); setZone(browser).catch((e) => setFailure(errorMessage(e))) }}>
          Use {browser}
        </button>{' '}
        <button type="button" onClick={onDismiss}>
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
  // Asked when the picker is first used, so that the page itself asks for nothing.
  const [touched, setTouched] = useState(false)
  const accepted = useAcceptedZones(touched)
  // The browser's names, those the api accepts once it has said which (D-103); until then all of them, and a refusal
  // is shown as it comes.
  const zones = useMemo(() => allZones().filter((zone) => !accepted || accepted.has(zone)), [accepted])
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
          onFocus={() => setTouched(true)} onChange={(e) => { setTyped(e.target.value); setSaved(false) }} />
      </label>
      <label className="field">
        <span className="label">Time zones</span>
        <select size={6} value={chosen} onFocus={() => setTouched(true)} onChange={(e) => { setChosen(e.target.value); setSaved(false) }}>
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
