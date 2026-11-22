import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, Link2, Share2, Trash2 } from 'lucide-react'
import { type FormEvent, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { api, type Doc, type DocShare, errorMessage, shareLink } from '../lib/api'
import { cx, formatDate, formatDateTime, formatRelative } from '../lib/format'
import { currentLanguage } from '../lib/i18n'
import { Alert, Badge, Button, Card, ConfirmDialog, Field, IconButton, Input, Modal, Segmented, Select } from './ui'

const VALIDITY_DAYS = [7, 30, 90, 365] as const

/** „7 Tage“, „1 Jahr“ … in der Sprache der Oberfläche. */
function validityLabel(days: number) {
  const [value, unit] = days === 365 ? [1, 'year'] : [days, 'day']
  return new Intl.NumberFormat(currentLanguage(), { style: 'unit', unit, unitDisplay: 'long' }).format(value)
}

/** Kopiert einen Text – auch ohne HTTPS (dort fehlt die Zwischenablage-Schnittstelle des Browsers). */
async function copyText(text: string, fallbackInput?: HTMLInputElement | null) {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    if (!fallbackInput) return false
    fallbackInput.select()
    return document.execCommand('copy')
  }
}

/** Neuen Link erstellen: für wen, ob die Person bearbeiten darf, wie lange er gilt – danach den Link zeigen. */
export function ShareDialog({ doc, open, onClose }: { doc: Doc; open: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [recipient, setRecipient] = useState('')
  const [permission, setPermission] = useState<'view' | 'edit'>('view')
  const [validity, setValidity] = useState('30')
  const [created, setCreated] = useState<DocShare | null>(null)
  const linkInput = useRef<HTMLInputElement>(null)

  const create = useMutation({
    mutationFn: () =>
      api.createShare(doc.id, {
        recipient: recipient.trim(),
        canEdit: permission === 'edit',
        expiresInDays: validity === 'never' ? null : Number(validity),
      }),
    onSuccess: async (share) => {
      setCreated(share)
      await queryClient.invalidateQueries({ queryKey: ['shares', doc.id] })
    },
  })

  function close() {
    setRecipient('')
    setPermission('view')
    setValidity('30')
    setCreated(null)
    create.reset()
    onClose()
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    create.mutate()
  }

  async function copy() {
    if (!created) return
    if (await copyText(shareLink(created.token), linkInput.current)) toast.success(t('share.copied'))
  }

  return (
    <Modal open={open} onClose={close} title={t('share.title')} description={created ? undefined : t('share.intro')} size="md">
      {created ? (
        <div className="flex flex-col gap-4">
          <Alert tone="brand">{t('share.linkHint', { name: created.recipient })}</Alert>
          <Field label={t('share.link')}>
            {(id) => (
              <div className="flex gap-2">
                <Input id={id} ref={linkInput} readOnly value={shareLink(created.token)} className="font-mono text-[13px]" onFocus={(e) => e.target.select()} />
                <Button variant="primary" icon={<Copy className="size-4" aria-hidden />} onClick={copy}>
                  {t('share.copy')}
                </Button>
              </div>
            )}
          </Field>
          <div className="flex justify-end">
            <Button onClick={close}>{t('common.close')}</Button>
          </div>
        </div>
      ) : (
        <form onSubmit={submit} className="flex flex-col gap-4">
          {create.isError && <Alert tone="danger">{errorMessage(create.error)}</Alert>}
          <Field label={t('share.recipient')} hint={t('share.recipientHint')}>
            {(id) => <Input id={id} value={recipient} onChange={(e) => setRecipient(e.target.value)} maxLength={100} autoFocus required />}
          </Field>
          <div className="flex flex-col gap-1.5">
            <span className="text-sm font-medium text-ink">{t('share.permission')}</span>
            <Segmented
              label={t('share.permission')}
              value={permission}
              onChange={setPermission}
              options={[
                { value: 'view', label: t('share.viewOnly') },
                { value: 'edit', label: t('share.canEdit') },
              ]}
            />
            <p className="text-xs leading-relaxed text-muted">{permission === 'edit' ? t('share.editHint') : t('share.viewHint')}</p>
          </div>
          <Field label={t('share.validity')}>
            {(id) => (
              <Select id={id} value={validity} onChange={(e) => setValidity(e.target.value)}>
                {VALIDITY_DAYS.map((days) => (
                  <option key={days} value={String(days)}>
                    {validityLabel(days)}
                  </option>
                ))}
                <option value="never">{t('share.unlimited')}</option>
              </Select>
            )}
          </Field>
          <div className="mt-1 flex justify-end gap-2">
            <Button onClick={close}>{t('common.cancel')}</Button>
            <Button type="submit" variant="primary" icon={<Link2 className="size-4" aria-hidden />} loading={create.isPending}>
              {t('share.create')}
            </Button>
          </div>
        </form>
      )}
    </Modal>
  )
}

/** Schaltfläche für die Dokumentansicht. */
export function ShareButton({ doc, disabled }: { doc: Doc; disabled?: boolean }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button icon={<Share2 className="size-4" aria-hidden />} onClick={() => setOpen(true)} disabled={disabled}>
        {t('share.button')}
      </Button>
      <ShareDialog doc={doc} open={open} onClose={() => setOpen(false)} />
    </>
  )
}

/** Mit wem das Dokument geteilt ist – Anzahl und Personen, mit Link kopieren und entziehen. Nichts, wenn mit niemandem. */
export function SharesCard({ doc }: { doc: Doc }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const shares = useQuery({ queryKey: ['shares', doc.id], queryFn: () => api.shares(doc.id) })
  const [revoking, setRevoking] = useState<DocShare | null>(null)

  const revoke = useMutation({
    mutationFn: (share: DocShare) => api.revokeShare(doc.id, share.id),
    onSuccess: async () => {
      setRevoking(null)
      await queryClient.invalidateQueries({ queryKey: ['shares', doc.id] })
      toast.success(t('share.revoked'))
    },
    onError: (e) => {
      setRevoking(null)
      toast.error(errorMessage(e))
    },
  })

  if (!shares.data || shares.data.length === 0) return null
  const active = shares.data.filter((share) => !share.expired).length

  return (
    <Card className="p-4">
      <div className="mb-3 flex items-center gap-2">
        <Share2 className="size-4 text-brand" aria-hidden />
        <h2 className="text-sm font-semibold">{t('share.listTitle')}</h2>
        <Badge tone="brand">{active}</Badge>
      </div>
      <ul className="divide-y divide-line">
        {shares.data.map((share) => (
          <li key={share.id} className={cx('flex items-start gap-2 py-2.5 first:pt-0 last:pb-0', share.expired && 'opacity-60')}>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                <span className="text-sm font-medium [overflow-wrap:anywhere]">{share.recipient}</span>
                <Badge tone={share.canEdit ? 'brand' : 'neutral'}>{share.canEdit ? t('share.canEdit') : t('share.viewOnly')}</Badge>
                {share.expired && <Badge tone="warn">{t('share.expired')}</Badge>}
              </div>
              <p className="mt-0.5 text-xs text-muted">
                {[
                  share.expired ? null : share.expiresAt ? t('share.validUntil', { date: formatDate(share.expiresAt) }) : t('share.noExpiry'),
                  share.lastAccessAt
                    ? t('share.lastOpened', { when: formatRelative(share.lastAccessAt), views: share.accessCount })
                    : t('share.neverOpened'),
                ]
                  .filter(Boolean)
                  .join(' · ')}
              </p>
              <p className="text-xs text-subtle" title={formatDateTime(share.createdAt)}>
                {[formatDate(share.createdAt), share.createdBy && t('history.by', { user: share.createdBy })].filter(Boolean).join(' · ')}
              </p>
            </div>
            {!share.expired && (
              <IconButton
                label={t('share.copy')}
                onClick={async () => {
                  if (await copyText(shareLink(share.token))) toast.success(t('share.copied'))
                  else window.prompt(t('share.copy'), shareLink(share.token))
                }}
              >
                <Copy className="size-4" />
              </IconButton>
            )}
            <IconButton label={t('share.revoke')} onClick={() => setRevoking(share)}>
              <Trash2 className="size-4" />
            </IconButton>
          </li>
        ))}
      </ul>
      <ConfirmDialog
        open={revoking !== null}
        onClose={() => setRevoking(null)}
        onConfirm={() => revoking && revoke.mutate(revoking)}
        loading={revoke.isPending}
        title={t('share.revokeTitle', { name: revoking?.recipient ?? '' })}
        confirmLabel={t('share.revoke')}
      >
        {t('share.revokeText')}
      </ConfirmDialog>
    </Card>
  )
}
