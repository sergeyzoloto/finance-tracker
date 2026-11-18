// Addresses outside the single-page app, which plain links open with a full page load.

/** The backend's start of the login (SecurityConfig). Keycloak's sign-in page also offers to register. */
export const LOGIN_URL = '/oauth2/authorization/keycloak'
/** The privacy policy: static HTML next to index.html (public/privacy.html), readable without the backend. */
export const PRIVACY_URL = '/privacy'
export const SOURCE_URL = 'https://github.com/sergeyzoloto/finance-tracker'
/** The operator's address in the privacy policy, for requests such as deleting a login account. */
export const CONTACT_EMAIL = 'serge@finance-nl.com'
/** The author's page (D-979), the third link of both footers. */
export const AUTHOR_URL = 'https://cv-zoloto.pages.dev/'

/**
 * Whether a host is the apex of the site, such as `finance-nl.com`: a name of exactly two labels (D-980). The app's
 * host, `localhost` (dev stack, e2e) and an IP address are not, so they keep behaving as before. The only place that
 * tests it.
 */
export function isApexHost(hostname: string): boolean {
  return /^[a-z0-9-]+\.[a-z][a-z0-9-]*$/i.test(hostname)
}

/** The app's origin from the apex: `app.` + the apex, over https (D-980). */
export function appOrigin(hostname: string): string {
  return `https://app.${hostname}`
}
