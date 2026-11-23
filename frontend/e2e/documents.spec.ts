import { readFileSync } from 'node:fs'
import { expect, t, test } from './support/fixtures.ts'

const isoInMonths = (months: number) => {
  const date = new Date()
  date.setDate(1)
  date.setMonth(date.getMonth() + months)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-01`
}

test.describe('Dokumentansicht als Admin', () => {
  test('Vertrag erfassen: Kündigungsfrist steht in der Karte und in der Wiedervorlage', async ({ page, state }) => {
    await page.goto(`/documents/${state.documents.contract}`)
    // die Karte: Überschrift → Kopfzeile → Karte
    const card = page.getByRole('heading', { name: t('contracts.cardTitle'), exact: true }).locator('xpath=../..')
    await card.getByRole('button', { name: t('contracts.add') }).click()

    const dialog = page.getByRole('dialog')
    await dialog.getByLabel(t('contracts.fields.partner')).fill('Stadtwerke Test')
    await dialog.getByLabel(t('contracts.fields.endDate')).fill(isoInMonths(14))
    await dialog.getByLabel(t('contracts.fields.notice')).fill('3')
    await dialog.getByLabel(t('contracts.fields.renewal')).fill('12')
    await dialog.getByRole('button', { name: t('common.save'), exact: true }).click()
    await expect(page.getByText(t('contracts.saved'))).toBeVisible()
    await expect(dialog).toBeHidden()

    // Die Karte zeigt die Frist; dieselbe Angabe steht – vom Server formatiert – in der Wiedervorlage
    await expect(card.getByText('Stadtwerke Test')).toBeVisible()
    const deadline = (await card.locator('dt', { hasText: t('contracts.fields.deadline') }).locator('xpath=following-sibling::dd[1]').innerText()).trim()
    expect(deadline).toMatch(/^\d{1,2}\. \p{L}+ \d{4}$/u)
    const note = page.getByText(/Vertrag kündigen bis spätestens/).first()
    await expect(note).toContainText(deadline)
    await expect(note).not.toContainText('{0')
    await expect(card.getByText(t('contracts.status.ACTIVE'))).toBeVisible()
  })

  test('Seiten bearbeiten: verschieben, drehen, dann abtrennen', async ({ page, state }) => {
    const id = state.documents.threePages
    await page.goto(`/documents/${id}`)
    await page.getByRole('button', { name: t('pages.button'), exact: true }).click()
    const dialog = page.getByRole('dialog', { name: t('pages.title') })
    const pages = dialog.getByRole('listitem')
    await expect(pages).toHaveCount(3)

    // die dritte Seite nach vorn, die (neue) erste drehen
    await pages.nth(2).getByRole('button', { name: t('pages.moveLeft') }).click()
    await pages.nth(1).getByRole('button', { name: t('pages.moveLeft') }).click()
    await expect(pages.nth(0).getByText(t('pages.wasPage', { number: 3 }))).toBeVisible()
    await pages.nth(0).getByRole('button', { name: t('pages.rotate') }).click()
    // solange etwas ungespeichert ist, lässt sich nicht trennen
    await expect(pages.nth(1).getByRole('button', { name: t('pages.saveFirst') })).toBeDisabled()
    await dialog.getByRole('button', { name: t('common.save'), exact: true }).click()
    await expect(page.getByText(t('pages.saved'))).toBeVisible()

    const saved = await (await page.request.get(`/api/documents/${id}`)).json()
    expect(saved.pageCount).toBe(3)

    // ab Seite 3 ein eigenes Dokument
    await page.getByRole('button', { name: t('pages.button'), exact: true }).click()
    await pages.nth(2).getByRole('button', { name: t('pages.split.here') }).click()
    await page.getByRole('dialog', { name: t('pages.split.title') }).getByRole('button', { name: t('pages.split.confirm') }).click()
    await expect(page.getByText(t('pages.split.done'))).toBeVisible()
    const after = await (await page.request.get(`/api/documents/${id}`)).json()
    expect(after.pageCount).toBe(2)
  })
})

test.describe('E-Rechnung', () => {
  test('XRechnung hochladen: exakte Angaben in der Karte, die XML-Datei unverändert zurück', async ({ page }) => {
    const xml = readFileSync(new URL('../../backend/src/test/resources/einvoice/xrechnung-cii.xml', import.meta.url))
    await page.goto('/documents')
    const uploaded = page.waitForResponse((response) => response.url().endsWith('/api/documents') && response.request().method() === 'POST')
    // über die Dateiauswahl der Oberfläche – sie muss XML anbieten
    await page.locator('input[type=file]').setInputFiles({ name: 'xrechnung-cii.xml', mimeType: 'application/xml', buffer: xml })
    const [result] = await (await uploaded).json()
    expect(result.outcome).toBe('CREATED')
    await expect
      .poll(async () => (await (await page.request.get(`/api/documents/${result.documentId}`)).json()).status, { timeout: 30_000 })
      .not.toMatch(/PENDING|PROCESSING/)

    await page.goto(`/documents/${result.documentId}`)
    const card = page.getByRole('heading', { name: t('einvoice.title'), exact: true }).locator('xpath=../..')
    await expect(card.getByText(/^144,78\s€$/)).toBeVisible()
    await expect(card.getByText(t('einvoice.dueOn', { date: '15.12.2026' }))).toBeVisible()
    await expect(card.getByText('Stadtwerke Musterstadt GmbH')).toBeVisible()
    await expect(card.getByText(`${t('einvoice.fields.vatId')} DE123456789`)).toBeVisible()
    await expect(card.getByText('DE02120300000000202051')).toBeVisible()
    await expect(card.getByText(new RegExp(`^${t('einvoice.fields.tax', { rate: '19\\s%' })}$`))).toBeVisible()
    await expect(card.getByText(t('einvoice.exact', { format: 'XRechnung 3.0 (CII)' }))).toBeVisible()

    await card.getByRole('button', { name: t('einvoice.copy', { label: 'IBAN' }) }).click()
    await expect(page.getByText(t('einvoice.copied', { label: 'IBAN' }))).toBeVisible()

    await card.getByRole('button', { name: t('einvoice.lines.show_other', { count: 3 }) }).click()
    await expect(card.getByRole('cell', { name: 'Ratgeber „Energie sparen im Haushalt“' })).toBeVisible()
    await expect(card.getByRole('cell', { name: '412,5 kWh' })).toBeVisible()
    await expect(card.getByRole('cell', { name: /^0,312\s€$/ })).toBeVisible()

    const download = page.waitForEvent('download')
    await card.getByRole('link', { name: t('einvoice.downloadXml') }).click()
    const file = await download
    expect(file.suggestedFilename()).toBe('xrechnung-cii.xml')
    expect(readFileSync(await file.path())).toEqual(xml)
  })
})

test.describe('Dokumentansicht nur mit Leserecht', () => {
  test.use({ as: 'reader' })

  test('zeigt den Hinweis und keine Werkzeuge zum Ändern', async ({ page, state }) => {
    await page.goto(`/documents/${state.documents.readOnly}`)
    await expect(page.getByText(t('document.readOnly'))).toBeVisible()
    await expect(page.getByRole('button', { name: t('pages.button'), exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: t('contracts.add') })).toHaveCount(0)
    await expect(page.getByRole('heading', { name: t('contracts.cardTitle'), exact: true })).toHaveCount(0)
  })

  test('sieht keine Einstellungen und keinen Sicherungshinweis', async ({ page }) => {
    await page.goto('/settings/backup')
    await expect(page).toHaveURL(/\/documents$/)
    await expect(page.getByText(t('documents.backupNotice.action'))).toHaveCount(0)
  })
})
