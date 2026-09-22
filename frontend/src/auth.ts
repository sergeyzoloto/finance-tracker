// Login runs on the backend (backend-for-frontend): it holds the tokens, and this page only ever has the
// HttpOnly session cookie. See docs/auth.md.

const RETURN_TO = 'returnTo' // sessionStorage: the page to reopen after the round trip through Keycloak

/** How the backend sent the browser back from Keycloak: `/?login=done` or `/?login=failed`; null on other visits. */
export const loginResult = new URLSearchParams(location.search).get('login')

/** Sends the browser to the Keycloak login page, which returns it to the current page. Never resolves. */
export function logIn(): Promise<never> {
  sessionStorage.setItem(RETURN_TO, location.pathname + location.search)
  location.assign('/oauth2/authorization/keycloak')
  return new Promise<never>(() => {})
}

/** Leaves the backend's landing URL for the page the user was on before the login, or else the start page. */
export function finishLogin() {
  const returnTo = sessionStorage.getItem(RETURN_TO) ?? '/'
  sessionStorage.removeItem(RETURN_TO)
  if (loginResult !== null) history.replaceState(null, '', returnTo.startsWith('/') && !returnTo.startsWith('//') ? returnTo : '/')
}

/** Ends the session here and at Keycloak. A form post, so the browser follows the redirects to Keycloak and back. */
export function logOut() {
  const form = document.createElement('form')
  form.method = 'POST'
  form.action = '/logout'
  const token = document.createElement('input')
  token.type = 'hidden'
  token.name = '_csrf'
  token.value = csrfToken()
  form.append(token)
  document.body.append(form)
  form.submit()
}

/** The CSRF token the backend puts in a readable cookie; every write sends it back. */
export function csrfToken() {
  const cookie = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))
  return cookie ? decodeURIComponent(cookie.slice('XSRF-TOKEN='.length)) : ''
}
