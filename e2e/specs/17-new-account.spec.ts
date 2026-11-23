import { deleteAllMyData, expect, loadDemoData, test } from '../fixtures.ts'

// The Accounts page's "New account" form (D-1148), with A, on the demo:
// 1. "New account" opens the form; a blank name is refused on the page, nothing is sent.
// 2. "Test savings" with the default type creates the account (its code made from the name): the form closes and the
//    account is listed under Assets, and still after a reload.
// Delete all my data at the end.

test.beforeEach(({ target }) => {
  // D-1149: this spec creates an account on the demo; production's accounts are the owner's, so it runs locally (and on the test target) only.
  test.skip(target.name === 'prod', 'The new-account spec creates an account; production runs it never (D-1149).')
})

test('a new account from the Accounts page', async ({ as }) => {
  const a = await as('A')
  const { page } = a
  await deleteAllMyData(a)
  await page.goto('/')
  await loadDemoData(page)
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible({ timeout: 30_000 })

  await page.goto('/accounts')
  await page.getByRole('button', { name: 'New account' }).click()
  await page.getByRole('textbox', { name: 'Account name' }).fill('   ')
  await page.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText('Enter a name for the account.')).toBeVisible()

  await page.getByRole('textbox', { name: 'Account name' }).fill('Test savings')
  const created = page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/accounts')
  await page.getByRole('button', { name: 'Create' }).click()
  expect((await created).ok()).toBe(true)
  await expect(page.getByRole('textbox', { name: 'Account name' })).toHaveCount(0)

  const assets = page.getByRole('rowgroup', { name: 'Assets', exact: true })
  await expect(assets.getByRole('link', { name: 'Test savings', exact: true })).toBeVisible()
  await page.reload()
  await expect(assets.getByRole('link', { name: 'Test savings', exact: true })).toBeVisible()

  await deleteAllMyData(a)
})
