import { FileSearch, Inbox, ShieldCheck, Sparkles } from 'lucide-react'
import { type FormEvent, type ReactNode, useState } from 'react'
import { Alert, Button, Field, Input, Logo } from '../components/ui'
import { api, errorMessage, type User } from '../lib/api'

function AuthLayout({ title, subtitle, children, aside }: { title: string; subtitle: string; children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="relative flex min-h-dvh items-center justify-center overflow-hidden px-4 py-10">
      {/* dezenter Farbverlauf als Hintergrund */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(60%_50%_at_50%_0%,var(--brand-soft),transparent_70%)]"
      />
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
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      onDone(await api.login(username, password))
    } catch (e) {
      setError(errorMessage(e))
      setBusy(false)
    }
  }

  return (
    <AuthLayout title="Willkommen zurück" subtitle="Melde dich an, um auf deine Dokumente zuzugreifen.">
      <form onSubmit={submit} className="flex flex-col gap-4">
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label="Benutzername">
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
        <Field label="Passwort">
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
          Anmelden
        </Button>
      </form>
    </AuthLayout>
  )
}

export function SetupPage({ onDone }: { onDone: (user: User) => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [repeat, setRepeat] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const mismatch = repeat.length > 0 && password !== repeat

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (password !== repeat) return
    setBusy(true)
    setError(null)
    try {
      onDone(await api.setup(username, password))
    } catch (e) {
      setError(errorMessage(e))
      setBusy(false)
    }
  }

  const features = [
    { icon: <Inbox className="size-4" />, text: 'PDFs aus Postfach und Eingangsordner' },
    { icon: <Sparkles className="size-4" />, text: 'KI fasst zusammen und verschlagwortet' },
    { icon: <FileSearch className="size-4" />, text: 'Volltextsuche bis ins Dokument' },
  ]

  return (
    <AuthLayout
      title="AIDocuments einrichten"
      subtitle="Lege zuerst das Administratorkonto an. Weitere Benutzer kannst du danach in den Einstellungen hinzufügen."
      aside={
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
      }
    >
      <form onSubmit={submit} className="flex flex-col gap-4">
        <div className="flex items-center gap-2 text-[13px] font-medium text-brand-ink">
          <ShieldCheck className="size-4" aria-hidden />
          Administratorkonto
        </div>
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label="Benutzername" hint="3–50 Zeichen: Buchstaben, Ziffern, Punkt, Bindestrich, Unterstrich, @">
          {(id) => (
            <Input
              id={id}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              autoCapitalize="none"
              pattern="[A-Za-z0-9._@\-]{3,50}"
              autoFocus
              required
            />
          )}
        </Field>
        <Field label="Passwort" hint="Mindestens 8 Zeichen">
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
        <Field label="Passwort wiederholen" error={mismatch ? 'Die Passwörter stimmen nicht überein.' : null}>
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
        <Button type="submit" variant="primary" loading={busy} disabled={mismatch} className="mt-1 w-full">
          Konto anlegen und starten
        </Button>
      </form>
    </AuthLayout>
  )
}
