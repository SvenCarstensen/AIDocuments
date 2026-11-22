import { createHmac } from 'node:crypto'
import { readState, writeState } from './state.ts'

/** Base32 (RFC 4648) – so liefert der Server den Schlüssel für die Authenticator-App. */
function base32(value: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = ''
  for (const char of value.replace(/=+$/, '').toUpperCase()) {
    const index = alphabet.indexOf(char)
    if (index < 0) throw new Error(`Kein Base32-Zeichen: ${char}`)
    bits += index.toString(2).padStart(5, '0')
  }
  const bytes: number[] = []
  for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2))
  return Buffer.from(bytes)
}

/** TOTP wie der Server: HMAC-SHA1, 30 Sekunden, 6 Ziffern. */
export function totpAt(secret: string, step: number): string {
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(step))
  const hash = createHmac('sha1', base32(secret)).update(counter).digest()
  const offset = hash[hash.length - 1] & 15
  return String((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, '0')
}

const currentStep = () => Math.floor(Date.now() / 30_000)

/**
 * Ein Code, den der Server noch annimmt: Jeder Code gilt nur einmal, angenommen werden der aktuelle und je einer davor
 * und danach. Der zuletzt verbrauchte Zeitschritt steht in der gemeinsamen Testdatei – so wartet ein Test nur, wenn
 * schon drei Codes desselben Zeitfensters verbraucht sind.
 */
export async function freshCode(user: string): Promise<string> {
  const state = readState()
  const account = state.users[user]
  if (!account?.totpSecret) throw new Error(`${user} hat keine Zwei-Faktor-Anmeldung`)
  const last = state.totpSteps[user] ?? 0
  let step = Math.max(currentStep() - 1, last + 1)
  while (step > currentStep() + 1) {
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  step = Math.max(step, currentStep() - 1)
  writeState({ ...state, totpSteps: { ...state.totpSteps, [user]: step } })
  return totpAt(account.totpSecret, step)
}
