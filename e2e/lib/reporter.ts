import { appendFileSync } from 'node:fs'
import { basename } from 'node:path'
import type { FullResult, Reporter, Suite, TestCase, TestResult } from '@playwright/test/reporter'
import type { SpecResult, SpecStatus } from './summary.ts'

/** A spec's name in the summary: its file's name without the order and the extension, "pages", "family F7". */
export function specName(file: string): string {
  const name = basename(file).replace(/\.(spec|setup|teardown)\.ts$/, '').replace(/^\d+-/, '')
  return name === 'family-f7' ? 'family F7' : name
}

/**
 * Writes each spec's outcome, a line of JSON per spec, to E2E_RESULTS, for the runner's summary. A spec is a test file:
 * passed if every test in it passed, failed if one failed, not run if none ran (its sign-in failed, say).
 */
export default class SummaryReporter implements Reporter {
  private readonly tests = new Map<TestCase, { status: SpecStatus; durationMs: number }>()

  onBegin(_config: unknown, suite: Suite) {
    for (const test of suite.allTests()) this.tests.set(test, { status: 'not run', durationMs: 0 })
  }

  onTestEnd(test: TestCase, result: TestResult) {
    // A test skipped on purpose (`test.skip`, such as the family specs with E2E_FAMILY=off) says so in an annotation;
    // one skipped without it didn't run (a failed sign-in before it).
    const meant = test.annotations.some((a) => a.type === 'skip')
    const status: SpecStatus = result.status === 'passed' ? 'passed'
      : result.status === 'skipped' ? (meant ? 'skipped' : 'not run') : 'failed'
    this.tests.set(test, { status, durationMs: result.duration })
  }

  onEnd(_result: FullResult) {
    const path = process.env.E2E_RESULTS
    if (!path) return
    const specs = new Map<string, SpecResult>()
    for (const [test, outcome] of this.tests) {
      const project = test.parent.project()?.name ?? ''
      const name = `${specName(test.location.file)}${project.endsWith('-narrow') ? ' at 375 px' : ''}`
      const spec = specs.get(name) ?? { spec: name, status: 'skipped', durationMs: 0, tests: 0 }
      spec.tests += 1
      spec.durationMs += outcome.durationMs
      spec.status = worse(spec.status, outcome.status, spec.tests === 1)
      specs.set(name, spec)
    }
    for (const spec of specs.values()) appendFileSync(path, `${JSON.stringify(spec)}\n`, { mode: 0o600 })
  }

  printsToStdio() {
    return false
  }
}

/** A spec's status with one more test's: failed over not run over passed over skipped. */
function worse(spec: SpecStatus, test: SpecStatus, first: boolean): SpecStatus {
  if (first) return test
  const order: SpecStatus[] = ['failed', 'not run', 'passed', 'skipped']
  return order[Math.min(order.indexOf(spec), order.indexOf(test))]
}
