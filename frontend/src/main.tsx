import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import App from './App'
import { initAuth } from './auth'
import './index.css'

// Nothing renders until Keycloak has authenticated the user.
const authenticated = await initAuth().then(() => true, () => false)

createRoot(document.getElementById('root')!).render(
  authenticated ? (
    <StrictMode>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </StrictMode>
  ) : (
    <p className="error">Could not reach the login service. Please reload the page.</p>
  ),
)
