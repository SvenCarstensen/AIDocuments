import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, ShieldCheck } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { api, errorMessage, type TwoFactorSetup } from '../lib/api'
import { useAuth } from '../lib/auth'
import { Alert, Badge, Button, Field, Input, Modal, Spinner } from './ui'

/**
 * Zwei-Faktor-Anmeldung für das eigene Konto: einrichten (QR-Code scannen, Code bestätigen, Wiederherstellungscodes
 * sichern) oder abschalten. Beides verlangt das Passwort.
 */
export function TwoFactorDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const { isAdmin } = useAuth()
  const queryClient = useQueryClient()
  const status = useQuery({ queryKey: ['two-factor'], queryFn: api.twoFactorStatus, enabled: open })
  // läuft gerade eine Einrichtung? Dann bleibt sie sichtbar, auch wenn der Status schon „aktiv“ meldet
  const [settingUp, setSettingUp] = useState(false)

  function close() {
    setSettingUp(false)
    onClose()
  }

  let content
  if (settingUp || (status.data && !status.data.enabled)) {
    content = (
      <TwoFactorSetupFlow
        intro={t('twoFactor.intro')}
        onStarted={() => setSettingUp(true)}
        onCancel={close}
        cancelLabel={t('common.cancel')}
        onFinished={async () => {
          await queryClient.invalidateQueries({ queryKey: ['auth'] })
          close()
        }}
      />
    )
  } else if (status.isPending) {
    content = (
      <div className="flex justify-center py-8">
        <Spinner />
      </div>
    )
  } else if (status.isError) {
    content = <Alert tone="danger">{errorMessage(status.error)}</Alert>
  } else if (isAdmin) {
    content = (
      <div className="flex flex-col gap-4">
        <div className="flex items-center gap-2">
          <Badge tone="brand">{t('twoFactor.on')}</Badge>
          <span className="text-xs text-muted">{t('twoFactor.recoveryLeft', { count: status.data.recoveryCodesLeft })}</span>
        </div>
        <p className="text-sm leading-relaxed text-muted">{t('twoFactor.adminCannotDisable')}</p>
        <div className="flex justify-end">
          <Button onClick={close}>{t('common.close')}</Button>
        </div>
      </div>
    )
  } else {
    content = <DisableForm recoveryCodesLeft={status.data.recoveryCodesLeft} onDone={close} onCancel={close} />
  }

  return (
    <Modal open={open} onClose={close} title={t('twoFactor.title')} size="md">
      {content}
    </Modal>
  )
}

/**
 * Einrichten in drei Schritten: Hinweis → QR-Code scannen und Code bestätigen → Wiederherstellungscodes sichern.
 * Auch für die Pflicht-Einrichtung der Administratoren (Anmeldeseite).
 */
export function TwoFactorSetupFlow({
  intro,
  onStarted,
  onCancel,
  cancelLabel,
  onFinished,
}: {
  intro: string
  onStarted?: () => void
  onCancel: () => void
  cancelLabel: string
  onFinished: () => void
}) {
  const [setup, setSetup] = useState<TwoFactorSetup | null>(null)
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null)
  if (recoveryCodes) {
    return <RecoveryCodes codes={recoveryCodes} onDone={onFinished} />
  }
  if (setup) {
    return <EnableForm setup={setup} onEnabled={setRecoveryCodes} onCancel={onCancel} cancelLabel={cancelLabel} />
  }
  return (
    <Intro
      text={intro}
      onStart={(value) => {
        onStarted?.()
        setSetup(value)
      }}
      onCancel={onCancel}
      cancelLabel={cancelLabel}
    />
  )
}

function Intro({
  text,
  onStart,
  onCancel,
  cancelLabel,
}: {
  text: string
  onStart: (setup: TwoFactorSetup) => void
  onCancel: () => void
  cancelLabel: string
}) {
  const { t } = useTranslation()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function start() {
    setBusy(true)
    setError(null)
    try {
      onStart(await api.twoFactorSetup())
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      {error && <Alert tone="danger">{error}</Alert>}
      <div className="flex items-center gap-2">
        <Badge>{t('twoFactor.off')}</Badge>
      </div>
      <p className="text-sm leading-relaxed text-muted">{text}</p>
      <div className="mt-1 flex justify-end gap-2">
        <Button onClick={onCancel}>{cancelLabel}</Button>
        <Button variant="primary" icon={<ShieldCheck className="size-4" aria-hidden />} loading={busy} onClick={start}>
          {t('twoFactor.enable')}
        </Button>
      </div>
    </div>
  )
}

function EnableForm({
  setup,
  onEnabled,
  onCancel,
  cancelLabel,
}: {
  setup: TwoFactorSetup
  onEnabled: (codes: string[]) => void
  onCancel: () => void
  cancelLabel: string
}) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const result = await api.twoFactorEnable(password, code)
      // den Anmeldestatus erst nach den Wiederherstellungscodes neu laden (onFinished) – sonst wechselte die
      // Pflicht-Einrichtung der Administratoren schon vorher zur Anwendung
      await queryClient.invalidateQueries({ queryKey: ['two-factor'] })
      toast.success(t('twoFactor.enabled'))
      onEnabled(result.recoveryCodes)
    } catch (e) {
      setError(errorMessage(e))
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      {error && <Alert tone="danger">{error}</Alert>}
      <p className="text-sm leading-relaxed text-muted">{t('twoFactor.scan')}</p>
      <div className="flex flex-col items-center gap-3 sm:flex-row sm:items-start">
        <img
          src={setup.qrCode}
          alt={t('twoFactor.qrAlt')}
          className="size-44 shrink-0 rounded-lg border border-line bg-white p-1"
        />
        <div className="min-w-0 text-sm">
          <div className="font-medium text-ink">{t('twoFactor.secret')}</div>
          <code className="mt-1 block font-mono text-[13px] break-all text-muted select-all">
            {setup.secret.match(/.{1,4}/g)?.join(' ')}
          </code>
        </div>
      </div>
      <Field label={t('twoFactor.confirmCode')}>
        {(id) => (
          <Input
            id={id}
            value={code}
            onChange={(e) => setCode(e.target.value)}
            autoComplete="one-time-code"
            inputMode="numeric"
            placeholder="123456"
            className="font-mono tracking-wider"
            maxLength={10}
            required
          />
        )}
      </Field>
      <Field label={t('shell.passwordDialog.current')}>
        {(id) => (
          <Input id={id} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        )}
      </Field>
      <div className="mt-1 flex justify-end gap-2">
        <Button onClick={onCancel}>{cancelLabel}</Button>
        <Button type="submit" variant="primary" loading={busy}>
          {t('twoFactor.activate')}
        </Button>
      </div>
    </form>
  )
}

function RecoveryCodes({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const { t } = useTranslation()

  function download() {
    const text = `AIDocuments – ${t('twoFactor.recoveryTitle')}\n\n${codes.join('\n')}\n`
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'aidocuments-recovery-codes.txt'
    link.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="flex flex-col gap-4">
      <Alert tone="brand">{t('twoFactor.recoveryText')}</Alert>
      <ul className="grid grid-cols-2 gap-x-6 gap-y-1.5 rounded-lg border border-line bg-raised px-4 py-3 font-mono text-sm select-all">
        {codes.map((code) => (
          <li key={code}>{code}</li>
        ))}
      </ul>
      <div className="mt-1 flex justify-end gap-2">
        <Button icon={<Download className="size-4" aria-hidden />} onClick={download}>
          {t('twoFactor.download')}
        </Button>
        <Button variant="primary" onClick={onDone}>
          {t('twoFactor.done')}
        </Button>
      </div>
    </div>
  )
}

function DisableForm({
  recoveryCodesLeft,
  onDone,
  onCancel,
}: {
  recoveryCodesLeft: number
  onDone: () => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.twoFactorDisable(password, code)
      await queryClient.invalidateQueries({ queryKey: ['two-factor'] })
      await queryClient.invalidateQueries({ queryKey: ['auth'] })
      toast.success(t('twoFactor.disabled'))
      onDone()
    } catch (e) {
      setError(errorMessage(e))
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      {error && <Alert tone="danger">{error}</Alert>}
      <div className="flex items-center gap-2">
        <Badge tone="brand">{t('twoFactor.on')}</Badge>
        <span className="text-xs text-muted">{t('twoFactor.recoveryLeft', { count: recoveryCodesLeft })}</span>
      </div>
      <p className="text-sm leading-relaxed text-muted">{t('twoFactor.disableHint')}</p>
      <Field label={t('auth.twoFactor.code')} hint={t('auth.twoFactor.codeHint')}>
        {(id) => (
          <Input
            id={id}
            value={code}
            onChange={(e) => setCode(e.target.value)}
            autoComplete="one-time-code"
            autoCapitalize="none"
            spellCheck={false}
            className="font-mono tracking-wider"
            maxLength={50}
            required
          />
        )}
      </Field>
      <Field label={t('shell.passwordDialog.current')}>
        {(id) => (
          <Input id={id} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        )}
      </Field>
      <div className="mt-1 flex justify-end gap-2">
        <Button onClick={onCancel}>{t('common.cancel')}</Button>
        <Button type="submit" variant="danger" loading={busy}>
          {t('twoFactor.disable')}
        </Button>
      </div>
    </form>
  )
}
