import type { Page } from '@playwright/test'
import { deleteAllMyData, expect, loadDemoData, mainMenu, openSettings, shot, test, userMenu, type Session } from '../fixtures.ts'
import type { Target } from '../lib/targets.ts'

// UX-10's themes (D-495 to D-499), A only, on the demo:
// 1. At 1280 px each theme chosen through Settings → Appearance gives `html[data-theme]` and the body's background
//    equal to the theme's --bg, kept after a reload and on another page. Since UX-15 (D-614, D-617) also the cookie
//    ft_theme with that value and its domain (the app's parent domain in production, host-only locally), and
//    Keycloak's sign-in page, opened with prompt=login and nothing submitted, showing that theme as html[data-theme]
//    and "Finance Tracker" as its header.
// 2. The header has no theme control, at 1280 px and at 375 px, the user's menu open included.
// 3. In a fresh context with no choice, a dark device gives Blue and a light one Light.
// 4. In each theme at 375 px nothing scrolls sideways (a screenshot of each in local-narrow): Dashboard, Entries, New
//    entry, Accounts, Categories, Settings → Appearance and, with E2E_FAMILY=on, the demo budget's Overview.
// Delete all my data at the end. The choice lives in the browser only (D-497): no request changes it.

/** Each theme's --bg (D-496), as the body's computed background reads it. */
const THEMES = [
  { name: 'Blue', value: 'blue', bg: 'rgb(24, 33, 47)' },
  { name: 'Light', value: 'light', bg: 'rgb(244, 246, 249)' },
  { name: 'Dark', value: 'dark', bg: 'rgb(23, 24, 27)' },
] as const

const PAGES = [
  ['dashboard', '/'], ['entries', '/entries'], ['new-entry', '/entries/new'], ['accounts', '/accounts'],
  ['categories', '/categories'], ['appearance', '/settings/appearance'],
] as const

test('themes', async ({ as, family, target }) => {
  const a = await as('A')
  await deleteAllMyData(a)
  const { page } = a
  await loadTheDemo(page, family === 'on')
  const budget = family === 'on' ? await budgetPath(page) : null

  // 1. Each theme through Settings → Appearance at 1280 px, kept after a reload and on another page.
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/')
  await openSettings(page, 'Appearance')
  for (const theme of THEMES) {
    const choice = page.getByRole('radiogroup', { name: 'Theme' }).getByRole('radio', { name: new RegExp(`^${theme.name}`) })
    await choice.check()
    await expectTheme(page, theme)
    await expectCookieAndSignInPage(a, target, theme.value)
    await page.goto('/')
    await openSettings(page, 'Appearance')
    await page.reload()
    await expect(page.getByRole('radiogroup', { name: 'Theme' }).getByRole('radio', { name: new RegExp(`^${theme.name}`) })).toBeChecked()
    await expectTheme(page, theme)
    await (await mainMenu(page)).getByRole('link', { name: 'Accounts', exact: true }).click()
    await expect(page.getByRole('heading', { level: 2, name: 'Accounts' })).toBeVisible()
    await expectTheme(page, theme)
    await openSettings(page, 'Appearance')
  }

  // 2. No theme control in the header, at either width.
  for (const width of [1280, 375]) {
    await page.setViewportSize({ width, height: width === 375 ? 812 : 900 })
    await page.goto('/accounts')
    await expect(page.getByRole('heading', { level: 2, name: 'Accounts' })).toBeVisible()
    await userMenu(page)
    const header = page.locator('header')
    await expect(header.getByRole('radio')).toHaveCount(0)
    await expect(header.getByRole('combobox')).toHaveCount(0)
    await expect(header.getByText(/\b(theme|appearance|blue|light|dark)\b/i)).toHaveCount(0)
    await page.keyboard.press('Escape')
  }

  // 3. A fresh context, nothing chosen: the device decides.
  for (const [scheme, theme] of [['dark', THEMES[0]], ['light', THEMES[1]]] as const) {
    const fresh = await as('A')
    await fresh.page.emulateMedia({ colorScheme: scheme })
    await fresh.page.goto('/accounts')
    await expect(fresh.page.getByRole('heading', { level: 2, name: 'Accounts' })).toBeVisible()
    expect(await fresh.page.evaluate(() => localStorage.getItem('finance-tracker.theme')), 'nothing chosen').toBeNull()
    await expectTheme(fresh.page, theme)
    await fresh.context.close()
  }

  // 4. Each theme at 375 px: nothing scrolls sideways.
  await page.setViewportSize({ width: 375, height: 812 })
  for (const theme of THEMES) {
    await page.evaluate((value) => localStorage.setItem('finance-tracker.theme', value), theme.value)
    const pages: (readonly [string, string])[] = budget ? [...PAGES, ['family-overview', budget]] : [...PAGES]
    for (const [name, path] of pages) {
      await page.goto(path)
      await expect(page.getByRole('heading', { level: 2 }).first()).toBeVisible()
      await page.waitForLoadState('networkidle')
      await expectTheme(page, theme)
      await expectNoSideScroll(a, `${theme.name} ${path}`)
      await shot(page, `themes-${theme.value}-${name}`)
    }
  }

  await page.evaluate(() => localStorage.removeItem('finance-tracker.theme'))
  await page.setViewportSize({ width: 1280, height: 900 })
  await deleteAllMyData(a)
})

/**
 * D-617: the cookie the choice wrote, its value and domain; D-614, D-618: Keycloak's sign-in page in the same browser
 * shows that theme and the realm's name. The page is opened with prompt=login (and PKCE, which the client requires)
 * and left without submitting anything.
 */
async function expectCookieAndSignInPage({ page, context }: Session, target: Target, value: string) {
  const cookies = (await context.cookies(target.app)).filter((c) => c.name === 'ft_theme')
  expect(cookies.map((c) => c.value), 'the theme cookie').toEqual([value])
  const appHost = new URL(target.app).hostname
  expect(cookies[0].domain, 'its domain').toBe(target.name === 'prod' ? `.${appHost.split('.').slice(1).join('.')}` : appHost)
  expect(cookies[0].sameSite).toBe('Lax')
  expect(cookies[0].secure).toBe(target.app.startsWith('https:'))
  const me = await (await page.request.get('/api/me')).json() as { accountUrl?: string }
  expect(me.accountUrl, '/api/me names the account console').toMatch(/\/account\?referrer=finance-tracker$/)
  const realm = me.accountUrl!.replace(/\/account\?.*$/, '')
  expect(new URL(realm).origin).toBe(target.keycloak)
  const signIn = new URL(`${realm}/protocol/openid-connect/auth`)
  for (const [k, v] of Object.entries({
    client_id: 'finance-tracker', response_type: 'code', scope: 'openid', prompt: 'login',
    redirect_uri: `${target.app}/login/oauth2/code/keycloak`,
    code_challenge: 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM', code_challenge_method: 'S256',
  })) signIn.searchParams.set(k, v)
  await page.goto(signIn.toString())
  await expect(page.locator('#kc-login')).toBeVisible()
  await expect(page.locator('html')).toHaveAttribute('data-theme', value)
  await expect(page.locator('#kc-header-wrapper')).toHaveText('Finance Tracker')
}

async function expectTheme(page: Page, theme: (typeof THEMES)[number]) {
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme.value)
  await expect.poll(() => page.evaluate(() => getComputedStyle(document.body).backgroundColor), `${theme.name}'s --bg`)
    .toBe(theme.bg)
}

async function loadTheDemo(page: Page, withFamily: boolean) {
  await page.goto('/')
  await expect(page.getByText('It also creates the family budget “Demo household”')).toHaveCount(withFamily ? 1 : 0)
  await loadDemoData(page)
  await expect(page.getByText('Your ledger is empty. How would you like to start?')).toHaveCount(0, { timeout: 30_000 })
  await expect(page.getByRole('heading', { name: 'Cash flow by category' })).toBeVisible()
}

/** The demo budget's Overview, from the header's main navigation. */
async function budgetPath(page: Page): Promise<string> {
  const link = (await mainMenu(page)).getByRole('link', { name: 'Demo household', exact: true })
  await expect(link).toHaveCount(1)
  return (await link.getAttribute('href'))!
}

async function expectNoSideScroll({ page }: Session, what: string) {
  const { scroll, client } = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth,
  }))
  expect(scroll, `${what}: page width ${scroll} px in a window of ${client} px`).toBeLessThanOrEqual(client)
}
