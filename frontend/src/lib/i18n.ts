// Mehrsprachigkeit: Einrichtung von i18next, Wahl der Sprache und Nachladen der Übersetzungsdateien.
//
// Jede Sprache ist eine Datei src/locales/<code>.json. Englisch ist die Rückfallsprache und fest eingebaut;
// alle anderen werden erst geladen, wenn sie gebraucht werden. Eine neue Datei in diesem Ordner genügt,
// damit die Sprache in der Auswahl erscheint – am Code ist dafür nichts zu ändern.

import i18next, { type Resource, type TFunction } from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from '../locales/en.json'

type Messages = typeof en

/** Rückfallsprache: gilt, wenn keine Sprache des Benutzers übersetzt ist oder einer Übersetzung ein Text fehlt. */
export const FALLBACK_LANGUAGE = 'en'

/** Unter diesem Namen merkt sich der Browser die in der Oberfläche gewählte Sprache. */
const STORAGE_KEY = 'language'

/** Die Sprachen in ihrer eigenen Schreibweise – so findet jede ihre Sprache, egal was gerade angezeigt wird. */
const NATIVE_NAMES: Record<string, string> = {
  bg: 'Български',
  cs: 'Čeština',
  da: 'Dansk',
  de: 'Deutsch',
  el: 'Ελληνικά',
  en: 'English',
  es: 'Español',
  et: 'Eesti',
  fi: 'Suomi',
  fr: 'Français',
  ga: 'Gaeilge',
  hr: 'Hrvatski',
  hu: 'Magyar',
  it: 'Italiano',
  lt: 'Lietuvių',
  lv: 'Latviešu',
  mt: 'Malti',
  nl: 'Nederlands',
  pl: 'Polski',
  pt: 'Português',
  ro: 'Română',
  sk: 'Slovenčina',
  sl: 'Slovenščina',
  sv: 'Svenska',
  nb: 'Norsk bokmål',
  is: 'Íslenska',
  uk: 'Українська',
  ru: 'Русский',
  be: 'Беларуская',
  sr: 'Српски',
  bs: 'Bosanski',
  mk: 'Македонски',
  sq: 'Shqip',
  tr: 'Türkçe',
  ca: 'Català',
  lb: 'Lëtzebuergesch',
}

/** Sprachkennzeichen, für die es keine eigene Datei gibt, die aber mit einer vorhandenen gut bedient sind. */
const ALIASES: Record<string, string> = { no: 'nb', nn: 'nb' }

// Vite macht aus jeder Datei ein eigenes Paket, das erst beim Aufruf geladen wird. Englisch ist ausgenommen,
// weil es oben schon fest eingebunden ist.
const files = import.meta.glob<Messages>(['../locales/*.json', '!../locales/en.json'], { import: 'default' })
const loaders = new Map<string, () => Promise<Messages>>(
  Object.entries(files).map(([path, load]) => [path.replace(/^.*\/([^/]+)\.json$/, '$1'), load]),
)

export interface Language {
  code: string
  /** Name der Sprache in dieser Sprache selbst, z. B. "Deutsch". */
  name: string
}

function nativeName(code: string): string {
  if (NATIVE_NAMES[code]) return NATIVE_NAMES[code]
  // Nicht in der Liste (etwa eine Regionalvariante wie "pt-BR"): den Namen vom Browser erfragen.
  try {
    const name = new Intl.DisplayNames([code], { type: 'language' }).of(code)
    if (name && name !== code) return name.charAt(0).toLocaleUpperCase(code) + name.slice(1)
  } catch {
    /* unbekanntes Kennzeichen – dann steht eben das Kürzel da */
  }
  return code
}

/** Alle Sprachen, für die eine Übersetzungsdatei vorliegt – abgeleitet aus dem Ordner src/locales. */
export const availableLanguages: Language[] = [FALLBACK_LANGUAGE, ...loaders.keys()]
  .map((code) => ({ code, name: nativeName(code) }))
  .sort((a, b) => a.name.localeCompare(b.name, 'en'))

const findLanguage = (code: string) =>
  availableLanguages.find((language) => language.code.toLowerCase() === code.toLowerCase())?.code ?? null

/** Sucht zu einem Sprachkennzeichen ("de-AT") die vorhandene Sprache: erst genau, dann die Grundsprache. */
function matchLanguage(tag: string): string | null {
  const base = tag.split('-')[0].toLowerCase()
  return findLanguage(tag) ?? findLanguage(base) ?? (ALIASES[base] ? findLanguage(ALIASES[base]) : null)
}

/** Die im Browser eingestellten Sprachen (Systemsprache zuerst), in der Reihenfolge der Vorliebe. */
function browserLanguages(): readonly string[] {
  if (typeof navigator === 'undefined') return []
  const languages = navigator.languages?.length ? navigator.languages : [navigator.language]
  return languages.filter(Boolean)
}

function storedLanguage(): string | null {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    // Nur gelten lassen, was es (noch) gibt – sonst entscheidet wieder die Systemsprache.
    return stored && availableLanguages.some((language) => language.code === stored) ? stored : null
  } catch {
    return null // privater Modus – dann gilt die Systemsprache
  }
}

/**
 * Die Sprache für diesen Start: die in der Oberfläche gewählte, sonst die erste Sprache des Systems, für die
 * es eine Übersetzung gibt, sonst Englisch.
 */
export function detectLanguage(): string {
  const stored = storedLanguage()
  if (stored) return stored
  for (const tag of browserLanguages()) {
    const match = matchLanguage(tag)
    if (match) return match
  }
  return FALLBACK_LANGUAGE
}

/** Die Sprache, in der die Oberfläche gerade angezeigt wird. */
export function currentLanguage(): string {
  return i18next.resolvedLanguage ?? i18next.language ?? FALLBACK_LANGUAGE
}

async function loadLanguage(code: string): Promise<void> {
  if (code === FALLBACK_LANGUAGE || i18next.hasResourceBundle(code, 'translation')) return
  const load = loaders.get(code)
  if (!load) throw new Error(`No translation file for "${code}"`)
  i18next.addResourceBundle(code, 'translation', await load())
}

/** Wechselt die Sprache der Oberfläche, lädt sie bei Bedarf nach und merkt sich die Wahl für spätere Starts. */
export async function changeLanguage(code: string): Promise<void> {
  await loadLanguage(code)
  await i18next.changeLanguage(code)
  try {
    localStorage.setItem(STORAGE_KEY, code)
  } catch {
    /* privater Modus – dann eben nur für diese Sitzung */
  }
}

/**
 * Richtet die Übersetzungen ein und lädt die passende Sprache. Die Anwendung wartet darauf, bevor sie
 * zeichnet – so blitzt kein Text in der falschen Sprache auf.
 */
export async function initI18n(): Promise<void> {
  let language = detectLanguage()
  const resources: Resource = { [FALLBACK_LANGUAGE]: { translation: en } }
  if (language !== FALLBACK_LANGUAGE) {
    try {
      resources[language] = { translation: await loaders.get(language)!() }
    } catch {
      // Datei nicht ladbar (etwa nach einem Update mit altem Seitenstand): lieber Englisch als gar nichts.
      // Die gespeicherte Wahl bleibt unangetastet und gilt beim nächsten Start wieder.
      language = FALLBACK_LANGUAGE
    }
  }

  i18next.on('languageChanged', syncDocument)
  await i18next.use(initReactI18next).init({
    resources,
    lng: language,
    fallbackLng: FALLBACK_LANGUAGE,
    // React maskiert Texte selbst; doppeltes Maskieren würde z. B. aus "&" ein "&amp;" machen.
    interpolation: { escapeValue: false },
    react: { useSuspense: false },
  })
  // Zahlen in Sätzen ("{{count, number}}") so schreiben wie alle anderen Zahlen der Oberfläche.
  i18next.services.formatter?.add('number', (value) => formatNumber(Number(value)))
  syncDocument()
}

/** <html lang> und Schreibrichtung folgen der Sprache – wichtig für Vorlesehilfen, Silbentrennung und Schriftwahl. */
function syncDocument() {
  formatLocaleCache = null
  document.documentElement.lang = currentLanguage()
  document.documentElement.dir = i18next.dir(currentLanguage())
}

/**
 * Übersetzen außerhalb von Komponenten (Meldungen, Formatierung, Fehlertexte) – immer in der aktuellen Sprache.
 * In Komponenten stattdessen useTranslation() verwenden, damit sie beim Sprachwechsel neu zeichnen.
 */
export const t: TFunction = i18next.t.bind(i18next)

// ---------- Zahlen und Formate ----------

let formatLocaleCache: string | null = null

/**
 * Das Kennzeichen für Datums- und Zahlenformate. Es folgt der Sprache der Oberfläche; steht dieselbe Sprache
 * im Browser mit Region ("de-AT", "en-US"), gilt deren Schreibweise. Englisch ohne Region wird europäisch
 * formatiert (Tag vor Monat, 24 Stunden), weil es hier als Rückfallsprache für ganz Europa dient.
 */
export function formatLocale(): string {
  if (formatLocaleCache) return formatLocaleCache
  const language = currentLanguage()
  let locale = language
  if (!language.includes('-')) {
    const regional = browserLanguages().find((tag) => tag.includes('-') && tag.split('-')[0].toLowerCase() === language)
    locale = regional ?? (language === 'en' ? 'en-GB' : language)
  }
  try {
    locale = Intl.getCanonicalLocales(locale)[0] ?? language
  } catch {
    locale = language
  }
  formatLocaleCache = locale
  return locale
}

const numberFormats = new Map<string, Intl.NumberFormat>()

/** Zahl in der Schreibweise der aktuellen Sprache, z. B. 1.234,5 oder 1,234.5. */
export function formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
  const locale = formatLocale()
  const key = options ? `${locale}|${JSON.stringify(options)}` : locale
  let format = numberFormats.get(key)
  if (!format) {
    format = new Intl.NumberFormat(locale, options)
    numberFormats.set(key, format)
  }
  return format.format(value)
}
