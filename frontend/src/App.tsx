import { NavLink, Navigate, Route, Routes } from 'react-router'
import type { Me } from './api'
import { logOut } from './auth'
import Categories from './Categories'
import Dashboard from './Dashboard'
import Transactions from './Transactions'

export default function App({ me }: { me: Me }) {
  return (
    <>
      <header>
        <nav>
          <NavLink to="/" end>Dashboard</NavLink>
          <NavLink to="/transactions">Transactions</NavLink>
          <NavLink to="/categories">Categories</NavLink>
        </nav>
        <span>{me.name}</span>
        <button onClick={logOut}>Log out</button>
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
