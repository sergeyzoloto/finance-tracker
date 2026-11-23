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
function stubApi(list: () => Account[], post: () => { status: number; body: unknown } = () => ({ status: 201, body: {} })) {
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

  it('shows the server’s 409 as it comes, keeps the form and does not try again', async () => {
    const detail = 'You have an account with the code SAVINGS already'
    stubApi(() => [cash], () => ({ status: 409, body: { status: 409, detail } }))
    renderAccounts()
    await open()
    fireEvent.change(nameField(), { target: { value: 'Savings' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe(detail)
    expect(nameField()).toHaveProperty('value', 'Savings')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Create' }).hasAttribute('disabled')).toBe(false))
    expect(posted()).toHaveLength(1)
  })
})
