import { t } from './i18n'

/**
 * Die Dokumentarten, aus denen die KI wählt (backend: AiAnalysisService.CATEGORIES). Gespeichert wird der deutsche
 * Name – so bleiben Filter, Aufbewahrungsfristen und Exporte unabhängig von der Sprache –, angezeigt wird die
 * Übersetzung aus „documentTypes“. Von Hand vergebene andere Arten erscheinen so, wie sie eingegeben wurden.
 */
const KEYS = {
  Rechnung: 'invoice',
  Angebot: 'quote',
  Bestellung: 'order',
  Lieferschein: 'deliveryNote',
  Mahnung: 'paymentReminder',
  Vertrag: 'contract',
  Kündigung: 'cancellation',
  Kontoauszug: 'bankStatement',
  Versicherung: 'insurance',
  Steuer: 'tax',
  Gehaltsabrechnung: 'payslip',
  Behördenschreiben: 'officialLetter',
  Bescheinigung: 'certificate',
  Zeugnis: 'reference',
  Arztbrief: 'medicalLetter',
  Brief: 'letter',
  Bericht: 'report',
  Anleitung: 'manual',
  Formular: 'form',
  Sonstiges: 'other',
} as const

type Stored = keyof typeof KEYS

function known(value: string): value is Stored {
  return Object.hasOwn(KEYS, value)
}

/** Der Name einer Dokumentart in der Sprache der Oberfläche. */
export function documentTypeLabel(value: string): string {
  return known(value) ? t(`documentTypes.${KEYS[value]}`) : value
}

/** Umgekehrt: eine Eingabe – übersetzt oder gespeichert, Groß-/Kleinschreibung egal – als zu speichernder Name. */
export function documentTypeValue(input: string): string {
  const wanted = input.trim().toLocaleLowerCase()
  for (const stored of Object.keys(KEYS) as Stored[]) {
    if (stored.toLocaleLowerCase() === wanted || documentTypeLabel(stored).toLocaleLowerCase() === wanted) return stored
  }
  return input.trim()
}

/** Vorschläge für ein Eingabefeld: alle bekannten Arten und die übrigen, die schon vorkommen – übersetzt, sortiert. */
export function documentTypeOptions(inUse: string[] = []): string[] {
  const labels = new Set([...Object.keys(KEYS), ...inUse].map(documentTypeLabel))
  return [...labels].sort((a, b) => a.localeCompare(b))
}
