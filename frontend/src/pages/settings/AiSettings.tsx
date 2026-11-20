import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bot, ScanText } from 'lucide-react'
import { type FormEvent, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Alert, Badge, Button, Card, Field, Input, Segmented, Spinner, Switch } from '../../components/ui'
import { api, errorMessage, type TestResult } from '../../lib/api'
import { plural } from '../../lib/format'
import { SectionHeader } from './SectionHeader'

const ANTHROPIC_URL = 'https://api.anthropic.com'
const claudeModels = ['claude-opus-5-5', 'claude-sonnet-5-5', 'claude-haiku-5-5', 'claude-fable-5-2']
const isAnthropic = (url: string) => {
  const trimmed = url.trim()
  return trimmed === '' || /^https:\/\/api\.anthropic\.com\/?$/i.test(trimmed)
}
const efforts = [
  { value: 'low', label: 'Schnell' },
  { value: 'medium', label: 'Ausgewogen' },
  { value: 'high', label: 'Gründlich' },
]

export function AiSettingsSection() {
  const queryClient = useQueryClient()
  const settings = useQuery({ queryKey: ['settings', 'ai'], queryFn: api.aiSettings })
  const system = useQuery({ queryKey: ['system'], queryFn: api.system })
  // Was der gespeicherte Server selbst an Modellen meldet – als Auswahlhilfe.
  const serverModels = useQuery({
    queryKey: ['settings', 'ai', 'models'],
    queryFn: api.aiModels,
    enabled: settings.data?.apiKeySet === true,
    staleTime: 60_000,
  })

  const [baseUrl, setBaseUrl] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [removeKey, setRemoveKey] = useState(false)
  const [model, setModel] = useState('')
  const [compatibilityMode, setCompatibilityMode] = useState(false)
  const [effort, setEffort] = useState('medium')
  const [outputLanguage, setOutputLanguage] = useState('Deutsch')
  const [ocr, setOcr] = useState(true)
  const [test, setTest] = useState<TestResult | null>(null)

  const data = settings.data
  useEffect(() => {
    if (!data) return
    setBaseUrl(data.baseUrl)
    setApiKey('')
    setRemoveKey(false)
    setModel(data.model)
    setCompatibilityMode(data.compatibilityMode)
    setEffort(efforts.some((e) => e.value === data.effort) ? data.effort : 'high')
    setOutputLanguage(data.outputLanguage)
    setOcr(data.ocr)
  }, [data])

  const save = useMutation({
    mutationFn: () =>
      api.updateAiSettings({
        baseUrl: baseUrl.trim(),
        // null = gespeicherten Schlüssel behalten, '' = entfernen
        apiKey: removeKey ? '' : apiKey.trim() || null,
        model: model.trim(),
        effort,
        outputLanguage: outputLanguage.trim(),
        ocr,
        compatibilityMode,
      }),
    onSuccess: async (updated) => {
      queryClient.setQueryData(['settings', 'ai'], updated)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['system'] }),
        queryClient.invalidateQueries({ queryKey: ['settings', 'ai', 'models'] }),
      ])
      setTest(null)
      toast.success('KI-Einstellungen gespeichert')
    },
  })
  const runTest = useMutation({
    mutationFn: api.testAi,
    onMutate: () => setTest(null),
    onSuccess: setTest,
    onError: (e) => setTest({ ok: false, message: errorMessage(e) }),
  })
  const reprocess = useMutation({
    mutationFn: api.reprocessUnanalyzed,
    onSuccess: async ({ count }) => {
      toast.success(`${plural(count, 'Dokument wird', 'Dokumente werden')} jetzt analysiert.`)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['system'] }),
        queryClient.invalidateQueries({ queryKey: ['documents'] }),
      ])
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
  if (settings.isError || !data) return <Alert tone="danger">{errorMessage(settings.error)}</Alert>

  const dirty =
    baseUrl.trim() !== data.baseUrl ||
    apiKey.trim() !== '' ||
    removeKey ||
    model.trim() !== data.model ||
    effort !== data.effort ||
    outputLanguage.trim() !== data.outputLanguage ||
    ocr !== data.ocr ||
    compatibilityMode !== data.compatibilityMode
  const waiting = (system.data?.unanalyzed ?? 0) + (system.data?.failed ?? 0)
  const anthropic = isAnthropic(baseUrl)
  const modelSuggestions = Array.from(new Set([...(serverModels.data ?? []), ...(anthropic ? claudeModels : [])]))

  function changeBaseUrl(value: string) {
    // Wechselt der Server zwischen Claude API und "etwas anderem", zieht der Kompatibilitätsmodus mit.
    if (isAnthropic(value) !== isAnthropic(baseUrl)) setCompatibilityMode(!isAnthropic(value))
    setBaseUrl(value)
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate()
  }

  return (
    <form onSubmit={submit}>
      <SectionHeader
        title="KI"
        action={<Badge tone={data.apiKeySet ? 'ok' : 'warn'}>{data.apiKeySet ? 'Eingerichtet' : 'Nicht eingerichtet'}</Badge>}
      >
        Die KI liest jedes neue PDF, fasst es zusammen, vergibt Dokumentart und Schlagworte und zieht Kerndaten wie
        Beträge oder Fristen heraus.
      </SectionHeader>
      {save.isError && (
        <Alert tone="danger" className="mb-4">
          {errorMessage(save.error)}
        </Alert>
      )}

      <Card className="p-5">
        <h3 className="flex items-center gap-2 font-semibold">
          <Bot className="size-[18px] text-muted" aria-hidden /> LLM-Server
        </h3>
        <div className="mt-4 flex flex-col gap-4">
          <Field
            label="Server-Adresse"
            hint="Standard ist die Claude API von Anthropic. Auch ein eigener Server im Netz funktioniert, wenn er die Anthropic-Messages-API spricht – z. B. http://192.168.1.10:8000."
          >
            {(id) => (
              <Input id={id} type="url" value={baseUrl} onChange={(e) => changeBaseUrl(e.target.value)} placeholder={ANTHROPIC_URL} spellCheck={false} className="font-mono !text-[13px]" />
            )}
          </Field>
          <Field
            label="API-Schlüssel"
            hint={
              data.apiKeySource === 'SETTINGS' ? (
                removeKey ? (
                  'Der gespeicherte Schlüssel wird beim Speichern entfernt.'
                ) : (
                  <>
                    Ein Schlüssel ist verschlüsselt gespeichert. Leer lassen, um ihn zu behalten –{' '}
                    <button type="button" onClick={() => setRemoveKey(true)} className="font-medium text-danger hover:underline">
                      Schlüssel entfernen
                    </button>
                  </>
                )
              ) : data.apiKeySource === 'ENVIRONMENT' ? (
                'Der Schlüssel kommt derzeit aus der Server-Konfiguration (Umgebungsvariable ANTHROPIC_API_KEY bzw. Konfigurationsdatei). Ein hier eingetragener Schlüssel hat Vorrang.'
              ) : (
                'Wird verschlüsselt gespeichert und nie wieder angezeigt.'
              )
            }
          >
            {(id) => (
              <Input
                id={id}
                type="password"
                value={apiKey}
                onChange={(e) => {
                  setApiKey(e.target.value)
                  setRemoveKey(false)
                }}
                placeholder={data.apiKeySet && !removeKey ? '•••••••••••••••• (gespeichert)' : 'sk-ant-…'}
                autoComplete="off"
                spellCheck={false}
                className="font-mono !text-[13px]"
              />
            )}
          </Field>
          <Field
            label="Modell"
            hint={
              anthropic
                ? 'Claude Opus 5.5 ist die empfohlene Wahl; Sonnet und Haiku sind schneller und günstiger.'
                : 'Die Modell-ID so, wie der Server sie nennt. Nach dem Speichern erscheinen hier die Modelle, die der Server anbietet.'
            }
          >
            {(id) => (
              <>
                <Input id={id} value={model} onChange={(e) => setModel(e.target.value)} list={`${id}-models`} placeholder="Modell-ID" spellCheck={false} required className="font-mono !text-[13px]" />
                <datalist id={`${id}-models`}>
                  {modelSuggestions.map((name) => (
                    <option key={name} value={name} />
                  ))}
                </datalist>
                {modelSuggestions.length > 0 && (
                  <div className="flex flex-wrap gap-1.5">
                    {modelSuggestions.slice(0, 8).map((name) => (
                      <button
                        key={name}
                        type="button"
                        onClick={() => setModel(name)}
                        className={
                          'max-w-full truncate rounded-full border px-2.5 py-0.5 font-mono text-xs transition-colors ' +
                          (model === name
                            ? 'border-brand bg-brand-soft text-brand-ink'
                            : 'border-line-strong text-muted hover:bg-raised hover:text-ink')
                        }
                      >
                        {name}
                      </button>
                    ))}
                  </div>
                )}
              </>
            )}
          </Field>
          <Switch
            checked={compatibilityMode}
            onChange={setCompatibilityMode}
            label="Kompatibilitätsmodus für lokale Modelle"
            description="Für Server ohne PDF-Eingabe und strukturierte Ausgaben: Das Dokument geht als Text bzw. bei Scans als Seitenbilder an das Modell, das Ergebnis kommt als Werkzeugaufruf zurück. Bei der Claude API ausgeschaltet lassen."
          />

          {test && <Alert tone={test.ok ? 'ok' : 'danger'}>{test.message}</Alert>}
          <div>
            <Button
              onClick={() => runTest.mutate()}
              loading={runTest.isPending}
              disabled={dirty || !data.apiKeySet}
              title={dirty ? 'Bitte zuerst speichern' : !data.apiKeySet ? 'Es ist noch kein API-Schlüssel hinterlegt' : undefined}
            >
              Verbindung testen
            </Button>
          </div>
        </div>
      </Card>

      <Card className="mt-4 p-5">
        <h3 className="flex items-center gap-2 font-semibold">
          <ScanText className="size-[18px] text-muted" aria-hidden /> Analyse
        </h3>
        <div className="mt-4 flex flex-col gap-5">
          <div className="flex flex-col gap-1.5">
            <span className="text-[13px] font-medium">Gründlichkeit</span>
            <Segmented label="Gründlichkeit" value={effort} onChange={setEffort} options={efforts} />
            <p className="text-[13px] text-muted">Gründlicher bedeutet: sorgfältigere Ergebnisse, aber langsamer und teurer je Dokument.</p>
          </div>
          <Field label="Sprache der Ergebnisse" hint="In dieser Sprache schreibt die KI Titel, Zusammenfassung und Schlagworte." className="max-w-xs">
            {(id) => <Input id={id} value={outputLanguage} onChange={(e) => setOutputLanguage(e.target.value)} maxLength={50} required />}
          </Field>
          <Switch
            checked={ocr}
            onChange={setOcr}
            label="Texterkennung für Scans"
            description="Gescannte PDFs ohne Textebene lässt die Anwendung von der KI transkribieren – erst dadurch werden sie im Volltext durchsuchbar. Kostet je Scan etwas mehr."
          />
        </div>
      </Card>

      <div className="mt-5 flex justify-end">
        <Button type="submit" variant="primary" loading={save.isPending} disabled={!dirty}>
          Speichern
        </Button>
      </div>

      {data.apiKeySet && waiting > 0 && (
        <Alert
          tone="brand"
          className="mt-6"
          action={
            <Button size="sm" onClick={() => reprocess.mutate()} loading={reprocess.isPending} className="shrink-0">
              Jetzt analysieren
            </Button>
          }
        >
          {plural(waiting, 'Dokument ist', 'Dokumente sind')} noch nicht von der KI erschlossen.
        </Alert>
      )}
    </form>
  )
}
