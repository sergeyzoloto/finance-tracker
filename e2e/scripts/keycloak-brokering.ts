import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { chromium, type Page } from '@playwright/test'
import { LOCAL, requireLocalOnly } from '../lib/targets.ts'

// npm run brokering:keycloak -- <folder>: first sign-ins through an identity provider, for a Keycloak upgrade's
// review (OPS-4, D-921; the auth server's PRODUCTION.md, "Identity providers"). Local only. Against the dev stack's
// Keycloak (KEYCLOAK_URL, localhost:8080 by default; a second Keycloak on another localhost port works too, with
// SMTP_HOST naming Mailpit as that Keycloak reaches it) and Mailpit on 127.0.0.1:8025, signed in as the dev stack's
// master admin. It creates two throwaway realms and deletes both at the end, also after a failure:
//   ops4-idp  the provider's realm, five users whose email_verified is true or false;
//   ops4-app  production's myapps in what matters here (registration, verifyEmail, theme, SMTP to Mailpit, first login
//             flow "first broker login") with the identity provider dev-idp, whose Trust Email is switched per case,
//             and two existing users: eva (email verified) and fred (not verified).
// Each case signs in as an identity-provider user with a fresh browser, follows the pages Keycloak shows (account
// already exists, the email to confirm the link, review profile) and prints what the app realm holds afterwards:
// the user's emailVerified, its required actions, its linked identity and the emails sent. The last case signs out
// of the app realm and says whether the provider's session ended too (Keycloak 26.8's "a browser logout always
// performs the upstream provider's logout"). The output is one JSON object per case, in <folder>/brokering.json.

const KEYCLOAK = process.env.KEYCLOAK_URL ?? LOCAL.keycloak
const MAILPIT = 'http://127.0.0.1:8025'
const SMTP_HOST = process.env.SMTP_HOST ?? 'mailpit'
requireLocalOnly({ ...LOCAL, keycloak: KEYCLOAK, hosts: [...LOCAL.hosts, new URL(MAILPIT).hostname] })
const ADMIN = { user: 'admin', password: 'admin_dev_password' }
const APP = 'ops4-app'
const IDP = 'ops4-idp'
const PASSWORD = 'ops4-dev-password-1'
const CALLBACK = 'http://localhost:8099/cb'
const SECRET = 'ops4-client-secret'

const [folder] = process.argv.slice(2)
if (!folder) {
  console.error('Usage: npm run brokering:keycloak -- <folder>')
  process.exit(1)
}
mkdirSync(folder, { recursive: true })

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

interface IdpUser { username: string; email: string; verified: boolean }
const IDP_USERS: Record<string, IdpUser> = {
  newVerified: { username: 'iv-new', email: 'newv@ops4.test', verified: true },
  newUnverified: { username: 'iu-new', email: 'newu@ops4.test', verified: false },
  evaVerified: { username: 'iv-eva', email: 'eva@ops4.test', verified: true },
  evaUnverified: { username: 'iu-eva', email: 'eva@ops4.test', verified: false },
  fredVerified: { username: 'iv-fred', email: 'fred@ops4.test', verified: true },
}

async function setUp() {
  for (const realm of [APP, IDP]) await admin('DELETE', `/${realm}`, undefined, [404])
  await admin('POST', '', {
    realm: IDP, enabled: true, displayName: 'Provider stand-in', duplicateEmailsAllowed: true,
    clients: [{
      clientId: 'ops4-broker', enabled: true, publicClient: false, secret: 'ops4-broker-secret', standardFlowEnabled: true,
      redirectUris: [`${KEYCLOAK}/realms/${APP}/broker/dev-idp/endpoint`],
    }],
    users: Object.values(IDP_USERS).map((u) => ({
      username: u.username, email: u.email, emailVerified: u.verified, enabled: true, firstName: 'Pat', lastName: 'Provider',
      credentials: [{ type: 'password', value: PASSWORD, temporary: false }],
    })),
  })
  await admin('PUT', `/${IDP}/authentication/required-actions/VERIFY_PROFILE`, {
    alias: 'VERIFY_PROFILE', name: 'Verify Profile', providerId: 'VERIFY_PROFILE', enabled: false, defaultAction: false,
  })
  await admin('POST', '', {
    realm: APP, enabled: true, displayName: 'Finance Tracker',
    loginTheme: 'finance-tracker', accountTheme: 'finance-tracker', emailTheme: 'finance-tracker',
    registrationAllowed: true, registrationEmailAsUsername: true, verifyEmail: true, resetPasswordAllowed: true,
    loginWithEmailAllowed: true, duplicateEmailsAllowed: false, editUsernameAllowed: false, rememberMe: false,
    smtpServer: { host: SMTP_HOST, port: '1025', from: 'noreply@ops4.test', fromDisplayName: 'Finance Tracker' },
    clients: [{
      clientId: 'ops4-client', enabled: true, publicClient: false, secret: SECRET, standardFlowEnabled: true,
      redirectUris: [CALLBACK], attributes: { 'post.logout.redirect.uris': 'http://localhost:8099/bye' },
    }],
    users: [
      { username: 'eva@ops4.test', email: 'eva@ops4.test', firstName: 'Eva', lastName: 'Existing', emailVerified: true, enabled: true,
        credentials: [{ type: 'password', value: PASSWORD, temporary: false }] },
      { username: 'fred@ops4.test', email: 'fred@ops4.test', firstName: 'Fred', lastName: 'Existing', emailVerified: false, enabled: true,
        credentials: [{ type: 'password', value: PASSWORD, temporary: false }] },
    ],
    identityProviders: [{
      alias: 'dev-idp', providerId: 'oidc', displayName: 'Provider', enabled: true, trustEmail: false,
      config: {
        syncMode: 'IMPORT', clientId: 'ops4-broker', clientSecret: 'ops4-broker-secret', clientAuthMethod: 'client_secret_post',
        defaultScope: 'openid profile email',
        authorizationUrl: `${KEYCLOAK}/realms/${IDP}/protocol/openid-connect/auth`,
        tokenUrl: `${KEYCLOAK}/realms/${IDP}/protocol/openid-connect/token`,
        userInfoUrl: `${KEYCLOAK}/realms/${IDP}/protocol/openid-connect/userinfo`,
        logoutUrl: `${KEYCLOAK}/realms/${IDP}/protocol/openid-connect/logout`,
        issuer: `${KEYCLOAK}/realms/${IDP}`, validateSignature: 'false', pkceEnabled: 'false',
      },
    }],
  })
}

async function setTrust(trust: boolean, logoutUrl = true) {
  const idp = await admin('GET', `/${APP}/identity-provider/instances/dev-idp`)
  idp.trustEmail = trust
  // Google and GitHub in production have no logout URL; the stand-in has one unless a case says otherwise.
  if (logoutUrl) idp.config.logoutUrl = `${KEYCLOAK}/realms/${IDP}/protocol/openid-connect/logout`
  else delete idp.config.logoutUrl
  await admin('PUT', `/${APP}/identity-provider/instances/dev-idp`, idp)
}

async function mails(to: string): Promise<{ id: string; subject: string }[]> {
  const list = await (await fetch(`${MAILPIT}/api/v1/messages`)).json() as
    { messages: { ID: string; Subject: string; To: { Address: string }[] }[] }
  return list.messages.filter((m) => m.To.some((t) => t.Address === to)).map((m) => ({ id: m.ID, subject: m.Subject }))
}

async function mailLink(id: string): Promise<string> {
  const message = await (await fetch(`${MAILPIT}/api/v1/message/${id}`)).json() as { Text: string }
  const link = message.Text.match(/https?:\/\/\S+/)?.[0]
  if (!link) throw new Error('No link in the email')
  return link
}

async function waitForMail(to: string, subject: RegExp, seconds = 15) {
  for (let i = 0; i < seconds * 2; i++) {
    const found = (await mails(to)).find((m) => subject.test(m.subject))
    if (found) return found
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  return undefined
}

const heading = async (page: Page) =>
  ((await page.locator('#kc-page-title, h1').first().innerText({ timeout: 3000 }).catch(() => '')) ?? '').replace(/\s+/g, ' ').trim()

interface CaseResult {
  name: string
  idpUser: string
  idpEmailVerified: boolean
  existing: string
  trustEmail: boolean
  trail: string[]
  outcome: string
  appUser: { emailVerified: boolean; requiredActions: string[]; linked: string[] } | null
  emailsToAddress: string[]
  note?: string
}

const browser = await chromium.launch({ channel: 'chrome' })
const results: CaseResult[] = []

async function runCase(name: string, idp: IdpUser, existing: 'none' | 'eva' | 'fred', trust: boolean, signOut = false, logoutUrl = true) {
  await fetch(`${MAILPIT}/api/v1/messages`, { method: 'DELETE' })
  await setTrust(trust, logoutUrl)
  const context = await browser.newContext({ locale: 'en-US', viewport: { width: 1280, height: 900 } })
  let callback: string | undefined
  const page = await context.newPage()
  // Nothing listens on port 8099: the redirect back to the client fails, and the failed request is the callback.
  page.on('requestfailed', (request) => {
    if (request.url().startsWith(CALLBACK)) callback ??= request.url()
  })
  const trail: string[] = []
  const result: CaseResult = {
    name, idpUser: idp.username, idpEmailVerified: idp.verified, existing, trustEmail: trust, trail, outcome: '', appUser: null, emailsToAddress: [],
  }
  try {
    await page.goto(`${KEYCLOAK}/realms/${APP}/protocol/openid-connect/auth?client_id=ops4-client&redirect_uri=${encodeURIComponent(CALLBACK)}` +
      `&response_type=code&scope=openid&state=s&nonce=n&kc_idp_hint=dev-idp`)
    await page.locator('#username').fill(idp.username)
    await page.locator('#password').fill(PASSWORD)
    await page.locator('#kc-login').click()
    for (let step = 0; step < 10 && !callback; step++) {
      await page.waitForLoadState('domcontentloaded').catch(() => {})
      await page.waitForTimeout(600)
      if (callback || page.url().startsWith('chrome-error')) break
      const title = await heading(page)
      trail.push(title || page.url().slice(0, 80))
      if (/already exists/i.test(title)) {
        await page.locator('#linkAccount, a:has-text("Add to existing account")').first().click()
      } else if (/verify existing account|link provider/i.test(title)) {
        const mail = await waitForMail(idp.email, /verify existing account|link provider/i)
        if (!mail) { result.outcome = 'no email to verify the existing account'; break }
        const link = await mailLink(mail.id)
        trail.push(`link to ${new URL(link).origin}`)
        await page.goto(link).catch(() => {}) // after the link the browser goes on to the client, which isn't listening
      } else if (/confirm linking|link/i.test(title) && (await page.getByText(/click here to proceed/i).count())) {
        await page.getByText(/click here to proceed/i).first().click()
      } else if (/review profile|update account/i.test(title)) {
        for (const field of ['email', 'firstName', 'lastName']) {
          const input = page.locator(`#${field}`)
          if (await input.count() && !(await input.inputValue())) await input.fill(field === 'email' ? idp.email : 'Pat')
        }
        await page.locator('#kc-login, [type=submit]').first().click()
      } else if (/verify|email/i.test(title)) {
        result.outcome = `stopped at "${title}"`
        break
      } else if (/proceed|continue|confirm/i.test(title) || (await page.getByText(/click here to proceed/i).count())) {
        await page.getByText(/click here to proceed/i).first().click().catch(() => {})
      } else {
        result.outcome = `unexpected page "${title}": ${((await page.locator('body').innerText().catch(() => '')) ?? '').replace(/\s+/g, ' ').slice(0, 200)}`
        break
      }
    }
    if (callback) result.outcome = 'signed in (the redirect to the client was reached)'
    else if (!result.outcome) result.outcome = 'no callback'

    const email = existing === 'none' ? idp.email : `${existing}@ops4.test`
    const users = await admin('GET', `/${APP}/users?email=${encodeURIComponent(email)}&exact=true`) as
      { id: string; emailVerified: boolean; requiredActions: string[] }[]
    if (users.length) {
      const identities = await admin('GET', `/${APP}/users/${users[0].id}/federated-identity`) as { identityProvider: string }[]
      result.appUser = { emailVerified: users[0].emailVerified, requiredActions: users[0].requiredActions, linked: identities.map((i) => i.identityProvider) }
    }
    result.emailsToAddress = (await mails(email)).map((m) => m.subject)

    if (signOut && callback) {
      const code = new URL(callback).searchParams.get('code')!
      const tokens = await (await fetch(`${KEYCLOAK}/realms/${APP}/protocol/openid-connect/token`, {
        method: 'POST',
        body: new URLSearchParams({ grant_type: 'authorization_code', code, client_id: 'ops4-client', client_secret: SECRET, redirect_uri: CALLBACK }),
      })).json() as { id_token?: string; refresh_expires_in?: number }
      const idpUsers = await admin('GET', `/${IDP}/users?username=${idp.username}&exact=true`) as { id: string }[]
      const before = (await admin('GET', `/${IDP}/users/${idpUsers[0].id}/sessions`) as unknown[]).length
      let bye: string | undefined
      page.on('requestfailed', (request) => {
        if (request.url().startsWith('http://localhost:8099/bye')) bye = request.url()
      })
      await page.goto(`${KEYCLOAK}/realms/${APP}/protocol/openid-connect/logout?id_token_hint=${tokens.id_token}` +
        `&post_logout_redirect_uri=${encodeURIComponent('http://localhost:8099/bye')}&state=bye`).catch(() => {})
      await page.waitForTimeout(1500)
      const after = (await admin('GET', `/${IDP}/users/${idpUsers[0].id}/sessions`) as unknown[]).length
      result.note = `provider sessions of ${idp.username}: ${before} before the app realm's logout, ${after} after; ` +
        `the browser ${bye ? 'was sent back to the client (post_logout_redirect_uri)' : `ended at ${page.url().replace(/[?#].*/, '')}`}`
    }
  } catch (error) {
    result.outcome = `error: ${(error as Error).message.slice(0, 200)}`
  }
  await context.close()
  // Back to the starting state: the new user deleted, an existing one unlinked and as it was.
  const email = existing === 'none' ? idp.email : `${existing}@ops4.test`
  for (const user of await admin('GET', `/${APP}/users?email=${encodeURIComponent(email)}&exact=true`) as { id: string }[]) {
    if (existing === 'none') await admin('DELETE', `/${APP}/users/${user.id}`)
    else {
      await admin('DELETE', `/${APP}/users/${user.id}/federated-identity/dev-idp`, undefined, [404])
      await admin('PUT', `/${APP}/users/${user.id}`, { emailVerified: existing === 'eva', requiredActions: [] })
    }
  }
  results.push(result)
  console.log(JSON.stringify(result))
}

try {
  await setUp()
  for (const trust of [true, false]) {
    await runCase(`new address, provider says verified, Trust Email ${trust ? 'on' : 'off'}`, IDP_USERS.newVerified, 'none', trust)
    await runCase(`new address, provider says unverified, Trust Email ${trust ? 'on' : 'off'}`, IDP_USERS.newUnverified, 'none', trust)
    await runCase(`existing verified user, provider says verified, Trust Email ${trust ? 'on' : 'off'}`, IDP_USERS.evaVerified, 'eva', trust)
    await runCase(`existing verified user, provider says unverified, Trust Email ${trust ? 'on' : 'off'}`, IDP_USERS.evaUnverified, 'eva', trust)
    await runCase(`existing unverified user, provider says verified, Trust Email ${trust ? 'on' : 'off'}`, IDP_USERS.fredVerified, 'fred', trust)
  }
  await runCase('sign-out: new address, verified, Trust Email on, then the app realm\'s browser logout (the provider has a logout URL)', IDP_USERS.newVerified, 'none', true, true)
  await runCase('sign-out: the same, the provider has no logout URL (as Google and GitHub in production)', IDP_USERS.newVerified, 'none', true, true, false)
} finally {
  writeFileSync(join(folder, 'brokering.json'), JSON.stringify(results, null, 1))
  for (const realm of [APP, IDP]) await admin('DELETE', `/${realm}`, undefined, [404]).catch(() => {})
  await browser.close()
}
