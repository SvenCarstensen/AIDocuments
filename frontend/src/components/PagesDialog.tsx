import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, FilePlus2, Files, RotateCw, Scissors, Search, Trash2, Undo2 } from 'lucide-react'
import { type DragEvent, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { api, canDelete, type Doc, type DocumentQuery, errorMessage } from '../lib/api'
import { cx, formatLongDate } from '../lib/format'
import { Alert, Button, ConfirmDialog, IconButton, Input, Modal, Spinner } from './ui'

interface Item {
  /** die bisherige Seite (ab 1) */
  page: number
  rotate: number
  removed: boolean
}

const initial = (count: number): Item[] => Array.from({ length: count }, (_, i) => ({ page: i + 1, rotate: 0, removed: false }))

/**
 * Seiten bearbeiten: umsortieren (ziehen oder mit den Pfeilen), drehen, entfernen, ab einer Seite in ein eigenes
 * Dokument trennen und andere Dokumente anhängen. Gespeichert wird als neue Fassung – die bisherige bleibt erhalten.
 */
export function PagesDialog({ doc, open, onClose }: { doc: Doc; open: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [items, setItems] = useState<Item[]>(() => initial(doc.pageCount ?? 0))
  const [dragging, setDragging] = useState<number | null>(null)
  const [splitAt, setSplitAt] = useState<number | null>(null)
  const [merging, setMerging] = useState(false)

  const kept = items.filter((item) => !item.removed)
  const changed = items.some((item, index) => item.removed || item.rotate % 360 !== 0 || item.page !== index + 1)

  async function refresh(updated: Doc) {
    queryClient.setQueryData(['document', doc.id], updated)
    await Promise.all(
      [['documents'], ['versions', doc.id], ['history'], ['system']].map((queryKey) => queryClient.invalidateQueries({ queryKey })),
    )
  }

  const save = useMutation({
    mutationFn: () => api.rearrangePages(doc.id, kept.map(({ page, rotate }) => ({ page, rotate: rotate % 360 }))),
    onSuccess: async (updated) => {
      await refresh(updated)
      toast.success(t('pages.saved'))
      onClose()
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  const split = useMutation({
    mutationFn: (fromPage: number) => api.splitDocument(doc.id, fromPage),
    onSuccess: async (result) => {
      setSplitAt(null)
      await refresh(result.original)
      toast.success(t('pages.split.done'), {
        action: { label: t('pages.split.open'), onClick: () => navigate(`/documents/${result.createdId}`) },
      })
      onClose()
    },
    onError: (e) => {
      setSplitAt(null)
      toast.error(errorMessage(e))
    },
  })

  function move(from: number, to: number) {
    if (to < 0 || to >= items.length || from === to) return
    setItems((list) => {
      const next = [...list]
      const [item] = next.splice(from, 1)
      next.splice(to, 0, item)
      return next
    })
  }
  function update(index: number, change: Partial<Item>) {
    setItems((list) => list.map((item, i) => (i === index ? { ...item, ...change } : item)))
  }

  function onDrop(event: DragEvent, index: number) {
    event.preventDefault()
    if (dragging !== null) move(dragging, index)
    setDragging(null)
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t('pages.title')}
      description={t('pages.intro')}
      size="xl"
      footer={
        <>
          <Button icon={<FilePlus2 className="size-4" aria-hidden />} onClick={() => setMerging(true)} disabled={changed} className="mr-auto" title={changed ? t('pages.saveFirst') : undefined}>
            {t('pages.merge.button')}
          </Button>
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button variant="primary" onClick={() => save.mutate()} loading={save.isPending} disabled={!changed || kept.length === 0}>
            {t('common.save')}
          </Button>
        </>
      }
    >
      {kept.length === 0 && <Alert tone="warn">{t('pages.noneLeft')}</Alert>}
      <ol className="grid max-h-[60dvh] grid-cols-2 gap-3 overflow-y-auto p-1 sm:grid-cols-3 lg:grid-cols-4">
        {items.map((item, index) => (
          <li
            key={item.page}
            draggable
            onDragStart={() => setDragging(index)}
            onDragEnd={() => setDragging(null)}
            onDragOver={(event) => event.preventDefault()}
            onDrop={(event) => onDrop(event, index)}
            className={cx(
              'flex flex-col overflow-hidden rounded-xl border bg-surface shadow-card transition-opacity',
              dragging === index ? 'border-brand opacity-50' : 'border-line',
              item.removed && 'opacity-45',
            )}
          >
            <div className="relative flex aspect-[3/4] cursor-grab items-center justify-center overflow-hidden bg-raised p-2">
              <img
                src={api.pageImageUrl(doc.id, item.page, doc.modifiedAt)}
                alt={t('pages.page', { number: item.page })}
                loading="lazy"
                draggable={false}
                className="max-h-full max-w-full object-contain shadow-sm transition-transform"
                style={{ transform: `rotate(${item.rotate}deg)` }}
              />
              <span className="absolute top-1.5 left-1.5 rounded-md bg-surface/90 px-1.5 text-xs font-semibold tabular-nums shadow-sm">
                {index + 1}
              </span>
              {item.page !== index + 1 && (
                <span className="absolute top-1.5 right-1.5 rounded-md bg-brand-soft px-1.5 text-[11px] font-medium text-brand-ink">
                  {t('pages.wasPage', { number: item.page })}
                </span>
              )}
            </div>
            <div className="flex items-center justify-center gap-0.5 border-t border-line px-1 py-1">
              <IconButton label={t('pages.moveLeft')} onClick={() => move(index, index - 1)} disabled={index === 0}>
                <ArrowLeft className="size-4" />
              </IconButton>
              <IconButton label={t('pages.rotate')} onClick={() => update(index, { rotate: item.rotate + 90 })} disabled={item.removed}>
                <RotateCw className="size-4" />
              </IconButton>
              {item.removed ? (
                <IconButton label={t('pages.keep')} onClick={() => update(index, { removed: false })}>
                  <Undo2 className="size-4" />
                </IconButton>
              ) : (
                <IconButton label={t('pages.remove')} onClick={() => update(index, { removed: true })} className="hover:!bg-danger-soft hover:!text-danger">
                  <Trash2 className="size-4" />
                </IconButton>
              )}
              <IconButton
                label={changed ? t('pages.saveFirst') : t('pages.split.here')}
                onClick={() => setSplitAt(index + 1)}
                disabled={index === 0 || changed}
              >
                <Scissors className="size-4" />
              </IconButton>
              <IconButton label={t('pages.moveRight')} onClick={() => move(index, index + 1)} disabled={index === items.length - 1}>
                <ArrowRight className="size-4" />
              </IconButton>
            </div>
          </li>
        ))}
      </ol>
      <ConfirmDialog
        open={splitAt !== null}
        onClose={() => setSplitAt(null)}
        onConfirm={() => splitAt !== null && split.mutate(splitAt)}
        loading={split.isPending}
        title={t('pages.split.title')}
        confirmLabel={t('pages.split.confirm')}
      >
        {t('pages.split.text', { page: splitAt ?? '' })}
      </ConfirmDialog>
      {merging && (
        <MergeDialog
          doc={doc}
          onClose={() => setMerging(false)}
          onMerged={async (updated) => {
            setMerging(false)
            await refresh(updated)
            onClose()
          }}
        />
      )}
    </Modal>
  )
}

const searchQuery = (q: string): DocumentQuery => ({
  q,
  category: '',
  correspondent: '',
  tags: [],
  status: '',
  duplicates: false,
  from: '',
  to: '',
  sort: 'newest',
  area: '',
  retentionExpired: false,
})

/** Andere Dokumente auswählen, deren Seiten angehängt werden; sie kommen danach in den Papierkorb. */
function MergeDialog({ doc, onClose, onMerged }: { doc: Doc; onClose: () => void; onMerged: (updated: Doc) => Promise<void> }) {
  const { t } = useTranslation()
  const [q, setQ] = useState('')
  const [selected, setSelected] = useState<Doc[]>([])
  const results = useQuery({ queryKey: ['documents', 'merge-search', q], queryFn: () => api.documents(searchQuery(q), 0, 20) })

  const merge = useMutation({
    mutationFn: () => api.mergeDocuments(doc.id, selected.map((d) => d.id)),
    onSuccess: async (updated) => {
      toast.success(t('pages.merge.done', { count: selected.length }))
      await onMerged(updated)
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  function toggle(candidate: Doc) {
    setSelected((list) => (list.some((d) => d.id === candidate.id) ? list.filter((d) => d.id !== candidate.id) : [...list, candidate]))
  }

  const candidates = (results.data?.items ?? []).filter((d) => d.id !== doc.id)

  return (
    <Modal
      open
      onClose={onClose}
      title={t('pages.merge.title')}
      description={t('pages.merge.intro', { title: doc.title })}
      size="lg"
      footer={
        <>
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button variant="primary" onClick={() => merge.mutate()} loading={merge.isPending} disabled={selected.length === 0}>
            {t('pages.merge.confirm', { count: selected.length })}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-subtle" aria-hidden />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('pages.merge.search')} aria-label={t('pages.merge.search')} className="pl-9" autoFocus />
        </div>
        {results.isPending ? (
          <div className="flex justify-center py-6">
            <Spinner />
          </div>
        ) : results.isError ? (
          <Alert tone="danger">{errorMessage(results.error)}</Alert>
        ) : candidates.length === 0 ? (
          <p className="py-4 text-center text-sm text-muted">{t('pages.merge.none')}</p>
        ) : (
          <ul className="flex max-h-80 flex-col gap-1 overflow-y-auto rounded-lg border border-line p-1.5">
            {candidates.map((candidate) => {
              const position = selected.findIndex((d) => d.id === candidate.id)
              const reason = !canDelete(candidate)
                ? t('pages.merge.noDeleteRight')
                : candidate.retentionLocked && candidate.retainUntil
                  ? t('document.retention.locked', { date: formatLongDate(candidate.retainUntil) })
                  : null
              return (
                <li key={candidate.id}>
                  <label className={cx('flex items-center gap-3 rounded-md px-2 py-1.5 text-sm', reason ? 'opacity-55' : 'cursor-pointer hover:bg-raised')}>
                    <input type="checkbox" className="size-4 accent-brand" checked={position >= 0} disabled={reason !== null} onChange={() => toggle(candidate)} />
                    <Files className="size-4 shrink-0 text-subtle" aria-hidden />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-medium">{candidate.title}</span>
                      <span className="block truncate text-xs text-muted">
                        {reason ?? t('document.details.pageCount', { count: candidate.pageCount ?? 0 })}
                      </span>
                    </span>
                    {position >= 0 && (
                      <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-brand text-xs font-semibold text-white tabular-nums">
                        {position + 1}
                      </span>
                    )}
                  </label>
                </li>
              )
            })}
          </ul>
        )}
        <p className="text-xs text-muted">{t('pages.merge.hint')}</p>
      </div>
    </Modal>
  )
}

/** Schaltfläche für die Dokumentansicht. */
export function PagesButton({ doc, disabled }: { doc: Doc; disabled?: boolean }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button icon={<Files className="size-4" aria-hidden />} onClick={() => setOpen(true)} disabled={disabled || !doc.pageCount}>
        {t('pages.button')}
      </Button>
      {open && <PagesDialog doc={doc} open onClose={() => setOpen(false)} />}
    </>
  )
}
