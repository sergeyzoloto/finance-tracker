import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { cloneElement, type ReactElement } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Me } from './api'
import Appearance from './Appearance'
import IncomeExpenseChart from './IncomeExpenseChart'
import { SETTINGS_PARTS } from './Settings'
import { THEME_KEY } from './theme'

// UX-10: Settings' Appearance (D-499) and the chart in the theme's tokens (D-501).

// Recharts measures its container, which jsdom can't: a fixed size in its place, so that the chart draws.
vi.mock('recharts', async (original) => {
  const actual = await original<typeof import('recharts')>()
  return {
    ...actual,
    ResponsiveContainer: ({ children }: { children: ReactElement<{ width?: number; height?: number }> }) =>
      cloneElement(children, { width: 600, height: 260 }),
  }
})

function device(dark: boolean) {
  vi.stubGlobal('matchMedia', vi.fn(() => ({
    matches: dark, media: '', addEventListener: () => undefined, removeEventListener: () => undefined,
  })))
}

beforeEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('Settings → Appearance (D-499)', () => {
  it('is a part of Settings, between Date format and Import', () => {
    expect(SETTINGS_PARTS.map(([, label]) => label))
      .toEqual(['Time zone', 'Date format', 'Appearance', 'Import', 'Export', 'Delete all my data'])
  })

  it('shows Theme, three cards with their lines and previews, the note, and no Save button', () => {
    device(false)
    render(<Appearance />)
    expect(screen.getByRole('heading', { level: 3, name: 'Theme' })).toBeDefined()
    const group = screen.getByRole('radiogroup', { name: 'Theme' })
    const radios = within(group).getAllByRole('radio') as HTMLInputElement[]
    expect(radios.map((r) => r.closest('label')!.textContent)).toEqual([
      'BlueDark navy, the look the app was designed in',
      'LightWhite panels on a pale grey page',
      'DarkNeutral graphite, no blue tint',
    ])
    expect(group.querySelectorAll('.theme-preview')).toHaveLength(3)
    expect([...group.querySelectorAll('.theme-preview')].map((p) => p.getAttribute('data-theme'))).toEqual(['blue', 'light', 'dark'])
    expect(screen.getByText(/^Saved in this browser\. Until you choose, the app follows your device: Light when it is set to light, Blue when it is set to dark\.$/))
      .toBeDefined()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('checks the theme in force: the device’s while nothing is chosen', () => {
    device(true)
    render(<Appearance />)
    expect(screen.getByRole<HTMLInputElement>('radio', { name: /^Blue/ }).checked).toBe(true)
    cleanup()
    device(false)
    render(<Appearance />)
    expect(screen.getByRole<HTMLInputElement>('radio', { name: /^Light/ }).checked).toBe(true)
  })

  it('applies a choice at once, keeps it in the browser and says nothing', () => {
    device(false)
    render(<Appearance />)
    fireEvent.click(screen.getByRole('radio', { name: /^Dark/ }))
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(localStorage.getItem(THEME_KEY)).toBe('dark')
    expect(screen.getByRole<HTMLInputElement>('radio', { name: /^Dark/ }).checked).toBe(true)
    expect(screen.queryByRole('status')).toBeNull()
    fireEvent.click(screen.getByRole('radio', { name: /^Blue/ }))
    expect(document.documentElement.getAttribute('data-theme')).toBe('blue')
    expect(localStorage.getItem(THEME_KEY)).toBe('blue')
  })

  it('is at /settings/appearance in the app', async () => {
    device(false)
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 404, headers: { 'Content-Type': 'application/json' } })))
    const me: Me = { name: 'Anna', timeZone: 'UTC', today: '2026-11-12', features: { familyLedgers: false } }
    function Where() { return <p data-testid="where">{useLocation().pathname}</p> }
    render(<MemoryRouter initialEntries={['/settings/appearance']}><App me={me} /><Routes><Route path="*" element={<Where />} /></Routes></MemoryRouter>)
    await waitFor(() => expect(screen.getByRole('radiogroup', { name: 'Theme' })).toBeDefined())
    const nav = screen.getByRole('navigation', { name: 'Settings' })
    expect(within(nav).getByRole('link', { name: 'Appearance' }).getAttribute('aria-current')).toBe('page')
    expect(screen.getByTestId('where').textContent).toBe('/settings/appearance')
  })
})

describe('the chart in the theme’s tokens (D-501)', () => {
  const fills = (container: HTMLElement, selector: string, attribute: string) =>
    [...new Set([...container.querySelectorAll(selector)].map((e) => e.getAttribute(attribute)))]

  it('draws the months’ bars, the earlier period’s dashed lines and the grid with var()', () => {
    const { container } = render(<IncomeExpenseChart currency="EUR" rounded={false} data={{
      form: 'months', role: 'year',
      months: [
        { month: '2026-09', income: '100', expense: '-80', before: { month: '2025-09', income: '90', expense: '-70' } },
        { month: '2026-10', income: '120', expense: '-60', before: { month: '2025-10', income: '80', expense: '-50' } },
      ],
    }} />)
    // The bars grow in (an animation), so their fills are read from the legend's icons, which Recharts draws from them.
    expect(fills(container, 'path.recharts-legend-icon', 'fill')).toEqual(['var(--chart-income)', 'var(--chart-expense)'])
    expect(fills(container, 'line.recharts-legend-icon', 'stroke'))
      .toEqual(['var(--chart-income-before)', 'var(--chart-expense-before)'])
    expect(fills(container, '.recharts-line-curve', 'stroke').sort())
      .toEqual(['var(--chart-expense-before)', 'var(--chart-income-before)'])
    expect(fills(container, '.recharts-line-dot', 'stroke')).toEqual(['var(--surface)'])
    expect(fills(container, '.recharts-cartesian-grid-horizontal line', 'stroke')).toEqual(['var(--chart-grid)'])
    expect(fills(container, '.recharts-cartesian-axis-tick-value', 'fill')).toEqual(['var(--muted)'])
    // No colour of its own anywhere in the drawing.
    expect(container.innerHTML).not.toMatch(/#[0-9a-fA-F]{3,6}\b|rgb\(/)
  })

  it('draws the ranges’ bars with var()', () => {
    const { container } = render(<IncomeExpenseChart currency="EUR" rounded={false} data={{
      form: 'ranges',
      bars: [{ key: 'previous', role: 'Previous period', label: 'Sep 2026', income: '10', expense: '-20' },
        { key: 'period', role: 'Period', label: 'Oct 2026', income: '30', expense: '-5' }],
    }} />)
    expect(fills(container, 'path.recharts-legend-icon', 'fill')).toEqual(['var(--chart-income)', 'var(--chart-expense)'])
    expect(fills(container, '.recharts-cartesian-grid-horizontal line', 'stroke')).toEqual(['var(--chart-grid)'])
    expect(container.innerHTML).not.toMatch(/#[0-9a-fA-F]{3,6}\b|rgb\(/)
  })
})
