/// <reference types="vitest/config" />
import { defineConfig, type Connect, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

const backend = process.env.API_PROXY_TARGET ?? 'http://localhost:8081'

/** Static pages of public/ at their address without .html, as nginx serves them (web.conf). */
const STATIC_PAGES = ['/privacy']

function staticPages(): Plugin {
  const rewrite: Connect.NextHandleFunction = (request, _response, next) => {
    const path = request.url?.split('?')[0]
    if (path && STATIC_PAGES.includes(path)) request.url = `${path}.html`
    next()
  }
  return {
    name: 'static-pages',
    configureServer: (server) => { server.middlewares.use(rewrite) },
    configurePreviewServer: (server) => { server.middlewares.use(rewrite) },
  }
}

export default defineConfig({
  plugins: [react(), staticPages()],
  server: {
    // Keycloak sends the browser back to exactly this port (registered redirect URI), so never move to another.
    strictPort: true,
    // Mirrors nginx.conf: the API, and the backend's login, login callback and logout. The Host header must stay
    // localhost:5173 (the string shorthand would rewrite it), so the backend builds its redirect URIs for this server.
    proxy: Object.fromEntries(['/api', '/oauth2', '/login/oauth2', '/logout']
      .map((path) => [path, { target: backend, changeOrigin: false }])),
  },
  test: {
    // Component tests render into a simulated DOM; the rest are plain functions.
    environment: 'jsdom',
    // The browser's zone in the tests, so that the saved zone of a test's user (UTC) is the browser's and no hint shows;
    // a test that wants another zone sets process.env.TZ itself (D-101).
    env: { TZ: 'UTC' },
  },
})
