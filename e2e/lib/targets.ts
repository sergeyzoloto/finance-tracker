import { LOCAL_ROLES, PROD_ACCOUNTS, TEST_ROLES, type Account, type Role } from './accounts.ts'

/** Where a run goes: the app's origin and the login's, and the accounts it may use. */
export interface Target {
  name: 'local' | 'prod' | 'test'
  /** The app's origin, as the browser reaches it. */
  app: string
  /** Keycloak's origin: the sign-in goes there and comes back. */
  keycloak: string
  /** The hosts any request of the browser may go to; anything else is a problem of the spec. */
  hosts: string[]
  roles: Record<Role, Account>
  /** How the app serves its pages: the Vite dev server locally, nginx behind Caddy in production. */
  server: 'vite' | 'nginx'
}

export const LOCAL: Target = {
  name: 'local',
  // dev.sh's Vite dev server; Keycloak's redirect URI is registered for exactly this port.
  app: 'http://localhost:5173',
  keycloak: 'http://localhost:8080',
  hosts: ['localhost'],
  roles: LOCAL_ROLES,
  server: 'vite',
}

export const PROD: Target = {
  name: 'prod',
  app: 'https://app.finance-nl.com',
  keycloak: 'https://auth.finance-nl.com',
  hosts: ['app.finance-nl.com', 'auth.finance-nl.com'],
  roles: PROD_ACCOUNTS,
  server: 'nginx',
}

/**
 * The guards' own tests: a fake app and a fake Keycloak (lib/fake-app.ts) on the loopback ports in E2E_TEST_APP and
 * E2E_TEST_KEYCLOAK, so that the sign-in, the identity check and the cleanup run for real, against nothing real.
 */
export function testTarget(env: NodeJS.ProcessEnv = process.env): Target {
  const port = (name: string) => {
    const value = env[name] ?? ''
    if (!/^\d{2,5}$/.test(value)) throw new Error(`${name} isn't a port.`)
    return value
  }
  const target: Target = {
    name: 'test',
    app: `http://127.0.0.1:${port('E2E_TEST_APP')}`,
    keycloak: `http://127.0.0.1:${port('E2E_TEST_KEYCLOAK')}`,
    hosts: ['127.0.0.1'],
    roles: TEST_ROLES,
    server: 'vite',
  }
  requireLocalOnly(target)
  return target
}

const LOOPBACK = new Set(['localhost', '127.0.0.1', '[::1]'])

/**
 * The local target refuses to run unless every host it names is this machine's: a local run never reaches anything
 * else, production least of all.
 *
 * @throws Error naming the first host that isn't
 */
export function requireLocalOnly(target: Target) {
  const hosts = [new URL(target.app).hostname, new URL(target.keycloak).hostname, ...target.hosts]
  const remote = hosts.find((h) => !LOOPBACK.has(h))
  if (remote !== undefined) throw new Error(`The local target names ${remote}, which isn't this machine: refused.`)
}

/** The target a run names in E2E_TARGET; the local one is checked to be local only. */
export function targetNamed(name: string | undefined): Target {
  if (name === 'local') {
    requireLocalOnly(LOCAL)
    return LOCAL
  }
  if (name === 'prod') return PROD
  if (name === 'test') return testTarget()
  throw new Error('E2E_TARGET is neither local nor prod. Run the suite with npm run e2e:local or npm run e2e:prod.')
}
