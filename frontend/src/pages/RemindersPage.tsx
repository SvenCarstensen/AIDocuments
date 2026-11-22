import { useQuery } from '@tanstack/react-query'
import { BellRing, CalendarCheck } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ReminderRow, SuggestionRow } from '../components/Reminders'
import { Alert, Card, EmptyState, Segmented, Spinner } from '../components/ui'
import { api, errorMessage } from '../lib/api'

/** Wiedervorlage: was fällig ist, was bald kommt, und Termine, die die KI in den Dokumenten gefunden hat. */
export function RemindersPage() {
  const { t } = useTranslation()
  const [scope, setScope] = useState<'mine' | 'all'>(() => (localStorage.getItem('reminders.scope') === 'all' ? 'all' : 'mine'))
  const data = useQuery({ queryKey: ['reminders', scope], queryFn: () => api.reminders(scope === 'mine'), refetchInterval: 60_000 })

  function changeScope(next: 'mine' | 'all') {
    setScope(next)
    try {
      localStorage.setItem('reminders.scope', next)
    } catch {
      // ohne Speicher gilt die Wahl nur bis zum Neuladen
    }
  }

  return (
    <div className="mx-auto max-w-3xl">
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <h1 className="mr-auto text-2xl font-semibold tracking-tight">{t('reminders.title')}</h1>
        <Segmented
          label={t('reminders.scope.label')}
          value={scope}
          onChange={changeScope}
          options={[
            { value: 'mine', label: t('reminders.scope.mine') },
            { value: 'all', label: t('reminders.scope.all') },
          ]}
        />
      </div>

      {data.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner />
        </div>
      ) : data.isError ? (
        <Alert tone="danger">{errorMessage(data.error)}</Alert>
      ) : data.data.due.length + data.data.upcoming.length + data.data.suggestions.length === 0 ? (
        <EmptyState icon={<CalendarCheck className="size-7" />} title={t('reminders.emptyTitle')}>
          {t('reminders.emptyText')}
        </EmptyState>
      ) : (
        <div className="flex flex-col gap-6">
          <Section title={t('reminders.dueTitle')} empty={t('reminders.nothingDue')} count={data.data.due.length}>
            {data.data.due.map((r) => (
              <ReminderRow key={r.id} reminder={r} showDocument />
            ))}
          </Section>
          {data.data.upcoming.length > 0 && (
            <Section title={t('reminders.upcomingTitle')} count={data.data.upcoming.length}>
              {data.data.upcoming.map((r) => (
                <ReminderRow key={r.id} reminder={r} showDocument />
              ))}
            </Section>
          )}
          {data.data.suggestions.length > 0 && (
            <Section title={t('reminders.suggestionsTitle')} hint={t('reminders.suggestionsPageHint')} count={data.data.suggestions.length}>
              {data.data.suggestions.map((s) => (
                <SuggestionRow key={`${s.documentId}|${s.date}`} suggestion={s} showDocument />
              ))}
            </Section>
          )}
        </div>
      )}
    </div>
  )
}

function Section({ title, hint, empty, count, children }: { title: string; hint?: string; empty?: string; count: number; children: React.ReactNode }) {
  return (
    <section>
      <h2 className="mb-1 flex items-center gap-2 text-sm font-semibold">
        <BellRing className="size-4 text-brand" aria-hidden />
        {title}
        <span className="font-normal text-muted">{count}</span>
      </h2>
      {hint && <p className="mb-2 text-[13px] text-muted">{hint}</p>}
      <Card className="px-4">
        {count === 0 && empty ? <p className="py-3 text-sm text-muted">{empty}</p> : <ul className="divide-y divide-line">{children}</ul>}
      </Card>
    </section>
  )
}
