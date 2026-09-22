import { useCallback, useEffect, useRef, useState } from 'react'
import { keycloak } from './auth'

export type CategoryType = 'INCOME' | 'EXPENSE'
export interface Category { id: number; name: string; type: CategoryType }
export interface Transaction { id: number; categoryId: number; amount: number; occurredOn: string; note: string | null }
export interface Summary {
  balance: number
  income: number
  expense: number
  spendByCategory: { categoryId: number; name: string; total: number }[]
}

/** Calls the backend with the current access token; an expired session ends at the Keycloak login, not in an error. */
export async function api<T = void>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const send = () => fetch(`/api${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${keycloak.token}`,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  await refreshToken(30)
  let response = await send()
  if (response.status === 401) {
    // Rejected: force a new token from the Keycloak session and retry once.
    await refreshToken(-1)
    response = await send()
  }
  // A fresh token rejected is a server-side problem; logging in again would only loop.
  if (response.status === 401) throw new Error('The server did not accept your session.')
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new Error(problem?.detail ?? problem?.title ?? `${response.status} ${response.statusText}`)
  }
  return (response.status === 204 ? undefined : await response.json()) as T
}

async function refreshToken(minValidity: number) {
  try {
    await keycloak.updateToken(minValidity)
  } catch {
    // Refresh refused: the session is gone, keycloak-js cleared the token and is already redirecting
    // to the login page. Wait for the navigation rather than flash an error.
    if (!keycloak.token) return new Promise<never>(() => {})
    throw new Error('Could not reach the login service to renew your session.')
  }
}

/** Loads `path` and reloads whenever it changes; responses to superseded requests are dropped. */
export function useApi<T>(path: string) {
  const [data, setData] = useState<T>()
  const [error, setError] = useState<string>()
  const latest = useRef(0)
  const reload = useCallback(() => {
    const request = ++latest.current
    api<T>(path).then(
      (result) => { if (request === latest.current) { setData(result); setError(undefined) } },
      (e) => { if (request === latest.current) setError(errorMessage(e)) },
    )
  }, [path])
  useEffect(reload, [reload])
  return { data, error, reload }
}

/** Runs a write, then `onDone`; keeps the failure message for display. Resolves to whether it succeeded. */
export function useMutation(onDone: () => void) {
  const [error, setError] = useState<string>()
  const run = async (action: () => Promise<unknown>) => {
    try {
      await action()
      setError(undefined)
      onDone()
      return true
    } catch (e) {
      setError(errorMessage(e))
      return false
    }
  }
  return { run, error }
}

const errorMessage = (e: unknown) => (e instanceof Error ? e.message : 'Unexpected error')

const moneyFormat = new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
export const money = (amount: number) => moneyFormat.format(amount)

// Local calendar dates; toISOString() would convert to UTC and shift the day near midnight.
export const isoDate = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

export function currentMonth() {
  const now = new Date()
  return {
    from: isoDate(new Date(now.getFullYear(), now.getMonth(), 1)),
    to: isoDate(new Date(now.getFullYear(), now.getMonth() + 1, 0)),
  }
}
