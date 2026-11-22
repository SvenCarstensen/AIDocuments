import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

/** Was die Wegwerf-Instanz für die Tests bereitstellt; liegt als JSON in ihrem Verzeichnis. */
export interface E2eState {
  /** Verzeichnis der Wegwerf-Instanz (Daten, Protokoll, Anmeldezustände) */
  dir: string
  backendUrl: string
  users: Record<string, { username: string; password: string; totpSecret?: string }>
  areas: { readOnly: string }
  documents: { contract: string; threePages: string; readOnly: string }
  /** zuletzt verbrauchter TOTP-Zeitschritt je Benutzer */
  totpSteps: Record<string, number>
}

export function statePath(): string {
  const dir = process.env.AIDOCS_E2E_DIR
  if (!dir) throw new Error('AIDOCS_E2E_DIR fehlt – die Tests laufen nur über playwright.config.ts')
  return join(dir, 'state.json')
}

export function readState(): E2eState {
  return JSON.parse(readFileSync(statePath(), 'utf8')) as E2eState
}

export function writeState(state: E2eState): void {
  writeFileSync(statePath(), JSON.stringify(state, null, 2))
}

/** Gespeicherte Anmeldung (Cookies) eines Testbenutzers, angelegt vom Projekt „login“. */
export function storageStatePath(user: string): string {
  return join(process.env.AIDOCS_E2E_DIR ?? '', `${user}.storage.json`)
}
