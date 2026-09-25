/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const backend = process.env.API_PROXY_TARGET ?? 'http://localhost:8081'

export default defineConfig({
  plugins: [react()],
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
  },
})
