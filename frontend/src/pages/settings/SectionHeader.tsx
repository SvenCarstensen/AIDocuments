import type { ReactNode } from 'react'

export function SectionHeader({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0 flex-1 basis-64">
        <h2 className="text-lg font-semibold">{title}</h2>
        {children && <p className="mt-1 text-sm leading-relaxed text-muted">{children}</p>}
      </div>
      {action}
    </div>
  )
}
