import { test as base, expect, type Page } from '@playwright/test'
import { t } from './i18n.ts'
import { type E2eState, readState, storageStatePath } from './state.ts'
import { freshCode } from './totp.ts'

type User = 'admin' | 'reader' | 'passkey'

/**
 * `test.use({ as: 'reader' })` – angemeldet als dieser Testbenutzer (Anmeldung aus dem Projekt „login“).
 * `as: null` beginnt ohne Anmeldung.
 */
export const test = base.extend<{ as: User | null; state: E2eState }>({
  as: ['admin', { option: true }],
  // (der zweite Parameter heißt bei Playwright üblicherweise „use“ – hier anders, sonst hält ihn oxlint für einen React-Hook)
  storageState: async ({ as }, provide) => {
    await provide(as ? storageStatePath(as) : { cookies: [], origins: [] })
  },
  // eslint-disable-next-line no-empty-pattern
  state: async ({}, provide) => {
    await provide(readState())
  },
})

export { expect, t }

/** Über die Anmeldeseite anmelden – mit Code aus der App, wenn das Konto ihn verlangt. */
export async function signIn(page: Page, user: User) {
  const account = readState().users[user]
  await page.goto('/')
  await page.getByLabel(t('common.username'), { exact: true }).fill(account.username)
  await page.getByLabel(t('common.password'), { exact: true }).fill(account.password)
  await page.getByRole('button', { name: t('auth.login.submit'), exact: true }).click()
  if (account.totpSecret) {
    await expect(page.getByRole('heading', { name: t('auth.twoFactor.title') })).toBeVisible()
    await page.getByLabel(t('auth.twoFactor.code'), { exact: true }).fill(await freshCode(user))
    await page.getByRole('button', { name: t('auth.twoFactor.submit'), exact: true }).click()
  }
  await expect(page.getByRole('button', { name: t('shell.userMenu.label', { name: account.username }) })).toBeVisible()
}

/** Ein Eintrag im Benutzermenü oben rechts. */
export async function openUserMenu(page: Page, username: string, item: string) {
  await page.getByRole('button', { name: t('shell.userMenu.label', { name: username }) }).click()
  await page.getByRole('menuitem', { name: item }).click()
}
