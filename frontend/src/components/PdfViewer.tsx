import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Download, RotateCcw, Save, Undo2 } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { api, type Doc, errorMessage, fileUrl, originalUrl } from '../lib/api'
import { formatDateTime } from '../lib/format'
import { currentLanguage } from '../lib/i18n'
import { Button, ConfirmDialog } from './ui'

/**
 * Die PDF-Ansicht: der Betrachter von PDF.js, unter derselben Adresse wie die Anwendung ausgeliefert (public/pdfjs).
 * Anders als der eingebaute Betrachter des Browsers lässt er sich anpassen: Er spricht die Sprache der Oberfläche,
 * folgt ihrem Design, seine Werkzeugleiste passt immer vollständig in die verfügbare Breite – und Änderungen am
 * Dokument (Markierungen, Text, Zeichnungen, Bilder, Unterschriften) lassen sich auslesen und speichern.
 */

/** Was die Anwendung vom Betrachter braucht – ein kleiner Ausschnitt seiner Schnittstelle. */
interface PdfJsApp {
  initializedPromise: Promise<void>
  pdfDocument: {
    annotationStorage: {
      onSetModified: (() => void) | null
      onResetModified: (() => void) | null
      resetModified: () => void
    }
    saveDocument: () => Promise<Uint8Array>
  } | null
  eventBus: { on: (name: string, listener: () => void) => void }
}
interface PdfJsWindow extends Window {
  PDFViewerApplication: PdfJsApp
  PDFViewerApplicationOptions: { set: (name: string, value: unknown) => void }
}

/** Stufen, in denen die Werkzeugleiste Platz schafft – die Regeln stehen in public/pdfjs-fit.css. */
const FIT_LEVELS = 7

/** Blendet stufenweise aus, bis die Werkzeugleiste ohne Überlauf in die Breite passt. */
function fitToolbar(win: Window) {
  const html = win.document.documentElement
  const toolbar = win.document.getElementById('toolbarViewer')
  if (!toolbar) return
  for (let level = 1; level <= FIT_LEVELS; level++) html.classList.remove(`aid-fit-${level}`)
  for (let level = 1; level <= FIT_LEVELS && toolbar.scrollWidth > toolbar.clientWidth + 1; level++) {
    html.classList.add(`aid-fit-${level}`)
  }
}

export function PdfViewer({ doc }: { doc: Doc }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const frame = useRef<HTMLIFrameElement>(null)
  const [modified, setModified] = useState(false)
  const [reloads, setReloads] = useState(0)
  const [confirmRestore, setConfirmRestore] = useState(false)

  // Jede gespeicherte Fassung hat eine eigene Adresse – so zeigt der Betrachter nie eine zwischengespeicherte alte.
  const version = `${doc.modifiedAt ?? 'original'}-${reloads}`
  const src = `/pdfjs/web/viewer.html?file=${encodeURIComponent(`${fileUrl(doc.id)}?v=${encodeURIComponent(version)}`)}`

  const app = useCallback(() => (frame.current?.contentWindow as PdfJsWindow | null)?.PDFViewerApplication, [])

  // Vor dem Start des Betrachters: Sprache und Design der Anwendung, keine eigenen gespeicherten Vorlieben.
  useEffect(() => {
    function configure(event: Event) {
      const source = (event as CustomEvent<{ source: PdfJsWindow }>).detail?.source
      if (!source || source !== frame.current?.contentWindow) return
      const options = source.PDFViewerApplicationOptions
      options.set('disablePreferences', true)
      options.set('localeProperties', { lang: currentLanguage() })
      options.set('viewerCssTheme', document.documentElement.classList.contains('dark') ? 2 : 1)
      options.set('disableHistory', true)
    }
    document.addEventListener('webviewerloaded', configure)
    return () => document.removeEventListener('webviewerloaded', configure)
  }, [])

  // Nach dem Laden: Werkzeugleiste einpassen und Änderungen am Dokument mitbekommen.
  useEffect(() => {
    const iframe = frame.current
    if (!iframe) return
    async function attach() {
      const win = iframe?.contentWindow as PdfJsWindow | null
      if (!win?.PDFViewerApplication) return
      // gleich und noch einmal kurz danach: Der Betrachter ordnet seine Leiste nach einer Größenänderung selbst um
      const fit = () => {
        fitToolbar(win)
        win.setTimeout(() => fitToolbar(win), 120)
      }
      // als Datei, nicht als eingebetteter Stil: Die Sicherheitsrichtlinie des Betrachters erlaubt nur Stile derselben Herkunft
      const style = win.document.createElement('link')
      style.rel = 'stylesheet'
      style.href = '/pdfjs-fit.css'
      style.addEventListener('load', fit)
      win.document.head.append(style)
      // Das Fenster des Betrachters meldet seine neue Größe erst, wenn es sie auch übernommen hat – dann messen.
      win.addEventListener('resize', fit)
      const viewer = win.PDFViewerApplication
      await viewer.initializedPromise
      viewer.eventBus.on('localized', fit)
      viewer.eventBus.on('documentloaded', () => {
        fit()
        const storage = viewer.pdfDocument?.annotationStorage
        if (!storage) return
        const onSet = storage.onSetModified
        const onReset = storage.onResetModified
        storage.onSetModified = () => {
          onSet?.()
          setModified(true)
        }
        storage.onResetModified = () => {
          onReset?.()
          setModified(false)
        }
      })
      fit()
    }
    setModified(false)
    iframe.addEventListener('load', attach)
    return () => {
      iframe.removeEventListener('load', attach)
    }
  }, [src])

  /** Änderungen im Betrachter vergessen – sonst fragt er beim Neuladen, ob man die Seite wirklich verlassen will. */
  function forgetChanges() {
    app()?.pdfDocument?.annotationStorage.resetModified()
    setModified(false)
  }

  const save = useMutation({
    mutationFn: async () => {
      const pdf = app()?.pdfDocument
      if (!pdf) throw new Error(t('errors.unexpected'))
      return api.replaceFile(doc.id, await pdf.saveDocument())
    },
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
