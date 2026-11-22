import { useCallback, useEffect, useRef, useState } from 'react'
import { currentLanguage } from './i18n'

/**
 * Anbindung an den Betrachter von PDF.js (public/pdfjs), der in einem iframe derselben Herkunft läuft: Sprache und
 * Design der Anwendung, eine immer passende Werkzeugleiste – und mitbekommen, ob das Dokument im Betrachter geändert
 * wurde (Markierungen, Text, Zeichnungen, Bilder, Unterschriften), damit es sich speichern lässt.
 */

/** Was die Anwendung vom Betrachter braucht – ein kleiner Ausschnitt seiner Schnittstelle. */
export interface PdfJsApp {
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

/** Adresse der Betrachter-Seite für eine PDF-Adresse. */
export const viewerSrc = (fileUrl: string) => `/pdfjs/web/viewer.html?file=${encodeURIComponent(fileUrl)}`

/**
 * @param src       Adresse der Betrachter-Seite (siehe {@link viewerSrc})
 * @param editable  false: Werkzeuge zum Bearbeiten aus, Formularfelder nur anzeigen (z. B. Link „nur ansehen“)
 */
export function usePdfJsFrame(src: string, editable = true) {
  const frame = useRef<HTMLIFrameElement>(null)
  const [modified, setModified] = useState(false)

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
      // PDFs kommen auch von außen (E-Mail-Anhänge): keine eingebetteten Skripte und keine XFA-Formulare
      // (eigene, selten genutzte Darstellung mit eigener Angriffsfläche)
      options.set('enableScripting', false)
      options.set('enableXfa', false)
      if (!editable) {
        options.set('annotationEditorMode', -1) // keine Werkzeuge zum Markieren, Schreiben, Zeichnen
        options.set('annotationMode', 1) // Formularfelder anzeigen, aber nicht ausfüllen
      }
    }
    document.addEventListener('webviewerloaded', configure)
    return () => document.removeEventListener('webviewerloaded', configure)
  }, [editable])

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
  const forgetChanges = useCallback(() => {
    app()?.pdfDocument?.annotationStorage.resetModified()
    setModified(false)
  }, [app])

  /** Das Dokument mit allen Änderungen aus dem Betrachter. */
  const savedBytes = useCallback(async () => {
    const pdf = app()?.pdfDocument
    if (!pdf) throw new Error('PDF.js nicht bereit')
    return pdf.saveDocument()
  }, [app])

  return { frame, modified, forgetChanges, savedBytes }
}
