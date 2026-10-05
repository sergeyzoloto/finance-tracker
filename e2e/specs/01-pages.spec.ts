import type { APIResponse } from '@playwright/test'
import { expect, test, type Session } from '../fixtures.ts'
import type { Target } from '../lib/targets.ts'

// The pages without signing in, as the deploy scripts' page checks list them (deploy/common.sh, PAGES) and the
// checklists' browser checks looked at them: the landing page, the privacy policy at both addresses, both icons. At
// 1280 and at 375 px, each page with a full-page screenshot.

/** The policy's family section, published word for word from docs/family-budget/privacy-draft.md (F6c). */
const PRIVACY_PHRASES = [
  'A family budget is a budget you keep with other people',
  'What other members of a family budget see',
  'What other members never see',
  'Invite links',
]

/**
 * Where the Vite dev server (local) and nginx behind Caddy (production) legitimately differ:
 * - Vite adds `charset=utf-8` to a static page's content type; nginx sends `text/html`.
 * - The icons: nginx keeps them a day (`web.conf`); Vite sends `no-cache` for everything.
 * - Caddy adds the security headers (`deploy/finance.caddy`); Vite has none.
 */
function expected(target: Target) {
  const nginx = target.server === 'nginx'
  return {
    pageType: nginx ? 'text/html' : 'text/html;charset=utf-8',
    iconCache: nginx ? 'public, max-age=86400' : 'no-cache',
    security: nginx,
  }
}

const SECURITY_HEADERS = ['strict-transport-security', 'content-security-policy', 'x-content-type-options', 'referrer-policy']

function expectHeaders(response: APIResponse, target: Target, type: string, cache: string) {
  expect(response.status()).toBe(200)
  expect(response.headers()['content-type']).toBe(type)
  expect(response.headers()['cache-control']).toBe(cache)
  if (expected(target).security) {
    for (const header of SECURITY_HEADERS) expect(response.headers()[header], header).toBeTruthy()
    expect(response.headers()['x-content-type-options']).toBe('nosniff')
  }
}

for (const width of [1280, 375]) {
  test(`pages at ${width} px`, async ({ anonymous, watch, target }, testInfo) => {
    const session = await anonymous()
    const { page, context } = session
    await page.setViewportSize({ width, height: width === 375 ? 812 : 800 })
    const shot = (name: string) => page.screenshot({ path: testInfo.outputPath(`${width}-${name}.png`), fullPage: true })

    // The landing page asks who is signed in: nobody.
    watch.expect({ method: 'GET', path: /^\/api\/me$/, status: 401, reason: '/api/me before sign-in' })
    const landing = await page.goto('/')
    expect(landing?.status()).toBe(200)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Your money, kept like a proper set of books')
    await expect(page.getByRole('link', { name: 'Privacy policy' })).toBeVisible()
    await expectNoSideScroll(session, 'the landing page')
    await shot('landing')

    for (const path of ['/privacy', '/privacy.html']) {
      const response = await page.goto(path)
      expect(response?.status(), path).toBe(200)
      expect(response?.headers()['content-type'], path).toBe(expected(target).pageType)
      await expect(page).toHaveTitle('Privacy policy · Finance Tracker')
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Privacy policy')
      await expect(page.getByRole('heading', { level: 2, name: 'Family budgets' })).toBeVisible()
      for (const phrase of PRIVACY_PHRASES) await expect(page.getByText(phrase, { exact: false }).first()).toBeVisible()
      await expectNoSideScroll(session, path)
      await shot(path.slice(1).replace('.', '-'))
    }

    // The same pages as plain requests, for their headers.
    const exp = expected(target)
    expectHeaders(await context.request.get('/'), target, 'text/html', 'no-cache')
    expectHeaders(await context.request.get('/privacy'), target, exp.pageType, 'no-cache')
    expectHeaders(await context.request.get('/privacy.html'), target, exp.pageType, 'no-cache')
    const svg = await context.request.get('/favicon.svg')
    expectHeaders(svg, target, 'image/svg+xml', exp.iconCache)
    expect((await svg.text()).trimStart()).toMatch(/^<svg/)
    const ico = await context.request.get('/favicon.ico')
    expectHeaders(ico, target, 'image/x-icon', exp.iconCache)
    // An ICO file: reserved 0, type 1 (an icon).
    expect([...(await ico.body()).subarray(0, 4)]).toEqual([0, 0, 1, 0])
  })
}

/** Nothing on the page is wider than the window: no horizontal scroll at 375 px. */
async function expectNoSideScroll({ page }: Session, what: string) {
  const { scroll, client } = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
  expect(scroll, `${what}: page width ${scroll} px in a window of ${client} px`).toBeLessThanOrEqual(client)
}
