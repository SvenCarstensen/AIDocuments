import { Check, Copy, Download, ReceiptText } from 'lucide-react'
import { type ReactNode, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { type Doc, type EInvoice, type EInvoiceParty, eInvoiceXmlUrl } from '../lib/api'
import { formatDate, formatNumber } from '../lib/format'
import { formatLocale } from '../lib/i18n'
import { Card, IconButton } from './ui'

/** Gutschriften nach UNTDID 1001 – wie im Server (EInvoice.creditNote). */
const CREDIT_NOTES = ['381', '261', '262', '296', '308', '396', '420', '458', '532']

/** Ein Betrag in der Währung der Rechnung; unbekannte Währungscodes stehen hinter der Zahl. */
function formatMoney(value: number | null | undefined, currency: string | null, maxDigits = 2): string {
  if (value == null) return ''
  try {
    return new Intl.NumberFormat(formatLocale(), {
      style: 'currency',
      currency: currency ?? 'EUR',
      minimumFractionDigits: Math.min(2, maxDigits),
      maximumFractionDigits: maxDigits,
    }).format(value)
  } catch {
    return `${formatNumber(value, { minimumFractionDigits: 2, maximumFractionDigits: maxDigits })} ${currency ?? ''}`.trim()
  }
}

/** „XRechnung 3.0 (CII)“, „ZUGFeRD / Factur-X (CII)“ … – aus der Kennung der Spezifikation. */
function formatName(invoice: EInvoice): string {
  const profile = (invoice.profile ?? '').toLowerCase()
  const xrechnung = /xrechnung_(\d+(?:\.\d+)*)/.exec(profile)
  const name = xrechnung
    ? `XRechnung ${xrechnung[1]}`
    : profile.includes('xrechnung')
      ? 'XRechnung'
      : profile.includes('factur-x') || profile.includes('zugferd')
        ? 'ZUGFeRD / Factur-X'
        : profile.includes('peppol')
          ? 'Peppol BIS'
          : profile.includes('en16931')
            ? 'EN 16931'
            : null
  return name ? `${name} (${invoice.syntax})` : invoice.syntax
}

/** Einheiten (UN/ECE Rec. 20), die der Browser in der Sprache der Oberfläche schreiben kann – samt Mehrzahl. */
const INTL_UNITS: Record<string, string> = {
  SEC: 'second', MIN: 'minute', HUR: 'hour', DAY: 'day', WEE: 'week', MON: 'month', ANN: 'year',
  GRM: 'gram', KGM: 'kilogram', MTR: 'meter', KMT: 'kilometer', LTR: 'liter', MLT: 'milliliter',
}
/** Übrige häufige Einheiten als Zeichen; Stück ohne Einheit. */
const SYMBOLS: Record<string, string> = { KWH: 'kWh', MTK: 'm²', MTQ: 'm³', C62: '', H87: '', EA: '', XPP: '' }

/** „412,5 kWh“, „1 Mon.“, „3 Tage“ – oder nur die Zahl, wenn die Einheit Stück ist. */
function quantity(value: number, code: string | null): string {
  const intl = code ? INTL_UNITS[code] : undefined
  if (intl) {
    try {
      return formatNumber(value, { style: 'unit', unit: intl, unitDisplay: 'short', maximumFractionDigits: 4 })
    } catch {
      // ältere Browser ohne diese Einheit: weiter unten mit dem Code
    }
  }
  const unit = code == null ? '' : (SYMBOLS[code] ?? code)
  return `${formatNumber(value, { maximumFractionDigits: 4 })} ${unit}`.trim()
}

function percent(rate: number | null): string {
  return rate == null ? '' : formatNumber(rate / 100, { style: 'percent', maximumFractionDigits: 2 })
}

/** Kopiert einen Text – auch ohne HTTPS (dort fehlt die Zwischenablage-Schnittstelle des Browsers). */
async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const area = document.createElement('textarea')
    area.value = text
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const done = document.execCommand('copy')
    area.remove()
    return done
  }
}

function CopyValue({ label, value }: { label: string; value: string }) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  return (
    <span className="inline-flex items-center gap-1">
      <span className="font-mono text-[13px] [overflow-wrap:anywhere]">{value}</span>
      <IconButton
        label={t('einvoice.copy', { label })}
        className="-my-1 !size-7"
        onClick={async () => {
          if (await copyText(value)) {
            setCopied(true)
            toast.success(t('einvoice.copied', { label }))
            window.setTimeout(() => setCopied(false), 1500)
          } else {
            toast.error(t('einvoice.copyFailed'))
          }
        }}
      >
        {copied ? <Check className="size-3.5 text-ok" /> : <Copy className="size-3.5" />}
      </IconButton>
    </span>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-muted">{label}</dt>
      <dd className="min-w-0 font-medium [overflow-wrap:anywhere]">{children}</dd>
    </>
  )
}

function Party({ party }: { party: EInvoiceParty }) {
  const { t } = useTranslation()
  const place = [party.postcode, party.city].filter(Boolean).join(' ')
  return (
    <span className="block">
      {party.name}
      {(party.street || place) && (
        <span className="block font-normal text-muted">{[party.street, place, party.country].filter(Boolean).join(', ')}</span>
      )}
      {party.vatId && (
        <span className="block font-normal text-muted">
          {t('einvoice.fields.vatId')} {party.vatId}
        </span>
      )}
    </span>
  )
}

/**
 * Die Angaben einer E-Rechnung (ZUGFeRD/Factur-X, XRechnung) in der Dokumentansicht – so, wie sie in der XML-Datei
 * stehen. Betrag, Fälligkeit und Bankverbindung zuerst, die Positionen auf Wunsch.
 */
export function EInvoiceCard({ doc }: { doc: Doc }) {
  const { t } = useTranslation()
  const [showLines, setShowLines] = useState(false)
  const invoice = doc.eInvoice
  if (!invoice) return null
  const credit = invoice.typeCode != null && CREDIT_NOTES.includes(invoice.typeCode)
  const money = (value: number | null, digits = 2) => formatMoney(value, invoice.currency, digits)
  const payable = invoice.amountDue ?? invoice.grossAmount
  const taxes = invoice.taxes.filter((tax) => tax.amount != null)

  return (
    <Card className="p-4">
      <div className="mb-3 flex items-center gap-2">
        <ReceiptText className="size-4 text-brand" aria-hidden />
        <h2 className="mr-auto text-sm font-semibold">{t(credit ? 'einvoice.creditNoteTitle' : 'einvoice.title')}</h2>
        <a
          href={eInvoiceXmlUrl(doc.id)}
          download
          className="inline-flex h-8 items-center gap-1.5 rounded-lg px-2 text-[13px] font-medium text-muted transition-colors hover:bg-raised hover:text-ink"
        >
          <Download className="size-4" aria-hidden />
          {t('einvoice.downloadXml')}
        </a>
      </div>

      {payable != null && (
        <div className="mb-3 rounded-lg bg-raised px-3 py-2.5">
          <div className="text-xs text-muted">{t(credit ? 'einvoice.creditAmount' : 'einvoice.amountDue')}</div>
          <div className="text-xl font-semibold tabular-nums">{money(payable)}</div>
          {invoice.dueDate && !credit && (
            <div className="text-[13px] text-muted">{t('einvoice.dueOn', { date: formatDate(invoice.dueDate) })}</div>
          )}
        </div>
      )}

      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1.5 text-sm">
        {invoice.number && (
          <Row label={t('einvoice.fields.number')}>
            <CopyValue label={t('einvoice.fields.number')} value={invoice.number} />
          </Row>
        )}
        {invoice.issueDate && <Row label={t('einvoice.fields.issueDate')}>{formatDate(invoice.issueDate)}</Row>}
        {invoice.seller?.name && (
          <Row label={t('einvoice.fields.seller')}>
            <Party party={invoice.seller} />
          </Row>
        )}
        {invoice.buyer?.name && (
          <Row label={t('einvoice.fields.buyer')}>
            <Party party={invoice.buyer} />
          </Row>
        )}
        {invoice.buyerReference && <Row label={t('einvoice.fields.buyerReference')}>{invoice.buyerReference}</Row>}
        {invoice.netAmount != null && <Row label={t('einvoice.fields.net')}>{money(invoice.netAmount)}</Row>}
        {taxes.length > 0
          ? taxes.map((tax, index) => (
              <Row key={index} label={t('einvoice.fields.tax', { rate: percent(tax.rate) }).trim()}>
                {money(tax.amount)}
              </Row>
            ))
          : invoice.taxAmount != null && <Row label={t('einvoice.fields.tax', { rate: '' }).trim()}>{money(invoice.taxAmount)}</Row>}
        {invoice.grossAmount != null && <Row label={t('einvoice.fields.gross')}>{money(invoice.grossAmount)}</Row>}
        {invoice.iban && (
          <Row label="IBAN">
            <CopyValue label="IBAN" value={invoice.iban} />
          </Row>
        )}
        {invoice.bic && <Row label="BIC">{invoice.bic}</Row>}
        {invoice.paymentReference && (
          <Row label={t('einvoice.fields.reference')}>
            <CopyValue label={t('einvoice.fields.reference')} value={invoice.paymentReference} />
          </Row>
        )}
        {invoice.paymentTerms && (
          <Row label={t('einvoice.fields.terms')}>
            <span className="font-normal whitespace-pre-line">{invoice.paymentTerms}</span>
          </Row>
        )}
      </dl>

      {invoice.lines.length > 0 && (
        <div className="mt-3">
          <button
            type="button"
            onClick={() => setShowLines((value) => !value)}
            aria-expanded={showLines}
            className="text-[13px] font-medium text-brand hover:underline"
          >
            {showLines ? t('einvoice.lines.hide') : t('einvoice.lines.show', { count: invoice.lines.length })}
          </button>
          {showLines && (
            <div className="mt-2 overflow-x-auto">
              <table className="w-full text-[13px]">
                <thead>
                  <tr className="border-b border-line text-left text-xs text-muted">
                    <th className="py-1.5 pr-2 font-medium">{t('einvoice.lines.description')}</th>
                    <th className="py-1.5 pr-2 text-right font-medium">{t('einvoice.lines.quantity')}</th>
                    <th className="py-1.5 pr-2 text-right font-medium">{t('einvoice.lines.unitPrice')}</th>
                    <th className="py-1.5 text-right font-medium">{t('einvoice.lines.amount')}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {invoice.lines.map((line, index) => (
                    <tr key={index} className="align-top">
                      <td className="py-1.5 pr-2 [overflow-wrap:anywhere]">{line.name}</td>
                      <td className="py-1.5 pr-2 text-right whitespace-nowrap tabular-nums">
                        {line.quantity != null ? quantity(line.quantity, line.unit) : ''}
                      </td>
                      <td className="py-1.5 pr-2 text-right whitespace-nowrap tabular-nums">{money(line.netPrice, 4)}</td>
                      <td className="py-1.5 text-right whitespace-nowrap tabular-nums">{money(line.netAmount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      <p className="mt-3 text-xs text-subtle">
        {t('einvoice.exact', { format: formatName(invoice) })}
      </p>
    </Card>
  )
}
