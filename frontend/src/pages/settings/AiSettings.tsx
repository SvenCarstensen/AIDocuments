import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bot, ScanText, ShieldAlert } from 'lucide-react'
import { type FormEvent, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Alert, Badge, Button, Card, Field, Input, Segmented, Select, Spinner, Switch } from '../../components/ui'
import { api, errorMessage, type LlmProvider, type LlmProviderInfo, type TestResult } from '../../lib/api'
import { plural } from '../../lib/format'
import { SectionHeader } from './SectionHeader'

const claudeModels = ['claude-opus-5-5', 'claude-sonnet-5-5', 'claude-haiku-5-5', 'claude-fable-5-2']
const efforts = [
  { value: 'low', label: 'Schnell' },
  { value: 'medium', label: 'Ausgewogen' },
  { value: 'high', label: 'Gründlich' },
]

/** Was beim jeweiligen Anbieter in den Feldern steht bzw. als Hinweis darunter. */
const providerHints: Record<LlmProvider, { url: string; model: string; key: string }> = {
  ANTHROPIC: {
    url: 'Die Claude API von Anthropic. Nur ändern, wenn ein eigenes Gateway davorsteht.',
    model: 'Claude Opus 5.5 ist die empfohlene Wahl; Sonnet und Haiku sind schneller und günstiger.',
    key: 'sk-ant-…',
  },
  OPENAI: {
    url: 'Die API von OpenAI. Nur ändern, wenn ein eigenes Gateway davorsteht.',
    model: 'Die Modell-ID, wie OpenAI sie nennt. Nach dem Speichern des Schlüssels erscheinen hier die verfügbaren Modelle.',
    key: 'sk-…',
  },
  MOONSHOT: {
    url: 'Die API von Moonshot AI (Kimi). Für Konten der chinesischen Plattform: https://api.moonshot.cn/v1',
    model: 'Die Modell-ID, wie Moonshot sie nennt. Nach dem Speichern des Schlüssels erscheinen hier die verfügbaren Modelle.',
    key: 'sk-…',
  },
  LM_STUDIO: {
    url: 'Der lokale Server von LM Studio (in LM Studio unter „Developer“ starten). Läuft AIDocuments in Docker: http://host.docker.internal:1234/v1',
    model: 'Die ID des in LM Studio geladenen Modells. Für Scans braucht es ein Modell mit Bildeingabe, für die Erschließung eines mit Werkzeugaufrufen (Tool Use).',
    key: 'nicht nötig',
  },
  OPENAI_COMPATIBLE: {
    url: 'Adresse eines Servers, der die OpenAI-Schnittstelle spricht – üblicherweise mit /v1 am Ende, z. B. http://192.168.1.10:8000/v1',
    model: 'Die Modell-ID, wie der Server sie nennt. Nach dem Speichern erscheinen hier die Modelle, die er anbietet.',
    key: 'nur falls der Server einen verlangt',
  },
}

/**
 * Zeigt die Adresse auf diesen Rechner oder ins eigene Netz? Dann bleiben die Inhalte im Haus –
 * auch wenn als Anbieter ein Internetdienst gewählt ist (z. B. ein eigener Server mit Anthropic-Schnittstelle).
 */
function isLocalAddress(url: string): boolean {
  try {
    const host = new URL(url.trim()).hostname.toLowerCase()
    return (
      host === 'localhost' ||
      host === 'host.docker.internal' ||
      host === '[::1]' ||
      host.endsWith('.local') ||
      host.endsWith('.lan') ||
      /^127\./.test(host) ||
      /^10\./.test(host) ||
      /^192\.168\./.test(host) ||
      /^172\.(1[6-9]|2\d|3[01])\./.test(host)
    )
  } catch {
    return false
  }
}

export function AiSettingsSection() {
  const queryClient = useQueryClient()
  const settings = useQuery({ queryKey: ['settings', 'ai'], queryFn: api.aiSettings })
  const system = useQuery({ queryKey: ['system'], queryFn: api.system })
  // Was der gespeicherte Server selbst an Modellen meldet – als Auswahlhilfe.
  const serverModels = useQuery({
    queryKey: ['settings', 'ai', 'models'],
    queryFn: api.aiModels,
    enabled: settings.data !== undefined,
    staleTime: 60_000,
  })

  const [provider, setProvider] = useState<LlmProvider>('ANTHROPIC')
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
    setProvider(data.provider)
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
        provider,
        baseUrl: baseUrl.trim(),
        // null = gespeicherten Schlüssel behalten, '' = entfernen
        apiKey: removeKey ? '' : apiKey.trim() || null,
        model: model.trim(),
        effort,
        outputLanguage: outputLanguage.trim(),
        ocr,
        compatibilityMode: provider === 'ANTHROPIC' ? compatibilityMode : null,
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

  const info = (id: LlmProvider): LlmProviderInfo => data.providers.find((p) => p.id === id) ?? data.providers[0]
  const current = info(provider)
  const hints = providerHints[provider]
  const providerChanged = provider !== data.provider
  // Ein gespeicherter Schlüssel gehört zum gespeicherten Anbieter – beim Wechsel zählt er nicht mehr.
  const keyStored = data.apiKeySet && !providerChanged
  const dirty =
    providerChanged ||
    baseUrl.trim() !== data.baseUrl ||
    apiKey.trim() !== '' ||
    removeKey ||
    model.trim() !== data.model ||
    effort !== data.effort ||
    outputLanguage.trim() !== data.outputLanguage ||
    ocr !== data.ocr ||
    (provider === 'ANTHROPIC' && compatibilityMode !== data.compatibilityMode)
  const waiting = (system.data?.unanalyzed ?? 0) + (system.data?.failed ?? 0)
  const modelSuggestions = Array.from(
    new Set([...(providerChanged ? [] : (serverModels.data ?? [])), ...(provider === 'ANTHROPIC' ? claudeModels : [])]),
  )

  function changeProvider(next: LlmProvider) {
    if (next === provider) return
    // Zurück zum gespeicherten Anbieter: dessen gespeicherte Angaben wiederherstellen.
    const restoring = next === data!.provider
    setProvider(next)
    setBaseUrl(restoring ? data!.baseUrl : info(next).defaultBaseUrl)
    setModel(restoring ? data!.model : next === 'ANTHROPIC' ? claudeModels[0] : '')
    setCompatibilityMode(restoring ? data!.compatibilityMode : false)
    setApiKey('')
    setRemoveKey(false)
    setTest(null)
  }
  function changeBaseUrl(value: string) {
    // Bei Anthropic: Zeigt die Adresse nicht mehr auf die Claude API selbst, ist der Kompatibilitätsmodus gefragt.
    if (provider === 'ANTHROPIC') {
      const isClaudeApi = (url: string) => /^https:\/\/api\.anthropic\.com\/?$/i.test(url.trim()) || url.trim() === ''
      if (isClaudeApi(value) !== isClaudeApi(baseUrl)) setCompatibilityMode(!isClaudeApi(value))
    }
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
        action={<Badge tone={data.enabled ? 'ok' : 'warn'}>{data.enabled ? 'Eingerichtet' : 'Nicht eingerichtet'}</Badge>}
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
          <Field label="Anbieter" className="max-w-xs">
            {(id) => (
              <Select id={id} value={provider} onChange={(e) => changeProvider(e.target.value as LlmProvider)}>
                {data.providers.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.label}
                  </option>
                ))}
              </Select>
            )}
          </Field>

          {current.cloud && !isLocalAddress(baseUrl) && (
            <div role="note" className="flex items-start gap-3 rounded-lg border border-warn/40 bg-warn-soft px-4 py-3.5">
              <ShieldAlert className="mt-0.5 size-5 shrink-0 text-warn" aria-hidden />
              <div className="text-sm leading-relaxed">
                <p className="font-semibold text-ink">Deine Dokumente verlassen bei {current.label} diesen Rechner</p>
                <p className="mt-1 text-muted">
                  Zur Analyse wird der Inhalt jedes PDFs an die Server von {current.label} im Internet übertragen und
                  dort verarbeitet – auch Rechnungen, Verträge, Arztbriefe und Behördenpost.
                </p>
                <p className="mt-2 text-muted">
                  <span className="font-medium text-ink">Empfehlung:</span> eine lokale KI verwenden. Mit{' '}
                  <button type="button" onClick={() => changeProvider('LM_STUDIO')} className="font-medium text-brand-ink underline underline-offset-2">
                    LM Studio
                  </button>{' '}
                  oder einem{' '}
                  <button type="button" onClick={() => changeProvider('OPENAI_COMPATIBLE')} className="font-medium text-brand-ink underline underline-offset-2">
                    OpenAI-kompatiblen Server
                  </button>{' '}
                  im eigenen Netz bleiben alle Inhalte bei dir.
                </p>
              </div>
            </div>
          )}

          <Field label="Server-Adresse" hint={hints.url}>
            {(id) => (
              <Input
                id={id}
                type="url"
                value={baseUrl}
                onChange={(e) => changeBaseUrl(e.target.value)}
                placeholder={current.defaultBaseUrl || 'http://server:8000/v1'}
                required={current.defaultBaseUrl === ''}
                spellCheck={false}
                className="font-mono !text-[13px]"
              />
            )}
          </Field>
          <Field
            label={current.requiresApiKey ? 'API-Schlüssel' : 'API-Schlüssel (optional)'}
            hint={
              keyStored && data.apiKeySource === 'SETTINGS' ? (
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
              ) : keyStored && data.apiKeySource === 'ENVIRONMENT' ? (
                'Der Schlüssel kommt derzeit aus der Server-Konfiguration (Umgebungsvariable bzw. Konfigurationsdatei). Ein hier eingetragener Schlüssel hat Vorrang.'
              ) : providerChanged && data.apiKeySet ? (
                `Der bisher gespeicherte Schlüssel gehört zu ${info(data.provider).label} und wird beim Wechsel verworfen.`
              ) : current.requiresApiKey ? (
                'Wird verschlüsselt gespeichert und nie wieder angezeigt.'
              ) : (
                'Lokale Server brauchen meist keinen Schlüssel.'
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
                placeholder={keyStored && !removeKey ? '•••••••••••••••• (gespeichert)' : hints.key}
                required={current.requiresApiKey && !keyStored}
                autoComplete="off"
                spellCheck={false}
                className="font-mono !text-[13px]"
              />
            )}
          </Field>
          <Field label="Modell" hint={hints.model}>
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
                    {modelSuggestions.slice(0, 10).map((name) => (
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
          {provider === 'ANTHROPIC' && (
            <Switch
              checked={compatibilityMode}
              onChange={setCompatibilityMode}
              label="Kompatibilitätsmodus für lokale Modelle"
              description="Für Server, die zwar die Anthropic-Schnittstelle sprechen, aber keine PDF-Eingabe und keine strukturierten Ausgaben kennen: Das Dokument geht als Text bzw. bei Scans als Seitenbilder an das Modell. Bei der Claude API ausgeschaltet lassen."
            />
          )}

          {test && <Alert tone={test.ok ? 'ok' : 'danger'}>{test.message}</Alert>}
          <div>
            <Button
              onClick={() => runTest.mutate()}
              loading={runTest.isPending}
              disabled={dirty || !data.enabled}
              title={dirty ? 'Bitte zuerst speichern' : !data.enabled ? 'Die Angaben sind noch nicht vollständig' : undefined}
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
          {provider === 'ANTHROPIC' && (
            <div className="flex flex-col gap-1.5">
              <span className="text-[13px] font-medium">Gründlichkeit</span>
              <Segmented label="Gründlichkeit" value={effort} onChange={setEffort} options={efforts} />
              <p className="text-[13px] text-muted">Gründlicher bedeutet: sorgfältigere Ergebnisse, aber langsamer und teurer je Dokument.</p>
            </div>
          )}
          <Field label="Sprache der Ergebnisse" hint="In dieser Sprache schreibt die KI Titel, Zusammenfassung und Schlagworte." className="max-w-xs">
            {(id) => <Input id={id} value={outputLanguage} onChange={(e) => setOutputLanguage(e.target.value)} maxLength={50} required />}
          </Field>
          <Switch
            checked={ocr}
            onChange={setOcr}
            label="Texterkennung für Scans"
            description="Gescannte PDFs ohne Textebene lässt die Anwendung von der KI transkribieren – erst dadurch werden sie im Volltext durchsuchbar. Braucht je Scan mehr Zeit bzw. Kosten."
          />
        </div>
      </Card>

      <div className="mt-5 flex justify-end">
        <Button type="submit" variant="primary" loading={save.isPending} disabled={!dirty}>
          Speichern
        </Button>
      </div>

      {data.enabled && waiting > 0 && (
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
