import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileSignature, Pencil, Trash2 } from 'lucide-react'
import { type FormEvent, type ReactNode, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { api, canEdit, type Contract, type ContractStatus, type Doc, errorMessage } from '../lib/api'
import { useAuth } from '../lib/auth'
import { formatLongDate } from '../lib/format'
import { Alert, Badge, Button, Card, ConfirmDialog, Field, IconButton, Input, Modal, Select, Switch } from './ui'

const STATUS_TONE: Record<ContractStatus, 'brand' | 'warn' | 'danger' | 'neutral' | 'ok'> = {
  ACTIVE: 'ok',
  DUE_SOON: 'warn',
  NOTICE_MISSED: 'danger',
  CANCELLED: 'neutral',
  ENDED: 'neutral',
  OPEN_ENDED: 'brand',
}

export function ContractStatusBadge({ status }: { status: ContractStatus }) {
  const { t } = useTranslation()
  return <Badge tone={STATUS_TONE[status]}>{t(`contracts.status.${status}`)}</Badge>
}

/** „12 Monate“, „1 Monat“ – oder ein Strich, wenn nichts angegeben ist. */
function Months({ value }: { value: number | null }) {
  const { t } = useTranslation()
  return <>{value ? t('contracts.months', { count: value }) : '–'}</>
}

function refreshAll(queryClient: ReturnType<typeof useQueryClient>, documentId: string) {
  return Promise.all(
    [['contract', documentId], ['contracts'], ['document-reminders'], ['reminders']].map((queryKey) =>
      queryClient.invalidateQueries({ queryKey }),
    ),
  )
}

/** Vertragsangaben in der Dokumentansicht – oder, mit Bearbeitungsrecht, der Weg, sie zu erfassen. */
export function ContractCard({ doc }: { doc: Doc }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const contract = useQuery({ queryKey: ['contract', doc.id], queryFn: () => api.documentContract(doc.id) })
  const [editing, setEditing] = useState(false)
  const [removing, setRemoving] = useState(false)
  const editable = canEdit(doc)

  const remove = useMutation({
    mutationFn: () => api.deleteContract(doc.id),
    onSuccess: async () => {
      setRemoving(false)
      await refreshAll(queryClient, doc.id)
      toast.success(t('contracts.removed'))
    },
    onError: (e) => {
      setRemoving(false)
      toast.error(errorMessage(e))
    },
  })

  if (contract.isPending || contract.isError) return null
  const data = contract.data
  if (!data && !editable) return null

  return (
    <Card className="p-4">
      <div className="mb-2 flex items-center gap-2">
        <FileSignature className="size-4 text-brand" aria-hidden />
        <h2 className="mr-auto text-sm font-semibold">{t('contracts.cardTitle')}</h2>
        {data && <ContractStatusBadge status={data.status} />}
        {data && editable && (
          <>
            <IconButton label={t('contracts.edit')} onClick={() => setEditing(true)} className="!size-8">
              <Pencil className="size-4" />
            </IconButton>
            <IconButton label={t('contracts.remove.label')} onClick={() => setRemoving(true)} className="!size-8 hover:!bg-danger-soft hover:!text-danger">
              <Trash2 className="size-4" />
            </IconButton>
          </>
        )}
      </div>
      {data ? (
        <ContractFacts contract={data} />
      ) : (
        <div className="flex flex-wrap items-center gap-3">
          <p className="flex-1 text-sm text-muted">{t('contracts.none')}</p>
          <Button size="sm" onClick={() => setEditing(true)}>
            {t('contracts.add')}
          </Button>
        </div>
      )}
      {editing && <ContractDialog doc={doc} contract={data ?? null} onClose={() => setEditing(false)} />}
      <ConfirmDialog
        open={removing}
        onClose={() => setRemoving(false)}
        onConfirm={() => remove.mutate()}
        loading={remove.isPending}
        title={t('contracts.remove.title')}
        confirmLabel={t('common.delete')}
      >
        {t('contracts.remove.text')}
      </ConfirmDialog>
    </Card>
  )
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-muted">{label}</dt>
      <dd className="min-w-0 font-medium [overflow-wrap:anywhere]">{children}</dd>
    </>
  )
}

function ContractFacts({ contract }: { contract: Contract }) {
  const { t } = useTranslation()
  return (
    <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1.5 text-sm">
      {contract.partner && <Fact label={t('contracts.fields.partner')}>{contract.partner}</Fact>}
      {contract.startDate && <Fact label={t('contracts.fields.startDate')}>{formatLongDate(contract.startDate)}</Fact>}
      <Fact label={t('contracts.fields.endDate')}>{contract.endDate ? formatLongDate(contract.endDate) : t('contracts.openEnded')}</Fact>
      {contract.renewalMonths && (
        <Fact label={t('contracts.fields.renewal')}>
          <Months value={contract.renewalMonths} />
        </Fact>
      )}
      {contract.noticeMonths && (
        <Fact label={t('contracts.fields.notice')}>
          <Months value={contract.noticeMonths} />
        </Fact>
      )}
      {contract.noticeDeadline && !contract.cancelled && (
        <Fact label={t('contracts.fields.deadline')}>{formatLongDate(contract.noticeDeadline)}</Fact>
      )}
      {contract.amount && <Fact label={t('contracts.fields.amount')}>{contract.amount}</Fact>}
    </dl>
  )
}

const NUMBER = (value: string) => (value.trim() === '' ? null : Math.max(0, Math.round(Number(value))))

function ContractDialog({ doc, contract, onClose }: { doc: Doc; contract: Contract | null; onClose: () => void }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const assignees = useQuery({ queryKey: ['reminder-assignees', doc.id], queryFn: () => api.reminderAssignees(doc.id) })
  const [partner, setPartner] = useState(contract?.partner ?? doc.correspondent ?? '')
  const [startDate, setStartDate] = useState(contract?.startDate ?? '')
  const [endDate, setEndDate] = useState(contract?.endDate ?? '')
  const [noticeMonths, setNoticeMonths] = useState(contract?.noticeMonths?.toString() ?? '')
  const [renewalMonths, setRenewalMonths] = useState(contract?.renewalMonths?.toString() ?? '12')
  const [amount, setAmount] = useState(contract?.amount ?? '')
  const [reminderDays, setReminderDays] = useState((contract?.reminderDays ?? 14).toString())
  const [cancelled, setCancelled] = useState(contract?.cancelled ?? false)
  const [assigneeId, setAssigneeId] = useState(contract?.assigneeId ?? user.id)

  const save = useMutation({
    mutationFn: () =>
      api.saveContract(doc.id, {
        partner: partner.trim() || null,
        startDate: startDate || null,
        endDate: endDate || null,
        noticeMonths: NUMBER(noticeMonths),
        renewalMonths: NUMBER(renewalMonths),
        amount: amount.trim() || null,
        reminderDays: NUMBER(reminderDays) ?? 14,
        cancelled,
        assigneeId: assigneeId || null,
      }),
    onSuccess: async () => {
      await refreshAll(queryClient, doc.id)
      toast.success(t('contracts.saved'))
      onClose()
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate()
  }

  return (
    <Modal open onClose={onClose} title={contract ? t('contracts.edit') : t('contracts.add')} description={t('contracts.intro')} size="lg">
      <form onSubmit={submit} className="flex flex-col gap-4">
        {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t('contracts.fields.partner')}>
            {(id) => <Input id={id} value={partner} onChange={(e) => setPartner(e.target.value)} maxLength={255} />}
          </Field>
          <Field label={t('contracts.fields.amount')} hint={t('contracts.amountHint')}>
            {(id) => <Input id={id} value={amount} onChange={(e) => setAmount(e.target.value)} maxLength={100} />}
          </Field>
          <Field label={t('contracts.fields.startDate')}>
            {(id) => <Input id={id} type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />}
          </Field>
          <Field label={t('contracts.fields.endDate')} hint={t('contracts.endDateHint')}>
            {(id) => <Input id={id} type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} />}
          </Field>
          <Field label={t('contracts.fields.notice')} hint={t('contracts.noticeHint')}>
            {(id) => <Input id={id} type="number" min={0} max={120} value={noticeMonths} onChange={(e) => setNoticeMonths(e.target.value)} />}
          </Field>
          <Field label={t('contracts.fields.renewal')} hint={t('contracts.renewalHint')}>
            {(id) => <Input id={id} type="number" min={0} max={120} value={renewalMonths} onChange={(e) => setRenewalMonths(e.target.value)} />}
          </Field>
          <Field label={t('contracts.fields.reminderDays')}>
            {(id) => <Input id={id} type="number" min={0} max={365} value={reminderDays} onChange={(e) => setReminderDays(e.target.value)} />}
          </Field>
          <Field label={t('reminders.assignee')}>
            {(id) => (
              <Select id={id} value={assigneeId ?? ''} onChange={(e) => setAssigneeId(e.target.value)}>
                {(assignees.data ?? [{ id: user.id, username: user.username }]).map((person) => (
                  <option key={person.id} value={person.id}>
                    {person.id === user.id ? t('reminders.me', { name: person.username }) : person.username}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
        <Switch checked={cancelled} onChange={setCancelled} label={t('contracts.fields.cancelled')} description={t('contracts.cancelledHint')} />
        <div className="mt-1 flex justify-end gap-2">
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" variant="primary" loading={save.isPending}>
            {t('common.save')}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
