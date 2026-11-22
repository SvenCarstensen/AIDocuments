import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, Save, Undo2 } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { Alert, Badge, Button, Logo, Spinner } from '../components/ui'
import { api, ApiError, errorMessage, sharedFileUrl, type SharedDoc } from '../lib/api'
import { formatDate } from '../lib/format'
import { usePdfJsFrame, viewerSrc } from '../lib/pdfjs'

/**
 * Ein geteiltes Dokument für Personen ohne Konto: genau dieses eine PDF, über den Link geöffnet. Erlaubt der Link das
 * Bearbeiten, lässt sich das PDF im Betrachter ändern und speichern (als neue Fassung).
 */
export function SharePage() {
  const { t } = useTranslation()
  const { token = '' } = useParams()
  const shared = useQuery({
    queryKey: ['shared', token],
    queryFn: () => api.sharedDocument(token),
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 1,
    refetchOnWindowFocus: false,
  })

  return (
    <div className="flex h-dvh flex-col bg-page">
      <header className="flex items-center gap-2.5 border-b border-line bg-surface px-4 py-2.5">
        <Logo className="size-7" />
        <span className="text-sm font-semibold tracking-tight">AIDocuments</span>
      </header>
      {shared.isPending ? (
        <div className="flex flex-1 items-center justify-center">
          <Spinner className="size-7" />
        </div>
      ) : shared.isError ? (
        <div className="mx-auto w-full max-w-lg px-4 py-16">
          <Alert tone="danger">
            {shared.error instanceof ApiError && shared.error.status === 404 ? t('share.page.invalid') : errorMessage(shared.error)}
          </Alert>
        </div>
      ) : (
        <SharedDocument token={token} doc={shared.data} />
      )}
    </div>
  )
}

function SharedDocument({ token, doc }: { token: string; doc: SharedDoc }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [reloads, setReloads] = useState(0)
  const version = `${doc.modifiedAt ?? 'original'}-${reloads}`
  const src = viewerSrc(`${sharedFileUrl(token)}?v=${encodeURIComponent(version)}`)
  const { frame, modified, forgetChanges, savedBytes } = usePdfJsFrame(src, doc.canEdit)

  const save = useMutation({
    mutationFn: async () => api.saveSharedFile(token, await savedBytes()),
    onSuccess: (updated) => {
      forgetChanges()
      queryClient.setQueryData(['shared', token], updated)
      toast.success(t('document.pdf.saved'))
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  function download() {
    const link = document.createElement('a')
    link.href = sharedFileUrl(token)
    link.download = doc.filename
    link.click()
  }

  return (
    <>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line bg-surface px-4 py-3">
        <div className="min-w-0 flex-1 basis-64">
          <h1 className="text-base leading-tight font-semibold [overflow-wrap:anywhere]">{doc.title}</h1>
          <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-[13px] text-muted">
            <span>{t('share.page.for', { name: doc.recipient })}</span>
            <Badge tone={doc.canEdit ? 'brand' : 'neutral'}>{doc.canEdit ? t('share.canEdit') : t('share.viewOnly')}</Badge>
            {doc.expiresAt && <span>{t('share.validUntil', { date: formatDate(doc.expiresAt) })}</span>}
          </div>
        </div>
        <Button icon={<Download className="size-4" aria-hidden />} onClick={download}>
          {t('document.download')}
        </Button>
      </div>
      {doc.canEdit && modified && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-line bg-brand-soft px-4 py-2 text-sm text-brand-ink">
          <span className="mr-auto font-medium">{t('document.pdf.unsaved')}</span>
          <Button
            size="sm"
            variant="ghost"
            icon={<Undo2 className="size-4" aria-hidden />}
            onClick={() => {
              forgetChanges()
              setReloads((n) => n + 1)
            }}
            disabled={save.isPending}
          >
            {t('document.pdf.discard')}
          </Button>
          <Button size="sm" variant="primary" icon={<Save className="size-4" aria-hidden />} onClick={() => save.mutate()} loading={save.isPending}>
            {t('document.pdf.save')}
          </Button>
        </div>
      )}
      <iframe ref={frame} key={src} src={src} title={t('document.pdfTitle', { title: doc.title })} className="min-h-0 w-full flex-1" />
    </>
  )
}
