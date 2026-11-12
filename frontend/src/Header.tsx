import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Link, NavLink, useLocation } from 'react-router'
import type { FamilyLedger } from './api'
import { logOut } from './auth'
import { SharedIcon } from './components'

/** Whether a path is inside a family budget (D-435): `/family/<id>…`, not `/family/new`. */
export const insideFamilyBudget = (pathname: string) => /^\/family\/(?!new(?:\/|$))[^/]+/.test(pathname)

/**
 * The app's header (D-498), two rows, both always shown. Row 1: the wordmark, a link to `/`; on the right "New entry"
 * (not inside a family budget, D-435) and the user's menu (Settings, Log out; D-433, D-434, D-464). Row 2, the main
 * navigation: the pages, then with family budgets on each budget by name, current on any of its pages, and "New family
 * budget". No theme control: the theme is chosen in Settings only (D-499).
 *
 * @param families the user's family budgets, undefined while they load; null with family budgets switched off
 * @param name the user's name, or their email for an account without one
 */
export default function Header({ families, name }: { families: FamilyLedger[] | undefined | null; name: string }) {
  const { pathname, search } = useLocation()
  const [open, setOpen] = useState(false)
  // Any route change closes the panel, choosing an item of it included (D-434).
  useEffect(() => setOpen(false), [pathname, search])
  const inside = families !== null && insideFamilyBudget(pathname)
  const toggle = useCallback(() => setOpen((now) => !now), [])
  const close = useCallback(() => setOpen(false), [])

  return (
    <header className="app-header">
      <div className="header-row">
        <Link className="wordmark" to="/">Finance Tracker</Link>
        <div className="header-end">
          {!inside && <Link className="button primary" to="/entries/new">New entry</Link>}
          {/* Under 40rem a person icon stands for the name, which the panel's first line gives (D-464). */}
          <Disclosure open={open} onToggle={toggle} onClose={close} className="user-menu" end
            title={name} name={name} label={<><PersonIcon /><span className="user-name">{name}</span><ChevronIcon /></>}>
            <p className="menu-user-name">{name}</p>
            <ul>
              <li><NavLink to="/settings">Settings</NavLink></li>
              <li><button type="button" className="link" onClick={logOut}>Log out</button></li>
            </ul>
          </Disclosure>
        </div>
      </div>
      <MainNav families={families} />
    </header>
  )
}

/**
 * Row 2 (D-498): the pages, then, with family budgets on, a divider, the budgets by name with the shared icon and "New
 * family budget". The current link is marked; under 40rem the row scrolls inside itself, the current link in view.
 */
function MainNav({ families }: { families: FamilyLedger[] | undefined | null }) {
  const { pathname } = useLocation()
  const nav = useRef<HTMLElement>(null)
  const sorted = [...(families ?? [])].sort((a, b) => a.name.localeCompare(b.name))
  useEffect(() => {
    const row = nav.current
    const current = row?.querySelector<HTMLElement>('a[aria-current="page"]')
    if (!row || !current) return
    // Scrolls the row only, never the page (scrollIntoView would move the page too).
    const left = current.offsetLeft
    const right = left + current.offsetWidth
    if (left < row.scrollLeft || right > row.scrollLeft + row.clientWidth) {
      row.scrollLeft = Math.max(0, left - (row.clientWidth - current.offsetWidth) / 2)
    }
  }, [pathname, families])
  return (
    <nav className="main-nav" aria-label="Main" ref={nav}>
      <NavLink to="/" end>Dashboard</NavLink>
      <NavLink to="/entries">Entries</NavLink>
      <NavLink to="/accounts">Accounts</NavLink>
      <NavLink to="/categories">Categories</NavLink>
      <NavLink to="/rates">Rates</NavLink>
      {families !== null && (
        <>
          <span className="nav-divider" aria-hidden="true" />
          {sorted.map((f) => <NavLink key={f.id} to={`/family/${f.id}`}><SharedIcon />{f.name}</NavLink>)}
          <NavLink to="/family/new"><PlusIcon />New family budget</NavLink>
        </>
      )}
    </nav>
  )
}

/**
 * A button that shows and hides the panel under it, the disclosure pattern (D-434; not `role="menu"`): its items are
 * links and buttons, reached by Tab. The panel closes on Escape, which puts the focus back on the button, on a click
 * outside, and on choosing an item.
 *
 * @param end the panel's right edge under the button's, for a menu at the header's right
 */
function Disclosure({ open, onToggle, onClose, label, title, name, className, end = false, children }: {
  open: boolean
  onToggle: () => void
  onClose: () => void
  label: ReactNode
  title?: string
  /** The button's accessible name, where its label may show an icon in place of its text. */
  name?: string
  className: string
  end?: boolean
  children: ReactNode
}) {
  const panelId = useId()
  const root = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const outside = (event: PointerEvent | MouseEvent) => {
      if (root.current && !root.current.contains(event.target as Node)) onClose()
    }
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      onClose()
      button.current?.focus()
    }
    document.addEventListener('mousedown', outside)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', outside)
      document.removeEventListener('keydown', escape)
    }
  }, [open, onClose])

  return (
    <div className={`disclosure ${className}`} ref={root}>
      <button ref={button} type="button" className="menu-button" aria-expanded={open} aria-controls={panelId}
        title={title} aria-label={name} onClick={onToggle}>
        {label}
      </button>
      <div id={panelId} className={`menu-panel${end ? ' end' : ''}`} hidden={!open}
        onClick={(event) => {
          // Choosing an item closes the panel; a click on its blank space doesn't.
          if ((event.target as HTMLElement).closest('a, button')) onClose()
        }}>
        {/* Its items only while it is open, so that a closed menu holds nothing a page's own labels could match. */}
        {open && children}
      </div>
    </div>
  )
}

/** A plus, drawn for the app: "New family budget" (D-498). */
function PlusIcon() {
  return (
    <svg className="icon" width="12" height="12" viewBox="0 0 12 12" aria-hidden="true" focusable="false">
      <path d="M6 1.5v9M1.5 6h9" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" fill="none" />
    </svg>
  )
}

/** A person, head and shoulders, drawn for the app (D-464): the user's button under 40rem. */
function PersonIcon() {
  return (
    <svg className="icon person" width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
      <circle cx="8" cy="5.2" r="2.7" stroke="currentColor" strokeWidth="1.5" fill="none" />
      <path d="M2.8 14c0-2.9 2.3-4.8 5.2-4.8s5.2 1.9 5.2 4.8" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" fill="none" />
    </svg>
  )
}

/** A small chevron pointing down. */
function ChevronIcon() {
  return (
    <svg className="icon chevron" width="10" height="10" viewBox="0 0 10 10" aria-hidden="true" focusable="false">
      <path d="M2 3.5l3 3 3-3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"
        fill="none" />
    </svg>
  )
}
