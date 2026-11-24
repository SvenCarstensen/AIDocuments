import { BadgeCheck, Building2, Home, Landmark, Users } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import type { LicenseUsage } from '../lib/api'
import { cx } from '../lib/format'

const usages: Array<{ value: LicenseUsage; icon: ReactNode }> = [
  { value: 'PRIVATE', icon: <Home className="size-4" aria-hidden /> },
  { value: 'CLUB', icon: <Users className="size-4" aria-hidden /> },
  { value: 'AUTHORITY', icon: <Landmark className="size-4" aria-hidden /> },
  { value: 'BUSINESS', icon: <Building2 className="size-4" aria-hidden /> },
]

/** Wer nutzt AIDocuments? Privat, Verein und Behörde kostenlos – Unternehmen mit Freischaltcode. */
export function UsageChoice({ value, onChange, required }: { value: LicenseUsage | null; onChange: (usage: LicenseUsage) => void; required?: boolean }) {
  const { t } = useTranslation()
  return (
    <fieldset className="flex flex-col gap-1.5">
      <legend className="mb-1.5 text-[13px] font-medium">{t('license.question')}</legend>
      <div className="grid grid-cols-2 gap-2">
        {usages.map((usage) => (
          <label
            key={usage.value}
            className={cx(
              'flex cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2.5 transition-colors has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-brand/40',
              value === usage.value ? 'border-brand bg-brand-soft/60' : 'border-line-strong hover:bg-raised',
            )}
          >
            <input
              type="radio"
              name="license-usage"
              value={usage.value}
              checked={value === usage.value}
              onChange={() => onChange(usage.value)}
              required={required}
              className="sr-only"
            />
            <span className={cx('mt-0.5', value === usage.value ? 'text-brand-ink' : 'text-muted')}>{usage.icon}</span>
            <span className="min-w-0">
              <span className="block text-sm font-medium">{t(`license.usage.${usage.value}`)}</span>
              <span className={cx('block text-xs', usage.value === 'BUSINESS' ? 'text-muted' : 'text-ok')}>
                {usage.value === 'BUSINESS' ? t('license.needsCode') : t('license.free')}
              </span>
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}

/** „Kostenlos für Privatpersonen, Vereine und Behörden“ – auf der Anmeldeseite und bei der Ersteinrichtung. */
export function FreeForNote({ className }: { className?: string }) {
  const { t } = useTranslation()
  return (
    <p className={cx('text-center text-[13px] leading-relaxed text-muted', className)}>
      <BadgeCheck className="mr-1.5 -mt-0.5 inline size-4 text-ok" aria-hidden />
      {t('license.freeFor')} {t('license.businessNeedsCode')}
    </p>
  )
}
