import type { ReactNode } from 'react'
import type { Me } from './api'
import { MeProvider } from './me'

/** A signed-in user for tests: zone UTC (the tests' own, `vite.config.ts`), and today as the tests' clocks have it. */
export const TEST_ME: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-09-30', features: { familyLedgers: true } }

/** What a page rendered without the app around it needs: the user that `/api/me` says, with today's date. */
export function WithMe({ me = TEST_ME, children }: { me?: Me; children: ReactNode }) {
  return <MeProvider initial={me}>{children}</MeProvider>
}
