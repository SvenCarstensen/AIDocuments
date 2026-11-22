import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, ChevronRight, Clock, Filter, Mail, Pencil, Plus, RefreshCw, Trash2, XCircle } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Alert, Badge, Button, Card, ConfirmDialog, EmptyState, Field, IconButton, Input, Modal, Segmented, Spinner, Switch, Textarea } from '../../components/ui'
import { AreaSelect } from '../../components/AreaSelect'
import { api, errorMessage, type MailAccount, type MailAccountInput, type MailFilter, type MailSecurity, type TestResult } from '../../lib/api'
import { formatDuration, formatRelative } from '../../lib/format'
import { SectionHeader } from './SectionHeader'

export function MailboxSettings() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState<MailAccount | 'new' | null>(null)
  const [deleting, setDeleting] = useState<MailAccount | null>(null)

  const accounts = useQuery({
    queryKey: ['mail-accounts'],
    queryFn: api.mailAccounts,
    // während eines Abrufs eng nachfragen, damit das Ergebnis ohne Neuladen erscheint
    refetchInterval: (query) => (query.state.data?.some((a) => a.lastSyncStatus === 'RUNNING') ? 1500 : 20_000),
  })

  const sync = useMutation({
    mutationFn: (id: string) => api.syncMailAccount(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['mail-accounts'] }),
    onError: (e) => toast.error(errorMessage(e)),
  })
  const remove = useMutation({
    mutationFn: (id: string) => api.deleteMailAccount(id),
    onSuccess: async () => {
      setDeleting(null)
      toast.success(t('settings.mailboxes.removed'))
      await queryClient.invalidateQueries({ queryKey: ['mail-accounts'] })
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  return (
    <section>
      <SectionHeader
        title={t('settings.mailboxes.title')}
        action={
          <Button variant="primary" icon={<Plus className="size-4" aria-hidden />} onClick={() => setEditing('new')}>
            {t('settings.mailboxes.add')}
          </Button>
        }
      >
        {t('settings.mailboxes.intro')}
      </SectionHeader>

      {accounts.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner />
        </div>
      ) : accounts.isError ? (
        <Alert tone="danger">{errorMessage(accounts.error)}</Alert>
      ) : accounts.data.length === 0 ? (
        <Card>
          <EmptyState
            icon={<Mail className="size-7" />}
            title={t('settings.mailboxes.emptyTitle')}
            action={
              <Button variant="primary" icon={<Plus className="size-4" aria-hidden />} onClick={() => setEditing('new')}>
                {t('settings.mailboxes.add')}
              </Button>
            }
          >
            {t('settings.mailboxes.emptyText')}
          </EmptyState>
        </Card>
      ) : (
        <div className="flex flex-col gap-3">
          {accounts.data.map((account) => (
            <Card key={account.id} className="p-4 sm:p-5">
              <div className="flex flex-wrap items-start gap-x-4 gap-y-3">
                <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-brand-soft text-brand-ink">
                  <Mail className="size-5" aria-hidden />
                </div>
                <div className="min-w-0 flex-1 basis-56">
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="truncate font-semibold">{account.name}</h3>
                    {!account.enabled && <Badge>{t('settings.mailboxes.paused')}</Badge>}
                    <Badge tone="neutral">{t('common.documentCount', { count: account.documentCount })}</Badge>
                    {filterTerms(account.filter) > 0 && (
                      <Badge tone="brand">
                        <Filter className="size-3" aria-hidden />
                        {t('settings.mailboxes.filterActive')}
                      </Badge>
                    )}
                  </div>
                  <p className="mt-0.5 truncate text-sm text-muted">
                    {account.username} · {account.host}:{account.port} · {account.folder}
                  </p>
                  <SyncState account={account} />
                  <Schedule account={account} />
                </div>
                <div className="flex items-center gap-1">
                  <Button
                    size="sm"
                    icon={<RefreshCw className="size-3.5" aria-hidden />}
                    loading={account.lastSyncStatus === 'RUNNING' || (sync.isPending && sync.variables === account.id)}
                    onClick={() => sync.mutate(account.id)}
                  >
                    {t('settings.mailboxes.syncNow')}
                  </Button>
                  <IconButton label={t('common.edit')} onClick={() => setEditing(account)}>
                    <Pencil className="size-4" />
                  </IconButton>
                  <IconButton label={t('common.remove')} onClick={() => setDeleting(account)} className="hover:!bg-danger-soft hover:!text-danger">
                    <Trash2 className="size-4" />
                  </IconButton>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      {editing && <MailboxDialog account={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        onConfirm={() => deleting && remove.mutate(deleting.id)}
        loading={remove.isPending}
        title={t('settings.mailboxes.delete.title')}
        confirmLabel={t('common.remove')}
      >
        {t('settings.mailboxes.delete.text', { name: deleting?.name ?? '' })}
      </ConfirmDialog>
    </section>
  )
}

function SyncState({ account }: { account: MailAccount }) {
  const { t } = useTranslation()
  if (account.lastSyncStatus === 'RUNNING') {
    return <p className="mt-2 text-[13px] text-brand-ink">{t('settings.mailboxes.syncing')}</p>
  }
  if (!account.lastSyncAt) {
    return <p className="mt-2 text-[13px] text-muted">{t('settings.mailboxes.neverSynced')}</p>
  }
  const failed = account.lastSyncStatus === 'ERROR'
  return (
    <p className={`mt-2 flex items-start gap-1.5 text-[13px] ${failed ? 'text-danger' : 'text-muted'}`}>
      {failed ? <XCircle className="mt-0.5 size-3.5 shrink-0" aria-hidden /> : <CheckCircle2 className="mt-0.5 size-3.5 shrink-0 text-ok" aria-hidden />}
      <span className="[overflow-wrap:anywhere]">
        {account.lastSyncMessage} <span className="text-subtle">· {formatRelative(account.lastSyncAt)}</span>
      </span>
    </p>
  )
}

/** Intervall und nächster automatischer Abruf; bei pausierten Postfächern nichts. */
function Schedule({ account }: { account: MailAccount }) {
  const { t } = useTranslation()
  if (!account.nextSyncAt || account.lastSyncStatus === 'RUNNING') return null
  const interval = formatInterval(account.effectiveIntervalMinutes)
  const soon = dueSoon(account.nextSyncAt)
  return (
    <p className="mt-1 flex items-center gap-1.5 text-[13px] text-subtle">
      <Clock className="size-3.5 shrink-0" aria-hidden />
      {soon
        ? t('settings.mailboxes.scheduleSoon', { interval })
        : t('settings.mailboxes.schedule', { interval, when: formatRelative(account.nextSyncAt) })}
    </p>
  )
}

/** Der Takt im Backend prüft alle 15 Sekunden – ein gerade fälliger Abruf startet also gleich. */
function dueSoon(at: string) {
  return new Date(at).getTime() <= Date.now() + 15_000
}

function formatInterval(minutes: number) {
  return minutes >= 60 && minutes % 60 === 0 ? formatDuration(minutes / 60, 'hour') : formatDuration(minutes, 'minute')
}

const defaultPorts: Record<MailSecurity, number> = { SSL: 993, STARTTLS: 143, NONE: 143 }

const FILTER_FIELDS = ['subjectIncludes', 'subjectExcludes', 'senderIncludes', 'senderExcludes'] as const
type FilterTexts = Record<keyof MailFilter, string>

function filterTerms(filter: MailFilter | undefined) {
  return filter ? FILTER_FIELDS.reduce((sum, key) => sum + filter[key].length, 0) : 0
}

/** Ein Begriff pro Zeile – im Formular als Text, beim Speichern als Liste. */
function filterTexts(filter: MailFilter | undefined): FilterTexts {
  return Object.fromEntries(FILTER_FIELDS.map((key) => [key, filter?.[key].join('\n') ?? ''])) as FilterTexts
}
function parseFilter(texts: FilterTexts): MailFilter {
  return Object.fromEntries(
    FILTER_FIELDS.map((key) => [key, texts[key].split('\n').map((term) => term.trim()).filter(Boolean)]),
  ) as unknown as MailFilter
}

function MailboxDialog({ account, onClose }: { account: MailAccount | null; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [name, setName] = useState(account?.name ?? '')
  const [host, setHost] = useState(account?.host ?? '')
  const [port, setPort] = useState(String(account?.port ?? 993))
  const [security, setSecurity] = useState<MailSecurity>(account?.security ?? 'SSL')
  const [username, setUsername] = useState(account?.username ?? '')
  const [password, setPassword] = useState('')
  const [folder, setFolder] = useState(account?.folder ?? 'INBOX')
  const [importSince, setImportSince] = useState(account?.importSince ?? '')
  const [enabled, setEnabled] = useState(account?.enabled ?? true)
  const [filter, setFilter] = useState<FilterTexts>(() => filterTexts(account?.filter))
  const [interval, setIntervalMinutes] = useState(account?.intervalMinutes == null ? '' : String(account.intervalMinutes))
  const [areaId, setAreaId] = useState(account?.areaId ?? 'general')
  const [test, setTest] = useState<TestResult | null>(null)
  // für den Hinweis, welches Intervall ohne eigene Angabe gilt
  const importSettings = useQuery({ queryKey: ['settings', 'import'], queryFn: api.importSettings })

  const input = (): MailAccountInput => ({
    name: name.trim() || username.trim() || host.trim(),
    host: host.trim(),
    port: Number(port),
    security,
    username: username.trim(),
    password: password || null,
    folder: folder.trim() || 'INBOX',
    importSince: importSince || null,
    enabled,
    filter: parseFilter(filter),
    intervalMinutes: interval.trim() === '' ? null : Number(interval),
    areaId,
  })

  const save = useMutation({
    mutationFn: () => (account ? api.updateMailAccount(account.id, input()) : api.createMailAccount(input())),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['mail-accounts'] })
      toast.success(account ? t('settings.mailboxes.saved') : t('settings.mailboxes.added'), {
        description: !account && enabled ? t('settings.mailboxes.firstSyncRunning') : undefined,
      })
      onClose()
    },
  })
  const runTest = useMutation({
    mutationFn: () => api.testMailAccount(account?.id ?? null, input()),
    onMutate: () => setTest(null),
    onSuccess: setTest,
    onError: (e) => setTest({ ok: false, message: errorMessage(e) }),
  })

  function changeSecurity(next: MailSecurity) {
    // den Port mitziehen, solange er noch dem Standard der bisherigen Auswahl entspricht
    if (Number(port) === defaultPorts[security]) setPort(String(defaultPorts[next]))
    setSecurity(next)
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate()
  }
  const complete = host.trim() !== '' && username.trim() !== '' && (password !== '' || account !== null)
  // SSL/TLS und STARTTLS sind Fachbegriffe und heißen in jeder Sprache so; nur "Keine" wird übersetzt.
  const securityOptions: Array<{ value: MailSecurity; label: string }> = [
    { value: 'SSL', label: 'SSL/TLS' },
    { value: 'STARTTLS', label: 'STARTTLS' },
    { value: 'NONE', label: t('settings.mailboxes.dialog.securityNone') },
  ]

  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      title={account ? t('settings.mailboxes.dialog.editTitle') : t('settings.mailboxes.add')}
      description={t('settings.mailboxes.dialog.description')}
    >
      <form onSubmit={submit} className="flex flex-col gap-4">
        {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
        <Field label={t('settings.mailboxes.dialog.name')} hint={t('settings.mailboxes.dialog.nameHint')}>
          {(id) => <Input id={id} value={name} onChange={(e) => setName(e.target.value)} maxLength={120} placeholder={t('settings.mailboxes.dialog.namePlaceholder')} autoFocus />}
        </Field>
        <div className="grid gap-4 sm:grid-cols-[minmax(0,1fr)_7rem]">
          <Field label={t('settings.mailboxes.dialog.host')}>
            {(id) => <Input id={id} value={host} onChange={(e) => setHost(e.target.value)} placeholder="imap.example.com" autoCapitalize="none" spellCheck={false} required />}
          </Field>
          <Field label={t('settings.mailboxes.dialog.port')}>
            {(id) => <Input id={id} type="number" min={1} max={65535} value={port} onChange={(e) => setPort(e.target.value)} required />}
          </Field>
        </div>
        <div className="flex flex-col gap-1.5">
          <span className="text-[13px] font-medium">{t('settings.mailboxes.dialog.security')}</span>
          <Segmented label={t('settings.mailboxes.dialog.security')} value={security} onChange={changeSecurity} options={securityOptions} />
          {security === 'NONE' && (
            <p className="text-[13px] text-warn">{t('settings.mailboxes.dialog.securityNoneWarning')}</p>
          )}
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t('common.username')}>
            {(id) => <Input id={id} value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" autoCapitalize="none" spellCheck={false} required />}
          </Field>
          <Field label={t('common.password')} hint={account ? t('settings.mailboxes.dialog.passwordKeepHint') : undefined}>
            {(id) => (
              <Input id={id} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required={!account} placeholder={account ? '••••••••' : ''} />
            )}
          </Field>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t('settings.mailboxes.dialog.folder')} hint={t('settings.mailboxes.dialog.folderHint', { folder: 'INBOX' })}>
            {(id) => <Input id={id} value={folder} onChange={(e) => setFolder(e.target.value)} spellCheck={false} required />}
          </Field>
          <Field label={t('settings.mailboxes.dialog.since')} hint={t('settings.mailboxes.dialog.sinceHint')}>
            {(id) => <Input id={id} type="date" value={importSince} onChange={(e) => setImportSince(e.target.value)} />}
          </Field>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label={t('settings.mailboxes.dialog.interval')}
            hint={t('settings.mailboxes.dialog.intervalHint', {
              interval: importSettings.data ? formatInterval(importSettings.data.intervalMinutes) : '…',
            })}
          >
            {(id) => (
              <Input
                id={id}
                type="number"
                min={1}
                max={1440}
                step={1}
                value={interval}
                onChange={(e) => setIntervalMinutes(e.target.value)}
                placeholder={importSettings.data ? String(importSettings.data.intervalMinutes) : ''}
              />
            )}
          </Field>
          <AreaSelect value={areaId} onChange={setAreaId} hint={t('settings.mailboxes.dialog.areaHint')} />
        </div>
        <MailFilterFields value={filter} onChange={setFilter} />
        <Switch checked={enabled} onChange={setEnabled} label={t('settings.mailboxes.dialog.enabled')} description={t('settings.mailboxes.dialog.enabledHint')} />

        {test && <Alert tone={test.ok ? 'ok' : 'danger'}>{test.message}</Alert>}

        <div className="mt-1 flex flex-wrap items-center gap-2">
          <Button onClick={() => runTest.mutate()} loading={runTest.isPending} disabled={!complete}>
            {t('common.testConnection')}
          </Button>
          <div className="ml-auto flex gap-2">
            <Button onClick={onClose}>{t('common.cancel')}</Button>
            <Button type="submit" variant="primary" loading={save.isPending}>
              {account ? t('common.save') : t('common.add')}
            </Button>
          </div>
        </div>
      </form>
    </Modal>
  )
}

/** Betreff- und Absenderfilter; aufgeklappt, sobald einer gesetzt ist. */
function MailFilterFields({ value, onChange }: { value: FilterTexts; onChange: (next: FilterTexts) => void }) {
  const { t } = useTranslation()
  const active = filterTerms(parseFilter(value))
  // nur anfangs nach dem Inhalt richten – sonst klappt der Bereich zu, sobald man den letzten Begriff löscht
  const [open, setOpen] = useState(active > 0)
  const field = (key: keyof MailFilter, hint?: string) => (
    <Field label={t(`settings.mailboxes.dialog.filter.${key}`)} hint={hint}>
      {(id) => (
        <Textarea
          id={id}
          rows={2}
          value={value[key]}
          onChange={(e) => onChange({ ...value, [key]: e.target.value })}
          spellCheck={false}
          autoCapitalize="none"
          className="min-h-16 resize-y"
        />
      )}
    </Field>
  )
  return (
    <details open={open} onToggle={(e) => setOpen(e.currentTarget.open)} className="group rounded-xl border border-line">
      <summary className="flex cursor-pointer list-none items-center gap-2 px-4 py-3 text-[13px] font-medium select-none [&::-webkit-details-marker]:hidden">
        <ChevronRight className="size-4 text-muted transition-transform group-open:rotate-90" aria-hidden />
        <Filter className="size-4 text-muted" aria-hidden />
        {t('settings.mailboxes.dialog.filter.title')}
        {active > 0 && <Badge tone="brand">{active}</Badge>}
      </summary>
      <div className="flex flex-col gap-4 border-t border-line px-4 py-4">
        <p className="text-[13px] leading-relaxed text-muted">{t('settings.mailboxes.dialog.filter.intro')}</p>
        <div className="grid gap-4 sm:grid-cols-2">
          {field('subjectIncludes')}
          {field('subjectExcludes')}
          {field('senderIncludes', t('settings.mailboxes.dialog.filter.senderHint'))}
          {field('senderExcludes')}
        </div>
        <p className="text-xs leading-relaxed text-subtle">{t('settings.mailboxes.dialog.filter.newOnly')}</p>
      </div>
    </details>
  )
}
