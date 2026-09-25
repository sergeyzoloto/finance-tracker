import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { newForm, type EntryForm, type Tab } from './entryForm'
import { EntryFormView, type SaveResult } from './EntryForms'
import { formatMoney } from './money'
import { testLedger } from './testLedger'

afterEach(cleanup)

type OnSave = (form: EntryForm, andNew: boolean) => Promise<SaveResult>

function renderForm(tab: Tab, onSave = vi.fn<OnSave>(async () => {})) {
  render(<EntryFormView ledger={testLedger} initial={newForm(testLedger, '2026-09-25', tab)} onSave={onSave} />)
  return onSave
}

const type = (element: HTMLElement, value: string) => fireEvent.change(element, { target: { value } })

describe('Expense', () => {
  it('shows the own share of a split expense live', () => {
    renderForm('expense')
    type(screen.getByLabelText(/^Amount/), '725.55')
    fireEvent.click(screen.getByRole('switch', { name: 'Split with family' }))

    // 50 % by default: the family's part is round(725.55 × 0.5, 2) = 362.78 (HALF_UP), and the own share the rest.
    expect(screen.getByLabelText(/^Family's share/)).toHaveProperty('value', '50')
    expect(screen.getByTestId('own-share').textContent).toBe(formatMoney('362.77', 'EUR'))
    expect(screen.getByText(/Your share/).textContent).toContain(formatMoney('362.78', 'EUR'))

    type(screen.getByLabelText(/^Family's share/), '30')
    expect(screen.getByTestId('own-share').textContent).toBe(formatMoney('507.88', 'EUR'))
  })

  it('saves a split expense as a shared expense with the ratio', async () => {
    const onSave = renderForm('expense')
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: '2' } })
    type(screen.getByLabelText(/^Amount/), '725,55')
    fireEvent.change(screen.getByLabelText(/^Category/), { target: { value: '12' } })
    fireEvent.click(screen.getByRole('switch', { name: 'Split with family' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await vi.waitFor(() => expect(onSave).toHaveBeenCalledOnce())
    expect(onSave.mock.calls[0][0]).toMatchObject({ tab: 'expense', split: true, amount: '725,55', sharePercent: '50' })
  })

  it('shows what is missing at the fields instead of saving', () => {
    const onSave = renderForm('expense')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(screen.getByLabelText(/^Paid from/).closest('label')!.textContent).toContain('Choose an account.')
    expect(screen.getByLabelText(/^Amount/).closest('label')!.textContent).toContain('Enter an amount.')
    expect(onSave).not.toHaveBeenCalled()
  })

  it('shows the messages the server returns at the fields', async () => {
    renderForm('expense', vi.fn<OnSave>(async () => ({ amount: ['The amount must not be zero.'], '': ['Something else.'] })))
    fireEvent.change(screen.getByLabelText(/^Paid from/), { target: { value: '2' } })
    type(screen.getByLabelText(/^Amount/), '5')
    fireEvent.change(screen.getByLabelText(/^Category/), { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect((await screen.findByText('The amount must not be zero.')).closest('label')).toBe(
      screen.getByLabelText(/^Amount/).closest('label'))
    expect(screen.getByText('Something else.')).toBeTruthy()
  })

  it('preselects the category of a known payee', () => {
    renderForm('expense')
    type(screen.getByLabelText(/^Payee/), 'albert heijn')
    expect(screen.getByLabelText(/^Category/)).toHaveProperty('value', '12')
  })
})

describe('Advanced', () => {
  const balance = () => screen.getByRole('status', { name: 'Balance per currency' }).textContent
  const save = () => screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement

  it('shows the balance per currency and saves only when every currency adds up to zero', () => {
    renderForm('advanced')
    fireEvent.change(screen.getByLabelText('Account 1'), { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText('Account 2'), { target: { value: '2' } })
    expect(save().disabled).toBe(true)

    type(screen.getByLabelText('Amount 1'), '100')
    type(screen.getByLabelText('Amount 2'), '-60.5')
    expect(balance()).toBe(`EUR: off by ${formatMoney('39.5', 'EUR', { signed: true })}`)
    expect(save().disabled).toBe(true)

    type(screen.getByLabelText('Amount 2'), '-100')
    expect(balance()).toBe('EUR: balanced ✓')
    expect(save().disabled).toBe(false)

    // A second currency has to balance on its own.
    fireEvent.click(screen.getByRole('button', { name: 'Add posting' }))
    fireEvent.change(screen.getByLabelText('Account 3'), { target: { value: '9' } })
    type(screen.getByLabelText('Amount 3'), '0.1')
    expect(screen.getByLabelText('Currency 3')).toHaveProperty('value', 'USD')
    expect(balance()).toBe(`EUR: balanced ✓USD: off by ${formatMoney('0.1', 'USD', { signed: true })}`)
    expect(save().disabled).toBe(true)
  })

  it('adds decimals exactly', () => {
    renderForm('advanced')
    type(screen.getByLabelText('Amount 1'), '0.1')
    type(screen.getByLabelText('Amount 2'), '0.2')
    fireEvent.click(screen.getByRole('button', { name: 'Add posting' }))
    type(screen.getByLabelText('Amount 3'), '-0.3')
    expect(balance()).toBe('EUR: balanced ✓') // 0.1 + 0.2 − 0.3 is 5.55e-17 in floating point
  })

  it('offers a category only on equity accounts', () => {
    renderForm('advanced')
    fireEvent.change(screen.getByLabelText('Account 1'), { target: { value: '2' } })
    fireEvent.change(screen.getByLabelText('Account 2'), { target: { value: '6' } })
    expect(screen.getByLabelText('Category 1')).toHaveProperty('disabled', true)
    expect(screen.getByLabelText('Category 2')).toHaveProperty('disabled', false)
  })
})
