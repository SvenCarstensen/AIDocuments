import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // Im Entwicklungsmodus leitet Vite alle API-Aufrufe an das Spring-Boot-Backend weiter.
    proxy: {
      '/api': 'http://127.0.0.1:8080',
    },
  },
})
