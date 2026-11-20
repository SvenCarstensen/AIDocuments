import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil, Plus, Trash2 } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { toast } from 'sonner'
import { Alert, Badge, Button, Card, ConfirmDialog, Field, IconButton, Input, Modal, Select, Spinner, Switch } from '../../components/ui'
import { api, errorMessage, type Role, type User } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { formatRelative } from '../../lib/format'
import { SectionHeader } from './SectionHeader'

const roleHints: Record<Role, string> = {
  ADMIN: 'Darf alles – auch Einstellungen ändern, Benutzer verwalten und Dokumente löschen.',
  USER: 'Darf Dokumente ansehen, durchsuchen, hochladen und ihre Angaben bearbeiten.',
}

export function UserSettings() {
  const { user: me } = useAuth()
  const queryClient = useQueryClient()
  const users = useQuery({ queryKey: ['users'], queryFn: api.users })
  const [editing, setEditing] = useState<User | 'new' | null>(null)
  const [deleting, setDeleting] = useState<User | null>(null)

  const remove = useMutation({
    mutationFn: (id: string) => api.deleteUser(id),
    onSuccess: async () => {
      setDeleting(null)
      toast.success('Benutzer gelöscht')
      await queryClient.invalidateQueries({ queryKey: ['users'] })
    },
    onError: (e) => {
      setDeleting(null)
      toast.error(errorMessage(e))
    },
  })

  return (
    <section>
      <SectionHeader
        title="Benutzer"
        action={
          <Button variant="primary" icon={<Plus className="size-4" aria-hidden />} onClick={() => setEditing('new')}>
            Benutzer anlegen
          </Button>
        }
      >
        Jede Person meldet sich mit einem eigenen Konto an. Administratoren verwalten die Anwendung, Benutzer arbeiten
        mit den Dokumenten.
      </SectionHeader>

      {users.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner />
        </div>
      ) : users.isError ? (
        <Alert tone="danger">{errorMessage(users.error)}</Alert>
      ) : (
        <Card className="overflow-hidden">
          <ul className="divide-y divide-line">
            {users.data.map((user) => (
              <li key={user.id} className="flex items-center gap-3 px-4 py-3 sm:px-5">
                <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-brand-soft text-sm font-semibold text-brand-ink uppercase">
                  {user.username.slice(0, 1)}
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <span className="truncate text-sm font-semibold">{user.username}</span>
                    <Badge tone={user.role === 'ADMIN' ? 'brand' : 'neutral'}>{user.role === 'ADMIN' ? 'Admin' : 'Benutzer'}</Badge>
                    {user.id === me.id && <Badge>Du</Badge>}
                    {!user.enabled && <Badge tone="warn">Deaktiviert</Badge>}
                  </div>
                  <p className="mt-0.5 text-xs text-muted">
                    {user.lastLoginAt ? `Zuletzt angemeldet ${formatRelative(user.lastLoginAt)}` : 'Noch nie angemeldet'}
                  </p>
                </div>
                <IconButton label={`${user.username} bearbeiten`} onClick={() => setEditing(user)}>
                  <Pencil className="size-4" />
                </IconButton>
                <IconButton
                  label={`${user.username} löschen`}
                  onClick={() => setDeleting(user)}
                  disabled={user.id === me.id}
                  className="hover:!bg-danger-soft hover:!text-danger"
                >
                  <Trash2 className="size-4" />
                </IconButton>
              </li>
            ))}
          </ul>
        </Card>
      )}

      {editing && <UserDialog user={editing === 'new' ? null : editing} isSelf={editing !== 'new' && editing.id === me.id} onClose={() => setEditing(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        onConfirm={() => deleting && remove.mutate(deleting.id)}
        loading={remove.isPending}
        title="Benutzer löschen?"
        confirmLabel="Löschen"
      >
        Das Konto „{deleting?.username}“ wird gelöscht und sofort abgemeldet. Dokumente bleiben erhalten.
      </ConfirmDialog>
    </section>
  )
}

function UserDialog({ user, isSelf, onClose }: { user: User | null; isSelf: boolean; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [username, setUsername] = useState(user?.username ?? '')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<Role>(user?.role ?? 'USER')
  const [enabled, setEnabled] = useState(user?.enabled ?? true)

  const save = useMutation({
    mutationFn: () =>
      user
        ? api.updateUser(user.id, { role, enabled, password: password || null })
        : api.createUser({ username: username.trim(), password, role }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['users'] })
      toast.success(user ? 'Benutzer gespeichert' : 'Benutzer angelegt')
      onClose()
      if (isSelf) {
        // Eigene Rolle oder eigenes Passwort geändert: Die Sitzung ist damit beendet.
        window.dispatchEvent(new Event('auth:expired'))
      }
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate()
  }

  return (
    <Modal open onClose={onClose} title={user ? `${user.username} bearbeiten` : 'Benutzer anlegen'}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
        {isSelf && <Alert tone="brand">Das ist dein eigenes Konto. Nach dem Speichern musst du dich neu anmelden.</Alert>}
        {!user && (
          <Field label="Benutzername" hint="3–50 Zeichen: Buchstaben, Ziffern, Punkt, Bindestrich, Unterstrich, @">
            {(id) => (
              <Input id={id} value={username} onChange={(e) => setUsername(e.target.value)} pattern="[A-Za-z0-9._@\-]{3,50}" autoComplete="off" autoCapitalize="none" autoFocus required />
            )}
          </Field>
        )}
        <Field label={user ? 'Neues Passwort' : 'Passwort'} hint={user ? 'Leer lassen, um das Passwort nicht zu ändern. Sonst mindestens 8 Zeichen.' : 'Mindestens 8 Zeichen'}>
          {(id) => (
            <Input id={id} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" minLength={8} required={!user} />
          )}
        </Field>
        <Field label="Rolle" hint={roleHints[role]}>
          {(id) => (
            <Select id={id} value={role} onChange={(e) => setRole(e.target.value as Role)}>
              <option value="USER">Benutzer</option>
              <option value="ADMIN">Admin</option>
            </Select>
          )}
        </Field>
        {user && (
          <Switch checked={enabled} onChange={setEnabled} label="Konto aktiv" description="Deaktivierte Konten können sich nicht anmelden; laufende Sitzungen enden sofort." />
        )}
        <div className="mt-1 flex justify-end gap-2">
          <Button onClick={onClose}>Abbrechen</Button>
          <Button type="submit" variant="primary" loading={save.isPending}>
            {user ? 'Speichern' : 'Anlegen'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
