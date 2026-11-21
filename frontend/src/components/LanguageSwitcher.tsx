import { Languages } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { cx } from '../lib/format'
import { availableLanguages, changeLanguage, currentLanguage } from '../lib/i18n'
import { Select } from './ui'

/**
 * Sprachwahl. Die Sprachen stehen in ihrer eigenen Schreibweise da und ein Symbol ersetzt die Beschriftung –
 * so findet sich auch zurecht, wer die gerade angezeigte Sprache nicht lesen kann.
 */
export function LanguageSwitcher({ className, selectClassName }: { className?: string; selectClassName?: string }) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)

  async function change(code: string) {
    setBusy(true)
    try {
      await changeLanguage(code)
    } catch {
      // Die Datei der Sprache ließ sich nicht nachladen – die bisherige Sprache bleibt.
      toast.error(t('errors.languageLoad'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <label className={cx('flex items-center gap-2.5 text-muted', className)}>
      <Languages className="size-4 shrink-0" aria-hidden />
      <Select
        aria-label={t('common.language')}
        title={t('common.language')}
        value={currentLanguage()}
        disabled={busy}
        onChange={(event) => void change(event.target.value)}
        className={cx('!h-8 !text-[13px]', selectClassName)}
      >
        {availableLanguages.map((language) => (
          <option key={language.code} value={language.code} lang={language.code}>
            {language.name}
          </option>
        ))}
      </Select>
    </label>
  )
}
