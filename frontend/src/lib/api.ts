// Schlanke Anbindung an das Backend: fetch mit CSRF-Token, einheitlicher Fehlerbehandlung und allen Typen.

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
  return fetch(path, { ...init, headers, credentials: 'same-origin' })
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response = await send(path, init)
  const isWrite = (init.method ?? 'GET').toUpperCase() !== 'GET'
  if (response.status === 403 && isWrite && !path.startsWith('/api/auth/status')) {
    // Möglicherweise fehlte nur das CSRF-Cookie (z. B. nach der Abmeldung): neu holen, einmal wiederholen.
    await fetch('/api/auth/status', { credentials: 'same-origin' })
    response = await send(path, init)
  }
  if (response.status === 401 && !path.startsWith('/api/auth/')) {
    window.dispatchEvent(new Event('auth:expired'))
  }
  if (!response.ok) {
    let message = `Der Server meldet einen Fehler (${response.status}).`
    if (response.status === 403) message = 'Dafür fehlt die Berechtigung.'
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
  source: DocumentSource
  mailSubject: string | null
  mailFrom: string | null
  mailReceivedAt: string | null
  createdAt: string
  processedAt: string | null
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
export interface DocumentQuery {
  q: string
  category: string
  correspondent: string
  tags: string[]
  status: string
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
  aiModel: string
  documents: number
  processing: number
  failed: number
  unanalyzed: number
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
}
export interface AiSettings {
  provider: LlmProvider
  baseUrl: string
  model: string
  effort: string
  outputLanguage: string
  ocr: boolean
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
  setup: (username: string, password: string) =>
    request<User>('/api/auth/setup', json('POST', { username, password })),
  login: (username: string, password: string) =>
    request<User>('/api/auth/login', json('POST', { username, password })),
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
  deleteDocument: (id: string) => request<void>(`/api/documents/${id}`, { method: 'DELETE' }),
  reprocess: (id: string) => request<Doc>(`/api/documents/${id}/reprocess`, { method: 'POST' }),
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

  users: () => request<User[]>('/api/users'),
  createUser: (body: { username: string; password: string; role: Role }) =>
    request<User>('/api/users', json('POST', body)),
  updateUser: (id: string, body: { role: Role; enabled: boolean; password: string | null }) =>
    request<User>(`/api/users/${id}`, json('PUT', body)),
  deleteUser: (id: string) => request<void>(`/api/users/${id}`, { method: 'DELETE' }),
}

export const fileUrl = (id: string, download = false) => `/api/documents/${id}/file${download ? '?download=true' : ''}`
export const thumbnailUrl = (doc: Pick<Doc, 'id' | 'processedAt'>) =>
  `/api/documents/${doc.id}/thumbnail?v=${encodeURIComponent(doc.processedAt ?? '')}`

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof TypeError) return 'Der Server ist nicht erreichbar.'
  return 'Unerwarteter Fehler.'
}
