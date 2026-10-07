import { defineConfig, type Project } from '@playwright/test'
import { familyFrom } from './lib/identity.ts'
import { sessionDir } from './lib/session.ts'
import { targetNamed } from './lib/targets.ts'

// Run only through the runners (scripts/local.ts, scripts/prod.ts): they pick the target, hand over the session folder
// with its run id, and print the summary. Without them this file refuses, so Playwright alone never reaches a target.
const target = targetNamed(process.env.E2E_TARGET)
sessionDir()
familyFrom(process.env.E2E_FAMILY)
const artifacts = target.name === 'local' ? 'test-results' : process.env.E2E_ARTIFACTS
if (!artifacts) throw new Error('E2E_ARTIFACTS is missing: run the production suite with npm run e2e:prod.')

const t = target.name
/** The sign-in and the cleanup record no trace and take no screenshot: the login form is in them (D-52, guards 3, 4). */
const quiet = { trace: 'off', screenshot: 'off', video: 'off' } as const
/**
 * Smoke, family F7, family F8, family F8d, then the time zone (which saves another zone for A, so it is last; every spec before
 * it starts with Delete all my data, which takes the zone along), in this order (workers 1, files in order); the
 * guards' tests run a probe instead.
 */
const specs = t === 'test' ? /guards-probe\.spec\.ts$/ : /0[2-6]-[a-z0-9-]+\.spec\.ts$/

const projects: Project[] = [
  // Pages without signing in: run first, on their own.
  { name: `${t}-pages`, testMatch: /01-pages\.spec\.ts$/, outputDir: `${artifacts}/pages` },
  // One sign-in per account per run; the cleanup runs after every spec that needed it, even after a failure.
  { name: `${t}-setup`, testMatch: /sign-in\.setup\.ts$/, use: quiet, teardown: `${t}-cleanup`, outputDir: `${artifacts}/setup` },
  { name: `${t}-cleanup`, testMatch: /cleanup\.teardown\.ts$/, use: quiet, outputDir: `${artifacts}/cleanup` },
  { name: `${t}-specs`, testMatch: specs, dependencies: [`${t}-setup`], outputDir: `${artifacts}/specs` },
]
// Locally, the smoke test and family F7 again at a phone's 375 px, with a screenshot of each screen for the review.
// Production checks only the pages at 375 px.
if (t === 'local') {
  projects.push({
    name: 'local-narrow', testMatch: specs, dependencies: ['local-setup'], outputDir: `${artifacts}/narrow`,
    use: { viewport: { width: 375, height: 812 } },
  })
}

export default defineConfig({
  testDir: 'specs',
  projects,
  // D-52, guard 8, and QA-1's rule 10: a flaky test is fixed, never retried.
  retries: 0,
  workers: 1,
  fullyParallel: false,
  forbidOnly: true,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['./lib/reporter.ts']],
  use: {
    baseURL: target.app,
    browserName: 'chromium',
    // The amounts read "€55.00", and today is the server's (UTC in production), so that "today" agrees on both sides.
    locale: 'en-US',
    timezoneId: 'UTC',
    viewport: { width: 1280, height: 800 },
    // Locally, E2E_TRACE=on keeps every spec's trace (the sign-in's never), for debugging and for the password search.
    trace: t === 'local' && process.env.E2E_TRACE === 'on' ? 'on' : 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
})
