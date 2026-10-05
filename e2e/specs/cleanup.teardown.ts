import { test as teardown, type APIRequestContext } from '@playwright/test'
import type { Role } from '../lib/accounts.ts'
import { checkMe, familyFrom } from '../lib/identity.ts'
import { aborted, isVerified, markAborted, recordCleanup, sessionDir, statePath } from '../lib/session.ts'
import { targetNamed } from '../lib/targets.ts'

// The clean end (D-52, guard 7): after the specs, even after a failure, "Delete all my data" for every account whose
// sign-in was checked, then its session ended here and at Keycloak. Through the API, so that nothing provisions the
// account again afterwards: production keeps no row of the test accounts after a run. It says when it couldn't.

const ROLES: Role[] = ['A', 'B']

teardown('delete all data of every signed-in account', async ({ playwright }) => {
  const target = targetNamed(process.env.E2E_TARGET)
  const family = familyFrom(process.env.E2E_FAMILY)
  const dir = sessionDir()
  const failures: string[] = []
  for (const role of ROLES) {
    const account = target.roles[role]
    const stop = aborted(dir)
    if (stop) {
      if (isVerified(dir, account.label)) recordCleanup(dir, account.label, `not attempted: the run was aborted (${stop})`)
      continue
    }
    if (!isVerified(dir, account.label)) {
      recordCleanup(dir, account.label, 'not signed in')
      continue
    }
    const request = await playwright.request.newContext({ baseURL: target.app, storageState: statePath(dir, account.label) })
    try {
      const me = await request.get('/api/me')
      if (me.status() !== 200) throw new Error(`/api/me answered ${me.status()}`)
      try {
        checkMe(await me.json(), account, family)
      } catch (error) {
        markAborted(dir, (error as Error).message)
        throw error
      }
      const deleted = await request.delete('/api/me/data', { headers: { 'X-XSRF-TOKEN': await xsrf(request) } })
      if (deleted.status() !== 204) throw new Error(`DELETE /api/me/data answered ${deleted.status()}`)
      recordCleanup(dir, account.label, `deleted ${new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')}${await logOut(request)}`)
    } catch (error) {
      const reason = (error as Error).message.split('\n')[0]
      recordCleanup(dir, account.label, `FAILED: ${reason}. Delete its data by hand: sign in as ${account.label}, Settings, Delete all my data.`)
      failures.push(`${account.label}: ${reason}`)
    } finally {
      await request.dispose()
    }
  }
  if (failures.length > 0) throw new Error(`The cleanup didn't finish: ${failures.join('; ')}`)
})

async function xsrf(request: APIRequestContext) {
  const cookie = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')
  if (!cookie) throw new Error('no XSRF-TOKEN cookie in the session')
  return decodeURIComponent(cookie.value)
}

/** Ends the session at the app and at Keycloak, as the app's "Log out" does; a session the traces hold is then void. */
async function logOut(request: APIRequestContext): Promise<string> {
  try {
    const out = await request.post('/logout', { form: { _csrf: await xsrf(request) } })
    return out.ok() ? ', signed out' : `, sign-out answered ${out.status()}`
  } catch (error) {
    return `, sign-out failed (${(error as Error).message.split('\n')[0]})`
  }
}
