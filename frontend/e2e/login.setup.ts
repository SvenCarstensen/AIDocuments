import { test as setup } from '@playwright/test'
import { expect, signIn, t } from './support/fixtures.ts'
import { readState, storageStatePath } from './support/state.ts'

// Meldet die Testbenutzer einmal über die Oberfläche an und merkt sich die Sitzungen für die übrigen Tests.

setup('falsches Passwort wird abgewiesen', async ({ page }) => {
  const { users } = readState()
  await page.goto('/')
  await page.getByLabel(t('common.username'), { exact: true }).fill(users.reader.username)
  await page.getByLabel(t('common.password'), { exact: true }).fill('ganz-falsch-123')
  await page.getByRole('button', { name: t('auth.login.submit'), exact: true }).click()
  // die Meldung kommt vom Server, in der Sprache der Oberfläche
  await expect(page.getByText('Benutzername oder Passwort ist nicht korrekt.')).toBeVisible()
  await expect(page.getByRole('heading', { name: t('auth.login.title') })).toBeVisible()
})

for (const user of ['admin', 'reader'] as const) {
  setup(`Anmeldung: ${user}`, async ({ page }) => {
    await signIn(page, user)
    await page.context().storageState({ path: storageStatePath(user) })
  })
}
