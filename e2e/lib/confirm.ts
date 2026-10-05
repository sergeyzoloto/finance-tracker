import { closeSync, constants, openSync, readSync } from 'node:fs'

// The production run's confirmation (D-52, guard 6): typed at the terminal by the owner, never piped or pasted ahead.

export const CONFIRMATION = 'E2E PROD'

/** Whether the line typed is exactly the confirmation; anything else stops the run with nothing done. */
export const confirmed = (line: string | undefined) => line === CONFIRMATION

/** The controlling terminal; the terminal tests pass another path only to show what a missing one does. */
export const TTY = '/dev/tty'

/** How long the terminal must stay quiet before the question, as deploy.sh's `discard_waiting` waits (`read -t 0.2`). */
const QUIET_MS = 200

/**
 * Asks at the terminal itself (/dev/tty), not at standard input, so that nothing piped into the runner can answer.
 * Lines already waiting at the terminal (a pasted block's leftovers) are discarded first, as deploy.sh does before its
 * questions (QA-1b), so only a line typed after the question counts. Resolves to that line without its newline, to ''
 * when the terminal closes (Ctrl-D) before a whole line, or to undefined without a terminal.
 *
 * The reads are synchronous on purpose: nothing else runs while the runner waits, a read never runs ahead past the
 * answer's line, and no event can settle the answer before the line does. (QA-1's version used readline and settled
 * on its 'close' event, which `close()` emits synchronously inside the 'line' handler: every answer read as undefined,
 * "No terminal to type the confirmation at".)
 */
export function askAtTerminal(question: string, tty = TTY, write = (text: string) => { process.stdout.write(text) }): Promise<string | undefined> {
  let fd: number
  try {
    fd = openSync(tty, constants.O_RDONLY)
  } catch {
    return Promise.resolve(undefined)
  }
  try {
    const discarded = discardWaiting(tty)
    if (discarded > 0) {
      write(`(${discarded} line(s) were waiting at the terminal before this question, pasted ahead: discarded. `
        + 'Type the answer after the question.)\n')
    }
    write(question)
    return Promise.resolve(readLine(fd))
  } finally {
    closeSync(fd)
  }
}

/**
 * Reads and drops whatever is waiting at the terminal until it has been quiet for QUIET_MS, through a non-blocking
 * descriptor of its own; resolves to the number of lines dropped. A line still being typed (no newline yet) can't be
 * read in the terminal's line mode and stays: it becomes the start of the answer, which then isn't the confirmation.
 */
function discardWaiting(tty: string): number {
  const fd = openSync(tty, constants.O_RDONLY | constants.O_NONBLOCK)
  try {
    const buffer = Buffer.alloc(4096)
    let lines = 0
    for (let round = 0; round < 1000; round += 1) {
      let got = 0
      for (;;) {
        const n = read(fd, buffer)
        if (n <= 0) break
        got += n
        const text = buffer.subarray(0, n).toString('utf8')
        lines += text.split('\n').length - (text.endsWith('\n') ? 1 : 0)
      }
      if (got === 0 && round > 0) break
      sleep(QUIET_MS)
    }
    return lines
  } finally {
    closeSync(fd)
  }
}

/** One line from a blocking descriptor: reads until the newline, never past the read that holds it. */
function readLine(fd: number): string {
  const buffer = Buffer.alloc(4096)
  const parts: Buffer[] = []
  for (;;) {
    const n = read(fd, buffer)
    if (n === 0) return ''
    if (n < 0) {
      sleep(50)
      continue
    }
    const chunk = buffer.subarray(0, n)
    const newline = chunk.indexOf(0x0a)
    if (newline >= 0) {
      parts.push(Buffer.from(chunk.subarray(0, newline)))
      return Buffer.concat(parts).toString('utf8').replace(/\r$/, '')
    }
    parts.push(Buffer.from(chunk))
  }
}

/** readSync, with -1 for "nothing yet" (EAGAIN) and a retry after a signal (EINTR). */
function read(fd: number, buffer: Buffer): number {
  for (;;) {
    try {
      return readSync(fd, buffer, 0, buffer.length, null)
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code
      if (code === 'EAGAIN' || code === 'EWOULDBLOCK') return -1
      if (code !== 'EINTR') throw error
    }
  }
}

function sleep(ms: number) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms)
}
