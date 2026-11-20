const dateFormat = new Intl.DateTimeFormat('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' })
const longDateFormat = new Intl.DateTimeFormat('de-DE', { day: 'numeric', month: 'long', year: 'numeric' })
const dateTimeFormat = new Intl.DateTimeFormat('de-DE', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})
const relativeFormat = new Intl.RelativeTimeFormat('de-DE', { numeric: 'auto' })

/** "2025-03-14" (reines Datum) ohne Zeitzonenverschiebung lesen. */
function parse(value: string): Date {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T12:00:00`) : new Date(value)
}

export const formatDate = (value: string | null | undefined) => (value ? dateFormat.format(parse(value)) : '')
export const formatLongDate = (value: string | null | undefined) => (value ? longDateFormat.format(parse(value)) : '')
export const formatDateTime = (value: string | null | undefined) => (value ? dateTimeFormat.format(parse(value)) : '')

export function formatRelative(value: string | null | undefined): string {
  if (!value) return ''
  const seconds = Math.round((parse(value).getTime() - Date.now()) / 1000)
  const abs = Math.abs(seconds)
  if (abs < 45) return 'gerade eben'
  if (abs < 3600) return relativeFormat.format(Math.round(seconds / 60), 'minute')
  if (abs < 86400) return relativeFormat.format(Math.round(seconds / 3600), 'hour')
  if (abs < 86400 * 7) return relativeFormat.format(Math.round(seconds / 86400), 'day')
  return formatDate(value)
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / 1024 / 1024).toLocaleString('de-DE', { maximumFractionDigits: 1 })} MB`
}

export const plural = (count: number, one: string, many: string) =>
  `${count.toLocaleString('de-DE')} ${count === 1 ? one : many}`

export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ')
}
