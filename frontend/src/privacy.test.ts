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
    // UX-9: payees only by the member's own choice, in both lists.
    expect(section).toContain("only if that member chooses to show it in the budget's settings")
    expect(section).toContain("where you paid (the payee), unless you choose to show it in the budget's settings")
  })

  it('has the new date, names the time zone among what is stored, and no longer says nobody else sees anything of yours', () => {
    expect(page.querySelector('.updated')!.textContent).toBe('Last updated: 23 November 2026')
    const data = words(page.getElementById('data')!.parentElement!.textContent!)
    // D-101 (F8c): the time zone is stored with the settings, from the browser, changeable, and decides today's date.
    expect(data).toContain('Your settings include your time zone, such as Europe/Amsterdam, which the app takes from your browser the first time you use it and which you can change in Settings')
    expect(data).toContain('it decides which date is "today" for you')
    expect(data).toContain('No other user of the app can see it, apart from what you record in a family budget, which its members see')
    expect(words(page.getElementById('retention')!.nextElementSibling!.textContent!))
      .toContain('The records of a family budget are kept as long as the family budget exists')
  })

  it('lists the sign-in hint cookie: what it holds, why, when set and removed, its lifetime and where it goes (WEB-1, D-981)', () => {
    const cookies = words(page.getElementById('cookies')!.nextElementSibling!.nextElementSibling!.textContent!)
    expect(cookies).toContain('the sign-in hint cookie (ft_session), which holds only the value 1 and nothing about you')
    expect(cookies).toContain('The app sets it when you are signed in')
    expect(cookies).toContain('It is removed when you sign out or your session is no longer valid, ends when you close your browser, and is sent only to finance-nl.com')
  })

  it('lists the theme cookie among the cookies: what it holds and why (UX-15, D-617)', () => {
    const cookies = words(page.getElementById('cookies')!.nextElementSibling!.nextElementSibling!.textContent!)
    expect(cookies).toContain('the theme cookie (ft_theme), which holds only the theme you chose in Settings (Blue, Light or Dark), so that the login pages at auth.finance-nl.com show the same theme')
    expect(cookies).toContain('It is set only once you choose a theme, lasts a year from your last visit')
    expect(cookies).toContain('nothing on the servers reads it')
  })

  it('names everything the app keeps in the browser, as the draft holds it (UX-19, D-824, D-828)', () => {
    const text = draft.slice(draft.indexOf("## What the app keeps in the browser"))
    const blocks = text.split(/\n\s*\n/).slice(2).flatMap((block) => block.trim().startsWith('- ')
      ? block.trim().split(/\n- /).map((item) => words(item.replace(/^- /, '')))
      : [words(block)]).filter((block) => block !== '')
    const list = page.querySelector('ul.browser-storage')!
    const published = [words(list.previousElementSibling!.textContent!),
      ...Array.from(list.querySelectorAll('li')).map((li) => words(li.textContent!)),
      words(list.nextElementSibling!.textContent!)]
    expect(published).toEqual(blocks)
    expect(published).toHaveLength(9)
    // ENT-1, D-877: each page's filters in session storage, the summary's fold in local storage.
    expect(published.join(' ')).toContain('the filters and period each page last showed')
    expect(published.join(' ')).toContain("kept in your browser's local storage on this device, which is never sent to the server")
    expect(published.join(' ')).not.toContain('reloads only once')
  })

  it('states every period as an upper bound, the copies in backups included (OPS-3, D-277)', () => {
    const retention = words(page.getElementById('retention')!.nextElementSibling!.textContent!)
    expect(retention).not.toContain('rotated by size')
    expect(retention).not.toContain('several months')
    expect(retention).toContain('are deleted after at most 15 days')
    expect(retention).toContain('kept for 14 days on the server')
    expect(retention).toContain('for 60 days on my own computer')
    expect(retention).toContain('keeps the last 7 of these backups, so each for at most 8 days')
    expect(retention).toContain('within 23 days and from my computer within 61 days')
    expect(retention).toContain('login and account events within 91 days, administrative events within 151 days')
    expect(words(page.getElementById('recipients')!.nextElementSibling!.textContent!))
      .toContain('keeps the daily backups of the whole server in the same location')
  })

  it('keeps the draft’s open questions and its review note out of the page, and has no script', () => {
    expect(draft).not.toContain('TODO')
    expect(draft).toContain('Reviewed by the owner on 2026-10-02')
    expect(html).not.toContain('legal review')
    expect(html).not.toContain('TODO')
    expect(page.querySelectorAll('script')).toHaveLength(0)
  })

  it('publishes the history and sign-in events section as the draft holds it, with its 13 months (HIST-1, D-1033)', () => {
    const text = draft.slice(draft.indexOf('## History and sign-in events'), draft.indexOf('## What the app keeps in the browser'))
    const blocks = text.split(/\n\s*\n/).slice(2).map((block) => words(block.replace(/\n/g, ' '))).filter((block) => block !== '')
    const start = page.getElementById('history')!
    const published: string[] = []
    for (let node = start.nextElementSibling; node && node.tagName !== 'H2'; node = node.nextElementSibling) {
      published.push(words(node.textContent!))
    }
    expect(published).toEqual(blocks)
    expect(published.join(' ')).toContain('These events are kept for 13 months')
    expect(published.join(' ')).toContain('but not your email, your address or your browser')
    const retention = words(page.getElementById('retention')!.nextElementSibling!.textContent!)
    expect(retention).toContain('Your version history is kept until you delete your data')
    expect(retention).toContain('sign-ins, sign-outs and ended sessions are kept for 13 months')
  })

  /** The text of a section, from its h2 to the next h2. */
  function sectionText(id: string) {
    const parts: string[] = [words(page.getElementById(id)!.textContent!)]
    for (let node = page.getElementById(id)!.nextElementSibling; node && node.tagName !== 'H2'; node = node.nextElementSibling) {
      parts.push(words(node.textContent!))
    }
    return parts.join(' ')
  }

  it('has the record history and the activity and notifications sections, outside the family budgets section (HIST-2, D-1121, D-1141, D-1142)', () => {
    const history = sectionText('record-history')
    const activity = sectionText('activity-notifications')
    expect(history).toContain('Record history')
    expect(activity).toContain('Activity and notifications')
    // What an item holds, who sees it, how long it is kept, and the erasure.
    expect(history).toContain('holds the record as it was after a change, with all its details, when the change was made, and who made it')
    expect(history).toContain('seen only by the members of that family budget')
    expect(history).toContain('Your own history is kept until you use "Delete all my data", which erases it')
    expect(history).toContain('the changes you made stay in it, with your name replaced by "Former member" and the comments you wrote removed')
    expect(history).toContain('until the family budget is closed')
    expect(activity).toContain('are kept for 13 months')
    expect(activity).toContain('"Delete all my data"')
    expect(activity).toContain('in the app only')
    expect(activity).toContain('holds no content of the change')
    expect(activity).toContain('deleted when you use "Delete all my data"')
    // Neither new section sits inside the family budgets section, which stays the draft's text.
    const family = page.getElementById('family')!
    expect(family.contains(page.getElementById('record-history'))).toBe(false)
    expect(family.contains(page.getElementById('activity-notifications'))).toBe(false)
    expect(publishedSection().join(' ')).not.toContain('Record history')
    expect(publishedSection()).toEqual(draftSection())
  })

  it('writes the new sections in plain words: no decision number and no technical word (D-1142)', () => {
    for (const text of [sectionText('record-history'), sectionText('activity-notifications')]) {
      expect(text).not.toMatch(/\bD-\d+/)
      for (const word of ['table', 'row', 'rows', 'version_id', 'kind', 'API', 'JSON', 'FAMILY_CHANGES']) {
        expect(text).not.toMatch(new RegExp(`\\b${word}\\b`, 'i'))
      }
    }
  })
})
