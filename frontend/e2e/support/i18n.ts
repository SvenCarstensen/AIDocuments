import { readFileSync } from 'node:fs'

/**
 * Die Tests laufen auf Deutsch und suchen Knöpfe und Felder über dieselben Texte wie die Oberfläche – so brechen sie
 * nicht, wenn sich eine Formulierung ändert. Die Screenshots laufen auf Englisch.
 */
const bundles = new Map<string, Record<string, unknown>>()

export function textIn(language: string, key: string, values: Record<string, string | number> = {}): string {
  let bundle = bundles.get(language)
  if (!bundle) {
    bundle = JSON.parse(readFileSync(new URL(`../../src/locales/${language}.json`, import.meta.url), 'utf8')) as Record<string, unknown>
    bundles.set(language, bundle)
  }
  let node: unknown = bundle
  for (const part of key.split('.')) {
    node = (node as Record<string, unknown> | undefined)?.[part]
  }
  if (typeof node !== 'string') throw new Error(`Kein Text für ${key} (${language})`)
  return node.replace(/\{\{\s*(\w+)(?:,[^}]*)?\}\}/g, (_, name: string) => String(values[name] ?? `{{${name}}}`))
}

export const t = (key: string, values: Record<string, string | number> = {}) => textIn('de', key, values)
