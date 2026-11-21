import { useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { toast } from 'sonner'
import { api, errorMessage } from './api'
import { t } from './i18n'

const isPdf = (file: File) => file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')

/** Lädt eine oder mehrere PDF-Dateien hoch und fasst das Ergebnis in einer Meldung zusammen. */
export function useUpload() {
  const queryClient = useQueryClient()

  return useCallback(
    async (files: File[]) => {
      const pdfs = files.filter(isPdf)
      const skipped = files.length - pdfs.length
      if (pdfs.length === 0) {
        toast.error(t('upload.onlyPdf'))
        return
      }
      const toastId = toast.loading(
        pdfs.length === 1 ? t('upload.uploadingFile', { name: pdfs[0].name }) : t('upload.uploadingFiles', { count: pdfs.length }),
      )
      try {
        // In Paketen senden, damit auch viele Dateien auf einmal das Anfragelimit nicht sprengen.
        const results = []
        for (let i = 0; i < pdfs.length; i += 5) {
          results.push(...(await api.upload(pdfs.slice(i, i + 5))))
        }
        const created = results.filter((r) => r.outcome === 'CREATED').length
        const duplicates = results.filter((r) => r.outcome === 'DUPLICATE').length
        const rejected = results.filter((r) => r.outcome === 'REJECTED').length + skipped

        const rejectedNote = rejected > 0 ? t('upload.rejected', { count: rejected }) : undefined
        const details = [duplicates > 0 && t('upload.duplicates', { count: duplicates }), rejectedNote].filter(Boolean)
        const description = details.length > 0 ? details.join(' · ') : undefined

        if (created > 0) {
          toast.success(t('upload.created', { count: created }), {
            id: toastId,
            description: description ?? t('upload.processingInBackground'),
          })
        } else if (duplicates > 0) {
          toast.info(t('upload.onlyDuplicates', { count: duplicates }), { id: toastId, description: rejectedNote })
        } else {
          toast.error(t('upload.noneValid'), { id: toastId })
        }
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: ['documents'] }),
          queryClient.invalidateQueries({ queryKey: ['system'] }),
        ])
      } catch (e) {
        toast.error(t('upload.failed'), { id: toastId, description: errorMessage(e) })
      }
    },
    [queryClient],
  )
}
