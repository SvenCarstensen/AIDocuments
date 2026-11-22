// Holt den PDF.js-Betrachter von Mozilla (Apache-2.0) in einer festen Version nach public/pdfjs.
// Die Dokumentseite bettet ihn ein: Er läuft unter derselben Adresse wie die Anwendung, so lassen sich Änderungen
// am PDF (Markierungen, Notizen, Unterschriften …) auslesen und speichern. Läuft vor "dev" und "build"; ist die
// Version schon da, passiert nichts. Die Prüfsumme stellt sicher, dass genau diese Fassung verwendet wird.
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const VERSION = '6.5.438'
const SHA256 = 'fcbe9ba21d9911843e3502adc178a7f549a524a4055f887bedc942e5490c6ba1'
const URL_ = `https://github.com/mozilla/pdf.js/releases/download/v${VERSION}/pdfjs-${VERSION}-dist.zip`

const target = fileURLToPath(new URL('../public/pdfjs/', import.meta.url))
const marker = join(target, 'VERSION')
// Stand der eigenen Anpassungen (harden unten); erhöhen, wenn sich daran etwas ändert
const HARDENING = '1'
const hardeningMarker = join(target, '.hardening')

/**
 * Sichere Voreinstellungen direkt im Betrachter: keine Skripte aus PDFs, keine XFA-Formulare. Die Dokumentseite
 * setzt beides zwar auch, aber wer viewer.html direkt in einem Tab öffnet (etwa über einen zugeschickten Link),
 * bekäme sonst die Voreinstellungen von PDF.js.
 */
const harden = () => {
  const viewer = join(target, 'web', 'viewer.mjs')
  let code = readFileSync(viewer, 'utf8')
  for (const option of ['enableScripting', 'enableXfa']) {
    const pattern = new RegExp(`(\\["${option}", \\{\\s*value: )true`)
    if (!pattern.test(code)) {
      console.error(`PDF.js: Voreinstellung ${option} nicht gefunden – Anpassung prüfen`)
      process.exit(1)
    }
    code = code.replace(pattern, '$1false')
  }
  writeFileSync(viewer, code)
  writeFileSync(hardeningMarker, HARDENING + '\n')
}

if (existsSync(marker) && readFileSync(marker, 'utf8').trim() === VERSION) {
  if (!existsSync(hardeningMarker) || readFileSync(hardeningMarker, 'utf8').trim() !== HARDENING) {
    harden()
    console.log('PDF.js: sichere Voreinstellungen gesetzt')
  }
  process.exit(0)
}

console.log(`PDF.js ${VERSION} wird geladen …`)
const response = await fetch(URL_)
if (!response.ok) {
  console.error(`PDF.js konnte nicht geladen werden: HTTP ${response.status}`)
  process.exit(1)
}
const zip = Buffer.from(await response.arrayBuffer())
const actual = createHash('sha256').update(zip).digest('hex')
if (actual !== SHA256) {
  console.error(`Prüfsumme von PDF.js stimmt nicht: ${actual}`)
  process.exit(1)
}

const zipFile = join(tmpdir(), `pdfjs-${VERSION}-${process.pid}.zip`)
writeFileSync(zipFile, zip)
rmSync(target, { recursive: true, force: true })
mkdirSync(target, { recursive: true })
try {
  execFileSync('unzip', ['-q', '-o', zipFile, '-d', target], { stdio: 'inherit' })
} finally {
  rmSync(zipFile, { force: true })
}

// Was die Anwendung nicht braucht: Quellkarten und das Beispiel-PDF
const removeUnneeded = (dir) => {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) removeUnneeded(path)
    else if (name.endsWith('.map') || name.endsWith('.pdf')) rmSync(path)
  }
}
removeUnneeded(target)
harden()
writeFileSync(marker, VERSION + '\n')
console.log(`PDF.js ${VERSION} liegt in public/pdfjs`)
