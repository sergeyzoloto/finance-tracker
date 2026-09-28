// Addresses outside the single-page app, which plain links open with a full page load.

/** The backend's start of the login (SecurityConfig). Keycloak's sign-in page also offers to register. */
export const LOGIN_URL = '/oauth2/authorization/keycloak'
/** The privacy policy: static HTML next to index.html (public/privacy.html), readable without the backend. */
export const PRIVACY_URL = '/privacy'
export const SOURCE_URL = 'https://github.com/sergeyzoloto/finance-tracker'
/** The operator's address in the privacy policy, for requests such as deleting a login account. */
export const CONTACT_EMAIL = 'serge@finance-nl.com'
