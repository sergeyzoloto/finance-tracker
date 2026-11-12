import { fireEvent, screen, within } from '@testing-library/react'

// The header for the tests (D-498): row 2's navigation, always shown, and the user's menu (D-433, D-434).

/** The header's main navigation, row 2: nothing to open. */
export function mainMenu(): HTMLElement {
  return screen.getByRole('navigation', { name: 'Main' })
}

/** Opens the user's menu, the button named `name`, unless it is open, and returns its panel. */
export function userMenu(name: string): HTMLElement {
  const button = screen.getByRole('button', { name })
  if (button.getAttribute('aria-expanded') !== 'true') fireEvent.click(button)
  return document.getElementById(button.getAttribute('aria-controls')!)!
}

/** What row 2 lists after the pages: each family budget by name, then "New family budget". */
export function menuBudgets(): string[] {
  const links = within(mainMenu()).getAllByRole('link').map((a) => a.textContent ?? '')
  return links.slice(PAGES.length)
}

/** Row 2's pages, before any family budget. */
export const PAGES = ['Dashboard', 'Entries', 'Accounts', 'Categories', 'Rates']

/** The links of row 2 marked as the current page. */
export const currentLinks = (panel: HTMLElement = mainMenu()) =>
  within(panel).queryAllByRole('link').filter((a) => a.getAttribute('aria-current') === 'page').map((a) => a.textContent)
