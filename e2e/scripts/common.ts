import { spawn, spawnSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Account } from '../lib/accounts.ts'
import { aborted, cleanupOf, createSessionDir, isVerified, removeSessionDir } from '../lib/session.ts'
import { formatSummary, passed, type SpecResult, type Summary } from '../lib/summary.ts'
import type { Target } from '../lib/targets.ts'

/** The suite's folder, e2e/. */
export const E2E = join(dirname(fileURLToPath(import.meta.url)), '..')

/** The commit the suite runs from, and whether the tree has changes (D-52, guard 6). */
export function suiteCommit(): { commit: string; clean: boolean } {
  const git = (...args: string[]) => spawnSync('git', args, { cwd: E2E, encoding: 'utf8' })
  const head = git('rev-parse', 'HEAD')
  if (head.status !== 0) throw new Error('git rev-parse HEAD failed: run the suite from a clone of the repository.')
  return { commit: head.stdout.trim(), clean: git('status', '--porcelain').stdout.trim() === '' }
}

export interface Run {
  target: Target
  family: string
  artifacts: string
  /** Each Playwright invocation's projects, in order: the pages first, then what needs a sign-in. */
  invocations: string[][]
  /** The secrets to look for in the artifacts afterwards (D-52, guard 4). */
  secrets: string[]
}

/**
 * Runs Playwright once per invocation with the session folder, then prints the summary. The session folder is deleted
 * at the end, also after a failure or Ctrl-C. Resolves to the exit status: 0 only if everything passed.
 */
export async function runSuite(run: Run, commit: { commit: string; clean: boolean }): Promise<number> {
  const start = new Date()
  const runId = randomBytes(16).toString('hex')
  const dir = createSessionDir(runId)
  const results = join(dir, 'results.jsonl')
  // Ctrl-C reaches Playwright too, which stops and runs its teardown; this process waits for it, then cleans up.
  const ignore = () => {}
  process.on('SIGINT', ignore)
  try {
    for (const projects of run.invocations) {
      if (aborted(dir)) break
      await playwright(projects, {
        E2E_TARGET: run.target.name, E2E_FAMILY: run.family, E2E_SESSION_DIR: dir, E2E_RUN_ID: runId,
        E2E_RESULTS: results, ...(run.target.name === 'local' ? {} : { E2E_ARTIFACTS: run.artifacts }),
      })
    }
    const accounts = (Object.values(run.target.roles) as Account[]).map((account) => ({
      label: account.label,
      signIn: isVerified(dir, account.label) ? `checked (/api/me: ${account.name})` : 'not done',
      cleanup: cleanupOf(dir, account.label) ?? 'not attempted (the cleanup didn’t run)',
    }))
    const summary: Summary = {
      start, end: new Date(), target: run.target.app, ...commit, family: run.family,
      specs: readResults(results), accounts, artifacts: run.artifacts, aborted: aborted(dir),
    }
    const hits = searchSecrets(run.artifacts, run.secrets)
    console.log(`\n${formatSummary(summary)}`)
    console.log(hits.length === 0 ? 'Password search over the artifacts: 0 hits'
      : `Password search over the artifacts: ${hits.length} HIT(S), in ${hits.join(', ')}: delete the folder now.`)
    return passed(summary) && hits.length === 0 ? 0 : 1
  } finally {
    removeSessionDir(dir)
    process.off('SIGINT', ignore)
  }
}

function playwright(projects: string[], env: Record<string, string>): Promise<number> {
  const bin = join(E2E, 'node_modules', '.bin', 'playwright')
  const args = ['test', ...projects.map((p) => `--project=${p}`)]
  return new Promise((resolve) => {
    const child = spawn(bin, args, { cwd: E2E, stdio: 'inherit', env: { ...process.env, ...env } })
    child.on('exit', (code) => resolve(code ?? 1))
  })
}

function readResults(path: string): SpecResult[] {
  if (!existsSync(path)) return []
  return readFileSync(path, 'utf8').split('\n').filter((l) => l !== '').map((l) => JSON.parse(l) as SpecResult)
}

/**
 * Every file under the folder that holds one of the secrets, as typed, URL-encoded, JSON-escaped or in base64; a trace
 * (a zip) is searched inside, entry by entry. Screenshots can only be looked at: the forms never show a password.
 */
export function searchSecrets(folder: string, secrets: string[]): string[] {
  if (!existsSync(folder)) return []
  const needles = secrets.flatMap((s) => [s, encodeURIComponent(s), JSON.stringify(s).slice(1, -1), Buffer.from(s).toString('base64')])
    .filter((n, i, all) => n.length > 0 && all.indexOf(n) === i).map((n) => Buffer.from(n))
  const hits: string[] = []
  for (const file of files(folder)) {
    const contents = file.endsWith('.zip')
      ? spawnSync('unzip', ['-p', file], { maxBuffer: 1 << 30 }).stdout ?? Buffer.alloc(0)
      : readFileSync(file)
    if (needles.some((n) => contents.includes(n))) hits.push(relative(folder, file))
  }
  return hits
}

function files(folder: string): string[] {
  return readdirSync(folder).flatMap((name) => {
    const path = join(folder, name)
    return statSync(path).isDirectory() ? files(path) : [path]
  })
}
