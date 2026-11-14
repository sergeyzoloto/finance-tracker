import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

// D-786 (DOC-2): README.md's pictures exist, and docs/images' SVGs use only the app's colours: the token values of
// index.css's three theme blocks and the icon's own (favicon.svg). The palette's swatches show their token's value.
// The tokens are read here rather than imported from themeGuards.test.ts, whose import would run its tests again in
// this file (D-788).

const root = resolve(process.cwd(), '..')
const readme = readFileSync(join(root, 'README.md'), 'utf8')
const images = join(root, 'docs/images')
const svgs = readdirSync(images).filter((f) => f.endsWith('.svg'))
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
const favicon = readFileSync(resolve(process.cwd(), 'public/favicon.svg'), 'utf8').replace(/<!--[\s\S]*?-->/g, '')

const THEMES = ['blue', 'light', 'dark']

function tokensOf(theme: string): Record<string, string> {
  const block = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].find((m) => m[1].includes(`:root[data-theme="${theme}"]`))
  if (!block) return {}
  return Object.fromEntries(
    [...block[2].matchAll(/--([\w-]+)\s*:\s*(#[0-9a-fA-F]{6})\s*;/g)].map((m) => [m[1], m[2].toLowerCase()]))
}

const hexes = (text: string) => [...text.matchAll(/#[0-9a-fA-F]{3,8}\b/g)].map((m) => m[0].toLowerCase())
const attrs = (tag: string): Record<string, string> => Object.fromEntries([...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map((m) => [m[1], m[2]]))
const withoutComments = (svg: string) => svg.replace(/<!--[\s\S]*?-->/g, '')

describe('README.md’s images (D-786)', () => {
  it('every relative image path exists', () => {
    const paths = [
      ...[...readme.matchAll(/!\[[^\]]*\]\(([^)\s]+)/g)].map((m) => m[1]),
      ...[...readme.matchAll(/<img\b[^>]*\bsrc="([^"]+)"/g)].map((m) => m[1]),
    ].filter((p) => !/^[a-z]+:/i.test(p))
    expect(paths.length).toBeGreaterThanOrEqual(4)
    expect(paths.filter((p) => !existsSync(resolve(root, p.split('#')[0])))).toEqual([])
  })

  it('finds the SVGs it checks', () => {
    expect(svgs).toEqual(expect.arrayContaining(['readme-banner.svg', 'readme-palette.svg']))
  })
})

describe('docs/images’ colours (D-785, D-786)', () => {
  const allowed = new Set([...THEMES.flatMap((t) => Object.values(tokensOf(t))), ...hexes(favicon)])

  it('reads every theme and the icon', () => {
    for (const t of THEMES) expect(Object.keys(tokensOf(t)).length).toBeGreaterThan(20)
    expect(hexes(favicon).length).toBeGreaterThan(0)
  })

  it.each(svgs)('%s uses only token values and the icon’s colours', (file) => {
    const svg = withoutComments(readFileSync(join(images, file), 'utf8'))
    expect(hexes(svg).filter((h) => !allowed.has(h))).toEqual([])
    // Colours written any other way (a name, rgb(), a gradient) would escape the check above.
    const painted = [...svg.matchAll(/\b(?:fill|stroke|stop-color|color)="([^"]*)"/g)].map((m) => m[1])
    expect(painted.filter((v) => v !== 'none' && !/^#[0-9a-fA-F]{3,6}$/.test(v))).toEqual([])
    expect(svg).not.toMatch(/<script|foreignObject|href=|url\(|rgb\(/i)
  })

  it('a colour that isn’t a token is found', () => {
    expect(allowed.has('#123456')).toBe(false)
  })
})

describe('the palette (D-786)', () => {
  const svg = withoutComments(readFileSync(join(images, 'readme-palette.svg'), 'utf8'))
  const swatches = [...svg.matchAll(/(<rect\b[^>]*data-token="[^"]*"[^>]*\/>)\s*<text\b[^>]*>([^<]*)<\/text>\s*<text\b[^>]*>([^<]*)<\/text>/g)]
    .map((m): Record<string, string> => ({ ...attrs(m[1]), name: m[2], hex: m[3] }))

  it('has every swatch with its label', () => {
    expect(swatches.length).toBe((svg.match(/data-token=/g) ?? []).length)
    expect(swatches.length).toBe(27)
  })

  it('each swatch is its token’s value in its theme, printed as its fill', () => {
    const wrong = swatches.filter((s) =>
      s.fill.toLowerCase() !== tokensOf(s['data-theme'])[s['data-token']]
      || s.hex.toLowerCase() !== s.fill.toLowerCase()
      || s.name !== s['data-token'])
    expect(wrong).toEqual([])
  })
})
