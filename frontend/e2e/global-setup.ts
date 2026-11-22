import { type ChildProcess, execFileSync, spawn } from 'node:child_process'
import { createWriteStream, existsSync, mkdirSync, rmSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { pdf } from './support/pdf.ts'
import { type E2eState, writeState } from './support/state.ts'
import { totpAt } from './support/totp.ts'
import { BACKEND_PORT, E2E_DIR } from '../playwright.config.ts'

/**
 * Startet eine Wegwerf-Instanz des Backends: eigenes Datenverzeichnis, eigener Port, gestartet aus einem leeren
 * Verzeichnis (damit keine lokale config/debug.yml mitgelesen wird) und ohne AIDOCS_-Umgebung des Rechners. Legt die
 * Testkonten und ein paar erzeugte PDFs an. Am Ende wird alles beendet und gelöscht.
 */
export default async function globalSetup() {
  const backendDir = resolve(import.meta.dirname, '../../backend')
  const jar = process.env.AIDOCS_E2E_JAR ?? join(backendDir, 'target/aidocuments-backend-0.1.0.jar')
  if (!existsSync(jar)) {
    console.log('Backend wird gebaut (./mvnw package -DskipTests) …')
    execFileSync('./mvnw', ['-q', 'package', '-DskipTests'], { cwd: backendDir, stdio: 'inherit' })
  }

  rmSync(E2E_DIR, { recursive: true, force: true })
  mkdirSync(join(E2E_DIR, 'data'), { recursive: true })
  const env: NodeJS.ProcessEnv = {}
  for (const [key, value] of Object.entries(process.env)) {
    if (!/^(AIDOCS_|SPRING_|APP_|SERVER_)/.test(key)) env[key] = value
  }
  Object.assign(env, {
    SERVER_PORT: String(BACKEND_PORT),
    SERVER_ADDRESS: '127.0.0.1',
    AIDOCS_DATA_DIR: join(E2E_DIR, 'data'),
    AIDOCS_IMPORT_DIR: '',
  })
  const log = createWriteStream(join(E2E_DIR, 'backend.log'))
  const backend = spawn('java', ['-jar', jar], { cwd: E2E_DIR, env, stdio: ['ignore', 'pipe', 'pipe'] })
  backend.stdout.pipe(log)
  backend.stderr.pipe(log)

  const teardown = async () => {
    await stop(backend)
    if (!process.env.AIDOCS_E2E_KEEP) rmSync(E2E_DIR, { recursive: true, force: true })
  }
  try {
    const setupCode = await waitForSetupCode(backend)
    const state = await seed(`http://127.0.0.1:${BACKEND_PORT}`, setupCode)
    writeState(state)
  } catch (error) {
    await stop(backend)
    console.error(`Wegwerf-Instanz: siehe ${join(E2E_DIR, 'backend.log')}`)
    throw error
  }
  return teardown
}

function waitForSetupCode(backend: ChildProcess): Promise<string> {
  return new Promise((resolvePromise, reject) => {
    let output = ''
    const timer = setTimeout(() => reject(new Error('Das Backend ist nach 90 Sekunden nicht bereit')), 90_000)
    backend.once('exit', (code) => reject(new Error(`Das Backend hat sich beendet (Code ${code})`)))
    backend.stdout?.on('data', (chunk: Buffer) => {
      output += chunk.toString()
      const match = /Administratorkonto: (\S+)/.exec(output)
      if (match && /Started AiDocumentsApplication/.test(output)) {
        clearTimeout(timer)
        resolvePromise(match[1])
      }
    })
  })
}

async function stop(backend: ChildProcess) {
  if (backend.exitCode !== null) return
  const exited = new Promise((done) => backend.once('exit', done))
  backend.kill('SIGTERM')
  const timeout = new Promise((done) => setTimeout(done, 20_000, 'timeout'))
  if ((await Promise.race([exited, timeout])) === 'timeout') backend.kill('SIGKILL')
}

/** Ein kleiner HTTP-Client mit Sitzungscookie und XSRF-Token – wie der Browser. */
class Client {
  private cookies = new Map<string, string>()
  private readonly base: string
  constructor(base: string) {
    this.base = base
  }

  async call<T>(method: string, path: string, body?: unknown): Promise<T> {
    if (!this.cookies.has('XSRF-TOKEN')) await this.raw('GET', '/api/auth/status')
    const response = await this.raw(method, path, body)
    if (!response.ok) throw new Error(`${method} ${path}: ${response.status} ${await response.text()}`)
    const text = await response.text()
    return (text ? JSON.parse(text) : undefined) as T
  }

  async raw(method: string, path: string, body?: unknown): Promise<Response> {
    const headers: Record<string, string> = { 'Accept-Language': 'de' }
    const xsrf = this.cookies.get('XSRF-TOKEN')
    if (xsrf) headers['X-XSRF-TOKEN'] = xsrf
    if (this.cookies.size) headers.Cookie = [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ')
    let payload: FormData | string | undefined
    if (body instanceof FormData) payload = body
    else if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
      payload = JSON.stringify(body)
    }
    const response = await fetch(this.base + path, { method, headers, body: payload, redirect: 'manual' })
    for (const cookie of response.headers.getSetCookie()) {
      const [pair] = cookie.split(';')
      const [name, ...value] = pair.split('=')
      this.cookies.set(name.trim(), value.join('='))
    }
    return response
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
