import { AlertCircle, FileText, FolderInput, Loader2, Mail, ScanText, Upload } from 'lucide-react'
import { Fragment, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { type Doc, type DocumentSource, thumbnailUrl } from '../lib/api'
import { cx, formatDate } from '../lib/format'
import { Badge } from './ui'

export const isBusy = (doc: Pick<Doc, 'status'>) => doc.status === 'PENDING' || doc.status === 'PROCESSING'

/** Der Server markiert Treffer im Ausschnitt mit zwei Private-Use-Zeichen; daraus wird hier <mark>. */
export function Snippet({ text, className }: { text: string; className?: string }) {
  const parts = text.split(/[]/)
  return (
    <p className={className}>
      {parts.map((part, index) =>
        index % 2 === 1 ? <mark key={index}>{part}</mark> : <Fragment key={index}>{part}</Fragment>,
      )}
    </p>
  )
}

export function StatusBadge({ doc }: { doc: Pick<Doc, 'status'> }) {
  const { t } = useTranslation()
  switch (doc.status) {
    case 'PENDING':
      return (
        <Badge tone="neutral">
          <Loader2 className="size-3 animate-spin" aria-hidden /> {t('documents.status.pending')}
        </Badge>
      )
    case 'PROCESSING':
      return (
        <Badge tone="brand">
          <Loader2 className="size-3 animate-spin" aria-hidden /> {t('documents.status.processing')}
        </Badge>
      )
    case 'FAILED':
      return (
        <Badge tone="danger">
          <AlertCircle className="size-3" aria-hidden /> {t('documents.status.failed')}
        </Badge>
      )
    case 'TEXT_ONLY':
      return <Badge tone="warn">{t('documents.status.textOnly')}</Badge>
    default:
      return null
  }
}

export function Thumbnail({ doc, className }: { doc: Doc; className?: string }) {
  const [failed, setFailed] = useState(false)
  if (doc.hasThumbnail && !failed) {
    return (
      <img
        src={thumbnailUrl(doc)}
        alt=""
        loading="lazy"
        onError={() => setFailed(true)}
        className={cx('size-full bg-white object-cover object-top', className)}
      />
    )
  }
  return (
    <div className={cx('flex size-full items-center justify-center bg-raised text-subtle', className)}>
      {isBusy(doc) ? <Loader2 className="size-6 animate-spin" aria-hidden /> : <FileText className="size-7" aria-hidden />}
    </div>
  )
}

export function TagChip({
  tag,
  active,
  count,
  onClick,
  className,
}: {
  tag: string
  active?: boolean
  count?: number
  onClick?: () => void
  className?: string
}) {
  const style = cx(
    'inline-flex max-w-full items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-medium transition-colors',
    active
      ? 'border-brand bg-brand text-white dark:text-page'
      : 'border-line bg-raised text-muted hover:border-brand/50 hover:bg-brand-soft hover:text-brand-ink',
    className,
  )
  const content = (
    <>
      <span className="truncate">{tag}</span>
      {count !== undefined && <span className={active ? 'opacity-80' : 'text-subtle'}>{count}</span>}
    </>
  )
  return onClick ? (
    <button type="button" onClick={onClick} aria-pressed={active} className={style}>
      {content}
    </button>
  ) : (
    <span className={style}>{content}</span>
  )
}

/** Symbol je Herkunft; die Bezeichnung dazu steht in den Übersetzungen unter document.source.<Herkunft>. */
export const sourceInfo: Record<DocumentSource, { icon: typeof Mail }> = {
  MAIL: { icon: Mail },
  FOLDER: { icon: FolderInput },
  UPLOAD: { icon: Upload },
}

/** "Stadtwerke · 28.02.2025" – was davon bekannt ist; sonst das Eingangsdatum. */
export function metaLine(doc: Doc): string {
  const parts = [doc.correspondent, formatDate(doc.documentDate ?? doc.createdAt)].filter(Boolean)
  return parts.join(' · ')
}

export function OcrHint() {
  const { t } = useTranslation()
  return (
    <Badge tone="neutral">
      <ScanText className="size-3" aria-hidden /> {t('documents.ocrBadge')}
    </Badge>
  )
}
