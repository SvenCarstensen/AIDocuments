import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BellRing, Check, CalendarPlus, Pencil, RotateCcw, Sparkles, Trash2, X } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { api, type Doc, errorMessage, type Reminder, type ReminderSuggestion } from '../lib/api'
import { useAuth } from '../lib/auth'
import { cx, daysFromToday, formatDue, formatLongDate, todayIso } from '../lib/format'
import { Alert, Badge, Button, Card, ConfirmDialog, Field, IconButton, Input, Modal, Select, Textarea } from './ui'

/** Nach jeder Änderung: Dokument-Termine, Übersicht und Zähler im Menü neu holen. */
function useRefreshReminders() {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['reminders'] }),
      queryClient.invalidateQueries({ queryKey: ['document-reminders'] }),
    ])
}

/** Farbe des Termins: überfällig rot, heute gelb, sonst ruhig. */
function dueTone(date: string, done = false): 'danger' | 'warn' | 'neutral' {
  if (done) return 'neutral'
  const days = daysFromToday(date)
  return days < 0 ? 'danger' : days === 0 ? 'warn' : 'neutral'
}

export function DueDate({ date, done = false }: { date: string; done?: boolean }) {
  const tone = dueTone(date, done)
  return (
    <span
      title={formatLongDate(date)}
      className={cx('text-xs font-semibold whitespace-nowrap', tone === 'danger' ? 'text-danger' : tone === 'warn' ? 'text-warn' : 'text-muted')}
    >
      {formatDue(date)}
    </span>
  )
}

const REPEAT_OPTIONS = [1, 3, 6, 12, 24] as const
type RepeatOption = (typeof REPEAT_OPTIONS)[number]
const isOption = (months: number): months is RepeatOption => (REPEAT_OPTIONS as readonly number[]).includes(months)

/** „Monatlich“, „Jährlich“ … – andere Abstände (über die Schnittstelle gesetzt) als „alle n Monate“. */
function repeatLabel(t: TFunction, months: number): string {
  if (!months) return t('reminders.repeat.none')
  return isOption(months) ? t(`reminders.repeat.m${months}`) : t('reminders.repeat.months', { count: months })
}

/** Termin anlegen oder bearbeiten – Datum, Notiz, Wiederholung und wer sich kümmert. */
export function ReminderDialog({
  documentId,
  reminder,
  preset,
  onClose,
}: {
  documentId: string
  reminder?: Reminder
  /** übernimmt einen Vorschlag: Datum und Bezeichnung vorbelegt */
  preset?: ReminderSuggestion
  onClose: () => void
}) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const refresh = useRefreshReminders()
  const assignees = useQuery({ queryKey: ['reminder-assignees', documentId], queryFn: () => api.reminderAssignees(documentId) })
  const [dueDate, setDueDate] = useState(reminder?.dueDate ?? preset?.date ?? todayIso())
  const [note, setNote] = useState(reminder?.note ?? preset?.label ?? '')
  const [assigneeId, setAssigneeId] = useState(reminder?.assigneeId ?? user.id)
  const [repeatMonths, setRepeatMonths] = useState(reminder?.repeatMonths ?? 0)

  const save = useMutation({
    mutationFn: () => {
      const body = {
        dueDate,
        note: note.trim() || null,
        assigneeId: assigneeId || null,
        fromSuggestion: preset !== undefined,
        repeatMonths: repeatMonths || null,
      }
      return reminder ? api.updateReminder(reminder.id, body) : api.createReminder(documentId, body)
    },
    onSuccess: async () => {
      await refresh()
      toast.success(reminder ? t('reminders.saved') : t('reminders.created'))
      onClose()
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate()
  }

  return (
    <Modal open onClose={onClose} title={reminder ? t('reminders.edit') : t('reminders.add')} size="sm">
      <form onSubmit={submit} className="flex flex-col gap-4">
        {save.isError && <Alert tone="danger">{errorMessage(save.error)}</Alert>}
        <Field label={t('reminders.dueDate')}>
          {(id) => <Input id={id} type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} required autoFocus />}
        </Field>
        <Field label={t('reminders.note')}>
          {(id) => <Textarea id={id} value={note} onChange={(e) => setNote(e.target.value)} rows={2} maxLength={500} placeholder={t('reminders.notePlaceholder')} />}
        </Field>
        <Field label={t('reminders.repeat.label')} hint={repeatMonths ? t('reminders.repeat.hint') : undefined}>
          {(id) => (
            <Select id={id} value={repeatMonths} onChange={(e) => setRepeatMonths(Number(e.target.value))}>
              {[0, ...REPEAT_OPTIONS].map((months) => (
                <option key={months} value={months}>
                  {repeatLabel(t, months)}
                </option>
              ))}
              {reminder?.repeatMonths && !isOption(reminder.repeatMonths) && (
                <option value={reminder.repeatMonths}>{repeatLabel(t, reminder.repeatMonths)}</option>
              )}
            </Select>
          )}
        </Field>
        <Field label={t('reminders.assignee')} hint={t('reminders.assigneeHint')}>
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
        <div className="mt-1 flex justify-end gap-2">
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" variant="primary" loading={save.isPending}>
            {reminder ? t('common.save') : t('reminders.create')}
          </Button>
        </div>
      </form>
    </Modal>
  )
}

/** Eine Zeile: Termin mit Erledigt-Haken, Notiz, zuständiger Person; optional mit Dokumenttitel. */
export function ReminderRow({ reminder, showDocument = false }: { reminder: Reminder; showDocument?: boolean }) {
  const { t } = useTranslation()
  const refresh = useRefreshReminders()
  const [editing, setEditing] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const done = reminder.status === 'DONE'

  const toggle = useMutation({
    mutationFn: () => api.setReminderDone(reminder.id, !done),
    onSuccess: async () => {
      await refresh()
      if (!done) toast.success(t('reminders.markedDone'))
    },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const remove = useMutation({
    mutationFn: () => api.deleteReminder(reminder.id),
    onSuccess: async () => {
      setDeleting(false)
      await refresh()
      toast.success(t('reminders.deleted'))
    },
    onError: (e) => {
      setDeleting(false)
      toast.error(errorMessage(e))
    },
  })

  return (
    <li className={cx('flex items-start gap-3 py-2.5', done && 'opacity-60')}>
      <button
        type="button"
        onClick={() => toggle.mutate()}
        disabled={toggle.isPending}
        aria-label={done ? t('reminders.reopen') : t('reminders.markDone')}
        title={done ? t('reminders.reopen') : t('reminders.markDone')}
        className={cx(
          'mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-md border transition-colors',
          done ? 'border-ok bg-ok text-white' : 'border-line-strong bg-surface hover:border-brand',
        )}
      >
        {done ? <Check className="size-3.5" /> : null}
      </button>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
          <DueDate date={reminder.dueDate} done={done} />
          {showDocument ? (
            <Link to={`/documents/${reminder.documentId}`} className="text-sm font-medium [overflow-wrap:anywhere] hover:underline">
              {reminder.documentTitle}
            </Link>
          ) : (
            reminder.note && <span className={cx('text-sm [overflow-wrap:anywhere]', done && 'line-through')}>{reminder.note}</span>
          )}
        </div>
        {showDocument && reminder.note && <p className="text-[13px] text-muted [overflow-wrap:anywhere]">{reminder.note}</p>}
        <p className="text-xs text-subtle">
          {[
            formatLongDate(reminder.dueDate),
            reminder.assigneeName && t('reminders.assignedTo', { name: reminder.assigneeName }),
            reminder.repeatMonths && repeatLabel(t, reminder.repeatMonths),
            reminder.source === 'CONTRACT' && t('reminders.fromContract'),
            done && reminder.doneBy && t('reminders.doneBy', { name: reminder.doneBy }),
          ]
            .filter(Boolean)
            .join(' · ')}
        </p>
      </div>
      {done ? (
        <IconButton label={t('reminders.reopen')} onClick={() => toggle.mutate()} className="!size-8">
          <RotateCcw className="size-4" />
        </IconButton>
      ) : (
        <IconButton label={t('reminders.edit')} onClick={() => setEditing(true)} className="!size-8">
          <Pencil className="size-4" />
        </IconButton>
      )}
      <IconButton label={t('reminders.delete.label')} onClick={() => setDeleting(true)} className="!size-8 hover:!bg-danger-soft hover:!text-danger">
        <Trash2 className="size-4" />
      </IconButton>
      {editing && <ReminderDialog documentId={reminder.documentId} reminder={reminder} onClose={() => setEditing(false)} />}
      <ConfirmDialog
        open={deleting}
        onClose={() => setDeleting(false)}
        onConfirm={() => remove.mutate()}
        loading={remove.isPending}
        title={t('reminders.delete.title')}
        confirmLabel={t('common.delete')}
      >
        {t('reminders.delete.text')}
      </ConfirmDialog>
    </li>
  )
}

/** Ein Vorschlag der KI: übernehmen (öffnet den Dialog vorbelegt) oder verwerfen. */
export function SuggestionRow({ suggestion, showDocument = false }: { suggestion: ReminderSuggestion; showDocument?: boolean }) {
  const { t } = useTranslation()
  const refresh = useRefreshReminders()
  const [accepting, setAccepting] = useState(false)
  const dismiss = useMutation({
    mutationFn: () => api.dismissSuggestion(suggestion.documentId, suggestion.date, suggestion.label),
    onSuccess: refresh,
    onError: (e) => toast.error(errorMessage(e)),
  })
  return (
    <li className="flex items-start gap-3 py-2.5">
      <Sparkles className="mt-0.5 size-4 shrink-0 text-brand" aria-hidden />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
          <DueDate date={suggestion.date} />
          {showDocument ? (
            <Link to={`/documents/${suggestion.documentId}`} className="text-sm font-medium [overflow-wrap:anywhere] hover:underline">
              {suggestion.documentTitle}
            </Link>
          ) : (
            <span className="text-sm [overflow-wrap:anywhere]">{suggestion.label}</span>
          )}
        </div>
        <p className="text-xs text-subtle">
          {[formatLongDate(suggestion.date), showDocument && suggestion.label].filter(Boolean).join(' · ')}
        </p>
      </div>
      <Button size="sm" variant="primary" onClick={() => setAccepting(true)}>
        {t('reminders.accept')}
      </Button>
      <IconButton label={t('reminders.dismiss')} onClick={() => dismiss.mutate()} disabled={dismiss.isPending} className="!size-8">
        <X className="size-4" />
      </IconButton>
      {accepting && <ReminderDialog documentId={suggestion.documentId} preset={suggestion} onClose={() => setAccepting(false)} />}
    </li>
  )
}

/** Wiedervorlage in der Dokumentansicht: Vorschläge, offene und erledigte Termine, „Termin anlegen“. */
export function RemindersCard({ doc }: { doc: Doc }) {
  const { t } = useTranslation()
  const data = useQuery({ queryKey: ['document-reminders', doc.id], queryFn: () => api.documentReminders(doc.id) })
  const [adding, setAdding] = useState(false)
  const [showDone, setShowDone] = useState(false)
  const reminders = data.data?.reminders ?? []
  const suggestions = data.data?.suggestions ?? []
  const open = reminders.filter((r) => r.status === 'OPEN')
  const done = reminders.filter((r) => r.status === 'DONE')

  return (
    <Card className="p-4">
      <div className="flex items-center gap-2">
        <BellRing className="size-4 text-brand" aria-hidden />
        <h2 className="text-sm font-semibold">{t('reminders.cardTitle')}</h2>
        {open.length > 0 && <Badge tone="brand">{open.length}</Badge>}
        <Button size="sm" className="ml-auto" icon={<CalendarPlus className="size-4" aria-hidden />} onClick={() => setAdding(true)}>
          {t('reminders.add')}
        </Button>
      </div>
      {suggestions.length > 0 && (
        <div className="mt-3 rounded-lg bg-brand-soft/50 px-3 pt-2">
          <p className="text-xs font-medium text-brand-ink">{t('reminders.suggestionsHint')}</p>
          <ul className="divide-y divide-line">
            {suggestions.map((s) => (
              <SuggestionRow key={s.date} suggestion={s} />
            ))}
          </ul>
        </div>
      )}
      {open.length > 0 && (
        <ul className="mt-1 divide-y divide-line">
          {open.map((r) => (
            <ReminderRow key={r.id} reminder={r} />
          ))}
        </ul>
      )}
      {open.length === 0 && suggestions.length === 0 && !data.isPending && (
        <p className="mt-2 text-[13px] text-muted">{t('reminders.none')}</p>
      )}
      {done.length > 0 && (
        <div className="mt-2">
          <button type="button" onClick={() => setShowDone(!showDone)} className="text-xs font-medium text-muted hover:text-ink">
            {showDone ? t('reminders.hideDone') : t('reminders.showDone', { count: done.length })}
          </button>
          {showDone && (
            <ul className="divide-y divide-line">
              {done.map((r) => (
                <ReminderRow key={r.id} reminder={r} />
              ))}
            </ul>
          )}
        </div>
      )}
      {adding && <ReminderDialog documentId={doc.id} onClose={() => setAdding(false)} />}
    </Card>
  )
}
