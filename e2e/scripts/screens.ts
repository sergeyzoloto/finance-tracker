import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { chromium, type BrowserContext, type Page } from '@playwright/test'
import { LOCAL_PASSWORD } from '../lib/accounts.ts'
import { LOCAL, requireLocalOnly } from '../lib/targets.ts'

// npm run screens:local -- <folder> <label> [blue|light|dark]: screenshots of the app's main screens from the demo
// ledger, for a stage's review (UX-10, section 3). Local only: it refuses any host but localhost, and e2e:prod never
// runs it. It signs in testuser, deletes all his data, loads the demo, takes each screen at 1280 and 375 px, and
// deletes his data again. With a theme it is chosen in the browser before the first page (D-497's key); without one
// the browser is light, as the app looked before UX-10. Since UX-11 also Entries for a month, a family share whose
// payee comes from its record (a family expense with a payee added to the demo's budget), and Accounts compared.
// Since UX-12 also testuser2 in the demo's budget, paying with a payee he shows (D-547): Entries filtered by that
// family payee, and testuser's record page with a new name typed in its payee field (D-549); testuser2's data is
// deleted at the end too. Since UX-13 also the dashboard for a past month, in each currency, and with a family scope
// (the headline strip, D-569, and daily spending, D-571), and Entries filtered by a counterparty's name in another
// case (D-568). SCREENS=dashboard takes the dashboard's screens alone (the before shots of another commit).

requireLocalOnly(LOCAL)
const [folder, label, theme] = process.argv.slice(2)
if (!folder || !label || (theme !== undefined && !['blue', 'light', 'dark'].includes(theme))) {
  console.error('Usage: npm run screens:local -- <folder> <label> [blue|light|dark]')
  process.exit(1)
}
mkdirSync(folder, { recursive: true })

const WIDTHS = [1280, 375] as const
const ONLY_DASHBOARD = process.env.SCREENS === 'dashboard'
const PAYEE = 'Corner shop'
const PARTNERS_PAYEE = 'Trefpunt Coffeeshop'
const account = LOCAL.roles.A
const partner = LOCAL.roles.B

const browser = await chromium.launch({ channel: 'chrome' })
try {
  const state = await signIn(account.login)
  const partnerState = await signIn(partner.login)
  const setup = await browser.newContext({ baseURL: LOCAL.app, storageState: state })
  const partnerContext = await browser.newContext({ baseURL: LOCAL.app, storageState: partnerState })
  await deleteAll(partnerContext)
  const familyId = await demo(setup)
  const paid = familyId ? await shareWithAPayee(setup, familyId) : undefined
  const share = paid?.share
  const partnersPayee = familyId ? await partnerShowsAPayee(setup, partnerContext, familyId) : false
  await setup.close()
  await partnerContext.close()

  for (const width of WIDTHS) {
    const context = await browser.newContext({
      baseURL: LOCAL.app, storageState: state, locale: 'en-US', timezoneId: 'UTC', colorScheme: 'light',
      viewport: { width, height: width === 375 ? 812 : 900 },
    })
    if (theme) await context.addInitScript((t) => { localStorage.setItem('finance-tracker.theme', t) }, theme)
    const page = await context.newPage()
    const take = async (name: string, path: string, then?: (page: Page) => Promise<void>) => {
      await page.goto(path)
      await page.waitForLoadState('networkidle')
      await page.waitForTimeout(400)
      if (then) await then(page)
      await page.screenshot({ path: join(folder, `${label}-${width}-${name}.png`), fullPage: true })
    }
    await take('dashboard', '/')
    // UX-13 (D-569, D-571): a past month, each currency, and a family scope with its records.
    await take('dashboard-past-month', '/?period=previous-month')
    await take('dashboard-each', '/?currency=each')
    await take('dashboard-past-month-each', '/?period=previous-month&currency=each')
    if (familyId) await take('dashboard-family', `/?scope=family-mine&family=${familyId}`)
    if (ONLY_DASHBOARD) {
      await context.close()
      continue
    }
    await take('dashboard-user-menu', '/', async (p) => {
      await p.locator('header .user-menu button.menu-button').click()
      await p.waitForTimeout(200)
    })
    await take('entries', '/entries')
    await take('entries-month', '/entries?period=previous-month')
    if (share) {
      await take('entries-record-payee', `/entries?q=${encodeURIComponent(PAYEE)}`)
      await take('entry-record-payee', `/entries/${share}`)
    }
    // UX-13 (D-568): a counterparty's name in another case, chosen in "Payee or person" once.
    if (paid) await take('entries-payee-case', `/entries?payee=${encodeURIComponent(PAYEE.toUpperCase())}`)
    if (partnersPayee) {
      // UX-12 (D-547): the list closed, the chosen family payee shown in it, with the summary row.
      await take('entries-family-payee', `/entries?payee=${encodeURIComponent(PARTNERS_PAYEE)}`)
    }
    if (familyId && paid) {
      // UX-12 (D-549): a name no counterparty of testuser's has, typed in the record page's payee field.
      await take('record-payee-field', `/family/${familyId}/expenses/${paid.recordId}`, async (p) => {
        await p.getByLabel(/^Payee/).fill('New café UX12')
        await p.getByText('New payee: it is added when you save.').scrollIntoViewIfNeeded()
        await p.waitForTimeout(200)
      })
    }
    await take('new-entry', '/entries/new')
    await take('accounts', '/accounts')
    await take('accounts-compared', '/accounts', async (p) => {
      await p.getByRole('button', { name: 'Compare', exact: true }).click()
      await p.waitForLoadState('networkidle')
      await p.waitForTimeout(300)
    })
    await take('categories', '/categories')
    // Before UX-10 there is no Appearance: Settings' first part stands in for it.
    await take('settings-appearance', theme ? '/settings/appearance' : '/settings/time-zone')
    if (familyId) await take('family-overview', `/family/${familyId}`)
    await context.close()

    // The landing page, signed out.
    const visitor = await browser.newContext({
      baseURL: LOCAL.app, locale: 'en-US', colorScheme: 'light', viewport: { width, height: width === 375 ? 812 : 900 },
    })
    if (theme) await visitor.addInitScript((t) => { localStorage.setItem('finance-tracker.theme', t) }, theme)
    const landing = await visitor.newPage()
    await landing.goto('/')
    await landing.waitForLoadState('networkidle')
    await landing.screenshot({ path: join(folder, `${label}-${width}-landing.png`), fullPage: true })
    await visitor.close()
  }

  const cleanup = await browser.newContext({ baseURL: LOCAL.app, storageState: state })
  await deleteAll(cleanup)
  await cleanup.close()
  const partnerCleanup = await browser.newContext({ baseURL: LOCAL.app, storageState: partnerState })
  await deleteAll(partnerCleanup)
  await partnerCleanup.close()
} finally {
  await browser.close()
}

async function signIn(login: string) {
  const context = await browser.newContext({ baseURL: LOCAL.app, locale: 'en-US', timezoneId: 'UTC' })
  const page = await context.newPage()
  await page.goto('/oauth2/authorization/keycloak')
  await page.locator('#username').fill(login)
  await page.locator('#password').evaluate((input, value) => { (input as HTMLInputElement).value = value }, LOCAL_PASSWORD)
  await page.locator('#kc-login').click()
  await page.waitForURL((url) => url.origin === LOCAL.app, { timeout: 30_000 })
  await page.waitForResponse((r) => r.url() === `${LOCAL.app}/api/me`).catch(() => undefined)
  const state = await context.storageState()
  await context.close()
  return state
}

async function xsrf(context: BrowserContext) {
  const page = await context.newPage()
  await page.goto('/')
  await page.waitForLoadState('networkidle')
  await page.close()
  const cookie = (await context.cookies()).find((c) => c.name === 'XSRF-TOKEN')
  if (!cookie) throw new Error('No XSRF-TOKEN cookie.')
  return { 'X-XSRF-TOKEN': cookie.value }
}

async function deleteAll(context: BrowserContext) {
  const response = await context.request.delete('/api/me/data', { headers: await xsrf(context) })
  if (response.status() !== 204) throw new Error(`DELETE /api/me/data answered ${response.status()}`)
}

/** All of testuser's data deleted, then the demo loaded; the demo family budget's id, if the switch made one. */
async function demo(context: BrowserContext): Promise<number | undefined> {
  await deleteAll(context)
  const response = await context.request.post('/api/demo-data', { headers: await xsrf(context) })
  if (response.status() >= 300) throw new Error(`POST /api/demo-data answered ${response.status()}`)
  const body = await response.json().catch(() => ({})) as { familyLedgerId?: number }
  return body.familyLedgerId
}

/**
 * UX-11 (D-523): a family expense of testuser's in the demo's budget, paid from his current account with a payee, so
 * that his share names its record's payee; the share's entry id.
 */
async function shareWithAPayee(context: BrowserContext, familyId: number): Promise<{ share?: number; recordId: number }> {
  const headers = await xsrf(context)
  const get = async <T,>(path: string) => (await (await context.request.get(path)).json()) as T
  const budget = await get<{ memberId: number }>(`/api/family-ledgers/${familyId}`)
  const categories = await get<{ id: number; type: string; archived: boolean }[]>(`/api/family-ledgers/${familyId}/categories`)
  const accounts = await get<{ id: number; code: string }[]>('/api/accounts')
  const payee = await context.request.post('/api/counterparties', { headers, data: { name: PAYEE } })
  const me = await get<{ today: string }>('/api/me')
  const record = await context.request.post(`/api/family-ledgers/${familyId}/records`, {
    headers,
    data: {
      date: me.today, categoryId: categories.find((c) => c.type === 'EXPENSE' && !c.archived)!.id, amount: '24.80',
      payerMemberId: budget.memberId, paymentAccountId: accounts.find((a) => a.code === 'CURRENT_ACCOUNT')!.id,
      payeeId: ((await payee.json()) as { id: number }).id,
    },
  })
  if (record.status() !== 201) throw new Error(`POST …/records answered ${record.status()}`)
  const entries = await get<{ content: { id: number; family: { link: string } | null }[] }>(
    `/api/entries?q=${encodeURIComponent(PAYEE)}`)
  return {
    share: entries.content.find((e) => e.family?.link === 'SHARE')?.id,
    recordId: ((await record.json()) as { id: number }).id,
  }
}

/**
 * UX-12 (D-547): testuser2 joins the demo's budget through an invite, pays a family expense there with a payee of his
 * own and shows his payees, so that testuser's share names a payee no counterparty of his has. True once done.
 */
async function partnerShowsAPayee(owner: BrowserContext, context: BrowserContext, familyId: number): Promise<boolean> {
  const invite = await owner.request.post(`/api/family-ledgers/${familyId}/invites`, {
    headers: await xsrf(owner), data: { kind: 'NEW_MEMBER' },
  })
  if (invite.status() !== 201) throw new Error(`POST …/invites answered ${invite.status()}`)
  const token = ((await invite.json()) as { link: string }).link.split('#')[1]
  const headers = await xsrf(context)
  const accepted = await context.request.post('/api/invites/accept', {
    headers, data: { token, displayName: 'Sam’s friend', categoryIds: [] },
  })
  if (accepted.status() >= 300) throw new Error(`POST /api/invites/accept answered ${accepted.status()}`)
  const get = async <T,>(path: string) => (await (await context.request.get(path)).json()) as T
  const budget = await get<{ memberId: number }>(`/api/family-ledgers/${familyId}`)
  const categories = await get<{ id: number; type: string; archived: boolean }[]>(`/api/family-ledgers/${familyId}/categories`)
  const accounts = await get<{ id: number; code: string }[]>('/api/accounts')
  const payee = await context.request.post('/api/counterparties', { headers, data: { name: PARTNERS_PAYEE } })
  const me = await get<{ today: string }>('/api/me')
  const record = await context.request.post(`/api/family-ledgers/${familyId}/records`, {
    headers,
    data: {
      date: me.today, categoryId: categories.find((c) => c.type === 'EXPENSE' && !c.archived)!.id, amount: '7.40',
      payerMemberId: budget.memberId, paymentAccountId: accounts.find((a) => a.code === 'CURRENT_ACCOUNT')!.id,
      payeeId: ((await payee.json()) as { id: number }).id,
    },
  })
  if (record.status() !== 201) throw new Error(`testuser2's POST …/records answered ${record.status()}`)
  const shown = await context.request.patch(`/api/family-ledgers/${familyId}/me`, { headers, data: { showsPayees: true } })
  if (shown.status() !== 200) throw new Error(`PATCH …/me answered ${shown.status()}`)
  return true
}
