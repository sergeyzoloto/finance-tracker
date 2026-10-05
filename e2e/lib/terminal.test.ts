import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, test } from 'node:test'
import { E2E } from '../scripts/common.ts'
import { TEST_ROLES } from './accounts.ts'
import type { Logged } from './fake-app.ts'
import { Pty } from './pty.ts'

// The typed confirmation (D-52, guard 6) at a real terminal (QA-1b): an interactive bash in a pseudo-terminal, a block
// pasted at its prompt as the owner pasted the README's lines on 2026-10-05, then the production runner's entry against
// the fakes (lib/prod-entry.ts, through npm run, as npm run e2e:prod) and the line typed after its question. Each test
// reads the fake app's request log: what reached "the app".

const REPOSITORY = join(E2E, '..')
const PROMPT = /Type E2E PROD to go on, anything else to stop: $/
const SHELL_PROMPT = /owner\$ $/

let dir: string
let log: string

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'e2e-terminal-'))
  log = join(dir, 'fake-app.json')
})

afterEach(() => rmSync(dir, { recursive: true, force: true }))

/** The fake app's and fake Keycloak's requests, as "server METHOD path user". */
function requests(): string[] {
  assert.ok(existsSync(log), 'the entry wrote the fake app’s log')
  return (JSON.parse(readFileSync(log, 'utf8')) as Logged[]).map((l) => `${l.server} ${l.method} ${l.path} ${l.user ?? '-'}`)
}

/** B's block: the README's two lines, pasted at once, with the fakes' entry in place of npm run e2e:prod. */
const block = (extra = '') => `cd ${REPOSITORY} && git status --short && git log -1 --format='%H %s'\n`
  + `cd e2e && E2E_FAKE_LOG=${log} E2E_FAMILY=on npm run --silent test:prod-entry\n${extra}`

/** An interactive bash in a pseudo-terminal, at its prompt. */
async function shell(): Promise<Pty> {
  const pty = new Pty('bash --norc --noprofile -i', { ...process.env, GIT_PAGER: 'cat' }, REPOSITORY)
  await pty.waitFor(/\$ $/)
  pty.write("PS1='owner$ '\n")
  await pty.waitFor(SHELL_PROMPT)
  return pty
}

/** Pastes the block, waits for the question, types the answer, and waits for the shell's prompt again. */
async function answer(typed: string, extra = ''): Promise<string> {
  rmSync(log, { force: true })
  const pty = await shell()
  try {
    const start = pty.output.length
    pty.write(block(extra))
    await pty.waitFor(PROMPT, 120_000, start)
    // The owner reads the question, then types.
    await new Promise((r) => setTimeout(r, 300))
    const asked = pty.output.length
    pty.write(`${typed}\n`)
    await pty.waitFor(SHELL_PROMPT, 180_000, asked)
    return pty.output.slice(start)
  } finally {
    pty.write('exit\n')
    await pty.close()
  }
}

const { A, B } = TEST_ROLES

describe('the confirmation at a real terminal', { timeout: 300_000 }, () => {
  test('"E2E PROD" typed after the question: the run goes on and reaches the fake app', async () => {
    const output = await answer('E2E PROD')
    assert.match(output, /Result: {4}PASSED/)
    assert.doesNotMatch(output, /No terminal|Not confirmed/)
    const calls = requests()
    assert.ok(calls.includes(`keycloak POST /login-actions/authenticate ${A.login}`), calls.join('\n'))
    assert.deepEqual(calls.filter((c) => c.startsWith('app DELETE')), [`app DELETE /api/me/data ${A.login}`, `app DELETE /api/me/data ${B.login}`])
  })

  test('anything else typed: stopped, nothing sent', async () => {
    for (const typed of ['e2e prod', 'yes']) {
      const output = await answer(typed)
      assert.deepEqual(requests(), [])
      assert.match(output, /Not confirmed: stopped, nothing done\./)
    }
  })

  test('a line waiting at the terminal before the question is discarded, not taken as the answer', async () => {
    // The block's third line arrives with the paste, before the runner asks: it must not answer the question.
    const output = await answer('no', 'E2E PROD\n')
    assert.deepEqual(requests(), [])
    assert.match(output, /\(1 line\(s\) were waiting at the terminal before this question, pasted ahead: discarded\./)
    assert.match(output, /Not confirmed: stopped, nothing done\./)
  })

  test('no controlling terminal (setsid): stopped, nothing sent, and standard input answers nothing', () => {
    const result = spawnSync('setsid', ['--wait', 'npm', 'run', '--silent', 'test:prod-entry'], {
      cwd: E2E, env: { ...process.env, E2E_FAKE_LOG: log, E2E_FAMILY: 'on' }, input: 'E2E PROD\n', encoding: 'utf8', timeout: 120_000,
    })
    assert.equal(result.status, 2, result.stdout + result.stderr)
    assert.match(result.stdout, /No terminal to type the confirmation at: stopped, nothing done\./)
    assert.deepEqual(requests(), [])
  })
})
