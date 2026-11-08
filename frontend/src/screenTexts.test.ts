import { readdirSync, readFileSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { parseSync } from 'vite'
import { describe, expect, it } from 'vitest'

// D-384 (UX-4; D-353): no decision number in any text a screen shows. Every string, template and JSX text of the
// app's sources is read with Vite's own parser (Oxc, an ESTree tree), so comments never count; the backend's messages
// have ScreenTextTests.

const DECISION = /\bD-\d/

/** The app's sources: every .ts and .tsx under src/ but the tests and their helpers. */
function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name)
    if (e.isDirectory()) return sources(path)
    if (!/\.tsx?$/.test(e.name) || /\.test\.tsx?$/.test(e.name) || /^test[A-Z]/.test(e.name)) return []
    return [path]
  })
}

/** The texts of a source file: string literals, template parts and JSX text. */
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

describe('decision numbers on a screen (D-384)', () => {
  it('are in no text of the app’s sources', () => {
    const root = resolve(process.cwd(), 'src')
    const files = sources(root)
    expect(files.length).toBeGreaterThan(50)
    const offending = files.flatMap((file) => texts(file, readFileSync(file, 'utf8'))
      .filter((t) => DECISION.test(t)).map((t) => `${relative(root, file)}: ${t}`))
    expect(offending).toEqual([])
  })

  it('are found in a string, a template and JSX text, and not in a comment', () => {
    const code = `// D-1 in a comment
      /** D-2 in a doc comment */
      const a = 'Sum is 0 (D-72)'
      const b = \`only they change it (D-8, \${a})\`
      const c = <p>the check doesn’t pass (D-336)</p>`
    expect(texts('x.tsx', code).filter((t) => DECISION.test(t)))
      .toEqual(['Sum is 0 (D-72)', 'only they change it (D-8, ', 'the check doesn’t pass (D-336)'])
  })
})
