import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Me } from './api'
import Appearance from './Appearance'
import IncomeExpenseChart from './IncomeExpenseChart'
import { SETTINGS_PARTS } from './Settings'
import { THEME_KEY } from './theme'

// UX-10: Settings' Appearance (D-499) and the chart in the theme's tokens (D-501).

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

describe('the chart in the theme’s tokens (D-501, D-649)', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')

  it('colours its bars, markers, baseline, legend and panel only through the tokens', () => {
    // Every declaration of the chart's own rules that names a colour names it through var().
    const rules = [...css.matchAll(/([^{}]*\.(?:ie-[\w-]+|chart-tooltip)[^{}]*)\{([^}]*)\}/g)]
    expect(rules.length).toBeGreaterThan(10)
    for (const [, selector, body] of rules) {
      for (const declaration of body.split(';')) {
        const [property, value] = declaration.split(':').map((part) => part?.trim())
        if (!property || !/(color|background|border|shadow)/.test(property) || value === undefined) continue
        expect(value, `${selector.trim()} { ${property} }`).not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/)
        if (/^(color|background|background-color)$/.test(property)) expect(value, `${selector.trim()} { ${property} }`).toMatch(/^(var\(--[\w-]+\)|inherit|transparent)$/)
      }
    }
    expect(css).toMatch(/\.ie-bar\.income \{ background: var\(--chart-income\); \}/)
    expect(css).toMatch(/\.ie-bar\.expense \{ background: var\(--chart-expense\); \}/)
    expect(css).toMatch(/\.ie-bars \{[^}]*border-left: 1px solid var\(--chart-grid\);/)
    expect(css).toMatch(/\.ie-marker \{[^}]*background: var\(--text\);\s*box-shadow: -1px 0 0 var\(--surface\), 1px 0 0 var\(--surface\);/)
    expect(css).toMatch(/\.ie-stub \{[^}]*background: var\(--border\);/)
    expect(css).toMatch(/\.ie-row\.active \{ background: var\(--surface-raised\); \}/)
    expect(css).toMatch(/\.ie-value \{[^}]*color: var\(--text\);/)
    expect(css).toMatch(/\.ie-value\.none \{ color: var\(--muted\); \}/)
  })

  it('draws the months with their markers and no colour of its own', () => {
    const { container } = render(<IncomeExpenseChart currency="EUR" rounded={false} data={{
      form: 'months', role: 'year',
      months: [
        { month: '2026-09', income: '100', expense: '80', before: { month: '2025-09', income: '90', expense: '70' } },
        { month: '2026-10', income: '120', expense: '60', before: { month: '2025-10', income: '80', expense: '50' } },
      ],
    }} />)
    expect(container.querySelectorAll('.ie-bar.income')).toHaveLength(2)
    expect(container.querySelectorAll('.ie-bar.expense')).toHaveLength(2)
    expect(container.querySelectorAll('.ie-marker')).toHaveLength(4)
    // Inline styles place things; none colours them.
    for (const element of container.querySelectorAll<HTMLElement>('[style]')) {
      expect(element.getAttribute('style')).not.toMatch(/color|background|border|fill|stroke/)
    }
    expect(container.innerHTML).not.toMatch(/#[0-9a-fA-F]{3,6}\b|rgb\(/)
    expect(container.querySelector('svg')).toBeNull()
  })

  it('draws the ranges with no colour of its own', () => {
    const { container } = render(<IncomeExpenseChart currency="EUR" rounded={false} data={{
      form: 'ranges',
      bars: [{ key: 'previous', role: 'Previous period', label: 'Sep 2026', income: '10', expense: '20' },
        { key: 'period', role: 'Period', label: 'Oct 2026', income: '30', expense: '5' }],
    }} />)
    expect(container.querySelectorAll('.ie-bar')).toHaveLength(4)
    expect(container.innerHTML).not.toMatch(/#[0-9a-fA-F]{3,6}\b|rgb\(/)
  })
})
