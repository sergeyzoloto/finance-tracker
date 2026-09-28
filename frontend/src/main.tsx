import { StrictMode, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import App from './App'
import type { Me } from './api'
import { finishLogin, logIn, loginResult, logOut } from './auth'
import Landing from './Landing'
import './index.css'

/** How long the start page waits for the backend before it shows the landing page anyway. */
const BACKEND_WAIT_MS = 8000

// Nothing renders until the backend has said who is signed in. Without a session, the start page shows what the app is
// and how to sign in, and any other page goes straight to Keycloak.
createRoot(document.getElementById('root')!).render(await start())

async function start(): Promise<ReactNode> {
  finishLogin()
  if (loginResult === 'failed') {
    return <Notice message="Signing in didn't work. The login service may be unavailable." action="Try again" onAction={logIn} />
  }
  const onStartPage = location.pathname === '/'
  const response = await fetch('/api/me', { signal: AbortSignal.timeout?.(BACKEND_WAIT_MS) }).catch(() => null)
  if (response?.ok) {
    const me: Me = await response.json()
    return (
      <StrictMode>
        <BrowserRouter>
          <App me={me} />
        </BrowserRouter>
      </StrictMode>
    )
  }
  if (response?.status === 401 && loginResult !== 'done') return onStartPage ? <Landing /> : logIn()
  if (response?.status === 403) {
    return (
      <Notice
        message="Your account has no access to Finance Tracker. Ask an administrator for access, then sign in again."
        action="Sign out"
        onAction={logOut}
      />
    )
  }
  if (response?.status === 401) {
    // Right after signing in: the backend rejects fresh logins, so another automatic one would only loop.
    return <Notice message="You signed in, but the server did not accept the session." action="Try again" onAction={logIn} />
  }
  // The backend is down or doesn't answer: the start page needs nothing from it, and its sign-in link leads to the
  // login as soon as the backend is back.
  if (onStartPage) return <Landing />
  return <Notice message="Could not reach the server. Please try again in a moment." action="Reload" onAction={() => location.reload()} />
}

function Notice({ message, action, onAction }: { message: string; action: string; onAction: () => unknown }) {
  return (
    <main>
      <p className="error">{message}</p>
      <button onClick={() => void onAction()}>{action}</button>
    </main>
  )
}
