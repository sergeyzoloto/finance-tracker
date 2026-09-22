import Keycloak from 'keycloak-js'

// Tokens live only in this object's memory; after a page load the Keycloak session restores them.
export const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8180',
  realm: 'finance-tracker',
  clientId: 'finance-frontend',
})

export function initAuth() {
  return keycloak.init({
    // No login screen of our own: straight to Keycloak, unless its session is still alive.
    onLoad: 'login-required',
    pkceMethod: 'S256',
    // The status iframe needs third-party cookies; an ended session shows up on the next refresh instead,
    // and with login-required keycloak-js then sends the browser to the login page by itself.
    checkLoginIframe: false,
  })
}
