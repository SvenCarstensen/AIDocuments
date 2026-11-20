import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Download, ExternalLink, Pencil, Plus, RefreshCw, Sparkles, Trash2, X } from 'lucide-react'
import { type FormEvent, type KeyboardEvent, type ReactNode, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { isBusy, OcrHint, sourceInfo, StatusBadge, TagChip } from '../components/documents'
import { Alert, Badge, Button, Card, ConfirmDialog, Field, Input, Modal, Spinner, Textarea } from '../components/ui'
import { api, ApiError, type Doc, errorMessage, fileUrl } from '../lib/api'
import { useAuth } from '../lib/auth'
import { cx, formatBytes, formatDateTime, formatLongDate, plural } from '../lib/format'

export function DocumentPage() {
  const { id = '' } = useParams()
  const { isAdmin } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [tab, setTab] = useState<'overview' | 'text'>('overview')

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
      toast.success('Das Dokument wird neu analysiert.')
    },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const remove = useMutation({
    mutationFn: () => api.deleteDocument(id),
    onSuccess: async () => {
      toast.success('Dokument gelöscht')
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
          <ArrowLeft className="size-4" /> Zur Übersicht
        </Link>
      </div>
    )
  }

  const busy = isBusy(doc)
  const SourceIcon = sourceInfo[doc.source].icon

  return (
    <div>
      <div className="mb-5 flex flex-wrap items-start gap-x-4 gap-y-3">
        <button
          type="button"
          onClick={() => (window.history.length > 1 ? navigate(-1) : navigate('/documents'))}
          aria-label="Zurück"
          className="mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-lg border border-line-strong bg-surface text-muted hover:bg-raised hover:text-ink"
        >
          <ArrowLeft className="size-[18px]" />
        </button>
        <div className="min-w-0 flex-1 basis-72">
          <h1 className="text-xl leading-tight font-semibold tracking-tight [overflow-wrap:anywhere]">{doc.title}</h1>
          <div className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted">
            {doc.category && <Badge tone="brand">{doc.category}</Badge>}
            <StatusBadge doc={doc} />
            {doc.correspondent && <span>{doc.correspondent}</span>}
            {doc.correspondent && doc.documentDate && <span aria-hidden>·</span>}
            {doc.documentDate && <span>{formatLongDate(doc.documentDate)}</span>}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <a href={fileUrl(doc.id, true)} download>
            <Button icon={<Download className="size-4" aria-hidden />}>Herunterladen</Button>
          </a>
          <Button icon={<Pencil className="size-4" aria-hidden />} onClick={() => setEditing(true)} disabled={busy}>
            Bearbeiten
          </Button>
          {isAdmin && (
            <>
              <Button
                icon={<RefreshCw className="size-4" aria-hidden />}
                onClick={() => reprocess.mutate()}
                loading={reprocess.isPending}
                disabled={busy}
                title="PDF erneut auslesen und von der KI analysieren lassen"
              >
                Neu analysieren
              </Button>
              <Button
                variant="ghost"
                className="!text-danger hover:!bg-danger-soft"
                icon={<Trash2 className="size-4" aria-hidden />}
                onClick={() => setConfirmDelete(true)}
              >
                Löschen
              </Button>
            </>
          )}
        </div>
      </div>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_26rem]">
        {/* PDF-Ansicht: der eingebaute Betrachter des Browsers (blättern, zoomen, im PDF suchen, drucken) */}
        <div className="relative h-[70dvh] overflow-hidden rounded-xl border border-line bg-raised shadow-card xl:sticky xl:top-22 xl:h-[calc(100dvh-10.5rem)]">
          <iframe src={fileUrl(doc.id)} title={`PDF: ${doc.title}`} className="size-full" />
          <a
            href={fileUrl(doc.id)}
            target="_blank"
            rel="noreferrer"
            className="absolute right-3 bottom-3 inline-flex items-center gap-1.5 rounded-lg border border-line bg-surface/95 px-2.5 py-1.5 text-xs font-medium text-muted shadow-card backdrop-blur hover:text-ink"
          >
            <ExternalLink className="size-3.5" aria-hidden /> In neuem Tab öffnen
          </a>
        </div>

        <div className="flex min-w-0 flex-col gap-4">
          {busy && (
            <Alert tone="brand">
              Das Dokument wird gerade verarbeitet. Zusammenfassung und Schlagworte erscheinen hier, sobald die
              Analyse fertig ist.
            </Alert>
          )}
          {doc.status === 'FAILED' && doc.errorMessage && <Alert tone="danger">{doc.errorMessage}</Alert>}
          {doc.status === 'INDEXED' && doc.errorMessage && <Alert tone="warn">{doc.errorMessage}</Alert>}
          {doc.status === 'TEXT_ONLY' && (
            <Alert tone="warn">
              Ohne KI erschlossen: Das Dokument ist über seinen Text durchsuchbar, hat aber keine Zusammenfassung
              und keine automatischen Schlagworte.
            </Alert>
          )}

          <div className="flex gap-1 rounded-lg bg-raised p-1">
            {(
              [
                ['overview', 'Übersicht'],
                ['text', 'Volltext'],
              ] as const
            ).map(([key, label]) => (
              <button
                key={key}
                type="button"
                onClick={() => setTab(key)}
                className={cx(
                  'h-8 flex-1 rounded-md text-[13px] font-medium transition-colors',
                  tab === key ? 'bg-surface text-ink shadow-sm' : 'text-muted hover:text-ink',
                )}
              >
                {label}
              </button>
            ))}
          </div>

          {tab === 'overview' ? (
            <>
              {doc.summary && (
                <Card className="p-4">
                  <SectionTitle icon={<Sparkles className="size-4 text-brand" aria-hidden />}>Zusammenfassung</SectionTitle>
                  <p className="text-sm leading-relaxed text-ink">{doc.summary}</p>
                </Card>
              )}

              <Card className="p-4">
                <SectionTitle>Schlagworte</SectionTitle>
                {doc.tags.length > 0 ? (
                  <div className="flex flex-wrap gap-1.5">
                    {doc.tags.map((tag) => (
                      <Link key={tag} to={`/documents?tag=${encodeURIComponent(tag)}`} title={`Alle Dokumente mit „${tag}“`}>
                        <TagChip tag={tag} className="!px-2.5 !py-1 !text-[13px]" />
                      </Link>
                    ))}
                  </div>
                ) : (
                  <p className="text-sm text-muted">
                    Keine Schlagworte.{' '}
                    <button type="button" onClick={() => setEditing(true)} disabled={busy} className="font-medium text-brand-ink hover:underline">
                      Hinzufügen
                    </button>
                  </p>
                )}
              </Card>

              {doc.keyFacts.length > 0 && (
                <Card className="p-4">
                  <SectionTitle>Kerndaten</SectionTitle>
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
                <SectionTitle>Details</SectionTitle>
                <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2 text-sm">
                  <Detail label="Datei">{doc.originalFilename}</Detail>
                  <Detail label="Umfang">
                    {doc.pageCount != null ? `${plural(doc.pageCount, 'Seite', 'Seiten')} · ` : ''}
                    {formatBytes(doc.sizeBytes)}
                  </Detail>
                  {doc.language && <Detail label="Sprache">{doc.language.toUpperCase()}</Detail>}
                  <Detail label="Herkunft">
                    <span className="inline-flex items-center gap-1.5">
                      <SourceIcon className="size-4 text-muted" aria-hidden />
                      {sourceInfo[doc.source].label}
                    </span>
                  </Detail>
                  {doc.mailSubject && <Detail label="Betreff">{doc.mailSubject}</Detail>}
                  {doc.mailFrom && <Detail label="Von">{doc.mailFrom}</Detail>}
                  {doc.mailReceivedAt && <Detail label="Empfangen">{formatDateTime(doc.mailReceivedAt)}</Detail>}
                  <Detail label="Hinzugefügt">{formatDateTime(doc.createdAt)}</Detail>
                  {doc.ocrUsed && (
                    <Detail label="Text">
                      <OcrHint />
                    </Detail>
                  )}
                </dl>
              </Card>
            </>
          ) : (
            <FullText id={doc.id} stamp={doc.processedAt} />
          )}
        </div>
      </div>

      {editing && <EditDialog doc={doc} onClose={() => setEditing(false)} />}
      <ConfirmDialog
        open={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        onConfirm={() => remove.mutate()}
        loading={remove.isPending}
        title="Dokument löschen?"
        confirmLabel="Endgültig löschen"
      >
        „{doc.title}“ wird mitsamt PDF-Datei, Volltext und Schlagworten gelöscht. Das lässt sich nicht rückgängig
        machen.
      </ConfirmDialog>
    </div>
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

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-muted">{label}</dt>
      <dd className="min-w-0 [overflow-wrap:anywhere]">{children}</dd>
    </>
  )
}

function FullText({ id, stamp }: { id: string; stamp: string | null }) {
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
        <p className="text-sm leading-relaxed text-muted">
          Für dieses Dokument liegt kein Text vor – vermutlich ein Scan ohne Textebene. Mit eingerichteter KI und
          aktivierter Texterkennung wird der Inhalt beim Analysieren transkribiert.
        </p>
      )}
    </Card>
  )
}

// ---------- Metadaten bearbeiten ----------

function EditDialog({ doc, onClose }: { doc: Doc; onClose: () => void }) {
  const queryClient = useQueryClient()
  const facets = useQuery({ queryKey: ['documents', 'facets'], queryFn: api.facets })
  const [title, setTitle] = useState(doc.title)
  const [summary, setSummary] = useState(doc.summary ?? '')
  const [category, setCategory] = useState(doc.category ?? '')
  const [correspondent, setCorrespondent] = useState(doc.correspondent ?? '')
  const [documentDate, setDocumentDate] = useState(doc.documentDate ?? '')
  const [tags, setTags] = useState<string[]>(doc.tags)

  const save = useMutation({
    mutationFn: () =>
      api.updateDocument(doc.id, {
        title: title.trim(),
        summary: summary.trim() || null,
        category: category.trim() || null,
        correspondent: correspondent.trim() || null,
        documentDate: documentDate || null,
        tags,
      }),
    onSuccess: async (updated) => {
      queryClient.setQueryData(['document', doc.id], updated)
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
      toast.success('Änderungen gespeichert')
      onClose()
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate()
  }

  return (
    <Modal open onClose={onClose} title="Dokument bearbeiten" size="lg">
      <form onSubmit={submit} className="flex flex-col gap-4">
        {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
        <Field label="Titel">
          {(id) => <Input id={id} value={title} onChange={(e) => setTitle(e.target.value)} maxLength={500} required />}
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Dokumentart">
            {(id) => (
              <>
                <Input id={id} value={category} onChange={(e) => setCategory(e.target.value)} list={`${id}-list`} maxLength={100} />
                <datalist id={`${id}-list`}>
                  {facets.data?.categories.map((facet) => <option key={facet.name} value={facet.name} />)}
                </datalist>
              </>
            )}
          </Field>
          <Field label="Dokumentdatum">
            {(id) => <Input id={id} type="date" value={documentDate} onChange={(e) => setDocumentDate(e.target.value)} />}
          </Field>
        </div>
        <Field label="Absender">
          {(id) => (
            <>
              <Input id={id} value={correspondent} onChange={(e) => setCorrespondent(e.target.value)} list={`${id}-list`} maxLength={255} />
              <datalist id={`${id}-list`}>
                {facets.data?.correspondents.map((facet) => <option key={facet.name} value={facet.name} />)}
              </datalist>
            </>
          )}
        </Field>
        <Field label="Schlagworte" hint="Mit Enter oder Komma bestätigen. Vorschläge sind bereits verwendete Schlagworte.">
          {(id) => (
            <TagInput id={id} value={tags} onChange={setTags} suggestions={facets.data?.tags.map((facet) => facet.name) ?? []} />
          )}
        </Field>
        <Field label="Zusammenfassung">
          {(id) => <Textarea id={id} value={summary} onChange={(e) => setSummary(e.target.value)} rows={4} maxLength={4000} />}
        </Field>
        <div className="mt-1 flex justify-end gap-2">
          <Button onClick={onClose}>Abbrechen</Button>
          <Button type="submit" variant="primary" loading={save.isPending}>
            Speichern
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function TagInput({ id, value, onChange, suggestions }: { id: string; value: string[]; onChange: (tags: string[]) => void; suggestions: string[] }) {
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
              aria-label={`Schlagwort „${tag}“ entfernen`}
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
          placeholder={value.length === 0 ? 'Schlagwort eingeben …' : ''}
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
