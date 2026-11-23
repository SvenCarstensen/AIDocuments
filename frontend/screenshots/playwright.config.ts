import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { defineConfig } from '@playwright/test'

/**
 * Screenshots der wichtigsten Seiten für README und GitHub – englische Oberfläche, englische Beispieldokumente,
 * eine Wegwerf-Instanz mit Schein-KI (siehe setup.ts). Eine laufende Installation bleibt unberührt.
 *
 *   npm run screenshots
 *
 * Die Bilder landen in docs/screenshots/.
 */
export const BACKEND_PORT = Number(process.env.AIDOCS_SHOTS_BACKEND_PORT ?? 8093)
export const MOCK_AI_PORT = Number(process.env.AIDOCS_SHOTS_AI_PORT ?? 8094)
export const UI_PORT = Number(process.env.AIDOCS_SHOTS_UI_PORT ?? 5197)
// kurzer, neutraler Pfad – der Sicherungsordner darin ist auf einem Bild zu sehen
process.env.AIDOCS_SHOTS_DIR ??= process.platform === 'win32' ? join(tmpdir(), 'aidocuments-screenshots') : '/tmp/aidocuments-screenshots'
export const SHOTS_DIR = process.env.AIDOCS_SHOTS_DIR
export const OUTPUT_DIR = resolve(import.meta.dirname, '../../docs/screenshots')

const browser = process.env.AIDOCS_E2E_BROWSER ?? 'chrome'

export default defineConfig({
  testDir: '.',
  testMatch: /\.shots\.ts/,
  globalSetup: './setup.ts',
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  outputDir: 'test-results/screenshots',
  use: {
    baseURL: `http://localhost:${UI_PORT}`,
    locale: 'en-GB',
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    colorScheme: 'light',
    ...(browser === 'chromium' ? {} : { channel: browser }),
    // Datumsfelder zeigen ihr Format in der Sprache des Browsers, nicht der Seite
    launchOptions: { args: ['--lang=en-GB'] },
  },
  webServer: {
    command: `node scripts/fetch-pdfjs.mjs && npx vite --host 127.0.0.1 --port ${UI_PORT} --strictPort`,
    cwd: resolve(import.meta.dirname, '..'),
    url: `http://127.0.0.1:${UI_PORT}`,
    env: { AIDOCS_BACKEND: `http://127.0.0.1:${BACKEND_PORT}` },
    reuseExistingServer: false,
    timeout: 60_000,
  },
})
