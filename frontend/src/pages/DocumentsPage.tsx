import { keepPreviousData, useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { FileSearch, FolderOpen, LayoutGrid, List, Loader2, SlidersHorizontal, Sparkles, X } from 'lucide-react'
import { type ReactNode, useEffect, useMemo, useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router-dom'
import { isBusy, metaLine, Snippet, StatusBadge, TagChip, Thumbnail } from '../components/documents'
import { Alert, Badge, Button, EmptyState, IconButton, Input, Modal, Select, Spinner } from '../components/ui'
import { api, type Doc, type DocumentQuery, errorMessage, type Facets, MAX_PAGE_SIZE, type SortOrder } from '../lib/api'
import { useAuth } from '../lib/auth'
import { cx, formatFinishTime, formatRemaining } from '../lib/format'

type View = 'grid' | 'list'

/** Wie viele Dokumente auf einmal sichtbar sind; "all" lädt alles nach. */
const PAGE_SIZES = ['10', '20', '50', '100', 'all'] as const
type PageSize = (typeof PAGE_SIZES)[number]

function storedPageSize(): PageSize {
  try {
    const stored = localStorage.getItem('pageSize') as PageSize | null
    if (stored && PAGE_SIZES.includes(stored)) return stored
  } catch {
    /* privater Modus – dann gilt die Vorgabe */
  }
  return '20'
}

/** Filter und Suchbegriff leben in der Adresse – so bleiben sie beim Neuladen, Teilen und Zurückgehen erhalten. */
function useDocumentFilters() {
  const [params, setParams] = useSearchParams()
  const filters = useMemo<DocumentQuery>(() => {
    const q = params.get('q') ?? ''
    const sort = params.get('sort') as SortOrder | null
    return {
      q,
      category: params.get('category') ?? '',
      correspondent: params.get('correspondent') ?? '',
      tags: params.getAll('tag'),
      status: params.get('status') ?? '',
      from: params.get('from') ?? '',
      to: params.get('to') ?? '',
      sort: sort ?? (q ? 'relevance' : 'newest'),
    }
  }, [params])

  /** @param replace ersetzt den Verlaufseintrag – für Änderungen, die nicht der Anwender ausgelöst hat */
  function set(key: string, value: string, replace = false) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next, { replace })
  }
  function toggleTag(tag: string) {
    const next = new URLSearchParams(params)
    const tags = next.getAll('tag')
    next.delete('tag')
    ;(tags.includes(tag) ? tags.filter((t) => t !== tag) : [...tags, tag]).forEach((t) => next.append('tag', t))
    setParams(next)
  }
  function clearFilters() {
    const next = new URLSearchParams()
    if (filters.q) next.set('q', filters.q)
    setParams(next)
  }
  const activeCount =
    [filters.category, filters.correspondent, filters.status, filters.from, filters.to].filter(Boolean).length +
    filters.tags.length
  return { filters, set, toggleTag, clearFilters, activeCount }
}

export function DocumentsPage() {
  const { t } = useTranslation()
  const { isAdmin } = useAuth()
  const { filters, set, toggleTag, clearFilters, activeCount } = useDocumentFilters()
  const [view, setView] = useState<View>(() => (localStorage.getItem('view') === 'list' ? 'list' : 'grid'))
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [pageSize, setPageSize] = useState<PageSize>(storedPageSize)
  const showAll = pageSize === 'all'
  // "Alle" holt die Dokumente in den größten Paketen, die der Server liefert, und lädt selbsttätig nach.
  const requestSize = showAll ? MAX_PAGE_SIZE : Number(pageSize)

  // Die Kennzahlen verraten, ob im Hintergrund gerade etwas verarbeitet wird (z. B. neue Post).
  const system = useQuery({
    queryKey: ['system'],
    queryFn: api.system,
    refetchInterval: (query) => ((query.state.data?.processing ?? 0) > 0 ? 4000 : 10_000),
  })
  const inProgress = system.data?.processing ?? 0
  const failed = system.data?.failed ?? 0

  // Der Filter „Fehlgeschlagen“ ist nur wählbar, solange es Fehlschläge gibt. Ist der letzte behoben, während
  // er gesetzt ist, entfällt er – sonst bliebe eine leere Liste hinter einem Filter, den niemand mehr sieht.
  const failedFilterObsolete = filters.status === 'FAILED' && system.data !== undefined && failed === 0
  useEffect(() => {
    if (failedFilterObsolete) set('status', '', true)
    // hängt bewusst nur an der Bedingung: "set" entsteht bei jedem Rendern neu
  }, [failedFilterObsolete])
  const remainingSeconds = system.data?.estimatedSecondsRemaining ?? null
  const working = inProgress > 0

  const documents = useInfiniteQuery({
    queryKey: ['documents', filters, requestSize],
    queryFn: ({ pageParam }) => api.documents(filters, pageParam, requestSize),
    initialPageParam: 0,
    getNextPageParam: (last) => ((last.page + 1) * last.size < last.total ? last.page + 1 : undefined),
    placeholderData: keepPreviousData,
    // Solange etwas verarbeitet wird, eng nachfragen; sonst gelegentlich nach neuen Eingängen sehen.
    // Bei "Alle" hängen viele Pakete an jeder Nachfrage – deshalb dort etwas seltener.
    refetchInterval: (query) =>
      working || query.state.data?.pages.some((page) => page.items.some(isBusy))
        ? showAll
          ? 8000
          : 2500
        : 30_000,
  })

  const { hasNextPage, isFetching, fetchNextPage } = documents
  useEffect(() => {
    if (showAll && hasNextPage && !isFetching) void fetchNextPage()
  }, [showAll, hasNextPage, isFetching, fetchNextPage])
  const facets = useQuery({ queryKey: ['documents', 'facets'], queryFn: api.facets, refetchInterval: 30_000 })

  // Kommen während des Ladens neue Dokumente hinzu, verschieben sich die Seiten gegeneinander und ein
  // Dokument kann in zwei Seiten stehen. Jedes darf aber nur einmal erscheinen.
  const items = useMemo(() => {
    const seen = new Set<string>()
    return (documents.data?.pages ?? [])
      .flatMap((page) => page.items)
      .filter((doc) => (seen.has(doc.id) ? false : (seen.add(doc.id), true)))
  }, [documents.data])
  const total = documents.data?.pages[0]?.total ?? 0
  const searching = filters.q !== ''
  const libraryEmpty = system.data?.documents === 0

  function changeView(next: View) {
    setView(next)
    localStorage.setItem('view', next)
  }
  function changePageSize(next: PageSize) {
    setPageSize(next)
    try {
      localStorage.setItem('pageSize', next)
    } catch {
      /* privater Modus – die Auswahl gilt dann nur bis zum Neuladen */
    }
  }

  const filterPanel = (
    <FilterPanel
      facets={facets.data}
      filters={filters}
      failed={failed}
      onSet={set}
      onToggleTag={toggleTag}
    />
  )

  return (
    <div className="flex gap-8">
      <aside className="hidden w-60 shrink-0 lg:block">
        <div className="sticky top-22 -ml-2 max-h-[calc(100dvh-7rem)] overflow-x-hidden overflow-y-auto pr-1 pb-4 pl-2">
          {filterPanel}
        </div>
      </aside>

      <div className="min-w-0 flex-1">
        {system.data && !system.data.aiEnabled && (
          <Alert
            tone="warn"
            className="mb-5"
            action={
              isAdmin && (
                <Link to="/settings/ai" className="shrink-0 font-semibold underline underline-offset-2">
                  {t('documents.aiNotice.action')}
                </Link>
              )
            }
          >
            {t('documents.aiNotice.text')}
          </Alert>
        )}

        <div className="mb-4 flex flex-wrap items-center gap-x-3 gap-y-2">
          {/* Überschrift, Anzahl und Bearbeitungsstand stehen auf einer Linie – jeweils senkrecht mittig */}
          <div className="mr-auto flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
            <h1 className="flex min-w-0 items-center gap-2 text-xl font-semibold tracking-tight">
              {searching ? (
                // Ein Satz aus zwei unterschiedlich gesetzten Teilen – die Wortstellung bestimmt die Übersetzung.
                <Trans
                  i18nKey="documents.searchResults"
                  count={total}
                  values={{ query: filters.q }}
                  components={{ count: <span />, query: <span className="truncate font-normal text-muted" /> }}
                />
              ) : (
                <span>{t('common.documentCount', { count: total })}</span>
              )}
            </h1>
            {inProgress > 0 && (
              <Badge tone="brand" className="!gap-1.5 !px-2 !py-1 !text-[13px] !leading-none !font-medium">
                <Loader2 className="size-3.5 animate-spin" aria-hidden />
                <span title={t('documents.progress.inProgressHint')}>
                  {t('documents.progress.inProgress', { documents: inProgress })}
                </span>
                {remainingSeconds != null && (
                  <span
                    className="border-l border-current/25 pl-1.5 font-normal"
                    title={t('documents.progress.finishHint', { time: formatFinishTime(remainingSeconds) })}
                  >
                    {formatRemaining(remainingSeconds)}
                  </span>
                )}
              </Badge>
            )}
            {/* Ladeanzeige nur beim Wechsel von Suche oder Filter – nicht bei jeder Hintergrundabfrage */}
            {documents.isPlaceholderData && <Spinner className="size-4" />}
          </div>

          <Button
            size="sm"
            className="lg:hidden"
            icon={<SlidersHorizontal className="size-4" aria-hidden />}
            onClick={() => setFiltersOpen(true)}
          >
            {activeCount > 0 ? t('documents.filters.titleWithCount', { active: activeCount }) : t('documents.filters.title')}
          </Button>
          <Select
            aria-label={t('documents.sort.label')}
            value={filters.sort}
            onChange={(event) => set('sort', event.target.value)}
            className="!h-8 !w-auto !text-[13px]"
          >
            {searching && <option value="relevance">{t('documents.sort.relevance')}</option>}
            <option value="newest">{t('documents.sort.newest')}</option>
            <option value="date">{t('documents.sort.date')}</option>
          </Select>
          <div className="flex rounded-lg border border-line-strong bg-surface p-0.5">
            <IconButton label={t('documents.view.grid')} active={view === 'grid'} onClick={() => changeView('grid')} className="!size-7 !rounded-md">
              <LayoutGrid className="size-4" />
            </IconButton>
            <IconButton label={t('documents.view.list')} active={view === 'list'} onClick={() => changeView('list')} className="!size-7 !rounded-md">
              <List className="size-4" />
            </IconButton>
          </div>
          <Select
            aria-label={t('documents.pageSize.label')}
            title={t('documents.pageSize.label')}
            value={pageSize}
            onChange={(event) => changePageSize(event.target.value as PageSize)}
            className="!h-8 !w-auto !text-[13px]"
          >
            {PAGE_SIZES.map((size) => (
              <option key={size} value={size}>
                {size === 'all' ? t('documents.pageSize.all') : size}
              </option>
            ))}
          </Select>
        </div>

        {activeCount > 0 && (
          <div className="mb-4 flex flex-wrap items-center gap-2">
            {filters.category && <ActiveFilter label={filters.category} onRemove={() => set('category', '')} />}
            {filters.correspondent && (
              <ActiveFilter label={filters.correspondent} onRemove={() => set('correspondent', '')} />
            )}
            {filters.tags.map((tag) => (
              <ActiveFilter key={tag} label={`# ${tag}`} onRemove={() => toggleTag(tag)} />
            ))}
            {filters.status === 'FAILED' && <ActiveFilter label={t('documents.filters.failed')} onRemove={() => set('status', '')} />}
            {filters.from && <ActiveFilter label={t('documents.filters.activeFrom', { date: filters.from })} onRemove={() => set('from', '')} />}
            {filters.to && <ActiveFilter label={t('documents.filters.activeTo', { date: filters.to })} onRemove={() => set('to', '')} />}
            <button type="button" onClick={clearFilters} className="text-[13px] font-medium text-muted hover:text-ink">
              {t('documents.filters.removeAll')}
            </button>
          </div>
        )}

        {documents.isError ? (
          <Alert tone="danger">{errorMessage(documents.error)}</Alert>
        ) : documents.isPending ? (
          <SkeletonGrid view={view} />
        ) : items.length === 0 ? (
          libraryEmpty && !searching && activeCount === 0 ? (
            <EmptyState icon={<FolderOpen className="size-7" />} title={t('documents.empty.libraryTitle')}>
              {isAdmin ? t('documents.empty.libraryHintAdmin') : t('documents.empty.libraryHint')}
            </EmptyState>
          ) : (
            <EmptyState
              icon={<FileSearch className="size-7" />}
              title={t('documents.empty.noResultsTitle')}
              action={activeCount > 0 && <Button onClick={clearFilters}>{t('documents.filters.clear')}</Button>}
            >
              {searching ? t('documents.empty.noResultsSearch') : t('documents.empty.noResultsFilters')}
            </EmptyState>
          )
        ) : view === 'grid' ? (
          <div key="grid" className="grid auto-rows-fr grid-cols-[repeat(auto-fill,minmax(15rem,1fr))] gap-4">
            {items.map((doc) => (
              <DocumentCard key={doc.id} doc={doc} activeTags={filters.tags} onTag={toggleTag} />
            ))}
          </div>
        ) : (
          <div key="list" className="overflow-hidden rounded-xl border border-line bg-surface shadow-card">
            {items.map((doc) => (
              <DocumentRow key={doc.id} doc={doc} activeTags={filters.tags} onTag={toggleTag} />
            ))}
          </div>
        )}

        {documents.hasNextPage &&
          (showAll ? (
            <p className="mt-6 flex items-center justify-center gap-2 text-sm text-muted">
              <Spinner className="size-4" />
              {t('documents.loadingAll', { loaded: items.length, count: total })}
            </p>
          ) : (
            <div className="mt-6 flex justify-center">
              <Button onClick={() => documents.fetchNextPage()} loading={documents.isFetchingNextPage}>
                {t('documents.loadMore', { remaining: total - items.length })}
              </Button>
            </div>
          ))}
      </div>

      <Modal open={filtersOpen} onClose={() => setFiltersOpen(false)} title={t('documents.filters.title')} footer={<Button variant="primary" onClick={() => setFiltersOpen(false)}>{t('documents.filters.apply')}</Button>}>
        {filterPanel}
      </Modal>
    </div>
  )
}

function ActiveFilter({ label, onRemove }: { label: string; onRemove: () => void }) {
  const { t } = useTranslation()
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-brand-soft py-1 pr-1 pl-3 text-[13px] font-medium text-brand-ink">
      {label}
      <button
        type="button"
        aria-label={t('documents.filters.removeOne', { label })}
        onClick={onRemove}
        className="flex size-5 items-center justify-center rounded-full hover:bg-brand/15"
      >
        <X className="size-3.5" />
      </button>
    </span>
  )
}

// ---------- Filterleiste ----------

/** So oft muss ein Schlagwort vorkommen, damit es in der Übersicht der Filter erscheint. */
const MIN_TAG_COUNT = 3

interface FilterPanelProps {
  facets: Facets | undefined
  filters: DocumentQuery
  failed: number
  onSet: (key: string, value: string) => void
  onToggleTag: (tag: string) => void
}

function FilterPanel({ facets, filters, failed, onSet, onToggleTag }: FilterPanelProps) {
  const { t } = useTranslation()
  const [allTags, setAllTags] = useState(false)
  const [allCorrespondents, setAllCorrespondents] = useState(false)
  if (!facets) {
    return (
      <div className="flex flex-col gap-3">
        {[60, 85, 70, 50].map((width, index) => (
          <div key={index} className="skeleton h-5 rounded-md" style={{ width: `${width}%` }} />
        ))}
      </div>
    )
  }
  // Die Übersicht zeigt nur Schlagworte, die öfter vorkommen – einmalige würden sie bei vielen Dokumenten
  // unbrauchbar lang machen. Ein gerade gewähltes bleibt sichtbar, damit es sich wieder abwählen lässt.
  const frequentTags = facets.tags.filter((facet) => facet.count >= MIN_TAG_COUNT || filters.tags.includes(facet.name))
  const tags = allTags ? frequentTags : frequentTags.slice(0, 18)
  const correspondents = allCorrespondents ? facets.correspondents : facets.correspondents.slice(0, 7)
  const nothingYet = facets.categories.length + facets.tags.length + facets.correspondents.length === 0

  return (
    <div className="flex flex-col gap-6">
      {nothingYet && (
        <p className="flex gap-2 text-[13px] leading-relaxed text-muted">
          <Sparkles className="mt-0.5 size-4 shrink-0 text-brand" aria-hidden />
          {t('documents.filters.hint')}
        </p>
      )}

      {facets.categories.length > 0 && (
        <FilterSection title={t('document.fields.category')}>
          {facets.categories.map((facet) => (
            <FacetRow
              key={facet.name}
              label={facet.name}
              count={facet.count}
              active={filters.category === facet.name}
              onClick={() => onSet('category', filters.category === facet.name ? '' : facet.name)}
            />
          ))}
        </FilterSection>
      )}

      {frequentTags.length > 0 && (
        <FilterSection title={t('document.fields.tags')}>
          <div className="flex flex-wrap gap-1.5">
            {tags.map((facet) => (
              <TagChip
                key={facet.name}
                tag={facet.name}
                count={facet.count}
                active={filters.tags.includes(facet.name)}
                onClick={() => onToggleTag(facet.name)}
              />
            ))}
          </div>
          {frequentTags.length > 18 && <MoreToggle open={allTags} onClick={() => setAllTags(!allTags)} />}
        </FilterSection>
      )}

      {facets.correspondents.length > 0 && (
        <FilterSection title={t('document.fields.correspondent')}>
          {correspondents.map((facet) => (
            <FacetRow
              key={facet.name}
              label={facet.name}
              count={facet.count}
              active={filters.correspondent === facet.name}
              onClick={() => onSet('correspondent', filters.correspondent === facet.name ? '' : facet.name)}
            />
          ))}
          {facets.correspondents.length > 7 && (
            <MoreToggle open={allCorrespondents} onClick={() => setAllCorrespondents(!allCorrespondents)} />
          )}
        </FilterSection>
      )}

      <FilterSection title={t('document.fields.documentDate')}>
        <div className="grid grid-cols-2 gap-2">
          <label className="flex flex-col gap-1 text-xs text-muted">
            {t('documents.filters.dateFrom')}
            <Input type="date" value={filters.from} max={filters.to || undefined} onChange={(e) => onSet('from', e.target.value)} className="!h-9 !px-2 !text-[13px]" />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted">
            {t('documents.filters.dateTo')}
            <Input type="date" value={filters.to} min={filters.from || undefined} onChange={(e) => onSet('to', e.target.value)} className="!h-9 !px-2 !text-[13px]" />
          </label>
        </div>
      </FilterSection>

      {failed > 0 && (
        <FilterSection title={t('documents.filters.status')}>
          <FacetRow
            label={t('documents.filters.failed')}
            count={failed}
            active={filters.status === 'FAILED'}
            onClick={() => onSet('status', filters.status === 'FAILED' ? '' : 'FAILED')}
          />
        </FilterSection>
      )}
    </div>
  )
}

function FilterSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h2 className="mb-2 text-xs font-semibold tracking-wider text-subtle uppercase">{title}</h2>
      <div className="flex flex-col gap-0.5">{children}</div>
    </section>
  )
}

function FacetRow({ label, count, active, onClick }: { label: string; count: number; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cx(
        '-mx-2 flex items-center justify-between gap-2 rounded-lg px-2 py-1.5 text-left text-sm transition-colors',
        active ? 'bg-brand-soft font-medium text-brand-ink' : 'text-ink hover:bg-raised',
      )}
    >
      <span className="truncate">{label}</span>
      <span className={cx('text-xs tabular-nums', active ? 'text-brand-ink' : 'text-subtle')}>{count}</span>
    </button>
  )
}

function MoreToggle({ open, onClick }: { open: boolean; onClick: () => void }) {
  const { t } = useTranslation()
  return (
    <button type="button" onClick={onClick} className="mt-1.5 self-start text-[13px] font-medium text-brand-ink hover:underline">
      {open ? t('documents.filters.showLess') : t('documents.filters.showAll')}
    </button>
  )
}

// ---------- Dokument als Kachel / Zeile ----------

interface DocItemProps {
  doc: Doc
  activeTags: string[]
  onTag: (tag: string) => void
}

function DocumentCard({ doc, activeTags, onTag }: DocItemProps) {
  return (
    <article className="group relative flex h-full flex-col overflow-hidden rounded-xl border border-line bg-surface shadow-card transition duration-150 focus-within:border-brand hover:-translate-y-0.5 hover:border-line-strong hover:shadow-pop">
      <div className="relative aspect-[5/4] overflow-hidden border-b border-line bg-raised">
        <Thumbnail doc={doc} className={cx(isBusy(doc) && 'opacity-50')} />
        <div className="absolute inset-x-2.5 top-2.5 flex items-start justify-between gap-2">
          {doc.category ? <Badge className="!border-line !bg-surface/95 !text-ink shadow-sm backdrop-blur">{doc.category}</Badge> : <span />}
          <StatusBadge doc={doc} />
        </div>
      </div>
      <div className="flex flex-1 flex-col gap-1.5 p-3.5">
        <h3 className="line-clamp-2 text-sm leading-snug font-semibold">
          <Link to={`/documents/${doc.id}`} className="outline-none after:absolute after:inset-0">
            {doc.title}
          </Link>
        </h3>
        <p className="truncate text-xs text-muted">{metaLine(doc)}</p>
        {doc.snippet ? (
          <Snippet text={doc.snippet} className="line-clamp-3 text-xs leading-relaxed text-muted" />
        ) : (
          doc.summary && <p className="line-clamp-2 text-xs leading-relaxed text-muted">{doc.summary}</p>
        )}
        {doc.tags.length > 0 && (
          <div className="relative z-10 mt-auto flex flex-wrap gap-1 pt-1.5">
            {doc.tags.slice(0, 3).map((tag) => (
              <TagChip key={tag} tag={tag} active={activeTags.includes(tag)} onClick={() => onTag(tag)} />
            ))}
            {doc.tags.length > 3 && <span className="self-center text-xs text-subtle">+{doc.tags.length - 3}</span>}
          </div>
        )}
      </div>
    </article>
  )
}

function DocumentRow({ doc, activeTags, onTag }: DocItemProps) {
  return (
    <article className="relative flex gap-4 border-b border-line px-4 py-3.5 transition-colors last:border-b-0 hover:bg-raised/70">
      <div className="h-[76px] w-[58px] shrink-0 overflow-hidden rounded-md border border-line bg-raised">
        <Thumbnail doc={doc} />
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <h3 className="min-w-0 truncate text-sm font-semibold">
            <Link to={`/documents/${doc.id}`} className="outline-none after:absolute after:inset-0">
              {doc.title}
            </Link>
          </h3>
          {doc.category && <Badge>{doc.category}</Badge>}
          <StatusBadge doc={doc} />
        </div>
        <p className="truncate text-xs text-muted">{metaLine(doc)}</p>
        {doc.snippet ? (
          <Snippet text={doc.snippet} className="line-clamp-2 text-[13px] leading-relaxed text-muted" />
        ) : (
          doc.summary && <p className="line-clamp-2 text-[13px] leading-relaxed text-muted">{doc.summary}</p>
        )}
        {doc.tags.length > 0 && (
          <div className="relative z-10 mt-0.5 flex flex-wrap gap-1">
            {doc.tags.slice(0, 6).map((tag) => (
              <TagChip key={tag} tag={tag} active={activeTags.includes(tag)} onClick={() => onTag(tag)} />
            ))}
          </div>
        )}
      </div>
    </article>
  )
}

function SkeletonGrid({ view }: { view: View }) {
  if (view === 'list') {
    return (
      <div className="overflow-hidden rounded-xl border border-line bg-surface">
        {Array.from({ length: 6 }, (_, index) => (
          <div key={index} className="flex gap-4 border-b border-line px-4 py-3.5 last:border-b-0">
            <div className="skeleton h-[76px] w-[58px] rounded-md" />
            <div className="flex flex-1 flex-col gap-2 py-1">
              <div className="skeleton h-4 w-2/5 rounded" />
              <div className="skeleton h-3 w-1/4 rounded" />
              <div className="skeleton h-3 w-4/5 rounded" />
            </div>
          </div>
        ))}
      </div>
    )
  }
  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(15rem,1fr))] gap-4">
      {Array.from({ length: 8 }, (_, index) => (
        <div key={index} className="overflow-hidden rounded-xl border border-line bg-surface">
          <div className="skeleton aspect-[5/4]" />
          <div className="flex flex-col gap-2 p-3.5">
            <div className="skeleton h-4 w-4/5 rounded" />
            <div className="skeleton h-3 w-1/2 rounded" />
          </div>
        </div>
      ))}
    </div>
  )
}
