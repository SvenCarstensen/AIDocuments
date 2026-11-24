import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BadgeCheck } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { FreeForNote, RequestCodeNote, UsageChoice } from '../../components/License'
import { Alert, Button, Card, Field, Spinner, Textarea } from '../../components/ui'
import { api, errorMessage, type LicenseStatus, type LicenseUsage } from '../../lib/api'
import { formatDate } from '../../lib/format'
import { SectionHeader } from './SectionHeader'

/** Nutzungsart und Freischaltcode – kostenlos für Privatpersonen, Vereine und Behörden. */
export function LicenseSettings() {
  const status = useQuery({ queryKey: ['settings', 'license'], queryFn: api.licenseSettings })
  if (status.isError) return <Alert tone="danger">{errorMessage(status.error)}</Alert>
  if (!status.data) {
    return (
      <div className="flex justify-center py-12">
        <Spinner />
      </div>
    )
  }
  return <LicenseForm stored={status.data} />
}

function LicenseForm({ stored }: { stored: LicenseStatus }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [status, setStatus] = useState(stored)
  const [usage, setUsage] = useState<LicenseUsage | null>(stored.usage)
  const [code, setCode] = useState('')

  const save = useMutation({
    // leer gelassen: den gespeicherten Code behalten
    mutationFn: ({ usage, code }: { usage: LicenseUsage; code?: string }) => api.updateLicense(usage, code),
    onSuccess: (data) => {
      queryClient.setQueryData(['settings', 'license'], data)
      void queryClient.invalidateQueries({ queryKey: ['system'] })
      setStatus(data)
      setUsage(data.usage)
      setCode('')
      toast.success(t('license.saved'))
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!usage) return
    save.mutate({ usage, code: code.trim() || undefined })
  }

  return (
    <section>
      <SectionHeader title={t('settings.license.title')}>{t('license.intro')}</SectionHeader>
      <Card className="p-4 sm:p-5">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <LicenseState status={status} />
          {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
          <UsageChoice value={usage} onChange={setUsage} required />
          {usage === 'BUSINESS' && (
            <Field label={t('license.code')} hint={status.codeStored ? t('license.codeKeepHint') : t('license.codeLaterHint')}>
              {(id) => (
                <Textarea
                  id={id}
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  rows={3}
                  spellCheck={false}
                  autoCapitalize="characters"
                  autoComplete="off"
                  placeholder="AIDOCS-XXXXX-XXXXX-…"
                  className="font-mono text-xs tracking-wide"
                  maxLength={1000}
                />
              )}
            </Field>
          )}
          {usage === 'BUSINESS' && <RequestCodeNote />}
          <div className="flex flex-wrap items-center gap-2">
            <Button type="submit" variant="primary" loading={save.isPending && save.variables?.code !== ''} disabled={!usage}>
              {t('common.save')}
            </Button>
            {usage === 'BUSINESS' && status.codeStored && (
              <Button variant="ghost" loading={save.isPending && save.variables?.code === ''} onClick={() => save.mutate({ usage: 'BUSINESS', code: '' })}>
                {t('license.removeCode')}
              </Button>
            )}
          </div>
        </form>
      </Card>
      <FreeForNote className="mt-4 !text-left" />
    </section>
  )
}

function LicenseState({ status }: { status: LicenseStatus }) {
  const { t } = useTranslation()
  switch (status.state) {
    case 'LICENSED':
      return (
        <Alert tone="ok">
          <span className="flex items-start gap-2">
            <BadgeCheck className="mt-0.5 size-4 shrink-0" aria-hidden />
            <span>
              <span className="font-semibold">{t('license.licensedTo', { name: status.licensee })}</span>
              <span className="block text-[13px]">
                {status.validUntil ? t('license.validUntil', { date: formatDate(status.validUntil) }) : t('license.unlimited')}
                {' · '}
                {t('license.number', { id: status.licenseId })}
              </span>
            </span>
          </span>
        </Alert>
      )
    case 'EXPIRED':
      return <Alert tone="warn">{t('license.expired', { name: status.licensee, date: formatDate(status.validUntil) })}</Alert>
    case 'UNLICENSED':
      return <Alert tone="warn">{status.codeStored ? t('license.storedInvalid') : t('license.missing')}</Alert>
    case 'FREE':
      return <Alert tone="ok">{t('license.freeState', { usage: t(`license.usage.${status.usage ?? 'PRIVATE'}`) })}</Alert>
    default:
      return <Alert tone="brand">{t('license.unset')}</Alert>
  }
}
