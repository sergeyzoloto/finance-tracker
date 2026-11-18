import { expect, test } from '../fixtures.ts'

// WEB-1 (D-980, D-981, D-982): finance-nl.com serves the landing page, sends a browser with the sign-in hint on to the
// app, and sends every other path on to the app's host; www goes to the apex. Production only, and only once the owner
// says the DNS is live (E2E_APEX_LIVE=1): until then the apex doesn't resolve to the server, and the spec skips. It
// signs in nowhere and touches no account.

const APEX = 'https://finance-nl.com'
const APP = 'https://app.finance-nl.com'

test.beforeEach(({ target }) => {
  test.skip(target.name !== 'prod', 'The apex is production’s only.')
  test.skip(process.env.E2E_APEX_LIVE !== '1', 'The apex’s DNS isn’t live yet (set E2E_APEX_LIVE=1 once it is).')
})

test('the apex shows the landing page, and its sign-in goes to the app', async ({ anonymous }) => {
  const { page, context } = await anonymous()
  const apiCalls: string[] = []
  context.on('request', (request) => { if (new URL(request.url()).pathname.startsWith('/api/')) apiCalls.push(request.url()) })
  await page.goto(`${APEX}/`)
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  expect(page.url()).toBe(`${APEX}/`)
  for (const link of await page.getByRole('link', { name: /Sign in/ }).all()) {
    expect(await link.getAttribute('href')).toBe(`${APP}/oauth2/authorization/keycloak`)
  }
  await expect(page.getByRole('link', { name: 'Privacy policy' })).toHaveAttribute('href', '/privacy')
  await expect(page.getByRole('link', { name: 'Sergey Zolotko' })).toHaveAttribute('href', 'https://cv-zoloto.pages.dev/')
  expect(apiCalls, 'the apex has no backend and asks for none').toEqual([])
  const privacy = await context.request.get(`${APEX}/privacy`)
  expect(privacy.status()).toBe(200)
})

test('the apex sends a browser with the sign-in hint to the app', async ({ anonymous }) => {
  const { page, context } = await anonymous()
  await context.addCookies([{ name: 'ft_session', value: '1', domain: 'finance-nl.com', path: '/', secure: true, sameSite: 'Lax' }])
  // The app's own page is stubbed: this spec looks at the redirect, not at the app.
  await page.route(`${APP}/**`, (route) => route.fulfill({ status: 200, contentType: 'text/html', body: '<title>app</title>' }))
  const requested = page.waitForRequest(`${APP}/`)
  await page.goto(`${APEX}/`)
  await requested
  expect(page.url()).toBe(`${APP}/`)
})

test('every other path of the apex, and www, are sent on with a 308', async ({ anonymous }) => {
  const { context } = await anonymous()
  for (const path of ['/entries?page=2', '/api/me', '/oauth2/authorization/keycloak', '/logout']) {
    const response = await context.request.get(`${APEX}${path}`, { maxRedirects: 0 })
    expect(response.status(), path).toBe(308)
    expect(response.headers().location, path).toBe(`${APP}${path}`)
  }
  const www = await context.request.get('https://www.finance-nl.com/entries?x=1', { maxRedirects: 0 })
  expect(www.status()).toBe(308)
  expect(www.headers().location).toBe(`${APEX}/entries?x=1`)
})
