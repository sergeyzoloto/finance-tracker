// The accounts the suite may sign in as (D-52, guard 2). Committed, so that a credentials file naming anyone else is
// refused before anything happens.

/** The accounts a spec uses: A, and for the family specs also B. */
export type Role = 'A' | 'B'

/** An account the suite may use, with what identifies it. */
export interface Account {
  /** The account's label in the summary and the session's file names. */
  label: string
  /** What Keycloak's login form takes: production's realm makes the email the username ("Email as username"). */
  login: string
  email: string
  /**
   * The name `/api/me` reports after the sign-in: the token's `name` (first and last name in Keycloak), which is all
   * `/api/me` says about who is signed in. The identity check compares it exactly.
   */
  name: string
}

/** Production: two accounts made only for the suite (D-51), never the owner's own. */
export const PROD_ACCOUNTS: Record<Role, Account> = {
  A: { label: 'e2e-a', login: 'e2e-a@finance-nl.com', email: 'e2e-a@finance-nl.com', name: 'E2E Account A' },
  B: { label: 'e2e-b', login: 'e2e-b@finance-nl.com', email: 'e2e-b@finance-nl.com', name: 'E2E Account B' },
}

/**
 * The dev realm's users (the auth server's dev stack): testuser from its realm export, testuser2 and testuser3 made in
 * its admin console for F5's walk-through. The dev realm has no "Email as username", so they sign in by username.
 */
export const LOCAL_ACCOUNTS = {
  testuser: { label: 'testuser', login: 'testuser', email: 'testuser@example.com', name: 'Test User' },
  testuser2: { label: 'testuser2', login: 'testuser2', email: 'testuser2@example.com', name: 'Second User' },
  testuser3: { label: 'testuser3', login: 'testuser3', email: 'testuser3@example.com', name: 'Third User' },
} as const satisfies Record<string, Account>

/** Locally, A and B are testuser and testuser2; testuser3 is allowed but unused. */
export const LOCAL_ROLES: Record<Role, Account> = { A: LOCAL_ACCOUNTS.testuser, B: LOCAL_ACCOUNTS.testuser2 }

/**
 * The dev users' password, the dev realm's (CLAUDE.md, "How to run tests"). Not a secret, but it must never show in
 * an artifact all the same: the local runs prove guard 4 by searching their artifacts for it.
 */
export const LOCAL_PASSWORD = 'test1234'

/**
 * The guards' own tests (lib/guards.test.ts): accounts of a fake app and a fake Keycloak on loopback ports, which
 * exist nowhere else.
 */
export const TEST_ROLES: Record<Role, Account> = {
  A: { label: 'fake-a', login: 'fake-a', email: 'fake-a@example.invalid', name: 'Fake A' },
  B: { label: 'fake-b', login: 'fake-b', email: 'fake-b@example.invalid', name: 'Fake B' },
}
export const TEST_PASSWORD = 'fake-Pa55word-not-real'

/** The allowlisted account whose login this is, or undefined. */
export function allowed(login: string, accounts: readonly Account[]): Account | undefined {
  return accounts.find((a) => a.login === login)
}
