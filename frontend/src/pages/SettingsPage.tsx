import { Bot, FolderSync, Mail, Users } from 'lucide-react'
import type { ReactNode } from 'react'
import { NavLink, useParams } from 'react-router-dom'
import { cx } from '../lib/format'
import { AiSettingsSection } from './settings/AiSettings'
import { ImportSettingsSection } from './settings/ImportSettings'
import { MailboxSettings } from './settings/MailboxSettings'
import { UserSettings } from './settings/UserSettings'

const sections: Array<{ key: string; label: string; hint: string; icon: ReactNode; content: ReactNode }> = [
  {
    key: 'mailboxes',
    label: 'Postfächer',
    hint: 'IMAP-Server, aus denen PDF-Anhänge übernommen werden',
    icon: <Mail className="size-[18px]" aria-hidden />,
    content: <MailboxSettings />,
  },
  {
    key: 'import',
    label: 'Abruf & Eingangsordner',
    hint: 'Abrufintervall und überwachtes Verzeichnis',
    icon: <FolderSync className="size-[18px]" aria-hidden />,
    content: <ImportSettingsSection />,
  },
  {
    key: 'ai',
    label: 'KI',
    hint: 'LLM-Server, Modell und Texterkennung',
    icon: <Bot className="size-[18px]" aria-hidden />,
    content: <AiSettingsSection />,
  },
  {
    key: 'users',
    label: 'Benutzer',
    hint: 'Konten und Rollen',
    icon: <Users className="size-[18px]" aria-hidden />,
    content: <UserSettings />,
  },
]

export function SettingsPage() {
  const { section } = useParams()
  const current = sections.find((s) => s.key === section) ?? sections[0]

  return (
    <div className="mx-auto max-w-5xl">
      <h1 className="mb-6 text-2xl font-semibold tracking-tight">Einstellungen</h1>
      <div className="flex flex-col gap-6 md:flex-row md:gap-10">
        <nav aria-label="Einstellungen" className="flex shrink-0 gap-1 overflow-x-auto md:w-60 md:flex-col md:overflow-visible">
          {sections.map((s) => (
            <NavLink
              key={s.key}
              to={`/settings/${s.key}`}
              className={cx(
                'flex shrink-0 items-start gap-3 rounded-xl px-3 py-2.5 transition-colors',
                s.key === current.key ? 'bg-brand-soft text-brand-ink' : 'text-ink hover:bg-raised',
              )}
            >
              <span className={cx('mt-0.5', s.key === current.key ? 'text-brand-ink' : 'text-muted')}>{s.icon}</span>
              <span className="flex flex-col">
                <span className="text-sm font-medium whitespace-nowrap">{s.label}</span>
                <span className={cx('hidden text-xs leading-snug md:block', s.key === current.key ? 'text-brand-ink/80' : 'text-muted')}>
                  {s.hint}
                </span>
              </span>
            </NavLink>
          ))}
        </nav>
        <div className="min-w-0 flex-1 pb-12">{current.content}</div>
      </div>
    </div>
  )
}
