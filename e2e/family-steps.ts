import type { Page } from '@playwright/test'
import { expect, ledgerIdOf, shot, today, type Session } from './fixtures.ts'

// The steps of the family specs (F7's and F8's) that read the same in both: a budget created in EUR, an invite, the
// other account joining, leaving, and the facts of a record's page.

/** A family budget in EUR, from today, with equal shares and the starter Groceries and Other income brought in. */
export async function createTheBudget({ page }: Session, name: string, prefix: string): Promise<string> {
  await page.goto('/family/new')
  await page.getByLabel('Name', { exact: true }).fill(name)
  await expect(page.getByLabel('Base currency')).toHaveValue('EUR')
  await expect(page.getByLabel('Start date')).toHaveValue(today())
  await page.getByRole('checkbox', { name: 'Groceries', exact: true }).check()
  await page.getByRole('checkbox', { name: 'Other income', exact: true }).check()
  await expect(page.getByRole('radio', { name: 'Equal shares' })).toBeChecked()
  await shot(page, `${prefix}-01-new-budget`)
  await page.getByRole('button', { name: 'Create family budget' }).click()
  await expect(page.getByRole('heading', { level: 2, name })).toBeVisible()
  await expect(page.getByText('Family budget · EUR · Owner')).toBeVisible()
  return ledgerIdOf(page)
}

/** An owner's link for someone new, from the members page. */
export async function inviteSomeoneNew({ page }: Session, prefix: string): Promise<string> {
  await page.getByRole('navigation', { name: 'Family budget' }).getByRole('link', { name: 'Members' }).click()
  await page.getByRole('button', { name: 'Invite someone new' }).click()
  const link = page.getByRole('textbox', { name: 'Invite link' })
  await expect(link).toHaveValue(/\/invite#[A-Za-z0-9_-]{43}$/)
  await shot(page, `${prefix}-02-members-invite`)
  return link.inputValue()
}

/** When the last join of this run happened (workers 1: one process runs every spec). */
let lastJoin = 0

/**
 * The invited account opens the link and accepts under its own name. Joins are at least 31 s apart: D-29 allows B 10
 * invite requests a minute, a join sends about 3 (4 in the dev build, whose StrictMode looks the invite up twice), and
 * a local run joins six times, F7, F8 and F8d at both widths.
 */
export async function joinThroughTheLink({ page }: Session, link: string, budget: string, ledgerId: string, name: string,
  prefix: string) {
  const wait = lastJoin + 31_000 - Date.now()
  if (wait > 0) await page.waitForTimeout(wait)
  lastJoin = Date.now()
  await page.goto(link)
  await expect(page).toHaveURL(/\/invite$/)
  const join = page.getByRole('heading', { name: `Join the family budget “${budget}”` })
  const tooMany = page.getByRole('heading', { name: 'Try again later' })
  await expect(join.or(tooMany)).toBeVisible()
  // D-29's limit counts B's invite requests, 10 a minute and 50 an hour, in the api's memory. A run sends about 3 per
  // family spec (the dev build's StrictMode may look the invite up twice): say so rather than fail at the next step.
  if (await tooMany.isVisible()) {
    throw new Error(`B's invite requests hit D-29's limit (429: 10 a minute, 50 an hour per user, counted in the api's `
      + "memory). Locally, more than about two runs in an hour do (six joins a run since F8d); restart the dev stack's "
      + 'backend or wait. In production one run sends about 9.')
  }
  await expect(join).toBeVisible()
  await expect(page.getByLabel('Your name in this budget')).toHaveValue(name)
  await shot(page, `${prefix}-03-invite`)
  await page.getByRole('button', { name: 'Accept' }).click()
  await expect(page).toHaveURL(new RegExp(`/family/${ledgerId}$`))
  await expect(page.getByText('Family budget · EUR · Member')).toBeVisible()
}

/** Leaves the budget through its members page; the last one with an account is told it will be deleted (D-36). */
export async function leave({ page }: Session, budgetPath: string, budget: string, button: string, deletion: string | null,
  prefix: string) {
  await page.goto(`${budgetPath}/members`)
  await page.getByRole('button', { name: 'Leave', exact: true }).click()
  const confirmation = page.getByRole('region', { name: 'Leave the family budget' })
  await expect(confirmation.getByRole('heading', { name: `Leave “${budget}”?` })).toBeVisible()
  if (deletion) await expect(confirmation).toContainText(deletion)
  else await expect(confirmation).not.toContainText('will be deleted')
  await shot(page, `${prefix}-14-${button.replace(/[^a-z0-9]+/gi, '-')}`)
  await confirmation.getByRole('button', { name: button }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('combobox', { name: 'Budget' }).getByRole('option', { name: budget })).toHaveCount(0)
}

/** A member's share on a record's page: the row whose header starts with their name (it also says who changed it). */
export const share = (page: Page, name: string) => page.locator('table.shares tbody tr')
  .filter({ has: page.locator('th', { hasText: new RegExp(`^${name}`) }) }).locator('td').first()

/** A fact of a record's page: the value after its term ("Amount", "Received into"). */
export const fact = (page: Page, term: string) => page.locator(`dl.facts dt:text-is("${term}") + dd`)

/** An amount as the app formats it in en-US: "€45.00", "$20.00", "RUB 1,500.00" (with Intl's no-break space). */
export const money = (amount: string, currency: string) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(Number(amount))
