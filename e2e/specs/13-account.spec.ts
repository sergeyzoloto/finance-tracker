import { expect, test, userMenu } from '../fixtures.ts'

// UX-15 (D-619), A only: the user menu's Account, between Settings and Log out, opens Keycloak's account console in the
// same tab, in the realm's theme (the masthead's "Finance Tracker", the app's theme on <html>), signed in by the
// session the run's sign-in left; the console's "Back to Finance Tracker" returns to the app's base URL. Nothing is
// changed in the console.

test('account', async ({ as, target }) => {
  const { page } = await as('A')
  await page.goto('/accounts')
  await expect(page.getByRole('heading', { level: 2, name: 'Accounts' })).toBeVisible()
  const menu = await userMenu(page)
  await expect(menu.locator('li')).toHaveText(['Settings', 'Account', 'Log out'])
  const account = menu.getByRole('link', { name: 'Account', exact: true })
  await expect(account).toHaveAttribute('href', `${target.keycloak}/realms/myapps/account?referrer=finance-tracker`)
  await expect(account).not.toHaveAttribute('target', /./)

  await account.click()
  await page.waitForURL((url) => url.origin === target.keycloak && url.pathname.startsWith('/realms/myapps/account'))
  await expect(page.getByRole('heading', { level: 1, name: 'Personal info' })).toBeVisible({ timeout: 30_000 })
  await expect(page.locator('html')).toHaveAttribute('data-theme', /^(blue|light|dark)$/)
  // The wordmark is drawn by the theme's CSS in place of Keycloak's logo.
  expect(await page.locator('.pf-v5-c-masthead__brand').evaluate((a) => getComputedStyle(a, '::after').content))
    .toBe('"Finance Tracker"')

  const back = page.getByTestId('referrer-link')
  await expect(back).toHaveText('Back to Finance Tracker')
  await expect(back).toHaveAttribute('href', `${target.app}/`)
  await back.click()
  await page.waitForURL((url) => url.origin === target.app)
  expect(new URL(page.url()).pathname).toBe('/')
  await expect(page.getByRole('heading', { level: 2, name: 'Dashboard' })).toBeVisible({ timeout: 30_000 })
})
