import { test as setup, type Browser } from '@playwright/test'
import type { Role } from '../lib/accounts.ts'
import { LOCAL_PASSWORD, TEST_PASSWORD } from '../lib/accounts.ts'
import { CREDENTIALS_PATH, readCredentials } from '../lib/credentials.ts'
import { checkMe, familyFrom, RunAborted, type Family } from '../lib/identity.ts'
import { markAborted, markVerified, sessionDir, statePath } from '../lib/session.ts'
import { targetNamed, type Target } from '../lib/targets.ts'

// One sign-in per account per run (D-52, guard 3), with tracing and screenshots off (the project's options). The
// password goes into Keycloak's form through the page itself, so that no step's title or log holds it. A failed sign-in
// is never tried again, because of Keycloak's brute-force protection: the run stops.

const ROLES: Role[] = ['A', 'B']

setup('sign in each account once', async ({ browser }) => {
  const target = targetNamed(process.env.E2E_TARGET)
  const family = familyFrom(process.env.E2E_FAMILY)
  const dir = sessionDir()
  const passwords = target.name === 'prod'
    ? (() => { const c = readCredentials(CREDENTIALS_PATH, target.roles); return { A: c.A.password, B: c.B.password } })()
    : target.name === 'test' ? { A: TEST_PASSWORD, B: TEST_PASSWORD } : { A: LOCAL_PASSWORD, B: LOCAL_PASSWORD }
  for (const role of ROLES) {
    try {
      await signIn(browser, target, role, passwords[role], family, dir)
    } catch (error) {
      // Nothing more from any account: not the other's sign-in, no spec, no cleanup.
      const reason = error instanceof RunAborted || error instanceof SignInFailed
        ? error.message : `the sign-in of ${target.roles[role].label} failed: ${(error as Error).message.split('\n')[0]}`
      markAborted(dir, reason)
      throw new Error(reason)
    }
  }
})

class SignInFailed extends Error {}

async function signIn(browser: Browser, target: Target, role: Role, password: string, family: Family, dir: string) {
  const account = target.roles[role]
  const context = await browser.newContext({ baseURL: target.app, locale: 'en-US', timezoneId: 'UTC' })
  try {
    // Until /api/me has named the account, the app's other requests never reach the API (D-52, guard 2).
    await context.route((url) => url.origin === target.app && url.pathname.startsWith('/api/') && url.pathname !== '/api/me',
      (route) => route.abort('blockedbyclient'))
    const page = await context.newPage()
    await page.goto('/oauth2/authorization/keycloak')
    if (new URL(page.url()).origin !== target.keycloak) throw new SignInFailed(`the login page isn't on ${target.keycloak}`)
    await page.locator('#username').fill(account.login)
    await page.locator('#password').evaluate((input, value) => { (input as HTMLInputElement).value = value }, password)
    const me = page.waitForResponse((r) => r.url() === `${target.app}/api/me`, { timeout: 30_000 })
    me.catch(() => undefined) // awaited below only once back at the app
    await page.locator('#kc-login').click()
    // Back at the app, or Keycloak answering the form with a page of its own (/login-actions/…): an error or a
    // required action.
    await page.waitForURL((url) => url.origin === target.app || url.pathname.includes('/login-actions/'), { timeout: 30_000 })
      .catch(() => undefined)
    if (new URL(page.url()).origin !== target.app) {
      // Still at Keycloak: a wrong password, a lockout, or a required action such as "Update your account". Its title
      // and message say which; the form's values are never read.
      const title = (await page.locator('#kc-page-title').textContent({ timeout: 2_000 }).catch(() => null))?.trim()
      const message = (await page.locator('#input-error, .kc-feedback-text, .pf-v5-c-alert__title').first()
        .textContent({ timeout: 2_000 }).catch(() => null))?.trim()
      throw new SignInFailed(`the sign-in of ${account.label} didn't come back to the app (Keycloak shows "${title ?? '?'}"`
        + `${message ? `: "${message}"` : ''}). Not tried again: Keycloak counts failed logins.`)
    }
    const answer = await me
    if (answer.status() !== 200) throw new SignInFailed(`/api/me answered ${answer.status()} after ${account.label}'s sign-in`)
    checkMe(await answer.json(), account, family)
    await context.unrouteAll({ behavior: 'ignoreErrors' })
    await context.storageState({ path: statePath(dir, account.label) })
    markVerified(dir, account.label)
  } finally {
    await context.close()
  }
}
