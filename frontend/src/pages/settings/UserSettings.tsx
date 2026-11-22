import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil, Plus, Trash2 } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Alert, Badge, Button, Card, ConfirmDialog, Field, IconButton, Input, Modal, Select, Spinner, Switch } from '../../components/ui'
import { api, errorMessage, type Role, type User } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { formatRelative } from '../../lib/format'
import { SectionHeader } from './SectionHeader'

export function UserSettings() {
  const { t } = useTranslation()
  const { user: me } = useAuth()
  const queryClient = useQueryClient()
  const users = useQuery({ queryKey: ['users'], queryFn: api.users })
  const [editing, setEditing] = useState<User | 'new' | null>(null)
  const [deleting, setDeleting] = useState<User | null>(null)

  const remove = useMutation({
    mutationFn: (id: string) => api.deleteUser(id),
    onSuccess: async () => {
      setDeleting(null)
      toast.success(t('settings.users.deleted'))
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
        title={t('settings.users.title')}
        action={
          <Button variant="primary" icon={<Plus className="size-4" aria-hidden />} onClick={() => setEditing('new')}>
            {t('settings.users.add')}
          </Button>
        }
      >
        {t('settings.users.intro')}
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
                    <Badge tone={user.role === 'ADMIN' ? 'brand' : 'neutral'}>{t(`common.role.${user.role}`)}</Badge>
                    {user.id === me.id && <Badge>{t('settings.users.you')}</Badge>}
                    {!user.enabled && <Badge tone="warn">{t('settings.users.disabled')}</Badge>}
                    {user.twoFactorEnabled && <Badge tone="ok">{t('settings.users.twoFactor')}</Badge>}
                  </div>
                  <p className="mt-0.5 text-xs text-muted">
                    {user.lastLoginAt ? t('settings.users.lastLogin', { when: formatRelative(user.lastLoginAt) }) : t('settings.users.neverLoggedIn')}
                    {user.email && <> · {user.email}</>}
                  </p>
                </div>
                <IconButton label={t('settings.users.editUser', { name: user.username })} onClick={() => setEditing(user)}>
                  <Pencil className="size-4" />
                </IconButton>
                <IconButton
                  label={t('settings.users.deleteUser', { name: user.username })}
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
        title={t('settings.users.delete.title')}
        confirmLabel={t('common.delete')}
      >
        {t('settings.users.delete.text', { name: deleting?.username ?? '' })}
      </ConfirmDialog>
    </section>
  )
}

function UserDialog({ user, isSelf, onClose }: { user: User | null; isSelf: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [username, setUsername] = useState(user?.username ?? '')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<Role>(user?.role ?? 'USER')
  const [enabled, setEnabled] = useState(user?.enabled ?? true)
  const [confirmReset, setConfirmReset] = useState(false)

  const resetTwoFactor = useMutation({
    mutationFn: () => api.resetTwoFactor(user!.id),
    onSuccess: async () => {
      setConfirmReset(false)
      await queryClient.invalidateQueries({ queryKey: ['users'] })
      toast.success(t('settings.users.twoFactorReset'))
      onClose()
    },
    onError: (e) => {
      setConfirmReset(false)
      toast.error(errorMessage(e))
    },
  })

  const save = useMutation({
    mutationFn: () =>
      user
        ? api.updateUser(user.id, { role, enabled, password: password || null })
        : api.createUser({ username: username.trim(), password, role }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['users'] })
      toast.success(user ? t('settings.users.saved') : t('settings.users.created'))
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
    <Modal open onClose={onClose} title={user ? t('settings.users.editUser', { name: user.username }) : t('settings.users.add')}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
        {isSelf && <Alert tone="brand">{t('settings.users.dialog.selfNotice')}</Alert>}
        {!user && (
          <Field label={t('common.username')} hint={t('common.usernameHint')}>
            {(id) => (
              <Input id={id} value={username} onChange={(e) => setUsername(e.target.value)} pattern="[A-Za-z0-9._@\-]{3,50}" autoComplete="off" autoCapitalize="none" autoFocus required />
            )}
          </Field>
        )}
        <Field label={user ? t('common.newPassword') : t('common.password')} hint={user ? t('settings.users.dialog.passwordKeepHint') : t('common.passwordHint')}>
          {(id) => (
            <Input id={id} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" minLength={8} required={!user} />
          )}
        </Field>
        <Field label={t('settings.users.dialog.role')} hint={t(`settings.users.dialog.roleHints.${role}`)}>
          {(id) => (
            <Select id={id} value={role} onChange={(e) => setRole(e.target.value as Role)}>
              <option value="USER">{t('common.role.USER')}</option>
              <option value="ADMIN">{t('common.role.ADMIN')}</option>
            </Select>
          )}
        </Field>
        {user && (
          <Switch checked={enabled} onChange={setEnabled} label={t('settings.users.dialog.active')} description={t('settings.users.dialog.activeHint')} />
        )}
        {user?.twoFactorEnabled && !isSelf && (
          <div className="flex items-center justify-between gap-3 rounded-lg border border-line px-3 py-2.5">
            <p className="text-sm text-muted">{t('settings.users.resetTwoFactorHint')}</p>
            <Button onClick={() => setConfirmReset(true)}>{t('settings.users.resetTwoFactor')}</Button>
          </div>
        )}
        <div className="mt-1 flex justify-end gap-2">
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" variant="primary" loading={save.isPending}>
            {user ? t('common.save') : t('settings.users.dialog.create')}
          </Button>
        </div>
      </form>
      <ConfirmDialog
        open={confirmReset}
        onClose={() => setConfirmReset(false)}
        onConfirm={() => resetTwoFactor.mutate()}
        loading={resetTwoFactor.isPending}
        title={t('settings.users.resetTwoFactor')}
        confirmLabel={t('settings.users.resetTwoFactorConfirm')}
      >
        {t('settings.users.resetTwoFactorText', { name: user?.username ?? '' })}
      </ConfirmDialog>
    </Modal>
  )
}
