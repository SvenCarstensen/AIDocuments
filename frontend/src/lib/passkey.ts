/**
 * Passkeys im Browser: die Optionen des Servers (JSON, Binärwerte als base64url) in die Form von
 * navigator.credentials bringen und die Antwort wieder als JSON zurück – im Format von PublicKeyCredential.toJSON(),
 * das auch ältere Browser so liefern.
 */

/** Passkeys gehen nur über HTTPS (oder localhost) und mit einem Namen statt einer IP-Adresse. */
export function passkeysSupported(): boolean {
  // http://127.0.0.1 gilt dem Browser als sicher, eine IP-Adresse ist aber nie eine gültige Passkey-Domain
  return (
    typeof window !== 'undefined' &&
    window.isSecureContext &&
    typeof window.PublicKeyCredential === 'function' &&
    !addressIsIp()
  )
}

/** Ob das an der Adresse liegt (IP statt Name) – dann kann der Hinweis sagen, woran es scheitert. */
export function addressIsIp(): boolean {
  const host = window.location.hostname
  return /^\d{1,3}(\.\d{1,3}){3}$/.test(host) || host.includes(':')
}

function decode(value: string): ArrayBuffer {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(value.length / 4) * 4, '=')
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes.buffer
}

function encode(value: ArrayBuffer | null | undefined): string | null {
  if (!value) return null
  const bytes = new Uint8Array(value)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

interface Descriptor {
  type: 'public-key'
  id: string
  transports?: AuthenticatorTransport[]
}

export interface CreationOptionsJson {
  challenge: string
  rp: { id: string; name: string }
  user: { id: string; name: string; displayName: string }
  pubKeyCredParams: Array<{ type: 'public-key'; alg: number }>
  excludeCredentials: Descriptor[]
  authenticatorSelection: AuthenticatorSelectionCriteria
  attestation: AttestationConveyancePreference
  timeout: number
}

export interface RequestOptionsJson {
  challenge: string
  rpId: string
  userVerification: UserVerificationRequirement
  timeout: number
}

const descriptor = (d: Descriptor): PublicKeyCredentialDescriptor => ({ type: d.type, id: decode(d.id), transports: d.transports })

/** Neuen Passkey anlegen lassen; null, wenn abgebrochen. */
export async function createPasskey(options: CreationOptionsJson): Promise<Record<string, unknown> | null> {
  const credential = (await navigator.credentials.create({
    publicKey: {
      ...options,
      challenge: decode(options.challenge),
      user: { ...options.user, id: decode(options.user.id) },
      excludeCredentials: options.excludeCredentials.map(descriptor),
    },
  })) as PublicKeyCredential | null
  if (!credential) return null
  const response = credential.response as AuthenticatorAttestationResponse
  return {
    id: credential.id,
    rawId: encode(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: encode(response.clientDataJSON),
      attestationObject: encode(response.attestationObject),
      transports: typeof response.getTransports === 'function' ? response.getTransports() : [],
    },
    clientExtensionResults: credential.getClientExtensionResults(),
    authenticatorAttachment: credential.authenticatorAttachment ?? null,
  }
}

/** Mit einem Passkey ausweisen; null, wenn abgebrochen. */
export async function getPasskey(options: RequestOptionsJson): Promise<string | null> {
  const credential = (await navigator.credentials.get({
    publicKey: { ...options, challenge: decode(options.challenge) },
  })) as PublicKeyCredential | null
  if (!credential) return null
  const response = credential.response as AuthenticatorAssertionResponse
  return JSON.stringify({
    id: credential.id,
    rawId: encode(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: encode(response.clientDataJSON),
      authenticatorData: encode(response.authenticatorData),
      signature: encode(response.signature),
      userHandle: encode(response.userHandle),
    },
    clientExtensionResults: credential.getClientExtensionResults(),
    authenticatorAttachment: credential.authenticatorAttachment ?? null,
  })
}

/** Abgebrochen (Dialog geschlossen, Zeit abgelaufen) – kein Fehler, den man melden muss. */
export function isCancelled(error: unknown): boolean {
  return error instanceof DOMException && (error.name === 'NotAllowedError' || error.name === 'AbortError')
}
