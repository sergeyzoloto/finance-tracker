import { spawn, type ChildProcess } from 'node:child_process'

// A command in a real pseudo-terminal, for the terminal tests (lib/terminal.test.ts): util-linux's script(1) gives it a
// pseudo-terminal as its controlling terminal, with echo on, and copies what is written here to it as if typed or pasted.

export class Pty {
  private text = ''
  private readonly child: ChildProcess
  readonly exited: Promise<number>

  constructor(command: string, env: NodeJS.ProcessEnv, cwd: string) {
    this.child = spawn('script', ['--quiet', '--return', '--command', command, '/dev/null'],
      { cwd, env: { ...env, TERM: 'xterm' }, stdio: ['pipe', 'pipe', 'pipe'] })
    this.child.stdout!.on('data', (chunk) => { this.text += chunk.toString() })
    this.child.stderr!.on('data', (chunk) => { this.text += chunk.toString() })
    this.exited = new Promise((resolve) => this.child.on('exit', (code) => resolve(code ?? -1)))
  }

  /** Everything the terminal showed so far. */
  get output() { return this.text }

  /** Types or pastes this into the terminal. */
  write(text: string) { this.child.stdin!.write(text) }

  /** Resolves once the terminal has shown text matching the pattern after `from` characters of output. */
  async waitFor(pattern: RegExp, timeoutMs = 60_000, from = 0): Promise<void> {
    const end = Date.now() + timeoutMs
    while (!pattern.test(this.text.slice(from))) {
      if (Date.now() > end) throw new Error(`Timed out waiting for ${pattern}; the terminal showed:\n${this.text}`)
      await new Promise((r) => setTimeout(r, 50))
    }
  }

  /** Ends the session: its shell's stdin closes, then the session is killed if it is still there. */
  async close(): Promise<number> {
    this.child.stdin!.end()
    const timer = setTimeout(() => this.child.kill('SIGKILL'), 10_000)
    const code = await this.exited
    clearTimeout(timer)
    return code
  }
}
