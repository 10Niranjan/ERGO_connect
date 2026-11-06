import react from '@vitejs/plugin-react'
import path from 'node:path'
import { defineConfig } from 'vite'

// Same-origin in dev: the backend's CORS allow-list stays strict and no URLs leak into the bundle.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
  // Every asset becomes a file: the CSP in nginx.conf.template allows no data: images or fonts, so an inlined small
  // asset would be blocked without any build error.
  build: { assetsInlineLimit: 0 },
})
