import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bot, ScanText, ShieldAlert } from 'lucide-react'
import { type FormEvent, useEffect, useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Alert, Badge, Button, Card, Field, Input, Segmented, Select, Spinner, Switch } from '../../components/ui'
import { type AiSettings, api, errorMessage, type LlmProvider, type LlmProviderInfo, type TestResult } from '../../lib/api'
import { SectionHeader } from './SectionHeader'

const claudeModels = ['claude-opus-5-5', 'claude-sonnet-5-5', 'claude-haiku-5-5', 'claude-fable-5-2']
/** Stufen der Gründlichkeit; die Bezeichnungen stehen unter settings.ai.analysis.effortLevels.<Stufe>. */
const efforts = ['low', 'medium', 'high'] as const

/**
 * Was je Anbieter nicht übersetzt wird: die Beispieladresse im Hinweis zur Server-Adresse und das Muster des
 * Schlüssels. Die Hinweistexte selbst stehen unter settings.ai.providers.<Anbieter>.
 */
const providerExamples: Record<LlmProvider, { url: string; key: string | null }> = {
  ANTHROPIC: { url: '', key: 'sk-ant-…' },
  OPENAI: { url: '', key: 'sk-…' },
  MOONSHOT: { url: 'https://api.moonshot.cn/v1', key: 'sk-…' },
  LM_STUDIO: { url: 'http://host.docker.internal:1234/v1', key: null },
  OPENAI_COMPATIBLE: { url: 'http://192.168.1.10:8000/v1', key: null },
}

/** Platzhalter im Schlüsselfeld, solange ein gespeicherter oder vorbelegter Schlüssel gilt. */
const KEY_MASK = '••••••••••••••••'

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

/**
 * Der Schlüssel, der für diesen Anbieter aus der Server-Konfiguration gilt und im Formular gezeigt werden darf.
 * Leer, wenn es keinen gibt, wenn in der Oberfläche ein eigener gespeichert ist oder wenn der Server ihn –
 * wie im Auslieferungszustand – nicht herausgibt.
 */
function visibleConfigKey(settings: AiSettings, provider: LlmProvider): string {
  if (provider === settings.provider && settings.apiKeySource === 'SETTINGS') return ''
  return settings.providers.find((p) => p.id === provider)?.presetApiKey ?? ''
}

export function AiSettingsSection() {
  const { t } = useTranslation()
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
  const [quickOcrFirst, setQuickOcrFirst] = useState(false)
  const [test, setTest] = useState<TestResult | null>(null)

  const data = settings.data
  useEffect(() => {
    if (!data) return
    setProvider(data.provider)
    setBaseUrl(data.baseUrl)
    setApiKey(visibleConfigKey(data, data.provider))
    setRemoveKey(false)
    setModel(data.model)
    setCompatibilityMode(data.compatibilityMode)
    setEffort(efforts.some((e) => e === data.effort) ? data.effort : 'high')
    setOutputLanguage(data.outputLanguage)
    setOcr(data.ocr)
    setQuickOcrFirst(data.quickOcrFirst)
  }, [data])

  const save = useMutation({
    mutationFn: () =>
      api.updateAiSettings({
        provider,
        baseUrl: baseUrl.trim(),
        // null = gespeicherten Schlüssel behalten, '' = entfernen
        // Ein unverändert vorbelegter Schlüssel wird nicht mitgeschickt: Er gilt ohnehin, solange kein anderer gespeichert ist.
        apiKey: removeKey ? '' : apiKey.trim() === visibleConfigKey(data!, provider) ? null : apiKey.trim() || null,
        model: model.trim(),
        effort,
        outputLanguage: outputLanguage.trim(),
        ocr,
        quickOcrFirst,
        compatibilityMode: provider === 'ANTHROPIC' ? compatibilityMode : null,
      }),
    onSuccess: async (updated) => {
      queryClient.setQueryData(['settings', 'ai'], updated)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['system'] }),
        queryClient.invalidateQueries({ queryKey: ['settings', 'ai', 'models'] }),
      ])
      setTest(null)
      toast.success(t('settings.ai.saved'))
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
      toast.success(t('settings.ai.analysisStarted', { count }))
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
  const examples = providerExamples[provider]
  const providerChanged = provider !== data.provider
  // Ein gespeicherter Schlüssel gehört zum gespeicherten Anbieter – beim Wechsel zählt er nicht mehr.
  const keyStored = data.apiKeySet && !providerChanged
  // Für den neu gewählten Anbieter kann die Server-Konfiguration einen Schlüssel vorbelegen.
  const keyPreset = providerChanged && current.presetApiKeySet
  // In der Entwicklungsumgebung steht der vorbelegte Schlüssel sichtbar im Feld; das zählt nicht als Änderung.
  const initialKey = visibleConfigKey(data, provider)
  const keyShown = initialKey !== '' && apiKey === initialKey
  const dirty =
    providerChanged ||
    baseUrl.trim() !== data.baseUrl ||
    apiKey.trim() !== initialKey ||
    removeKey ||
    model.trim() !== data.model ||
    effort !== data.effort ||
    outputLanguage.trim() !== data.outputLanguage ||
    ocr !== data.ocr ||
    quickOcrFirst !== data.quickOcrFirst ||
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
    // Sonst: die Vorbelegung aus der Server-Konfiguration, ersatzweise die Standardwerte des Anbieters.
    const target = info(next)
    setBaseUrl(restoring ? data!.baseUrl : target.presetBaseUrl || target.defaultBaseUrl)
    setModel(restoring ? data!.model : target.presetModel || (next === 'ANTHROPIC' ? claudeModels[0] : ''))
    setCompatibilityMode(restoring ? data!.compatibilityMode : false)
    setApiKey(visibleConfigKey(data!, next))
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
        title={t('settings.ai.title')}
        action={<Badge tone={data.enabled ? 'ok' : 'warn'}>{data.enabled ? t('settings.ai.configured') : t('settings.ai.notConfigured')}</Badge>}
      >
        {t('settings.ai.intro')}
      </SectionHeader>
      {save.isError && (
        <Alert tone="danger" className="mb-4">
          {errorMessage(save.error)}
        </Alert>
      )}

      <Card className="p-5">
        <h3 className="flex items-center gap-2 font-semibold">
          <Bot className="size-[18px] text-muted" aria-hidden /> {t('settings.ai.server.title')}
        </h3>
        <div className="mt-4 flex flex-col gap-4">
          <Field label={t('settings.ai.server.provider')} className="max-w-xs">
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
                <p className="font-semibold text-ink">{t('settings.ai.privacy.title', { provider: current.label })}</p>
                <p className="mt-1 text-muted">{t('settings.ai.privacy.text', { provider: current.label })}</p>
                <p className="mt-2 text-muted">
                  {/* Ein zusammenhängender Satz mit zwei Schaltflächen mittendrin – die Wortstellung bestimmt die Übersetzung. */}
                  <Trans
                    i18nKey="settings.ai.privacy.recommendation"
                    components={{
                      emphasis: <span className="font-medium text-ink" />,
                      lmStudio: <button type="button" onClick={() => changeProvider('LM_STUDIO')} className="font-medium text-brand-ink underline underline-offset-2" />,
                      compatible: <button type="button" onClick={() => changeProvider('OPENAI_COMPATIBLE')} className="font-medium text-brand-ink underline underline-offset-2" />,
                    }}
                  />
                </p>
              </div>
            </div>
          )}

          <Field label={t('settings.ai.server.baseUrl')} hint={t(`settings.ai.providers.${provider}.urlHint`, { url: examples.url })}>
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
            label={current.requiresApiKey ? t('settings.ai.apiKey.label') : t('settings.ai.apiKey.labelOptional')}
            hint={
              keyShown ? (
                t('settings.ai.apiKey.hintShown')
              ) : keyStored && data.apiKeySource === 'SETTINGS' ? (
                removeKey ? (
                  t('settings.ai.apiKey.hintRemoving')
                ) : (
                  <Trans
                    i18nKey="settings.ai.apiKey.hintStored"
                    components={{
                      remove: <button type="button" onClick={() => setRemoveKey(true)} className="font-medium text-danger hover:underline" />,
                    }}
                  />
                )
              ) : keyStored && data.apiKeySource === 'ENVIRONMENT' ? (
                t('settings.ai.apiKey.hintEnvironment')
              ) : keyPreset ? (
                t('settings.ai.apiKey.hintPreset')
              ) : providerChanged && data.apiKeySet ? (
                data.apiKeySource === 'SETTINGS' ? (
                  t('settings.ai.apiKey.hintDiscardedOnSwitch', { previous: info(data.provider).label })
                ) : (
                  t('settings.ai.apiKey.hintPresetOtherProvider', { previous: info(data.provider).label, provider: current.label })
                )
              ) : current.requiresApiKey ? (
                t('settings.ai.apiKey.hintRequired')
              ) : (
                t('settings.ai.apiKey.hintLocal')
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
                placeholder={
                  keyStored && !removeKey
                    ? t('settings.ai.apiKey.placeholderStored', { mask: KEY_MASK })
                    : keyPreset
                      ? t('settings.ai.apiKey.placeholderPreset', { mask: KEY_MASK })
                      : (examples.key ??
                        (provider === 'LM_STUDIO' ? t('settings.ai.apiKey.placeholderNotNeeded') : t('settings.ai.apiKey.placeholderIfRequired')))
                }
                required={current.requiresApiKey && !keyStored && !keyPreset}
                autoComplete="off"
                spellCheck={false}
                className="font-mono !text-[13px]"
              />
            )}
          </Field>
          <Field label={t('settings.ai.server.model')} hint={t(`settings.ai.providers.${provider}.modelHint`)}>
            {(id) => (
              <>
                <Input id={id} value={model} onChange={(e) => setModel(e.target.value)} list={`${id}-models`} placeholder={t('settings.ai.server.modelPlaceholder')} spellCheck={false} required className="font-mono !text-[13px]" />
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
              label={t('settings.ai.server.compatibilityMode')}
              description={t('settings.ai.server.compatibilityModeHint')}
            />
          )}

          {test && <Alert tone={test.ok ? 'ok' : 'danger'}>{test.message}</Alert>}
          <div>
            <Button
              onClick={() => runTest.mutate()}
              loading={runTest.isPending}
              disabled={dirty || !data.enabled}
              title={dirty ? t('common.saveFirst') : !data.enabled ? t('settings.ai.server.testIncomplete') : undefined}
            >
              {t('common.testConnection')}
            </Button>
          </div>
        </div>
      </Card>

      <Card className="mt-4 p-5">
        <h3 className="flex items-center gap-2 font-semibold">
          <ScanText className="size-[18px] text-muted" aria-hidden /> {t('settings.ai.analysis.title')}
        </h3>
        <div className="mt-4 flex flex-col gap-5">
          {provider === 'ANTHROPIC' && (
            <div className="flex flex-col gap-1.5">
              <span className="text-[13px] font-medium">{t('settings.ai.analysis.effort')}</span>
              <Segmented
                label={t('settings.ai.analysis.effort')}
                value={effort}
                onChange={setEffort}
                options={efforts.map((level) => ({ value: level as string, label: t(`settings.ai.analysis.effortLevels.${level}`) }))}
              />
              <p className="text-[13px] text-muted">{t('settings.ai.analysis.effortHint')}</p>
            </div>
          )}
          <Field label={t('settings.ai.analysis.outputLanguage')} hint={t('settings.ai.analysis.outputLanguageHint')} className="max-w-xs">
            {(id) => <Input id={id} value={outputLanguage} onChange={(e) => setOutputLanguage(e.target.value)} maxLength={50} required />}
          </Field>
          <Switch
            checked={ocr}
            onChange={setOcr}
            label={t('settings.ai.analysis.ocr')}
            description={t('settings.ai.analysis.ocrHint')}
          />
          {system.data?.ocrAvailable && (
            <Switch
              checked={quickOcrFirst}
              onChange={setQuickOcrFirst}
              label={t('settings.ai.analysis.quickOcrFirst')}
              description={t('settings.ai.analysis.quickOcrFirstHint')}
            />
          )}
        </div>
      </Card>

      <div className="mt-5 flex justify-end">
        <Button type="submit" variant="primary" loading={save.isPending} disabled={!dirty}>
          {t('common.save')}
        </Button>
      </div>

      {data.enabled && waiting > 0 && (
        <Alert
          tone="brand"
          className="mt-6"
          action={
            <Button size="sm" onClick={() => reprocess.mutate()} loading={reprocess.isPending} className="shrink-0">
              {t('settings.ai.analyzeNow')}
            </Button>
          }
        >
          {t('settings.ai.unanalyzed', { count: waiting })}
        </Alert>
      )}
    </form>
  )
}
