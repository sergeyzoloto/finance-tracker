import { rmSync } from 'node:fs'
import { join } from 'node:path'
import { LOCAL_PASSWORD } from '../lib/accounts.ts'
import { familyFrom } from '../lib/identity.ts'
import { LOCAL, requireLocalOnly } from '../lib/targets.ts'
import { E2E, runSuite, suiteCommit } from './common.ts'

// npm run e2e:local: the suite against the dev stack (./dev.sh) on this machine. Every host it names is localhost, or it
// refuses. E2E_FAMILY defaults to on, as the dev stack has it.

requireLocalOnly(LOCAL)
const family = familyFrom(process.env.E2E_FAMILY ?? 'on')
const artifacts = join(E2E, 'test-results')
// The folder holds this run's artifacts only, so that the password search covers exactly them.
rmSync(artifacts, { recursive: true, force: true })
console.log(`Local run: ${LOCAL.app} (Keycloak ${LOCAL.keycloak}), accounts ${LOCAL.roles.A.label} and ${LOCAL.roles.B.label}, E2E_FAMILY=${family}`)
process.exitCode = await runSuite({
  target: LOCAL,
  family,
  artifacts,
  invocations: [['local-pages'], ['local-specs']],
  secrets: [LOCAL_PASSWORD],
}, suiteCommit())
