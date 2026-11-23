import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Accounts from './Accounts'
import { accountCode } from './accountCode'
import type { Account } from './api'
import { WithMe } from './testMe'

// The Accounts page's "New account" form (D-1148): its code is made from the name, a refusal is shown as it comes.

const cash: Account = {
  id: 1, code: 'CASH', name: 'Cash', type: 'ASSET', defaultCurrency: 'EUR', requiresCounterparty: false, system: false,
  archived: false,
} as Account

let calls: { call: string; body?: unknown }[] = []
const posted = () => calls.filter((c) => c.call === 'POST /api/accounts')

/** The stub: `list` is what GET /api/accounts answers (read at each call), `post` the answer to the POST. */
function stubApi(
  list: () => Account[], post: () => { status: number; body: unknown } = () => ({ status: 201, body: {} }),
  settings: { status: number; body: unknown } = { status: 404, body: { status: 404 } },
) {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ call: `${method} ${url}`, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    const json = (status: number, body: unknown) =>
      new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
    const path = url.split('?')[0]
    if (method === 'POST' && path === '/api/accounts') {
      const answer = post()
      return json(answer.status, answer.body)
    }
    if (path === '/api/accounts') return json(200, list())
    if (path === '/api/reports/balances') return json(200, [])
    if (path === '/api/settings') return json(settings.status, settings.body)
    return json(404, { status: 404 })
  }))
}

const renderAccounts = () => render(<WithMe><MemoryRouter><Accounts /></MemoryRouter></WithMe>)
const nameField = () => screen.getByRole('textbox', { name: 'Account name' })
const open = async () => {
  await screen.findByRole('heading', { name: 'Accounts' })
  fireEvent.click(await screen.findByRole('button', { name: 'New account' }))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('New account', () => {
  it('is a button in the page title, and again where there are no accounts', async () => {
    stubApi(() => [cash])
    renderAccounts()
    expect(await screen.findByRole('button', { name: 'New account' })).toBeDefined()
    expect(screen.getAllByRole('button', { name: 'New account' })).toHaveLength(1)
    cleanup()

    stubApi(() => [])
    renderAccounts()
    expect(await screen.findByText('You have no accounts yet.')).toBeDefined()
    expect(screen.getAllByRole('button', { name: 'New account' })).toHaveLength(2)
  })

  it('refuses a blank name on the client and sends nothing', async () => {
    stubApi(() => [cash])
    renderAccounts()
    await open()
    fireEvent.change(nameField(), { target: { value: '   ' } })
    fireEvent.submit(nameField().closest('form')!)

    expect((await screen.findByRole('alert')).textContent).toBe('Enter a name for the account.')
    expect(nameField().getAttribute('aria-invalid')).toBe('true')
    expect(posted()).toHaveLength(0)
  })

  it('creates the account with a code made from its name, closes the form and lists it', async () => {
    let list = [cash]
    const created = { ...cash, id: 2, code: 'SAVINGS', name: 'Savings', defaultCurrency: null }
    stubApi(() => list, () => { list = [cash, created]; return { status: 201, body: created } })
    renderAccounts()
    await open()
    expect(screen.getByRole('button', { name: 'New account' }).hasAttribute('disabled')).toBe(true)
    fireEvent.change(nameField(), { target: { value: ' Savings ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))

    expect(await screen.findByRole('link', { name: 'Savings' })).toBeDefined()
    expect(screen.queryByRole('textbox', { name: 'Account name' })).toBeNull()
    expect(posted()).toHaveLength(1)
    expect(posted()[0].body).toEqual({
      code: accountCode('Savings', ['CASH']), name: 'Savings', type: 'ASSET', defaultCurrency: null,
      requiresCounterparty: false,
    })
    expect((posted()[0].body as { code: string }).code).toBe('SAVINGS')
  })

  it('refuses a currency that is not three capital letters', async () => {
    stubApi(() => [cash])
    renderAccounts()
    await open()
    fireEvent.change(nameField(), { target: { value: 'Wallet' } })
    fireEvent.change(screen.getByLabelText('Default currency'), { target: { value: 'EU' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))

    expect((await screen.findByRole('alert')).textContent).toBe('Use a three-letter currency code, such as EUR.')
    expect(posted()).toHaveLength(0)
  })

  it('says a 409 neutrally, reloads the list once, keeps the form and does not try again', async () => {
    const detail = 'You have an account with the code SAVINGS already'
    stubApi(() => [cash], () => ({ status: 409, body: { status: 409, detail } }))
    renderAccounts()
    await open()
    const listed = () => calls.filter((c) => c.call === 'GET /api/accounts').length
    const before = listed()
    fireEvent.change(nameField(), { target: { value: 'Savings' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe('An account with this name already exists. The list has been updated; try again.')
    expect(alert.textContent).not.toMatch(/SAVINGS/)
    expect(nameField()).toHaveProperty('value', 'Savings')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Create' }).hasAttribute('disabled')).toBe(false))
    await waitFor(() => expect(listed()).toBe(before + 1))
    expect(posted()).toHaveLength(1)
  })
})

describe('New account: currency suggestions (D-1150)', () => {
  const withCurrency = (id: number, defaultCurrency: string | null) =>
    ({ ...cash, id, code: `A${id}`, name: `Account ${id}`, defaultCurrency }) as Account
  const settingsOk = { status: 200, body: { baseCurrency: 'EUR', sharedAccountId: null, defaultShareRatio: '0.50' } }
  const suggested = () => {
    const input = screen.getByLabelText('Default currency')
    const list = document.getElementById(input.getAttribute('list')!)!
    return [...list.querySelectorAll('option')].map((o) => o.getAttribute('value'))
  }
  const settingsAnswered = () => calls.some((c) => c.call === 'GET /api/settings')

  it('puts the base currency first, then the other currencies sorted, without repeating it', async () => {
    stubApi(() => [withCurrency(1, 'USD'), withCurrency(2, 'EUR'), withCurrency(3, 'CZK')], undefined, settingsOk)
    renderAccounts()
    await open()
    await waitFor(() => expect(suggested()).toEqual(['EUR', 'CZK', 'USD']))
  })

  it('falls back to the accounts currencies, sorted, when the settings request fails, and shows no error', async () => {
    stubApi(() => [withCurrency(1, 'USD'), withCurrency(2, 'EUR'), withCurrency(3, 'CZK')], undefined,
      { status: 500, body: { status: 500, detail: 'settings broke' } })
    renderAccounts()
    await open()
    await waitFor(settingsAnswered)
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(suggested()).toEqual(['CZK', 'EUR', 'USD'])
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByText(/settings broke/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Create' }).hasAttribute('disabled')).toBe(false)
  })

  it('lists each currency once', async () => {
    stubApi(() => [withCurrency(1, 'EUR'), withCurrency(2, 'USD'), withCurrency(3, 'USD'), withCurrency(4, null)],
      undefined, settingsOk)
    renderAccounts()
    await open()
    await waitFor(() => expect(suggested()).toEqual(['EUR', 'USD']))
  })
})
