import { formatLocale, formatNumber, t } from './i18n'

export { formatNumber }

// Alle Formate folgen der Sprache der Oberfläche. Die Intl-Formatierer sind teuer in der Herstellung,
// deshalb entsteht jeder je Sprache nur einmal.
const formatters = new Map<string, object>()

function cached<T extends object>(name: string, create: (locale: string) => T): T {
  const locale = formatLocale()
  const key = `${locale}|${name}`
  let formatter = formatters.get(key) as T | undefined
  if (!formatter) {
    formatter = create(locale)
    formatters.set(key, formatter)
  }
  return formatter
}

const dateFormat = () =>
  cached('date', (locale) => new Intl.DateTimeFormat(locale, { day: '2-digit', month: '2-digit', year: 'numeric' }))
const longDateFormat = () =>
  cached('longDate', (locale) => new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'long', year: 'numeric' }))
const dateTimeFormat = () =>
  cached(
    'dateTime',
    (locale) =>
      new Intl.DateTimeFormat(locale, {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      }),
  )
const relativeFormat = () => cached('relative', (locale) => new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }))

/** "2025-03-14" (reines Datum) ohne Zeitzonenverschiebung lesen. */
function parse(value: string): Date {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T12:00:00`) : new Date(value)
}

export const formatDate = (value: string | null | undefined) => (value ? dateFormat().format(parse(value)) : '')
export const formatLongDate = (value: string | null | undefined) => (value ? longDateFormat().format(parse(value)) : '')
export const formatDateTime = (value: string | null | undefined) => (value ? dateTimeFormat().format(parse(value)) : '')

export function formatRelative(value: string | null | undefined): string {
  if (!value) return ''
  const seconds = Math.round((parse(value).getTime() - Date.now()) / 1000)
  const abs = Math.abs(seconds)
  if (abs < 45) return t('common.justNow')
  if (abs < 3600) return relativeFormat().format(Math.round(seconds / 60), 'minute')
  if (abs < 86400) return relativeFormat().format(Math.round(seconds / 3600), 'hour')
  if (abs < 86400 * 7) return relativeFormat().format(Math.round(seconds / 86400), 'day')
  return formatDate(value)
}

/**
 * Zeitspanne mit Einheit – "30 Min.", "11 Std.", "2,5 Tage". Abkürzung und Mehrzahl liefert der Browser
 * passend zur Sprache; dafür braucht es keine eigenen Übersetzungen.
 */
export function formatDuration(value: number, unit: 'minute' | 'hour' | 'day', unitDisplay: 'short' | 'long' = 'short'): string {
  return cached(
    `${unit}|${unitDisplay}`,
    (locale) => new Intl.NumberFormat(locale, { style: 'unit', unit, unitDisplay, maximumFractionDigits: 1 }),
  ).format(value)
}

/** Restdauer als grobe Angabe – "noch ca. 11 Std. 30 Min." –, denn es ist eine Schätzung. */
export function formatRemaining(seconds: number): string {
  const minutes = Math.round(seconds / 60)
  if (minutes < 1) return t('documents.progress.remainingLessThan', { duration: formatDuration(1, 'minute') })
  let duration: string
  if (minutes < 60) {
    duration = formatDuration(minutes, 'minute')
  } else if (minutes < 48 * 60) {
    // ab einer Stunde auf fünf Minuten runden
    const rounded = Math.round(minutes / 5) * 5
    const hours = Math.floor(rounded / 60)
    const rest = rounded % 60
    duration = rest === 0 ? formatDuration(hours, 'hour') : `${formatDuration(hours, 'hour')} ${formatDuration(rest, 'minute')}`
  } else {
    duration = formatDuration(seconds / 86_400, 'day', 'long')
  }
  return t('documents.progress.remaining', { duration })
}

const finishFormat = () =>
  cached(
    'finish',
    (locale) =>
      new Intl.DateTimeFormat(locale, {
        weekday: 'short',
        day: '2-digit',
        month: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      }),
  )

/** Zeitpunkt, zu dem eine Restdauer abgelaufen ist: "Sa., 21.11., 21:45". */
export const formatFinishTime = (secondsFromNow: number) =>
  finishFormat().format(new Date(Date.now() + secondsFromNow * 1000))

export function formatBytes(bytes: number): string {
  // Die Einheiten stehen in den Übersetzungen, weil manche Sprachen sie anders schreiben (französisch "Mo").
  if (bytes < 1024) return t('common.fileSize.bytes', { value: formatNumber(bytes, { useGrouping: false }) })
  if (bytes < 1024 * 1024) {
    return t('common.fileSize.kilobytes', { value: formatNumber(Math.round(bytes / 1024), { useGrouping: false }) })
  }
  return t('common.fileSize.megabytes', { value: formatNumber(bytes / 1024 / 1024, { maximumFractionDigits: 1 }) })
}

export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ')
}
