/// <reference lib="dom" /> // page.evaluate läuft im Browser
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { expect, type Page, test } from '@playwright/test'
import { textIn } from '../e2e/support/i18n.ts'
import { totpAt } from '../e2e/support/totp.ts'
import { OUTPUT_DIR, SHOTS_DIR, UI_PORT } from './playwright.config.ts'
import { readShotState, writeShotState } from './setup.ts'

// Nimmt die Screenshots für README und GitHub auf – nacheinander, angemeldet als Admin der Wegwerf-Instanz.

const t = (key: string, values: Record<string, string | number> = {}) => textIn('en', key, values)
const session = () => join(SHOTS_DIR, 'alex.storage.json')

test.describe.configure({ mode: 'serial' })

test.beforeAll(async ({ browser }) => {
  mkdirSync(OUTPUT_DIR, { recursive: true })
  const state = readShotState()
  const page = await browser.newPage({ baseURL: `http://localhost:${UI_PORT}`, locale: 'en-GB', storageState: { cookies: [], origins: [] } })
  await page.goto('/')
  await page.getByLabel(t('common.username'), { exact: true }).fill(state.username)
  await page.getByLabel(t('common.password'), { exact: true }).fill(state.password)
  await page.getByRole('button', { name: t('auth.login.submit'), exact: true }).click()
  // Jeder Code gilt nur einmal; die Einrichtung hat schon einen verbraucht
  let step = Math.max(Math.floor(Date.now() / 30_000), state.totpStep + 1)
  while (step > Math.floor(Date.now() / 30_000) + 1) await page.waitForTimeout(1000)
  await page.getByLabel(t('auth.twoFactor.code'), { exact: true }).fill(totpAt(state.totpSecret, step))
  await page.getByRole('button', { name: t('auth.twoFactor.submit'), exact: true }).click()
  await expect(page.getByRole('button', { name: t('shell.userMenu.label', { name: state.username }) })).toBeVisible()
  writeShotState({ ...state, totpStep: step })
  await page.context().storageState({ path: session() })
  await page.close()
})

test.use({ storageState: session() })

/** Wartet, bis Bilder, Schriften und Ladeanzeigen fertig sind, und speichert das Bild. */
async function shot(page: Page, name: string) {
  await page.waitForLoadState('networkidle')
  await page.evaluate(() => document.fonts.ready)
  await page.waitForFunction(() => [...document.images].every((image) => image.complete))
  await page.waitForTimeout(400)
  await page.screenshot({ path: join(OUTPUT_DIR, `${name}.png`), animations: 'disabled', caret: 'hide' })
}

/** Wartet, bis PDF.js die erste Seite gezeichnet hat. */
async function pdfRendered(page: Page) {
  await expect(page.frameLocator('iframe').locator('.page[data-loaded="true"]').first()).toBeVisible({ timeout: 30_000 })
}

const doc = (file: string) => `/documents/${readShotState().documents[file]}`

test('documents', async ({ page }) => {
  await page.goto('/documents')
  await expect(page.getByText('First aid certificate')).toBeVisible()
  await shot(page, 'documents')
})

test('documents-list', async ({ page }) => {
  await page.goto('/documents')
  await page.getByRole('button', { name: t('documents.view.list') }).click()
  await expect(page.getByText('First aid certificate')).toBeVisible()
  await shot(page, 'documents-list')
})

test('documents-dark', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.goto('/documents')
  await expect(page.getByText('First aid certificate')).toBeVisible()
  await shot(page, 'documents-dark')
})

test('ai-search', async ({ page }) => {
  await page.goto('/documents')
  const search = page.getByRole('textbox', { name: t('shell.search.label') })
  await search.fill('all invoices from this year')
  await search.press('Enter')
  await expect(page).toHaveURL(/ai=/)
  await expect(page.getByText('Electricity invoice', { exact: false }).first()).toBeVisible()
  await shot(page, 'ai-search')
})

test('document', async ({ page }) => {
  await page.goto(doc('brightwave-invoice.pdf'))
  await pdfRendered(page)
  await shot(page, 'document')
})

test('ask', async ({ page }) => {
  await page.goto(doc('brightwave-invoice.pdf'))
  await page.getByRole('button', { name: t('document.tabs.chat'), exact: true }).click()
  await page.getByRole('button', { name: t('document.chat.suggestions.deadlines') }).first().click()
  await expect(page.getByText('nothing you need to pay by hand')).toBeVisible({ timeout: 30_000 })
  await pdfRendered(page)
  await shot(page, 'ask')
})

test('shared-document', async ({ page }) => {
  await page.goto(doc('kestrel-tenancy-agreement.pdf'))
  await expect(page.getByText('Letting agent')).toBeVisible()
  await pdfRendered(page)
  await shot(page, 'shared-document')
})

test('edit-pages', async ({ page }) => {
  await page.goto(doc('kestrel-tenancy-agreement.pdf'))
  await page.getByRole('button', { name: t('pages.button'), exact: true }).click()
  const dialog = page.getByRole('dialog', { name: t('pages.title') })
  await expect(dialog.getByRole('img')).toHaveCount(3)
  await shot(page, 'edit-pages')
})

test('reminders', async ({ page }) => {
  await page.goto('/reminders')
  await expect(page.getByRole('heading', { name: t('reminders.title') })).toBeVisible()
  await expect(page.getByText('Submit receipt to health insurer')).toBeVisible()
  await shot(page, 'reminders')
})

test('contracts', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 480 })
  await page.goto('/contracts')
  await expect(page.getByText('Motor insurance renewal')).toBeVisible()
  await shot(page, 'contracts')
})

test('settings-backup', async ({ page }) => {
  await page.goto('/settings/backup')
  await expect(page.getByRole('heading', { name: t('settings.backup.title') })).toBeVisible()
  await shot(page, 'settings-backup')
})
