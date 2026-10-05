import { chmodSync, mkdirSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { askAtTerminal, CONFIRMATION, confirmed } from '../lib/confirm.ts'
import { CREDENTIALS_PATH, readCredentials } from '../lib/credentials.ts'
import { familyFrom } from '../lib/identity.ts'
import { PROD } from '../lib/targets.ts'
import { runSuite, suiteCommit, type Run } from './common.ts'

// npm run e2e:prod: the suite against production, run only by the owner, from the laptop (D-51, D-52). Its checks come
// first and change nothing: E2E_FAMILY, the credentials file and the allowlist, the banner, the typed confirmation.
// Only then does a browser open.

/** The production run's artifacts: outside the repository, one folder per run, mode 700. */
export const ARTIFACTS_ROOT = join(homedir(), '.cache', 'finance-tracker-e2e')

/** What the run depends on; the guards' tests give fakes, never the owner's file or production. */
export interface Deps {
  env: NodeJS.ProcessEnv
  credentialsPath: string
  artifactsRoot: string
  ask: (question: string) => Promise<string | undefined>
  commit: () => { commit: string; clean: boolean }
  run: (run: Run, commit: { commit: string; clean: boolean }) => Promise<number>
  log: (line: string) => void
  now: () => Date
}

/** Resolves to the exit status: 0 passed, 1 a spec or the cleanup failed, 2 refused or stopped with nothing done. */
export async function main(deps: Deps): Promise<number> {
  let family, credentials, commit
  try {
    family = familyFrom(deps.env.E2E_FAMILY)
    credentials = readCredentials(deps.credentialsPath, PROD.roles)
    commit = deps.commit()
  } catch (error) {
    deps.log(`Refused, nothing done: ${(error as Error).message}`)
    return 2
  }
  deps.log([
    'Production end-to-end run',
    `  Target:     ${PROD.app} (Keycloak ${PROD.keycloak})`,
    `  Accounts:   ${credentials.A.account.label} (${credentials.A.account.login}), ${credentials.B.account.label} (${credentials.B.account.login})`,
    `  E2E_FAMILY: ${family}`,
    `  Suite:      ${commit.commit} (${commit.clean ? 'clean tree' : 'TREE NOT CLEAN: run it from a clean checkout'})`,
    '  It deletes all data of both accounts at its start and end, and touches no other account.',
  ].join('\n'))
  const answer = await deps.ask(`Type ${CONFIRMATION} to go on, anything else to stop: `)
  if (!confirmed(answer)) {
    deps.log(answer === undefined ? 'No terminal to type the confirmation at: stopped, nothing done.' : 'Not confirmed: stopped, nothing done.')
    return 2
  }
  mkdirSync(deps.artifactsRoot, { recursive: true, mode: 0o700 })
  chmodSync(deps.artifactsRoot, 0o700)
  const artifacts = join(deps.artifactsRoot, `prod-${deps.now().toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z')}`)
  mkdirSync(artifacts, { mode: 0o700 })
  chmodSync(artifacts, 0o700)
  return deps.run({
    target: PROD,
    family,
    artifacts,
    invocations: [['prod-pages'], ['prod-specs']],
    secrets: [credentials.A.password, credentials.B.password],
  }, commit)
}

if (import.meta.main) {
  process.exitCode = await main({
    env: process.env,
    credentialsPath: CREDENTIALS_PATH,
    artifactsRoot: ARTIFACTS_ROOT,
    ask: askAtTerminal,
    commit: suiteCommit,
    run: runSuite,
    log: (line) => console.log(line),
    now: () => new Date(),
  })
}
