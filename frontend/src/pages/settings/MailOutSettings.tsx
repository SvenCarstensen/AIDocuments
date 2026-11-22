import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { type FormEvent, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Alert, Button, Card, Field, Input, Segmented, Select, Spinner, Switch } from '../../components/ui'
import { api, errorMessage, type MailOutInput, type MailOutSettings, type MailSecurity, type TestResult } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { SectionHeader } from './SectionHeader'

const defaultPorts: Record<MailSecurity, number> = { SSL: 465, STARTTLS: 587, NONE: 25 }

/** E-Mail-Versand (SMTP) für Erinnerungen an fällige Wiedervorlagen. */
export function MailOutSettingsSection() {
  const settings = useQuery({ queryKey: ['settings', 'mail-out'], queryFn: api.mailOutSettings })
  if (settings.isError) return <Alert tone="danger">{errorMessage(settings.error)}</Alert>
  if (!settings.data) {
    return (
      <div className="flex justify-center py-12">
        <Spinner />
      </div>
    )
  }
  // erst mit den gespeicherten Werten aufbauen – spätere Abfragen überschreiben keine Eingaben
  return <MailOutForm stored={settings.data} />
}

function MailOutForm({ stored }: { stored: MailOutSettings }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [form, setForm] = useState<MailOutInput>(() => {
    const { passwordSet: _ignored, ...rest } = stored
    return { ...rest, password: null }
  })
  const [passwordSet, setPasswordSet] = useState(stored.passwordSet)
  const [test, setTest] = useState<TestResult | null>(null)

  const save = useMutation({
    mutationFn: (body: MailOutInput) => api.updateMailOutSettings(body),
    onSuccess: (data) => {
      queryClient.setQueryData(['settings', 'mail-out'], data)
      setPasswordSet(data.passwordSet)
      setForm((f) => ({ ...f, password: null }))
      toast.success(t('settings.mailOut.saved'))
    },
  })
  const runTest = useMutation({
    mutationFn: api.testMailOut,
    onSuccess: setTest,
    onError: (e) => setTest({ ok: false, message: errorMessage(e) }),
  })

  const set = <K extends keyof MailOutInput>(key: K, value: MailOutInput[K]) => setForm({ ...form, [key]: value })

  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate({ ...form, password: form.password || null })
  }

  return (
    <section>
      <SectionHeader title={t('settings.mailOut.title')}>{t('settings.mailOut.intro')}</SectionHeader>
      <Card className="p-4 sm:p-5">
        <form onSubmit={submit} className="flex flex-col gap-4">
          {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
          <Switch checked={form.enabled} onChange={(v) => set('enabled', v)} label={t('settings.mailOut.enabled')} description={t('settings.mailOut.enabledHint')} />
          <div className="grid gap-4 sm:grid-cols-[1fr_8rem]">
            <Field label={t('settings.mailOut.host')}>
              {(id) => <Input id={id} value={form.host} onChange={(e) => set('host', e.target.value)} placeholder="smtp.example.com" spellCheck={false} autoCapitalize="none" />}
            </Field>
            <Field label={t('settings.mailOut.port')}>
              {(id) => <Input id={id} type="number" min={1} max={65535} value={form.port} onChange={(e) => set('port', Number(e.target.value))} />}
            </Field>
          </div>
          <div className="flex flex-col gap-1.5">
            <span className="text-[13px] font-medium">{t('settings.mailOut.security')}</span>
            <Segmented
              label={t('settings.mailOut.security')}
              value={form.security}
              onChange={(security) => setForm({ ...form, security, port: form.port === defaultPorts[form.security] ? defaultPorts[security] : form.port })}
              options={[
                { value: 'STARTTLS', label: 'STARTTLS' },
                { value: 'SSL', label: 'SSL/TLS' },
                { value: 'NONE', label: t('settings.mailOut.unencrypted') },
              ]}
            />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label={t('common.username')} hint={t('settings.mailOut.usernameHint')}>
              {(id) => <Input id={id} value={form.username} onChange={(e) => set('username', e.target.value)} autoComplete="off" spellCheck={false} autoCapitalize="none" />}
            </Field>
            <Field label={t('common.password')} hint={passwordSet ? t('settings.mailOut.passwordKeepHint') : undefined}>
              {(id) => (
                <Input
                  id={id}
                  type="password"
                  value={form.password ?? ''}
                  onChange={(e) => set('password', e.target.value)}
                  autoComplete="new-password"
                  placeholder={passwordSet ? '••••••••' : ''}
                />
              )}
            </Field>
          </div>
          <Field label={t('settings.mailOut.from')} hint={t('settings.mailOut.fromHint')}>
            {(id) => <Input id={id} type="email" value={form.from} onChange={(e) => set('from', e.target.value)} placeholder="aidocuments@example.com" />}
          </Field>
          <div className="grid gap-4 sm:grid-cols-[1fr_10rem]">
            <Field label={t('settings.mailOut.appUrl')} hint={t('settings.mailOut.appUrlHint')}>
              {(id) => <Input id={id} type="url" value={form.appUrl} onChange={(e) => set('appUrl', e.target.value)} placeholder="https://docs.example.com" spellCheck={false} />}
            </Field>
            <Field label={t('settings.mailOut.digestHour')}>
              {(id) => (
                <Select id={id} value={form.digestHour} onChange={(e) => set('digestHour', Number(e.target.value))}>
                  {Array.from({ length: 24 }, (_, hour) => (
                    <option key={hour} value={hour}>
                      {t('settings.mailOut.fromHour', { hour: `${String(hour).padStart(2, '0')}:00` })}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
          </div>

          {test && <Alert tone={test.ok ? 'ok' : 'danger'}>{test.message}</Alert>}
          <div className="flex flex-wrap items-center gap-2 border-t border-line pt-4">
            <Button onClick={() => runTest.mutate()} loading={runTest.isPending} disabled={!user.email} title={user.email ? undefined : t('settings.mailOut.testNeedsAddress')}>
              {t('settings.mailOut.test', { address: user.email ?? '…' })}
            </Button>
            <Button type="submit" variant="primary" loading={save.isPending} className="ml-auto">
              {t('common.save')}
            </Button>
          </div>
          {!user.email && <p className="text-xs text-muted">{t('settings.mailOut.testNeedsAddress')}</p>}
          <p className="text-xs text-muted">{t('settings.mailOut.testSavedHint')}</p>
        </form>
      </Card>
    </section>
  )
}
