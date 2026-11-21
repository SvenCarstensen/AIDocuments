// Typen für die Übersetzungsschlüssel: en.json ist die Vorlage, an der t('…') beim Übersetzen geprüft wird.
// Ein Tippfehler im Schlüssel oder ein in en.json fehlender Text fällt damit schon beim Bauen auf.
import 'i18next'
import type en from './locales/en.json'

declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'translation'
    resources: { translation: typeof en }
  }
}
