import { type ChildProcess, execFileSync, spawn } from 'node:child_process'
import { createWriteStream, existsSync, mkdirSync, rmSync } from 'node:fs'
import { join, resolve } from 'node:path'

/** Eine laufende Wegwerf-Instanz des Backends. */
export interface Instance {
  /** Code für die Ersteinrichtung aus dem Server-Log */
  setupCode: string
  base: string
  stop(): Promise<void>
}

/**
 * Startet eine Wegwerf-Instanz des Backends: eigenes Datenverzeichnis, eigener Port, gestartet aus einem leeren
 * Verzeichnis (damit keine lokale config/debug.yml mitgelesen wird) und ohne AIDOCS_-Umgebung des Rechners.
 *
 * @param dir wird vorher geleert; Daten liegen in dir/data, das Protokoll in dir/backend.log
 */
export async function startBackend(dir: string, port: number, extraEnv: Record<string, string> = {}): Promise<Instance> {
  const backendDir = resolve(import.meta.dirname, '../../../backend')
  const jar = process.env.AIDOCS_E2E_JAR ?? join(backendDir, 'target/aidocuments-backend-0.1.0.jar')
  if (!existsSync(jar)) {
    console.log('Backend wird gebaut (./mvnw package -DskipTests) …')
    execFileSync('./mvnw', ['-q', 'package', '-DskipTests'], { cwd: backendDir, stdio: 'inherit' })
  }

  rmSync(dir, { recursive: true, force: true })
  mkdirSync(join(dir, 'data'), { recursive: true })
  const env: NodeJS.ProcessEnv = {}
  for (const [key, value] of Object.entries(process.env)) {
    if (!/^(AIDOCS_|SPRING_|APP_|SERVER_)/.test(key)) env[key] = value
  }
  Object.assign(env, {
    SERVER_PORT: String(port),
    SERVER_ADDRESS: '127.0.0.1',
    AIDOCS_DATA_DIR: join(dir, 'data'),
    AIDOCS_IMPORT_DIR: '',
    ...extraEnv,
  })
  const log = createWriteStream(join(dir, 'backend.log'))
  const backend = spawn('java', ['-jar', jar], { cwd: dir, env, stdio: ['ignore', 'pipe', 'pipe'] })
  backend.stdout.pipe(log)
  backend.stderr.pipe(log)

  try {
    const setupCode = await waitForSetupCode(backend)
    return { setupCode, base: `http://127.0.0.1:${port}`, stop: () => stop(backend) }
  } catch (error) {
    await stop(backend)
    console.error(`Wegwerf-Instanz: siehe ${join(dir, 'backend.log')}`)
    throw error
  }
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
export class Client {
  readonly cookies = new Map<string, string>()
  private readonly base: string
  private readonly language: string
  constructor(base: string, language = 'de') {
    this.base = base
    this.language = language
  }

  async call<T>(method: string, path: string, body?: unknown): Promise<T> {
    if (!this.cookies.has('XSRF-TOKEN')) await this.raw('GET', '/api/auth/status')
    const response = await this.raw(method, path, body)
    if (!response.ok) throw new Error(`${method} ${path}: ${response.status} ${await response.text()}`)
    const text = await response.text()
    return (text ? JSON.parse(text) : undefined) as T
  }

  async raw(method: string, path: string, body?: unknown): Promise<Response> {
    const headers: Record<string, string> = { 'Accept-Language': this.language }
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
