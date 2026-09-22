import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    // Mirrors the /api proxy in nginx.conf.
    proxy: {
      '/api': process.env.API_PROXY_TARGET ?? 'http://localhost:8080',
    },
  },
})
