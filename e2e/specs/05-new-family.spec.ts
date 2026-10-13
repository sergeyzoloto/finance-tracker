import { LOCAL_PASSWORD } from '../lib/accounts.ts'
import { deleteAllMyData, expect, ledgerIdOf, test } from '../fixtures.ts'

// D-163: "a member typed on /family/new right after a first sign-in is not created with the budget", reproduced as the
// owner met it, with A alone:
// 1. Delete all my data, so that A's next request is the app's first sight of A again: the starter ledger provisioned
//    anew and no time zone saved yet, which the first page load saves (D-101).
// 2. A fresh browser, no session: /family/new sends it to the login, where A signs in at Keycloak; back at the app, the
//    switcher's "New family budget…" at once, while the first load's requests (the zone, the family budgets) are still
//    on their way.
// 3. A name, a member without an account typed and added, Create: the budget's members are A and that member, in the
//    page and in the api.
// Local only: production's accounts sign in once per run, in the setup (D-52, guard 3), and their password never
// reaches a spec.

const BUDGET = 'First sign-in'
const MEMBER = 'Sam'

test('a new family budget right after a first sign-in', async ({ as, family, target, watch, browser }, testInfo) => {
  test.skip(family === 'off', 'The family budget is switched off (E2E_FAMILY=off).')
  test.skip(target.name !== 'local', 'A second sign-in needs the password, which only the local accounts have in the '
    + 'code (D-52, guard 3).')
  const a = await as('A')
  await deleteAllMyData(a)

  // 2. A browser without a session, as after a first sign-in.
  const { baseURL, locale, timezoneId, viewport } = testInfo.project.use
  const context = await browser.newContext({ baseURL, locale, timezoneId, viewport })
  watch.attach(context)
  watch.expect({ method: 'GET', path: /^\/api\/me$/, status: 401, reason: 'the app asks who is signed in before the login' })
  try {
    const page = await context.newPage()
    await page.goto('/family/new')
    await page.waitForURL((url) => url.origin === target.keycloak)
    await page.locator('#username').fill(a.account!.login)
    await page.locator('#password').evaluate((input, value) => { (input as HTMLInputElement).value = value }, LOCAL_PASSWORD)
    const me = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/me' && r.status() === 200)
    await page.locator('#kc-login').click()
    await page.waitForURL((url) => url.origin === target.app)
    await me
    // Straight to a new family budget, without waiting for anything else.
    if (new URL(page.url()).pathname !== '/family/new') {
      await page.getByRole('combobox', { name: 'Budget' }).selectOption({ label: 'New family budget…' })
    }
    await expect(page).toHaveURL(/\/family\/new$/)

    // 3. The budget with a member without an account.
    await page.getByLabel('Name', { exact: true }).fill(BUDGET)
    await page.getByLabel('Name of a member').fill(MEMBER)
    await page.getByRole('button', { name: 'Add member' }).click()
    await expect(page.getByRole('button', { name: `Remove ${MEMBER}` })).toBeVisible()
    await page.getByRole('button', { name: 'Create family budget' }).click()
    await expect(page.getByRole('heading', { level: 2, name: BUDGET })).toBeVisible()
    const ledgerId = ledgerIdOf(page)
    // No creation problem is listed on the budget's page.
    await expect(page.getByText(/wasn’t added/)).toHaveCount(0)

    const members = await context.request.get(`/api/family-ledgers/${ledgerId}/members`)
    expect(members.status()).toBe(200)
    const names = ((await members.json()) as { displayName: string; hasAccount: boolean }[])
      .map((m) => `${m.displayName} ${m.hasAccount ? 'with' : 'without'} an account`)
    expect(names).toContain(`${MEMBER} without an account`)
    expect(names).toHaveLength(2)
    await page.goto(`/family/${ledgerId}/members`)
    await expect(page.getByRole('row').filter({ hasText: MEMBER })).toHaveCount(1)

    // This browser's session ends here, at the app and at Keycloak.
    const xsrf = (await context.cookies()).find((c) => c.name === 'XSRF-TOKEN')
    expect((await context.request.post('/logout', { form: { _csrf: decodeURIComponent(xsrf?.value ?? '') } })).ok()).toBe(true)
  } finally {
    await context.close()
  }
  await deleteAllMyData(a)
})
