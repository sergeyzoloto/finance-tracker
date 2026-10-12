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
  /** The password search over the artifacts (D-52, guard 4); left out where none ran. */
  secretSearch?: SecretSearch
}

/**
 * What the password search found: the files that hold a secret, and those it couldn't search (a zip that `unzip`
 * couldn't read, D-135), each relative to the artifacts' folder.
 */
export interface SecretSearch { hits: string[]; unreadable: string[] }

/** The search found nothing and searched everything. */
const searched = (s?: SecretSearch) => !s || (s.hits.length === 0 && s.unreadable.length === 0)

/**
 * The run passed: every spec passed, nothing aborted, every account's cleanup worked, and the password search found
 * nothing and could read every file.
 */
export const passed = (s: Summary) => !s.aborted && s.specs.length > 0 && s.specs.every((x) => x.status === 'passed' || x.status === 'skipped')
  && s.accounts.every((a) => a.cleanup.startsWith('deleted') || a.cleanup === 'not signed in') && searched(s.secretSearch)

function searchLine(s: SecretSearch): string {
  if (s.unreadable.length > 0) {
    return `Password search over the artifacts: FAILED, ${s.unreadable.length} file(s) couldn’t be searched (is unzip installed?): ${s.unreadable.join(', ')}`
      + (s.hits.length > 0 ? `; ${s.hits.length} HIT(S), in ${s.hits.join(', ')}: delete the folder now.` : '')
  }
  return s.hits.length === 0 ? 'Password search over the artifacts: 0 hits'
    : `Password search over the artifacts: ${s.hits.length} HIT(S), in ${s.hits.join(', ')}: delete the folder now.`
}

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
    ...(s.secretSearch ? [searchLine(s.secretSearch)] : []),
    `Result:    ${passed(s) ? 'PASSED' : 'FAILED'}`,
    '=======================',
  ].join('\n')
}
