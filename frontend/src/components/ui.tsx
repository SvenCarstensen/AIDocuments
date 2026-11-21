import { AlertTriangle, CheckCircle2, Info, Loader2, X, XCircle } from 'lucide-react'
import {
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
  useEffect,
  useId,
  useRef,
} from 'react'
import { useTranslation } from 'react-i18next'
import { cx } from '../lib/format'

// ---------- Schaltflächen ----------

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: 'sm' | 'md'
  loading?: boolean
  icon?: ReactNode
}

const buttonVariants: Record<ButtonVariant, string> = {
  primary: 'bg-brand text-white shadow-sm hover:bg-brand-hover dark:text-page',
  secondary: 'border border-line-strong bg-surface text-ink hover:bg-raised',
  ghost: 'text-muted hover:bg-raised hover:text-ink',
  danger: 'bg-danger text-white shadow-sm hover:opacity-90 dark:text-page',
}

export function Button({
  variant = 'secondary',
  size = 'md',
  loading = false,
  icon,
  className,
  children,
  disabled,
  type = 'button',
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      className={cx(
        'inline-flex shrink-0 items-center justify-center gap-2 rounded-lg font-medium whitespace-nowrap transition-colors',
        'disabled:cursor-not-allowed disabled:opacity-55',
        size === 'sm' ? 'h-8 px-3 text-[13px]' : 'h-10 px-4 text-sm',
        buttonVariants[variant],
        className,
      )}
      {...rest}
    >
      {loading ? <Loader2 className="size-4 animate-spin" aria-hidden /> : icon}
      {children}
    </button>
  )
}

interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  label: string
  active?: boolean
}

export function IconButton({ label, active, className, children, type = 'button', ...rest }: IconButtonProps) {
  return (
    <button
      type={type}
      aria-label={label}
      title={label}
      className={cx(
        'inline-flex size-9 shrink-0 items-center justify-center rounded-lg transition-colors',
        active ? 'bg-brand-soft text-brand-ink' : 'text-muted hover:bg-raised hover:text-ink',
        'disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  )
}

// ---------- Formularfelder ----------

const controlClass =
  'w-full rounded-lg border border-line-strong bg-surface px-3 text-sm text-ink placeholder:text-subtle ' +
  'transition-shadow focus:border-brand focus:outline-none focus:ring-3 focus:ring-brand/20 ' +
  'disabled:cursor-not-allowed disabled:bg-raised disabled:text-muted'

interface FieldProps {
  label: string
  hint?: ReactNode
  error?: string | null
  className?: string
  children: (id: string) => ReactNode
}

export function Field({ label, hint, error, className, children }: FieldProps) {
  const id = useId()
  return (
    <div className={cx('flex flex-col gap-1.5', className)}>
      <label htmlFor={id} className="text-[13px] font-medium text-ink">
        {label}
      </label>
      {children(id)}
      {error ? (
        <p className="text-[13px] text-danger">{error}</p>
      ) : hint ? (
        <p className="text-[13px] leading-snug text-muted">{hint}</p>
      ) : null}
    </div>
  )
}

export function Input({ className, ...rest }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cx(controlClass, 'h-10', className)} {...rest} />
}

export function Textarea({ className, ...rest }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={cx(controlClass, 'py-2 leading-relaxed', className)} {...rest} />
}

export function Select({ className, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={cx(controlClass, 'h-10 pr-8', className)} {...rest}>
      {children}
    </select>
  )
}

interface SwitchProps {
  checked: boolean
  onChange: (checked: boolean) => void
  label: string
  description?: ReactNode
  disabled?: boolean
}

export function Switch({ checked, onChange, label, description, disabled }: SwitchProps) {
  const id = useId()
  return (
    <div className="flex items-start gap-3">
      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={checked}
        disabled={disabled}
        onClick={() => onChange(!checked)}
        className={cx(
          'relative mt-0.5 h-6 w-11 shrink-0 rounded-full transition-colors disabled:opacity-50',
          checked ? 'bg-brand' : 'bg-line-strong',
        )}
      >
        <span
          className={cx(
            'absolute top-0.5 left-0.5 size-5 rounded-full bg-white shadow transition-transform',
            checked && 'translate-x-5',
          )}
        />
      </button>
      <label htmlFor={id} className="flex flex-col gap-0.5">
        <span className="text-sm font-medium text-ink">{label}</span>
        {description && <span className="text-[13px] leading-snug text-muted">{description}</span>}
      </label>
    </div>
  )
}

interface SegmentedProps<T extends string> {
  value: T
  onChange: (value: T) => void
  options: Array<{ value: T; label: string }>
  label: string
}

export function Segmented<T extends string>({ value, onChange, options, label }: SegmentedProps<T>) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex w-fit max-w-full flex-wrap rounded-lg border border-line-strong bg-raised p-0.5">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="radio"
          aria-checked={value === option.value}
          onClick={() => onChange(option.value)}
          className={cx(
            'h-9 rounded-md px-3 text-[13px] font-medium transition-colors',
            value === option.value ? 'bg-surface text-ink shadow-sm' : 'text-muted hover:text-ink',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

// ---------- Anzeige ----------

type Tone = 'neutral' | 'brand' | 'ok' | 'warn' | 'danger'

const badgeTones: Record<Tone, string> = {
  neutral: 'bg-raised text-muted border-line',
  brand: 'bg-brand-soft text-brand-ink border-transparent',
  ok: 'bg-ok-soft text-ok border-transparent',
  warn: 'bg-warn-soft text-warn border-transparent',
  danger: 'bg-danger-soft text-danger border-transparent',
}

export function Badge({ tone = 'neutral', className, children }: { tone?: Tone; className?: string; children: ReactNode }) {
  return (
    <span
      className={cx(
        'inline-flex items-center gap-1 rounded-md border px-1.5 py-0.5 text-xs font-medium whitespace-nowrap',
        badgeTones[tone],
        className,
      )}
    >
      {children}
    </span>
  )
}

export function Spinner({ className }: { className?: string }) {
  const { t } = useTranslation()
  return <Loader2 className={cx('animate-spin text-brand', className ?? 'size-5')} aria-label={t('common.loading')} />
}

export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cx('rounded-xl border border-line bg-surface shadow-card', className)}>{children}</div>
}

const alertStyles: Record<Exclude<Tone, 'neutral'>, { box: string; icon: ReactNode }> = {
  brand: { box: 'bg-brand-soft text-brand-ink', icon: <Info className="size-4.5 shrink-0" aria-hidden /> },
  ok: { box: 'bg-ok-soft text-ok', icon: <CheckCircle2 className="size-4.5 shrink-0" aria-hidden /> },
  warn: { box: 'bg-warn-soft text-warn', icon: <AlertTriangle className="size-4.5 shrink-0" aria-hidden /> },
  danger: { box: 'bg-danger-soft text-danger', icon: <XCircle className="size-4.5 shrink-0" aria-hidden /> },
}

export function Alert({
  tone,
  children,
  action,
  className,
}: {
  tone: Exclude<Tone, 'neutral'>
  children: ReactNode
  action?: ReactNode
  className?: string
}) {
  const style = alertStyles[tone]
  return (
    <div role="status" className={cx('flex items-start gap-2.5 rounded-lg px-3.5 py-3 text-sm', style.box, className)}>
      <span className="mt-px">{style.icon}</span>
      <div className="min-w-0 flex-1 leading-relaxed [overflow-wrap:anywhere]">{children}</div>
      {action}
    </div>
  )
}

export function EmptyState({
  icon,
  title,
  children,
  action,
}: {
  icon: ReactNode
  title: string
  children?: ReactNode
  action?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center px-6 py-16 text-center">
      <div className="mb-4 flex size-14 items-center justify-center rounded-2xl bg-brand-soft text-brand-ink">{icon}</div>
      <h3 className="text-base font-semibold text-ink">{title}</h3>
      {children && <p className="mt-1.5 max-w-md text-sm leading-relaxed text-muted">{children}</p>}
      {action && <div className="mt-5 flex flex-wrap justify-center gap-2">{action}</div>}
    </div>
  )
}

// ---------- Dialoge ----------

interface ModalProps {
  open: boolean
  onClose: () => void
  title: string
  description?: ReactNode
  children: ReactNode
  footer?: ReactNode
  size?: 'sm' | 'md' | 'lg'
}

/** Modaler Dialog auf Basis des nativen <dialog>: Fokusfalle, Escape und Hintergrundsperre inklusive. */
export function Modal({ open, onClose, title, description, children, footer, size = 'md' }: ModalProps) {
  const { t } = useTranslation()
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])

  if (!open) return null
  return (
    <dialog
      ref={ref}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
      onMouseDown={(event) => {
        if (event.target === ref.current) onClose()
      }}
      className={cx(
        'm-auto w-[calc(100vw-2rem)] animate-rise rounded-2xl border border-line bg-surface p-0 shadow-pop',
        size === 'sm' ? 'max-w-md' : size === 'lg' ? 'max-w-2xl' : 'max-w-lg',
      )}
    >
      <div className="flex max-h-[calc(100dvh-4rem)] flex-col">
        <header className="flex items-start gap-4 px-6 pt-5 pb-1">
          <div className="min-w-0 flex-1">
            <h2 className="text-lg font-semibold text-ink">{title}</h2>
            {description && <p className="mt-1 text-sm leading-relaxed text-muted">{description}</p>}
          </div>
          <IconButton label={t('common.close')} onClick={onClose} className="-mt-1 -mr-2">
            <X className="size-5" />
          </IconButton>
        </header>
        <div className="overflow-y-auto px-6 py-4">{children}</div>
        {footer && (
          <footer className="flex flex-wrap items-center justify-end gap-2 border-t border-line bg-raised/60 px-6 py-3.5">
            {footer}
          </footer>
        )}
      </div>
    </dialog>
  )
}

interface ConfirmProps {
  open: boolean
  onClose: () => void
  onConfirm: () => void
  title: string
  children: ReactNode
  confirmLabel: string
  loading?: boolean
}

export function ConfirmDialog({ open, onClose, onConfirm, title, children, confirmLabel, loading }: ConfirmProps) {
  const { t } = useTranslation()
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      size="sm"
      footer={
        <>
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button variant="danger" onClick={onConfirm} loading={loading}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="text-sm leading-relaxed text-muted">{children}</div>
    </Modal>
  )
}

/** Das Signet der Anwendung. */
export function Logo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" className={className ?? 'size-8'} aria-hidden>
      <rect width="32" height="32" rx="8" className="fill-brand" />
      <path
        d="M10 7.5h8.2L23 12.3V23a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 9 23V9a1.5 1.5 0 0 1 1-1.5Z"
        fill="#fff"
      />
      <path d="M18 7.5V11a1.5 1.5 0 0 0 1.5 1.5H23" fill="#c7d2fe" />
      <path d="m16 14.5.9 2.3 2.3.9-2.3.9-.9 2.3-.9-2.3-2.3-.9 2.3-.9.9-2.3Z" className="fill-brand" />
    </svg>
  )
}
