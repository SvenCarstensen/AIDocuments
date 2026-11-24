import type { TFunction } from 'i18next'
import type { LicenseStatus } from './api'
import { formatDate } from './format'

/** Eine Zeile zum Stand: kostenlos genutzt, freigeschaltet für …, nicht freigeschaltet. */
export function licenseSummary(status: LicenseStatus, t: TFunction): string | null {
  switch (status.state) {
    case 'FREE':
      return t('license.sidebarFree', { usage: t(`license.usage.${status.usage ?? 'PRIVATE'}`) })
    case 'LICENSED':
      return t('license.licensedTo', { name: status.licensee })
    case 'EXPIRED':
      return t('license.expired', { name: status.licensee, date: formatDate(status.validUntil) })
    case 'UNLICENSED':
      return t('license.sidebarUnlicensed')
    default:
      return null
  }
}
