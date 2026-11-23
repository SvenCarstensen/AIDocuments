import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Download, Pencil, Plus, RefreshCw, Sparkles, Trash2, X } from 'lucide-react'
import { type FormEvent, type KeyboardEvent, type ReactNode, useEffect, useRef, useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { isBusy, OcrHint, sourceInfo, StatusBadge } from '../components/documents'
import { Alert, Badge, Button, Card, ConfirmDialog, Field, Input, Modal, Select, Spinner, Textarea } from '../components/ui'
import { ContractCard } from '../components/Contracts'
import { EInvoiceCard } from '../components/EInvoiceCard'
import { PagesButton } from '../components/PagesDialog'
import { PdfViewer } from '../components/PdfViewer'
import { RemindersCard } from '../components/Reminders'
import { ShareButton, SharesCard } from '../components/ShareDialog'
import { VersionsButton } from '../components/VersionsDialog'
import { api, ApiError, canDelete, canEdit, type ChatMessage, type Doc, errorMessage, fileUrl } from '../lib/api'
import { areaName, areaNameById, useAreas } from '../lib/areas'
import { useAuth } from '../lib/auth'
import { cx, formatBytes, formatDateTime, formatLongDate } from '../lib/format'
import { documentTypeLabel, documentTypeOptions, documentTypeValue } from '../lib/documentTypes'

export function DocumentPage() {
  const { t } = useTranslation()
  const { id = '' } = useParams()
  const { isAdmin } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [tab, setTab] = useState<'overview' | 'text' | 'chat'>('overview')

  const query = useQuery({
    queryKey: ['document', id],
    queryFn: () => api.document(id),
    refetchInterval: (q) => (q.state.data && isBusy(q.state.data) ? 2000 : false),
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 1,
  })
  const doc = query.data

  const reprocess = useMutation({
    mutationFn: () => api.reprocess(id),
    onSuccess: (updated) => {
      queryClient.setQueryData(['document', id], updated)
      toast.success(t('document.reprocessStarted'))
    },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const remove = useMutation({
    mutationFn: () => api.deleteDocument(id),
    onSuccess: async () => {
      toast.success(t('document.deleted'))
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
      navigate('/documents')
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  if (query.isPending) {
    return (
      <div className="flex justify-center py-24">
        <Spinner className="size-7" />
      </div>
    )
  }
  if (query.isError || !doc) {
    return (
      <div className="mx-auto max-w-lg py-16">
        <Alert tone="danger">{errorMessage(query.error)}</Alert>
        <Link to="/documents" className="mt-4 inline-flex items-center gap-1.5 text-sm font-medium text-brand-ink">
          <ArrowLeft className="size-4" /> {t('document.backToList')}
        </Link>
      </div>
    )
  }

  const busy = isBusy(doc)
  const editable = canEdit(doc)
  const SourceIcon = sourceInfo[doc.source].icon

  return (
    <div>
      <div className="mb-5 flex flex-wrap items-start gap-x-4 gap-y-3">
        <button
          type="button"
          onClick={() => (window.history.length > 1 ? navigate(-1) : navigate('/documents'))}
          aria-label={t('common.back')}
          className="mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-lg border border-line-strong bg-surface text-muted hover:bg-raised hover:text-ink"
        >
          <ArrowLeft className="size-[18px]" />
        </button>
        <div className="min-w-0 flex-1 basis-72">
          <h1 className="text-xl leading-tight font-semibold tracking-tight [overflow-wrap:anywhere]">{doc.title}</h1>
          <div className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted">
            {doc.category && <Badge tone="brand">{documentTypeLabel(doc.category)}</Badge>}
            <StatusBadge doc={doc} />
            {doc.correspondent && <span>{doc.correspondent}</span>}
            {doc.correspondent && doc.documentDate && <span aria-hidden>·</span>}
            {doc.documentDate && <span>{formatLongDate(doc.documentDate)}</span>}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <a href={fileUrl(doc.id, true)} download>
            <Button icon={<Download className="size-4" aria-hidden />}>{t('document.download')}</Button>
          </a>
          {editable && (
            <Button icon={<Pencil className="size-4" aria-hidden />} onClick={() => setEditing(true)} disabled={busy}>
              {t('common.edit')}
            </Button>
          )}
          {editable && <PagesButton doc={doc} disabled={busy} />}
          <VersionsButton doc={doc} disabled={busy} />
          {editable && <ShareButton doc={doc} />}
          {isAdmin && (
            <Button
              icon={<RefreshCw className="size-4" aria-hidden />}
              onClick={() => reprocess.mutate()}
              loading={reprocess.isPending}
              disabled={busy}
              title={t('document.reprocessHint')}
            >
              {t('document.reprocess')}
            </Button>
          )}
          {canDelete(doc) && (
            <Button
              variant="ghost"
              className="!text-danger hover:!bg-danger-soft"
              icon={<Trash2 className="size-4" aria-hidden />}
              onClick={() => setConfirmDelete(true)}
              disabled={doc.retentionLocked}
              title={doc.retentionLocked && doc.retainUntil ? t('document.retention.locked', { date: formatLongDate(doc.retainUntil) }) : undefined}
            >
              {t('common.delete')}
            </Button>
          )}
        </div>
      </div>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_26rem]">
        {/* PDF-Ansicht: blättern, zoomen, suchen, drucken – und markieren, beschriften, unterschreiben */}
        <div className="relative h-[70dvh] overflow-hidden rounded-xl border border-line bg-raised shadow-card xl:sticky xl:top-22 xl:h-[calc(100dvh-10.5rem)]">
          <PdfViewer doc={doc} />
        </div>

        <div className="flex min-w-0 flex-col gap-4">
          {busy && <Alert tone="brand">{t('document.processingNotice')}</Alert>}
          {doc.status === 'FAILED' && doc.errorMessage && <Alert tone="danger">{doc.errorMessage}</Alert>}
          {doc.status === 'INDEXED' && doc.errorMessage && <Alert tone="warn">{doc.errorMessage}</Alert>}
          {doc.status === 'TEXT_ONLY' && <Alert tone="warn">{t('document.textOnlyNotice')}</Alert>}
          {doc.status === 'TEXT_ONLY' && doc.errorMessage && <Alert tone="warn">{doc.errorMessage}</Alert>}
          {doc.access === 'READ' && <Alert tone="brand">{t('document.readOnly')}</Alert>}
          {doc.duplicateOf && <DuplicateNotice doc={doc} />}
          <EInvoiceCard doc={doc} />
          {editable && <SharesCard doc={doc} />}
          <RemindersCard doc={doc} />
          <ContractCard doc={doc} />

          <div className="flex gap-1 rounded-lg bg-raised p-1">
            {(['overview', 'text', 'chat'] as const).map((key) => (
              <button
                key={key}
                type="button"
                onClick={() => setTab(key)}
                className={cx(
                  'h-8 flex-1 rounded-md text-[13px] font-medium transition-colors',
                  tab === key ? 'bg-surface text-ink shadow-sm' : 'text-muted hover:text-ink',
                )}
              >
                {t(`document.tabs.${key}`)}
              </button>
            ))}
          </div>

          {tab === 'overview' ? (
            <>
              {doc.summary && (
                <Card className="p-4">
                  <SectionTitle icon={<Sparkles className="size-4 text-brand" aria-hidden />}>{t('document.fields.summary')}</SectionTitle>
                  <p className="text-sm leading-relaxed text-ink">{doc.summary}</p>
                </Card>
              )}

              <Card className="p-4">
                <SectionTitle>{t('document.fields.tags')}</SectionTitle>
                <TagEditor doc={doc} disabled={busy || !editable} />
              </Card>

              {doc.keyFacts.length > 0 && (
                <Card className="p-4">
                  <SectionTitle>{t('document.keyFacts')}</SectionTitle>
                  <dl className="divide-y divide-line">
                    {doc.keyFacts.map((fact, index) => (
                      <div key={index} className="flex gap-4 py-2 text-sm first:pt-0 last:pb-0">
                        <dt className="w-2/5 shrink-0 text-muted">{fact.label}</dt>
                        <dd className="min-w-0 flex-1 font-medium [overflow-wrap:anywhere]">{fact.value}</dd>
                      </div>
                    ))}
                  </dl>
                </Card>
              )}

              <Card className="p-4">
                <SectionTitle>{t('document.details.title')}</SectionTitle>
                <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2 text-sm">
                  <AreaDetail areaId={doc.areaId} />
                  {doc.retainUntil && (
                    <Detail label={t('document.retention.label')}>
                      {doc.retentionLocked
                        ? t('document.retention.until', { date: formatLongDate(doc.retainUntil) })
                        : t('document.retention.expired', { date: formatLongDate(doc.retainUntil) })}
                    </Detail>
                  )}
                  <Detail label={t('document.details.file')}>{doc.originalFilename}</Detail>
                  <Detail label={t('document.details.size')}>
                    {doc.pageCount != null ? `${t('document.details.pageCount', { count: doc.pageCount })} · ` : ''}
                    {formatBytes(doc.sizeBytes)}
                  </Detail>
                  {doc.language && <Detail label={t('document.details.language')}>{doc.language.toUpperCase()}</Detail>}
                  <Detail label={t('document.details.source')}>
                    <span className="inline-flex items-center gap-1.5">
                      <SourceIcon className="size-4 text-muted" aria-hidden />
                      {t(`document.source.${doc.source}`)}
                    </span>
                  </Detail>
                  {doc.mailSubject && <Detail label={t('document.details.mailSubject')}>{doc.mailSubject}</Detail>}
                  {doc.mailFrom && <Detail label={t('document.details.mailFrom')}>{doc.mailFrom}</Detail>}
                  {doc.mailReceivedAt && <Detail label={t('document.details.mailReceived')}>{formatDateTime(doc.mailReceivedAt)}</Detail>}
                  <Detail label={t('document.details.added')}>{formatDateTime(doc.createdAt)}</Detail>
                  {doc.ocrUsed && (
                    <Detail label={t('document.details.text')}>
                      <OcrHint />
                    </Detail>
                  )}
                </dl>
              </Card>
            </>
          ) : tab === 'text' ? (
            <FullText id={doc.id} stamp={doc.processedAt} />
          ) : (
            <DocumentChat doc={doc} />
          )}
        </div>
      </div>

      {editing && <EditDialog doc={doc} onClose={() => setEditing(false)} />}
      <ConfirmDialog
        open={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        onConfirm={() => remove.mutate()}
        loading={remove.isPending}
        title={t('document.delete.title')}
        confirmLabel={t('document.delete.confirm')}
      >
        {t('document.delete.text', { title: doc.title })}
      </ConfirmDialog>
    </div>
  )
}

/**
 * Fragen an das Dokument. Das Gespräch lebt nur hier im Browser; jede Frage schickt den bisherigen Verlauf mit, damit
 * die KI Rückfragen versteht („Und bis wann?“).
 */
function DocumentChat({ doc }: { doc: Doc }) {
  const { t } = useTranslation()
  const { isAdmin } = useAuth()
  const system = useQuery({ queryKey: ['system'], queryFn: api.system })
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [draft, setDraft] = useState('')
  const end = useRef<HTMLDivElement>(null)

  const ask = useMutation({
    mutationFn: (conversation: ChatMessage[]) => api.chat(doc.id, conversation),
    onSuccess: ({ answer }) => setMessages((current) => [...current, { fromUser: false, text: answer }]),
    onError: (e) => {
      toast.error(errorMessage(e))
      // die unbeantwortete Frage zurück ins Eingabefeld, damit sie nicht verloren geht
      setMessages((current) => {
        const last = current.at(-1)
        if (last?.fromUser) setDraft(last.text)
        return last?.fromUser ? current.slice(0, -1) : current
      })
    },
  })

  useEffect(() => {
    end.current?.scrollIntoView({ block: 'nearest' })
  }, [messages, ask.isPending])

  function send(question: string) {
    const text = question.trim()
    if (!text || ask.isPending) return
    const conversation = [...messages, { fromUser: true, text }]
    setMessages(conversation)
    setDraft('')
    ask.mutate(conversation)
  }

  if (system.data && !system.data.aiEnabled) {
    return (
      <Card className="p-4 text-sm text-muted">
        {t('document.chat.notConfigured')}{' '}
        {isAdmin && (
          <Link to="/settings/ai" className="font-medium text-brand-ink underline underline-offset-2">
            {t('documents.aiNotice.action')}
          </Link>
        )}
      </Card>
    )
  }

  const suggestions = [t('document.chat.suggestions.summary'), t('document.chat.suggestions.deadlines'), t('document.chat.suggestions.amounts')]
  return (
    <Card className="flex flex-col gap-3 p-4">
      <div role="log" aria-live="polite" className="flex max-h-[55dvh] flex-col gap-2.5 overflow-y-auto">
        {messages.length === 0 && (
          <>
            <p className="text-sm text-muted">{t('document.chat.intro')}</p>
            <div className="flex flex-wrap gap-1.5">
              {suggestions.map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  onClick={() => send(suggestion)}
                  className="rounded-full border border-line bg-raised px-3 py-1 text-[13px] text-ink hover:border-brand hover:text-brand-ink"
                >
                  {suggestion}
                </button>
              ))}
            </div>
          </>
        )}
        {messages.map((message, index) => (
          <div
            key={index}
            className={cx(
              'max-w-[90%] rounded-xl px-3 py-2 text-sm leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]',
              message.fromUser ? 'self-end bg-brand text-white' : 'self-start bg-raised text-ink',
            )}
          >
            {message.fromUser ? message.text : <AnswerText text={message.text} />}
          </div>
        ))}
        {ask.isPending && (
          <div className="flex items-center gap-2 self-start text-[13px] text-muted">
            <Spinner className="size-4" /> {t('document.chat.thinking')}
          </div>
        )}
        <div ref={end} />
      </div>
      <form
        className="flex items-end gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          send(draft)
        }}
      >
        <Textarea
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              send(draft)
            }
          }}
          rows={2}
          maxLength={4000}
          placeholder={t('document.chat.placeholder')}
          aria-label={t('document.chat.placeholder')}
          className="min-h-0 flex-1 resize-none"
        />
        <Button type="submit" variant="primary" loading={ask.isPending} disabled={!draft.trim()}>
          {t('document.chat.send')}
        </Button>
      </form>
      {messages.length > 0 && !ask.isPending && (
        <button type="button" onClick={() => setMessages([])} className="self-start text-[13px] font-medium text-brand-ink hover:underline">
          {t('document.chat.restart')}
        </button>
      )}
    </Card>
  )
}

/** Antwort der KI: schlichter Text, nur **Fettes** wird dargestellt statt als Sternchen zu erscheinen. */
function AnswerText({ text }: { text: string }) {
  return (
    <>
      {text.split(/(\*\*[^*]+\*\*)/).map((part, index) =>
        part.startsWith('**') && part.endsWith('**') && part.length > 4 ? <strong key={index}>{part.slice(2, -2)}</strong> : part,
      )}
    </>
  )
}

/** Hinweis auf ein mögliches Duplikat – mit Weg zum anderen Dokument und der Möglichkeit, es zu verneinen. */
function DuplicateNotice({ doc }: { doc: Doc }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const other = useQuery({ queryKey: ['document', doc.duplicateOf], queryFn: () => api.document(doc.duplicateOf!) })
  const dismiss = useMutation({
    mutationFn: () => api.notDuplicate(doc.id),
    onSuccess: async (updated) => {
      queryClient.setQueryData(['document', doc.id], updated)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['documents'] }),
        queryClient.invalidateQueries({ queryKey: ['system'] }),
      ])
      toast.success(t('document.duplicate.dismissed'))
    },
    onError: (e) => toast.error(errorMessage(e)),
  })
  if (!other.data) return null
  return (
    <Alert
      tone="warn"
      action={
        <div className="flex shrink-0 flex-col items-end gap-1.5">
          <Link to={`/duplicates/${doc.id}`} className="font-semibold underline underline-offset-2">
            {t('duplicates.compare')}
          </Link>
          {canEdit(doc) && (
            <button type="button" onClick={() => dismiss.mutate()} disabled={dismiss.isPending} className="font-medium underline underline-offset-2">
              {t('document.duplicate.dismiss')}
            </button>
          )}
        </div>
      }
    >
      {t('document.duplicate.notice', { title: other.data.title })}
    </Alert>
  )
}

function SectionTitle({ icon, children }: { icon?: ReactNode; children: ReactNode }) {
  return (
    <h2 className="mb-2.5 flex items-center gap-1.5 text-xs font-semibold tracking-wider text-subtle uppercase">
      {icon}
      {children}
    </h2>
  )
}

/** Der Bereich – nur, wenn es mehr als einen gibt; sonst sagt er nichts. */
function AreaDetail({ areaId }: { areaId: string }) {
  const { t } = useTranslation()
  const areas = useAreas()
  if ((areas.data?.length ?? 0) < 2) return null
  return <Detail label={t('areas.label')}>{areaNameById(areas.data, areaId)}</Detail>
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-muted">{label}</dt>
      <dd className="min-w-0 [overflow-wrap:anywhere]">{children}</dd>
    </>
  )
}

function FullText({ id, stamp }: { id: string; stamp: string | null }) {
  const { t } = useTranslation()
  const text = useQuery({ queryKey: ['document', id, 'text', stamp], queryFn: () => api.documentText(id) })
  return (
    <Card className="p-4">
      {text.isPending ? (
        <div className="flex justify-center py-8">
          <Spinner />
        </div>
      ) : text.isError ? (
        <Alert tone="danger">{errorMessage(text.error)}</Alert>
      ) : text.data.content ? (
        <pre className="max-h-[60dvh] overflow-y-auto font-sans text-[13px] leading-relaxed whitespace-pre-wrap text-ink [overflow-wrap:anywhere]">
          {text.data.content}
        </pre>
      ) : (
        <p className="text-sm leading-relaxed text-muted">{t('document.noText')}</p>
      )}
    </Card>
  )
}

// ---------- Schlagworte direkt am Dokument ----------

/** So viele Schlagworte behält der Server je Dokument. */
const MAX_TAGS = 12

/** Dieselbe Bereinigung wie auf dem Server: Kleinschreibung, einfache Leerzeichen. */
const cleanTag = (raw: string) => raw.trim().toLowerCase().replace(/\s+/g, ' ').slice(0, 100)

/**
 * Die Schlagworte eines Dokuments: jedes führt zur Liste aller Dokumente mit diesem Schlagwort und lässt sich
 * mit dem × entfernen; „Hinzufügen“ öffnet ein Eingabefeld mit Vorschlägen. Jede Änderung wird sofort gespeichert.
 */
function TagEditor({ doc, disabled }: { doc: Doc; disabled: boolean }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [adding, setAdding] = useState(false)
  const [draft, setDraft] = useState('')
  const facets = useQuery({ queryKey: ['documents', 'facets'], queryFn: api.facets, enabled: adding })
  const key = ['document', doc.id]

  const save = useMutation({
    mutationKey: ['document-tags', doc.id],
    // Nacheinander speichern: Jede Änderung schickt die ganze Liste, die letzte muss auch als letzte ankommen.
    scope: { id: `document-tags-${doc.id}` },
    mutationFn: (tags: string[]) =>
      api.updateDocument(doc.id, {
        title: doc.title,
        summary: doc.summary,
        category: doc.category,
        correspondent: doc.correspondent,
        documentDate: doc.documentDate,
        tags,
      }),
    // Sofort anzeigen; schlägt das Speichern fehl, kommt der vorherige Stand zurück.
    onMutate: (tags) => {
      const previous = queryClient.getQueryData<Doc>(key)
      if (previous) queryClient.setQueryData<Doc>(key, { ...previous, tags })
      return { previous }
    },
    onError: (error, _tags, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous)
      toast.error(errorMessage(error))
    },
    onSuccess: (updated) => {
      // Folgt schon die nächste Änderung, gilt deren Stand – sonst spränge die Anzeige kurz zurück.
      if (queryClient.isMutating({ mutationKey: ['document-tags', doc.id] }) <= 1) queryClient.setQueryData(key, updated)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['documents'] }),
  })

  /** Der Stand, auf dem die nächste Änderung aufbaut – auch wenn die vorige noch nicht gezeichnet ist. */
  const currentTags = () => queryClient.getQueryData<Doc>(key)?.tags ?? doc.tags

  function add(raw: string[]) {
    const tags = [...currentTags()]
    for (const tag of raw.map(cleanTag)) {
      if (tag && !tags.includes(tag) && tags.length < MAX_TAGS) tags.push(tag)
    }
    // sortiert wie auf dem Server, damit die Anzeige nach dem Speichern nicht umspringt
    if (tags.length > currentTags().length) save.mutate(tags.sort())
  }
  function remove(tag: string) {
    save.mutate(currentTags().filter((item) => item !== tag))
  }
  function close() {
    setAdding(false)
    setDraft('')
  }
  function onChange(value: string) {
    // Tippt oder fügt jemand "rechnung, strom" ein, entstehen daraus zwei Schlagworte.
    if (value.includes(',')) {
      const parts = value.split(',')
      add(parts.slice(0, -1))
      setDraft(parts[parts.length - 1].trimStart())
    } else {
      setDraft(value)
    }
  }
  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') {
      event.preventDefault()
      add([draft])
      setDraft('')
    } else if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      close()
    }
  }

  const full = doc.tags.length >= MAX_TAGS
  const needle = cleanTag(draft)
  const suggestions = (facets.data?.tags ?? [])
    .map((facet) => facet.name)
    .filter((name) => !doc.tags.includes(name) && (needle === '' || name.includes(needle)))
    .slice(0, 8)

  if (doc.tags.length === 0 && !adding) {
    return (
      <p className="text-sm text-muted">
        <Trans
          i18nKey="document.tags.none"
          components={{
            add: <button type="button" onClick={() => setAdding(true)} disabled={disabled} className="font-medium text-brand-ink hover:underline disabled:opacity-50" />,
          }}
        />
      </p>
    )
  }

  return (
    <div>
      <ul className="flex flex-wrap items-center gap-1.5">
        {doc.tags.map((tag) => (
          <li
            key={tag}
            className="inline-flex max-w-full items-center rounded-full border border-line bg-raised text-[13px] font-medium text-muted transition-colors focus-within:border-brand/50 hover:border-brand/50"
          >
            <Link
              to={`/documents?tag=${encodeURIComponent(tag)}`}
              title={t('document.tags.allWith', { tag })}
              className={cx('truncate rounded-l-full py-1 pl-2.5 hover:text-brand-ink', disabled ? 'rounded-r-full pr-2.5' : 'pr-1')}
            >
              {tag}
            </Link>
            {!disabled && (
              <button
                type="button"
                onClick={() => remove(tag)}
                aria-label={t('document.tags.remove', { tag })}
                title={t('document.tags.remove', { tag })}
                className="mr-1 flex size-5 shrink-0 items-center justify-center rounded-full text-subtle hover:bg-danger-soft hover:text-danger"
              >
                <X className="size-3.5" aria-hidden />
              </button>
            )}
          </li>
        ))}
        {!disabled && !full && (
          <li className="inline-flex">
            {adding ? (
              <input
                autoFocus
                value={draft}
                onChange={(event) => onChange(event.target.value)}
                onKeyDown={onKeyDown}
                onBlur={() => {
                  add([draft])
                  close()
                }}
                maxLength={100}
                placeholder={t('document.tags.inputPlaceholder')}
                aria-label={t('document.tags.inputPlaceholder')}
                className="h-[30px] w-44 rounded-full border border-brand bg-surface px-3 text-[13px] text-ink ring-3 ring-brand/20 outline-none placeholder:text-subtle"
              />
            ) : (
              <button
                type="button"
                onClick={() => setAdding(true)}
                className="inline-flex h-[30px] items-center gap-1 rounded-full border border-dashed border-line-strong px-2.5 text-[13px] font-medium text-muted transition-colors hover:border-brand hover:text-brand-ink"
              >
                <Plus className="size-3.5" aria-hidden />
                {t('common.add')}
              </button>
            )}
          </li>
        )}
      </ul>
      {adding && suggestions.length > 0 && (
        <div className="mt-2.5 flex flex-wrap gap-1.5">
          {suggestions.map((suggestion) => (
            <button
              key={suggestion}
              type="button"
              // mousedown statt click: sonst schließt das Verlassen des Feldes die Vorschläge vor dem Klick
              onMouseDown={(event) => {
                event.preventDefault()
                add([suggestion])
                setDraft('')
              }}
              className="inline-flex items-center gap-1 rounded-full border border-dashed border-line-strong px-2 py-0.5 text-xs font-medium text-muted hover:border-brand hover:text-brand-ink"
            >
              <Plus className="size-3" aria-hidden />
              {suggestion}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

// ---------- Metadaten bearbeiten ----------

function EditDialog({ doc, onClose }: { doc: Doc; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const facets = useQuery({ queryKey: ['documents', 'facets'], queryFn: api.facets })
  const [title, setTitle] = useState(doc.title)
  const [summary, setSummary] = useState(doc.summary ?? '')
  const [category, setCategory] = useState(doc.category ? documentTypeLabel(doc.category) : '')
  const [correspondent, setCorrespondent] = useState(doc.correspondent ?? '')
  const [documentDate, setDocumentDate] = useState(doc.documentDate ?? '')
  const [tags, setTags] = useState<string[]>(doc.tags)
  const areas = useAreas()
  const [areaId, setAreaId] = useState(doc.areaId)
  const target = areas.data?.find((a) => a.id === areaId)

  const save = useMutation({
    mutationFn: () =>
      api.updateDocument(doc.id, {
        title: title.trim(),
        summary: summary.trim() || null,
        category: documentTypeValue(category) || null,
        correspondent: correspondent.trim() || null,
        documentDate: documentDate || null,
        tags,
        areaId: areaId === doc.areaId ? null : areaId,
      }),
    onSuccess: async (updated) => {
      queryClient.setQueryData(['document', doc.id], updated)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['documents'] }),
        queryClient.invalidateQueries({ queryKey: ['areas'] }),
        queryClient.invalidateQueries({ queryKey: ['history'] }),
      ])
      toast.success(t('document.edit.saved'))
      onClose()
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate()
  }

  return (
    <Modal open onClose={onClose} title={t('document.edit.title')} size="lg">
      <form onSubmit={submit} className="flex flex-col gap-4">
        {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
        <Field label={t('document.fields.title')}>
          {(id) => <Input id={id} value={title} onChange={(e) => setTitle(e.target.value)} maxLength={500} required />}
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t('document.fields.category')}>
            {(id) => (
              <>
                <Input id={id} value={category} onChange={(e) => setCategory(e.target.value)} list={`${id}-list`} maxLength={100} />
                <datalist id={`${id}-list`}>
                  {documentTypeOptions(facets.data?.categories.map((facet) => facet.name)).map((label) => <option key={label} value={label} />)}
                </datalist>
              </>
            )}
          </Field>
          <Field label={t('document.fields.documentDate')}>
            {(id) => <Input id={id} type="date" value={documentDate} onChange={(e) => setDocumentDate(e.target.value)} />}
          </Field>
        </div>
        <Field label={t('document.fields.correspondent')}>
          {(id) => (
            <>
              <Input id={id} value={correspondent} onChange={(e) => setCorrespondent(e.target.value)} list={`${id}-list`} maxLength={255} />
              <datalist id={`${id}-list`}>
                {facets.data?.correspondents.map((facet) => <option key={facet.name} value={facet.name} />)}
              </datalist>
            </>
          )}
        </Field>
        <Field label={t('document.fields.tags')} hint={t('document.tags.inputHint')}>
          {(id) => (
            <TagInput id={id} value={tags} onChange={setTags} suggestions={facets.data?.tags.map((facet) => facet.name) ?? []} />
          )}
        </Field>
        <Field label={t('document.fields.summary')}>
          {(id) => <Textarea id={id} value={summary} onChange={(e) => setSummary(e.target.value)} rows={4} maxLength={4000} />}
        </Field>
        {areas.data && areas.data.length > 1 && (
          <Field
            label={t('areas.label')}
            hint={
              areaId !== doc.areaId && target && !target.everyone
                ? t('document.edit.areaMoveHint', { area: areaName(target) })
                : t('document.edit.areaHint')
            }
          >
            {(id) => (
              <Select id={id} value={areaId} onChange={(e) => setAreaId(e.target.value)}>
                {areas.data.filter((area) => area.id === doc.areaId || area.myAccess !== 'READ').map((area) => (
                  <option key={area.id} value={area.id}>
                    {areaName(area)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        )}
        <div className="mt-1 flex justify-end gap-2">
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" variant="primary" loading={save.isPending}>
            {t('common.save')}
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function TagInput({ id, value, onChange, suggestions }: { id: string; value: string[]; onChange: (tags: string[]) => void; suggestions: string[] }) {
  const { t } = useTranslation()
  const [draft, setDraft] = useState('')
  const [focused, setFocused] = useState(false)

  function add(raw: string) {
    const tag = raw.trim().toLowerCase().replace(/\s+/g, ' ')
    if (tag && !value.includes(tag) && value.length < 12) onChange([...value, tag])
    setDraft('')
  }
  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault()
      add(draft)
    } else if (event.key === 'Backspace' && draft === '' && value.length > 0) {
      onChange(value.slice(0, -1))
    }
  }
  // Tippt jemand "rechnung, strom" am Stück, entstehen daraus zwei Schlagworte.
  useEffect(() => {
    if (draft.includes(',')) {
      const parts = draft.split(',')
      parts.slice(0, -1).forEach(add)
      setDraft(parts[parts.length - 1])
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft])

  const needle = draft.trim().toLowerCase()
  const matching = suggestions.filter((s) => !value.includes(s) && (needle === '' || s.includes(needle))).slice(0, 8)

  return (
    <div>
      <div className="flex min-h-10 flex-wrap items-center gap-1.5 rounded-lg border border-line-strong bg-surface px-2 py-1.5 focus-within:border-brand focus-within:ring-3 focus-within:ring-brand/20">
        {value.map((tag) => (
          <span key={tag} className="inline-flex items-center gap-1 rounded-full bg-brand-soft py-0.5 pr-1 pl-2.5 text-[13px] font-medium text-brand-ink">
            {tag}
            <button
              type="button"
              aria-label={t('document.tags.remove', { tag })}
              onClick={() => onChange(value.filter((t) => t !== tag))}
              className="flex size-4 items-center justify-center rounded-full hover:bg-brand/20"
            >
              <X className="size-3" />
            </button>
          </span>
        ))}
        <input
          id={id}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={onKeyDown}
          onFocus={() => setFocused(true)}
          onBlur={() => {
            setFocused(false)
            add(draft)
          }}
          placeholder={value.length === 0 ? t('document.tags.inputPlaceholder') : ''}
          className="h-7 min-w-28 flex-1 bg-transparent px-1 text-sm outline-none placeholder:text-subtle"
        />
      </div>
      {focused && matching.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {matching.map((suggestion) => (
            <button
              key={suggestion}
              type="button"
              // mousedown statt click: sonst nimmt das Verlassen des Feldes dem Klick den Vorschlag weg
              onMouseDown={(event) => {
                event.preventDefault()
                add(suggestion)
              }}
              className="inline-flex items-center gap-1 rounded-full border border-dashed border-line-strong px-2 py-0.5 text-xs font-medium text-muted hover:border-brand hover:text-brand-ink"
            >
              <Plus className="size-3" aria-hidden />
              {suggestion}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
