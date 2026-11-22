import { Fingerprint, ShieldCheck } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { type FormEvent, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, ApiError, errorMessage, setConfirmHandler } from '../lib/api'
import { getPasskey, isCancelled, passkeysSupported } from '../lib/passkey'
import { Alert, Button, Field, Input, Modal } from './ui'

/**
 * Fragt den Code aus der Authenticator-App ab, wenn der Server für eine Änderung (Konten, Postfächer, Einstellungen)
 * eine frische Bestätigung verlangt. Die Anfrage, die das auslöste, wird danach wiederholt (siehe lib/api.ts).
 */
export function ConfirmCodeHost() {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const pending = useRef<Array<(ok: boolean) => void>>([])
  // den Weg per Passkey nur anbieten, wenn das Konto einen hat
  const passkeys = useQuery({ queryKey: ['passkeys'], queryFn: api.passkeys, enabled: open && passkeysSupported() })

  useEffect(() => {
    setConfirmHandler(
      () =>
        new Promise<boolean>((resolve) => {
          pending.current.push(resolve)
          setOpen(true)
        }),
    )
    return () => setConfirmHandler(null)
  }, [])

  function finish(ok: boolean) {
    const waiting = pending.current
    pending.current = []
    setOpen(false)
    setCode('')
    setError(null)
    setBusy(false)
    waiting.forEach((resolve) => resolve(ok))
  }

  /** Statt des Codes: mit einem eigenen Passkey bestätigen. */
  async function confirmWithPasskey() {
    setBusy(true)
    setError(null)
    try {
      const credential = await getPasskey(await api.passkeyLoginOptions())
      if (credential) {
        await api.passkeyConfirm(credential)
        finish(true)
        return
      }
    } catch (e) {
      if (!isCancelled(e)) setError(errorMessage(e))
    }
    setBusy(false)
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.confirmCode(code)
      finish(true)
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        // nach fünf falschen Codes beendet der Server die Sitzung – zurück zur Anmeldung
        finish(false)
        window.dispatchEvent(new Event('auth:expired'))
        return
      }
      setError(errorMessage(e))
      setBusy(false)
    }
  }

  return (
    <Modal open={open} onClose={() => finish(false)} title={t('confirmCode.title')} size="sm">
      <form onSubmit={submit} className="flex flex-col gap-4">
        {error && <Alert tone="danger">{error}</Alert>}
        <p className="text-sm leading-relaxed text-muted">{t('confirmCode.text')}</p>
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
              autoFocus
              required
            />
          )}
        </Field>
        <div className="mt-1 flex flex-wrap justify-end gap-2">
          {passkeysSupported() && (passkeys.data?.length ?? 0) > 0 && (
            <Button className="mr-auto" icon={<Fingerprint className="size-4" aria-hidden />} disabled={busy} onClick={() => void confirmWithPasskey()}>
              {t('confirmCode.passkey')}
            </Button>
          )}
          <Button onClick={() => finish(false)}>{t('common.cancel')}</Button>
          <Button type="submit" variant="primary" icon={<ShieldCheck className="size-4" aria-hidden />} loading={busy}>
            {t('confirmCode.submit')}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
