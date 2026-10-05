import type { Account } from './accounts.ts'

// Who the app says is signed in (D-52, guards 2 and 5). `/api/me` answers `{ name, features: { familyLedgers } }`; the
// name is the token's (Keycloak's first and last name), which the allowlist commits for each account.

export type Family = 'on' | 'off'

/** The run can't go on: another account than the one signed in for, or the family switch other than expected. */
export class RunAborted extends Error {}

/** E2E_FAMILY, required: whether the family budget is switched on where the suite runs. */
export function familyFrom(value: string | undefined): Family {
  if (value === 'on' || value === 'off') return value
  throw new RunAborted('Set E2E_FAMILY=on or E2E_FAMILY=off: whether the family budget is switched on there.')
}

/**
 * Checks `/api/me`'s answer after a sign-in, before any other request of the account: the account it names, then the
 * family switch.
 *
 * @throws RunAborted on any mismatch, without echoing more than the name the app reported
 */
export function checkMe(me: unknown, account: Account, family: Family) {
  const answer = me as { name?: unknown; features?: { familyLedgers?: unknown } } | null
  const name = typeof answer?.name === 'string' ? answer.name : undefined
  if (name !== account.name) {
    throw new RunAborted(`Signed in for ${account.label}, but /api/me reports ${name === undefined ? 'no name'
      : `"${name.slice(0, 80)}"`}, not "${account.name}". The run stops here, with no further request from any account.`)
  }
  const on = answer?.features?.familyLedgers
  if (on !== (family === 'on')) {
    throw new RunAborted(`E2E_FAMILY=${family}, but /api/me reports familyLedgers ${String(on)} for ${account.label}.`)
  }
}
