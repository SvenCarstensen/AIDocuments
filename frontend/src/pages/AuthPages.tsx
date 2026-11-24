import { FileSearch, Fingerprint, Inbox, ShieldCheck, Sparkles } from 'lucide-react'
import { type FormEvent, type ReactNode, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { LanguageSwitcher } from '../components/LanguageSwitcher'
import { TwoFactorSetupFlow } from '../components/TwoFactorDialog'
import { FreeForNote, UsageChoice } from '../components/License'
import { Alert, Button, Field, Input, Logo, Textarea } from '../components/ui'
import { api, errorMessage, type LicenseUsage, type User } from '../lib/api'
import { getPasskey, isCancelled, passkeysSupported } from '../lib/passkey'

function AuthLayout({ title, subtitle, children, aside }: { title: string; subtitle: string; children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="relative flex min-h-dvh items-center justify-center overflow-hidden px-4 py-10">
      {/* dezenter Farbverlauf als Hintergrund */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(60%_50%_at_50%_0%,var(--brand-soft),transparent_70%)]"
      />
      {/* Sprachwahl schon vor der Anmeldung – hier landet, wer die Anwendung zum ersten Mal sieht */}
      <LanguageSwitcher className="absolute top-4 right-4" selectClassName="!w-auto" />
      <div className="w-full max-w-[26rem] animate-rise">
        <div className="mb-7 flex flex-col items-center text-center">
          <Logo className="mb-4 size-12" />
          <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
          <p className="mt-1.5 text-sm leading-relaxed text-muted">{subtitle}</p>
        </div>
        <div className="rounded-2xl border border-line bg-surface p-6 shadow-pop sm:p-7">{children}</div>
        {aside}
      </div>
    </div>
  )
}

export function LoginPage({ onDone }: { onDone: (user: User) => void }) {
  const { t } = useTranslation()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // Passwort geprüft, es fehlt noch der Code aus der Authenticator-App
  const [needsCode, setNeedsCode] = useState(false)

  const [passkeyBusy, setPasskeyBusy] = useState(false)

  async function signInWithPasskey() {
    setPasskeyBusy(true)
    setError(null)
    try {
      const credential = await getPasskey(await api.passkeyLoginOptions())
      if (credential) {
        onDone(await api.passkeyLogin(credential))
        return
      }
    } catch (e) {
      if (!isCancelled(e)) setError(errorMessage(e))
    }
    setPasskeyBusy(false)
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const result = await api.login(username, password)
      if ('twoFactorRequired' in result) {
        setNeedsCode(true)
        setPassword('')
        setBusy(false)
      } else {
        onDone(result)
      }
    } catch (e) {
      setError(errorMessage(e))
      setBusy(false)
    }
  }

  if (needsCode) {
    return (
      <TwoFactorStep
        onDone={onDone}
        onBack={() => {
          setNeedsCode(false)
          setError(null)
        }}
      />
    )
  }

  return (
    <AuthLayout title={t('auth.login.title')} subtitle={t('auth.login.subtitle')} aside={<FreeForNote className="mt-5 px-2" />}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label={t('common.username')}>
          {(id) => (
            <Input
              id={id}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              autoCapitalize="none"
              autoFocus
              required
            />
          )}
        </Field>
        <Field label={t('common.password')}>
          {(id) => (
            <Input
              id={id}
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          )}
        </Field>
        <Button type="submit" variant="primary" loading={busy} className="mt-1 w-full">
          {t('auth.login.submit')}
        </Button>
        {passkeysSupported() && (
          <>
            <div className="flex items-center gap-3 text-xs text-muted" aria-hidden>
              <span className="h-px flex-1 bg-line" />
              {t('auth.login.or')}
              <span className="h-px flex-1 bg-line" />
            </div>
            <Button className="w-full" icon={<Fingerprint className="size-4" aria-hidden />} loading={passkeyBusy} onClick={() => void signInWithPasskey()}>
              {t('auth.login.passkey')}
            </Button>
          </>
        )}
      </form>
    </AuthLayout>
  )
}

function TwoFactorStep({ onDone, onBack }: { onDone: (user: User) => void; onBack: () => void }) {
  const { t } = useTranslation()
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      onDone(await api.loginTwoFactor(code))
    } catch (e) {
      setError(errorMessage(e))
      setCode('')
      setBusy(false)
    }
  }

  return (
    <AuthLayout title={t('auth.twoFactor.title')} subtitle={t('auth.twoFactor.subtitle')}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label={t('auth.twoFactor.code')} hint={t('auth.twoFactor.codeHint')}>
          {(id) => (
            <Input
              id={id}
              value={code}
              onChange={(e) => setCode(e.target.value)}
              autoComplete="one-time-code"
              inputMode="text"
              autoCapitalize="none"
              spellCheck={false}
              placeholder="123456"
              className="font-mono tracking-wider"
              maxLength={50}
              autoFocus
              required
            />
          )}
        </Field>
        <Button type="submit" variant="primary" loading={busy} className="mt-1 w-full">
          {t('auth.twoFactor.submit')}
        </Button>
        <button type="button" onClick={onBack} className="text-sm text-muted hover:text-ink hover:underline">
          {t('auth.twoFactor.back')}
        </button>
      </form>
    </AuthLayout>
  )
}

export function SetupPage({ onDone }: { onDone: (user: User) => void }) {
  const { t } = useTranslation()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [repeat, setRepeat] = useState('')
  const [setupCode, setSetupCode] = useState('')
  const [usage, setUsage] = useState<LicenseUsage | null>(null)
  const [licenseCode, setLicenseCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const mismatch = repeat.length > 0 && password !== repeat

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (password !== repeat || !usage) return
    setBusy(true)
    setError(null)
    try {
      onDone(await api.setup(username, password, setupCode, usage, usage === 'BUSINESS' ? licenseCode.trim() : ''))
    } catch (e) {
      setError(errorMessage(e))
      setBusy(false)
    }
  }

  const features = [
    { icon: <Inbox className="size-4" />, text: t('auth.setup.features.import') },
    { icon: <Sparkles className="size-4" />, text: t('auth.setup.features.ai') },
    { icon: <FileSearch className="size-4" />, text: t('auth.setup.features.search') },
  ]

  return (
    <AuthLayout
      title={t('auth.setup.title')}
      subtitle={t('auth.setup.subtitle')}
      aside={
        <>
        <ul className="mt-6 flex flex-col gap-2.5 px-2 text-[13px] text-muted">
          {features.map((feature) => (
            <li key={feature.text} className="flex items-center gap-2.5">
              <span className="flex size-7 items-center justify-center rounded-lg bg-brand-soft text-brand-ink">
                {feature.icon}
              </span>
              {feature.text}
            </li>
          ))}
        </ul>
        <FreeForNote className="mt-5 px-2" />
        </>
      }
    >
      <form onSubmit={submit} className="flex flex-col gap-4">
        <div className="flex items-center gap-2 text-[13px] font-medium text-brand-ink">
          <ShieldCheck className="size-4" aria-hidden />
          {t('auth.setup.adminAccount')}
        </div>
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label={t('auth.setup.code')} hint={t('auth.setup.codeHint')}>
          {(id) => (
            <Input
              id={id}
              value={setupCode}
              onChange={(e) => setSetupCode(e.target.value)}
              autoComplete="one-time-code"
              autoCapitalize="characters"
              spellCheck={false}
              placeholder="XXXX-XXXX-XXXX"
              className="font-mono tracking-wider"
              autoFocus
              required
            />
          )}
        </Field>
        <Field label={t('common.username')} hint={t('common.usernameHint')}>
          {(id) => (
            <Input
              id={id}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              autoCapitalize="none"
              pattern="[A-Za-z0-9._@\-]{3,50}"
              required
            />
          )}
        </Field>
        <Field label={t('common.password')} hint={t('common.passwordHint')}>
          {(id) => (
            <Input
              id={id}
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
              minLength={8}
              required
            />
          )}
        </Field>
        <Field label={t('auth.setup.repeatPassword')} error={mismatch ? t('common.passwordMismatch') : null}>
          {(id) => (
            <Input
              id={id}
              type="password"
              value={repeat}
              onChange={(e) => setRepeat(e.target.value)}
              autoComplete="new-password"
              required
            />
          )}
        </Field>
        <div className="mt-1 border-t border-line pt-4">
          <UsageChoice value={usage} onChange={setUsage} required />
        </div>
        {usage === 'BUSINESS' && (
          <Field label={t('license.code')} hint={t('license.codeHint')}>
            {(id) => (
              <Textarea
                id={id}
                value={licenseCode}
                onChange={(e) => setLicenseCode(e.target.value)}
                rows={3}
                spellCheck={false}
                autoCapitalize="characters"
                autoComplete="off"
                placeholder="AIDOCS-XXXXX-XXXXX-…"
                className="font-mono text-xs tracking-wide"
                maxLength={1000}
              />
            )}
          </Field>
        )}
        <Button type="submit" variant="primary" loading={busy} disabled={mismatch} className="mt-1 w-full">
          {t('auth.setup.submit')}
        </Button>
      </form>
    </AuthLayout>
  )
}

/** Für Administratoren ist die Zwei-Faktor-Anmeldung Pflicht: Ohne sie zeigt die Anwendung nur diese Einrichtung. */
export function TwoFactorRequiredPage({ onDone, onLogout }: { onDone: () => void; onLogout: () => void }) {
  const { t } = useTranslation()
  return (
    <AuthLayout title={t('auth.twoFactorRequired.title')} subtitle={t('auth.twoFactorRequired.subtitle')}>
      <TwoFactorSetupFlow
        intro={t('auth.twoFactorRequired.intro')}
        onCancel={onLogout}
        cancelLabel={t('shell.userMenu.logout')}
        onFinished={onDone}
      />
    </AuthLayout>
  )
}
