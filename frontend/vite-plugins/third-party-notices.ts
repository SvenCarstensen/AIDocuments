// Erzeugt beim Bauen THIRD-PARTY-NOTICES.txt mit Lizenzhinweisen aller Pakete, die tatsächlich im Bundle landen.
// MIT, BSD, ISC und Apache 2.0 verlangen, dass Copyright- und Lizenztexte mit ausgeliefert werden; der Text steht
// auf Englisch, wie bei solchen Hinweisen üblich. Im Docker-Build ergänzt das Backend die Datei um seine
// Bibliotheken und die Laufzeitumgebung (backend/tools/ThirdPartyNotices.java).
import { createHash } from 'node:crypto'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import type { Plugin } from 'vite'

type Options = {
  /** Pakete, die nicht über JavaScript eingebunden werden (z. B. Schriften per CSS-@import). */
  extraPackages?: string[]
  /**
   * Verzeichnisse mit fremden Dateien, die unverändert ausgeliefert werden (z. B. PDF.js in public/). Ohne Angabe
   * gilt als Version der Inhalt einer Datei VERSION im Verzeichnis.
   */
  bundledDirectories?: Array<{ name: string; version?: string; license: string; url: string; dir: string }>
}

type Component = { name: string; version: string; license: string; url: string; texts: string[] }

const LICENSE_FILE = /^(licen[cs]e|copying|notice)([._-].*)?$/i

export function thirdPartyNotices(options: Options = {}): Plugin {
  let root = process.cwd()
  return {
    name: 'third-party-notices',
    apply: 'build',
    configResolved(config) {
      root = config.root
    },
    generateBundle(_, bundle) {
      const packageDirs = new Set<string>()
      for (const item of Object.values(bundle)) {
        if (item.type !== 'chunk') continue
        const ids = item.moduleIds ?? Object.keys(item.modules ?? {})
        for (const id of ids) {
          const dir = packageDirOf(id)
          if (dir) packageDirs.add(dir)
        }
      }
      for (const name of options.extraPackages ?? []) {
        const dir = join(root, 'node_modules', name)
        if (existsSync(join(dir, 'package.json'))) packageDirs.add(dir)
      }

      const components: Component[] = [...packageDirs].map(fromPackage)
      for (const extra of options.bundledDirectories ?? []) {
        const dir = join(root, extra.dir)
        if (!existsSync(dir)) continue
        const versionFile = join(dir, 'VERSION')
        components.push({
          name: extra.name,
          version: extra.version ?? (existsSync(versionFile) ? readFileSync(versionFile, 'utf8').trim() : ''),
          license: extra.license,
          url: extra.url,
          texts: licenseFilesIn(dir, true).map((file) => `[${relative(dir, file)}]\n${readFileSync(file, 'utf8').trim()}`),
        })
      }
      components.sort((a, b) => a.name.localeCompare(b.name))

      this.emitFile({ type: 'asset', fileName: 'THIRD-PARTY-NOTICES.txt', source: render(components) })
    },
  }
}

/** Paketverzeichnis zu einer Modul-ID unter node_modules, auch bei Paketen mit Scope (@scope/name). */
function packageDirOf(id: string): string | null {
  const clean = id.replace(/^\0/, '').split('?')[0]
  const marker = `${sep}node_modules${sep}`
  const at = clean.lastIndexOf(marker)
  if (at < 0) return null
  const rest = clean.slice(at + marker.length).split(sep)
  const nameParts = rest[0]?.startsWith('@') ? 2 : 1
  const dir = clean.slice(0, at + marker.length) + rest.slice(0, nameParts).join(sep)
  return existsSync(join(dir, 'package.json')) ? dir : null
}

function fromPackage(dir: string): Component {
  const pkg = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'))
  const repository = typeof pkg.repository === 'string' ? pkg.repository : pkg.repository?.url
  return {
    name: pkg.name,
    version: pkg.version ?? '',
    license: typeof pkg.license === 'string' ? pkg.license : JSON.stringify(pkg.license ?? pkg.licenses ?? 'UNKNOWN'),
    url: String(pkg.homepage ?? repository ?? `https://www.npmjs.com/package/${pkg.name}`).replace(/^git\+/, ''),
    texts: licenseFilesIn(dir, false).map((file) => readFileSync(file, 'utf8').trim()),
  }
}

function licenseFilesIn(dir: string, recursive: boolean): string[] {
  const found: string[] = []
  for (const name of readdirSync(dir).sort()) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) {
      if (recursive) found.push(...licenseFilesIn(path, true))
    } else if (LICENSE_FILE.test(name)) {
      found.push(path)
    }
  }
  return found
}

function render(components: Component[]): string {
  const lines = [
    'THIRD-PARTY SOFTWARE NOTICES – AIDocuments web interface',
    '',
    'The AIDocuments web interface includes the following third-party components.',
    'Each component is distributed under its own license; the license texts follow below.',
    '',
  ]
  for (const c of components) lines.push(`  ${c.name} ${c.version}`.trimEnd() + ` – ${c.license} – ${c.url}`)

  // Gleiche Lizenztexte nur einmal abdrucken, mit allen Komponenten, die ihn verwenden
  const byText = new Map<string, { names: string[]; text: string }>()
  for (const c of components) {
    for (const text of c.texts) {
      const key = createHash('sha256').update(text).digest('hex')
      const entry = byText.get(key) ?? { names: [], text }
      entry.names.push(c.version ? `${c.name} ${c.version}` : c.name)
      byText.set(key, entry)
    }
  }
  const withoutText = components.filter((c) => c.texts.length === 0)
  for (const { names, text } of byText.values()) {
    lines.push('', '='.repeat(78), names.join(', '), '='.repeat(78), '', text)
  }
  if (withoutText.length) {
    lines.push('', '='.repeat(78), 'Components without a bundled license file', '='.repeat(78), '')
    for (const c of withoutText) lines.push(`  ${c.name} ${c.version} – ${c.license}; full text: ${c.url}`)
  }
  return lines.join('\n') + '\n'
}
