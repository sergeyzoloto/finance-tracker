import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

// The published privacy policy (public/privacy.html) and its family budgets' section, which F6c publishes from the
// draft the owner reviewed (docs/family-budget/privacy-draft.md): the draft keeps the section's text as published, so
// that its history shows every change since the review.

// Vitest runs in frontend/, as npm test does.
const html = readFileSync(resolve(process.cwd(), 'public/privacy.html'), 'utf8')
const draft = readFileSync(resolve(process.cwd(), '../docs/family-budget/privacy-draft.md'), 'utf8')
const page = new DOMParser().parseFromString(html, 'text/html')

/** Words only: typographic quotes as plain ones, whitespace collapsed. */
const words = (text: string) => text.replace(/[“”]/g, '"').replace(/’/g, "'").replace(/\s+/g, ' ').trim()

/** The published section's blocks, from its heading to the next h2: each heading, paragraph and list item. */
function publishedSection() {
  const start = page.getElementById('family')!
  const blocks: string[] = []
  for (let node = start as Element | null; node; node = node.nextElementSibling) {
    if (node !== start && node.tagName === 'H2') break
    if (node.tagName === 'UL') node.querySelectorAll('li').forEach((li) => blocks.push(words(li.textContent!)))
    else blocks.push(words(node.textContent!))
  }
  return blocks
}

/** The draft's section, from "## Family budgets" to the note that isn't published, in the same blocks. */
function draftSection() {
  const text = draft.slice(draft.indexOf('## Family budgets'), draft.indexOf('> **Not published'))
  return text.split(/\n\s*\n/).flatMap((block) => block.trim().startsWith('- ')
    ? block.trim().split(/\n- /).map((item) => words(item.replace(/^- /, '')))
    : [words(block.replace(/^## /, ''))]).filter((block) => block !== '')
}

describe('the privacy policy', () => {
  it('publishes the family budgets’ section word for word as the draft holds it', () => {
    const published = publishedSection()
    expect(published.length).toBeGreaterThan(40)
    expect(published).toEqual(draftSection())
  })

  it('no longer says family budgets are not available yet, and says what F6b and F6c built', () => {
    const section = publishedSection().join(' ')
    expect(section).not.toContain("aren't available yet")
    // D-36: the last member with an account leaving deletes the budget.
    expect(section).toContain('the family budget is deleted, with its records')
    expect(section).not.toContain('is closed and nobody sees it')
    // D-37: a return lists the member's own entries after it.
    expect(section).toContain('Entries of your own on that balance dated after the day you come back')
    // E1 and H1.
    expect(section).toContain('the family report')
    expect(section).toContain('"Demo household"')
  })

  it('has the new date, and no longer says nobody else sees anything of yours', () => {
    expect(page.querySelector('.updated')!.textContent).toBe('Last updated: 2 October 2026')
    const data = words(page.getElementById('data')!.parentElement!.textContent!)
    expect(data).toContain('No other user of the app can see it, apart from what you record in a family budget, which its members see')
    expect(words(page.getElementById('retention')!.nextElementSibling!.textContent!))
      .toContain('The records of a family budget are kept as long as the family budget exists')
  })

  it('keeps the draft’s open questions and its review note out of the page, and has no script', () => {
    expect(draft).not.toContain('TODO')
    expect(draft).toContain('Reviewed by the owner on 2026-10-02')
    expect(html).not.toContain('legal review')
    expect(html).not.toContain('TODO')
    expect(page.querySelectorAll('script')).toHaveLength(0)
  })
})
