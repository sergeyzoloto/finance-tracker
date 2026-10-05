// The block the run ends with (D-52, guard 9), meant to be pasted into the chat as it is.

/** `skipped` is a spec skipped on purpose (the family specs with E2E_FAMILY=off); `not run` one that should have run. */
export type SpecStatus = 'passed' | 'failed' | 'not run' | 'skipped'

export interface SpecResult { spec: string; status: SpecStatus; durationMs: number; tests: number }

export interface Summary {
  start: Date
  end: Date
  target: string
  commit: string
  clean: boolean
  family: string
  specs: SpecResult[]
  /** Per account label: what its sign-in and its cleanup came to. */
  accounts: { label: string; signIn: string; cleanup: string }[]
  artifacts: string
  /** Why the run stopped early, if it did. */
  aborted?: string
}

/** The run passed: every spec passed, nothing aborted, and every account's cleanup worked. */
export const passed = (s: Summary) => !s.aborted && s.specs.length > 0 && s.specs.every((x) => x.status === 'passed' || x.status === 'skipped')
  && s.accounts.every((a) => a.cleanup.startsWith('deleted') || a.cleanup === 'not signed in')

const utc = (d: Date) => d.toISOString().replace(/\.\d{3}Z$/, 'Z')
const seconds = (ms: number) => `${(ms / 1000).toFixed(1)} s`

export function formatSummary(s: Summary): string {
  const width = Math.max(...s.specs.map((x) => x.spec.length), 4)
  return [
    '===== E2E summary =====',
    `Start:     ${utc(s.start)}`,
    `End:       ${utc(s.end)}`,
    `Target:    ${s.target}`,
    `Commit:    ${s.commit} (${s.clean ? 'clean tree' : 'TREE NOT CLEAN'})`,
    `E2E_FAMILY: ${s.family}`,
    'Specs:',
    ...s.specs.map((x) => `  ${x.spec.padEnd(width)}  ${x.status.padEnd(7)}  ${seconds(x.durationMs)}  (${x.tests} test${x.tests === 1 ? '' : 's'})`),
    'Accounts:',
    ...s.accounts.map((a) => `  ${a.label}: sign-in ${a.signIn}; cleanup ${a.cleanup}`),
    ...(s.aborted ? [`ABORTED:   ${s.aborted}`] : []),
    `Artifacts: ${s.artifacts}`,
    `Result:    ${passed(s) ? 'PASSED' : 'FAILED'}`,
    '=======================',
  ].join('\n')
}
