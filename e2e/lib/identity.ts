import type { Account } from './accounts.ts'

// Who the app says is signed in (D-52, guards 2 and 5). `/api/me` answers `{ name, email, features: { familyLedgers } }`;
// since F8 (D-54) the check compares the email, the token's, with the one the allowlist commits for each account. The
// name is only quoted in a refusal.

export type Family = 'on' | 'off'

/** The run can't go on: another account than the one signed in for, or the family switch other than expected. */
export class RunAborted extends Error {}

/** E2E_FAMILY, required: whether the family budget is switched on where the suite runs. */
export function familyFrom(value: string | undefined): Family {
  if (value === 'on' || value === 'off') return value
  throw new RunAborted('Set E2E_FAMILY=on or E2E_FAMILY=off: whether the family budget is switched on there.')
}

/**
 * Checks `/api/me`'s answer after a sign-in, before any other request of the account: the account's email it names
 * (D-54), then the family switch.
 *
 * @throws RunAborted on any mismatch, without echoing more than the name the app reported
 */
export function checkMe(me: unknown, account: Account, family: Family) {
  const answer = me as { name?: unknown; email?: unknown; features?: { familyLedgers?: unknown } } | null
  const email = typeof answer?.email === 'string' ? answer.email : undefined
  if (email?.toLowerCase() !== account.email.toLowerCase()) {
    const name = typeof answer?.name === 'string' ? ` (${JSON.stringify(answer.name.slice(0, 80))})` : ''
    throw new RunAborted(`Signed in for ${account.label}, but /api/me reports ${email === undefined ? 'no email'
      : `"${email.slice(0, 80)}"`}${name}, not "${account.email}". The run stops here, with no further request from any `
      + 'account.')
  }
  const on = answer?.features?.familyLedgers
  if (on !== (family === 'on')) {
    throw new RunAborted(`E2E_FAMILY=${family}, but /api/me reports familyLedgers ${String(on)} for ${account.label}.`)
  }
}
