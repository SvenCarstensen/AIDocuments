import { readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { chromium } from '@playwright/test'
import { Client, startBackend } from '../e2e/support/instance.ts'
import { totpAt } from '../e2e/support/totp.ts'
import { startMockAi } from './mock-ai.ts'
import { type Analysis, day, samples, STYLE } from './samples.ts'
import { BACKEND_PORT, MOCK_AI_PORT, SHOTS_DIR } from './playwright.config.ts'

/** Was die Screenshot-Aufnahmen von der vorbereiteten Instanz wissen müssen. */
export interface ShotState {
  username: string
  password: string
  totpSecret: string
  totpStep: number
  /** Dokument-ID je Dateiname */
  documents: Record<string, string>
}

const statePath = () => join(SHOTS_DIR, 'state.json')
export const readShotState = (): ShotState => JSON.parse(readFileSync(statePath(), 'utf8')) as ShotState
export const writeShotState = (state: ShotState) => writeFileSync(statePath(), JSON.stringify(state, null, 2))

export default async function setup() {
  const list = samples()
  const thisYear = new Date().getFullYear()
  const mock = await startMockAi(MOCK_AI_PORT, {
    analyses: new Map<string, Analysis>(list.map((s) => [s.file, s.analysis])),
    // „All invoices from this year“ – die KI-Suche liefert den gespeicherten (deutschen) Namen der Dokumentart
    search: { text: '', category: 'Rechnung', correspondent: '', tags: [], from: `${thisYear}-01-01`, to: '' },
    chat: (question) => /deadline|date|due/i.test(question)
      ? `Yes. The invoice of **€142.60** is due by **${day(9).long}**. It is collected by direct debit from the `
        + 'account ending in 8817, so there is nothing you need to pay by hand.\n\nThe next meter reading is taken '
        + 'automatically by the smart meter.'
      : 'This is an electricity invoice from Brightwave Energy for 508 kWh, €142.60 in total.',
  })
  const instance = await startBackend(SHOTS_DIR, BACKEND_PORT)
  const stopAll = async () => {
    await instance.stop()
    await new Promise((done) => mock.close(done))
  }
  try {
    writeShotState(await seed(instance.base, instance.setupCode))
  } catch (error) {
    await stopAll()
    throw error
  }
  return async () => {
    await stopAll()
    if (!process.env.AIDOCS_SHOTS_KEEP) rmSync(SHOTS_DIR, { recursive: true, force: true })
  }
}

async function seed(base: string, setupCode: string): Promise<ShotState> {
  const username = 'alex'
  const password = `Shots-${Math.random().toString(36).slice(2)}-${Date.now().toString(36)}`
  const admin = new Client(base, 'en-GB')
  await admin.call('POST', '/api/auth/setup', { username, password, setupCode })
  const { secret } = await admin.call<{ secret: string }>('POST', '/api/auth/two-factor/setup')
  const totpStep = Math.floor(Date.now() / 30_000)
  await admin.call('POST', '/api/auth/two-factor/enable', { password, code: totpAt(secret, totpStep) })

  const sam = await admin.call<{ id: string }>('POST', '/api/users', {
    username: 'sam', password: `Shots-${Math.random().toString(36).slice(2)}`, role: 'USER',
  })
  await admin.call('PUT', '/api/settings/ai', {
    provider: 'LM_STUDIO',
    baseUrl: `http://127.0.0.1:${MOCK_AI_PORT}/v1`,
    apiKey: '',
    model: 'local-model',
    effort: 'medium',
    outputLanguage: 'English',
    ocr: true,
  })

  // Die Beispiel-PDFs druckt Chrome aus HTML
  const list = samples()
  const browser = await chromium.launch(process.env.AIDOCS_E2E_BROWSER === 'chromium' ? {} : { channel: 'chrome' })
  const page = await browser.newPage()
  const documents: Record<string, string> = {}
  try {
    // rückwärts, damit „Neueste zuerst“ mit dem ersten Beispiel beginnt
    for (const sample of [...list].reverse()) {
      await page.setContent(`<!doctype html><html><head><meta charset="utf-8"><style>${STYLE}</style></head><body>`
        + sample.pages.map((p) => `<section class="page">${p}</section>`).join('') + '</body></html>')
      const file = await page.pdf({ format: 'A4', printBackground: true, preferCSSPageSize: true })
      const form = new FormData()
      form.append('files', new Blob([new Uint8Array(file)], { type: 'application/pdf' }), sample.file)
      const [result] = await admin.call<Array<{ outcome: string; documentId: string }>>('POST', '/api/documents', form)
      if (result.outcome !== 'CREATED') throw new Error(`${sample.file}: ${result.outcome}`)
      documents[sample.file] = result.documentId
    }
  } finally {
    await browser.close()
  }

  for (let i = 0; ; i++) {
    const system = await admin.call<{ processing: number; documents: number }>('GET', '/api/system')
    if (system.processing === 0 && system.documents === list.length) break
    if (i > 240) throw new Error('Die Beispieldokumente sind nach zwei Minuten nicht erschlossen')
    await new Promise((done) => setTimeout(done, 500))
  }

  // Gespeichert bleibt der deutsche Name der Dokumentart – die Oberfläche zeigt ihn übersetzt
  for (const sample of list) {
    const doc = await admin.call<{ status: string }>('GET', `/api/documents/${documents[sample.file]}`)
    if (doc.status !== 'INDEXED') throw new Error(`${sample.file}: Status ${doc.status}`)
  }

  // Verträge
  const contract = (file: string, body: Record<string, unknown>) =>
    admin.call('PUT', `/api/documents/${documents[file]}/contract`, body)
  await contract('brightwave-supply-contract.pdf', {
    partner: 'Brightwave Energy', startDate: day(-295).iso, endDate: day(-295 + 365).iso,
    noticeMonths: 1, renewalMonths: 12, amount: '€118.00 / month', reminderDays: 14, cancelled: false,
  })
  await contract('harbourline-car-insurance.pdf', {
    partner: 'Harbourline Insurance', startDate: day(52 - 365).iso, endDate: day(52).iso,
    noticeMonths: 1, renewalMonths: 12, amount: '€684.00 / year', reminderDays: 14, cancelled: false,
  })
  await contract('kestrel-tenancy-agreement.pdf', {
    partner: 'Kestrel Property Management', startDate: day(-520).iso, noticeMonths: 3,
    amount: '€1,450.00 / month', reminderDays: 30, cancelled: false,
  })
  await contract('pulse-gym-cancellation.pdf', {
    partner: 'Pulse Gym', startDate: day(-700).iso, endDate: day(37).iso, noticeMonths: 1, renewalMonths: 1,
    amount: '€39.00 / month', reminderDays: 14, cancelled: true,
  })

  // Wiedervorlagen
  const remind = (file: string, body: Record<string, unknown>) =>
    admin.call('POST', `/api/documents/${documents[file]}/reminders`, body)
  await remind('seaview-dental-invoice.pdf', { dueDate: day(-2).iso, note: 'Submit receipt to health insurer' })
  await remind('lakeside-property-tax.pdf', { dueDate: day(0).iso, note: 'Choose payment option', assigneeId: sam.id })
  await remind('brightwave-supply-contract.pdf', { dueDate: day(6).iso, note: 'Read the meter and compare', repeatMonths: 1 })
  await remind('greenroof-solar-quote.pdf', { dueDate: day(15).iso, note: 'Decide on solar quote', fromSuggestion: false })

  // Teilen-Links
  await admin.call('POST', `/api/documents/${documents['kestrel-tenancy-agreement.pdf']}/shares`, {
    recipient: 'Tax advisor', canEdit: false, expiresInDays: 30,
  })
  await admin.call('POST', `/api/documents/${documents['kestrel-tenancy-agreement.pdf']}/shares`, {
    recipient: 'Letting agent', canEdit: true, expiresInDays: 7,
  })

  // Eine automatische Sicherung – sonst zeigt die Übersicht den Hinweis „noch keine Sicherung“
  await admin.call('PUT', '/api/settings/backup/auto', {
    enabled: true, hour: 2, keep: 14, directory: join(SHOTS_DIR, 'backups'), warnAfterDays: 7,
  })
  await admin.call('POST', '/api/settings/backup/auto/run')
  for (let i = 0; ; i++) {
    const auto = await admin.call<{ running: boolean; lastRunAt: string | null }>('GET', '/api/settings/backup/auto')
    if (!auto.running && auto.lastRunAt) break
    if (i > 120) throw new Error('Die Sicherung ist nach einer Minute nicht fertig')
    await new Promise((done) => setTimeout(done, 500))
  }

  return { username, password, totpSecret: secret, totpStep, documents }
}
