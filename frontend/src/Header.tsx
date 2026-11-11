import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Link, NavLink, useLocation } from 'react-router'
import type { FamilyLedger } from './api'
import { logOut } from './auth'

/** Which of the header's two menus is open: one at a time (D-434). */
type Open = 'main' | 'user' | null

/** Whether a path is inside a family budget (D-435): `/family/<id>…`, not `/family/new`. */
export const insideFamilyBudget = (pathname: string) => /^\/family\/(?!new(?:\/|$))[^/]+/.test(pathname)

/**
 * The app's header (D-433 to D-436). In My finances: on the left the main menu (the pages, then with family budgets on
 * each budget by name and "New family budget…"); on the right "New entry" and the user's menu (Settings, Log out).
 * Inside a family budget: "← My finances" in place of the main menu, no "New entry", and the user's menu. Nothing else.
 *
 * @param families the user's family budgets, undefined while they load; null with family budgets switched off
 * @param name the user's name, or their email for an account without one
 */
export default function Header({ families, name }: { families: FamilyLedger[] | undefined | null; name: string }) {
  const { pathname, search } = useLocation()
  const [open, setOpen] = useState<Open>(null)
  // Any route change closes a panel, choosing an item of it included (D-434).
  useEffect(() => setOpen(null), [pathname, search])
  const inside = families !== null && insideFamilyBudget(pathname)
  const toggle = (which: Exclude<Open, null>) => () => setOpen((now) => (now === which ? null : which))
  const close = useCallback(() => setOpen(null), [])

  return (
    <header className="app-header">
      <div className="header-start">
        {inside
          ? <Link className="button" to="/">← My finances</Link>
          : (
            <Disclosure open={open === 'main'} onToggle={toggle('main')} onClose={close} className="main-menu"
              label={<><MenuIcon /><span>My finances</span></>}>
              <MainPanel families={families} />
            </Disclosure>
          )}
      </div>
      <div className="header-end">
        {!inside && <Link className="button primary" to="/entries/new">New entry</Link>}
        {/* Under 40rem a person icon stands for the name, which the panel's first line gives (D-464). */}
        <Disclosure open={open === 'user'} onToggle={toggle('user')} onClose={close} className="user-menu" end
          title={name} name={name} label={<><PersonIcon /><span className="user-name">{name}</span><ChevronIcon /></>}>
          <p className="menu-user-name">{name}</p>
          <ul>
            <li><NavLink to="/settings">Settings</NavLink></li>
            <li><button type="button" className="link" onClick={logOut}>Log out</button></li>
          </ul>
        </Disclosure>
      </div>
    </header>
  )
}

/** The main menu's items: the pages, then, with family budgets on, the budgets by name and a new one (D-433). */
function MainPanel({ families }: { families: FamilyLedger[] | undefined | null }) {
  const sorted = [...(families ?? [])].sort((a, b) => a.name.localeCompare(b.name))
  return (
    <>
      <ul aria-label="Pages">
        <li><NavLink to="/" end>Dashboard</NavLink></li>
        <li><NavLink to="/entries">Entries</NavLink></li>
        <li><NavLink to="/accounts">Accounts</NavLink></li>
        <li><NavLink to="/categories">Categories</NavLink></li>
        <li><NavLink to="/rates">Rates</NavLink></li>
      </ul>
      {families !== null && (
        <>
          <p className="menu-group" id="menu-family-budgets">Family budgets</p>
          <ul aria-labelledby="menu-family-budgets">
            {sorted.map((f) => <li key={f.id}><NavLink to={`/family/${f.id}`}>{f.name}</NavLink></li>)}
            <li><NavLink to="/family/new">New family budget…</NavLink></li>
          </ul>
        </>
      )}
    </>
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

/** Three lines, drawn for the app (D-434). */
function MenuIcon() {
  return (
    <svg className="icon" width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
      <path d="M2 3.5h12M2 8h12M2 12.5h12" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" fill="none" />
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
