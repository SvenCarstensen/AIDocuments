// Schlanke Anbindung an das Backend: fetch mit CSRF-Token, einheitlicher Fehlerbehandlung und allen Typen.

import { currentLanguage, t } from './i18n'

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : null
}

async function send(path: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers)
  const method = (init.method ?? 'GET').toUpperCase()
  if (method !== 'GET') {
    const token = csrfToken()
    if (token) headers.set('X-XSRF-TOKEN', token)
  }
  if (typeof init.body === 'string') headers.set('Content-Type', 'application/json')
  // Der Server antwortet in der Sprache der Oberfläche (Fehlertexte, Statusmeldungen).
  headers.set('Accept-Language', currentLanguage())
  return fetch(path, { ...init, headers, credentials: 'same-origin' })
}

/**
 * Fragt einen frischen Code aus der Authenticator-App ab (siehe ConfirmCodeHost); true, sobald der Server ihn
 * angenommen hat, false bei Abbruch.
 */
let confirmHandler: (() => Promise<boolean>) | null = null
export function setConfirmHandler(handler: (() => Promise<boolean>) | null) {
  confirmHandler = handler
}

async function needsConfirmation(response: Response): Promise<boolean> {
  if (response.status !== 403) return false
  try {
    const problem = await response.clone().json()
    return problem?.code === 'confirmationRequired'
  } catch {
    return false
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response = await send(path, init)
  const isWrite = (init.method ?? 'GET').toUpperCase() !== 'GET'
  if (await needsConfirmation(response)) {
    // Änderungen an Konten, Postfächern und Einstellungen verlangen einen frischen Code – abfragen, einmal wiederholen
    if (confirmHandler && (await confirmHandler())) response = await send(path, init)
  } else if (response.status === 403 && isWrite && !path.startsWith('/api/auth/status')) {
    // Möglicherweise fehlte nur das CSRF-Cookie (z. B. nach der Abmeldung): neu holen, einmal wiederholen.
    await send('/api/auth/status', {})
    response = await send(path, init)
  }
  if (response.status === 401 && !path.startsWith('/api/auth/')) {
    window.dispatchEvent(new Event('auth:expired'))
  }
  if (!response.ok) {
    let message = t('errors.server', { status: response.status })
    if (response.status === 403) message = t('errors.forbidden')
    try {
      const problem = await response.json()
      if (problem?.detail) message = problem.detail
    } catch {
      /* keine JSON-Antwort */
    }
    throw new ApiError(response.status, message)
  }
  if (response.status === 204 || response.status === 202) return undefined as T
  return (await response.json()) as T
}

const json = (method: string, body?: unknown): RequestInit => ({
  method,
  body: body === undefined ? undefined : JSON.stringify(body),
})

// ---------- Typen ----------

export type Role = 'ADMIN' | 'USER'
export interface User {
  id: string
  username: string
  role: Role
  enabled: boolean
  createdAt: string
  lastLoginAt: string | null
  twoFactorEnabled: boolean
}
/** Antwort der Anmeldung: angemeldet, oder es fehlt noch der Code aus der Authenticator-App. */
export type LoginResult = User | { twoFactorRequired: true }
export interface TwoFactorStatus {
  enabled: boolean
  recoveryCodesLeft: number
}
export interface TwoFactorSetup {
  secret: string
  uri: string
  /** QR-Code als data-URL (SVG) */
  qrCode: string
}
export type VersionKind = 'IMPORTED' | 'EDITED' | 'ORIGINAL_RESTORED' | 'VERSION_RESTORED' | 'UNDONE'
/** Eine Fassung der PDF-Datei eines Dokuments */
/** Ein Link, mit dem eine nicht angemeldete Person das Dokument öffnet. */
export interface DocShare {
  id: string
  recipient: string
  canEdit: boolean
  createdAt: string
  createdBy: string | null
  expiresAt: string | null
  expired: boolean
  lastAccessAt: string | null
  accessCount: number
  token: string
}

/** Was eine Person über einen Link vom Dokument sieht. */
export interface SharedDoc {
  title: string
  filename: string
  pageCount: number | null
  sizeBytes: number
  modifiedAt: string | null
  canEdit: boolean
  expiresAt: string | null
  recipient: string
}

export interface DocVersion {
  number: number
  kind: VersionKind
  restoredFrom: number | null
  createdAt: string | null
  createdBy: string | null
  sizeBytes: number | null
  pageCount: number | null
  current: boolean
}
export interface AuthStatus {
  setupRequired: boolean
  user: User | null
}

export type DocumentStatus = 'PENDING' | 'PROCESSING' | 'INDEXED' | 'TEXT_ONLY' | 'FAILED'
export type DocumentSource = 'MAIL' | 'FOLDER' | 'UPLOAD'
export interface KeyFact {
  label: string
  value: string
}
export interface Doc {
  id: string
  title: string
  originalFilename: string
  status: DocumentStatus
  errorMessage: string | null
  category: string | null
  correspondent: string | null
  documentDate: string | null
  summary: string | null
  language: string | null
  tags: string[]
  keyFacts: KeyFact[]
  pageCount: number | null
  sizeBytes: number
  ocrUsed: boolean
  hasThumbnail: boolean
  /** Wann das Vorschaubild zuletzt geschrieben wurde (null bei älteren Dokumenten). */
  thumbnailAt: string | null
  source: DocumentSource
  mailSubject: string | null
  mailFrom: string | null
  mailReceivedAt: string | null
  createdAt: string
  processedAt: string | null
  /** Wann das PDF zuletzt geändert wurde; null = es ist noch das Original. */
  modifiedAt: string | null
  /** Ob eine Kopie des Originals aufbewahrt ist (nur nach einer Änderung). */
  hasOriginal: boolean
  /** Das frühere Dokument, dem dieses inhaltlich gleicht; null = kein Duplikat bekannt. */
  duplicateOf: string | null
  snippet: string | null
}
export interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}
export interface FacetCount {
  name: string
  count: number
}
export interface Facets {
  categories: FacetCount[]
  correspondents: FacetCount[]
  tags: FacetCount[]
}
export type SortOrder = 'relevance' | 'newest' | 'date'
export type HistoryAction = 'IMPORTED' | 'EDITED' | 'DELETED'
export type HistoryChange = 'DETAILS' | 'FILE' | 'ORIGINAL_RESTORED' | 'DUPLICATE'
/** Ein Eintrag der Historie; Titel und Herkunft sind beim Eintragen festgehalten. */
export interface HistoryEntry {
  id: string
  at: string
  action: HistoryAction
  documentId: string
  documentTitle: string
  source: DocumentSource | null
  origin: string | null
  change: HistoryChange | null
  /** wer es getan hat; null = automatisch (Postfach, Eingangsordner) */
  user: string | null
  /** wann und von wem der Schritt rückgängig gemacht wurde */
  undoneAt: string | null
  undoneBy: string | null
  /** lässt sich (jetzt) zurücknehmen – jeweils der neueste geltende Schritt eines Dokuments */
  undoable: boolean
}
export interface ChatMessage {
  fromUser: boolean
  text: string
}
/** Wie die KI eine Suchfrage verstanden hat – leere Felder filtern nicht. */
export interface AskResult {
  question: string
  text: string
  category: string | null
  correspondent: string | null
  tags: string[]
  from: string | null
  to: string | null
}
export interface DocumentQuery {
  q: string
  category: string
  correspondent: string
  tags: string[]
  status: string
  /** nur Dokumente, die als mögliches Duplikat eines anderen vermerkt sind */
  duplicates: boolean
  from: string
  to: string
  sort: SortOrder
}
export interface DocumentUpdate {
  title: string
  summary: string | null
  category: string | null
  correspondent: string | null
  documentDate: string | null
  tags: string[]
}
export interface UploadResult {
  filename: string
  outcome: 'CREATED' | 'DUPLICATE' | 'REJECTED'
  documentId: string | null
  message: string | null
}
export interface SystemInfo {
  aiEnabled: boolean
  /** Scans liest die eingebaute Texterkennung (es gibt sie, und keine externe KI ist eingerichtet). */
  builtinOcr: boolean
  /** Die eingebaute Texterkennung (Tesseract) steht bereit – unabhängig davon, ob eine KI eingerichtet ist. */
  ocrAvailable: boolean
  aiModel: string
  documents: number
  processing: number
  failed: number
  unanalyzed: number
  /** Dokumente, die als mögliches Duplikat eines anderen vermerkt sind */
  duplicates: number
  /** Geschätzte Sekunden, bis alle Dokumente bearbeitet sind; null = (noch) keine Schätzung. */
  estimatedSecondsRemaining: number | null
}

export type MailSecurity = 'SSL' | 'STARTTLS' | 'NONE'
export interface MailAccount {
  id: string
  name: string
  host: string
  port: number
  security: MailSecurity
  username: string
  folder: string
  importSince: string | null
  enabled: boolean
  filter: MailFilter
  /** eigenes Abrufintervall in Minuten; null = das allgemeine */
  intervalMinutes: number | null
  /** das tatsächlich geltende Intervall */
  effectiveIntervalMinutes: number
  /** null = pausiert */
  nextSyncAt: string | null
  lastSyncAt: string | null
  lastSyncStatus: 'RUNNING' | 'OK' | 'ERROR' | null
  lastSyncMessage: string | null
  documentCount: number
}
export interface MailAccountInput {
  name: string
  host: string
  port: number
  security: MailSecurity
  username: string
  password: string | null
  folder: string
  importSince: string | null
  enabled: boolean
  filter: MailFilter
  /** null = das allgemeine Abrufintervall */
  intervalMinutes: number | null
}
/** Nur E-Mails, deren Betreff und Absender passen, werden ausgewertet; leere Listen schränken nicht ein. */
export interface MailFilter {
  subjectIncludes: string[]
  subjectExcludes: string[]
  senderIncludes: string[]
  senderExcludes: string[]
}
export interface TestResult {
  ok: boolean
  message: string
}

export type LlmProvider = 'ANTHROPIC' | 'OPENAI' | 'MOONSHOT' | 'LM_STUDIO' | 'OPENAI_COMPATIBLE'
export interface LlmProviderInfo {
  id: LlmProvider
  label: string
  protocol: 'ANTHROPIC' | 'OPENAI'
  defaultBaseUrl: string
  requiresApiKey: boolean
  /** Dienst im Internet: Dokumentinhalte verlassen bei der Analyse den eigenen Rechner. */
  cloud: boolean
  /** Vorbelegung aus der Server-Konfiguration (leer = keine); der Schlüssel selbst bleibt auf dem Server. */
  presetBaseUrl: string
  presetModel: string
  presetApiKeySet: boolean
  /** Nur in der Entwicklungsumgebung gefüllt; sonst leer – dann bleibt der Schlüssel auf dem Server. */
  presetApiKey: string
}
export interface AiSettings {
  provider: LlmProvider
  baseUrl: string
  model: string
  effort: string
  outputLanguage: string
  ocr: boolean
  /** Scans erst mit der eingebauten Texterkennung lesen, dann von der KI verfeinern lassen. */
  quickOcrFirst: boolean
  compatibilityMode: boolean
  /** Alles Nötige ist beisammen – neue Dokumente werden analysiert. */
  enabled: boolean
  apiKeySet: boolean
  apiKeySource: 'SETTINGS' | 'ENVIRONMENT' | 'NONE'
  providers: LlmProviderInfo[]
}
export interface AiSettingsInput {
  provider: LlmProvider
  baseUrl: string
  apiKey: string | null
  model: string
  effort: string
  outputLanguage: string
  ocr: boolean
  quickOcrFirst: boolean
  compatibilityMode: boolean | null
}
export type FolderMode = 'KEEP' | 'MOVE'
export interface ImportSettings {
  intervalMinutes: number
  folderEnabled: boolean
  folderPath: string
  folderMode: FolderMode
  folderStatus: { lastScanAt: string | null; ok: boolean; message: string | null; running: boolean }
  nextRunAt: string
}
export interface ImportSettingsInput {
  intervalMinutes: number
  folderEnabled: boolean
  folderPath: string
  folderMode: FolderMode
}

// ---------- Aufrufe ----------

/** Mehr als so viele Dokumente liefert der Server nicht in einer Antwort. */
export const MAX_PAGE_SIZE = 100

export const api = {
  authStatus: () => request<AuthStatus>('/api/auth/status'),
  setup: (username: string, password: string, setupCode: string) =>
    request<User>('/api/auth/setup', json('POST', { username, password, setupCode })),
  login: (username: string, password: string) =>
    request<LoginResult>('/api/auth/login', json('POST', { username, password })),
  loginTwoFactor: (code: string) => request<User>('/api/auth/login/two-factor', json('POST', { code })),
  confirmCode: (code: string) => request<void>('/api/auth/confirm', json('POST', { code })),
  twoFactorStatus: () => request<TwoFactorStatus>('/api/auth/two-factor'),
  twoFactorSetup: () => request<TwoFactorSetup>('/api/auth/two-factor/setup', { method: 'POST' }),
  twoFactorEnable: (password: string, code: string) =>
    request<{ recoveryCodes: string[] }>('/api/auth/two-factor/enable', json('POST', { password, code })),
  twoFactorDisable: (password: string, code: string) =>
    request<void>('/api/auth/two-factor/disable', json('POST', { password, code })),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('/api/auth/password', json('POST', { currentPassword, newPassword })),

  system: () => request<SystemInfo>('/api/system'),

  documents: (query: DocumentQuery, page: number, size: number) => {
    const params = new URLSearchParams()
    if (query.q) params.set('q', query.q)
    if (query.category) params.set('category', query.category)
    if (query.correspondent) params.set('correspondent', query.correspondent)
    query.tags.forEach((tag) => params.append('tag', tag))
    if (query.status) params.set('status', query.status)
    if (query.duplicates) params.set('duplicates', 'true')
    if (query.from) params.set('from', query.from)
    if (query.to) params.set('to', query.to)
    params.set('sort', query.sort)
    params.set('page', String(page))
    params.set('size', String(size))
    return request<Page<Doc>>(`/api/documents?${params}`)
  },
  facets: () => request<Facets>('/api/documents/facets'),
  document: (id: string) => request<Doc>(`/api/documents/${id}`),
  documentText: (id: string) => request<{ content: string }>(`/api/documents/${id}/text`),
  updateDocument: (id: string, body: DocumentUpdate) => request<Doc>(`/api/documents/${id}`, json('PUT', body)),
  /** Löscht in den Papierkorb; über die Historie 30 Tage lang zurückholbar. */
  deleteDocument: (id: string, asDuplicate = false) =>
    request<void>(`/api/documents/${id}${asDuplicate ? '?duplicate=true' : ''}`, { method: 'DELETE' }),
  reprocess: (id: string) => request<Doc>(`/api/documents/${id}/reprocess`, { method: 'POST' }),
  /** Fragen an ein Dokument; die Antwort kommt von der eingerichteten KI. */
  chat: (id: string, messages: ChatMessage[]) =>
    request<{ answer: string }>(`/api/documents/${id}/chat`, json('POST', { messages })),
  /** KI-Suche: übersetzt eine Frage in natürlicher Sprache in Filter. */
  askSearch: (question: string) => request<AskResult>('/api/documents/ask', json('POST', { question })),
  history: (page: number, size: number) => request<Page<HistoryEntry>>(`/api/history?page=${page}&size=${size}`),
  undoHistory: (entryId: string) => request<Doc>(`/api/history/${entryId}/undo`, { method: 'POST' }),
  /** Der Anwender bestätigt: kein Duplikat. */
  notDuplicate: (id: string) => request<Doc>(`/api/documents/${id}/not-duplicate`, { method: 'POST' }),
  reprocessUnanalyzed: () => request<{ count: number }>('/api/documents/reprocess-unanalyzed', { method: 'POST' }),
  upload: (files: File[]) => {
    const body = new FormData()
    files.forEach((file) => body.append('files', file))
    return request<UploadResult[]>('/api/documents', { method: 'POST', body })
  },

  mailAccounts: () => request<MailAccount[]>('/api/mail-accounts'),
  createMailAccount: (body: MailAccountInput) => request<MailAccount>('/api/mail-accounts', json('POST', body)),
  updateMailAccount: (id: string, body: MailAccountInput) =>
    request<MailAccount>(`/api/mail-accounts/${id}`, json('PUT', body)),
  deleteMailAccount: (id: string) => request<void>(`/api/mail-accounts/${id}`, { method: 'DELETE' }),
  syncMailAccount: (id: string) => request<void>(`/api/mail-accounts/${id}/sync`, { method: 'POST' }),
  testMailAccount: (accountId: string | null, account: MailAccountInput) =>
    request<TestResult>('/api/mail-accounts/test', json('POST', { accountId, account })),

  aiSettings: () => request<AiSettings>('/api/settings/ai'),
  updateAiSettings: (body: AiSettingsInput) => request<AiSettings>('/api/settings/ai', json('PUT', body)),
  testAi: () => request<TestResult>('/api/settings/ai/test', { method: 'POST' }),
  aiModels: () => request<string[]>('/api/settings/ai/models'),
  importSettings: () => request<ImportSettings>('/api/settings/import'),
  updateImportSettings: (body: ImportSettingsInput) =>
    request<ImportSettings>('/api/settings/import', json('PUT', body)),
  scanFolder: () => request<void>('/api/settings/import/scan', { method: 'POST' }),

  /** Speichert eine geänderte Fassung des PDFs; das Original bewahrt der Server auf. */
  replaceFile: (id: string, pdf: Uint8Array) =>
    request<Doc>(`/api/documents/${id}/file`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/pdf' },
      body: new Blob([pdf as BlobPart], { type: 'application/pdf' }),
    }),
  restoreOriginal: (id: string) => request<Doc>(`/api/documents/${id}/restore-original`, { method: 'POST' }),
  versions: (id: string) => request<DocVersion[]>(`/api/documents/${id}/versions`),
  restoreVersion: (id: string, number: number) =>
    request<Doc>(`/api/documents/${id}/versions/${number}/restore`, { method: 'POST' }),

  shares: (id: string) => request<DocShare[]>(`/api/documents/${id}/shares`),
  createShare: (id: string, body: { recipient: string; canEdit: boolean; expiresInDays: number | null }) =>
    request<DocShare>(`/api/documents/${id}/shares`, json('POST', body)),
  revokeShare: (id: string, shareId: string) =>
    request<void>(`/api/documents/${id}/shares/${shareId}`, { method: 'DELETE' }),
  sharedDocument: (token: string) => request<SharedDoc>(`/api/share/${encodeURIComponent(token)}`),
  saveSharedFile: (token: string, pdf: Uint8Array) =>
    request<SharedDoc>(`/api/share/${encodeURIComponent(token)}/file`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/pdf' },
      body: new Blob([pdf as BlobPart], { type: 'application/pdf' }),
    }),

  users: () => request<User[]>('/api/users'),
  createUser: (body: { username: string; password: string; role: Role }) =>
    request<User>('/api/users', json('POST', body)),
  updateUser: (id: string, body: { role: Role; enabled: boolean; password: string | null }) =>
    request<User>(`/api/users/${id}`, json('PUT', body)),
  deleteUser: (id: string) => request<void>(`/api/users/${id}`, { method: 'DELETE' }),
  resetTwoFactor: (id: string) => request<User>(`/api/users/${id}/two-factor`, { method: 'DELETE' }),
}

export const fileUrl = (id: string, download = false) => `/api/documents/${id}/file${download ? '?download=true' : ''}`
export const originalUrl = (id: string) => `/api/documents/${id}/original`
export const sharedFileUrl = (token: string) => `/api/share/${encodeURIComponent(token)}/file`
/** Der Link zum Weitergeben – vollständig, mit der Adresse, unter der die Anwendung gerade läuft. */
export const shareLink = (token: string) => `${window.location.origin}/s/${token}`
export const versionUrl = (id: string, number: number, download = false) =>
  `/api/documents/${id}/versions/${number}/file${download ? '?download=true' : ''}`
/** Die Adresse ändert sich mit jedem neu geschriebenen Vorschaubild – so zeigt der Browser nie ein altes aus seinem Zwischenspeicher. */
export const thumbnailUrl = (doc: Pick<Doc, 'id' | 'processedAt' | 'modifiedAt' | 'thumbnailAt'>) =>
  `/api/documents/${doc.id}/thumbnail?v=${encodeURIComponent(doc.thumbnailAt ?? `${doc.processedAt ?? ''}${doc.modifiedAt ?? ''}`)}`

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof TypeError) return t('errors.unreachable')
  return t('errors.unexpected')
}
