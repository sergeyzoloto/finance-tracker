import { test } from '../fixtures.ts'

// The guards' tests only (lib/guards.test.ts, target "test"): opens both accounts' saved sessions against the fake app,
// as a spec does, so that the tests see what a spec sends after the sign-in.

test('probe: both accounts from their saved sessions', async ({ as }) => {
  for (const role of ['A', 'B'] as const) {
    const { page } = await as(role)
    await page.goto('/')
    await page.getByText('fake app').waitFor()
  }
})
