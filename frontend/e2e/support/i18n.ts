import { readFileSync } from 'node:fs'

/**
 * Die Tests laufen auf Deutsch und suchen Knöpfe und Felder über dieselben Texte wie die Oberfläche – so brechen sie
 * nicht, wenn sich eine Formulierung ändert.
 */
const de = JSON.parse(readFileSync(new URL('../../src/locales/de.json', import.meta.url), 'utf8')) as Record<string, unknown>

export function t(key: string, values: Record<string, string | number> = {}): string {
  let node: unknown = de
  for (const part of key.split('.')) {
    node = (node as Record<string, unknown> | undefined)?.[part]
  }
  if (typeof node !== 'string') throw new Error(`Kein Text für ${key}`)
  return node.replace(/\{\{\s*(\w+)(?:,[^}]*)?\}\}/g, (_, name: string) => String(values[name] ?? `{{${name}}}`))
}
