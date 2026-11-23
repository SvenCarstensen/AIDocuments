import { useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { useLocation } from 'react-router-dom'
import { toast } from 'sonner'
import { api, errorMessage } from './api'
import { areaNameById, useAreas } from './areas'
import { t } from './i18n'

/** Was die Dateiauswahl anbietet: PDFs, Fotos und E-Rechnungen als XML (der Server macht aus beiden PDFs). */
export const UPLOAD_ACCEPT =
  'application/pdf,.pdf,image/jpeg,image/png,image/heic,image/heif,.jpg,.jpeg,.png,.heic,.heif,application/xml,text/xml,.xml'

const SUPPORTED_TYPES = ['application/pdf', 'image/jpeg', 'image/png', 'image/heic', 'image/heif', 'application/xml', 'text/xml']
// HEIC hat in manchen Browsern keinen Typ – dann entscheidet die Endung
const isSupported = (file: File) => SUPPORTED_TYPES.includes(file.type) || /\.(pdf|jpe?g|png|hei[cf]|xml)$/i.test(file.name)
const isImage = (file: File) => file.type.startsWith('image/') || /\.(jpe?g|png|hei[cf])$/i.test(file.name)

/** Die Fotos unter den Dateien – ab zwei fragt die Oberfläche, ob sie ein Dokument ergeben. */
export const photosAmong = (files: File[]) => files.filter((file) => isSupported(file) && isImage(file))

/** So viele Fotos und so viele Bytes nimmt der Server in einer Anfrage zum Zusammenfassen an. */
export const MAX_COMBINED_PHOTOS = 100
export const MAX_COMBINED_BYTES = 240 * 1024 * 1024
export const canCombine = (photos: File[]) =>
  photos.length <= MAX_COMBINED_PHOTOS && photos.reduce((sum, file) => sum + file.size, 0) <= MAX_COMBINED_BYTES

/** Seite 2 vor Seite 10: Fotos in der Reihenfolge ihrer Dateinamen. */
const byName = (a: File, b: File) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' })

/**
 * Lädt eine oder mehrere PDF-Dateien oder Fotos hoch und fasst das Ergebnis in einer Meldung zusammen.
 * Ist die Dokumentliste gerade auf einen Bereich gefiltert, kommen die Dateien in diesen Bereich, sonst in den
 * allgemeinen.
 */
export function useUpload() {
  const queryClient = useQueryClient()
  const location = useLocation()
  const areas = useAreas()
  const area = location.pathname.startsWith('/documents') ? (new URLSearchParams(location.search).get('area') ?? '') : ''
  // Den Bereich nur nennen, wenn es eine Wahl gibt
  const areaNote = (areas.data?.length ?? 0) > 1 ? t('upload.intoArea', { area: areaNameById(areas.data, area || 'general') }) : undefined

  return useCallback(
    /** @param combine die Fotos darunter zu einem Dokument zusammenfassen (die übrigen Dateien bleiben einzeln) */
    async (files: File[], combine = false) => {
      const pdfs = files.filter(isSupported)
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
        const photos = combine ? photosAmong(pdfs).sort(byName) : []
        const single = combine && photos.length > 1 ? pdfs.filter((file) => !photos.includes(file)) : pdfs
        if (photos.length > 1) results.push(...(await api.upload(photos, area, true)))
        for (let i = 0; i < single.length; i += 5) {
          results.push(...(await api.upload(single.slice(i, i + 5), area)))
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
            description: [description ?? t('upload.processingInBackground'), areaNote].filter(Boolean).join(' · '),
          })
        } else if (duplicates > 0) {
          toast.info(t('upload.onlyDuplicates', { count: duplicates }), { id: toastId, description: rejectedNote })
        } else {
          // bei einer einzelnen Datei den Grund nennen (etwa: HEIC kann der Server nicht umwandeln)
          const reason = results.length === 1 && skipped === 0 ? (results[0].message ?? undefined) : undefined
          toast.error(t('upload.noneValid'), { id: toastId, description: reason })
        }
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: ['documents'] }),
          queryClient.invalidateQueries({ queryKey: ['system'] }),
          queryClient.invalidateQueries({ queryKey: ['areas'] }),
        ])
      } catch (e) {
        toast.error(t('upload.failed'), { id: toastId, description: errorMessage(e) })
      }
    },
    [queryClient, area, areaNote],
  )
}
