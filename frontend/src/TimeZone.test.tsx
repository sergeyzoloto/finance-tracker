import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Me } from './api'
import { dismissedHint, msUntilMidnight, zoneMatches } from './timeZone'

// The user's time zone (D-100, D-101): the browser's zone saved on the first load, the Settings choice, the hint when
// the browser's zone isn't the saved one, and today's date, which comes from /api/me and never from the browser's clock.

type Answer = { status: number; body?: unknown }
type Call = { method: string; url: string; body: unknown }

/** Answers each request by "METHOD /api/path", or with 404; a list answers one after the other. Records every request. */
function stubApi(answers: Record<string, Answer | Answer[]>) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ method, url, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    const listed = answers[`${method} ${url}`]
    const answer = (Array.isArray(listed) ? (listed.length > 1 ? listed.shift() : listed[0]) : listed)
      ?? { status: 404, body: { status: 404, detail: `No static resource ${url.slice(1)}.` } }
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
      status: answer.status,
      headers: { 'Content-Type': answer.status >= 400 ? 'application/problem+json' : 'application/json' },
    })
  }))
  return calls
}

/** The ids the api accepts (D-103), as GET /api/settings/time-zones lists them. */
const ZONES: Answer = { status: 200, body: ['America/Los_Angeles', 'Asia/Tokyo', 'Europe/Amsterdam', 'UTC'] }
const gets = (calls: Call[], url: string) => calls.filter((c) => c.method === 'GET' && c.url === url)
const puts = (calls: Call[]) => calls.filter((c) => c.method === 'PUT' && c.url === '/api/settings/time-zone')
const OFF = { familyLedgers: false }
const me = (overrides: Partial<Me> = {}): Me => ({ name: 'Anna', timeZone: 'Europe/Amsterdam', today: '2026-10-07', features: OFF, ...overrides })

function renderApp(user: Me, path = '/settings') {
  render(<MemoryRouter initialEntries={[path]}><App me={user} /></MemoryRouter>)
}

const browserZone = process.env.TZ
function browserIn(zone: string) {
  process.env.TZ = zone
}

beforeEach(() => {
  try { localStorage.clear() } catch { /* not in this environment */ }
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  process.env.TZ = browserZone
})

describe('the first load', () => {
  it('saves the browser’s zone once when none is saved, and takes today from the answer', async () => {
    browserIn('America/Los_Angeles')
    const calls = stubApi({
      'PUT /api/settings/time-zone': { status: 200, body: { timeZone: 'America/Los_Angeles', today: '2026-10-06' } },
    })
    // At 03:00 UTC on 7 October /api/me says today is the 7th (UTC's, no zone saved yet); in Los Angeles it is the 6th.
    renderApp(me({ timeZone: null }))

    await waitFor(() => expect(puts(calls)).toHaveLength(1))
    expect(puts(calls)[0].body).toEqual({ timeZone: 'America/Los_Angeles' })
    expect(await screen.findByText('Tuesday, October 6, 2026')).toBeDefined()
    expect(screen.getByText('America/Los_Angeles', { selector: 'strong' })).toBeDefined()
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(puts(calls)).toHaveLength(1)
  })

  it('saves nothing when a zone is saved, and nothing again when the api refuses the browser’s', async () => {
    browserIn('America/Los_Angeles')
    const saved = stubApi({})
    renderApp(me())
    await screen.findByRole('heading', { name: 'Settings' })
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(puts(saved)).toEqual([])
    cleanup()

    const refused = stubApi({
      'PUT /api/settings/time-zone': { status: 422, body: { status: 422, detail: 'No.', violations: ['No'] } },
    })
    renderApp(me({ timeZone: null }))
    await waitFor(() => expect(puts(refused)).toHaveLength(1))
    // The page stays, on UTC's date, and doesn't ask again in a loop.
    expect(await screen.findByText(/No time zone is saved yet, so dates follow UTC/)).toBeDefined()
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(puts(refused)).toHaveLength(1)
  })

  it('is the api’s today, never the browser’s clock, in the forms', async () => {
    browserIn('America/Los_Angeles')
    vi.useFakeTimers({ toFake: ['Date'] })
    // The browser is on 6 October; /api/me says the 7th.
    vi.setSystemTime(new Date('2026-10-07T03:00:00Z'))
    stubApi({
      'GET /api/settings': { status: 200, body: { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' } },
      'GET /api/accounts': { status: 200, body: [] },
      'GET /api/categories': { status: 200, body: [] },
    })
    const user = me({ today: '2026-10-07', timeZone: 'America/Los_Angeles', features: { familyLedgers: true } })
    render(<MemoryRouter initialEntries={['/family/new']}><App me={user} /></MemoryRouter>)

    const start = await screen.findByLabelText(/^Start/)
    expect((start as HTMLInputElement).value).toBe('2026-10-07')
    expect(start.getAttribute('max')).toBe('2026-10-07')
  })

  it('leaves the start date out when it is the api’s today, and sends an earlier one', async () => {
    const reference = {
      'GET /api/settings': { status: 200, body: { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' } },
      'GET /api/accounts': { status: 200, body: [] },
      'GET /api/categories': { status: 200, body: [] },
      'GET /api/family-ledgers': { status: 200, body: [] },
      'POST /api/family-ledgers': [
        { status: 201, body: { id: 7, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 70, createdAt: '', startDate: '2026-10-06' } },
        { status: 201, body: { id: 8, name: 'Home', baseCurrency: 'EUR', splitRule: 'EQUAL', role: 'OWNER', memberId: 80, createdAt: '', startDate: '2026-10-01' } },
      ],
    }
    // Saved from the browser, in a Los Angeles evening: the api's today is the 6th, and a budget made now starts then.
    const user = me({ today: '2026-10-06', timeZone: 'America/Los_Angeles', features: { familyLedgers: true } })
    browserIn('America/Los_Angeles')
    for (const [start, sent] of [[undefined, {}], ['2026-10-01', { startDate: '2026-10-01' }]] as const) {
      const calls = stubApi(reference)
      render(<MemoryRouter initialEntries={['/family/new']}><App me={user} /></MemoryRouter>)
      fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Home' } })
      if (start) fireEvent.change(screen.getByLabelText(/^Start/), { target: { value: start } })
      fireEvent.click(screen.getByRole('button', { name: 'Create family budget' }))
      await waitFor(() => expect(calls.find((c) => c.method === 'POST')).toBeDefined())
      expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
        name: 'Home', baseCurrency: 'EUR', displayName: 'Anna', categoryIds: [], ...sent })
      cleanup()
      reference['POST /api/family-ledgers'].shift()
    }
  })

  it('saves UTC when the browser reports UTC, as Firefox with resistFingerprinting and Tor do (D-103)', async () => {
    browserIn('UTC')
    const calls = stubApi({
      'PUT /api/settings/time-zone': { status: 200, body: { timeZone: 'UTC', today: '2026-10-07' } },
    })
    renderApp(me({ timeZone: null }))
    await waitFor(() => expect(puts(calls)).toHaveLength(1))
    expect(puts(calls)[0].body).toEqual({ timeZone: 'UTC' })
    expect(await screen.findByText('UTC', { selector: 'strong' })).toBeDefined()
  })

  it('keeps working, with no error and no second try, whatever way saving the browser’s zone fails (D-103)', async () => {
    browserIn('America/Los_Angeles')
    for (const failure of ['422', '500', 'network'] as const) {
      const calls: Call[] = []
      vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
        calls.push({ method: init?.method ?? 'GET', url, body: undefined })
        if (url === '/api/settings/time-zone') {
          if (failure === 'network') throw new TypeError('Failed to fetch')
          return new Response(JSON.stringify({ status: Number(failure), detail: 'No.' }), {
            status: Number(failure), headers: { 'Content-Type': 'application/problem+json' } })
        }
        return new Response(JSON.stringify({ status: 404 }), { status: 404, headers: { 'Content-Type': 'application/problem+json' } })
      }))
      renderApp(me({ timeZone: null }))
      await waitFor(() => expect(puts(calls)).toHaveLength(1))
      expect(await screen.findByText(/No time zone is saved yet, so dates follow UTC/), failure).toBeDefined()
      // The tab coming back into view, and some waiting: still one try, and no alert on the page.
      await act(async () => { document.dispatchEvent(new Event('visibilitychange')) })
      await new Promise((resolve) => setTimeout(resolve, 50))
      expect(puts(calls), failure).toHaveLength(1)
      expect(screen.queryByRole('alert'), failure).toBeNull()
      cleanup()
    }
  })
})

describe('Settings', () => {
  it('searches the list, saves the chosen zone and shows today there', async () => {
    const calls = stubApi({
      'PUT /api/settings/time-zone': { status: 200, body: { timeZone: 'America/New_York', today: '2026-10-06' } },
    })
    renderApp(me())
    expect(await screen.findByText('Europe/Amsterdam', { selector: 'strong' })).toBeDefined()
    expect(screen.getByText('Wednesday, October 7, 2026')).toBeDefined()
    const save = screen.getByRole('button', { name: 'Save time zone' })
    expect(save).toHaveProperty('disabled', true)

    fireEvent.change(screen.getByLabelText('Find a time zone'), { target: { value: 'new york' } })
    const list = screen.getByLabelText('Time zones')
    expect(within(list).getAllByRole('option').map((o) => o.textContent)).toEqual(['America/New_York'])
    fireEvent.change(list, { target: { value: 'America/New_York' } })
    expect(save).toHaveProperty('disabled', false)
    fireEvent.click(save)

    await waitFor(() => expect(puts(calls)).toHaveLength(1))
    expect(puts(calls)[0].body).toEqual({ timeZone: 'America/New_York' })
    expect(await screen.findByText(/Saved\. Today is now/)).toBeDefined()
    expect(screen.getByText('Tuesday, October 6, 2026')).toBeDefined()
    expect(screen.getByText('America/New_York', { selector: 'strong' })).toBeDefined()
  })

  it('says what the api refused', async () => {
    stubApi({
      'PUT /api/settings/time-zone': { status: 422, body: { status: 422, detail: '\'Mars/Olympus_Mons\' is not a time zone.', violations: ['x'] } },
    })
    renderApp(me())
    fireEvent.change(await screen.findByLabelText('Find a time zone'), { target: { value: 'Amsterdam' } })
    fireEvent.change(screen.getByLabelText('Time zones'), { target: { value: 'Europe/Amsterdam' } })
    // The saved zone itself is no change to save.
    expect(screen.getByRole('button', { name: 'Save time zone' })).toHaveProperty('disabled', true)
    fireEvent.change(screen.getByLabelText('Find a time zone'), { target: { value: 'Asia/Tok' } })
    fireEvent.change(screen.getByLabelText('Time zones'), { target: { value: 'Asia/Tokyo' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save time zone' }))
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', '\'Mars/Olympus_Mons\' is not a time zone.')
  })

  it('finds a zone by any words of its name, in any case, and says when none matches', async () => {
    const zones = ['America/New_York', 'Europe/Amsterdam', 'Asia/Kolkata', 'UTC']
    expect(zoneMatches(zones, 'NEW york')).toEqual(['America/New_York'])
    expect(zoneMatches(zones, 'europe/ams')).toEqual(['Europe/Amsterdam'])
    expect(zoneMatches(zones, 'a')).toEqual(zones.filter((z) => /a/i.test(z)))
    expect(zoneMatches(zones, '')).toEqual(zones)
    expect(zoneMatches(zones, 'zzz')).toEqual([])
    renderApp(me())
    fireEvent.change(await screen.findByLabelText('Find a time zone'), { target: { value: 'zzz' } })
    expect(screen.getByText('No time zone matches “zzz”.')).toBeDefined()
  })

  it('offers the browser’s zones, UTC included', async () => {
    renderApp(me())
    const list = await screen.findByLabelText('Time zones')
    fireEvent.change(screen.getByLabelText('Find a time zone'), { target: { value: 'utc' } })
    expect(within(list).getAllByRole('option').map((o) => o.textContent)).toContain('UTC')
  })

  it('asks for the zone again after “Delete all my data”, which takes it with the rest', async () => {
    browserIn('America/Los_Angeles')
    const calls = stubApi({
      'DELETE /api/me/data': { status: 204 },
      // After the deletion the user is new: no zone.
      'GET /api/me': { status: 200, body: { name: 'Anna', timeZone: null, today: '2026-10-07', features: OFF } },
      'PUT /api/settings/time-zone': { status: 200, body: { timeZone: 'America/Los_Angeles', today: '2026-10-06' } },
    })
    renderApp(me())
    fireEvent.change(await screen.findByLabelText(/to confirm/), { target: { value: 'DELETE' } })
    fireEvent.click(screen.getByRole('button', { name: 'Delete all my data' }))

    await waitFor(() => expect(puts(calls)).toHaveLength(1))
    expect(puts(calls)[0].body).toEqual({ timeZone: 'America/Los_Angeles' })
    const order = calls.map((c) => `${c.method} ${c.url}`)
    expect(order.indexOf('GET /api/me')).toBeGreaterThan(order.indexOf('DELETE /api/me/data'))
    expect(order.indexOf('PUT /api/settings/time-zone')).toBeGreaterThan(order.indexOf('GET /api/me'))
  })

  it('offers only the zones the api accepts once it has said which (D-103)', async () => {
    browserIn('Europe/Amsterdam')
    const calls = stubApi({
      'GET /api/settings/time-zones': { status: 200, body: ['Europe/Amsterdam', 'UTC'] },
    })
    renderApp(me())
    const search = await screen.findByLabelText('Find a time zone')
    const offered = () => within(screen.getByRole('listbox')).queryAllByRole('option').map((o) => o.textContent)
    // The page itself asks for nothing, and shows the browser's names until the api has said which it accepts.
    fireEvent.change(search, { target: { value: 'calcutta' } })
    expect(offered()).toEqual(['Asia/Calcutta'])
    expect(gets(calls, '/api/settings/time-zones')).toHaveLength(0)

    fireEvent.focus(search)
    await waitFor(() => expect(offered()).toEqual([]))
    expect(gets(calls, '/api/settings/time-zones')).toHaveLength(1)
    fireEvent.change(search, { target: { value: 'a' } })
    expect(offered()).toEqual(['Europe/Amsterdam'])
  })
})

describe('the hint', () => {
  it('offers the browser’s zone when it isn’t the saved one, and “Use” saves it', async () => {
    browserIn('America/Los_Angeles')
    const calls = stubApi({
      'GET /api/settings/time-zones': ZONES,
      'PUT /api/settings/time-zone': { status: 200, body: { timeZone: 'America/Los_Angeles', today: '2026-10-06' } },
    })
    renderApp(me())
    const hint = await screen.findByText(/Your browser is in America\/Los_Angeles, and dates here follow Europe\/Amsterdam/)
    expect(hint.closest('.zone-hint')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Use America/Los_Angeles' }))

    await waitFor(() => expect(puts(calls)).toHaveLength(1))
    expect(puts(calls)[0].body).toEqual({ timeZone: 'America/Los_Angeles' })
    await waitFor(() => expect(screen.queryByText(/Your browser is in/)).toBeNull())
  })

  it('shows nothing when the zones are the same, or none is saved yet', async () => {
    browserIn('Europe/Amsterdam')
    stubApi({})
    renderApp(me())
    await screen.findByRole('heading', { name: 'Settings' })
    expect(screen.queryByText(/Your browser is in/)).toBeNull()
  })

  it('is dismissed for this browser, and comes back when the zones change', async () => {
    browserIn('America/Los_Angeles')
    stubApi({ 'GET /api/settings/time-zones': ZONES })
    renderApp(me())
    fireEvent.click(await screen.findByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByText(/Your browser is in/)).toBeNull()
    expect(dismissedHint()).toBe('America/Los_Angeles>Europe/Amsterdam')

    // A new visit in the same browser: still dismissed.
    cleanup()
    renderApp(me())
    await screen.findByRole('heading', { name: 'Settings' })
    expect(screen.queryByText(/Your browser is in/)).toBeNull()

    // The browser has moved, or the saved zone has changed: the hint asks again.
    cleanup()
    browserIn('Asia/Tokyo')
    renderApp(me())
    expect(await screen.findByText(/Your browser is in Asia\/Tokyo/)).toBeDefined()
    cleanup()
    browserIn('America/Los_Angeles')
    renderApp(me({ timeZone: 'Asia/Tokyo' }))
    expect(await screen.findByText(/dates here follow Asia\/Tokyo/)).toBeDefined()
  })

  it('offers a zone only if the api would accept it (D-103)', async () => {
    // This browser knows a name that the api's list doesn't have; the api says it can't tell; or it has it.
    browserIn('Asia/Tokyo')
    const unknown = stubApi({ 'GET /api/settings/time-zones': { status: 200, body: ['Europe/Amsterdam', 'UTC'] } })
    renderApp(me())
    await screen.findByRole('heading', { name: 'Settings' })
    await waitFor(() => expect(gets(unknown, '/api/settings/time-zones')).toHaveLength(1))
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByText(/Your browser is in/)).toBeNull()
    cleanup()

    const cannotTell = stubApi({ 'GET /api/settings/time-zones': { status: 500, body: { status: 500 } } })
    renderApp(me())
    await screen.findByRole('heading', { name: 'Settings' })
    await waitFor(() => expect(gets(cannotTell, '/api/settings/time-zones')).toHaveLength(1))
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByText(/Your browser is in/)).toBeNull()
    expect(gets(cannotTell, '/api/settings/time-zones')).toHaveLength(1)
    cleanup()

    stubApi({ 'GET /api/settings/time-zones': ZONES })
    renderApp(me())
    expect(await screen.findByText(/Your browser is in Asia\/Tokyo/)).toBeDefined()
  })

  it('asks the api nothing when the browser’s zone is the saved one or the hint was dismissed', async () => {
    browserIn('Europe/Amsterdam')
    const same = stubApi({ 'GET /api/settings/time-zones': ZONES })
    renderApp(me())
    await screen.findByRole('heading', { name: 'Settings' })
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(gets(same, '/api/settings/time-zones')).toHaveLength(0)
  })
})

describe('a tab left open', () => {
  it('asks for today again when it comes back into view, and the forms turn over', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-07T20:00:00Z'))
    stubApi({
      'GET /api/me': { status: 200, body: { name: 'Anna', timeZone: 'Europe/Amsterdam', today: '2026-10-08', features: OFF } },
    })
    renderApp(me())
    expect(await screen.findByText('Wednesday, October 7, 2026')).toBeDefined()

    // Four hours later, in the morning of the 8th in Amsterdam, the tab is shown again.
    vi.setSystemTime(new Date('2026-10-08T00:30:00Z'))
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')) })
    expect(await screen.findByText('Thursday, October 8, 2026')).toBeDefined()
  })
})

describe('the date turning over (D-106)', () => {
  const at = (iso: string) => vi.setSystemTime(new Date(iso))
  const meAt = (today: string) => ({ status: 200, body: { name: 'Anna', timeZone: 'Europe/Amsterdam', today, features: OFF } })
  const hour = 3600_000
  // Only Date and the timers are faked: real I/O (a response's body) settles on setImmediate, which testing-library's
  // own waiting (findBy, waitFor) can't use while setTimeout is faked, so the tests wait with this.
  const settle = async () => { for (let i = 0; i < 5; i++) await new Promise((resolve) => setImmediate(resolve)) }
  const advance = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms); await settle() })
  const setVisibility = (state: 'visible' | 'hidden') => Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })
  afterEach(() => setVisibility('visible'))

  it('computes the time to the next midnight in the zone', () => {
    const t = (iso: string) => Date.parse(iso)
    expect(msUntilMidnight('Europe/Amsterdam', t('2026-10-07T20:00:00Z'))).toBe(2 * hour)
    expect(msUntilMidnight('UTC', t('2026-10-07T23:59:59.500Z'))).toBe(500)
    expect(msUntilMidnight('UTC', t('2026-10-07T00:00:00Z'))).toBe(24 * hour)
    expect(msUntilMidnight('Pacific/Kiritimati', t('2026-10-07T09:59:00Z'))).toBe(60_000)
    expect(msUntilMidnight('America/Los_Angeles', t('2026-10-07T03:00:00Z'))).toBe(4 * hour)
    // A name this browser doesn't know counts as UTC.
    expect(msUntilMidnight('Mars/Olympus_Mons', t('2026-10-07T22:00:00Z'))).toBe(2 * hour)
  })

  it('asks nothing while idle or hidden, and not when the window is only focused', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
    at('2026-10-07T12:00:00Z')
    const calls = stubApi({ 'GET /api/me': meAt('2026-10-07') })
    renderApp(me())
    await advance(0)
    // Ten hours pass, a long idle stretch with the tab in view or not; the next midnight in Amsterdam is at 22:00 UTC.
    await advance(5 * hour)
    setVisibility('hidden')
    at('2026-10-07T17:00:00Z')
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')) })
    await act(async () => { window.dispatchEvent(new Event('focus')) })
    await advance(4 * hour)
    expect(gets(calls, '/api/me')).toHaveLength(0)
  })

  it('asks once at the next midnight of the saved zone, then again at the one after', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
    at('2026-10-07T20:00:00Z')
    const calls = stubApi({ 'GET /api/me': [meAt('2026-10-08'), meAt('2026-10-09')] })
    renderApp(me())
    expect(screen.getByText('Wednesday, October 7, 2026')).toBeDefined()

    // 22:00 UTC is midnight in Amsterdam: nothing before it, the request just after, though the tab is hidden.
    setVisibility('hidden')
    await advance(2 * hour - 1000)
    expect(gets(calls, '/api/me')).toHaveLength(0)
    await advance(3000)
    expect(gets(calls, '/api/me')).toHaveLength(1)
    expect(screen.getByText('Thursday, October 8, 2026')).toBeDefined()

    // The timer was armed again after the answer: the next midnight, a day later.
    await advance(23 * hour)
    expect(gets(calls, '/api/me')).toHaveLength(1)
    await advance(hour + 1000)
    expect(gets(calls, '/api/me')).toHaveLength(2)
    expect(screen.getByText('Friday, October 9, 2026')).toBeDefined()
  })

  it('is armed again after an answer that failed, and for a zone saved later', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
    at('2026-10-07T20:00:00Z')
    const calls = stubApi({ 'GET /api/me': [{ status: 500, body: { status: 500 } }, meAt('2026-10-09')] })
    renderApp(me())
    await advance(2 * hour + 3000)
    expect(gets(calls, '/api/me')).toHaveLength(1)
    await advance(24 * hour)
    expect(gets(calls, '/api/me')).toHaveLength(2)
  })

  it('gets the new today when a visible tab is shown after midnight', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
    at('2026-10-07T20:00:00Z')
    stubApi({ 'GET /api/me': meAt('2026-10-08') })
    renderApp(me())
    setVisibility('hidden')
    // The laptop slept through midnight; its timer never ran. Opening the lid shows the tab again.
    at('2026-10-08T05:00:00Z')
    setVisibility('visible')
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); await settle() })
    expect(screen.getByText('Thursday, October 8, 2026')).toBeDefined()
  })
})
