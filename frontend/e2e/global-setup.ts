import { rmSync } from 'node:fs'
import { Client, startBackend } from './support/instance.ts'
import { pdf } from './support/pdf.ts'
import { type E2eState, writeState } from './support/state.ts'
import { totpAt } from './support/totp.ts'
import { BACKEND_PORT, E2E_DIR } from '../playwright.config.ts'

/**
 * Startet eine Wegwerf-Instanz des Backends (siehe support/instance.ts) und legt die Testkonten und ein paar
 * erzeugte PDFs an. Am Ende wird alles beendet und gelöscht.
 */
export default async function globalSetup() {
  const instance = await startBackend(E2E_DIR, BACKEND_PORT)
  try {
    writeState(await seed(instance.base, instance.setupCode))
  } catch (error) {
    await instance.stop()
    throw error
  }
  return async () => {
    await instance.stop()
    if (!process.env.AIDOCS_E2E_KEEP) rmSync(E2E_DIR, { recursive: true, force: true })
  }
}

const password = () => `Test-${Math.random().toString(36).slice(2)}-${Date.now().toString(36)}`

async function seed(base: string, setupCode: string): Promise<E2eState> {
  const users: E2eState['users'] = {
    admin: { username: 'e2e-admin', password: password() },
    reader: { username: 'e2e-leser', password: password() },
    passkey: { username: 'e2e-passkey', password: password() },
  }
  const admin = new Client(base)
  await admin.call('POST', '/api/auth/setup', { ...users.admin, setupCode })
  // Admins brauchen die Zwei-Faktor-Anmeldung; der erste Code richtet sie ein und gilt als Bestätigung
  const { secret } = await admin.call<{ secret: string }>('POST', '/api/auth/two-factor/setup')
  users.admin.totpSecret = secret
  const step = Math.floor(Date.now() / 30_000)
  await admin.call('POST', '/api/auth/two-factor/enable', { password: users.admin.password, code: totpAt(secret, step) })

  const ids: Record<string, string> = {}
  for (const key of ['reader', 'passkey'] as const) {
    const created = await admin.call<{ id: string }>('POST', '/api/users', { ...users[key], role: 'USER' })
    ids[key] = created.id
  }
  const area = await admin.call<{ id: string }>('POST', '/api/areas', {
    name: 'Nur lesen',
    everyone: false,
    everyoneAccess: 'READ',
    members: [{ userId: ids.reader, access: 'READ' }],
  })

  async function upload(filename: string, pages: string[], areaId = ''): Promise<string> {
    const form = new FormData()
    form.append('files', new Blob([new Uint8Array(pdf(pages))], { type: 'application/pdf' }), filename)
    if (areaId) form.append('area', areaId)
    const [result] = await admin.call<Array<{ outcome: string; documentId: string }>>('POST', '/api/documents', form)
    if (result.outcome !== 'CREATED') throw new Error(`${filename}: ${result.outcome}`)
    return result.documentId
  }
  const documents = {
    contract: await upload('Stromvertrag.pdf', ['Stromvertrag Testwerk, Laufzeit 12 Monate']),
    threePages: await upload('Dreiseiter.pdf', ['Seite eins', 'Seite zwei', 'Seite drei']),
    readOnly: await upload('Leseprobe.pdf', ['Nur zum Ansehen'], area.id),
  }
  for (let i = 0; i < 120; i++) {
    const system = await admin.call<{ processing: number; documents: number }>('GET', '/api/system')
    if (system.processing === 0 && system.documents === 3) break
    await new Promise((done) => setTimeout(done, 500))
  }
  return {
    dir: E2E_DIR,
    backendUrl: base,
    users,
    areas: { readOnly: area.id },
    documents,
    totpSteps: { admin: step },
  }
}
