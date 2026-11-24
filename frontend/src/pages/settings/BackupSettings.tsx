import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarClock, CheckCircle2, DatabaseBackup, Download, LockKeyhole, Play, Upload, XCircle } from 'lucide-react'
import { type FormEvent, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { AreaSelect } from '../../components/AreaSelect'
import { Alert, Button, Card, Field, Input, Select, Spinner, Switch } from '../../components/ui'
import { api, type ArchiveImportResult, type AutoBackupStatus, BACKUP_PASSWORD_MIN, type BackupStatus, errorMessage } from '../../lib/api'
import { cx, formatBytes, formatDateTime, formatHour, formatRelative } from '../../lib/format'
import { SectionHeader } from './SectionHeader'

export function BackupSettings() {
  const { t } = useTranslation()
  return (
    <section className="flex flex-col gap-6">
      <SectionHeader title={t('settings.backup.title')}>{t('settings.backup.intro')}</SectionHeader>
      <AutoBackupCard />
      <BackupCard />
      <ImportCard />
    </section>
  )
}

type Encryption = 'configured' | 'own' | 'none'

function BackupCard() {
  const { t } = useTranslation()
  const status = useQuery({ queryKey: ['backup'], queryFn: api.backupStatus })

  return (
    <Card className="p-5">
      <div className="mb-3 flex items-center gap-2">
        <DatabaseBackup className="size-5 text-brand" aria-hidden />
        <h3 className="font-semibold">{t('settings.backup.backupTitle')}</h3>
      </div>
      {status.isPending ? (
        <Spinner />
      ) : status.isError ? (
        <Alert tone="danger">{errorMessage(status.error)}</Alert>
      ) : !status.data.available ? (
        <Alert tone="warn">{t('settings.backup.externalDatabase')}</Alert>
      ) : (
        <BackupForm status={status.data} />
      )}
    </Card>
  )
}

function BackupForm({ status }: { status: BackupStatus }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  // Ist ein Sicherungspasswort in der .env hinterlegt, wird damit verschlüsselt – sonst wie bisher ohne
  const [encryption, setEncryption] = useState<Encryption>(status.passwordConfigured ? 'configured' : 'none')
  const [password, setPassword] = useState('')
  const [repeat, setRepeat] = useState('')
  const mismatch = encryption === 'own' && repeat !== '' && password !== repeat

  async function download(event: FormEvent) {
    event.preventDefault()
    if (encryption === 'own' && password !== repeat) return
    setBusy(true)
    try {
      const { token } = await api.prepareBackup(
        encryption === 'configured' ? { useConfigured: true } : encryption === 'own' ? { password } : undefined,
      )
      // ein gewöhnlicher Download – der Browser zeigt den Fortschritt, die Datei wird nicht erst im Speicher gebaut
      const link = document.createElement('a')
      link.href = api.backupUrl(token)
      link.rel = 'noopener'
      document.body.appendChild(link)
      link.click()
      link.remove()
      toast.success(t('settings.backup.started'))
      // das Passwort nicht länger als nötig im Formular stehen lassen
      setPassword('')
      setRepeat('')
      window.setTimeout(() => void queryClient.invalidateQueries({ queryKey: ['backup'] }), 5000)
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={(event) => void download(event)} className="flex flex-col gap-4">
      <p className="text-sm text-muted">{t('settings.backup.backupText')}</p>
      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted">{t('settings.backup.size')}</dt>
        <dd className="font-medium">{t('settings.backup.sizeValue', { size: formatBytes(status.dataBytes), count: status.files })}</dd>
        <dt className="text-muted">{t('settings.backup.last')}</dt>
        <dd className="font-medium" title={status.lastBackupAt ? new Date(status.lastBackupAt).toLocaleString() : undefined}>
          {status.lastBackupAt ? formatRelative(status.lastBackupAt) : t('settings.backup.never')}
        </dd>
      </dl>
      <Field label={t('settings.backup.encryption.label')} className="max-w-md">
        {(id) => (
          <Select id={id} value={encryption} onChange={(e) => setEncryption(e.target.value as Encryption)}>
            {status.passwordConfigured && <option value="configured">{t('settings.backup.encryption.configured')}</option>}
            <option value="own">{t('settings.backup.encryption.own')}</option>
            <option value="none">{t('settings.backup.encryption.none')}</option>
          </Select>
        )}
      </Field>
      {encryption === 'own' && (
        <div className="flex flex-wrap gap-4">
          <Field label={t('settings.backup.encryption.password')} hint={t('settings.backup.encryption.passwordHint', { min: BACKUP_PASSWORD_MIN })} className="w-full sm:w-64">
            {(id) => (
              <Input
                id={id}
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                minLength={BACKUP_PASSWORD_MIN}
                maxLength={128}
                autoComplete="new-password"
                required
              />
            )}
          </Field>
          <Field label={t('settings.backup.encryption.repeat')} error={mismatch ? t('settings.backup.encryption.mismatch') : undefined} className="w-full sm:w-64">
            {(id) => (
              <Input id={id} type="password" value={repeat} onChange={(e) => setRepeat(e.target.value)} maxLength={128} autoComplete="new-password" required />
            )}
          </Field>
        </div>
      )}
      {encryption === 'none' ? (
        <Alert tone="warn">{t('settings.backup.keyWarning')}</Alert>
      ) : (
        <Alert tone="ok">
          {t(encryption === 'configured' ? 'settings.backup.encryption.configuredNote' : 'settings.backup.encryption.ownNote')}{' '}
          {t('settings.backup.encryption.openWith')}
        </Alert>
      )}
      <p className="text-sm text-muted">{t('settings.backup.restore')}</p>
      <div>
        <Button type="submit" variant="primary" icon={<Download className="size-4" aria-hidden />} loading={busy} disabled={mismatch}>
          {t('settings.backup.download')}
        </Button>
      </div>
    </form>
  )
}

const HOURS = Array.from({ length: 24 }, (_, hour) => hour)

function AutoBackupCard() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const status = useQuery({
    queryKey: ['backup', 'auto'],
    queryFn: api.autoBackup,
    refetchInterval: (query) => (query.state.data?.running ? 2000 : 30_000),
  })
  const data = status.data

  if (status.isPending) {
    return (
      <Card className="p-5">
        <Spinner />
      </Card>
    )
  }
  return (
    <Card className="p-5">
      <div className="mb-3 flex items-center gap-2">
        <CalendarClock className="size-5 text-brand" aria-hidden />
        <h3 className="font-semibold">{t('settings.backup.auto.title')}</h3>
      </div>
      {status.isError ? (
        <Alert tone="danger">{errorMessage(status.error)}</Alert>
      ) : !data?.available ? (
        <Alert tone="warn">{t('settings.backup.auto.externalDatabase')}</Alert>
      ) : (
        <AutoBackupForm
          data={data}
          onSaved={(next) => {
            queryClient.setQueryData(['backup', 'auto'], next)
            void queryClient.invalidateQueries({ queryKey: ['system'] })
          }}
        />
      )}
    </Card>
  )
}

function AutoBackupForm({ data, onSaved }: { data: AutoBackupStatus; onSaved: (next: AutoBackupStatus) => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [enabled, setEnabled] = useState(data.enabled)
  const [hour, setHour] = useState(data.hour)
  const [keep, setKeep] = useState(String(data.keep))
  const [directory, setDirectory] = useState(data.directory === data.defaultDirectory ? '' : data.directory)
  const [warnAfterDays, setWarnAfterDays] = useState(String(data.warnAfterDays))
  const wasRunning = useRef(data.running)

  // Eine Sicherung ist fertig: Liste, „zuletzt gesichert“ und den Hinweis in der Dokumentliste auffrischen
  useEffect(() => {
    if (wasRunning.current && !data.running) {
      void Promise.all([['backup'], ['system']].map((queryKey) => queryClient.invalidateQueries({ queryKey })))
      if (data.lastRunOk) toast.success(t('settings.backup.auto.done'))
      else if (data.lastRunError) toast.error(data.lastRunError)
    }
    wasRunning.current = data.running
  }, [data.running, data.lastRunAt, data.lastRunOk, data.lastRunError, queryClient, t])

  const savedDirectory = data.directory === data.defaultDirectory ? '' : data.directory
  const dirty =
    enabled !== data.enabled ||
    hour !== data.hour ||
    Number(keep) !== data.keep ||
    directory.trim() !== savedDirectory ||
    Number(warnAfterDays) !== data.warnAfterDays

  const save = useMutation({
    mutationFn: () =>
      api.updateAutoBackup({ enabled, hour, keep: Number(keep), directory: directory.trim(), warnAfterDays: Number(warnAfterDays) }),
    onSuccess: (next) => {
      onSaved(next)
      toast.success(t('settings.backup.auto.saved'))
    },
  })
  const run = useMutation({
    mutationFn: api.runAutoBackup,
    onSuccess: () => {
      toast.success(t('settings.backup.auto.runStarted'))
      wasRunning.current = true
      void queryClient.invalidateQueries({ queryKey: ['backup', 'auto'] })
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate()
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <p className="text-sm text-muted">{t('settings.backup.auto.text')}</p>
      <Switch checked={enabled} onChange={setEnabled} label={t('settings.backup.auto.enable')} />
      <div className="flex flex-wrap gap-4">
        <Field label={t('settings.backup.auto.hour')} className="w-36">
          {(id) => (
            <Select id={id} value={hour} onChange={(e) => setHour(Number(e.target.value))}>
              {HOURS.map((value) => (
                <option key={value} value={value}>
                  {formatHour(value)}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={t('settings.backup.auto.keep')} hint={t('settings.backup.auto.keepHint')} className="w-44">
          {(id) => <Input id={id} type="number" min={1} max={90} value={keep} onChange={(e) => setKeep(e.target.value)} required />}
        </Field>
      </div>
      <Field label={t('settings.backup.auto.directory')} hint={t('settings.backup.auto.directoryHint')}>
        {(id) => (
          <Input
            id={id}
            value={directory}
            onChange={(e) => setDirectory(e.target.value)}
            placeholder={data.defaultDirectory}
            spellCheck={false}
            autoComplete="off"
          />
        )}
      </Field>
      <Field label={t('settings.backup.auto.warnAfterDays')} hint={t('settings.backup.auto.warnAfterDaysHint')} className="w-44">
        {(id) => (
          <Input id={id} type="number" min={0} max={365} value={warnAfterDays} onChange={(e) => setWarnAfterDays(e.target.value)} required />
        )}
      </Field>
      {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}

      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted">{t('settings.backup.auto.lastRun')}</dt>
        <dd className="font-medium">
          {data.running ? (
            <span className="inline-flex items-center gap-1.5">
              <Spinner className="size-3.5" /> {t('settings.backup.auto.running')}
            </span>
          ) : data.lastRunAt ? (
            <span className="inline-flex items-center gap-1.5" title={formatDateTime(data.lastRunAt)}>
              {data.lastRunOk ? (
                <CheckCircle2 className="size-4 text-brand" aria-hidden />
              ) : (
                <XCircle className="size-4 text-danger" aria-hidden />
              )}
              {data.lastRunOk ? t('settings.backup.auto.ok', { when: formatRelative(data.lastRunAt) }) : t('settings.backup.auto.failed', { when: formatRelative(data.lastRunAt) })}
            </span>
          ) : (
            t('settings.backup.never')
          )}
        </dd>
        {data.nextRunAt && (
          <>
            <dt className="text-muted">{t('settings.backup.auto.nextRun')}</dt>
            <dd className="font-medium">{formatDateTime(data.nextRunAt)}</dd>
          </>
        )}
      </dl>
      {!data.running && data.lastRunOk === false && data.lastRunError && <Alert tone="danger">{data.lastRunError}</Alert>}
      <p className="inline-flex items-start gap-2 text-sm text-muted">
        <LockKeyhole className={cx('mt-0.5 size-4 shrink-0', data.encrypted ? 'text-ok' : 'text-muted')} aria-hidden />
        <span>{data.encrypted ? t('settings.backup.auto.encrypted') : t('settings.backup.auto.notEncrypted')}</span>
      </p>

      {data.backups.length > 0 && (
        <div>
          <p className="mb-1.5 text-sm font-medium">{t('settings.backup.auto.stored', { count: data.backups.length, directory: data.directory })}</p>
          <ul className="divide-y divide-line rounded-lg border border-line text-[13px]">
            {data.backups.map((backup) => (
              <li key={backup.name} className="flex flex-wrap items-baseline justify-between gap-x-3 px-3 py-1.5">
                <span className="font-mono [overflow-wrap:anywhere]">{backup.name}</span>
                <span className="text-muted">
                  {formatDateTime(backup.createdAt)} · {formatBytes(backup.bytes)}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        <Button type="submit" variant="primary" disabled={!dirty} loading={save.isPending}>
          {t('common.save')}
        </Button>
        <Button
          icon={<Play className="size-4" aria-hidden />}
          disabled={dirty || data.running}
          loading={run.isPending}
          onClick={() => run.mutate()}
          title={dirty ? t('settings.backup.auto.saveFirst') : undefined}
        >
          {t('settings.backup.auto.runNow')}
        </Button>
      </div>
    </form>
  )
}

function ImportCard() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [file, setFile] = useState<File | null>(null)
  const [area, setArea] = useState('general')
  const [matchAreas, setMatchAreas] = useState(true)
  const [result, setResult] = useState<ArchiveImportResult | null>(null)

  const run = useMutation({
    mutationFn: (archive: File) => api.importArchive(archive, area, matchAreas),
    onSuccess: async (data) => {
      setResult(data)
      setFile(null)
      await Promise.all([['documents'], ['facets'], ['system'], ['backup']].map((queryKey) => queryClient.invalidateQueries({ queryKey })))
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (file) {
      setResult(null)
      run.mutate(file)
    }
  }

  return (
    <Card className="p-5">
      <div className="mb-3 flex items-center gap-2">
        <Upload className="size-5 text-brand" aria-hidden />
        <h3 className="font-semibold">{t('settings.backup.importTitle')}</h3>
      </div>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <p className="text-sm text-muted">{t('settings.backup.importText')}</p>
        <Field label={t('settings.backup.file')}>
          {(id) => (
            <input
              id={id}
              type="file"
              accept=".zip,application/zip"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              className="block w-full text-sm file:mr-3 file:rounded-lg file:border-0 file:bg-raised file:px-3 file:py-2 file:text-sm file:font-medium file:text-ink hover:file:bg-line"
            />
          )}
        </Field>
        <AreaSelect value={area} onChange={setArea} hint={t('settings.backup.areaHint')} />
        <Switch checked={matchAreas} onChange={setMatchAreas} label={t('settings.backup.matchAreas')} description={t('settings.backup.matchAreasHint')} />
        {run.isError && <Alert tone="danger">{errorMessage(run.error)}</Alert>}
        {result && (
          <Alert tone={result.failed > 0 ? 'warn' : 'brand'}>
            <p>{t('settings.backup.result', { imported: result.imported, duplicates: result.duplicates, failed: result.failed })}</p>
            {result.problems.length > 0 && (
              <ul className="mt-2 list-disc pl-5 text-[13px]">
                {result.problems.map((problem) => (
                  <li key={problem} className="[overflow-wrap:anywhere]">
                    {problem}
                  </li>
                ))}
              </ul>
            )}
          </Alert>
        )}
        <div>
          <Button type="submit" variant="primary" disabled={!file} loading={run.isPending}>
            {run.isPending ? t('settings.backup.importing') : t('settings.backup.import')}
          </Button>
        </div>
      </form>
    </Card>
  )
}
