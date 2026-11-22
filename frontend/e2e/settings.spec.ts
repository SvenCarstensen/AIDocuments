import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { expect, openUserMenu, t, test } from './support/fixtures.ts'

test('E-Mail-Benachrichtigungen bleiben gespeichert', async ({ page, state }) => {
  const admin = state.users.admin.username
  await page.goto('/documents')
  await openUserMenu(page, admin, t('shell.notifications.title'))
  let dialog = page.getByRole('dialog', { name: t('shell.notifications.title') })
  await dialog.getByLabel(t('shell.profile.email')).fill('e2e-admin@example.test')
  const digest = dialog.getByRole('switch', { name: t('shell.notifications.digest') })
  await expect(digest).toHaveAttribute('aria-checked', 'false')
  await digest.click()
  await dialog.getByRole('button', { name: t('common.save'), exact: true }).click()
  await expect(page.getByText(t('shell.profile.saved'))).toBeVisible()

  await page.reload()
  await openUserMenu(page, admin, t('shell.notifications.title'))
  dialog = page.getByRole('dialog', { name: t('shell.notifications.title') })
  await expect(dialog.getByLabel(t('shell.profile.email'))).toHaveValue('e2e-admin@example.test')
  await expect(dialog.getByRole('switch', { name: t('shell.notifications.digest') })).toHaveAttribute('aria-checked', 'true')
})

test.describe('Sicherung', () => {
  test('warnt ohne Sicherung, sichert automatisch in einen Ordner und behält die Stände', async ({ page, state }) => {
    // noch nie gesichert: Hinweis in der Dokumentliste, der zur Sicherung führt
    await page.goto('/documents')
    await expect(page.getByText(t('documents.backupNotice.never'))).toBeVisible()
    await page.getByRole('link', { name: t('documents.backupNotice.action') }).click()
    await expect(page).toHaveURL(/\/settings\/backup$/)

    const card = page.getByRole('heading', { name: t('settings.backup.auto.title') }).locator('xpath=../..')
    await expect(card.getByRole('button', { name: t('settings.backup.auto.runNow') })).toBeEnabled()
    await card.getByRole('switch', { name: t('settings.backup.auto.enable') }).click()
    // ein relativer Pfad wird abgewiesen – mit der Meldung des Servers
    const directory = card.getByLabel(t('settings.backup.auto.directory'))
    await directory.fill('sicherungen')
    await card.getByRole('button', { name: t('common.save'), exact: true }).click()
    await expect(card.getByText('Bitte einen absoluten Pfad angeben')).toBeVisible()

    const target = join(state.dir, 'sicherungen')
    await directory.fill(target)
    await card.getByLabel(t('settings.backup.auto.keep')).fill('2')
    // ungespeichert lässt sich nicht sichern
    await expect(card.getByRole('button', { name: t('settings.backup.auto.runNow') })).toBeDisabled()
    await card.getByRole('button', { name: t('common.save'), exact: true }).click()
    await expect(page.getByText(t('settings.backup.auto.saved'))).toBeVisible()
    await expect(card.getByText(t('settings.backup.auto.nextRun'))).toBeVisible()

    await card.getByRole('button', { name: t('settings.backup.auto.runNow') }).click()
    await expect(page.getByText(t('settings.backup.auto.done'))).toBeVisible({ timeout: 30_000 })
    await expect(card.getByText(/^AIDocuments-Sicherung-\d{4}-\d{2}-\d{2}-\d{6}\.zip$/)).toHaveCount(1)
    await expect(card.getByText(t('settings.backup.auto.stored_one', { count: 1, directory: target }))).toBeVisible()
    expect(existsSync(target) && readdirSync(target).filter((name) => name.endsWith('.zip'))).toHaveLength(1)

    // gesichert: kein Hinweis mehr
    await page.goto('/documents')
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.getByText(t('documents.backupNotice.never'))).toHaveCount(0)
  })
})
