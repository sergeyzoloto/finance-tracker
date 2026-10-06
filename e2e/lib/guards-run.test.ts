import assert from 'node:assert/strict'
import { mkdtempSync, readdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, test } from 'node:test'
import { runSuite } from '../scripts/common.ts'
import { TEST_PASSWORD, TEST_ROLES } from './accounts.ts'
import { startFake, type Fake, type Logged } from './fake-app.ts'
import { testTarget } from './targets.ts'

// The guards with a real browser (D-52, guards 2, 3, 7): the suite's own sign-in, identity check, specs and cleanup run
// through the runner against a fake app and a fake Keycloak on loopback ports (lib/fake-app.ts), which log every request.

let fake: Fake | undefined
let artifacts: string | undefined

afterEach(async () => {
  await fake?.close()
  if (artifacts) rmSync(artifacts, { recursive: true, force: true })
  fake = undefined
  artifacts = undefined
})

async function run(names: Record<string, string>, passwords: Record<string, string>,
  emails: Record<string, string> = { [TEST_ROLES.A.login]: TEST_ROLES.A.email, [TEST_ROLES.B.login]: TEST_ROLES.B.email }) {
  fake = await startFake({ names, passwords, emails })
  process.env.E2E_TEST_APP = String(fake.appPort)
  process.env.E2E_TEST_KEYCLOAK = String(fake.keycloakPort)
  artifacts = mkdtempSync(join(tmpdir(), 'e2e-guards-run-'))
  const status = await runSuite({
    target: testTarget(), family: 'on', artifacts, invocations: [['test-specs']], secrets: [TEST_PASSWORD],
  }, { commit: '0'.repeat(40), clean: true })
  return { status, log: fake.log }
}

const A = TEST_ROLES.A
const B = TEST_ROLES.B
const both = { [A.login]: TEST_PASSWORD, [B.login]: TEST_PASSWORD }
/** The app's API requests, as "METHOD path user". */
const api = (log: Logged[]) => log.filter((l) => l.server === 'app' && (l.path.startsWith('/api/') || l.path === '/logout'))
  .map((l) => `${l.method} ${l.path} ${l.user ?? '-'}`)

describe('the guards with a real browser', { timeout: 120_000 }, () => {
  test('everything as expected: each account signed in once, checked, used, then deleted and signed out', async () => {
    const { status, log } = await run({ [A.login]: A.name, [B.login]: B.name }, both)
    assert.equal(status, 0)
    assert.equal(log.filter((l) => l.server === 'keycloak' && l.method === 'POST').length, 2, 'one sign-in per account')
    const calls = api(log)
    // The sign-in: /api/me alone, before anything else of the account (the page's other requests were blocked).
    assert.deepEqual(calls.slice(0, 2), [`GET /api/me ${A.login}`, `GET /api/me ${B.login}`])
    // The cleanup, last: identity again, Delete all my data with the CSRF token, sign-out.
    assert.deepEqual(calls.slice(-6), [
      `GET /api/me ${A.login}`, `DELETE /api/me/data ${A.login}`, `POST /logout ${A.login}`,
      `GET /api/me ${B.login}`, `DELETE /api/me/data ${B.login}`, `POST /logout ${B.login}`,
    ])
    assert.equal(readdirSync(tmpdir()).filter((n) => n.startsWith('finance-e2e-')).length, 0, 'the session folder is gone')
  })

  test("/api/me naming someone else: the run aborts, with no further request from any account", async () => {
    // B's own name, but another account's email (D-54: the email decides).
    const { status, log } = await run({ [A.login]: A.name, [B.login]: B.name }, both,
      { [A.login]: A.email, [B.login]: 'someone.else@example.invalid' })
    assert.equal(status, 1)
    // A was checked; B's /api/me named someone else: nothing after it, not even A's cleanup.
    assert.deepEqual(api(log), [`GET /api/me ${A.login}`, `GET /api/me ${B.login}`])
    const afterB = log.slice(log.findIndex((l) => l.path === '/api/me' && l.user === B.login) + 1)
    assert.deepEqual(afterB.filter((l) => l.server === 'app' && l.path.startsWith('/api/')), [])
  })

  test('a failed sign-in: not tried again, and nothing else happens', async () => {
    const { status, log } = await run({ [A.login]: A.name, [B.login]: B.name }, { [A.login]: 'another password', [B.login]: TEST_PASSWORD })
    assert.equal(status, 1)
    const posts = log.filter((l) => l.server === 'keycloak' && l.method === 'POST')
    assert.deepEqual(posts.map((l) => l.user), [A.login], "A's one attempt, and no sign-in of B")
    assert.deepEqual(api(log), [])
  })
})
