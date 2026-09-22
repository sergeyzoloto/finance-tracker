import { NavLink, Navigate, Route, Routes } from 'react-router'
import { keycloak } from './auth'
import Categories from './Categories'
import Dashboard from './Dashboard'
import Transactions from './Transactions'

export default function App() {
  return (
    <>
      <header>
        <nav>
          <NavLink to="/" end>Dashboard</NavLink>
          <NavLink to="/transactions">Transactions</NavLink>
          <NavLink to="/categories">Categories</NavLink>
        </nav>
        <span>{keycloak.tokenParsed?.name}</span>
        <button onClick={() => keycloak.logout({ redirectUri: `${window.location.origin}/` })}>Log out</button>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/transactions" element={<Transactions />} />
          <Route path="/categories" element={<Categories />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </>
  )
}
