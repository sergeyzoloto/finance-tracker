import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

// A run's sessions (D-52, guard 3): a temporary folder of mode 700 that the runner makes and deletes, also after a
// failure. The setup step writes each account's saved session there once its identity is checked; the specs reuse it;
// the teardown reads it to clean up. A marker stops everything after an identity mismatch or a failed sign-in.

const RUN_ID = 'run-id'
const ABORT = 'ABORT'

export function createSessionDir(runId: string): string {
  const dir = mkdtempSync(join(tmpdir(), 'finance-e2e-'))
  chmodSync(dir, 0o700)
  writeFileSync(join(dir, RUN_ID), runId, { mode: 0o600 })
  return dir
}

export function removeSessionDir(dir: string) {
  rmSync(dir, { recursive: true, force: true })
}

/**
 * The run's session folder, as the runner handed it over: of mode 700 and holding the runner's run id, so that
 * Playwright alone, without a runner, can't run against a target.
 */
export function sessionDir(env: NodeJS.ProcessEnv = process.env): string {
  const dir = env.E2E_SESSION_DIR
  const runId = env.E2E_RUN_ID
  if (!dir || !runId) throw new Error('No session folder: run the suite with npm run e2e:local or npm run e2e:prod.')
  if ((statSync(dir).mode & 0o777) !== 0o700) throw new Error(`The session folder ${dir} isn't mode 700.`)
  if (readFileSync(join(dir, RUN_ID), 'utf8') !== runId) throw new Error('The session folder belongs to another run.')
  return dir
}

/** The saved session of an account (cookies and local storage), written only after its identity was checked. */
export const statePath = (dir: string, label: string) => join(dir, `${label}.json`)
const verifiedPath = (dir: string, label: string) => join(dir, `${label}.verified`)
const cleanupPath = (dir: string, label: string) => join(dir, `${label}.cleanup`)

export function markVerified(dir: string, label: string) {
  chmodSync(statePath(dir, label), 0o600)
  writeFileSync(verifiedPath(dir, label), new Date().toISOString(), { mode: 0o600 })
}

/** Whether `/api/me` named this account after its sign-in (guard 2); only then may its data be deleted (guard 7). */
export const isVerified = (dir: string, label: string) => existsSync(verifiedPath(dir, label)) && existsSync(statePath(dir, label))

/** Stops the run: no further request from any account, the teardown's included. */
export function markAborted(dir: string, reason: string) {
  writeFileSync(join(dir, ABORT), reason, { mode: 0o600 })
}

export function aborted(dir: string): string | undefined {
  const path = join(dir, ABORT)
  return existsSync(path) ? readFileSync(path, 'utf8') : undefined
}

/** The teardown's outcome for an account, for the summary. */
export function recordCleanup(dir: string, label: string, outcome: string) {
  writeFileSync(cleanupPath(dir, label), outcome, { mode: 0o600 })
}

export function cleanupOf(dir: string, label: string): string | undefined {
  const path = cleanupPath(dir, label)
  return existsSync(path) ? readFileSync(path, 'utf8') : undefined
}
