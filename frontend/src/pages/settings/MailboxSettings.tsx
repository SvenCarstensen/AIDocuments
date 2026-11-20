import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, Mail, Pencil, Plus, RefreshCw, Trash2, XCircle } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { toast } from 'sonner'
import { Alert, Badge, Button, Card, ConfirmDialog, EmptyState, Field, IconButton, Input, Modal, Segmented, Spinner, Switch } from '../../components/ui'
import { api, errorMessage, type MailAccount, type MailAccountInput, type MailSecurity, type TestResult } from '../../lib/api'
import { formatRelative, plural } from '../../lib/format'
import { SectionHeader } from './SectionHeader'

export function MailboxSettings() {
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
      toast.success('Postfach entfernt')
      await queryClient.invalidateQueries({ queryKey: ['mail-accounts'] })
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  return (
    <section>
      <SectionHeader
        title="Postfächer"
        action={
          <Button variant="primary" icon={<Plus className="size-4" aria-hidden />} onClick={() => setEditing('new')}>
            Postfach hinzufügen
          </Button>
        }
      >
        Aus diesen IMAP-Postfächern werden PDF-Anhänge automatisch übernommen. Die Postfächer werden nur gelesen –
        E-Mails bleiben unverändert und ungelesen.
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
            title="Noch kein Postfach eingerichtet"
            action={
              <Button variant="primary" icon={<Plus className="size-4" aria-hidden />} onClick={() => setEditing('new')}>
                Postfach hinzufügen
              </Button>
            }
          >
            Trage die Zugangsdaten eines IMAP-Postfachs ein – zum Beispiel das Postfach, an das dein Scanner
            Dokumente schickt.
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
                    {!account.enabled && <Badge>Pausiert</Badge>}
                    <Badge tone="neutral">{plural(account.documentCount, 'Dokument', 'Dokumente')}</Badge>
                  </div>
                  <p className="mt-0.5 truncate text-sm text-muted">
                    {account.username} · {account.host}:{account.port} · {account.folder}
                  </p>
                  <SyncState account={account} />
                </div>
                <div className="flex items-center gap-1">
                  <Button
                    size="sm"
                    icon={<RefreshCw className="size-3.5" aria-hidden />}
                    loading={account.lastSyncStatus === 'RUNNING' || (sync.isPending && sync.variables === account.id)}
                    onClick={() => sync.mutate(account.id)}
                  >
                    Jetzt abrufen
                  </Button>
                  <IconButton label="Bearbeiten" onClick={() => setEditing(account)}>
                    <Pencil className="size-4" />
                  </IconButton>
                  <IconButton label="Entfernen" onClick={() => setDeleting(account)} className="hover:!bg-danger-soft hover:!text-danger">
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
        title="Postfach entfernen?"
        confirmLabel="Entfernen"
      >
        „{deleting?.name}“ wird nicht mehr abgerufen. Bereits übernommene Dokumente bleiben erhalten.
      </ConfirmDialog>
    </section>
  )
}

function SyncState({ account }: { account: MailAccount }) {
  if (account.lastSyncStatus === 'RUNNING') {
    return <p className="mt-2 text-[13px] text-brand-ink">Postfach wird abgerufen …</p>
  }
  if (!account.lastSyncAt) {
    return <p className="mt-2 text-[13px] text-muted">Noch nicht abgerufen.</p>
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

const securityOptions: Array<{ value: MailSecurity; label: string }> = [
  { value: 'SSL', label: 'SSL/TLS' },
  { value: 'STARTTLS', label: 'STARTTLS' },
  { value: 'NONE', label: 'Keine' },
]
const defaultPorts: Record<MailSecurity, number> = { SSL: 993, STARTTLS: 143, NONE: 143 }

function MailboxDialog({ account, onClose }: { account: MailAccount | null; onClose: () => void }) {
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
  const [test, setTest] = useState<TestResult | null>(null)

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
  })

  const save = useMutation({
    mutationFn: () => (account ? api.updateMailAccount(account.id, input()) : api.createMailAccount(input())),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['mail-accounts'] })
      toast.success(account ? 'Postfach gespeichert' : 'Postfach hinzugefügt', {
        description: !account && enabled ? 'Der erste Abruf läuft bereits.' : undefined,
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

  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      title={account ? 'Postfach bearbeiten' : 'Postfach hinzufügen'}
      description="Die Zugangsdaten bekommst du von deinem E-Mail-Anbieter. Das Passwort wird verschlüsselt gespeichert."
    >
      <form onSubmit={submit} className="flex flex-col gap-4">
        {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
        <Field label="Bezeichnung" hint="Frei wählbar, z. B. „Scanner“ oder „Rechnungen“">
          {(id) => <Input id={id} value={name} onChange={(e) => setName(e.target.value)} maxLength={120} placeholder="Scanner" autoFocus />}
        </Field>
        <div className="grid gap-4 sm:grid-cols-[minmax(0,1fr)_7rem]">
          <Field label="IMAP-Server">
            {(id) => <Input id={id} value={host} onChange={(e) => setHost(e.target.value)} placeholder="imap.example.com" autoCapitalize="none" spellCheck={false} required />}
          </Field>
          <Field label="Port">
            {(id) => <Input id={id} type="number" min={1} max={65535} value={port} onChange={(e) => setPort(e.target.value)} required />}
          </Field>
        </div>
        <div className="flex flex-col gap-1.5">
          <span className="text-[13px] font-medium">Verschlüsselung</span>
          <Segmented label="Verschlüsselung" value={security} onChange={changeSecurity} options={securityOptions} />
          {security === 'NONE' && (
            <p className="text-[13px] text-warn">Ohne Verschlüsselung werden Benutzername und Passwort im Klartext übertragen.</p>
          )}
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Benutzername">
            {(id) => <Input id={id} value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" autoCapitalize="none" spellCheck={false} required />}
          </Field>
          <Field label="Passwort" hint={account ? 'Leer lassen, um das gespeicherte Passwort zu behalten' : undefined}>
            {(id) => (
              <Input id={id} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required={!account} placeholder={account ? '••••••••' : ''} />
            )}
          </Field>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Ordner" hint="Meist INBOX (Posteingang)">
            {(id) => <Input id={id} value={folder} onChange={(e) => setFolder(e.target.value)} spellCheck={false} required />}
          </Field>
          <Field label="Nur E-Mails ab" hint="Optional – ältere E-Mails werden übersprungen">
            {(id) => <Input id={id} type="date" value={importSince} onChange={(e) => setImportSince(e.target.value)} />}
          </Field>
        </div>
        <Switch checked={enabled} onChange={setEnabled} label="Automatisch abrufen" description="Das Postfach wird im eingestellten Intervall auf neue PDFs geprüft." />

        {test && <Alert tone={test.ok ? 'ok' : 'danger'}>{test.message}</Alert>}

        <div className="mt-1 flex flex-wrap items-center gap-2">
          <Button onClick={() => runTest.mutate()} loading={runTest.isPending} disabled={!complete}>
            Verbindung testen
          </Button>
          <div className="ml-auto flex gap-2">
            <Button onClick={onClose}>Abbrechen</Button>
            <Button type="submit" variant="primary" loading={save.isPending}>
              {account ? 'Speichern' : 'Hinzufügen'}
            </Button>
          </div>
        </div>
      </form>
    </Modal>
  )
}
