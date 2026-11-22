import { useQuery } from '@tanstack/react-query'
import { FileSignature } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { ContractStatusBadge } from '../components/Contracts'
import { Alert, Card, EmptyState, Segmented, Spinner } from '../components/ui'
import { api, type Contract, errorMessage } from '../lib/api'
import { formatLongDate } from '../lib/format'

/** Vertragsübersicht: alle sichtbaren Verträge – zuerst die, bei denen bald etwas zu tun ist. */
export function ContractsPage() {
  const { t } = useTranslation()
  const [scope, setScope] = useState<'running' | 'all'>('running')
  const contracts = useQuery({ queryKey: ['contracts'], queryFn: api.contracts })
  const shown = (contracts.data ?? []).filter((c) => scope === 'all' || c.status !== 'ENDED')

  return (
    <div className="mx-auto max-w-4xl">
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <div className="mr-auto">
          <h1 className="text-2xl font-semibold tracking-tight">{t('contracts.title')}</h1>
          <p className="mt-1 text-sm text-muted">{t('contracts.pageIntro')}</p>
        </div>
        <Segmented
          label={t('contracts.scope.label')}
          value={scope}
          onChange={setScope}
          options={[
            { value: 'running', label: t('contracts.scope.running') },
            { value: 'all', label: t('contracts.scope.all') },
          ]}
        />
      </div>
      {contracts.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner />
        </div>
      ) : contracts.isError ? (
        <Alert tone="danger">{errorMessage(contracts.error)}</Alert>
      ) : shown.length === 0 ? (
        <EmptyState icon={<FileSignature className="size-7" />} title={t('contracts.emptyTitle')}>
          {t('contracts.emptyText')}
        </EmptyState>
      ) : (
        <Card className="overflow-hidden">
          <ul className="divide-y divide-line">
            {shown.map((contract) => (
              <ContractRow key={contract.documentId} contract={contract} />
            ))}
          </ul>
        </Card>
      )}
    </div>
  )
}

function ContractRow({ contract }: { contract: Contract }) {
  const { t } = useTranslation()
  const next = contract.cancelled || contract.status === 'ENDED' || !contract.keyDate
    ? null
    : contract.noticeDeadline
      ? t('contracts.row.cancelBy', { date: formatLongDate(contract.noticeDeadline) })
      : t('contracts.row.endsOn', { date: formatLongDate(contract.keyDate) })
  return (
    <li className="flex flex-wrap items-center gap-x-4 gap-y-1.5 px-4 py-3 sm:px-5">
      <div className="min-w-0 flex-1 basis-64">
        <Link to={`/documents/${contract.documentId}`} className="block truncate text-sm font-semibold hover:text-brand-ink hover:underline">
          {contract.documentTitle}
        </Link>
        <p className="mt-0.5 truncate text-xs text-muted">
          {[
            contract.partner,
            contract.amount,
            contract.endDate ? t('contracts.row.term', { date: formatLongDate(contract.endDate) }) : t('contracts.openEnded'),
            contract.renewalMonths && t('contracts.row.renews', { count: contract.renewalMonths }),
          ]
            .filter(Boolean)
            .join(' · ')}
        </p>
      </div>
      {next && <span className="text-sm font-medium whitespace-nowrap">{next}</span>}
      <ContractStatusBadge status={contract.status} />
    </li>
  )
}
