import { lstatSync, readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'
import { allowed, type Account, type Role } from './accounts.ts'

// The production run's credentials (D-52, guards 1 and 2): a file outside the repository that only the owner can read,
// holding exactly the four keys, naming only the allowlisted accounts. No message here ever holds a value of the file.

/** Where the owner keeps the production accounts' passwords (D-51). Only the runner and the setup step read it. */
export const CREDENTIALS_PATH = join(homedir(), '.config', 'finance-tracker', 'e2e-prod.env')

export const KEYS = ['E2E_A_USERNAME', 'E2E_A_PASSWORD', 'E2E_B_USERNAME', 'E2E_B_PASSWORD'] as const

/** An account's login and password, as the file gives them. */
export interface Credential { account: Account; password: string }

/** Why the runner refuses the file; its message names the rule, never a value. */
export class CredentialsRefused extends Error {}

const refuse = (message: string): never => { throw new CredentialsRefused(message) }

/**
 * Checks the file and its folder (guard 1), then the logins against the allowlist (guard 2), and gives each account's
 * credential.
 *
 * @param uid the user the file must belong to: the one who runs the suite
 * @throws CredentialsRefused for the first rule broken
 */
export function readCredentials(path: string, accounts: Record<Role, Account>, uid = process.getuid!()): Record<Role, Credential> {
  const folder = dirname(path)
  const dir = stat(folder, 'The folder')
  if (dir.isSymbolicLink() || !dir.isDirectory()) refuse(`${folder} isn't a folder.`)
  if (dir.uid !== uid) refuse(`${folder} doesn't belong to you (uid ${uid}).`)
  if ((dir.mode & 0o777) !== 0o700) refuse(`${folder} has mode ${octal(dir.mode)}; it must be 700 (chmod 700 ${folder}).`)
  const file = stat(path, 'The credentials file')
  if (file.isSymbolicLink() || !file.isFile()) refuse(`${path} isn't a regular file.`)
  if (file.uid !== uid) refuse(`${path} doesn't belong to you (uid ${uid}).`)
  if ((file.mode & 0o777) !== 0o600) refuse(`${path} has mode ${octal(file.mode)}; it must be 600 (chmod 600 ${path}).`)

  const values = parse(readFileSync(path, 'utf8'), path)
  const credential = (role: Role): Credential => {
    const login = values.get(`E2E_${role}_USERNAME`)!
    const account = allowed(login, [accounts[role]])
    if (!account) {
      refuse(`E2E_${role}_USERNAME in ${path} isn't ${accounts[role].login}, the allowlisted account ${accounts[role].label}.`)
    }
    return { account: account!, password: values.get(`E2E_${role}_PASSWORD`)! }
  }
  return { A: credential('A'), B: credential('B') }
}

/**
 * The file's keys and values: `KEY=value` lines, blank lines and `#` comments allowed, a value in matching quotes taken
 * without them. Exactly the four keys, each once and not empty.
 */
export function parse(text: string, path: string): Map<string, string> {
  const values = new Map<string, string>()
  text.split(/\r?\n/).forEach((line, index) => {
    if (line.trim() === '' || line.trimStart().startsWith('#')) return
    const equals = line.indexOf('=')
    if (equals <= 0) refuse(`Line ${index + 1} of ${path} isn't KEY=value.`)
    const key = line.slice(0, equals)
    if (!(KEYS as readonly string[]).includes(key)) refuse(`${path} holds the key "${printable(key)}", which isn't one of ${KEYS.join(', ')}.`)
    if (values.has(key)) refuse(`${path} holds ${key} twice.`)
    let value = line.slice(equals + 1)
    const quoted = /^(["'])(.*)\1$/s.exec(value)
    if (quoted) value = quoted[2]
    if (value === '') refuse(`${key} in ${path} is empty.`)
    values.set(key, value)
  })
  const missing = KEYS.filter((k) => !values.has(k))
  if (missing.length > 0) refuse(`${path} lacks ${missing.join(', ')}.`)
  return values
}

function stat(path: string, what: string) {
  try {
    return lstatSync(path)
  } catch {
    return refuse(`${what} ${path} doesn't exist. See e2e/README.md, "One-time setup".`)
  }
}

const octal = (mode: number) => (mode & 0o777).toString(8)

/** A key as typed, for a message: never a value, and nothing that could move the terminal. */
const printable = (key: string) => key.replace(/[^\x20-\x7e]/g, '?').slice(0, 40)
