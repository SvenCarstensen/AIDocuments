import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, Eye, History, RotateCcw } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { api, canEdit, type Doc, type DocVersion, errorMessage, versionUrl } from '../lib/api'
import { formatBytes, formatDateTime } from '../lib/format'
import { Alert, Badge, Button, ConfirmDialog, IconButton, Modal, Spinner } from './ui'

/**
 * Die Fassungen der PDF-Datei: jede Änderung ergibt eine neue, keine geht verloren. Ältere lassen sich ansehen,
 * herunterladen und wiederherstellen (als neue Fassung).
 */
export function VersionsDialog({ doc, open, onClose }: { doc: Doc; open: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const versions = useQuery({ queryKey: ['versions', doc.id, doc.modifiedAt], queryFn: () => api.versions(doc.id), enabled: open })
  const [restoring, setRestoring] = useState<DocVersion | null>(null)

  const restore = useMutation({
    mutationFn: (number: number) => api.restoreVersion(doc.id, number),
    onSuccess: async (updated, number) => {
      setRestoring(null)
      queryClient.setQueryData(['document', doc.id], updated)
      await queryClient.invalidateQueries({ queryKey: ['versions', doc.id] })
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
      await queryClient.invalidateQueries({ queryKey: ['history'] })
      toast.success(t('document.versions.restored', { number }))
    },
    onError: (e) => {
      setRestoring(null)
      toast.error(errorMessage(e))
    },
  })

  function download(number: number) {
    const link = document.createElement('a')
    link.href = versionUrl(doc.id, number, true)
    link.download = ''
    link.click()
  }

  return (
    <Modal open={open} onClose={onClose} title={t('document.versions.title')} description={t('document.versions.intro')} size="lg">
      {versions.isPending ? (
        <div className="flex justify-center py-8">
          <Spinner />
        </div>
      ) : versions.isError ? (
        <Alert tone="danger">{errorMessage(versions.error)}</Alert>
      ) : (
        <ul className="divide-y divide-line rounded-xl border border-line">
          {versions.data.map((version) => (
            <li key={version.number} className="flex items-center gap-3 px-4 py-3">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-brand-soft text-sm font-semibold text-brand-ink tabular-nums">
                {version.number}
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                  <span className="text-sm font-semibold">{t(`document.versions.kinds.${version.kind}`, { from: version.restoredFrom ?? '' })}</span>
                  {version.current && <Badge tone="brand">{t('document.versions.current')}</Badge>}
                </div>
                <p className="mt-0.5 text-xs text-muted">
                  {[
                    version.createdAt && formatDateTime(version.createdAt),
                    version.createdBy && t('history.by', { user: version.createdBy }),
                    version.pageCount != null && t('document.details.pageCount', { count: version.pageCount }),
                    version.sizeBytes != null && formatBytes(version.sizeBytes),
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </p>
              </div>
              <IconButton
                label={t('document.versions.view', { number: version.number })}
                onClick={() => window.open(versionUrl(doc.id, version.number), '_blank', 'noopener')}
              >
                <Eye className="size-4" />
              </IconButton>
              <IconButton label={t('document.versions.download', { number: version.number })} onClick={() => download(version.number)}>
                <Download className="size-4" />
              </IconButton>
              {!version.current && canEdit(doc) && (
                <IconButton label={t('document.versions.restore', { number: version.number })} onClick={() => setRestoring(version)}>
                  <RotateCcw className="size-4" />
                </IconButton>
              )}
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={restoring !== null}
        onClose={() => setRestoring(null)}
        onConfirm={() => restoring && restore.mutate(restoring.number)}
        loading={restore.isPending}
        title={t('document.versions.restoreTitle', { number: restoring?.number ?? '' })}
        confirmLabel={t('document.versions.restoreConfirm')}
      >
        {t('document.versions.restoreText')}
      </ConfirmDialog>
    </Modal>
  )
}

/** Schaltfläche für die Dokumentansicht. */
export function VersionsButton({ doc, disabled }: { doc: Doc; disabled?: boolean }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button icon={<History className="size-4" aria-hidden />} onClick={() => setOpen(true)} disabled={disabled}>
        {t('document.versions.title')}
      </Button>
      <VersionsDialog doc={doc} open={open} onClose={() => setOpen(false)} />
    </>
  )
}
