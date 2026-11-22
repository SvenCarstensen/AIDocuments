import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Download, RotateCcw, Save, Undo2 } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { api, type Doc, errorMessage, fileUrl, originalUrl } from '../lib/api'
import { formatDateTime } from '../lib/format'
import { usePdfJsFrame, viewerSrc } from '../lib/pdfjs'
import { Button, ConfirmDialog } from './ui'

/**
 * Die PDF-Ansicht: der Betrachter von PDF.js, unter derselben Adresse wie die Anwendung ausgeliefert (public/pdfjs).
 * Anders als der eingebaute Betrachter des Browsers lässt er sich anpassen: Er spricht die Sprache der Oberfläche,
 * folgt ihrem Design, seine Werkzeugleiste passt immer vollständig in die verfügbare Breite – und Änderungen am
 * Dokument (Markierungen, Text, Zeichnungen, Bilder, Unterschriften) lassen sich auslesen und speichern.
 */

export function PdfViewer({ doc }: { doc: Doc }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [reloads, setReloads] = useState(0)
  const [confirmRestore, setConfirmRestore] = useState(false)

  // Jede gespeicherte Fassung hat eine eigene Adresse – so zeigt der Betrachter nie eine zwischengespeicherte alte.
  const version = `${doc.modifiedAt ?? 'original'}-${reloads}`
  const src = viewerSrc(`${fileUrl(doc.id)}?v=${encodeURIComponent(version)}`)
  const { frame, modified, forgetChanges, savedBytes } = usePdfJsFrame(src)

  const save = useMutation({
    mutationFn: async () => api.replaceFile(doc.id, await savedBytes()),
    onSuccess: async (updated) => {
      forgetChanges()
      queryClient.setQueryData(['document', doc.id], updated)
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
      toast.success(t('document.pdf.saved'))
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  const restore = useMutation({
    mutationFn: () => api.restoreOriginal(doc.id),
    onSuccess: async (updated) => {
      forgetChanges()
      setConfirmRestore(false)
      queryClient.setQueryData(['document', doc.id], updated)
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
      toast.success(t('document.pdf.restored'))
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  function discard() {
    forgetChanges()
    setReloads((n) => n + 1)
  }

  return (
    <div className="flex size-full flex-col">
      {modified && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-line bg-brand-soft px-3 py-2 text-sm text-brand-ink">
          <span className="mr-auto font-medium">{t('document.pdf.unsaved')}</span>
          <Button size="sm" variant="ghost" icon={<Undo2 className="size-4" aria-hidden />} onClick={discard} disabled={save.isPending}>
            {t('document.pdf.discard')}
          </Button>
          <Button size="sm" variant="primary" icon={<Save className="size-4" aria-hidden />} onClick={() => save.mutate()} loading={save.isPending}>
            {t('document.pdf.save')}
          </Button>
        </div>
      )}
      {!modified && doc.hasOriginal && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 border-b border-line bg-surface px-3 py-2 text-[13px] text-muted">
          <span className="mr-auto">{t('document.pdf.modified', { when: formatDateTime(doc.modifiedAt) })}</span>
          <a href={originalUrl(doc.id)} download className="inline-flex items-center gap-1 font-medium text-brand-ink hover:underline">
            <Download className="size-3.5" aria-hidden /> {t('document.pdf.downloadOriginal')}
          </a>
          <button
            type="button"
            onClick={() => setConfirmRestore(true)}
            className="inline-flex items-center gap-1 font-medium text-brand-ink hover:underline"
          >
            <RotateCcw className="size-3.5" aria-hidden /> {t('document.pdf.restoreOriginal')}
          </button>
        </div>
      )}
      <iframe ref={frame} key={src} src={src} title={t('document.pdfTitle', { title: doc.title })} className="min-h-0 w-full flex-1" />
      <RestoreDialog
        open={confirmRestore}
        onClose={() => setConfirmRestore(false)}
        onConfirm={() => restore.mutate()}
        loading={restore.isPending}
      />
    </div>
  )
}

function RestoreDialog(props: { open: boolean; onClose: () => void; onConfirm: () => void; loading: boolean }) {
  const { t } = useTranslation()
  return (
    <ConfirmDialog {...props} title={t('document.pdf.restoreTitle')} confirmLabel={t('document.pdf.restoreOriginal')}>
      {t('document.pdf.restoreText')}
    </ConfirmDialog>
  )
}
