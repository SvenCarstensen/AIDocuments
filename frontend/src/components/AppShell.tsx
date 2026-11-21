import { useMutation, useQuery } from '@tanstack/react-query'
import { FileUp, KeyRound, Loader2, LogOut, Moon, Search, Settings, Sparkles, Sun, Upload, X } from 'lucide-react'
import { type FormEvent, type ReactNode, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, NavLink, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { toast } from 'sonner'
import { api, type AskResult, errorMessage } from '../lib/api'
import { useAuth } from '../lib/auth'
import { cx } from '../lib/format'
import { useTheme } from '../lib/theme'
import { useUpload } from '../lib/upload'
import { LanguageSwitcher } from './LanguageSwitcher'
import { Alert, Badge, Button, Field, IconButton, Input, Logo, Modal } from './ui'

export function AppShell() {
  const { t } = useTranslation()
  const { isAdmin } = useAuth()
  const upload = useUpload()
  const fileInput = useRef<HTMLInputElement>(null)
  const { dark, toggle } = useTheme()

  return (
    <div className="flex min-h-dvh flex-col">
      <header className="sticky top-0 z-30 border-b border-line bg-surface/85 backdrop-blur-md">
        <div className="mx-auto flex h-16 max-w-[1600px] items-center gap-3 px-4 sm:px-6">
          <Link to="/documents" className="flex shrink-0 items-center gap-2.5 rounded-lg pr-1">
            <Logo />
            <span className="hidden text-[17px] font-semibold tracking-tight md:block">AIDocuments</span>
          </Link>

          <SearchBox />

          <div className="flex shrink-0 items-center gap-1">
            <Button
              variant="primary"
              icon={<Upload className="size-4" aria-hidden />}
              onClick={() => fileInput.current?.click()}
              className="mr-1 max-sm:w-10 max-sm:px-0"
            >
              <span className="max-sm:sr-only">{t('shell.upload')}</span>
            </Button>
            <input
              ref={fileInput}
              type="file"
              accept="application/pdf,.pdf"
              multiple
              hidden
              onChange={(event) => {
                const files = Array.from(event.target.files ?? [])
                event.target.value = ''
                if (files.length > 0) void upload(files)
              }}
            />
            {isAdmin && (
              <NavLink to="/settings" aria-label={t('settings.title')} title={t('settings.title')}>
                {({ isActive }) => (
                  <span
                    className={cx(
                      'inline-flex size-9 items-center justify-center rounded-lg transition-colors',
                      isActive ? 'bg-brand-soft text-brand-ink' : 'text-muted hover:bg-raised hover:text-ink',
                    )}
                  >
                    <Settings className="size-5" />
                  </span>
                )}
              </NavLink>
            )}
            <IconButton label={dark ? t('shell.theme.light') : t('shell.theme.dark')} onClick={toggle}>
              {dark ? <Sun className="size-5" /> : <Moon className="size-5" />}
            </IconButton>
            <UserMenu />
          </div>
        </div>
      </header>

      <main className="mx-auto w-full max-w-[1600px] flex-1 px-4 py-6 sm:px-6">
        <Outlet />
      </main>

      <DropOverlay onFiles={upload} />
    </div>
  )
}

type SearchMode = 'ai' | 'keywords'

function storedSearchMode(): SearchMode {
  try {
    return localStorage.getItem('searchMode') === 'keywords' ? 'keywords' : 'ai'
  } catch {
    return 'ai'
  }
}

/** Baut aus der Deutung der KI die Adresse der Dokumentliste – dieselben Filter, die man auch von Hand setzen kann. */
function askParams(result: AskResult): URLSearchParams {
  const next = new URLSearchParams()
  if (result.text) next.set('q', result.text)
  if (result.category) next.set('category', result.category)
  if (result.correspondent) next.set('correspondent', result.correspondent)
  result.tags.forEach((tag) => next.append('tag', tag))
  if (result.from) next.set('from', result.from)
  if (result.to) next.set('to', result.to)
  next.set('ai', result.question)
  return next
}

/**
 * Suchfeld der Kopfleiste. Der Suchbegriff steht in der Adresse (?q=…), damit Suchen teilbar und per Zurück erreichbar sind.
 * Ist eine KI eingerichtet, versteht das Feld auch Fragen in natürlicher Sprache (KI-Suche, umschaltbar): Die KI übersetzt
 * sie in Filter, gesucht wird danach wie gewohnt.
 */
function SearchBox() {
  const { t } = useTranslation()
  const [params] = useSearchParams()
  const location = useLocation()
  const navigate = useNavigate()
  const system = useQuery({ queryKey: ['system'], queryFn: api.system })
  const [mode, setMode] = useState<SearchMode>(storedSearchMode)
  const aiMode = !!system.data?.aiEnabled && mode === 'ai'
  const onList = location.pathname === '/documents'
  const urlQuery = onList ? ((aiMode ? params.get('ai') : params.get('q')) ?? '') : ''
  const [text, setText] = useState(urlQuery)
  const input = useRef<HTMLInputElement>(null)
  const lastPushed = useRef(urlQuery)

  // Adresse → Feld (z. B. Zurück-Taste oder Klick auf das Logo)
  useEffect(() => {
    if (urlQuery !== lastPushed.current) {
      lastPushed.current = urlQuery
      setText(urlQuery)
    }
  }, [urlQuery])

  function apply(value: string, replace: boolean) {
    const next = new URLSearchParams(onList ? params : undefined)
    const trimmed = value.trim()
    if (trimmed) next.set('q', trimmed)
    else next.delete('q')
    next.delete('ai')
    lastPushed.current = trimmed
    navigate({ pathname: '/documents', search: next.toString() }, { replace: replace && onList })
  }

  const ask = useMutation({
    mutationFn: (question: string) => api.askSearch(question),
    onSuccess: (result) => {
      lastPushed.current = result.question
      navigate({ pathname: '/documents', search: askParams(result).toString() })
    },
    onError: (error, question) => {
      toast.error(`${errorMessage(error)} ${t('shell.search.askFailed')}`)
      apply(question, false)
    },
  })

  // Feld → Adresse, kurz verzögert: Treffer erscheinen schon beim Tippen. Die KI-Suche fragt erst bei Enter.
  useEffect(() => {
    if (aiMode || text.trim() === lastPushed.current) return
    const timer = setTimeout(() => apply(text, true), 280)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, aiMode])

  // "/" springt von überall ins Suchfeld
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target as HTMLElement
      const typing = target.closest('input, textarea, select, [contenteditable=true]')
      if (event.key === '/' && !typing && !event.metaKey && !event.ctrlKey) {
        event.preventDefault()
        input.current?.focus()
        input.current?.select()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  function submit(event: FormEvent) {
    event.preventDefault()
    if (aiMode && text.trim()) ask.mutate(text.trim())
    else apply(text, false)
  }

  function chooseMode(next: SearchMode) {
    setMode(next)
    try {
      localStorage.setItem('searchMode', next)
    } catch {
      // ohne Speicher gilt die Wahl bis zum Neuladen
    }
    input.current?.focus()
  }

  const aiAvailable = !!system.data?.aiEnabled
  return (
    <form
      onSubmit={submit}
      role="search"
      className={cx(
        'mx-auto flex h-11 min-w-0 flex-1 items-center gap-1 rounded-xl border border-line bg-raised pr-1.5 transition-all md:max-w-2xl',
        aiAvailable ? 'pl-1' : 'pl-3.5',
        'focus-within:border-brand focus-within:bg-surface focus-within:ring-3 focus-within:ring-brand/20',
      )}
    >
      {aiAvailable ? (
        // Mit eingerichteter KI: Schiebeschalter „KI“ vorn in der Zeile – an = Fragen an die KI, aus = Stichwortsuche.
        // Beschriftung und Schalter sind ein einziger Knopf: Jeder Klick schaltet genau einmal um (ein umschließendes
        // <label> würde den Klick in manchen Browsern ein zweites Mal an den Schalter weitergeben).
        <button
          type="button"
          role="switch"
          aria-checked={aiMode}
          aria-label={t('shell.search.labelAi')}
          onClick={() => chooseMode(aiMode ? 'keywords' : 'ai')}
          className="flex shrink-0 cursor-pointer items-center gap-2 rounded-lg py-1 pr-2 pl-1 select-none hover:bg-line/40"
        >
          <span
            className={cx('relative h-6 w-11 shrink-0 rounded-full transition-colors', aiMode ? 'bg-brand' : 'bg-line-strong')}
            aria-hidden
          >
            <span
              className={cx(
                'absolute top-0.5 left-0.5 flex size-5 items-center justify-center rounded-full bg-white shadow transition-transform',
                aiMode && 'translate-x-5',
              )}
            >
              {ask.isPending ? (
                <Loader2 className="size-3 animate-spin text-brand" />
              ) : aiMode ? (
                <Sparkles className="size-3 text-brand" />
              ) : (
                <Search className="size-3 text-subtle" />
              )}
            </span>
          </span>
          <span className={cx('hidden text-xs font-semibold sm:inline', aiMode ? 'text-brand-ink' : 'text-muted')} aria-hidden>
            {t('shell.search.labelAi')}
          </span>
        </button>
      ) : (
        <Search className="size-[18px] shrink-0 text-subtle" aria-hidden />
      )}
      <input
        ref={input}
        value={text}
        onChange={(event) => setText(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            setText('')
            input.current?.blur()
          }
        }}
        placeholder={aiMode ? t('shell.search.placeholderAi') : t('shell.search.placeholder')}
        aria-busy={ask.isPending}
        aria-label={t('shell.search.label')}
        className="h-full min-w-0 flex-1 bg-transparent pl-1.5 text-[15px] text-ink placeholder:text-subtle focus:outline-none"
      />
      {text ? (
        <button
          type="button"
          aria-label={t('shell.search.clear')}
          onClick={() => {
            setText('')
            input.current?.focus()
          }}
          className="flex size-7 shrink-0 items-center justify-center rounded-md text-subtle hover:bg-line hover:text-ink"
        >
          <X className="size-4" />
        </button>
      ) : (
        <kbd className="pointer-events-none mr-1.5 hidden shrink-0 rounded-md border border-line bg-surface px-1.5 py-0.5 text-xs font-medium text-subtle sm:block">
          /
        </kbd>
      )}
    </form>
  )
}

function UserMenu() {
  const { t } = useTranslation()
  const { user, isAdmin, logout } = useAuth()
  const [open, setOpen] = useState(false)
  const [passwordOpen, setPasswordOpen] = useState(false)
  const container = useRef<HTMLDivElement>(null)
  const system = useQuery({ queryKey: ['system'], queryFn: api.system })

  useEffect(() => {
    if (!open) return
    function onPointer(event: PointerEvent) {
      if (!container.current?.contains(event.target as Node)) setOpen(false)
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') setOpen(false)
    }
    window.addEventListener('pointerdown', onPointer)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('pointerdown', onPointer)
      window.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div ref={container} className="relative ml-1">
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={t('shell.userMenu.label', { name: user.username })}
        onClick={() => setOpen((value) => !value)}
        className="flex size-9 items-center justify-center rounded-full bg-brand-soft text-sm font-semibold text-brand-ink uppercase ring-brand/30 transition-shadow hover:ring-3"
      >
        {user.username.slice(0, 1)}
      </button>
      {open && (
        <div
          role="menu"
          className="absolute right-0 z-40 mt-2 w-64 animate-rise rounded-xl border border-line bg-surface p-1.5 shadow-pop"
        >
          <div className="px-3 py-2.5">
            <div className="flex items-center gap-2">
              <span className="truncate text-sm font-semibold">{user.username}</span>
              <Badge tone={isAdmin ? 'brand' : 'neutral'}>{t(`common.role.${user.role}`)}</Badge>
            </div>
            {system.data && (
              <p className="mt-1 text-xs text-muted">
                {t('common.documentCount', { count: system.data.documents })} ·{' '}
                {system.data.aiEnabled ? t('shell.userMenu.aiActive') : t('shell.userMenu.aiNotConfigured')}
              </p>
            )}
          </div>
          <div className="my-1 h-px bg-line" />
          <LanguageSwitcher className="px-3 py-1.5" />
          <div className="my-1 h-px bg-line" />
          <MenuItem
            icon={<KeyRound className="size-4" />}
            onClick={() => {
              setOpen(false)
              setPasswordOpen(true)
            }}
          >
            {t('shell.userMenu.changePassword')}
          </MenuItem>
          <MenuItem
            icon={<LogOut className="size-4" />}
            onClick={() => logout().catch((e) => toast.error(errorMessage(e)))}
          >
            {t('shell.userMenu.logout')}
          </MenuItem>
        </div>
      )}
      <ChangePasswordDialog open={passwordOpen} onClose={() => setPasswordOpen(false)} />
    </div>
  )
}

function MenuItem({ icon, onClick, children }: { icon: ReactNode; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      role="menuitem"
      onClick={onClick}
      className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left text-sm text-ink hover:bg-raised"
    >
      <span className="text-muted">{icon}</span>
      {children}
    </button>
  )
}

function ChangePasswordDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [repeat, setRepeat] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const mismatch = repeat.length > 0 && next !== repeat

  useEffect(() => {
    if (open) {
      setCurrent('')
      setNext('')
      setRepeat('')
      setError(null)
    }
  }, [open])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (next !== repeat) return
    setBusy(true)
    setError(null)
    try {
      await api.changePassword(current, next)
      toast.success(t('shell.passwordDialog.changed'))
      onClose()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal open={open} onClose={onClose} title={t('shell.userMenu.changePassword')} size="sm">
      <form onSubmit={submit} className="flex flex-col gap-4">
        {error && <Alert tone="danger">{error}</Alert>}
        <Field label={t('shell.passwordDialog.current')}>
          {(id) => (
            <Input id={id} type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" required autoFocus />
          )}
        </Field>
        <Field label={t('common.newPassword')} hint={t('common.passwordHint')}>
          {(id) => (
            <Input id={id} type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" minLength={8} required />
          )}
        </Field>
        <Field label={t('shell.passwordDialog.repeat')} error={mismatch ? t('common.passwordMismatch') : null}>
          {(id) => (
            <Input id={id} type="password" value={repeat} onChange={(e) => setRepeat(e.target.value)} autoComplete="new-password" required />
          )}
        </Field>
        <div className="mt-1 flex justify-end gap-2">
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" variant="primary" loading={busy} disabled={mismatch}>
            {t('common.save')}
          </Button>
        </div>
      </form>
    </Modal>
  )
}

/** Fängt Dateien ab, die irgendwo ins Fenster gezogen werden, und zeigt dabei eine Ablagefläche. */
function DropOverlay({ onFiles }: { onFiles: (files: File[]) => void }) {
  const { t } = useTranslation()
  const [active, setActive] = useState(false)
  const depth = useRef(0)

  useEffect(() => {
    const hasFiles = (event: DragEvent) => Array.from(event.dataTransfer?.types ?? []).includes('Files')

    function onEnter(event: DragEvent) {
      if (!hasFiles(event)) return
      depth.current += 1
      setActive(true)
    }
    function onOver(event: DragEvent) {
      if (!hasFiles(event)) return
      event.preventDefault()
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
    }
    function onLeave(event: DragEvent) {
      if (!hasFiles(event)) return
      depth.current = Math.max(0, depth.current - 1)
      if (depth.current === 0) setActive(false)
    }
    function onDrop(event: DragEvent) {
      if (!hasFiles(event)) return
      event.preventDefault()
      depth.current = 0
      setActive(false)
      const files = Array.from(event.dataTransfer?.files ?? [])
      if (files.length > 0) onFiles(files)
    }
    window.addEventListener('dragenter', onEnter)
    window.addEventListener('dragover', onOver)
    window.addEventListener('dragleave', onLeave)
    window.addEventListener('drop', onDrop)
    return () => {
      window.removeEventListener('dragenter', onEnter)
      window.removeEventListener('dragover', onOver)
      window.removeEventListener('dragleave', onLeave)
      window.removeEventListener('drop', onDrop)
    }
  }, [onFiles])

  if (!active) return null
  return (
    <div className="pointer-events-none fixed inset-0 z-50 flex animate-fade items-center justify-center bg-page/80 p-6 backdrop-blur-sm">
      <div className="flex w-full max-w-xl flex-col items-center rounded-3xl border-2 border-dashed border-brand bg-surface px-8 py-14 text-center shadow-pop">
        <div className="mb-5 flex size-16 items-center justify-center rounded-2xl bg-brand-soft text-brand-ink">
          <FileUp className="size-8" aria-hidden />
        </div>
        <p className="text-xl font-semibold">{t('shell.dropzone.title')}</p>
        <p className="mt-1.5 text-sm text-muted">{t('shell.dropzone.hint')}</p>
      </div>
    </div>
  )
}
