/** Eine kleine, gültige PDF mit einer Textzeile je Seite – reicht für Vorschau, Volltext und Seitenbearbeitung. */
export function pdf(pages: string[]): Buffer {
  const objects: string[] = []
  const add = (body: string) => objects.push(body) // Objektnummer = Index + 1
  const escape = (text: string) => text.replace(/[\\()]/g, (char) => `\\${char}`)

  add('<< /Type /Catalog /Pages 2 0 R >>')
  add('') // Seitenbaum, unten gefüllt
  add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
  const kids: string[] = []
  for (const text of pages) {
    const stream = `BT /F1 24 Tf 72 720 Td (${escape(text)}) Tj ET`
    add(`<< /Length ${Buffer.byteLength(stream, 'latin1')} >>\nstream\n${stream}\nendstream`)
    const contents = objects.length
    add(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents ${contents} 0 R >>`)
    kids.push(`${objects.length} 0 R`)
  }
  objects[1] = `<< /Type /Pages /Kids [${kids.join(' ')}] /Count ${pages.length} >>`

  let out = '%PDF-1.4\n'
  const offsets: number[] = []
  objects.forEach((body, index) => {
    offsets.push(Buffer.byteLength(out, 'latin1'))
    out += `${index + 1} 0 obj\n${body}\nendobj\n`
  })
  const xref = Buffer.byteLength(out, 'latin1')
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  for (const offset of offsets) out += `${String(offset).padStart(10, '0')} 00000 n \n`
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return Buffer.from(out, 'latin1')
}
