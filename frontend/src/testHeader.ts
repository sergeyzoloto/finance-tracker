import { fireEvent, screen, within } from '@testing-library/react'

// The header's menus (D-433, D-434) for the tests: open one, read what it lists.

/** Opens the main menu unless it is open, and returns its panel. */
export function mainMenu(): HTMLElement {
  return open(screen.getByRole('button', { name: 'My finances' }))
}

/** Opens the user's menu, the button named `name`, unless it is open, and returns its panel. */
export function userMenu(name: string): HTMLElement {
  return open(screen.getByRole('button', { name }))
}

function open(button: HTMLElement): HTMLElement {
  if (button.getAttribute('aria-expanded') !== 'true') fireEvent.click(button)
  return document.getElementById(button.getAttribute('aria-controls')!)!
}

/** What the main menu lists under "Family budgets": each budget by name, then "New family budget…". */
export function menuBudgets(): string[] {
  const list = within(mainMenu()).getByRole('list', { name: 'Family budgets' })
  return within(list).getAllByRole('link').map((a) => a.textContent ?? '')
}
