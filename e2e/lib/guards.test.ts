import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, test } from 'node:test'
import { main, type Deps } from '../scripts/prod.ts'
import { searchSecrets, type Run } from '../scripts/common.ts'
import { PROD_ACCOUNTS } from './accounts.ts'
import { confirmed } from './confirm.ts'
import { CredentialsRefused, readCredentials } from './credentials.ts'
import { checkMe, familyFrom, RunAborted } from './identity.ts'
import { formatSummary, passed, type Summary } from './summary.ts'
import { LOCAL, PROD, requireLocalOnly } from './targets.ts'

// The production run's guards (D-52), with temporary files and fake values only: nothing here reads the owner's
// credentials file or reaches production.

const FAKE_A = 'fake-password-A-0001'
const FAKE_B = 'fake-password-B-0002'
const GOOD = `E2E_A_USERNAME=e2e-a@finance-nl.com\nE2E_A_PASSWORD=${FAKE_A}\nE2E_B_USERNAME=e2e-b@finance-nl.com\nE2E_B_PASSWORD=${FAKE_B}\n`

let root: string
let folder: string
let file: string

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), 'e2e-guards-'))
  folder = join(root, 'finance-tracker')
  mkdirSync(folder, { mode: 0o700 })
  file = join(folder, 'e2e-prod.env')
  writeFileSync(file, GOOD, { mode: 0o600 })
})

afterEach(() => rmSync(root, { recursive: true, force: true }))

/** The refusal's message, which must never hold a value of the file. */
function refusal(action: () => unknown): string {
  try {
    action()
  } catch (error) {
    assert.ok(error instanceof CredentialsRefused, `refused with ${String(error)}`)
    for (const secret of [FAKE_A, FAKE_B, 'someone@example.com']) assert.ok(!error.message.includes(secret), error.message)
    return error.message
  }
  return assert.fail('not refused')
}

describe('the credentials file (guard 1)', () => {
  test('a good file gives both allowlisted accounts', () => {
    const credentials = readCredentials(file, PROD_ACCOUNTS)
    assert.equal(credentials.A.account.label, 'e2e-a')
    assert.equal(credentials.A.password, FAKE_A)
    assert.equal(credentials.B.account.login, 'e2e-b@finance-nl.com')
    assert.equal(credentials.B.password, FAKE_B)
  })

  test('comments, blank lines and quoted values are fine', () => {
    writeFileSync(file, `# e2e accounts\n\n${GOOD.replace(`=${FAKE_A}`, `="${FAKE_A}"`)}`)
    assert.equal(readCredentials(file, PROD_ACCOUNTS).A.password, FAKE_A)
  })

  test('the wrong mode is refused', () => {
    chmodSync(file, 0o644)
    assert.match(refusal(() => readCredentials(file, PROD_ACCOUNTS)), /mode 644; it must be 600/)
    chmodSync(file, 0o400)
    assert.match(refusal(() => readCredentials(file, PROD_ACCOUNTS)), /mode 400; it must be 600/)
  })

  test("the folder's wrong mode is refused", () => {
    chmodSync(folder, 0o755)
    assert.match(refusal(() => readCredentials(file, PROD_ACCOUNTS)), /mode 755; it must be 700/)
  })

  test('the wrong owner is refused', () => {
    // Another owner can't be made without root; the check compares the file's owner with the uid it is given.
    const other = process.getuid!() + 1
    assert.match(refusal(() => readCredentials(file, PROD_ACCOUNTS, other)), /doesn't belong to you/)
  })

  test('a symbolic link is refused', () => {
    const link = join(folder, 'link.env')
    symlinkSync(file, link)
    assert.match(refusal(() => readCredentials(link, PROD_ACCOUNTS)), /isn't a regular file/)
  })

  test('a missing file is refused', () => {
    rmSync(file)
    assert.match(refusal(() => readCredentials(file, PROD_ACCOUNTS)), /doesn't exist/)
  })

  test('an extra key is refused', () => {
    writeFileSync(file, `${GOOD}E2E_C_USERNAME=someone@example.com\n`)
    assert.match(refusal(() => readCredentials(file, PROD_ACCOUNTS)), /the key "E2E_C_USERNAME", which isn't one of/)
  })

  test('a missing key is refused', () => {
    writeFileSync(file, GOOD.replace(`E2E_B_PASSWORD=${FAKE_B}\n`, ''))
    assert.match(refusal(() => readCredentials(file, PROD_ACCOUNTS)), /lacks E2E_B_PASSWORD/)
  })

  test('a key twice, an empty value and a line without "=" are refused', () => {
    writeFileSync(file, `${GOOD}E2E_A_PASSWORD=${FAKE_A}\n`)
    assert.match(refusal(() => readCredentials(file, PROD_ACCOUNTS)), /holds E2E_A_PASSWORD twice/)
    writeFileSync(file, GOOD.replace(`=${FAKE_B}`, '='))
    assert.match(refusal(() => readCredentials(file, PROD_ACCOUNTS)), /E2E_B_PASSWORD in .* is empty/)
    writeFileSync(file, `${GOOD}${FAKE_A}\n`)
    assert.match(refusal(() => readCredentials(file, PROD_ACCOUNTS)), /Line 5 of .* isn't KEY=value/)
  })
})

describe('the allowlist (guard 2)', () => {
  test('a username outside the allowlist is refused', () => {
    writeFileSync(file, GOOD.replace('e2e-a@finance-nl.com', 'someone@example.com'))
    assert.match(refusal(() => readCredentials(file, PROD_ACCOUNTS)),
      /E2E_A_USERNAME in .* isn't e2e-a@finance-nl.com, the allowlisted account e2e-a/)
  })

  test("an allowlisted account in the other's place is refused", () => {
    writeFileSync(file, GOOD.replace('E2E_A_USERNAME=e2e-a', 'E2E_A_USERNAME=e2e-b'))
    assert.match(refusal(() => readCredentials(file, PROD_ACCOUNTS)), /E2E_A_USERNAME .* isn't e2e-a@finance-nl.com/)
  })

  test('/api/me reporting someone else aborts', () => {
    assert.throws(() => checkMe({ name: 'Sergey Zolotko', features: { familyLedgers: true } }, PROD_ACCOUNTS.A, 'on'),
      (e) => e instanceof RunAborted && /reports "Sergey Zolotko", not "E2E Account A".*no further request/.test(e.message))
    assert.throws(() => checkMe({ name: 'E2E Account B', features: { familyLedgers: true } }, PROD_ACCOUNTS.A, 'on'), RunAborted)
    assert.throws(() => checkMe({ features: { familyLedgers: true } }, PROD_ACCOUNTS.A, 'on'), /reports no name/)
    assert.throws(() => checkMe(null, PROD_ACCOUNTS.A, 'on'), RunAborted)
    checkMe({ name: 'E2E Account A', features: { familyLedgers: true } }, PROD_ACCOUNTS.A, 'on')
  })
})

describe('E2E_FAMILY (guard 5)', () => {
  test('unset or anything but on and off is refused', () => {
    for (const value of [undefined, '', 'true', 'ON', 'yes']) assert.throws(() => familyFrom(value), RunAborted)
    assert.equal(familyFrom('on'), 'on')
    assert.equal(familyFrom('off'), 'off')
  })

  test('a mismatch with /api/me aborts', () => {
    assert.throws(() => checkMe({ name: 'E2E Account A', features: { familyLedgers: true } }, PROD_ACCOUNTS.A, 'off'),
      /E2E_FAMILY=off, but \/api\/me reports familyLedgers true/)
    assert.throws(() => checkMe({ name: 'E2E Account A', features: { familyLedgers: false } }, PROD_ACCOUNTS.A, 'on'), RunAborted)
    assert.throws(() => checkMe({ name: 'E2E Account A' }, PROD_ACCOUNTS.A, 'off'), RunAborted)
  })
})

describe('the typed confirmation (guard 6)', () => {
  test('only "E2E PROD" goes on', () => {
    assert.equal(confirmed('E2E PROD'), true)
    for (const line of ['e2e prod', 'E2E PROD ', ' E2E PROD', 'E2E  PROD', 'yes', '', undefined]) assert.equal(confirmed(line), false)
  })
})

describe('the production runner, with fakes', () => {
  function deps(overrides: Partial<Deps> = {}) {
    const calls = { ask: 0, run: [] as Run[] }
    const lines: string[] = []
    const d: Deps = {
      env: { E2E_FAMILY: 'on' },
      credentialsPath: file,
      artifactsRoot: join(root, 'cache'),
      ask: async () => { calls.ask += 1; return 'E2E PROD' },
      commit: () => ({ commit: 'f'.repeat(40), clean: true }),
      run: async (run) => { calls.run.push(run); return 0 },
      log: (line) => lines.push(line),
      now: () => new Date('2026-10-05T12:34:56.789Z'),
      ...overrides,
    }
    return { d, calls, lines }
  }

  test('E2E_FAMILY unset: refused before anything is asked or run', async () => {
    const { d, calls, lines } = deps({ env: {} })
    assert.equal(await main(d), 2)
    assert.equal(calls.ask, 0)
    assert.equal(calls.run.length, 0)
    assert.match(lines.join('\n'), /Refused, nothing done: Set E2E_FAMILY=on or E2E_FAMILY=off/)
    assert.equal(existsSync(d.artifactsRoot), false)
  })

  test('a bad credentials file: refused before anything is asked or run', async () => {
    chmodSync(file, 0o640)
    const { d, calls } = deps()
    assert.equal(await main(d), 2)
    assert.equal(calls.ask, 0)
    assert.equal(calls.run.length, 0)
  })

  test('the confirmation typed wrong: stopped, nothing run, no folder made', async () => {
    for (const answer of ['e2e prod', 'yes', '', undefined]) {
      const { d, calls, lines } = deps({ ask: async () => answer })
      assert.equal(await main(d), 2)
      assert.equal(calls.run.length, 0)
      assert.match(lines.at(-1)!, /stopped, nothing done/)
      assert.equal(existsSync(d.artifactsRoot), false)
    }
  })

  test('the banner names the target, both accounts, E2E_FAMILY and the commit, and no password', async () => {
    const { d, lines } = deps({ commit: () => ({ commit: 'a'.repeat(40), clean: false }) })
    await main(d)
    const banner = lines[0]
    for (const part of ['https://app.finance-nl.com', 'e2e-a (e2e-a@finance-nl.com)', 'e2e-b (e2e-b@finance-nl.com)',
      'E2E_FAMILY: on', `${'a'.repeat(40)} (TREE NOT CLEAN`]) assert.ok(banner.includes(part), part)
    for (const secret of [FAKE_A, FAKE_B]) assert.ok(!lines.join('\n').includes(secret))
  })

  test('confirmed: runs production, pages first, with the artifacts in a folder of mode 700', async () => {
    const { d, calls } = deps({ run: async (run) => { calls.run.push(run); return 1 } })
    assert.equal(await main(d), 1)
    assert.equal(calls.run.length, 1)
    const run = calls.run[0]
    assert.equal(run.target, PROD)
    assert.deepEqual(run.invocations, [['prod-pages'], ['prod-specs']])
    assert.deepEqual(run.secrets, [FAKE_A, FAKE_B])
    assert.equal(run.artifacts, join(d.artifactsRoot, 'prod-20261005T123456Z'))
    assert.equal(statSync(run.artifacts).mode & 0o777, 0o700)
    assert.equal(statSync(d.artifactsRoot).mode & 0o777, 0o700)
  })
})

describe('the targets', () => {
  test('the local target names this machine only', () => {
    requireLocalOnly(LOCAL)
    assert.throws(() => requireLocalOnly({ ...LOCAL, app: 'https://app.finance-nl.com' }), /names app.finance-nl.com/)
    assert.throws(() => requireLocalOnly({ ...LOCAL, keycloak: 'http://192.168.1.2:8080' }), /names 192.168.1.2/)
    assert.throws(() => requireLocalOnly({ ...LOCAL, hosts: ['localhost', 'auth.finance-nl.com'] }), /auth.finance-nl.com/)
  })
})

describe('the summary (guard 9)', () => {
  const summary: Summary = {
    start: new Date('2026-10-05T10:00:00Z'), end: new Date('2026-10-05T10:03:10Z'), target: PROD.app,
    commit: 'c'.repeat(40), clean: true, family: 'on',
    specs: [{ spec: 'pages', status: 'passed', durationMs: 4200, tests: 2 }, { spec: 'family F7', status: 'passed', durationMs: 61000, tests: 1 }],
    accounts: [{ label: 'e2e-a', signIn: 'checked', cleanup: 'deleted 2026-10-05T10:03:09Z, signed out' },
      { label: 'e2e-b', signIn: 'checked', cleanup: 'deleted 2026-10-05T10:03:10Z, signed out' }],
    artifacts: '/home/owner/.cache/finance-tracker-e2e/prod-20261005T100000Z',
  }

  test('passes only when every spec passed and every cleanup deleted', () => {
    assert.equal(passed(summary), true)
    assert.equal(passed({ ...summary, specs: [...summary.specs, { spec: 'smoke', status: 'failed', durationMs: 1, tests: 1 }] }), false)
    assert.equal(passed({ ...summary, specs: [{ spec: 'smoke', status: 'not run', durationMs: 0, tests: 1 }] }), false)
    assert.equal(passed({ ...summary, accounts: [{ label: 'e2e-a', signIn: 'checked', cleanup: 'FAILED: 500' }] }), false)
    assert.equal(passed({ ...summary, aborted: 'identity' }), false)
    assert.equal(passed({ ...summary, specs: [] }), false)
  })

  test('says everything the chat needs', () => {
    const text = formatSummary(summary)
    for (const part of ['Start:     2026-10-05T10:00:00Z', 'End:       2026-10-05T10:03:10Z', 'Target:    https://app.finance-nl.com',
      `${'c'.repeat(40)} (clean tree)`, 'E2E_FAMILY: on', 'pages      passed   4.2 s  (2 tests)', 'family F7  passed   61.0 s',
      'e2e-a: sign-in checked; cleanup deleted', 'Artifacts: /home/owner/.cache', 'Result:    PASSED']) assert.ok(text.includes(part), part)
  })
})

describe('the password search (guard 4)', () => {
  test('finds a secret in a file and inside a zip, as typed, URL-encoded or in base64, and nothing else', () => {
    const secret = 'pa ss/wörd+1'
    const dir = join(root, 'artifacts')
    mkdirSync(join(dir, 'deep'), { recursive: true })
    writeFileSync(join(dir, 'clean.txt'), 'nothing here')
    assert.deepEqual(searchSecrets(dir, [secret]), [])
    writeFileSync(join(dir, 'deep', 'log.txt'), `value=${encodeURIComponent(secret)}`)
    writeFileSync(join(dir, 'b64.txt'), Buffer.from(secret).toString('base64'))
    writeFileSync(join(root, 'inner.json'), JSON.stringify({ value: secret }))
    assert.equal(spawnSync('zip', ['-q', '-j', join(dir, 'trace.zip'), join(root, 'inner.json')]).status, 0)
    assert.deepEqual(searchSecrets(dir, [secret]).sort(), ['b64.txt', 'deep/log.txt', 'trace.zip'])
  })
})
