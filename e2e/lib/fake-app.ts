import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import type { AddressInfo } from 'node:net'

// A fake app and a fake Keycloak on loopback ports, for the guards' tests (lib/guards.test.ts) only: just enough of the
// BFF's login, /api/me, Delete all my data and logout for the suite's sign-in, identity check and cleanup to run for
// real. Every request is logged, so a test can tell exactly what reached "the app".

export interface Logged { server: 'app' | 'keycloak'; method: string; path: string; user?: string }

export interface FakeOptions {
  /** What /api/me names for each login: an account's name, or someone else's. */
  names: Record<string, string>
  /** The email /api/me reports for each login (D-54): an account's, or someone else's. */
  emails: Record<string, string>
  /** The password each login must give at the fake Keycloak. */
  passwords: Record<string, string>
  familyLedgers?: boolean
}

export interface Fake { appPort: number; keycloakPort: number; log: Logged[]; close: () => Promise<void> }

const PAGE = `<!doctype html><html><head><meta charset="utf-8"><title>fake app</title></head><body><p>fake app</p>
<script>fetch('/api/me').then(() => Promise.all([fetch('/api/entries?size=1'), fetch('/api/family-ledgers')]))</script>
</body></html>`

export async function startFake(options: FakeOptions): Promise<Fake> {
  const log: Logged[] = []
  let appPort = 0
  let keycloakPort = 0
  const cookies = (req: IncomingMessage) => Object.fromEntries((req.headers.cookie ?? '').split('; ').filter(Boolean)
    .map((c) => [c.slice(0, c.indexOf('=')), decodeURIComponent(c.slice(c.indexOf('=') + 1))]))
  const userOf = (req: IncomingMessage) => {
    const session = cookies(req).SESSION
    return session?.startsWith('s-') ? session.slice(2) : undefined
  }
  const redirect = (res: ServerResponse, to: string, headers: Record<string, string | string[]> = {}) => {
    res.writeHead(302, { location: to, ...headers }).end()
  }

  const app = createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', `http://127.0.0.1:${appPort}`)
    const user = userOf(req)
    log.push({ server: 'app', method: req.method ?? '', path: url.pathname + url.search, user })
    const keycloak = `http://127.0.0.1:${keycloakPort}`
    if (url.pathname === '/oauth2/authorization/keycloak') return redirect(res, `${keycloak}/auth`)
    if (url.pathname === '/login/callback') {
      const login = url.searchParams.get('user') ?? ''
      return redirect(res, '/?login=done', { 'set-cookie': [`SESSION=s-${login}; Path=/; HttpOnly`, `XSRF-TOKEN=x-${login}; Path=/`] })
    }
    if (url.pathname === '/') return void res.writeHead(200, { 'content-type': 'text/html' }).end(PAGE)
    if (url.pathname === '/logout' && req.method === 'POST') {
      const body = await text(req)
      if (!user || new URLSearchParams(body).get('_csrf') !== `x-${user}`) return void res.writeHead(403).end()
      return redirect(res, `${keycloak}/logout`, { 'set-cookie': 'SESSION=; Path=/; Max-Age=0' })
    }
    if (!url.pathname.startsWith('/api/')) return void res.writeHead(404).end()
    if (!user) return void res.writeHead(401).end()
    if (url.pathname === '/api/me') {
      return json(res, { name: options.names[user] ?? user, email: options.emails[user] ?? null,
        features: { familyLedgers: options.familyLedgers ?? true } })
    }
    if (url.pathname === '/api/me/data' && req.method === 'DELETE') {
      return void res.writeHead(req.headers['x-xsrf-token'] === `x-${user}` ? 204 : 403).end()
    }
    return json(res, [])
  })

  const keycloak = createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', `http://127.0.0.1:${keycloakPort}`)
    const form = req.method === 'POST' ? new URLSearchParams(await text(req)) : undefined
    const login = form?.get('username') ?? undefined
    log.push({ server: 'keycloak', method: req.method ?? '', path: url.pathname, user: login })
    if (url.pathname === '/auth' && req.method === 'GET') return void res.writeHead(200, { 'content-type': 'text/html' }).end(loginPage())
    if (url.pathname === '/login-actions/authenticate' && form) {
      if (login && options.passwords[login] !== undefined && form.get('password') === options.passwords[login]) {
        return redirect(res, `http://127.0.0.1:${appPort}/login/callback?user=${encodeURIComponent(login)}`)
      }
      return void res.writeHead(200, { 'content-type': 'text/html' }).end(loginPage('Invalid username or password.'))
    }
    if (url.pathname === '/logout') return redirect(res, `http://127.0.0.1:${appPort}/`)
    res.writeHead(404).end()
  })

  appPort = await listen(app)
  keycloakPort = await listen(keycloak)
  return {
    appPort, keycloakPort, log,
    close: async () => { await Promise.all([close(app), close(keycloak)]) },
  }
}

function loginPage(error?: string) {
  return `<!doctype html><html><head><meta charset="utf-8"><title>Sign in</title></head><body>
<h1 id="kc-page-title">Sign in to your account</h1>${error ? `<span id="input-error">${error}</span>` : ''}
<form method="post" action="/login-actions/authenticate"><input id="username" name="username"><input id="password" name="password" type="password">
<button id="kc-login" type="submit">Sign In</button></form></body></html>`
}

function json(res: ServerResponse, body: unknown) {
  res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(body))
}

function text(req: IncomingMessage): Promise<string> {
  return new Promise((resolve) => {
    let body = ''
    req.on('data', (chunk) => { body += chunk })
    req.on('end', () => resolve(body))
  })
}

function listen(server: Server): Promise<number> {
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve((server.address() as AddressInfo).port)))
}

function close(server: Server): Promise<void> {
  return new Promise((resolve) => { server.closeAllConnections(); server.close(() => resolve()) })
}
