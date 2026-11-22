import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { FilePlus2, History, Pencil, Trash2, Undo2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { sourceInfo } from '../components/documents'
import { Alert, Button, Card, EmptyState, Spinner } from '../components/ui'
import { api, errorMessage, type HistoryEntry } from '../lib/api'
import { useAuth } from '../lib/auth'
import { cx, formatDateTime } from '../lib/format'

const PAGE_SIZE = 50

/** Die Historie: neue Dokumente mit Quelle, Bearbeitungen und Löschungen – neueste zuerst. */
export function HistoryPage() {
  const { t } = useTranslation()
  const history = useInfiniteQuery({
    queryKey: ['history'],
    queryFn: ({ pageParam }) => api.history(pageParam, PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (last) => ((last.page + 1) * last.size < last.total ? last.page + 1 : undefined),
    refetchInterval: 15_000,
  })
  const entries = history.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <div className="mx-auto max-w-3xl">
      <h1 className="text-xl font-semibold tracking-tight">{t('history.title')}</h1>
      <p className="mt-1 mb-5 text-sm text-muted">{t('history.subtitle')}</p>

      {history.isError && <Alert tone="danger">{errorMessage(history.error)}</Alert>}
      {history.isPending ? (
        <div className="flex justify-center py-16">
          <Spinner className="size-6" />
        </div>
      ) : entries.length === 0 ? (
        <EmptyState icon={<History className="size-6" aria-hidden />} title={t('history.empty')} />
      ) : (
        <Card className="divide-y divide-line">
          {entries.map((entry) => (
            <HistoryRow key={entry.id} entry={entry} />
          ))}
        </Card>
      )}

      {history.hasNextPage && (
        <div className="mt-4 flex justify-center">
          <Button onClick={() => history.fetchNextPage()} loading={history.isFetchingNextPage}>
            {t('history.more')}
          </Button>
        </div>
      )}
    </div>
  )
}

function HistoryRow({ entry }: { entry: HistoryEntry }) {
  const { t } = useTranslation()
  const { isAdmin } = useAuth()
  const queryClient = useQueryClient()
  const undo = useMutation({
    mutationFn: () => api.undoHistory(entry.id),
    onSuccess: async (doc) => {
      queryClient.setQueryData(['document', doc.id], doc)
      await Promise.all(
        [['history'], ['documents'], ['system']].map((queryKey) => queryClient.invalidateQueries({ queryKey })),
      )
      toast.success(t('history.undone'))
    },
    onError: (e) => toast.error(errorMessage(e)),
  })
  // ein gelöschtes Dokument zurückholen dürfen – wie das Löschen – nur Administratoren
  const canUndo = entry.undoable && (entry.action !== 'DELETED' || isAdmin)
  const Icon = entry.action === 'IMPORTED' ? FilePlus2 : entry.action === 'EDITED' ? Pencil : Trash2
  const SourceIcon = entry.source ? sourceInfo[entry.source].icon : null
  const what =
    entry.change && entry.action !== 'IMPORTED'
      ? `${t(`history.actions.${entry.action}`)}: ${t(`history.changes.${entry.change}`)}`
      : t(`history.actions.${entry.action}`)
  return (
    <div className={cx('flex gap-3 px-4 py-3', entry.undoneAt && 'opacity-60')}>
      <span
        className={cx(
          'mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-lg',
          entry.action === 'IMPORTED' && 'bg-brand-soft text-brand-ink',
          entry.action === 'EDITED' && 'bg-raised text-ink',
          entry.action === 'DELETED' && 'bg-danger-soft text-danger',
        )}
      >
        <Icon className="size-4" aria-hidden />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-baseline justify-between gap-x-3">
          {entry.action === 'DELETED' ? (
            <span className="font-medium text-muted line-through [overflow-wrap:anywhere]">{entry.documentTitle}</span>
          ) : (
            <Link to={`/documents/${entry.documentId}`} className="font-medium text-ink hover:text-brand-ink hover:underline [overflow-wrap:anywhere]">
              {entry.documentTitle}
            </Link>
          )}
          <time dateTime={entry.at} className="shrink-0 text-xs text-subtle tabular-nums">
            {formatDateTime(entry.at)}
          </time>
        </div>
        <p className="mt-0.5 flex flex-wrap items-center gap-x-1.5 text-[13px] text-muted">
          <span className="font-medium text-ink">{what}</span>
          {entry.source && SourceIcon && (
            <>
              <span aria-hidden>·</span>
              <SourceIcon className="size-3.5" aria-hidden />
              <span>{t(`document.source.${entry.source}`)}</span>
            </>
          )}
          <span aria-hidden>·</span>
          <span>{entry.user ? t('history.by', { user: entry.user }) : t('history.automatic')}</span>
        </p>
        {entry.origin && <p className="mt-0.5 truncate text-xs text-subtle">{entry.origin}</p>}
        {entry.undoneAt && (
          <p className="mt-0.5 flex flex-wrap items-center gap-x-1.5 text-xs text-subtle">
            <Undo2 className="size-3.5" aria-hidden />
            <span>{t('history.undone')}</span>
            {entry.undoneBy && (
              <>
                <span aria-hidden>·</span>
                <span>{t('history.by', { user: entry.undoneBy })}</span>
              </>
            )}
            <span aria-hidden>·</span>
            <time dateTime={entry.undoneAt}>{formatDateTime(entry.undoneAt)}</time>
          </p>
        )}
      </div>
      {canUndo && (
        <Button
          size="sm"
          variant="ghost"
          className="shrink-0 self-center"
          icon={<Undo2 className="size-4" aria-hidden />}
          onClick={() => undo.mutate()}
          loading={undo.isPending}
        >
          {t('history.undo')}
        </Button>
      )}
    </div>
  )
}
