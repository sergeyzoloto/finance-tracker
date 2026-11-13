import { startTransition, useCallback } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router'
import Accounts from './Accounts'
import { familyLedgersOn, useApi, type FamilyLedger, type Me } from './api'
import Categories from './Categories'
import { Safe } from './components'
import Dashboard from './Dashboard'
import Entries from './Entries'
import EntryEditor from './EntryEditor'
import Family from './Family'
import InvitePage from './Invite'
import Header from './Header'
import { MeProvider, useMe } from './me'
import { PRIVACY_URL, SOURCE_URL } from './links'
import NewFamily from './NewFamily'
import Rates from './Rates'
import Settings from './Settings'
import { ZoneHint } from './TimeZone'

/**
 * The signed-in app. `me` is what `/api/me` said at the start; `MeProvider` keeps it, and today's date with it, which
 * every screen takes from the api and never from the browser's clock (D-101).
 */
export default function App({ me }: { me: Me }) {
  return (
    <MeProvider initial={me}>
      <Shell />
    </MeProvider>
  )
}

function Shell() {
  const { me, reload: reloadMe } = useMe()
  const location = useLocation()
  // The family budget (D-25) exists on these screens only while the backend has it switched on: no switcher, no
  // family routes, and no request for family budgets otherwise.
  const familyOn = familyLedgersOn(me)
  const families = useApi<FamilyLedger[]>(familyOn ? '/family-ledgers' : null)
  // The switcher's list right after joining or leaving, before it has loaded again: no placeholder for a budget just
  // joined, and none left behind for one just left (F6b).
  const { update, reload } = families
  const joined = useCallback((ledger: FamilyLedger) => {
    update((list) => list && !list.some((f) => f.id === ledger.id)
      ? [...list, ledger].sort((a, b) => a.name.localeCompare(b.name)) : list)
    reload()
  }, [update, reload])
  const left = useCallback((ledgerId: number) => {
    // In a transition, as the router navigates away from the budget: both show in one render.
    startTransition(() => update((list) => list?.filter((f) => f.id !== ledgerId)))
    reload()
  }, [update, reload])
  return (
    <>
      <Header families={familyOn ? families.data : null} name={me.name ?? me.email ?? ''} accountUrl={me.accountUrl} />
      <main>
        <ZoneHint />
        {/* A page that can't read its answer shows an error with a reload link, and the header stays (F8c). */}
        <Safe what="this page" resetKey={location.pathname}>
        <Routes>
          <Route path="/" element={<Dashboard familyOn={familyOn} families={families.data} onFamilyCreated={families.reload} />} />
          <Route path="/entries" element={<Entries />} />
          <Route path="/entries/new" element={<EntryEditor key="new" families={familyOn ? families.data ?? [] : undefined} />} />
          <Route path="/entries/:id" element={<EntryEditor families={familyOn ? families.data ?? [] : undefined} />} />
          <Route path="/accounts" element={<Accounts />} />
          <Route path="/categories" element={<Categories />} />
          <Route path="/rates" element={<Rates />} />
          <Route path="/import" element={<Moved to="/settings/import" />} />
          <Route path="/export" element={<Moved to="/settings/export" />} />
          <Route path="/settings/*" element={
            <Settings onDeleted={() => { families.reload(); void reloadMe() }} familyOn={familyOn}
              families={familyOn ? families.data ?? [] : undefined} />} />
          {familyOn && <Route path="/family/new" element={<NewFamily me={me} onCreated={families.reload} />} />}
          {familyOn && <Route path="/family/:ledgerId/*" element={<Family onChanged={families.reload} onLeft={left} />} />}
          {familyOn && <Route path="/invite" element={<InvitePage onJoined={joined} />} />}
          <Route path="/transactions" element={<Navigate to="/entries" replace />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
        </Safe>
      </main>
      <footer className="app-footer">
        <a href={PRIVACY_URL}>Privacy policy</a> · <a href={SOURCE_URL}>Source code</a>
      </footer>
    </>
  )
}

/** A page that moved (D-438): to its new path, with the query it was opened with, replacing the address. */
function Moved({ to }: { to: string }) {
  const { search } = useLocation()
  return <Navigate to={`${to}${search}`} replace />
}
