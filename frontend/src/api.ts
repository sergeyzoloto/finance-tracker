import { useCallback, useEffect, useRef, useState } from 'react'
import { csrfToken, logIn } from './auth'

export interface Me { name: string }
export type CategoryType = 'INCOME' | 'EXPENSE'
export interface Category { id: number; name: string; type: CategoryType }
export interface Transaction { id: number; categoryId: number; amount: number; occurredOn: string; note: string | null }
export interface Summary {
  balance: number
  income: number
  expense: number
  spendByCategory: { categoryId: number; name: string; total: number }[]
}

/**
 * Calls the backend with the session cookie, which refreshes the tokens behind it. An ended session goes back through
 * the Keycloak login and returns to this page, rather than ending in an error.
 */
export async function api<T = void>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method,
    headers: {
      ...(method === 'GET' ? {} : { 'X-XSRF-TOKEN': csrfToken() }),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (response.status === 401) return logIn()
  if (response.status === 403) throw new Error('Access denied. Please reload the page.')
  if ([502, 503, 504].includes(response.status)) throw new ServerUnavailable()
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new Error(problem?.detail ?? problem?.title ?? `${response.status} ${response.statusText}`)
  }
  return (response.status === 204 ? undefined : await response.json()) as T
}

/** The backend is down or restarting; the session itself is fine. */
class ServerUnavailable extends Error {
  constructor() {
    super('The server is unavailable right now. Please try again in a moment.')
  }
}

/**
 * Loads `path` and reloads whenever it changes; responses to superseded requests are dropped. While the
 * backend is unavailable it keeps retrying, so the screen recovers on its own once the backend is back.
 */
export function useApi<T>(path: string) {
  const [data, setData] = useState<T>()
  const [error, setError] = useState<string>()
  const latest = useRef(0)
  const reload = useCallback(() => {
    const request = ++latest.current
    api<T>(path).then(
      (result) => { if (request === latest.current) { setData(result); setError(undefined) } },
      (e) => {
        if (request !== latest.current) return
        setError(errorMessage(e))
        if (e instanceof ServerUnavailable) setTimeout(() => request === latest.current && reload(), 3000)
      },
    )
  }, [path])
  useEffect(() => {
    reload()
    return () => { latest.current++ } // unmounted or path changed: drop pending responses and retries
  }, [reload])
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
