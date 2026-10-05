import { createReadStream, openSync } from 'node:fs'
import { createInterface } from 'node:readline'

// The production run's confirmation (D-52, guard 6): typed at the terminal by the owner, never piped or pasted ahead.

export const CONFIRMATION = 'E2E PROD'

/** Whether the line typed is exactly the confirmation; anything else stops the run with nothing done. */
export const confirmed = (line: string | undefined) => line === CONFIRMATION

/**
 * Asks at the terminal itself (/dev/tty), not at standard input, so that nothing piped into the runner can answer.
 * Resolves to the line typed, or undefined without a terminal.
 */
export function askAtTerminal(question: string): Promise<string | undefined> {
  let fd: number
  try {
    fd = openSync('/dev/tty', 'r')
  } catch {
    return Promise.resolve(undefined)
  }
  const input = createReadStream('', { fd })
  const terminal = createInterface({ input, output: process.stdout, terminal: false })
  process.stdout.write(question)
  return new Promise((resolve) => {
    terminal.once('line', (line) => {
      terminal.close()
      input.destroy()
      resolve(line)
    })
    terminal.once('close', () => resolve(undefined))
  })
}
