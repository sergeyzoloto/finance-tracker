import { deleteAllMyData, expect, ledgerIdOf, test, todayIn, type Session } from '../fixtures.ts'

// D-100, D-101: each user has a time zone, and the app's today is the date there, from /api/me. A browser in another
// zone than UTC (Playwright's timezoneId) must be on the same today as the api:
// 1. After Delete all my data, which takes the zone, the first load saves the browser's zone.
// 2. /api/me's today is the date in that zone at this moment, worked out here with Intl, and Settings says so.
// 3. (family budget on) A creates a family budget with no start date typed and adds an expense with the date the form
//    offers: the budget starts that day, and the expense, dated that day, is accepted. In Los Angeles in the evening
//    or at night that day is before UTC's, which is where an omitted start date used to be "tomorrow" (409, D-27).
// It runs for Los Angeles and for a zone whose date differs from UTC's at this moment whatever the hour, so that it
// tells the two apart at any time of day. Last of the specs: it leaves no zone behind (Delete all my data at its end).

/** The zone besides Los Angeles whose date at this moment isn't UTC's: UTC+14 from 10:00 UTC, UTC-12 before. */
const otherZone = () => (new Date().getUTCHours() >= 10 ? 'Pacific/Kiritimati' : 'Etc/GMT+12')

for (const zone of ['America/Los_Angeles', otherZone()]) {
  test(`time zone ${zone}`, async ({ as, family }) => {
    const a = await as('A', { timezoneId: zone })
    // Delete all my data takes the zone with it; the app then loads /api/me again and, seeing none, saves the browser's.
    await deleteAllMyData(a)
    await expect.poll(async () => (await me(a)).timeZone, { message: 'the zone saved by the first load' }).toBe(zone)

    // 2. /api/me's today, and Settings.
    const today = await expectToday(a, zone)
    test.info().annotations.push({
      type: 'zone', description: `${zone}: today ${today}, UTC's ${new Date().toISOString().slice(0, 10)}`,
    })
    await a.page.goto('/settings')
    await expect(a.page.getByRole('heading', { name: 'Time zone' })).toBeVisible()
    await expect(a.page.locator('strong', { hasText: zone })).toBeVisible()
    await expect(a.page.locator('strong', { hasText: new Date(`${today}T12:00:00Z`).toLocaleDateString('en-US',
      { dateStyle: 'full', timeZone: 'UTC' }) })).toBeVisible()
    // No hint: the browser is in the saved zone.
    await expect(a.page.getByText(/Your browser is in/)).toHaveCount(0)

    // 3. A family record dated that today.
    if (family === 'on') await familyRecordOnToday(a, zone, today)
    await deleteAllMyData(a)
  })
}

async function me({ context }: Session): Promise<{ timeZone: string | null; today: string }> {
  const response = await context.request.get('/api/me')
  expect(response.status()).toBe(200)
  return response.json()
}

/** /api/me's today against the date in the zone, read on both sides of the call, so that midnight can't fall between. */
async function expectToday(a: Session, zone: string): Promise<string> {
  for (let attempt = 0; attempt < 3; attempt++) {
    const before = todayIn(zone)
    const answer = await me(a)
    if (before === todayIn(zone)) {
      expect(answer.timeZone).toBe(zone)
      expect(answer.today, `/api/me's today in ${zone}`).toBe(before)
      return before
    }
  }
  throw new Error(`The date in ${zone} turned over in each of three attempts`)
}

async function familyRecordOnToday(a: Session, zone: string, today: string) {
  const { page, context } = a
  const name = `Zone check ${zone.replace(/\W+/g, ' ')}`
  await page.goto('/family/new')
  await page.getByLabel('Name', { exact: true }).fill(name)
  // The form offers the api's today, which isn't typed: the request leaves the start date out.
  await expect(page.getByLabel('Start date')).toHaveValue(today)
  await page.getByRole('checkbox', { name: 'Groceries', exact: true }).check()
  const created = page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/family-ledgers')
  await page.getByRole('button', { name: 'Create family budget' }).click()
  expect((await created).request().postDataJSON()).not.toHaveProperty('startDate')
  await expect(page.getByRole('heading', { level: 2, name })).toBeVisible()
  const ledgerId = ledgerIdOf(page)
  const ledger = await (await context.request.get(`/api/family-ledgers/${ledgerId}`)).json()
  expect(ledger.startDate, `the budget's start date in ${zone}`).toBe(today)

  await page.goto(`/family/${ledgerId}/expenses/new`)
  await expect(page.getByRole('heading', { name: 'Add an expense' })).toBeVisible()
  await expect(page.getByLabel(/^Date/)).toHaveValue(today)
  await page.getByLabel('Category').selectOption({ label: 'Groceries' })
  await page.getByLabel('Amount (EUR)').fill('12.00')
  await page.getByLabel('Paid from').selectOption({ label: 'Specify later' })
  await page.getByRole('button', { name: 'Add the expense' }).click()
  await expect(page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()
  const records = await (await context.request.get(`/api/family-ledgers/${ledgerId}/records`)).json()
  expect(records.content.map((r: { date: string }) => r.date), `the records in ${zone}`).toEqual([today])
}
