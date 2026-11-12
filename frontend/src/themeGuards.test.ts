import { readdirSync, readFileSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { parseSync } from 'vite'
import { describe, expect, it } from 'vitest'

// D-504: the three themes' tokens, read from index.css, meet WCAG 2.x contrast; and no colour is written anywhere but
// in those three blocks (D-496).

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
const withoutComments = css.replace(/\/\*[\s\S]*?\*\//g, '')

export const THEME_NAMES = ['blue', 'light', 'dark'] as const
export const TOKENS = [
  'bg', 'surface', 'surface-raised', 'border', 'border-control', 'text', 'muted', 'accent', 'on-accent', 'income',
  'expense', 'danger', 'danger-bg', 'notice-bg', 'success', 'success-bg', 'stale', 'chart-income', 'chart-expense',
  'chart-income-before', 'chart-expense-before', 'chart-grid',
] as const

/** Each block whose selector names `[data-theme="<theme>"]`: its selector and its body. */
function blocks(): { theme: string; start: number; end: number; body: string }[] {
  const found = []
  const rule = /([^{}]+)\{([^{}]*)\}/g
  for (let m = rule.exec(withoutComments); m; m = rule.exec(withoutComments)) {
    const theme = /:root\[data-theme="(\w+)"\]/.exec(m[1])?.[1]
    if (theme) found.push({ theme, start: m.index, end: m.index + m[0].length, body: m[2] })
  }
  return found
}

/** A theme's tokens, `--name: #RRGGBB`. */
export function tokensOf(theme: string): Record<string, string> {
  const block = blocks().find((b) => b.theme === theme)
  if (!block) return {}
  return Object.fromEntries([...block.body.matchAll(/--([\w-]+)\s*:\s*(#[0-9a-fA-F]{6})\s*;/g)].map((m) => [m[1], m[2]]))
}

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG 2.x's contrast ratio of two colours. */
export function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

const GROUNDS = ['bg', 'surface', 'surface-raised']
const TEXTS = ['text', 'muted', 'accent', 'income', 'expense', 'danger', 'stale']
const CHARTS = ['chart-income', 'chart-expense', 'chart-income-before', 'chart-expense-before']

/** Every pair D-504 checks, with its least ratio. */
const PAIRS: [string, string, number][] = [
  ...TEXTS.flatMap((t) => GROUNDS.map((g): [string, string, number] => [t, g, 4.5])),
  ['on-accent', 'accent', 4.5], ['danger', 'danger-bg', 4.5], ['success', 'success-bg', 4.5], ['text', 'notice-bg', 4.5],
  ...GROUNDS.map((g): [string, string, number] => ['border-control', g, 3]),
  ...CHARTS.map((c): [string, string, number] => [c, 'surface', 3]),
]

describe('the themes’ tokens (D-496, D-504)', () => {
  it('has exactly the three blocks', () => {
    expect(blocks().map((b) => b.theme)).toEqual([...THEME_NAMES])
  })

  it.each(THEME_NAMES)('%s has every token', (theme) => {
    expect(Object.keys(tokensOf(theme)).sort()).toEqual([...TOKENS].sort())
  })

  it.each(THEME_NAMES)('%s meets WCAG 2.x contrast for every pair', (theme) => {
    const tokens = tokensOf(theme)
    const failing = PAIRS
      .filter(([fg, bg, least]) => !(tokens[fg] && tokens[bg] && contrast(tokens[fg], tokens[bg]) >= least))
      .map(([fg, bg, least]) => `--${fg} on --${bg}: ${tokens[fg] && tokens[bg] ? contrast(tokens[fg], tokens[bg]).toFixed(2) : 'missing'} < ${least}`)
    expect(failing).toEqual([])
  })

  it('computes WCAG’s ratio', () => {
    expect(contrast('#000000', '#FFFFFF')).toBeCloseTo(21, 5)
    expect(contrast('#777777', '#FFFFFF')).toBeCloseTo(4.48, 2)
  })

  it('fails a block that lacks a token', () => {
    const lacking = { ...tokensOf('blue') }
    delete lacking.stale
    expect(Object.keys(lacking).sort()).not.toEqual([...TOKENS].sort())
  })
})

const COLOUR = /(?<![\w&])#[0-9a-fA-F]{3,8}(?![\w-])|\b(?:rgba?|hsla?)\(/

/** The app's sources: every .ts and .tsx under src/ but the tests and their helpers. */
function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name)
    if (e.isDirectory()) return sources(path)
    if (!/\.tsx?$/.test(e.name) || /\.test\.tsx?$/.test(e.name) || /^test[A-Z]/.test(e.name)) return []
    return [path]
  })
}

/** The texts of a source file: string literals, template parts and JSX text and attribute values. */
function texts(file: string, code: string): string[] {
  const { program, errors } = parseSync(file, code)
  expect(errors, file).toEqual([])
  const found: string[] = []
  const visit = (node: unknown): void => {
    if (Array.isArray(node)) return node.forEach(visit)
    if (!node || typeof node !== 'object') return
    const n = node as { type?: string; value?: unknown }
    if (n.type === 'Literal' && typeof n.value === 'string') found.push(n.value)
    if (n.type === 'JSXText' && typeof n.value === 'string') found.push(n.value)
    if (n.type === 'TemplateElement') found.push((n.value as { raw: string }).raw)
    Object.values(node).forEach(visit)
  }
  visit(program)
  return found
}

/** What of a stylesheet's text lies outside the theme blocks, comments removed. */
export function outsideBlocks(text: string): string {
  const plain = text.replace(/\/\*[\s\S]*?\*\//g, '')
  return plain.replace(/[^{}]*:root\[data-theme="\w+"\][^{}]*\{[^{}]*\}/g, '')
}

describe('colours only as tokens (D-496, D-504)', () => {
  const src = resolve(process.cwd(), 'src')

  it('no hex or rgb() colour in the app’s sources', () => {
    const found = sources(src).flatMap((file) =>
      texts(file, readFileSync(file, 'utf8')).filter((t) => COLOUR.test(t)).map((t) => `${relative(src, file)}: ${t}`))
    expect(found).toEqual([])
  })

  it('no hex or rgb() colour in index.css outside the three blocks', () => {
    const rest = outsideBlocks(css)
    expect(rest.split('\n').filter((line) => COLOUR.test(line))).toEqual([])
  })

  it('finds one where it is', () => {
    expect(texts('x.tsx', "const a = <div style={{ color: '#fff' }}>x</div>").some((t) => COLOUR.test(t))).toBe(true)
    expect(texts('x.ts', 'const a = `rgb(0 0 0)`').some((t) => COLOUR.test(t))).toBe(true)
    expect(COLOUR.test('/invite#token')).toBe(false)
    expect(COLOUR.test('#add-rate')).toBe(false)
    expect(outsideBlocks(':root[data-theme="blue"] { --a: #000000; }\n.x { color: #fff; }')).toContain('#fff')
    expect(outsideBlocks(':root[data-theme="blue"] { --a: #000000; }\n.x { color: var(--a); }')).not.toMatch(COLOUR)
  })
})
