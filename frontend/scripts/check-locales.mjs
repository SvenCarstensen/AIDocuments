#!/usr/bin/env node
// Prüft die Übersetzungsdateien in src/locales gegen die Vorlage en.json.
//
//   npm run check:locales            alle Dateien
//   npm run check:locales -- de fr   nur die genannten Sprachen
//
// Geprüft wird je Datei:
//   - gültiges JSON, nur verschachtelte Objekte mit Texten, kein leerer Text
//   - dieselben Schlüssel wie en.json – nichts fehlt, nichts ist zu viel
//   - Mehrzahlformen: genau die Formen, die die Sprache kennt (Intl.PluralRules), als _one, _few, _many …
//   - dieselben Platzhalter {{name}} bzw. {{name, format}} wie im englischen Text
//   - dieselben Marken <name>…</name> wie im englischen Text, sauber geöffnet und geschlossen
//
// Endet mit Code 1, sobald eine Datei einen Fehler hat. Hinweise (⚠) führen nicht zum Abbruch.

import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const localesDir = join(root, 'src', 'locales')
const REFERENCE = 'en'

/** Die Endungen, mit denen i18next die Mehrzahlformen unterscheidet – in der Reihenfolge von Intl.PluralRules. */
const PLURAL_CATEGORIES = ['zero', 'one', 'two', 'few', 'many', 'other']
const PLURAL_SUFFIX = new RegExp(`^(.+)_(${PLURAL_CATEGORIES.join('|')})$`)

// ---------- Einlesen ----------

/** Macht aus dem verschachtelten Objekt eine flache Liste "bereich.schlüssel" → Text und sammelt Formfehler. */
function flatten(node, prefix, entries, errors) {
  for (const [key, value] of Object.entries(node)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (key.includes('.')) errors.push(`${path}: Schlüsselnamen dürfen keinen Punkt enthalten`)
    if (typeof value === 'string') {
      entries.set(path, value)
    } else if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      flatten(value, path, entries, errors)
    } else {
      errors.push(`${path}: erwartet wird ein Text oder ein Objekt, gefunden: ${value === null ? 'null' : Array.isArray(value) ? 'Liste' : typeof value}`)
    }
  }
  return entries
}

function readLocale(code) {
  const errors = []
  let data
  try {
    data = JSON.parse(readFileSync(join(localesDir, `${code}.json`), 'utf8'))
  } catch (error) {
    return { entries: new Map(), errors: [`kein gültiges JSON: ${error.message}`], broken: true }
  }
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    return { entries: new Map(), errors: ['die Datei muss ein JSON-Objekt enthalten'], broken: true }
  }
  return { entries: flatten(data, '', new Map(), errors), errors, broken: false }
}

// ---------- Platzhalter und Marken ----------

/** {{ count, number }} → "count, number": Name und Format zählen, Leerzeichen nicht. */
function placeholders(text) {
  const found = new Set()
  for (const match of text.matchAll(/\{\{([^{}]*)\}\}/g)) {
    found.add(match[1].split(',').map((part) => part.trim()).join(', '))
  }
  return found
}

/** Namen der Marken im Text; meldet außerdem Marken, die nicht sauber geöffnet und geschlossen sind. */
function tags(text, problems) {
  const names = new Set()
  const open = []
  for (const match of text.matchAll(/<(\/?)([A-Za-z][\w-]*)\s*(\/?)>/g)) {
    const [, closing, name, selfClosing] = match
    names.add(name)
    if (selfClosing) continue
    if (!closing) {
      open.push(name)
    } else if (open.pop() !== name) {
      problems?.push(`</${name}> schließt keine passende Marke`)
      return names
    }
  }
  if (open.length > 0) problems?.push(`<${open[open.length - 1]}> wird nicht geschlossen`)
  return names
}

const list = (set) => (set.size > 0 ? [...set].map((item) => `"${item}"`).join(', ') : '–')
const union = (sets) => new Set(sets.flatMap((set) => [...set]))
const intersection = (sets) => new Set([...sets[0]].filter((item) => sets.every((set) => set.has(item))))

/**
 * Vergleicht Platzhalter oder Marken eines Textes mit der Vorlage. Bei Mehrzahlformen dürfen einzelne Formen
 * der Vorlage etwas weglassen (etwa die Zahl in "This document …"); verlangt wird deshalb, was in allen
 * englischen Formen steht, und erlaubt ist, was in irgendeiner steht.
 */
function compareSets(kind, actual, references) {
  const required = intersection(references)
  const allowed = union(references)
  const missing = new Set([...required].filter((item) => !actual.has(item)))
  const unknown = new Set([...actual].filter((item) => !allowed.has(item)))
  const problems = []
  if (missing.size > 0) problems.push(`${kind} fehlt: ${list(missing)}`)
  if (unknown.size > 0) problems.push(`${kind} unbekannt: ${list(unknown)} (in en.json: ${list(allowed)})`)
  return problems
}

// ---------- Mehrzahlformen ----------

/**
 * Teilt die Schlüssel in einfache Texte und Mehrzahlgruppen. Eine Gruppe ist alles, was sich nur in der Endung
 * _one/_other/… unterscheidet; ob ein Schlüssel zu einer Gruppe gehört, legt die Vorlage fest.
 */
function splitPlurals(entries, pluralBases) {
  const plain = new Map()
  const groups = new Map()
  for (const [path, text] of entries) {
    const match = path.match(PLURAL_SUFFIX)
    if (match && (pluralBases ? pluralBases.has(match[1]) : entries.has(`${match[1]}_other`))) {
      if (!groups.has(match[1])) groups.set(match[1], new Map())
      groups.get(match[1]).set(match[2], text)
    } else {
      plain.set(path, text)
    }
  }
  return { plain, groups }
}

function pluralCategories(code, warnings) {
  let known = false
  try {
    known = Intl.PluralRules.supportedLocalesOf(code).length > 0
  } catch {
    /* kein gültiges Sprachkennzeichen – das meldet die Prüfung des Dateinamens */
  }
  if (!known) {
    warnings.push(`Intl kennt für "${code}" keine Mehrzahlregeln – geprüft wird mit one/other`)
    return ['one', 'other']
  }
  const categories = new Intl.PluralRules(code).resolvedOptions().pluralCategories
  return PLURAL_CATEGORIES.filter((category) => categories.includes(category))
}

// ---------- Prüfung ----------

function checkTexts(entries, errors) {
  for (const [path, text] of entries) {
    if (text.trim() === '') errors.push(`${path}: der Text ist leer`)
    const problems = []
    tags(text, problems)
    for (const problem of problems) errors.push(`${path}: ${problem}`)
    if ((text.match(/\{\{/g) ?? []).length !== (text.match(/\}\}/g) ?? []).length) {
      errors.push(`${path}: Platzhalter nicht sauber mit {{ und }} geklammert`)
    }
  }
}

function checkLocale(code, reference) {
  const { entries, errors, broken } = readLocale(code)
  const warnings = []
  if (broken) return { errors, warnings, count: 0 }

  try {
    Intl.getCanonicalLocales(code)
  } catch {
    errors.push(`"${code}" ist kein gültiges Sprachkennzeichen – die Datei muss wie die Sprache heißen, z. B. fr.json`)
    return { errors, warnings, count: entries.size }
  }

  checkTexts(entries, errors)
  const expected = pluralCategories(code, warnings)
  const { plain, groups } = splitPlurals(entries, new Set(reference.groups.keys()))

  // Einfache Texte
  for (const [path, referenceText] of reference.plain) {
    const text = plain.get(path)
    if (text === undefined) {
      errors.push(`${path}: fehlt`)
      continue
    }
    for (const problem of compareSets('Platzhalter', placeholders(text), [placeholders(referenceText)])) errors.push(`${path}: ${problem}`)
    for (const problem of compareSets('Marke', tags(text), [tags(referenceText)])) errors.push(`${path}: ${problem}`)
  }
  for (const path of plain.keys()) {
    if (reference.plain.has(path)) continue
    const match = path.match(PLURAL_SUFFIX)
    errors.push(
      match && reference.plain.has(match[1])
        ? `${path}: "${match[1]}" hat in en.json keine Mehrzahlformen – die Endung _${match[2]} ist hier falsch`
        : `${path}: gibt es in en.json nicht`,
    )
  }

  // Mehrzahlgruppen
  for (const [base, referenceForms] of reference.groups) {
    const forms = groups.get(base)
    if (!forms) {
      errors.push(`${base}: fehlt – erwartet werden die Formen ${expected.map((category) => `${base}_${category}`).join(', ')}`)
      continue
    }
    const missing = expected.filter((category) => !forms.has(category))
    const surplus = [...forms.keys()].filter((category) => !expected.includes(category))
    if (missing.length > 0) errors.push(`${base}: Mehrzahlform fehlt: ${missing.map((category) => `_${category}`).join(', ')}`)
    if (surplus.length > 0) {
      errors.push(`${base}: Mehrzahlform ${surplus.map((category) => `_${category}`).join(', ')} gibt es in "${code}" nicht (erlaubt: ${expected.map((category) => `_${category}`).join(', ')})`)
    }
    const referenceTexts = [...referenceForms.values()]
    for (const [category, text] of forms) {
      const path = `${base}_${category}`
      for (const problem of compareSets('Platzhalter', placeholders(text), referenceTexts.map((item) => placeholders(item)))) errors.push(`${path}: ${problem}`)
      for (const problem of compareSets('Marke', tags(text), referenceTexts.map((item) => tags(item)))) errors.push(`${path}: ${problem}`)
    }
  }

  return { errors, warnings, count: entries.size }
}

// ---------- Ablauf ----------

const files = readdirSync(localesDir)
  .filter((file) => file.endsWith('.json'))
  .map((file) => file.slice(0, -'.json'.length))
  .sort()

if (!files.includes(REFERENCE)) {
  console.error(`✖ ${REFERENCE}.json fehlt in ${localesDir} – ohne Vorlage gibt es nichts zu prüfen.`)
  process.exit(1)
}

const wanted = process.argv.slice(2)
const unknownArguments = wanted.filter((code) => !files.includes(code))
if (unknownArguments.length > 0) {
  console.error(`✖ Keine Datei für: ${unknownArguments.join(', ')} (vorhanden: ${files.join(', ')})`)
  process.exit(1)
}

const referenceLocale = readLocale(REFERENCE)
const reference = splitPlurals(referenceLocale.entries, null)

// Eigennamen der Sprachen, wie sie die Sprachauswahl zeigt – nur für einen Hinweis, wenn einer fehlt.
const namedCodes = new Set()
try {
  const source = readFileSync(join(root, 'src', 'lib', 'i18n.ts'), 'utf8')
  const table = source.match(/const NATIVE_NAMES[^{]*\{([^}]*)\}/)?.[1] ?? ''
  for (const match of table.matchAll(/^\s*['"]?([A-Za-z-]+)['"]?\s*:/gm)) namedCodes.add(match[1])
} catch {
  /* ohne die Tabelle entfällt nur der Hinweis */
}

console.log(
  `Übersetzungen in src/locales – Vorlage: ${REFERENCE}.json mit ${reference.plain.size + reference.groups.size} Schlüsseln, ` +
    `davon ${reference.groups.size} mit Mehrzahlformen (${referenceLocale.entries.size} Texte)\n`,
)

let failed = 0
for (const code of [REFERENCE, ...files.filter((file) => file !== REFERENCE)]) {
  if (wanted.length > 0 && code !== REFERENCE && !wanted.includes(code)) continue
  const { errors, warnings, count } = checkLocale(code, reference)
  if (namedCodes.size > 0 && !namedCodes.has(code)) {
    warnings.push(`kein Eigenname in src/lib/i18n.ts (NATIVE_NAMES) – die Sprachauswahl zeigt den Namen, den der Browser liefert`)
  }
  const categories = pluralCategories(code, []).map((category) => `_${category}`).join(' ')
  if (errors.length === 0) {
    console.log(`✔ ${code}.json – ${count} Texte, Mehrzahlformen: ${categories}`)
  } else {
    failed += 1
    console.log(`✖ ${code}.json – ${errors.length} Fehler (Mehrzahlformen dieser Sprache: ${categories})`)
    for (const error of errors) console.log(`    ${error}`)
  }
  for (const warning of warnings) console.log(`    ⚠ ${warning}`)
}

if (failed > 0) {
  console.log(`\n${failed} ${failed === 1 ? 'Datei hat' : 'Dateien haben'} Fehler.`)
  process.exit(1)
}
console.log('\nAlles in Ordnung.')
