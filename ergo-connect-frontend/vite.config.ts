import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Same-origin in dev: the backend's CORS allow-list stays strict and no URLs leak into the bundle.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
})
