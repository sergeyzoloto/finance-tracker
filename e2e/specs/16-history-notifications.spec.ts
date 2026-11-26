import type { Page } from '@playwright/test'
import { deleteAllMyData, expect, expectReloadAfterLeaving, shot, test, userMenu } from '../fixtures.ts'
import { createTheBudget, inviteSomeoneNew, joinThroughTheLink, leave } from '../family-steps.ts'

// HIST-2 part 2's check with two accounts (D-1144), the family budget switched on:
// 1. A creates "HIST-2 check" and invites B, who joins; A records a family expense with a comment, and B reads and
//    marks as read what that told B, so that B's badge starts empty.
// 2. (a) A changes the record's comment.
// 3. (b) B's header shows the unread badge with its count; /notifications lists the server's text for the change with
//    "Unread"; opening the row marks it read (and leads to the journal), the badge count drops by one, and the row
//    shows no "Unread" afterwards.
// 4. (c) A opens the record's History: the versions newest first, A's change as "Comment: old → new", then the creation.
// 5. (d) A opens Activity from the name menu (above Log out): the session note and at least one row with its time.
// 6. B leaves, then A, which deletes the budget; Delete all my data for both.

const BUDGET = 'HIST-2 check'
const BEFORE = 'Weekly shop'
const AFTER = 'Checked twice'

const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

test('history, activity and notifications', async ({ as, family, target, watch }) => {
  test.skip(family === 'off', 'The family budget is switched off (E2E_FAMILY=off).')
  const a = await as('A')
  const b = await as('B')
  await deleteAllMyData(a)
  await deleteAllMyData(b)
  const nameA = target.roles.A.name
  const nameB = target.roles.B.name

  // 1. The budget, the invite, B joining, A's record, B's notifications read.
  const ledgerId = await createTheBudget(a, BUDGET, 'hist2')
  const link = await inviteSomeoneNew(a, 'hist2')
  await joinThroughTheLink(b, link, BUDGET, ledgerId, nameB, 'hist2')
  const budget = `/family/${ledgerId}`

  await a.page.goto(`${budget}/expenses/new`)
  await expect(a.page.getByRole('heading', { name: 'Add an expense' })).toBeVisible()
  await a.page.getByLabel('Category').selectOption({ label: 'Groceries' })
  await a.page.getByLabel('Amount (EUR)').fill('12.00')
  await a.page.getByLabel('Paid from').selectOption({ label: 'Current account' })
  await a.page.getByLabel('Comment (optional)').fill(BEFORE)
  await a.page.getByRole('button', { name: 'Add the expense' }).click()
  await expect(a.page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()
  const record = new URL(a.page.url()).pathname

  await b.page.goto('/notifications')
  const markAll = b.page.getByRole('button', { name: 'Mark all as read' })
  await expect(b.page.getByText('No notifications yet.').or(markAll)).toBeVisible()
  if (await markAll.isVisible()) await markAll.click()
  await expect(b.page.getByRole('button', { name: /^Unread/ })).toHaveCount(0)
  await b.page.goto('/')
  await expect(b.page.getByRole('region', { name: 'Headline figures' }).getByText('Net worth')).toBeVisible()
  await expect(badge(b.page)).toHaveCount(0)

  // 2. (a) A changes the record's comment.
  await expect(a.page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()
  await a.page.getByLabel('Comment (optional)').fill(AFTER)
  await a.page.getByRole('button', { name: 'Save the changes' }).click()
  await expect(a.page.getByRole('status')).toHaveText('Saved.')

  // 3. (b) B's badge, the list, opening the row, the badge after.
  await b.page.goto('/')
  await expect(badge(b.page)).toBeVisible()
  const before = await badgeCount(b.page)
  expect(before, 'the unread count in the badge').toBeGreaterThanOrEqual(1)
  await shot(b.page, 'hist2-01-badge')
  await b.page.goto('/notifications')
  await expect(b.page.getByRole('heading', { level: 2, name: 'Notifications' })).toBeVisible()
  const text = new RegExp(`${escape(nameA)} made (a change|\\d+ changes) in the family budget$`)
  // The row's button also holds the time after the text, so the filter takes the text without its end.
  const rowText = new RegExp(`${escape(nameA)} made (a change|\\d+ changes) in the family budget`)
  const unread = b.page.getByRole('button', { name: /^Unread/ }).filter({ hasText: rowText })
  await expect(unread).toHaveCount(1)
  await expect(unread.locator('.notification-text')).toHaveText(new RegExp(`^Unread${text.source}`))
  await shot(b.page, 'hist2-02-notifications')
  await unread.click()
  // Marked read, then to the budget's journal (D-1136).
  await expect(b.page).toHaveURL(new RegExp(`${budget}/journal$`))
  await expect.poll(() => badgeCount(b.page), { message: 'the unread count after opening the row' }).toBe(before - 1)
  await b.page.goto('/notifications')
  const read = b.page.getByRole('button').filter({ hasText: rowText })
  await expect(read.first()).toBeVisible()
  await expect(b.page.getByRole('button', { name: /^Unread/ })).toHaveCount(0)
  await expect(read.getByText('Unread', { exact: true })).toHaveCount(0)

  // 4. (c) A's History of the record: newest first, the change as "Comment: old → new".
  await a.page.goto(record)
  await expect(a.page.getByRole('heading', { name: /^Groceries, / })).toBeVisible()
  await a.page.getByRole('button', { name: 'History', exact: true }).click()
  const panel = a.page.getByRole('dialog', { name: 'History' })
  await expect(panel).toBeVisible()
  const versions = panel.locator('li.history-version')
  await expect(versions).toHaveCount(2)
  await expect(versions.nth(0).locator('strong')).toHaveText('Changed')
  await expect(versions.nth(0).getByText(nameA)).toBeVisible()
  await expect(versions.nth(0).getByText(`Comment: ${BEFORE} → ${AFTER}`, { exact: true })).toBeVisible()
  await expect(versions.nth(1).locator('strong')).toHaveText('Created')
  await shot(a.page, 'hist2-03-history')
  await panel.getByRole('button', { name: 'Close' }).click()
  await expect(panel).toHaveCount(0)

  // 5. (d) A's Activity from the name menu, the entry above Log out.
  await a.page.goto('/')
  const menu = await userMenu(a.page)
  const items = await menu.locator('li').allInnerTexts()
  expect(items.indexOf('Activity'), 'Activity is in the menu, just above Log out').toBeGreaterThanOrEqual(0)
  expect(items.indexOf('Activity')).toBe(items.indexOf('Log out') - 1)
  await menu.getByRole('link', { name: 'Activity' }).click()
  await expect(a.page).toHaveURL(/\/activity$/)
  await expect(a.page.getByRole('heading', { level: 2, name: 'Activity' })).toBeVisible()
  await expect(a.page.getByText('A session can also end when the browser is closed or after the app restarts. '
    + 'Such an end is not recorded as a sign-out.')).toBeVisible()
  const rows = a.page.locator('li.activity-row')
  await expect(rows.first()).toBeVisible()
  await expect(rows.first().locator('.muted')).toHaveText(/\S/)
  await shot(a.page, 'hist2-04-activity')

  // 6. B leaves, then A, which deletes the budget; Delete all my data for both.
  expectReloadAfterLeaving(watch, ledgerId)
  await leave(b, budget, BUDGET, 'Leave the family budget', null, 'hist2')
  await leave(a, budget, BUDGET, 'Leave and delete the family budget', `the family budget “${BUDGET}” and its records will be deleted`, 'hist2')
  await deleteAllMyData(a)
  await deleteAllMyData(b)
})

/** The header's unread badge, a link named "<n> unread notification(s)". */
const badge = (page: Page) => page.locator('header').getByRole('link', { name: /^\d+ unread notifications?$/ })

/** The badge's count from its accessible name; 0 when it is gone. */
async function badgeCount(page: Page): Promise<number> {
  // The badge can vanish between a look and a read (the count falls to 0): a read that finds nothing is 0, and it
  // gives up at once, since the default wait would use up the whole poll.
  const name = await badge(page).getAttribute('aria-label', { timeout: 500 }).catch(() => null)
  return Number(/^(\d+) /.exec(name ?? '')?.[1] ?? 0)
}

// HIST-4 part 2b: a snapshot restored through the API (no screen has a restore yet) shows on A's Activity page as
// "Restored “name”: n records changed".
test('a restore on the Activity page', async ({ as }) => {
  const a = await as('A')
  await deleteAllMyData(a)
  await a.page.goto('/activity')
  await expect(a.page.getByRole('heading', { level: 2, name: 'Activity' })).toBeVisible()
  const status = await a.page.evaluate(async () => {
    const xsrf = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '')
    const send = (path: string, body: unknown) => fetch(path, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf }, body: JSON.stringify(body),
    })
    const snapshot = await (await send('/api/snapshots', { name: 'E2E restore' })).json()
    await send('/api/accounts', { code: 'E2E_RESTORE', name: 'Made after the snapshot', type: 'ASSET', defaultCurrency: 'EUR' })
    const preview = await (await send(`/api/snapshots/${snapshot.id}/restore-preview`, {})).json()
    return (await send(`/api/snapshots/${snapshot.id}/restore`, { atRevision: preview.atRevision })).status
  })
  expect(status, 'the restore is applied').toBe(200)
  await a.page.reload()
  await expect(a.page.locator('li.activity-row').filter({ hasText: 'Restored “E2E restore”: ' }).first()).toBeVisible()
  await deleteAllMyData(a)
})
