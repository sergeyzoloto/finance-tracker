import { test as base, expect, type BrowserContext, type Page, type Request, type Response } from '@playwright/test'
import type { Account, Role } from './lib/accounts.ts'
import { checkMe, familyFrom, type Family } from './lib/identity.ts'
import { aborted, isVerified, sessionDir, statePath } from './lib/session.ts'
import { targetNamed, type Target } from './lib/targets.ts'

/** A response the spec expects to fail, and why: anything else of 400 or more on the app's origin fails the spec. */
export interface ExpectedFailure {
  method: string
  /** The path and query, as the browser requested it. */
  path: RegExp
  status: number
  reason: string
}

/**
 * Watches every context a spec opens: responses of 400 or more on the app's origin, console errors and page errors
 * there, and requests to any host the target doesn't name. Each expected failure must happen, at least once.
 */
export class Watch {
  private readonly problems: string[] = []
  private readonly expected: (ExpectedFailure & { seen: number })[] = []

  private readonly target: Target

  constructor(target: Target) {
    this.target = target
  }

  attach(context: BrowserContext) {
    context.on('request', (request) => this.request(request))
    context.on('response', (response) => this.response(response))
    context.on('page', (page) => this.page(page))
  }

  expect(failure: ExpectedFailure) {
    this.expected.push({ ...failure, seen: 0 })
  }

  /** What went wrong, for the spec's failure: unexpected responses and errors, and expected failures that never came. */
  finish(): string[] {
    const missing = this.expected.filter((e) => e.seen === 0)
      .map((e) => `expected ${e.status} for ${e.method} ${e.path} (${e.reason}), which never came`)
    return [...this.problems, ...missing]
  }

  private request(request: Request) {
    const url = new URL(request.url())
    if (!['http:', 'https:'].includes(url.protocol)) return
    if (!this.target.hosts.includes(url.hostname)) this.problems.push(`a request to ${url.origin}, which isn't the target's`)
  }

  private response(response: Response) {
    const url = new URL(response.url())
    if (url.origin !== this.target.app || response.status() < 400) return
    const method = response.request().method()
    const path = url.pathname + url.search
    const expected = this.expected.find((e) => e.method === method && e.status === response.status() && e.path.test(path))
    if (expected) expected.seen += 1
    else this.problems.push(`${response.status()} for ${method} ${path}`)
  }

  private page(page: Page) {
    page.on('console', (message) => {
      if (message.type() !== 'error' || !this.onApp(page)) return
      // A failed response is the response check's to judge, expected or not.
      if (message.text().startsWith('Failed to load resource: the server responded with a status of')) return
      this.problems.push(`console error on ${new URL(page.url()).pathname}: ${message.text().slice(0, 300)}`)
    })
    page.on('pageerror', (error) => {
      this.problems.push(`page error on ${new URL(page.url()).pathname}: ${error.message.slice(0, 300)}`)
    })
  }

  private onApp(page: Page) {
    try {
      return new URL(page.url()).origin === this.target.app
    } catch {
      return false
    }
  }
}

/** A browser context of one account, signed in from the saved session, or of nobody. */
export interface Session {
  role?: Role
  account?: Account
  context: BrowserContext
  page: Page
}

interface Fixtures {
  target: Target
  family: Family
  watch: Watch
  /** Opens a context of the account in this role, from its saved session, after checking `/api/me` names it. */
  as: (role: Role) => Promise<Session>
  /** Opens a context without a session. */
  anonymous: () => Promise<Session>
}

export const test = base.extend<Fixtures>({
  target: async ({}, use) => use(targetNamed(process.env.E2E_TARGET)),
  family: async ({}, use) => use(familyFrom(process.env.E2E_FAMILY)),
  watch: async ({ target }, use) => use(new Watch(target)),
  as: async ({ browser, target, family, watch }, use, testInfo) => {
    const contexts: BrowserContext[] = []
    await use(async (role) => {
      const dir = sessionDir()
      const stop = aborted(dir)
      if (stop) throw new Error(`The run was aborted: ${stop}`)
      const account = target.roles[role]
      if (!isVerified(dir, account.label)) throw new Error(`${account.label} isn't signed in: its sign-in failed or didn't run.`)
      const context = await browser.newContext({ ...contextOptions(testInfo.project.use), storageState: statePath(dir, account.label) })
      contexts.push(context)
      watch.attach(context)
      // The saved session is still this account's, before anything else is asked of it (D-52, guard 2).
      const me = await context.request.get('/api/me')
      expect(me.status(), '/api/me with the saved session').toBe(200)
      checkMe(await me.json(), account, family)
      return { role, account, context, page: await context.newPage() }
    })
    await finish(contexts, watch)
  },
  anonymous: async ({ browser, watch }, use, testInfo) => {
    const contexts: BrowserContext[] = []
    await use(async () => {
      const context = await browser.newContext(contextOptions(testInfo.project.use))
      contexts.push(context)
      watch.attach(context)
      return { context, page: await context.newPage() }
    })
    await finish(contexts, watch)
  },
})

export { expect }

/** The project's options for a context the spec opens itself, as the default context would get them. */
function contextOptions(use: Record<string, unknown>) {
  const { baseURL, locale, timezoneId, viewport } = use as { baseURL: string; locale: string; timezoneId: string; viewport: { width: number; height: number } }
  return { baseURL, locale, timezoneId, viewport }
}

async function finish(contexts: BrowserContext[], watch: Watch) {
  for (const context of contexts) await context.close()
  const problems = watch.finish()
  if (problems.length > 0) throw new Error(`Unexpected on the app's origin:\n- ${problems.join('\n- ')}`)
}

/**
 * Delete all my data, through Settings as a user does it (D-52, guard 7: only for an account whose identity was
 * checked). The app then opens the empty dashboard, which provisions the account's starter ledger again.
 */
export async function deleteAllMyData({ page, account }: Session) {
  if (!account || !isVerified(sessionDir(), account.label)) throw new Error('Delete all my data only for a checked account.')
  await page.goto('/settings')
  await page.getByLabel('Type DELETE to confirm').fill('DELETE')
  const deleted = page.waitForResponse((r) => r.request().method() === 'DELETE' && new URL(r.url()).pathname === '/api/me/data')
  await page.getByRole('button', { name: 'Delete all my data' }).click()
  expect((await deleted).status()).toBe(204)
  await expect(page.getByRole('status')).toHaveText('All your data has been deleted.')
}

/**
 * Leaving a family budget: after the DELETE, the app reloads the budget it just left (`useFamilyMutation` reloads
 * before `family.left()` opens the personal pages), and the reload answers 404, since the reader is no member any more
 * or the budget is gone (D-36). Expected, with that reason; a later stage may drop the reload (QA-1's report).
 */
export function expectReloadAfterLeaving(watch: Watch, ledgerId: string) {
  for (const path of [new RegExp(`^/api/family-ledgers/${ledgerId}$`), new RegExp(`^/api/family-ledgers/${ledgerId}/members$`)]) {
    watch.expect({ method: 'GET', path, status: 404, reason: 'the family page reloads the budget just left' })
  }
}

/** The family budget's id in the page's address, /family/{id}/…. */
export function ledgerIdOf(page: Page): string {
  const id = /\/family\/(\d+)/.exec(new URL(page.url()).pathname)?.[1]
  if (!id) throw new Error(`Not on a family budget's page: ${page.url()}`)
  return id
}

/** GET /api/reports/integrity from the account's session: no difference anywhere. */
export async function expectIntegrity({ context, account }: Session) {
  const response = await context.request.get('/api/reports/integrity')
  expect(response.status(), `integrity for ${account?.label}`).toBe(200)
  expect(await response.json(), `integrity for ${account?.label}`).toEqual([])
}

/** Today as the app and the server see it in these runs (UTC): "2026-10-05". */
export const today = () => new Date().toISOString().slice(0, 10)
