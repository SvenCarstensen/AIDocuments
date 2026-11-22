// Schlanke Anbindung an das Backend: fetch mit CSRF-Token, einheitlicher Fehlerbehandlung und allen Typen.

import { currentLanguage, t } from './i18n'
import type { CreationOptionsJson, RequestOptionsJson } from './passkey'

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
/** Die Filter der Dokumentliste als Abfrageparameter – für Liste und Export gleich. */
function queryParams(query: DocumentQuery): URLSearchParams {
  const params = new URLSearchParams()
  if (query.q) params.set('q', query.q)
  if (query.category) params.set('category', query.category)
  if (query.correspondent) params.set('correspondent', query.correspondent)
  query.tags.forEach((tag) => params.append('tag', tag))
  if (query.status) params.set('status', query.status)
  if (query.duplicates) params.set('duplicates', 'true')
  if (query.from) params.set('from', query.from)
  if (query.to) params.set('to', query.to)
  if (query.area) params.set('area', query.area)
  if (query.retentionExpired) params.set('retention', 'expired')
  params.set('sort', query.sort)
  return params
}

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
  /** für Erinnerungen und Benachrichtigungen per E-Mail; null = keine */
  email: string | null
  /** täglich eine Übersicht neuer Dokumente */
  notifyNewDocuments: boolean
  /** für Admins: abgelaufene Fristen, Postfächer mit Fehlern */
  notifyAdmin: boolean
}
export interface ProfileInput {
  email: string | null
  notifyNewDocuments: boolean
  notifyAdmin: boolean
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
  /** In welchem Bereich das Dokument liegt (bestimmt, wer es sieht). */
  areaId: string
  /** Bis zu diesem Tag aufzubewahren (Regel der Kategorie); null = keine Frist. */
  retainUntil: string | null
  /** Die Frist verbietet das Löschen heute noch – für alle, auch Administratoren. */
  retentionLocked: boolean
  /** was der Angemeldete mit dem Dokument darf – das Recht in dessen Bereich */
  access: Permission
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
/** Eine Seite der neuen Fassung: welche bisherige (ab 1), um wie viel Grad im Uhrzeigersinn gedreht. */
export interface PageSpec {
  page: number
  rotate: number
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
  /** leer = alle sichtbaren Bereiche */
  area: string
  /** nur Dokumente, deren Aufbewahrungsfrist abgelaufen ist */
  retentionExpired: boolean
}
export interface DocumentUpdate {
  title: string
  summary: string | null
  category: string | null
  correspondent: string | null
  documentDate: string | null
  tags: string[]
  /** undefined/null = Bereich unverändert */
  areaId?: string | null
}
/** Rechte in einem Bereich; jede Stufe schließt die vorige ein. */
export type Permission = 'READ' | 'EDIT' | 'DELETE'
export const PERMISSIONS: Permission[] = ['READ', 'EDIT', 'DELETE']
export const canEdit = (doc: { access: Permission }) => doc.access !== 'READ'
export const canDelete = (doc: { access: Permission }) => doc.access === 'DELETE'
export interface AreaMember {
  userId: string
  access: Permission
}
/** Ein Bereich: wer Mitglied ist, sieht dessen Dokumente; „für alle“ sehen alle Angemeldeten. */
export interface PasskeyInfo {
  id: string
  name: string
  createdAt: string
  lastUsedAt: string | null
  /** im Passwortmanager bzw. in der Cloud des Geräts gesichert – auch auf anderen Geräten verfügbar */
  synced: boolean
}
export interface BackupStatus {
  /** nur mit der eingebauten Datenbank */
  available: boolean
  lastBackupAt: string | null
  dataBytes: number
  files: number
}
export interface StoredBackup {
  name: string
  bytes: number
  createdAt: string
}
export interface AutoBackupStatus {
  /** nur mit der eingebauten Datenbank */
  available: boolean
  enabled: boolean
  /** volle Stunde, ab der täglich gesichert wird */
  hour: number
  /** so viele Stände bleiben liegen */
  keep: number
  /** der Ordner, in den gesichert wird */
  directory: string
  defaultDirectory: string
  /** 0 = nie warnen */
  warnAfterDays: number
  running: boolean
  lastRunAt: string | null
  lastRunOk: boolean | null
  lastRunError: string | null
  nextRunAt: string | null
  backups: StoredBackup[]
}
export interface AutoBackupInput {
  enabled: boolean
  hour: number
  keep: number
  /** leer = die Vorgabe */
  directory: string
  warnAfterDays: number
}
export interface BackupNotice {
  lastBackupAt: string | null
  /** länger nicht gesichert als eingestellt */
  overdue: boolean
  /** warum die automatische Sicherung zuletzt fehlschlug */
  failure: string | null
}
export interface ArchiveImportResult {
  imported: number
  duplicates: number
  failed: number
  problems: string[]
}
export interface Area {
  id: string
  /** null nur beim allgemeinen Bereich – dann gilt der übersetzte Name */
  name: string | null
  everyone: boolean
  /** was alle dürfen, wenn der Bereich für alle freigegeben ist */
  everyoneAccess: Permission
  /** nur für Admins gefüllt */
  memberIds: string[]
  members: AreaMember[]
  documentCount: number
  /** was der Angemeldete im Bereich darf */
  myAccess: Permission
}
export interface AreaInput {
  name: string
  everyone: boolean
  everyoneAccess: Permission
  members: AreaMember[]
}
export interface UploadResult {
  filename: string
  outcome: 'CREATED' | 'DUPLICATE' | 'REJECTED' | 'IGNORED'
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
  /** Dokumente, deren Aufbewahrungsfrist abgelaufen ist (sie dürfen jetzt gelöscht werden) */
  retentionExpired: number
  /** nur für Admins: Postfächer, die sich wiederholt nicht abrufen lassen */
  failingMailboxes: string[]
  /** nur für Admins: zu lange nicht gesichert oder die automatische Sicherung schlug fehl */
  backup: BackupNotice | null
}
export type ReminderStatus = 'OPEN' | 'DONE'
/** Ein Termin (Wiedervorlage) an einem Dokument. */
export interface Reminder {
  id: string
  documentId: string
  documentTitle: string
  dueDate: string
  note: string | null
  assigneeId: string | null
  assigneeName: string | null
  status: ReminderStatus
  /** CONTRACT: aus den Vertragsangaben angelegt (vor Ablauf der Kündigungsfrist) */
  source: 'USER' | 'SUGGESTION' | 'CONTRACT'
  createdBy: string | null
  createdAt: string
  doneAt: string | null
  doneBy: string | null
  /** wiederholt sich alle so viele Monate; null = einmalig */
  repeatMonths: number | null
}
/** Ein Termin, den die KI in den Angaben des Dokuments gefunden hat – wird erst durch Übernehmen zur Wiedervorlage. */
export interface ReminderSuggestion {
  documentId: string
  documentTitle: string
  date: string
  label: string
}
export interface ReminderInput {
  dueDate: string
  note: string | null
  /** null = man selbst */
  assigneeId: string | null
  fromSuggestion?: boolean
  repeatMonths?: number | null
}
export type ContractStatus = 'ACTIVE' | 'DUE_SOON' | 'NOTICE_MISSED' | 'CANCELLED' | 'ENDED' | 'OPEN_ENDED'
/** Vertragsangaben eines Dokuments: Laufzeit, Kündigungsfrist, Verlängerung. */
export interface Contract {
  documentId: string
  documentTitle: string
  areaId: string
  /** ohne eigene Angabe: der Absender des Dokuments */
  partner: string | null
  startDate: string | null
  /** Ende der laufenden Laufzeit; null = unbefristet */
  endDate: string | null
  noticeMonths: number | null
  /** verlängert sich ohne Kündigung um so viele Monate; null = endet */
  renewalMonths: number | null
  amount: string | null
  reminderDays: number
  cancelled: boolean
  /** bis wann gekündigt sein muss; null = verlängert sich nicht */
  noticeDeadline: string | null
  /** der Tag, an dem etwas zu tun ist (Kündigungsfrist oder Laufzeitende) */
  keyDate: string | null
  status: ContractStatus
  assigneeId: string | null
  reminderId: string | null
  access: Permission
}
export interface ContractInput {
  partner: string | null
  startDate: string | null
  endDate: string | null
  noticeMonths: number | null
  renewalMonths: number | null
  amount: string | null
  reminderDays: number
  cancelled: boolean
  assigneeId: string | null
}
export interface Person {
  id: string
  username: string
}
export interface MailOutSettings {
  enabled: boolean
  host: string
  port: number
  security: MailSecurity
  username: string
  passwordSet: boolean
  from: string
  /** für Links in den E-Mails; leer = keine */
  appUrl: string
  /** ab dieser Stunde gehen die Erinnerungen des Tages hinaus */
  digestHour: number
}
export interface MailOutInput extends Omit<MailOutSettings, 'passwordSet'> {
  /** null = gespeichertes behalten */
  password: string | null
}
/** Dokumente dieser Kategorie werden so viele Jahre aufbewahrt, gezählt ab dem Ende des Jahres ihres Datums. */
export interface RetentionRule {
  id: string
  category: string
  years: number
  documentCount: number
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
  /** Zielbereich der übernommenen Dokumente */
  areaId: string
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
  areaId: string
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
  /** Zielbereich der Dokumente aus dem Eingangsordner */
  folderAreaId: string
}
export interface ImportSettingsInput {
  intervalMinutes: number
  folderEnabled: boolean
  folderPath: string
  folderMode: FolderMode
  folderAreaId: string
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
  passkeyLoginOptions: () => request<RequestOptionsJson>('/api/auth/passkey/options', { method: 'POST' }),
  passkeyLogin: (credential: string) => request<User>('/api/auth/passkey/login', { method: 'POST', body: credential }),
  passkeyConfirm: (credential: string) => request<void>('/api/auth/passkey/confirm', { method: 'POST', body: credential }),
  passkeys: () => request<PasskeyInfo[]>('/api/auth/passkeys'),
  passkeyRegistrationOptions: (password: string | null) =>
    request<CreationOptionsJson>('/api/auth/passkeys/options', json('POST', { password })),
  registerPasskey: (name: string, credential: Record<string, unknown>) =>
    request<PasskeyInfo>('/api/auth/passkeys', json('POST', { name, credential })),
  deletePasskey: (id: string) => request<void>(`/api/auth/passkeys/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  loginTwoFactor: (code: string) => request<User>('/api/auth/login/two-factor', json('POST', { code })),
  confirmCode: (code: string) => request<void>('/api/auth/confirm', json('POST', { code })),
  twoFactorStatus: () => request<TwoFactorStatus>('/api/auth/two-factor'),
  twoFactorSetup: () => request<TwoFactorSetup>('/api/auth/two-factor/setup', { method: 'POST' }),
  twoFactorEnable: (password: string, code: string) =>
    request<{ recoveryCodes: string[] }>('/api/auth/two-factor/enable', json('POST', { password, code })),
  twoFactorDisable: (password: string, code: string) =>
    request<void>('/api/auth/two-factor/disable', json('POST', { password, code })),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),
  updateProfile: (profile: ProfileInput) => request<User>('/api/auth/profile', json('PUT', profile)),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('/api/auth/password', json('POST', { currentPassword, newPassword })),

  system: () => request<SystemInfo>('/api/system'),

  documents: (query: DocumentQuery, page: number, size: number) => {
    const params = queryParams(query)
    params.set('page', String(page))
    params.set('size', String(size))
    return request<Page<Doc>>(`/api/documents?${params}`)
  },
  /** Export, Schritt 1: merkt sich alle Treffer (kann eine Bestätigung per App-Code verlangen). */
  prepareExport: (query: DocumentQuery) =>
    request<{ token: string; count: number }>(`/api/documents/export?${queryParams(query)}`, { method: 'POST' }),
  /** Export, Schritt 2: Download-Adresse, einmal gültig. */
  exportUrl: (token: string) => `/api/documents/export/${encodeURIComponent(token)}`,
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
  /**
   * @param area Zielbereich; leer = der allgemeine (bzw. der erste sichtbare)
   * @param combine die Fotos zu einem Dokument mit je einer Seite zusammenfassen
   */
  upload: (files: File[], area = '', combine = false) => {
    const body = new FormData()
    files.forEach((file) => body.append('files', file))
    if (area) body.append('area', area)
    if (combine) body.append('combine', 'true')
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
  retentionRules: () => request<RetentionRule[]>('/api/settings/retention'),
  createRetentionRule: (body: { category: string; years: number }) =>
    request<RetentionRule>('/api/settings/retention', json('POST', body)),
  updateRetentionRule: (id: string, body: { category: string; years: number }) =>
    request<RetentionRule>(`/api/settings/retention/${id}`, json('PUT', body)),
  deleteRetentionRule: (id: string) => request<void>(`/api/settings/retention/${id}`, { method: 'DELETE' }),

  /** Speichert eine geänderte Fassung des PDFs; das Original bewahrt der Server auf. */
  replaceFile: (id: string, pdf: Uint8Array) =>
    request<Doc>(`/api/documents/${id}/file`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/pdf' },
      body: new Blob([pdf as BlobPart], { type: 'application/pdf' }),
    }),
  /** Eine Seite (ab 1) als kleines Bild; {@code stamp} sorgt dafür, dass nach einer Änderung kein altes erscheint. */
  pageImageUrl: (id: string, page: number, stamp: string | null) =>
    `/api/documents/${id}/pages/${page}/image?width=240&v=${encodeURIComponent(stamp ?? 'original')}`,
  rearrangePages: (id: string, pages: PageSpec[]) => request<Doc>(`/api/documents/${id}/pages`, json('POST', { pages })),
  splitDocument: (id: string, fromPage: number) =>
    request<{ original: Doc; createdId: string }>(`/api/documents/${id}/split`, json('POST', { fromPage })),
  mergeDocuments: (id: string, documentIds: string[]) =>
    request<Doc>(`/api/documents/${id}/merge`, json('POST', { documentIds })),
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

  reminders: (mine: boolean) =>
    request<{ due: Reminder[]; upcoming: Reminder[]; suggestions: ReminderSuggestion[] }>(`/api/reminders?mine=${mine}`),
  reminderCount: () => request<{ due: number }>('/api/reminders/count'),
  documentReminders: (id: string) =>
    request<{ reminders: Reminder[]; suggestions: ReminderSuggestion[] }>(`/api/documents/${id}/reminders`),
  createReminder: (documentId: string, body: ReminderInput) =>
    request<Reminder>(`/api/documents/${documentId}/reminders`, json('POST', body)),
  dismissSuggestion: (documentId: string, date: string, label: string) =>
    request<void>(`/api/documents/${documentId}/reminders/dismiss`, json('POST', { date, label })),
  reminderAssignees: (documentId: string) => request<Person[]>(`/api/documents/${documentId}/reminders/assignees`),
  updateReminder: (id: string, body: ReminderInput) => request<Reminder>(`/api/reminders/${id}`, json('PUT', body)),
  setReminderDone: (id: string, done: boolean) =>
    request<Reminder>(`/api/reminders/${id}/${done ? 'done' : 'reopen'}`, { method: 'POST' }),
  deleteReminder: (id: string) => request<void>(`/api/reminders/${id}`, { method: 'DELETE' }),

  contracts: () => request<Contract[]>('/api/contracts'),
  /** undefined = zum Dokument sind keine Vertragsangaben erfasst */
  /** null, wenn keine Vertragsangaben erfasst sind (204) – undefined nimmt TanStack Query nicht als Ergebnis an. */
  documentContract: async (id: string) =>
    (await request<Contract | undefined>(`/api/documents/${id}/contract`)) ?? null,
  saveContract: (id: string, body: ContractInput) => request<Contract>(`/api/documents/${id}/contract`, json('PUT', body)),
  deleteContract: (id: string) => request<void>(`/api/documents/${id}/contract`, { method: 'DELETE' }),
  mailOutSettings: () => request<MailOutSettings>('/api/settings/mail-out'),
  updateMailOutSettings: (body: MailOutInput) => request<MailOutSettings>('/api/settings/mail-out', json('PUT', body)),
  testMailOut: () => request<TestResult>('/api/settings/mail-out/test', { method: 'POST' }),

  areas: () => request<Area[]>('/api/areas'),
  backupStatus: () => request<BackupStatus>('/api/settings/backup'),
  prepareBackup: () => request<{ token: string }>('/api/settings/backup', { method: 'POST' }),
  autoBackup: () => request<AutoBackupStatus>('/api/settings/backup/auto'),
  updateAutoBackup: (input: AutoBackupInput) =>
    request<AutoBackupStatus>('/api/settings/backup/auto', { method: 'PUT', body: JSON.stringify(input) }),
  runAutoBackup: () => request<void>('/api/settings/backup/auto/run', { method: 'POST' }),
  backupUrl: (token: string) => `/api/settings/backup/${encodeURIComponent(token)}`,
  /** Das ZIP geht als Rohdaten hinaus – ein Archiv ist oft größer als die Grenze für Formular-Uploads. */
  importArchive: (file: File, area: string, matchAreas: boolean) =>
    request<ArchiveImportResult>(`/api/settings/backup/import?${new URLSearchParams({ area, matchAreas: String(matchAreas) })}`, {
      method: 'POST',
      body: file,
      headers: { 'Content-Type': 'application/zip' },
    }),
  createArea: (body: AreaInput) => request<Area>('/api/areas', json('POST', body)),
  updateArea: (id: string, body: AreaInput) => request<Area>(`/api/areas/${id}`, json('PUT', body)),
  deleteArea: (id: string) => request<void>(`/api/areas/${id}`, { method: 'DELETE' }),

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
  // Passkey: der Browser lehnt die Adresse ab (falscher Name hinter einem Proxy, IP-Adresse, kein HTTPS)
  if (error instanceof DOMException && error.name === 'SecurityError') return t('passkeys.unsupported')
  return t('errors.unexpected')
}
