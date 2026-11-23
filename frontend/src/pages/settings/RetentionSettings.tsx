import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, Pencil, Plus, Trash2 } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Alert, Button, Card, ConfirmDialog, EmptyState, Field, IconButton, Input, Modal, Spinner } from '../../components/ui'
import { api, errorMessage, type RetentionRule } from '../../lib/api'
import { SectionHeader } from './SectionHeader'
import { documentTypeLabel, documentTypeOptions, documentTypeValue } from '../../lib/documentTypes'

export function RetentionSettings() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const rules = useQuery({ queryKey: ['retention'], queryFn: api.retentionRules })
  const [editing, setEditing] = useState<RetentionRule | 'new' | null>(null)
  const [deleting, setDeleting] = useState<RetentionRule | null>(null)

  const remove = useMutation({
    mutationFn: (id: string) => api.deleteRetentionRule(id),
    onSuccess: async () => {
      setDeleting(null)
      toast.success(t('settings.retention.deleted'))
      await queryClient.invalidateQueries({ queryKey: ['retention'] })
    },
    onError: (e) => {
      setDeleting(null)
      toast.error(errorMessage(e))
    },
  })

  return (
    <section>
      <SectionHeader
        title={t('settings.retention.title')}
        action={
          <Button variant="primary" icon={<Plus className="size-4" aria-hidden />} onClick={() => setEditing('new')}>
            {t('settings.retention.add')}
          </Button>
        }
      >
        {t('settings.retention.intro')}
      </SectionHeader>

      <Alert tone="warn" className="mb-4">
        {t('settings.retention.permanent')}
      </Alert>

      {rules.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner />
        </div>
      ) : rules.isError ? (
        <Alert tone="danger">{errorMessage(rules.error)}</Alert>
      ) : rules.data.length === 0 ? (
        <EmptyState icon={<Archive className="size-7" />} title={t('settings.retention.emptyTitle')}>
          {t('settings.retention.emptyText')}
        </EmptyState>
      ) : (
        <Card className="overflow-hidden">
          <ul className="divide-y divide-line">
            {rules.data.map((rule) => (
              <li key={rule.id} className="flex items-center gap-3 px-4 py-3 sm:px-5">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-semibold">{documentTypeLabel(rule.category)}</p>
                  <p className="mt-0.5 text-xs text-muted">
                    {t('settings.retention.years', { count: rule.years })}
                    {' · '}
                    {t('common.documentCount', { count: rule.documentCount })}
                  </p>
                </div>
                <IconButton label={t('settings.retention.edit', { category: documentTypeLabel(rule.category) })} onClick={() => setEditing(rule)}>
                  <Pencil className="size-4" />
                </IconButton>
                <IconButton
                  label={t('settings.retention.delete.label', { category: documentTypeLabel(rule.category) })}
                  onClick={() => setDeleting(rule)}
                  className="hover:!bg-danger-soft hover:!text-danger"
                >
                  <Trash2 className="size-4" />
                </IconButton>
              </li>
            ))}
          </ul>
        </Card>
      )}

      {editing && <RuleDialog rule={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        onConfirm={() => deleting && remove.mutate(deleting.id)}
        loading={remove.isPending}
        title={t('settings.retention.delete.title')}
        confirmLabel={t('common.delete')}
      >
        {t('settings.retention.delete.text', { category: deleting ? documentTypeLabel(deleting.category) : '' })}
      </ConfirmDialog>
    </section>
  )
}

function RuleDialog({ rule, onClose }: { rule: RetentionRule | null; onClose: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const facets = useQuery({ queryKey: ['documents', 'facets'], queryFn: api.facets })
  const [category, setCategory] = useState(rule ? documentTypeLabel(rule.category) : '')
  const [years, setYears] = useState(String(rule?.years ?? 10))
  const shorter = rule !== null && Number(years) < rule.years

  const save = useMutation({
    mutationFn: () => {
      const body = { category: documentTypeValue(category), years: Number(years) }
      return rule ? api.updateRetentionRule(rule.id, body) : api.createRetentionRule(body)
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['retention'] }),
        queryClient.invalidateQueries({ queryKey: ['documents'] }),
        queryClient.invalidateQueries({ queryKey: ['document'] }),
      ])
      toast.success(rule ? t('settings.retention.saved') : t('settings.retention.created'))
      onClose()
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate()
  }

  return (
    <Modal open onClose={onClose} title={rule ? t('settings.retention.edit', { category: documentTypeLabel(rule.category) }) : t('settings.retention.add')}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
        <Field label={t('document.fields.category')} hint={t('settings.retention.dialog.categoryHint')}>
          {(id) => (
            <>
              <Input id={id} value={category} onChange={(e) => setCategory(e.target.value)} list={`${id}-list`} maxLength={100} required autoFocus />
              <datalist id={`${id}-list`}>
                {documentTypeOptions(facets.data?.categories.map((facet) => facet.name)).map((label) => <option key={label} value={label} />)}
              </datalist>
            </>
          )}
        </Field>
        <Field label={t('settings.retention.dialog.years')} hint={t('settings.retention.dialog.yearsHint')}>
          {(id) => <Input id={id} type="number" min={1} max={100} step={1} value={years} onChange={(e) => setYears(e.target.value)} required />}
        </Field>
        {shorter && <Alert tone="warn">{t('settings.retention.dialog.shorterHint')}</Alert>}
        <div className="mt-1 flex justify-end gap-2">
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" variant="primary" loading={save.isPending}>
            {rule ? t('common.save') : t('settings.retention.dialog.create')}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
