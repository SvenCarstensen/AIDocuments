import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fingerprint, Plus, Trash2 } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { api, errorMessage, type PasskeyInfo } from '../lib/api'
import { useAuth } from '../lib/auth'
import { formatRelative } from '../lib/format'
import { addressIsIp, createPasskey, isCancelled, passkeysSupported } from '../lib/passkey'
import { Alert, Badge, Button, ConfirmDialog, Field, IconButton, Input, Modal, Spinner } from './ui'

/** Eigene Passkeys: anlegen (mit Passwort bzw. frischem Code), ansehen, entfernen. */
export function PasskeysDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const passkeys = useQuery({ queryKey: ['passkeys'], queryFn: api.passkeys, enabled: open })
  const [adding, setAdding] = useState(false)
  const [removing, setRemoving] = useState<PasskeyInfo | null>(null)
  const supported = passkeysSupported()

  const remove = useMutation({
    mutationFn: (id: string) => api.deletePasskey(id),
    onSuccess: async () => {
      setRemoving(null)
      toast.success(t('passkeys.removed'))
      await queryClient.invalidateQueries({ queryKey: ['passkeys'] })
    },
    onError: (e) => {
      setRemoving(null)
      toast.error(errorMessage(e))
    },
  })

  return (
    <Modal open={open} onClose={onClose} title={t('passkeys.title')} description={t('passkeys.intro')}>
      <div className="flex flex-col gap-4">
        {!supported && <Alert tone="warn">{addressIsIp() ? t('passkeys.unsupportedIp') : t('passkeys.unsupported')}</Alert>}
        {passkeys.isPending ? (
          <div className="flex justify-center py-6">
            <Spinner />
          </div>
        ) : passkeys.isError ? (
          <Alert tone="danger">{errorMessage(passkeys.error)}</Alert>
        ) : passkeys.data.length === 0 ? (
          <p className="text-sm text-muted">{t('passkeys.none')}</p>
        ) : (
          <ul className="divide-y divide-line rounded-xl border border-line">
            {passkeys.data.map((passkey) => (
              <li key={passkey.id} className="flex items-center gap-3 px-3 py-2.5">
                <Fingerprint className="size-5 shrink-0 text-muted" aria-hidden />
                <div className="min-w-0 flex-1">
                  <p className="flex flex-wrap items-center gap-2 text-sm font-medium [overflow-wrap:anywhere]">
                    {passkey.name}
                    {passkey.synced && <Badge tone="neutral">{t('passkeys.synced')}</Badge>}
                  </p>
                  <p className="text-xs text-muted">
                    {passkey.lastUsedAt
                      ? t('passkeys.lastUsed', { time: formatRelative(passkey.lastUsedAt) })
                      : t('passkeys.created', { time: formatRelative(passkey.createdAt) })}
                  </p>
                </div>
                <IconButton label={t('passkeys.remove.label')} onClick={() => setRemoving(passkey)} className="!size-8 hover:!bg-danger-soft hover:!text-danger">
                  <Trash2 className="size-4" />
                </IconButton>
              </li>
            ))}
          </ul>
        )}
        {adding ? (
          <AddPasskey onDone={() => setAdding(false)} />
        ) : (
          <div className="flex justify-end gap-2">
            <Button onClick={onClose}>{t('common.close')}</Button>
            <Button variant="primary" icon={<Plus className="size-4" aria-hidden />} disabled={!supported} onClick={() => setAdding(true)}>
              {t('passkeys.add')}
            </Button>
          </div>
        )}
      </div>
      <ConfirmDialog
        open={removing !== null}
        onClose={() => setRemoving(null)}
        onConfirm={() => removing && remove.mutate(removing.id)}
        loading={remove.isPending}
        title={t('passkeys.remove.title')}
        confirmLabel={t('passkeys.remove.confirm')}
      >
        {t('passkeys.remove.text', { name: removing?.name ?? '' })}
      </ConfirmDialog>
    </Modal>
  )
}

/** Name und – ohne Zwei-Faktor-Anmeldung – das Passwort; dann fragt der Browser nach Fingerabdruck, Gesicht oder PIN. */
function AddPasskey({ onDone }: { onDone: () => void }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [name, setName] = useState(defaultName())
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const options = await api.passkeyRegistrationOptions(user.twoFactorEnabled ? null : password)
      const credential = await createPasskey(options)
      if (credential) {
        await api.registerPasskey(name.trim(), credential)
        await queryClient.invalidateQueries({ queryKey: ['passkeys'] })
        toast.success(t('passkeys.added'))
        onDone()
        return
      }
    } catch (e) {
      if (!isCancelled(e)) setError(errorMessage(e))
    }
    setBusy(false)
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4 rounded-xl border border-line p-4">
      {error && <Alert tone="danger">{error}</Alert>}
      <Field label={t('passkeys.name')} hint={t('passkeys.nameHint')}>
        {(id) => <Input id={id} value={name} onChange={(e) => setName(e.target.value)} maxLength={100} required autoFocus />}
      </Field>
      {!user.twoFactorEnabled && (
        <Field label={t('passkeys.password')} hint={t('passkeys.passwordHint')}>
          {(id) => <Input id={id} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />}
        </Field>
      )}
      <div className="flex justify-end gap-2">
        <Button onClick={onDone}>{t('common.cancel')}</Button>
        <Button type="submit" variant="primary" icon={<Fingerprint className="size-4" aria-hidden />} loading={busy}>
          {t('passkeys.create')}
        </Button>
      </div>
    </form>
  )
}

/** Ein Vorschlag aus dem Gerät – „Mac“, „iPhone“, „Windows“ … */
function defaultName(): string {
  const agent = navigator.userAgent
  if (/iPhone/.test(agent)) return 'iPhone'
  if (/iPad/.test(agent)) return 'iPad'
  if (/Android/.test(agent)) return 'Android'
  if (/Mac OS X/.test(agent)) return 'Mac'
  if (/Windows/.test(agent)) return 'Windows'
  if (/Linux/.test(agent)) return 'Linux'
  return 'Passkey'
}
