# Übersetzungen

Jede Sprache der Oberfläche ist eine Datei `<code>.json` in diesem Ordner (`de.json`, `fr.json`, `pt-BR.json` …).
Der Dateiname ist das Sprachkennzeichen nach BCP 47. Liegt die Datei hier, erscheint die Sprache von selbst in der
Sprachauswahl – am Code ist nichts zu ändern.

- **`en.json` ist die Vorlage** und zugleich die Rückfallsprache: Fehlt einer Übersetzung ein Text, erscheint er
  auf Englisch.
- `de.json` ist der ursprüngliche deutsche Wortlaut und hilft, wenn der englische Text mehrdeutig ist.

## Eine Sprache hinzufügen

1. `en.json` kopieren und nach der Sprache benennen, z. B. `fr.json`.
2. Nur die **Texte** (rechts vom Doppelpunkt) übersetzen. Schlüssel (links) und Aufbau bleiben unverändert.
3. Im Ordner `frontend` prüfen: `npm run check:locales` (oder nur eine Sprache: `npm run check:locales -- fr`).

## Schlüssel

Die Schlüssel sind nach Bereichen der Oberfläche geordnet und englisch benannt:

| Bereich | Inhalt |
| --- | --- |
| `common` | Mehrfach verwendete Wörter: Speichern, Abbrechen, Benutzername … |
| `errors` | Fehlermeldungen der Oberfläche |
| `auth` | Anmeldung und Ersteinrichtung |
| `shell` | Kopfleiste, Suche, Benutzermenü, Ablagefläche |
| `upload` | Meldungen beim Hochladen |
| `documents` | Dokumentliste, Filter, Sortierung |
| `document` | Einzelnes Dokument: Ansicht, Bearbeiten, Löschen |
| `settings.mailboxes` / `.import` / `.ai` / `.users` | Die vier Seiten der Einstellungen |

Schlüsselteile in GROSSBUCHSTABEN (`ADMIN`, `MAIL`, `LM_STUDIO`) entsprechen Werten, die der Server liefert.

## Platzhalter `{{…}}`

`{{name}}` wird zur Laufzeit ersetzt. Platzhalter **unverändert übernehmen** – auch mit Zusatz wie
`{{count, number}}` (die Zahl wird dann in der Schreibweise der Sprache ausgegeben). Die Stellung im Satz ist frei:

```json
"deleteUser": "{{name}} löschen"      →      "deleteUser": "Delete {{name}}"
```

## Mehrzahl

Texte mit einer Anzahl gibt es in mehreren Formen, unterschieden durch die Endung des Schlüssels. Englisch und
Deutsch kennen zwei:

```json
"documentCount_one": "{{count, number}} document",
"documentCount_other": "{{count, number}} documents"
```

Andere Sprachen brauchen andere Formen. Anzulegen sind **genau** die Formen der Zielsprache, mit diesen Endungen:
`_zero`, `_one`, `_two`, `_few`, `_many`, `_other`. Polnisch zum Beispiel: `_one`, `_few`, `_many`, `_other`;
Französisch: `_one`, `_many`, `_other`. Welche Formen eine Sprache hat, nennt `npm run check:locales`.

Achtung: `_one` heißt nicht überall „genau 1“ – in manchen Sprachen gilt die Form auch für 21, 31 … Die Zahl
deshalb nie ausschreiben, sondern `{{count, number}}` verwenden, wo der englische Text es auch tut.

## Marken `<…>`

Manche Sätze enthalten mittendrin eine Schaltfläche oder eine Hervorhebung:

```json
"none": "No tags. <add>Add some</add>"
```

Den Text zwischen den Marken übersetzen, die Marken selbst (`<add>`, `</add>`) unverändert lassen. Sie dürfen im
Satz wandern, aber nicht umbenannt, weggelassen oder ineinander verschoben werden. Der Satz bleibt **ein** Satz.

## Nicht übersetzen

- den Produktnamen **AIDocuments**
- Namen von Anbietern und Modellen: Anthropic, Claude, OpenAI, Moonshot AI (Kimi), LM Studio, Opus, Sonnet, Haiku
- Fachkürzel und feste Bezeichnungen: PDF, IMAP, API, LLM, Docker, `/v1`, Tool Use
- alles in `{{…}}` und die Namen der Marken in `<…>`

Adressen, Pfade und Ordnernamen (`/import`, `INBOX`, Beispiel-URLs) stehen als Platzhalter im Text und kommen aus
dem Code.

## Schreibweise

- Die Oberfläche spricht ihr Gegenüber im Deutschen mit „du“ an; in der Zielsprache die dort für Software übliche
  Anrede wählen und durchhalten.
- Anführungszeichen der Zielsprache verwenden („…“, “…”, «…»). Gerade Anführungszeichen `"` müssten in JSON als
  `\"` geschrieben werden.
- Auslassungspunkte als ein Zeichen `…`, Gedankenstrich als `–`.
- Kurze Texte (Schaltflächen, Spaltentitel, Marken) kurz halten – der Platz ist knapp.
- Begriffe einheitlich verwenden: Der Text `documents.empty.libraryHint` nennt z. B. die Schaltfläche `shell.upload`.

## Was nicht in diesen Dateien steht

- Datum, Uhrzeit, Zahlen, Zeitspannen („vor 3 Stunden“, „30 Min.“): Das formatiert der Browser passend zur Sprache.
- Texte, die der Server liefert (Fehlerdetails, Statusmeldungen der Postfächer) – sie werden im Backend übersetzt.
- Inhalte der Dokumente: Titel, Schlagworte, Zusammenfassungen.

## Was die Prüfung meldet

`npm run check:locales` vergleicht jede Datei mit `en.json`: fehlende und überzählige Schlüssel, falsche oder
fehlende Mehrzahlformen, abweichende Platzhalter und Marken, leere Texte, ungültiges JSON. Solange sie Fehler
meldet, ist die Übersetzung nicht fertig.
