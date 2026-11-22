import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil, Plus, Trash2 } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Alert, Badge, Button, Card, ConfirmDialog, Field, IconButton, Input, Modal, Select, Spinner, Switch } from '../../components/ui'
import { api, type Area, type AreaMember, errorMessage, type Permission, PERMISSIONS } from '../../lib/api'
import { areaName, useAreas } from '../../lib/areas'
import { SectionHeader } from './SectionHeader'

export function AreaSettings() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const areas = useAreas()
  const users = useQuery({ queryKey: ['users'], queryFn: api.users })
  const [editing, setEditing] = useState<Area | 'new' | null>(null)
  const [deleting, setDeleting] = useState<Area | null>(null)

  const remove = useMutation({
    mutationFn: (id: string) => api.deleteArea(id),
    onSuccess: async () => {
      setDeleting(null)
      toast.success(t('settings.areas.deleted'))
      await queryClient.invalidateQueries({ queryKey: ['areas'] })
    },
    onError: (e) => {
      setDeleting(null)
      toast.error(errorMessage(e))
    },
  })

  const userName = (id: string) => users.data?.find((u) => u.id === id)?.username

  return (
    <section>
      <SectionHeader
        title={t('settings.areas.title')}
        action={
          <Button variant="primary" icon={<Plus className="size-4" aria-hidden />} onClick={() => setEditing('new')}>
            {t('settings.areas.add')}
          </Button>
        }
      >
        {t('settings.areas.intro')}
      </SectionHeader>

      {areas.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner />
        </div>
      ) : areas.isError ? (
        <Alert tone="danger">{errorMessage(areas.error)}</Alert>
      ) : (
        <Card className="overflow-hidden">
          <ul className="divide-y divide-line">
            {areas.data.map((area) => {
              const members = area.members
                .map((m) => {
                  const name = userName(m.userId)
                  return name && t('settings.areas.memberWithAccess', { name, access: t(`areas.access.${m.access}`) })
                })
                .filter(Boolean) as string[]
              const parts = [
                area.everyone && `${t('settings.areas.visibleForAll')} (${t(`areas.access.${area.everyoneAccess}`)})`,
                members.length > 0 && t('settings.areas.members', { names: members.join(', ') }),
                !area.everyone && members.length === 0 && t('settings.areas.adminsOnly'),
              ].filter(Boolean)
              return (
                <li key={area.id} className="flex items-center gap-3 px-4 py-3 sm:px-5">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                      <span className="truncate text-sm font-semibold">{areaName(area)}</span>
                      {area.everyone ? <Badge tone="brand">{t('settings.areas.everyone')}</Badge> : <Badge>{t('settings.areas.restricted')}</Badge>}
                    </div>
                    <p className="mt-0.5 text-xs text-muted">
                      {[t('common.documentCount', { count: area.documentCount }), ...parts].join(' · ')}
                    </p>
                  </div>
                  <IconButton label={t('settings.areas.edit', { name: areaName(area) })} onClick={() => setEditing(area)}>
                    <Pencil className="size-4" />
                  </IconButton>
                  <IconButton
                    label={t('settings.areas.delete.label', { name: areaName(area) })}
                    onClick={() => setDeleting(area)}
                    disabled={area.id === 'general'}
                    className="hover:!bg-danger-soft hover:!text-danger"
                  >
                    <Trash2 className="size-4" />
                  </IconButton>
                </li>
              )
            })}
          </ul>
        </Card>
      )}
      <p className="mt-3 text-xs leading-relaxed text-muted">{t('settings.areas.adminHint')}</p>

      {editing && <AreaDialog area={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        onConfirm={() => deleting && remove.mutate(deleting.id)}
        loading={remove.isPending}
        title={t('settings.areas.delete.title')}
        confirmLabel={t('common.delete')}
      >
        {t('settings.areas.delete.text', { name: deleting ? areaName(deleting) : '' })}
      </ConfirmDialog>
    </section>
  )
}

function AreaDialog({ area, onClose }: { area: Area | null; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const users = useQuery({ queryKey: ['users'], queryFn: api.users })
  const general = area?.id === 'general'
  const [name, setName] = useState(area?.name ?? '')
  const [everyone, setEveryone] = useState(area?.everyone ?? false)
  const [everyoneAccess, setEveryoneAccess] = useState<Permission>(area?.everyoneAccess ?? 'EDIT')
  const [members, setMembers] = useState<AreaMember[]>(area?.members ?? [])

  const save = useMutation({
    mutationFn: () => {
      const body = { name: name.trim(), everyone, everyoneAccess, members }
      return area ? api.updateArea(area.id, body) : api.createArea(body)
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['areas'] }),
        queryClient.invalidateQueries({ queryKey: ['documents'] }),
      ])
      toast.success(area ? t('settings.areas.saved') : t('settings.areas.created'))
      onClose()
    },
  })

  function toggle(userId: string, on: boolean) {
    setMembers((list) => (on ? [...list, { userId, access: 'EDIT' }] : list.filter((m) => m.userId !== userId)))
  }
  function setAccess(userId: string, access: Permission) {
    setMembers((list) => list.map((m) => (m.userId === userId ? { ...m, access } : m)))
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate()
  }

  // Admins sehen ohnehin alles – zur Auswahl stehen nur die übrigen Benutzer.
  const candidates = (users.data ?? []).filter((u) => u.role !== 'ADMIN')

  return (
    <Modal open onClose={onClose} title={area ? t('settings.areas.edit', { name: areaName(area) }) : t('settings.areas.add')}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
        <Field label={t('settings.areas.dialog.name')} hint={general ? t('settings.areas.dialog.generalNameHint') : undefined}>
          {(id) => (
            <Input
              id={id}
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={general ? t('areas.general') : t('settings.areas.dialog.namePlaceholder')}
              maxLength={80}
              required={!general}
              autoFocus
            />
          )}
        </Field>
        <Switch checked={everyone} onChange={setEveryone} label={t('settings.areas.dialog.everyone')} description={t('settings.areas.dialog.everyoneHint')} />
        {everyone && (
          <Field label={t('settings.areas.dialog.everyoneAccess')}>
            {(id) => (
              <Select id={id} value={everyoneAccess} onChange={(e) => setEveryoneAccess(e.target.value as Permission)}>
                {PERMISSIONS.map((p) => (
                  <option key={p} value={p}>
                    {t(`areas.access.${p}`)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        )}
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium">{t('settings.areas.dialog.members')}</legend>
          {users.isPending ? (
            <Spinner />
          ) : candidates.length === 0 ? (
            <p className="text-sm text-muted">{t('settings.areas.dialog.noUsers')}</p>
          ) : (
            <ul className="flex max-h-64 flex-col gap-1 overflow-y-auto rounded-lg border border-line p-2">
              {candidates.map((u) => {
                const member = members.find((m) => m.userId === u.id)
                return (
                  <li key={u.id} className="flex items-center gap-2 rounded-md pr-1 hover:bg-raised">
                    <label className="flex min-w-0 flex-1 cursor-pointer items-center gap-3 px-2 py-1.5 text-sm">
                      <input
                        type="checkbox"
                        className="size-4 accent-brand"
                        checked={member !== undefined}
                        onChange={(e) => toggle(u.id, e.target.checked)}
                      />
                      <span className="truncate">{u.username}</span>
                      {!u.enabled && <Badge tone="warn">{t('settings.users.disabled')}</Badge>}
                    </label>
                    {member && (
                      <Select
                        aria-label={t('settings.areas.dialog.accessOf', { name: u.username })}
                        value={member.access}
                        onChange={(e) => setAccess(u.id, e.target.value as Permission)}
                        className="!h-8 !w-auto !py-0 text-[13px]"
                      >
                        {PERMISSIONS.map((p) => (
                          <option key={p} value={p}>
                            {t(`areas.access.${p}`)}
                          </option>
                        ))}
                      </Select>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
          <p className="text-xs text-muted">
            {everyone ? t('settings.areas.dialog.membersHintEveryone') : t('settings.areas.dialog.membersHint')}
          </p>
        </fieldset>
        <div className="mt-1 flex justify-end gap-2">
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" variant="primary" loading={save.isPending}>
            {area ? t('common.save') : t('settings.areas.dialog.create')}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
