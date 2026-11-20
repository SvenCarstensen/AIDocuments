import { useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { toast } from 'sonner'
import { api, errorMessage } from './api'
import { plural } from './format'

const isPdf = (file: File) => file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')

/** Lädt eine oder mehrere PDF-Dateien hoch und fasst das Ergebnis in einer Meldung zusammen. */
export function useUpload() {
  const queryClient = useQueryClient()

  return useCallback(
    async (files: File[]) => {
      const pdfs = files.filter(isPdf)
      const skipped = files.length - pdfs.length
      if (pdfs.length === 0) {
        toast.error('Es können nur PDF-Dateien hinzugefügt werden.')
        return
      }
      const toastId = toast.loading(
        pdfs.length === 1 ? `„${pdfs[0].name}“ wird hochgeladen …` : `${pdfs.length} PDFs werden hochgeladen …`,
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

        const details = [
          duplicates > 0 && `${duplicates} bereits vorhanden`,
          rejected > 0 && `${plural(rejected, 'Datei', 'Dateien')} kein gültiges PDF`,
        ].filter(Boolean)
        const description = details.length > 0 ? details.join(' · ') : undefined

        if (created > 0) {
          toast.success(`${plural(created, 'Dokument', 'Dokumente')} hinzugefügt`, {
            id: toastId,
            description: description ?? 'Die Verarbeitung läuft im Hintergrund.',
          })
        } else if (duplicates > 0) {
          toast.info(duplicates === 1 ? 'Dieses Dokument ist bereits vorhanden.' : 'Diese Dokumente sind bereits vorhanden.', {
            id: toastId,
            description: rejected > 0 ? `${plural(rejected, 'Datei', 'Dateien')} kein gültiges PDF` : undefined,
          })
        } else {
          toast.error('Keine der Dateien ist ein gültiges PDF.', { id: toastId })
        }
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: ['documents'] }),
          queryClient.invalidateQueries({ queryKey: ['system'] }),
        ])
      } catch (e) {
        toast.error('Hochladen fehlgeschlagen', { id: toastId, description: errorMessage(e) })
      }
    },
    [queryClient],
  )
}
