import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { defineConfig } from '@playwright/test'

/**
 * Oberflächentests gegen eine Wegwerf-Instanz (siehe e2e/global-setup.ts): eigenes Backend auf BACKEND_PORT mit
 * leerem Datenverzeichnis, dazu der Vite-Entwicklungsserver auf UI_PORT. Eine laufende Installation bleibt unberührt.
 *
 *   npm run test:e2e
 *
 * Standardmäßig mit dem installierten Google Chrome; AIDOCS_E2E_BROWSER=chromium nimmt den Chromium von Playwright
 * (vorher einmal `npx playwright install chromium`).
 */
export const BACKEND_PORT = Number(process.env.AIDOCS_E2E_BACKEND_PORT ?? 8091)
export const UI_PORT = Number(process.env.AIDOCS_E2E_UI_PORT ?? 5198)
// Die Worker erben die Umgebung – so arbeiten alle mit demselben Verzeichnis
process.env.AIDOCS_E2E_DIR ??= join(tmpdir(), `aidocuments-e2e-${process.pid}`)
export const E2E_DIR = process.env.AIDOCS_E2E_DIR

const browser = process.env.AIDOCS_E2E_BROWSER ?? 'chrome'

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  // eine Instanz, ein Datenbestand: nacheinander
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  outputDir: 'test-results',
  use: {
    // „localhost“ statt 127.0.0.1: Passkeys brauchen einen Namen, keine IP-Adresse
    baseURL: `http://localhost:${UI_PORT}`,
    locale: 'de-DE',
    timezoneId: 'Europe/Berlin',
    trace: 'retain-on-failure',
    ...(browser === 'chromium' ? {} : { channel: browser }),
  },
  projects: [
    { name: 'login', testMatch: /login\.setup\.ts/ },
    { name: 'app', testMatch: /\.spec\.ts/, dependencies: ['login'] },
  ],
  webServer: {
    command: `node scripts/fetch-pdfjs.mjs && npx vite --host 127.0.0.1 --port ${UI_PORT} --strictPort`,
    url: `http://127.0.0.1:${UI_PORT}`,
    env: { AIDOCS_BACKEND: `http://127.0.0.1:${BACKEND_PORT}` },
    reuseExistingServer: false,
    timeout: 60_000,
  },
})
