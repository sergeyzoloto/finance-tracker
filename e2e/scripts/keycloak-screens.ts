import { createHmac } from 'node:crypto'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { chromium, type BrowserContext, type Page } from '@playwright/test'
import { LOCAL, requireLocalOnly } from '../lib/targets.ts'

// npm run screens:keycloak -- <folder>: screenshots of every Keycloak page UX-15's theme styles (D-618), the account
// console's Personal info, Signing in and Linked accounts (D-619), and the emails in Mailpit (D-620), each in Blue,
// Light and Dark at 1280 and 375 px (the emails at 600 and 375 px, Light only: D-620), for the stage's review. Local
// only: the dev stack of ../auth_server (its Keycloak on localhost:8080 with the theme mounted, Mailpit on
// 127.0.0.1:8025), signed in as its master admin with the dev stack's own fixed password. It never touches the realm
// myapps: it creates two throwaway realms, ux15-pages (set as production's myapps is: registration, email
// verification, password reset, Google and GitHub on the sign-in page, D-621's settings, SMTP to Mailpit) and
// ux15-idp (a stand-in identity provider, hidden on the sign-in page, for a first sign-in through a provider, since
// the dev stack can't reach Google or GitHub), deletes Mailpit's messages, and deletes both realms at the end, also
// after a failure. A page is taken in a theme by setting the cookie ft_theme and data-theme on <html> as the theme's
// js/theme.js does on load (the cookie itself is the 10-themes spec's to check), so that a page reached by a form
// isn't posted again.

const KEYCLOAK = LOCAL.keycloak
const MAILPIT = 'http://127.0.0.1:8025'
requireLocalOnly({ ...LOCAL, hosts: [...LOCAL.hosts, new URL(MAILPIT).hostname] })
const ADMIN = { user: 'admin', password: 'admin_dev_password' }
const REALM = 'ux15-pages'
const IDP_REALM = 'ux15-idp'
const PASSWORD = 'ux15-dev-password-1'
const THEMES = ['blue', 'light', 'dark'] as const
const WIDTHS = [1280, 375] as const
const DANA = 'dana@finance-tracker.test'
const OTTO = 'otto@finance-tracker.test'
const NINA = 'nina@finance-tracker.test'
const ERIN = 'erin@finance-tracker.test'

const [folder] = process.argv.slice(2)
if (!folder) {
  console.error('Usage: npm run screens:keycloak -- <folder>')
  process.exit(1)
}
mkdirSync(folder, { recursive: true })
const taken: string[] = []
/** Pages wider than their window: a sideways scroll, reported at the end. */
const wide: string[] = []

// The admin REST API, as the dev stack's master admin.
async function adminToken(): Promise<string> {
  const response = await fetch(`${KEYCLOAK}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli', username: ADMIN.user, password: ADMIN.password }),
  })
  if (!response.ok) throw new Error(`The dev Keycloak's admin sign-in answered ${response.status}`)
  return (await response.json()).access_token
}

async function admin(method: string, path: string, body?: unknown, okStatuses: number[] = []) {
  const response = await fetch(`${KEYCLOAK}/admin/realms${path}`, {
    method,
    headers: { Authorization: `Bearer ${await adminToken()}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok && !okStatuses.includes(response.status)) {
    throw new Error(`${method} ${path}: ${response.status} ${(await response.text()).slice(0, 300)}`)
  }
  const text = await response.text()
  return text ? JSON.parse(text) : undefined
}

async function removeRealms() {
  for (const realm of [REALM, IDP_REALM]) await admin('DELETE', `/${realm}`, undefined, [404])
}

async function setUp() {
  await removeRealms()
  // The provider: a user with an email of ux15-pages' and no name, so that the first sign-in asks for a profile and
  // then finds the account that exists.
  await admin('POST', '', {
    realm: IDP_REALM, enabled: true, displayName: 'Provider stand-in',
    clients: [{
      clientId: 'ux15-broker', enabled: true, publicClient: false, secret: 'ux15-broker-secret',
      standardFlowEnabled: true, redirectUris: [`${KEYCLOAK}/realms/${REALM}/broker/dev-idp/endpoint`],
    }],
    users: [{
      username: 'dana', email: DANA, emailVerified: true, enabled: true,
      credentials: [{ type: 'password', value: PASSWORD, temporary: false }],
    }],
  })
  await admin('PUT', `/${IDP_REALM}/authentication/required-actions/VERIFY_PROFILE`, {
    alias: 'VERIFY_PROFILE', name: 'Verify Profile', providerId: 'VERIFY_PROFILE', enabled: false, defaultAction: false,
  })
  // The pages' realm, as production's myapps is set (PRODUCTION.md, "Realm myapps"), with D-621's settings.
  await admin('POST', '', {
    realm: REALM, enabled: true, displayName: 'Finance Tracker',
    loginTheme: 'finance-tracker', accountTheme: 'finance-tracker', emailTheme: 'finance-tracker',
    registrationAllowed: true, registrationEmailAsUsername: true, verifyEmail: true, resetPasswordAllowed: true,
    loginWithEmailAllowed: true, duplicateEmailsAllowed: false, editUsernameAllowed: false, rememberMe: false,
    passwordPolicy: 'length(12) and maxLength(128) and notUsername and notEmail',
    smtpServer: { host: 'mailpit', port: '1025', from: 'noreply@finance-tracker.test', fromDisplayName: 'Finance Tracker' },
    clients: [{
      // Only for the account console's "Back to Finance Tracker" (referrer=finance-tracker), as in myapps.
      clientId: 'finance-tracker', name: 'Finance Tracker', enabled: true, publicClient: false, secret: 'ux15-secret',
      standardFlowEnabled: true, baseUrl: 'http://localhost:5173/', redirectUris: ['http://localhost:5173/login/oauth2/code/keycloak'],
    }],
    users: [
      { username: DANA, email: DANA, firstName: 'Dana', lastName: 'Example', emailVerified: true, enabled: true,
        realmRoles: [`default-roles-${REALM}`], credentials: [{ type: 'password', value: PASSWORD, temporary: false }] },
      { username: OTTO, email: OTTO, firstName: 'Otto', lastName: 'Example', emailVerified: true, enabled: true,
        realmRoles: [`default-roles-${REALM}`], requiredActions: ['CONFIGURE_TOTP'], credentials: [{ type: 'password', value: PASSWORD, temporary: false }] },
      // No role at all: the account console's API refuses her (its error screen).
      { username: ERIN, email: ERIN, firstName: 'Erin', lastName: 'Example', emailVerified: true, enabled: true,
        credentials: [{ type: 'password', value: PASSWORD, temporary: false }] },
    ],
    identityProviders: [
      // Shown as in production; never followed here.
      { alias: 'google', providerId: 'google', displayName: 'Google', enabled: true,
        config: { clientId: 'dev-only', clientSecret: 'dev-only', syncMode: 'IMPORT' } },
      { alias: 'github', providerId: 'github', displayName: 'GitHub', enabled: true,
        config: { clientId: 'dev-only', clientSecret: 'dev-only', syncMode: 'IMPORT' } },
      // The stand-in, hidden on the sign-in page and reached with kc_idp_hint.
      { alias: 'dev-idp', providerId: 'oidc', displayName: 'GitHub', enabled: true, hideOnLogin: true, trustEmail: false,
        config: {
          hideOnLoginPage: 'true', syncMode: 'IMPORT', clientId: 'ux15-broker', clientSecret: 'ux15-broker-secret',
          clientAuthMethod: 'client_secret_post', defaultScope: 'openid profile email',
          authorizationUrl: `${KEYCLOAK}/realms/${IDP_REALM}/protocol/openid-connect/auth`,
          tokenUrl: `${KEYCLOAK}/realms/${IDP_REALM}/protocol/openid-connect/token`,
          userInfoUrl: `${KEYCLOAK}/realms/${IDP_REALM}/protocol/openid-connect/userinfo`,
          issuer: `${KEYCLOAK}/realms/${IDP_REALM}`, validateSignature: 'false', pkceEnabled: 'false',
        } },
    ],
  })
  await fetch(`${MAILPIT}/api/v1/messages`, { method: 'DELETE' })
}

/** Takes the page as it is now in each theme at each width. */
async function snap(context: BrowserContext, page: Page, name: string, widths: readonly number[] = WIDTHS) {
  for (const theme of THEMES) {
    await context.addCookies([{ name: 'ft_theme', value: theme, domain: 'localhost', path: '/' }])
    await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), theme)
    for (const width of widths) {
      await page.setViewportSize({ width, height: width === 375 ? 812 : 900 })
      await page.waitForTimeout(700)
      const file = `${name}-${theme}-${width}.png`
      const scroll = await page.evaluate(() => document.documentElement.scrollWidth)
      if (scroll > width) wide.push(`${file}: ${scroll} px wide`)
      await page.screenshot({ path: join(folder, file), fullPage: true })
      taken.push(file)
    }
  }
  await page.setViewportSize({ width: 1280, height: 900 })
}

async function fresh(): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ locale: 'en-US', viewport: { width: 1280, height: 900 }, colorScheme: 'light' })
  return { context, page: await context.newPage() }
}

/** The account console of ux15-pages, which sends an anonymous browser to the sign-in page. */
const consoleUrl = (path = '') => `${KEYCLOAK}/realms/${REALM}/account/${path}?referrer=finance-tracker`

async function signInForm(page: Page, email: string, password = PASSWORD) {
  await page.locator('#username').fill(email)
  await page.locator('#password').fill(password)
  await page.locator('#kc-login').click()
}

async function mail(to: string, subject: RegExp): Promise<{ id: string; link: string }> {
  for (let i = 0; i < 40; i++) {
    const list = await (await fetch(`${MAILPIT}/api/v1/messages`)).json() as
      { messages: { ID: string; Subject: string; To: { Address: string }[] }[] }
    const found = list.messages.find((m) => m.To.some((t) => t.Address === to) && subject.test(m.Subject))
    if (found) {
      const message = await (await fetch(`${MAILPIT}/api/v1/message/${found.ID}`)).json() as { Text: string }
      const link = message.Text.match(/https?:\/\/\S+/)?.[0]
      if (!link) throw new Error(`No link in "${found.Subject}"`)
      return { id: found.ID, link }
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`No email to ${to} matching ${subject}`)
}

async function snapMail(id: string, name: string) {
  const { context, page } = await fresh()
  await page.goto(`${MAILPIT}/view/${id}.html`)
  for (const width of [600, 375]) {
    await page.setViewportSize({ width, height: 900 })
    await page.waitForTimeout(250)
    const file = `${name}-${width}.png`
    await page.screenshot({ path: join(folder, file), fullPage: true })
    taken.push(file)
  }
  await context.close()
}

/** RFC 6238's code for a base32 secret, as Keycloak's default OTP policy has it: SHA-1, 6 digits, 30 seconds. */
function totp(secret: string): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = ''
  for (const c of secret.replace(/[\s=]/g, '').toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, '0')
  const key = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)))
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30_000)))
  const hmac = createHmac('sha1', key).update(counter).digest()
  const offset = hmac[hmac.length - 1] & 0xf
  return String((hmac.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, '0')
}

const browser = await chromium.launch({ channel: 'chrome' })
try {
  await setUp()

  // 1. Sign in: empty, then a wrong password; register: empty and with errors; forgot password.
  {
    const { context, page } = await fresh()
    await page.goto(consoleUrl())
    await page.locator('#kc-login').waitFor()
    await snap(context, page, '01-sign-in')
    await signInForm(page, DANA, 'not the password at all')
    await page.locator('[id^="input-error-"]').first().waitFor()
    await snap(context, page, '02-sign-in-error')
    await page.getByRole('link', { name: 'Register' }).click()
    await page.locator('#kc-register-form').waitFor()
    await snap(context, page, '03-register')
    // With email verification on, Keycloak 26.7 asks for no password here: it asks after the email is verified
    // (RegistrationPassword, unless always_set_password_on_register_form is set), as in production.
    await page.locator('#email').fill('not-an-email')
    await page.locator('#kc-register-form [type=submit]').click()
    await page.locator('#kc-register-form').waitFor()
    await snap(context, page, '04-register-errors')
    await page.getByRole('link', { name: /Back to Login/i }).click()
    await page.getByRole('link', { name: /Forgot Password/i }).click()
    await page.locator('#kc-reset-password-form').waitFor()
    await snap(context, page, '05-forgot-password')
    await page.locator('#username').fill(DANA)
    await page.locator('#kc-reset-password-form [type=submit]').click()
    await page.locator('.pf-v5-c-alert').first().waitFor()
    await snap(context, page, '06-sign-in-reset-sent')
    await context.close()
  }

  // 2. The reset email, then its link: update password.
  {
    const reset = await mail(DANA, /password/i)
    await snapMail(reset.id, '20-email-reset-password')
    const { context, page } = await fresh()
    await page.goto(reset.link)
    await page.locator('#password-new').waitFor()
    await snap(context, page, '07-update-password')
    await context.close()
  }

  // 3. Register: verify email; the email; its link (email verified); the link again (no longer valid).
  {
    const { context, page } = await fresh()
    await page.goto(consoleUrl())
    await page.getByRole('link', { name: 'Register' }).click()
    await page.locator('#firstName').fill('Nina')
    await page.locator('#lastName').fill('Example')
    await page.locator('#email').fill(NINA)
    await page.locator('#kc-register-form [type=submit]').click()
    await page.getByText(/verify your email/i).first().waitFor()
    await snap(context, page, '08-verify-email')
    const verify = await mail(NINA, /verify/i)
    await snapMail(verify.id, '21-email-verify')
    // The link in another browser: the email is verified there, and the new account's password asked for.
    const other = await fresh()
    await other.page.goto(verify.link)
    await other.page.waitForLoadState('networkidle')
    await snap(other.context, other.page, '09-info-email-verified')
    const go = other.page.locator('#kc-info-message a, .instruction a').first()
    if (await go.count() > 0) {
      await go.click()
      await other.page.waitForLoadState('networkidle')
      await snap(other.context, other.page, '09a-after-email-verified')
    }
    await other.context.close()
    // The same link again, used: no longer valid.
    const again = await fresh()
    await again.page.goto(verify.link)
    await again.page.waitForLoadState('networkidle')
    await snap(again.context, again.page, '10-error-link-expired')
    await again.context.close()
    await context.close()
  }

  // 4. Page expired: the register form's action, posted again once the flow is back at the sign-in step. Then the
  //    account console's own error.
  {
    const { context, page } = await fresh()
    await page.goto(consoleUrl())
    await page.getByRole('link', { name: 'Register' }).click()
    await page.locator('#kc-register-form').waitFor()
    const registerAction = (await page.locator('#kc-register-form').getAttribute('action'))!
    await page.getByRole('link', { name: /Back to Login/i }).click()
    await page.locator('#kc-login').waitFor()
    await page.evaluate((action) => {
      const form = document.createElement('form')
      form.method = 'post'
      form.action = action
      document.body.append(form)
      form.submit()
    }, registerAction)
    await page.getByText('Page has expired').first().waitFor()
    await snap(context, page, '11-page-expired')
    await context.close()
  }
  {
    // The account console's own error: Erin has none of the account client's roles, so its API answers 401.
    const { context, page } = await fresh()
    await page.goto(consoleUrl())
    await signInForm(page, ERIN)
    await page.getByText(/Something went wrong|unexpected error/i).first().waitFor()
    await snap(context, page, '33-account-error')
    await context.close()
  }

  // 5. Set up a one-time code (Otto must), then enter it at the next sign-in.
  {
    const { context, page } = await fresh()
    await page.goto(consoleUrl())
    await signInForm(page, OTTO)
    await page.locator('#kc-totp-settings-form, #totp').first().waitFor()
    await snap(context, page, '12-otp-set-up')
    await page.getByRole('link', { name: /Unable to scan/i }).click()
    await page.locator('#kc-totp-secret-key').waitFor()
    await snap(context, page, '13-otp-set-up-manual')
    const secret = (await page.locator('#kc-totp-secret-key').textContent())!.trim()
    await page.locator('#totp').fill(totp(secret))
    await page.locator('#userLabel').fill('Phone')
    await page.locator('#kc-totp-settings-form [type=submit], #saveTOTPBtn').first().click()
    await page.waitForURL(/\/account/)
    await page.waitForLoadState('networkidle')
    // 6. The logout confirmation, then the code at the next sign-in.
    await page.goto(`${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/logout`)
    await page.locator('#kc-logout').waitFor()
    await snap(context, page, '14-logout-confirm')
    await page.locator('#kc-logout').click()
    await page.waitForLoadState('networkidle')
    await page.goto(consoleUrl())
    await signInForm(page, OTTO)
    await page.locator('#otp').waitFor()
    await snap(context, page, '15-otp-enter')
    await context.close()
  }

  // 7. A first sign-in through a provider: review profile, account exists, link by email (and its email), link by
  //    signing in.
  {
    const { context, page } = await fresh()
    await page.goto(consoleUrl())
    await page.locator('#kc-login').waitFor()
    const url = new URL(page.url())
    url.searchParams.set('kc_idp_hint', 'dev-idp')
    await page.goto(url.toString())
    await page.locator('#username').fill('dana')
    await page.locator('#password').fill(PASSWORD)
    await page.locator('#kc-login').click()
    await page.locator('#firstName').waitFor()
    await snap(context, page, '16-first-sign-in-review-profile')
    await page.locator('#firstName').fill('Dana')
    await page.locator('#lastName').fill('Example')
    await page.locator('[type=submit]').first().click()
    await page.locator('#linkAccount, [name=submitAction][value=linkAccount]').first().waitFor()
    await snap(context, page, '17-first-sign-in-account-exists')
    await page.locator('#linkAccount, [name=submitAction][value=linkAccount]').first().click()
    await page.waitForLoadState('networkidle')
    await snap(context, page, '18-first-sign-in-link-by-email')
    const link = await mail(DANA, /link/i)
    await snapMail(link.id, '22-email-identity-provider-link')
    const another = page.getByRole('link', { name: /try another way/i })
    if (await another.count() > 0) {
      await another.click()
      await page.waitForLoadState('networkidle')
      await snap(context, page, '19a-first-sign-in-choose-a-way')
      const reauth = page.locator('.select-auth-box-parent, [data-testid="select-auth"] a, .pf-v5-c-data-list__item').filter({ hasText: /password|re-?authenticat|sign in/i }).first()
      await reauth.click()
      await page.waitForLoadState('networkidle')
    }
    await snap(context, page, '19-first-sign-in-link-by-signing-in')
    await context.close()
  }

  // 8. The account console, signed in as Dana: Personal info, Signing in, Linked accounts.
  {
    const { context, page } = await fresh()
    await page.goto(consoleUrl())
    await signInForm(page, DANA)
    await page.waitForURL(/\/account\//)
    for (const [name, path] of [['30-account-personal-info', ''], ['31-account-signing-in', 'account-security/signing-in'],
      ['32-account-linked-accounts', 'account-security/linked-accounts']] as const) {
      await page.goto(consoleUrl(path))
      await page.waitForLoadState('networkidle')
      await page.waitForTimeout(500)
      await snap(context, page, name)
    }
    await context.close()
  }
} finally {
  await browser.close()
  // KEEP_REALMS=1 leaves both realms for a look in the admin console; the next run removes them first.
  if (process.env.KEEP_REALMS !== '1') await removeRealms().catch((error) => console.error(`The throwaway realms weren't removed: ${error.message}`))
  writeFileSync(join(folder, 'taken.txt'), taken.join('\n') + '\n')
  console.log(`${taken.length} screenshots in ${folder}`)
  console.log(wide.length === 0 ? 'No page wider than its window.' : `Wider than the window:\n${wide.join('\n')}`)
  if (wide.length > 0) process.exitCode = 1
}
