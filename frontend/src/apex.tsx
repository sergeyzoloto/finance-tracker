import type { ReactNode } from 'react'
import { hasSessionHint } from './hint'
import Landing from './Landing'
import { appOrigin, isApexHost } from './links'

type Place = { hostname: string; replace: (url: string) => void }

/**
 * The start of the apex host (finance-nl.com, D-980), where there is no backend: with the sign-in hint (D-981) the
 * browser goes to the app, else the landing page shows, its sign-in links pointing to the app. It never asks
 * `/api/me`. Null for any other host (the app's, localhost, an address): they start as they always did.
 */
export function startApex(place: Place, cookies: string): ReactNode | null {
  if (!isApexHost(place.hostname)) return null
  const app = appOrigin(place.hostname)
  if (hasSessionHint(cookies)) {
    place.replace(`${app}/`)
    return <main><p>Taking you to the app…</p></main>
  }
  return <Landing appOrigin={app} />
}
