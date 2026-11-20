import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, Clock, FolderSearch, XCircle } from 'lucide-react'
import { type FormEvent, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Alert, Button, Card, Field, Input, Segmented, Spinner, Switch } from '../../components/ui'
import { api, errorMessage, type FolderMode } from '../../lib/api'
import { cx, formatRelative } from '../../lib/format'
import { SectionHeader } from './SectionHeader'

const presets = [1, 5, 15, 30, 60]

export function ImportSettingsSection() {
  const queryClient = useQueryClient()
  const settings = useQuery({
    queryKey: ['settings', 'import'],
    queryFn: api.importSettings,
    refetchInterval: (query) => (query.state.data?.folderStatus.running ? 1500 : 15_000),
  })

  const [interval, setIntervalMinutes] = useState('5')
  const [folderEnabled, setFolderEnabled] = useState(false)
  const [folderPath, setFolderPath] = useState('')
  const [folderMode, setFolderMode] = useState<FolderMode>('KEEP')
  const [loaded, setLoaded] = useState(false)

  // Formular einmalig mit den gespeicherten Werten füllen; spätere Statusabfragen überschreiben keine Eingaben.
  useEffect(() => {
    if (settings.data && !loaded) {
      setIntervalMinutes(String(settings.data.intervalMinutes))
      setFolderEnabled(settings.data.folderEnabled)
      setFolderPath(settings.data.folderPath)
      setFolderMode(settings.data.folderMode)
      setLoaded(true)
    }
  }, [settings.data, loaded])

  const save = useMutation({
    mutationFn: () =>
      api.updateImportSettings({ intervalMinutes: Number(interval), folderEnabled, folderPath: folderPath.trim(), folderMode }),
    onSuccess: (data) => {
      queryClient.setQueryData(['settings', 'import'], data)
      toast.success('Einstellungen gespeichert')
    },
  })
  const scan = useMutation({
    mutationFn: api.scanFolder,
    onSuccess: () => {
      toast.success('Der Eingangsordner wird geprüft.')
      setTimeout(() => queryClient.invalidateQueries({ queryKey: ['settings', 'import'] }), 600)
      setTimeout(() => queryClient.invalidateQueries({ queryKey: ['documents'] }), 2500)
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  if (settings.isPending) {
    return (
      <div className="flex justify-center py-12">
        <Spinner />
      </div>
    )
  }
  if (settings.isError) return <Alert tone="danger">{errorMessage(settings.error)}</Alert>

  const data = settings.data
  const dirty =
    Number(interval) !== data.intervalMinutes ||
    folderEnabled !== data.folderEnabled ||
    folderPath.trim() !== data.folderPath ||
    folderMode !== data.folderMode
  const status = data.folderStatus

  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate()
  }

  return (
    <form onSubmit={submit}>
      <SectionHeader title="Abruf & Eingangsordner">
        Legt fest, wie oft nach neuen PDF-Dateien gesucht wird – in den Postfächern und im Eingangsordner.
      </SectionHeader>
      {save.isError && (
        <Alert tone="danger" className="mb-4">
          {errorMessage(save.error)}
        </Alert>
      )}

      <Card className="p-5">
        <h3 className="flex items-center gap-2 font-semibold">
          <Clock className="size-[18px] text-muted" aria-hidden /> Abrufintervall
        </h3>
        <p className="mt-1 text-sm text-muted">So oft prüft die Anwendung alle Postfächer und den Eingangsordner auf neue PDFs.</p>
        <div className="mt-4 flex flex-wrap items-end gap-x-4 gap-y-3">
          <Field label="Alle … Minuten" className="w-32">
            {(id) => (
              <Input id={id} type="number" min={1} max={1440} value={interval} onChange={(e) => setIntervalMinutes(e.target.value)} required />
            )}
          </Field>
          <div className="flex flex-wrap gap-1.5 pb-1">
            {presets.map((minutes) => (
              <button
                key={minutes}
                type="button"
                onClick={() => setIntervalMinutes(String(minutes))}
                className={cx(
                  'h-8 rounded-full border px-3 text-[13px] font-medium transition-colors',
                  Number(interval) === minutes
                    ? 'border-brand bg-brand-soft text-brand-ink'
                    : 'border-line-strong text-muted hover:bg-raised hover:text-ink',
                )}
              >
                {minutes < 60 ? `${minutes} Min.` : '1 Std.'}
              </button>
            ))}
          </div>
        </div>
        <p className="mt-3 text-[13px] text-muted">Nächster automatischer Abruf {formatRelative(data.nextRunAt)}.</p>
      </Card>

      <Card className="mt-4 p-5">
        <h3 className="flex items-center gap-2 font-semibold">
          <FolderSearch className="size-[18px] text-muted" aria-hidden /> Eingangsordner
        </h3>
        <p className="mt-1 text-sm text-muted">
          Ein Verzeichnis auf dem Server, das auf neue PDF-Dateien überwacht wird – etwa das Ziel eines Netzwerkscanners.
          Unterordner werden mit durchsucht.
        </p>
        <div className="mt-4 flex flex-col gap-4">
          <Switch checked={folderEnabled} onChange={setFolderEnabled} label="Eingangsordner überwachen" />
          <Field
            label="Pfad auf dem Server"
            hint="Absoluter Pfad. Im Docker-Container ist das der eingebundene Ordner, üblicherweise /import."
          >
            {(id) => (
              <Input
                id={id}
                value={folderPath}
                onChange={(e) => setFolderPath(e.target.value)}
                placeholder="/import"
                spellCheck={false}
                autoCapitalize="none"
                disabled={!folderEnabled}
                required={folderEnabled}
                className="font-mono !text-[13px]"
              />
            )}
          </Field>
          <div className="flex flex-col gap-1.5">
            <span className="text-[13px] font-medium">Nach der Übernahme</span>
            <Segmented
              label="Nach der Übernahme"
              value={folderMode}
              onChange={setFolderMode}
              options={[
                { value: 'KEEP', label: 'Datei liegen lassen' },
                { value: 'MOVE', label: 'In „importiert“ verschieben' },
              ]}
            />
            <p className="text-[13px] leading-snug text-muted">
              {folderMode === 'KEEP'
                ? 'Die Dateien bleiben unverändert. Die Anwendung merkt sich, was sie bereits übernommen hat.'
                : 'Übernommene Dateien wandern in den Unterordner „importiert“ – der Eingangsordner bleibt aufgeräumt.'}
            </p>
          </div>

          {data.folderEnabled && (
            <div className="flex flex-wrap items-center gap-3 border-t border-line pt-4">
              <p className={cx('flex min-w-0 flex-1 basis-60 items-start gap-1.5 text-[13px]', status.ok ? 'text-muted' : 'text-danger')}>
                {status.running ? (
                  <>
                    <Spinner className="mt-0.5 size-3.5" /> Ordner wird geprüft …
                  </>
                ) : status.lastScanAt ? (
                  <>
                    {status.ok ? (
                      <CheckCircle2 className="mt-0.5 size-3.5 shrink-0 text-ok" aria-hidden />
                    ) : (
                      <XCircle className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                    )}
                    <span className="[overflow-wrap:anywhere]">
                      {status.message} <span className="text-subtle">· {formatRelative(status.lastScanAt)}</span>
                    </span>
                  </>
                ) : (
                  'Noch nicht geprüft.'
                )}
              </p>
              <Button size="sm" onClick={() => scan.mutate()} loading={scan.isPending || status.running} disabled={dirty} title={dirty ? 'Bitte zuerst speichern' : undefined}>
                Jetzt prüfen
              </Button>
            </div>
          )}
        </div>
      </Card>

      <div className="mt-5 flex justify-end">
        <Button type="submit" variant="primary" loading={save.isPending} disabled={!dirty}>
          Speichern
        </Button>
      </div>
    </form>
  )
}
