/**
 * Englische Beispieldokumente für die Screenshots: fiktive Firmen, Personen und Nummern. Alle Daten hängen vom Tag
 * der Aufnahme ab – so sind Fristen immer in der Zukunft und „fällig“ heißt fällig.
 */

const DAY = 86_400_000
const today = new Date(new Date().toISOString().slice(0, 10) + 'T12:00:00Z')

/** Ein Tag relativ zu heute, in den Formen, die Dokument, KI und Schnittstelle brauchen. */
export function day(offset: number) {
  const date = new Date(today.getTime() + offset * DAY)
  const iso = date.toISOString().slice(0, 10)
  return {
    iso,
    /** 08/12/2026 – so liest die Terminerkennung ein Datum europäisch */
    short: `${iso.slice(8, 10)}/${iso.slice(5, 7)}/${iso.slice(0, 4)}`,
    long: date.toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }),
    month: date.toLocaleDateString('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' }),
  }
}

export interface Analysis {
  title: string
  summary: string
  /** Dokumentart, wie die KI sie aus der deutschen Liste wählt */
  category: string
  correspondent: string
  documentDate: string
  tags: string[]
  keyFacts: Array<{ label: string; value: string }>
}

export interface Sample {
  file: string
  /** Seiten als HTML-Fragmente */
  pages: string[]
  analysis: Analysis
  /** Dokumentart, die in der englischen Oberfläche stehen soll */
  type: string
}

const brand = (name: string, color: string, tagline = '') => `
  <header class="brand" style="--c:${color}">
    <div class="logo">${name.split(' ').map((w) => w[0]).join('').slice(0, 2)}</div>
    <div><div class="name">${name}</div><div class="tagline">${tagline}</div></div>
  </header>`

const address = (lines: string[]) => `<div class="address">${lines.join('<br>')}</div>`
const recipient = address(['Alex Morgan', '7 Willow Crescent', 'Galway H91 X2Y4', 'Ireland'])
const table = (head: string[], rows: string[][], foot?: string[]) => `
  <table><thead><tr>${head.map((h) => `<th>${h}</th>`).join('')}</tr></thead>
  <tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join('')}</tr>`).join('')}</tbody>
  ${foot ? `<tfoot><tr>${foot.map((c) => `<td>${c}</td>`).join('')}</tr></tfoot>` : ''}</table>`
const facts = (rows: Array<[string, string]>) =>
  `<dl class="facts">${rows.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join('')}</dl>`
const footer = (text: string) => `<footer>${text}</footer>`

/** Gemeinsames Aussehen aller Beispiel-PDFs (A4, Chrome druckt es mit page.pdf). */
export const STYLE = `
  @page { size: A4; margin: 0 }
  * { box-sizing: border-box }
  body { margin: 0; font: 10.5pt/1.45 'Helvetica Neue', Arial, sans-serif; color: #1f2430 }
  .page { width: 210mm; height: 297mm; padding: 18mm 20mm; position: relative; page-break-after: always; overflow: hidden }
  .page:last-child { page-break-after: auto }
  /* Briefkopf rechts – links oben liegt in der Übersicht das Schild mit der Dokumentart */
  .brand { display: flex; flex-direction: row-reverse; align-items: center; gap: 12px; text-align: right;
           border-bottom: 3px solid var(--c); padding-bottom: 10px }
  .logo { width: 44px; height: 44px; border-radius: 10px; background: var(--c); color: #fff; font-weight: 700;
          font-size: 17pt; display: grid; place-items: center }
  .name { font-size: 16pt; font-weight: 700; color: var(--c) }
  .tagline { font-size: 9pt; color: #6b7280 }
  .address { margin: 16mm 0 8mm; font-size: 10.5pt }
  .meta { position: absolute; right: 20mm; top: 44mm; text-align: right; font-size: 9.5pt; color: #4b5563 }
  h1 { font-size: 17pt; margin: 0 0 4mm }
  h2 { font-size: 12pt; margin: 7mm 0 2mm }
  table { width: 100%; border-collapse: collapse; margin: 4mm 0; font-size: 10pt }
  th { text-align: left; background: #f3f4f6; padding: 6px 8px; border-bottom: 1px solid #d1d5db }
  td { padding: 6px 8px; border-bottom: 1px solid #e5e7eb }
  td:last-child, th:last-child { text-align: right }
  tfoot td { font-weight: 700; border-top: 2px solid #1f2430; border-bottom: 0 }
  .facts { display: grid; grid-template-columns: 52mm 1fr; gap: 2mm 6mm; margin: 4mm 0 }
  .facts dt { color: #6b7280 } .facts dd { margin: 0; font-weight: 600 }
  .box { border: 1px solid #d1d5db; border-left: 4px solid #2563eb; padding: 4mm 5mm; margin: 6mm 0; background: #f8fafc }
  .stamp { display: inline-block; border: 2px solid #16a34a; color: #16a34a; padding: 2mm 5mm; font-weight: 700;
           transform: rotate(-4deg); margin-top: 6mm }
  p { margin: 0 0 3mm }
  .sign { margin-top: 18mm; display: flex; gap: 30mm }
  .sign div { border-top: 1px solid #6b7280; padding-top: 2mm; width: 60mm; font-size: 9pt; color: #4b5563 }
  footer { position: absolute; left: 20mm; right: 20mm; bottom: 12mm; font-size: 8pt; color: #9ca3af;
           border-top: 1px solid #e5e7eb; padding-top: 3mm }
`

export function samples(): Sample[] {
  const invoiceDate = day(-9)
  const invoiceDue = day(9)
  const lastMonth = day(-25).month
  const contractStart = day(-295)
  const insuranceDate = day(-38)
  const insuranceRenewal = day(52)
  const statementEnd = day(-23)
  const taxDate = day(-14)
  const taxDue = day(38)
  const dentalDate = day(-6)
  const dentalDue = day(24)
  const mobileDate = day(-4)
  const tenancyStart = day(-520)
  const deliveryDate = day(-17)
  const quoteDate = day(-8)
  const quoteValid = day(22)
  const gymDate = day(-3)
  const gymEnd = day(37)
  const courseDate = day(-60)
  const certValid = day(670)

  return [
    {
      file: 'brightwave-invoice.pdf',
      type: 'Invoice',
      pages: [`
        ${brand('Brightwave Energy', '#f59e0b', 'Clean power for every home')}
        ${recipient}
        <div class="meta">Invoice no. BW-2026-118734<br>Customer no. 40017725<br>Date ${invoiceDate.long}</div>
        <h1>Electricity invoice – ${lastMonth}</h1>
        <p>Dear Alex Morgan, thank you for choosing Brightwave Energy. Here is your bill for the last billing period.</p>
        ${table(['Item', 'Quantity', 'Unit price', 'Amount'], [
          ['Standing charge', '30 days', '€0.42', '€12.60'],
          ['Day rate', '412 kWh', '€0.248', '€102.18'],
          ['Night rate', '96 kWh', '€0.142', '€13.63'],
          ['VAT 9 %', '', '', '€11.53'],
          ['Public service obligation levy', '', '', '€2.66'],
        ], ['Total due', '', '', '€142.60'])}
        <div class="box"><b>Payment due by ${invoiceDue.long}.</b> The amount will be collected by direct debit from
          your account ending in 8817. Meter no. 7731 0094 · Smart meter reading on ${day(-10).long}.</div>
        ${footer('Brightwave Energy Ltd · 18 Quay Street, Galway · VAT IE 3349712QH · hello@brightwave.example')}
      `],
      analysis: {
        title: `Electricity invoice ${lastMonth}`,
        summary: `Brightwave Energy bills €142.60 for electricity used in ${lastMonth} (508 kWh, day and night rate). `
          + `The amount is collected by direct debit; payment is due by ${invoiceDue.long}.`,
        category: 'Rechnung',
        correspondent: 'Brightwave Energy',
        documentDate: invoiceDate.iso,
        tags: ['electricity', 'utilities', 'household'],
        keyFacts: [
          { label: 'Invoice number', value: 'BW-2026-118734' },
          { label: 'Customer number', value: '40017725' },
          { label: 'Total amount', value: '€142.60' },
          { label: 'Payment due', value: invoiceDue.short },
          { label: 'Consumption', value: '508 kWh' },
          { label: 'Meter number', value: '7731 0094' },
        ],
      },
    },
    {
      file: 'brightwave-supply-contract.pdf',
      type: 'Contract',
      pages: [`
        ${brand('Brightwave Energy', '#f59e0b', 'Clean power for every home')}
        ${recipient}
        <div class="meta">Contract no. BW-C-55120<br>Customer no. 40017725<br>Date ${contractStart.long}</div>
        <h1>Electricity supply agreement – Green Fix 12</h1>
        <p>This agreement confirms the supply of 100 % renewable electricity to the address above.</p>
        ${facts([
          ['Tariff', 'Green Fix 12 (fixed unit rates)'],
          ['Start of supply', contractStart.long],
          ['Minimum term', '12 months'],
          ['Notice period', '1 month before the end of the term'],
          ['Automatic renewal', 'by 12 months unless cancelled'],
          ['Monthly payment', '€118.00 by direct debit'],
          ['Day / night rate', '€0.248 / €0.142 per kWh'],
        ])}
        <h2>Price guarantee</h2>
        <p>Unit rates are fixed for the minimum term. Taxes and levies set by the state are passed on unchanged.</p>
        <h2>Cancellation</h2>
        <p>Either party may cancel in writing with one month's notice to the end of the term.</p>
        <div class="sign"><div>Brightwave Energy Ltd</div><div>Alex Morgan</div></div>
        ${footer('Brightwave Energy Ltd · 18 Quay Street, Galway · Registered in Ireland no. 612288')}
      `],
      analysis: {
        title: 'Electricity supply agreement Green Fix 12',
        summary: 'Supply agreement for 100 % renewable electricity with fixed unit rates for 12 months. '
          + 'It renews automatically by 12 months unless cancelled one month before the end of the term; '
          + 'the monthly payment is €118.00.',
        category: 'Vertrag',
        correspondent: 'Brightwave Energy',
        documentDate: contractStart.iso,
        tags: ['electricity', 'utilities', 'household', 'fixed price'],
        keyFacts: [
          { label: 'Contract number', value: 'BW-C-55120' },
          { label: 'Start of supply', value: contractStart.short },
          { label: 'Minimum term', value: '12 months' },
          { label: 'Notice period', value: '1 month before end of term' },
          { label: 'Monthly payment', value: '€118.00' },
        ],
      },
    },
    {
      file: 'harbourline-car-insurance.pdf',
      type: 'Insurance',
      pages: [`
        ${brand('Harbourline Insurance', '#0e7490', 'Motor · Home · Travel')}
        ${recipient}
        <div class="meta">Policy no. HL-2291-7730<br>Date ${insuranceDate.long}</div>
        <h1>Your motor insurance renewal</h1>
        <p>Dear Alex Morgan, your comprehensive car insurance renews soon. Please check the details below.</p>
        ${facts([
          ['Vehicle', 'Hatchback, 1.5 petrol, 2022'],
          ['Cover', 'Comprehensive, protected no-claims bonus'],
          ['No-claims bonus', '6 years'],
          ['Renewal date', insuranceRenewal.long],
          ['Annual premium', '€684.00'],
          ['Excess', '€300.00'],
        ])}
        <div class="box">If you would like to make changes or cancel, please contact us before <b>${insuranceRenewal.long}</b>.
          Otherwise your policy renews automatically and the premium is collected in 12 monthly instalments of €57.00.</div>
        ${table(['Premium breakdown', 'Amount'], [
          ['Comprehensive cover', '€598.00'],
          ['Windscreen cover', '€38.00'],
          ['Breakdown assistance', '€48.00'],
        ], ['Annual premium', '€684.00'])}
        ${footer('Harbourline Insurance DAC is regulated by the Central Bank. Example document for screenshots.')}
      `],
      analysis: {
        title: 'Motor insurance renewal',
        summary: `Harbourline Insurance announces the renewal of the comprehensive car insurance on ${insuranceRenewal.long} `
          + 'for an annual premium of €684.00. Changes or a cancellation must be made before that date.',
        category: 'Versicherung',
        correspondent: 'Harbourline Insurance',
        documentDate: insuranceDate.iso,
        tags: ['car', 'motor insurance', 'renewal'],
        keyFacts: [
          { label: 'Policy number', value: 'HL-2291-7730' },
          { label: 'Renewal date', value: insuranceRenewal.short },
          { label: 'Annual premium', value: '€684.00' },
          { label: 'Excess', value: '€300.00' },
          { label: 'No-claims bonus', value: '6 years' },
        ],
      },
    },
    {
      file: 'northbank-statement.pdf',
      type: 'Bank statement',
      pages: [
        `
        ${brand('Northbank', '#1d4ed8', 'Current account statement')}
        ${recipient}
        <div class="meta">Statement no. 10/2026<br>IBAN IE12 NBXX 9310 1522 8840 17<br>Period ends ${statementEnd.long}</div>
        <h1>Account statement</h1>
        ${table(['Date', 'Description', 'Amount'], [
          [day(-52).short, 'Opening balance', '€3,412.08'],
          [day(-50).short, 'Salary Corrib Software Ltd', '+€3,180.55'],
          [day(-49).short, 'Rent – Kestrel Property Management', '−€1,450.00'],
          [day(-47).short, 'Brightwave Energy direct debit', '−€118.00'],
          [day(-45).short, 'Harbourline Insurance', '−€57.00'],
          [day(-41).short, 'Skylink Mobile', '−€29.99'],
          [day(-39).short, 'Supermarket card payment', '−€86.40'],
          [day(-36).short, 'Pulse Gym membership', '−€39.00'],
          [day(-33).short, 'Oakline Furniture', '−€1,240.00'],
        ])}
        ${footer('Northbank plc · Example document for screenshots · Page 1 of 2')}
      `,
        `
        ${brand('Northbank', '#1d4ed8', 'Current account statement')}
        ${table(['Date', 'Description', 'Amount'], [
          [day(-31).short, 'Supermarket card payment', '−€64.12'],
          [day(-29).short, 'Transfer to savings', '−€500.00'],
          [day(-27).short, 'Seaview Dental Practice', '−€95.00'],
          [day(-24).short, 'Cash withdrawal', '−€100.00'],
          [statementEnd.short, 'Closing balance', '€2,858.12'],
        ])}
        ${footer('Northbank plc · Example document for screenshots · Page 2 of 2')}
      `,
      ],
      analysis: {
        title: `Account statement ${day(-30).month}`,
        summary: 'Monthly statement of the current account at Northbank with salary, rent, utilities and card payments. '
          + 'The balance went from €3,412.08 to €2,858.12.',
        category: 'Kontoauszug',
        correspondent: 'Northbank',
        documentDate: statementEnd.iso,
        tags: ['bank', 'current account', 'finances'],
        keyFacts: [
          { label: 'IBAN', value: 'IE12 NBXX 9310 1522 8840 17' },
          { label: 'Opening balance', value: '€3,412.08' },
          { label: 'Closing balance', value: '€2,858.12' },
          { label: 'Statement number', value: '10/2026' },
        ],
      },
    },
    {
      file: 'corrib-payslip.pdf',
      type: 'Payslip',
      pages: [`
        ${brand('Corrib Software Ltd', '#7c3aed', 'Payroll')}
        ${recipient}
        <div class="meta">Employee no. 0042<br>Pay date ${day(-50).long}</div>
        <h1>Payslip – ${day(-50).month}</h1>
        ${table(['Payments', 'Amount'], [['Basic salary', '€4,250.00'], ['Remote working allowance', '€96.00']], ['Gross pay', '€4,346.00'])}
        ${table(['Deductions', 'Amount'], [['Income tax', '€742.15'], ['Social insurance', '€173.84'], ['Universal social charge', '€149.46'], ['Pension contribution 5 %', '€100.00']], ['Total deductions', '€1,165.45'])}
        <div class="box"><b>Net pay €3,180.55</b> paid to account ending in 8840 17.</div>
        ${footer('Corrib Software Ltd · Example document for screenshots')}
      `],
      analysis: {
        title: `Payslip ${day(-50).month}`,
        summary: 'Monthly payslip from Corrib Software with a gross pay of €4,346.00 and a net pay of €3,180.55.',
        category: 'Gehaltsabrechnung',
        correspondent: 'Corrib Software',
        documentDate: day(-50).iso,
        tags: ['salary', 'payroll', 'tax'],
        keyFacts: [
          { label: 'Gross pay', value: '€4,346.00' },
          { label: 'Net pay', value: '€3,180.55' },
          { label: 'Income tax', value: '€742.15' },
          { label: 'Employee number', value: '0042' },
        ],
      },
    },
    {
      file: 'lakeside-property-tax.pdf',
      type: 'Tax',
      pages: [`
        ${brand('Lakeside County Council', '#15803d', 'Finance Department')}
        ${recipient}
        <div class="meta">Reference LPT-88213-K<br>Date ${taxDate.long}</div>
        <h1>Local property tax ${new Date().getFullYear() + 1}</h1>
        <p>The local property tax for the property at 7 Willow Crescent has been assessed as follows.</p>
        ${facts([
          ['Valuation band', '€350,001 – €437,500'],
          ['Annual tax', '€405.00'],
          ['Payment deadline', taxDue.long],
          ['Payment options', 'single payment, direct debit or instalments'],
        ])}
        <div class="box">Please pay or choose a payment option by <b>${taxDue.long}</b>. If you think the valuation is
          wrong, you can object within 30 days of this notice.</div>
        ${footer('Lakeside County Council · Example document for screenshots')}
      `],
      analysis: {
        title: `Local property tax ${new Date().getFullYear() + 1}`,
        summary: `Lakeside County Council sets the local property tax at €405.00 a year. Payment or a payment option `
          + `must be chosen by ${taxDue.long}; objections are possible within 30 days.`,
        category: 'Steuer',
        correspondent: 'Lakeside County Council',
        documentDate: taxDate.iso,
        tags: ['property tax', 'house', 'council'],
        keyFacts: [
          { label: 'Reference', value: 'LPT-88213-K' },
          { label: 'Annual tax', value: '€405.00' },
          { label: 'Payment deadline', value: taxDue.short },
          { label: 'Objection deadline', value: day(16).short },
        ],
      },
    },
    {
      file: 'seaview-dental-invoice.pdf',
      type: 'Invoice',
      pages: [`
        ${brand('Seaview Dental Practice', '#0891b2', 'Dr. Niamh Keane & team')}
        ${recipient}
        <div class="meta">Invoice no. SD-4471<br>Date ${dentalDate.long}</div>
        <h1>Invoice for dental treatment</h1>
        ${table(['Treatment', 'Date', 'Amount'], [['Check-up and cleaning', dentalDate.short, '€75.00'], ['Bitewing x-ray', dentalDate.short, '€20.00']], ['Total', '', '€95.00'])}
        <p>Please pay within 30 days, by <b>${dentalDue.long}</b>. Keep this invoice for your health insurance claim.</p>
        ${footer('Seaview Dental Practice · Example document for screenshots')}
      `],
      analysis: {
        title: 'Dental check-up invoice',
        summary: `Seaview Dental Practice charges €95.00 for a check-up, cleaning and x-ray. Payable by ${dentalDue.long}; `
          + 'the invoice can be submitted to the health insurer.',
        category: 'Rechnung',
        correspondent: 'Seaview Dental Practice',
        documentDate: dentalDate.iso,
        tags: ['health', 'dentist', 'insurance claim'],
        keyFacts: [
          { label: 'Invoice number', value: 'SD-4471' },
          { label: 'Amount', value: '€95.00' },
          { label: 'Payable by', value: dentalDue.short },
        ],
      },
    },
    {
      file: 'skylink-mobile-invoice.pdf',
      type: 'Invoice',
      pages: [`
        ${brand('Skylink Mobile', '#db2777', 'Unlimited 5G')}
        ${recipient}
        <div class="meta">Account 300-118-462<br>Date ${mobileDate.long}</div>
        <h1>Your mobile bill</h1>
        ${table(['Item', 'Amount'], [['Unlimited 5G plan', '€24.99'], ['Roaming add-on', '€5.00']], ['Total', '€29.99'])}
        <p>The amount will be collected by direct debit on ${day(5).long}.</p>
        ${footer('Skylink Mobile Ltd · Example document for screenshots')}
      `],
      analysis: {
        title: `Mobile phone bill ${mobileDate.month}`,
        summary: 'Monthly bill from Skylink Mobile for the Unlimited 5G plan and a roaming add-on, €29.99 in total, '
          + 'collected by direct debit.',
        category: 'Rechnung',
        correspondent: 'Skylink Mobile',
        documentDate: mobileDate.iso,
        tags: ['mobile', 'phone', 'utilities'],
        keyFacts: [
          { label: 'Account number', value: '300-118-462' },
          { label: 'Total amount', value: '€29.99' },
          { label: 'Direct debit on', value: day(5).short },
        ],
      },
    },
    {
      file: 'kestrel-tenancy-agreement.pdf',
      type: 'Contract',
      pages: [
        `
        ${brand('Kestrel Property Management', '#9333ea', 'Lettings · Galway')}
        <h1 style="margin-top:10mm">Residential tenancy agreement</h1>
        ${facts([
          ['Property', 'Apartment 4B, 12 Harbour Road, Galway'],
          ['Tenant', 'Alex Morgan'],
          ['Landlord', 'Harbour Road Residences Ltd, represented by Kestrel Property Management'],
          ['Start of tenancy', tenancyStart.long],
          ['Term', 'periodic (no fixed end date)'],
          ['Monthly rent', '€1,450.00, payable on the 1st of each month'],
          ['Deposit', '€1,450.00'],
          ['Notice period (tenant)', '3 months'],
        ])}
        <h2>1. Rent</h2><p>The rent is paid by standing order to the account named by the landlord.</p>
        <h2>2. Deposit</h2><p>The deposit is held for the duration of the tenancy and returned within 14 days of its end.</p>
        ${footer('Example document for screenshots · Page 1 of 3')}
      `,
        `
        <h2 style="margin-top:6mm">3. Use of the property</h2><p>The property is let as a private residence for the tenant and members of the household.</p>
        <h2>4. Repairs</h2><p>The landlord keeps the structure and the installations in good repair. The tenant reports defects without delay.</p>
        <h2>5. Pets</h2><p>Small pets are allowed with the written consent of the landlord.</p>
        <h2>6. Ending the tenancy</h2><p>The tenant may end the tenancy with three months' notice in writing.</p>
        ${footer('Example document for screenshots · Page 2 of 3')}
      `,
        `
        <h2 style="margin-top:6mm">7. Signatures</h2>
        <div class="sign"><div>Kestrel Property Management</div><div>Alex Morgan</div></div>
        ${footer('Example document for screenshots · Page 3 of 3')}
      `,
      ],
      analysis: {
        title: 'Tenancy agreement Apartment 4B, Harbour Road',
        summary: 'Periodic tenancy agreement for Apartment 4B, 12 Harbour Road in Galway at €1,450.00 a month '
          + 'with a deposit of €1,450.00. The tenant can end it with three months’ notice.',
        category: 'Vertrag',
        correspondent: 'Kestrel Property Management',
        documentDate: tenancyStart.iso,
        tags: ['apartment', 'rent', 'tenancy'],
        keyFacts: [
          { label: 'Monthly rent', value: '€1,450.00' },
          { label: 'Deposit', value: '€1,450.00' },
          { label: 'Start of tenancy', value: tenancyStart.short },
          { label: 'Notice period', value: '3 months' },
        ],
      },
    },
    {
      file: 'oakline-delivery-note.pdf',
      type: 'Delivery note',
      pages: [`
        ${brand('Oakline Furniture', '#92400e', 'Solid wood, made to last')}
        ${recipient}
        <div class="meta">Delivery note DN-20931<br>Order 77-4410<br>Date ${deliveryDate.long}</div>
        <h1>Delivery note</h1>
        ${table(['Item', 'Qty', 'Article no.'], [['Dining table, oak, 180 × 90 cm', '1', 'OF-T180'], ['Dining chair, oak, linen seat', '4', 'OF-C22'], ['Care oil, 250 ml', '1', 'OF-OIL']])}
        <p>Goods received in good condition. 5-year warranty on frame and joints from the date of delivery.</p>
        <div class="stamp">DELIVERED</div>
        ${footer('Oakline Furniture · Example document for screenshots')}
      `],
      analysis: {
        title: 'Delivery note dining table and chairs',
        summary: 'Oakline Furniture delivered an oak dining table, four chairs and care oil. '
          + 'A five-year warranty applies from the date of delivery.',
        category: 'Lieferschein',
        correspondent: 'Oakline Furniture',
        documentDate: deliveryDate.iso,
        tags: ['furniture', 'warranty', 'household'],
        keyFacts: [
          { label: 'Delivery note', value: 'DN-20931' },
          { label: 'Order number', value: '77-4410' },
          { label: 'Warranty until', value: day(-17 + 5 * 365).short },
        ],
      },
    },
    {
      file: 'greenroof-solar-quote.pdf',
      type: 'Quote',
      pages: [`
        ${brand('GreenRoof Solar', '#16a34a', 'Solar panels and storage')}
        ${recipient}
        <div class="meta">Quote Q-3318<br>Date ${quoteDate.long}</div>
        <h1>Quote: 6.4 kWp solar installation</h1>
        ${table(['Item', 'Amount'], [
          ['16 × 400 W panels, all-black', '€5,120.00'],
          ['Hybrid inverter 6 kW', '€1,980.00'],
          ['Battery 10 kWh', '€4,150.00'],
          ['Mounting, cabling, installation', '€1,950.00'],
          ['Grant (estimated)', '−€1,350.00'],
        ], ['Total incl. VAT', '€11,850.00'])}
        <div class="box">This quote is valid until <b>${quoteValid.long}</b>. Installation can start about six weeks
          after the order.</div>
        ${footer('GreenRoof Solar Ltd · Example document for screenshots')}
      `],
      analysis: {
        title: 'Quote 6.4 kWp solar installation',
        summary: `GreenRoof Solar offers a 6.4 kWp solar installation with a 10 kWh battery for €11,850.00 after the grant. `
          + `The quote is valid until ${quoteValid.long}.`,
        category: 'Angebot',
        correspondent: 'GreenRoof Solar',
        documentDate: quoteDate.iso,
        tags: ['solar', 'house', 'energy'],
        keyFacts: [
          { label: 'Quote number', value: 'Q-3318' },
          { label: 'Total', value: '€11,850.00' },
          { label: 'Valid until', value: quoteValid.short },
          { label: 'System size', value: '6.4 kWp' },
        ],
      },
    },
    {
      file: 'pulse-gym-cancellation.pdf',
      type: 'Cancellation',
      pages: [`
        ${brand('Pulse Gym', '#dc2626', 'Train smarter')}
        ${recipient}
        <div class="meta">Member no. PG-60218<br>Date ${gymDate.long}</div>
        <h1>Confirmation of your cancellation</h1>
        <p>Dear Alex, we have received your cancellation. Your membership ends on <b>${gymEnd.long}</b>.
          Until then you can keep using all our facilities. The last monthly fee of €39.00 is collected as usual.</p>
        <p>We are sorry to see you go – you are welcome back any time.</p>
        ${footer('Pulse Gym · Example document for screenshots')}
      `],
      analysis: {
        title: 'Gym membership cancellation confirmed',
        summary: `Pulse Gym confirms the cancellation of the membership, which ends on ${gymEnd.long}. `
          + 'The last monthly fee of €39.00 is collected as usual.',
        category: 'Kündigung',
        correspondent: 'Pulse Gym',
        documentDate: gymDate.iso,
        tags: ['gym', 'membership', 'sport'],
        keyFacts: [
          { label: 'Member number', value: 'PG-60218' },
          { label: 'Membership expires', value: gymEnd.short },
          { label: 'Monthly fee', value: '€39.00' },
        ],
      },
    },
    {
      file: 'first-aid-certificate.pdf',
      type: 'Certificate',
      pages: [`
        <div style="border:6px double #b45309; height:100%; padding:20mm; text-align:center">
          <div class="name" style="color:#b45309; font-size:13pt; letter-spacing:3px">RED HARBOUR TRAINING</div>
          <h1 style="font-size:28pt; margin:18mm 0 8mm">Certificate</h1>
          <p style="font-size:12pt">This is to certify that</p>
          <p style="font-size:20pt; font-weight:700; margin:6mm 0">Alex Morgan</p>
          <p style="font-size:12pt">has successfully completed the course</p>
          <p style="font-size:15pt; font-weight:600">Occupational First Aid (3 days)</p>
          <p style="margin-top:10mm">Course date: ${courseDate.long} · Valid until: ${certValid.long}</p>
          <div class="sign" style="justify-content:center"><div>Course instructor</div><div>Director of training</div></div>
        </div>
      `],
      analysis: {
        title: 'First aid certificate',
        summary: `Certificate from Red Harbour Training for the three-day Occupational First Aid course on ${courseDate.long}. `
          + `It is valid until ${certValid.long}.`,
        category: 'Bescheinigung',
        correspondent: 'Red Harbour Training',
        documentDate: courseDate.iso,
        tags: ['first aid', 'training', 'certificate'],
        keyFacts: [
          { label: 'Course', value: 'Occupational First Aid' },
          { label: 'Valid until', value: certValid.short },
        ],
      },
    },
  ]
}
