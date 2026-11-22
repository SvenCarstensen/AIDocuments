import { expect, openUserMenu, signIn, t, test } from './support/fixtures.ts'

// Ein virtueller Authenticator im Testbrowser (Chrome DevTools Protocol) – kein echter Passkey, nichts im
// Schlüsselbund des Rechners.
test.use({ as: null })

test('Passkey anlegen und damit ohne Passwort anmelden', async ({ page, state, browserName }) => {
  test.skip(browserName !== 'chromium', 'virtueller Authenticator nur in Chromium/Chrome')
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('WebAuthn.enable')
  await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2',
      transport: 'internal',
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
      automaticPresenceSimulation: true,
    },
  })

  const user = state.users.passkey
  await signIn(page, 'passkey')
  await openUserMenu(page, user.username, t('passkeys.title'))
  const dialog = page.getByRole('dialog', { name: t('passkeys.title') })
  await expect(dialog.getByText(t('passkeys.none'))).toBeVisible()
  await dialog.getByRole('button', { name: t('passkeys.add') }).click()
  await dialog.getByLabel(t('passkeys.name')).fill('Testbrowser')
  await dialog.getByLabel(t('passkeys.password')).fill(user.password)
  await dialog.getByRole('button', { name: t('passkeys.create') }).click()
  await expect(page.getByText(t('passkeys.added'))).toBeVisible()
  await expect(dialog.getByText('Testbrowser')).toBeVisible()
  await page.keyboard.press('Escape')

  await openUserMenu(page, user.username, t('shell.userMenu.logout'))
  await expect(page.getByRole('heading', { name: t('auth.login.title') })).toBeVisible()
  await page.getByRole('button', { name: t('auth.login.passkey') }).click()
  await expect(page.getByRole('button', { name: t('shell.userMenu.label', { name: user.username }) })).toBeVisible()
})

test('über eine IP-Adresse bietet die Anmeldeseite keinen Passkey an', async ({ page, baseURL }) => {
  await page.goto(baseURL!.replace('localhost', '127.0.0.1'))
  await expect(page.getByRole('heading', { name: t('auth.login.title') })).toBeVisible()
  await expect(page.getByRole('button', { name: t('auth.login.passkey') })).toHaveCount(0)
})
