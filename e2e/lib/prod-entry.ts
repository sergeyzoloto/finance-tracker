import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { main } from '../scripts/prod.ts'
import { runSuite, suiteCommit } from '../scripts/common.ts'
import { TEST_PASSWORD, TEST_ROLES } from './accounts.ts'
import { askAtTerminal } from './confirm.ts'
import { startFake } from './fake-app.ts'
import { testTarget } from './targets.ts'

// The production runner's entry against the fakes, for the terminal tests (lib/terminal.test.ts) only: scripts/prod.ts's
// main with its real prompt at /dev/tty, a credentials file of fake values in a temporary folder, and a run against the
// fake app and fake Keycloak of lib/fake-app.ts. npm run e2e:prod itself can't be pointed at them: its target (PROD)
// and the credentials file's path are constants, so that no variable of the environment can send it anywhere else.
//
// E2E_FAKE_LOG names the file that receives the fake app's request log (JSON) at the end.

const logPath = process.env.E2E_FAKE_LOG
if (!logPath) throw new Error('E2E_FAKE_LOG is missing: this entry is for lib/terminal.test.ts only.')

const { A, B } = TEST_ROLES
const fake = await startFake({
  names: { [A.login]: A.name, [B.login]: B.name },
  passwords: { [A.login]: TEST_PASSWORD, [B.login]: TEST_PASSWORD },
})
const root = mkdtempSync(join(tmpdir(), 'e2e-prod-entry-'))
try {
  const folder = join(root, 'finance-tracker')
  mkdirSync(folder, { mode: 0o700 })
  const credentials = join(folder, 'e2e-prod.env')
  writeFileSync(credentials, 'E2E_A_USERNAME=e2e-a@finance-nl.com\nE2E_A_PASSWORD=fake-A\n'
    + 'E2E_B_USERNAME=e2e-b@finance-nl.com\nE2E_B_PASSWORD=fake-B\n', { mode: 0o600 })
  process.env.E2E_TEST_APP = String(fake.appPort)
  process.env.E2E_TEST_KEYCLOAK = String(fake.keycloakPort)
  console.log(`lib/prod-entry.ts: the banner below is production's; the run goes to the fake app on port ${fake.appPort}.`)
  process.exitCode = await main({
    env: process.env,
    credentialsPath: credentials,
    artifactsRoot: join(root, 'cache'),
    ask: askAtTerminal,
    commit: suiteCommit,
    // The production run's own folder and order, but the fake target and the guards' probe in place of the specs.
    run: (run, commit) => runSuite({ ...run, target: testTarget(), invocations: [['test-specs']], secrets: [TEST_PASSWORD] }, commit),
    log: (line) => console.log(line),
    now: () => new Date(),
  })
} finally {
  await fake.close()
  writeFileSync(logPath, JSON.stringify(fake.log))
  rmSync(root, { recursive: true, force: true })
}
