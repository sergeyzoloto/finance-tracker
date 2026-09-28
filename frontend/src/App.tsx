import { Link, NavLink, Navigate, Route, Routes } from 'react-router'
import Accounts from './Accounts'
import type { Me } from './api'
import { logOut } from './auth'
import Categories from './Categories'
import Dashboard from './Dashboard'
import Entries from './Entries'
import EntryEditor from './EntryEditor'
import Import from './Import'
import { PRIVACY_URL, SOURCE_URL } from './links'
import Rates from './Rates'
import Settings from './Settings'

export default function App({ me }: { me: Me }) {
  return (
    <>
      <header>
        <nav>
          <NavLink to="/" end>Dashboard</NavLink>
          <NavLink to="/entries">Entries</NavLink>
          <NavLink to="/accounts">Accounts</NavLink>
          <NavLink to="/categories">Categories</NavLink>
          <NavLink to="/rates">Rates</NavLink>
          <NavLink to="/import">Import</NavLink>
          <NavLink to="/settings">Settings</NavLink>
        </nav>
        <Link className="button primary" to="/entries/new">New entry</Link>
        <span>{me.name}</span>
        <button onClick={logOut}>Log out</button>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/entries" element={<Entries />} />
          <Route path="/entries/new" element={<EntryEditor key="new" />} />
          <Route path="/entries/:id" element={<EntryEditor />} />
          <Route path="/accounts" element={<Accounts />} />
          <Route path="/categories" element={<Categories />} />
          <Route path="/rates" element={<Rates />} />
          <Route path="/import" element={<Import />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="/transactions" element={<Navigate to="/entries" replace />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
      <footer className="app-footer">
        <a href={PRIVACY_URL}>Privacy policy</a> · <a href={SOURCE_URL}>Source code</a>
      </footer>
    </>
  )
}
