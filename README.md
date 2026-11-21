# AIDocuments

Dokumentenverwaltung für PDFs: Die Anwendung holt PDF-Dateien aus IMAP-Postfächern, einem überwachten
Eingangsordner oder per Upload, lässt sie von einer KI erschließen (Titel, Zusammenfassung, Dokumentart,
Absender, Datum, Schlagworte, Kerndaten – bei Scans auch den Volltext) und macht sie durchsuchbar.

- **Backend:** Spring Boot 4 (Java 21), MongoDB, Apache Lucene (Volltextsuche), PDFBox, Anthropic Java SDK,
  eigene Anbindung für OpenAI-kompatible Server
- **Frontend:** React 19, TypeScript, Vite, Tailwind CSS 4, TanStack Query

## Funktionen

| Bereich | Was die Anwendung tut |
|---|---|
| Eingang | PDF-Anhänge aus IMAP-Postfächern (nur lesend – E-Mails bleiben unverändert und ungelesen), neue Dateien aus einem überwachten Ordner, Upload per Schaltfläche oder Drag & Drop (mehrere Dateien auf einmal). Inhaltsgleiche Dateien werden erkannt und nicht doppelt abgelegt. |
| KI | Zusammenfassung, Dokumentart, Absender, Dokumentdatum, Schlagworte und Kerndaten (Beträge, Fristen, Nummern …). Vorhandene Schlagworte und Absender werden wiederverwendet. Scans ohne Textebene werden transkribiert und damit im Volltext durchsuchbar. |
| Suche | Volltext über Inhalt, Titel, Schlagworte, Absender und Kerndaten – mit deutscher Stammformbildung, Treffern schon beim Tippen und Teiltextsuche mitten im Wort. Filter nach Dokumentart, Schlagworten, Absender und Zeitraum. |
| Ansicht | Kachel- und Listenansicht mit Vorschaubildern, Detailseite mit PDF-Betrachter, helles und dunkles Design. Metadaten sind von Hand bearbeitbar, Schlagworte lassen sich direkt am Dokument entfernen und ergänzen. |
| Benutzer | Ersteinrichtung mit Administratorkonto, weitere Konten mit den Rollen **Admin** und **Benutzer**. |
| Einstellungen | Postfächer, Abrufintervall und Eingangsordner, KI-/LLM-Server, Benutzer – alles in der Oberfläche. |
| Sprachen | Oberfläche und Meldungen des Servers in 36 europäischen Sprachen. Beim ersten Start gilt die Systemsprache, ohne passende Übersetzung Englisch; umschaltbar im Benutzermenü. Siehe [Sprachen](#sprachen). |

### Rollen

| | Benutzer | Admin |
|---|:-:|:-:|
| Dokumente ansehen, suchen, hochladen, Metadaten bearbeiten | ✓ | ✓ |
| Dokumente löschen, neu analysieren | | ✓ |
| Einstellungen (Postfächer, Abruf, KI), Benutzerverwaltung | | ✓ |

### Suche

| Eingabe | findet |
|---|---|
| `rechnung strom` | Dokumente, in denen beide Wörter vorkommen (auch „Rechnungen“, „Stromrechnung“) |
| `versicherung` | auch „Haftpflichtversicherung“ – Wortteile zählen mit |
| `"zahlbar bis"` | genau diese Wortfolge |
| `miete -kaution` | „Miete“, aber nicht „Kaution“ |
| `strom \| gas` | eines von beiden |

## Stand

Das Projekt ist in Entwicklung und wird bislang von einer Person genutzt. Es gibt noch keine veröffentlichten
Versionen und keine Lizenz – das Repository ist privat.

## Entwicklung

Voraussetzungen: Java 21, Node.js 22+, Docker (für die MongoDB).

```bash
# 1. MongoDB starten (localhost:27017)
docker compose -f docker-compose.dev.yml up -d

# 2. Backend starten (http://127.0.0.1:8080)
cd backend && ./mvnw spring-boot:run

# 3. Frontend starten (http://127.0.0.1:5173, leitet /api an das Backend weiter)
cd frontend && npm install && npm run dev
```

Beim ersten Aufruf von <http://127.0.0.1:5173> fragt die Anwendung nach Name und Passwort für das
Administratorkonto.

**LLM-Server der Debug-Version:** Liegt `backend/config/debug.yml` vor, wird sie beim lokalen Start
automatisch geladen (Vorlage: `backend/config/debug.example.yml`). Die Datei steht nicht im Git und landet
nicht im Docker-Image – der richtige Ort für Server-Adresse und API-Schlüssel der Entwicklungsumgebung.

Tests (brauchen ein laufendes Docker für Testcontainers):

```bash
cd backend && ./mvnw test
```

Der Integrationstest durchläuft Ersteinrichtung, Rollen, Upload, Suche, IMAP-Abruf (gegen einen
eingebetteten Test-Mailserver) und Eingangsordner gegen eine echte MongoDB.

## Produktion mit Docker

Ein Image enthält Oberfläche, Backend und – wahlweise – die MongoDB.

### Variante A: MongoDB im Container

```bash
docker compose up -d --build
```

Ohne `MONGODB_URI` startet im Container eine eingebaute MongoDB. Alles Bleibende liegt im Volume
`/aidocuments`:

```
/aidocuments
├── mongo/        Datenbank (nur bei eingebauter MongoDB)
├── files/        die PDF-Dateien
├── thumbnails/   Vorschaubilder
├── index/        Suchindex (wird bei Bedarf aus der Datenbank neu aufgebaut)
└── secret.key    Schlüssel für gespeicherte Postfach-Passwörter und API-Schlüssel
```

### Variante B: externe MongoDB

```bash
docker compose -f docker-compose.external-mongo.yml up -d --build
```

Mit gesetzter `MONGODB_URI` läuft im Container nur die Anwendung. Das Volume `/aidocuments` bleibt nötig
(PDFs, Suchindex, Schlüsseldatei).

```bash
docker run -d -p 8080:8080 \
  -e MONGODB_URI='mongodb://benutzer:passwort@db.example.com:27017/aidocuments?authSource=admin' \
  -v aidocuments-data:/aidocuments \
  aidocuments
```

### Eingangsordner

Den Ordner des Scanners als `/import` einbinden (`-v /pfad/zum/scan-ordner:/import`) – er ist im Image als
Eingangsordner vorbelegt. Abschalten oder ändern unter *Einstellungen → Abruf & Eingangsordner*.

### Sicherung

`/aidocuments` sichern – bei externer MongoDB zusätzlich die Datenbank. Die Datei `secret.key` gehört zur
Sicherung: Ohne sie lassen sich gespeicherte Postfach-Passwörter und API-Schlüssel nicht mehr entschlüsseln.

### Hinter einem Reverse-Proxy (HTTPS)

Die Anwendung selbst spricht HTTP. Für den Betrieb im Netz einen Reverse-Proxy mit TLS davorsetzen und
`SERVER_FORWARD_HEADERS_STRATEGY=framework` sowie `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` setzen.

### Hinweis zur MongoDB-Version

Die eingebaute MongoDB ist Version 7.0. MongoDB 8.0 und neuer starten auf Hosts mit Linux-Kernel 6.19 bis
7.0.13 nicht ([SERVER-121912](https://jira.mongodb.org/browse/SERVER-121912)) – das betrifft unter anderem
aktuelle Docker-Desktop-Versionen. Wer nicht betroffen ist, kann mit
`docker build --build-arg MONGO_VERSION=8.0 -t aidocuments .` eine neuere Version einbauen.

## Konfiguration

Fast alles lässt sich in der Oberfläche einstellen. Die Umgebungsvariablen sind Vorgaben für den Start;
in der Oberfläche gespeicherte Werte haben Vorrang.

| Variable | Bedeutung | Standard |
|---|---|---|
| `MONGODB_URI` | Verbindung zur MongoDB | `mongodb://localhost:27017/aidocuments` (im Image: eingebaute MongoDB) |
| `AIDOCS_DATA_DIR` | Ablage für PDFs, Suchindex, Schlüsseldatei | `./data` (im Image: `/aidocuments`) |
| `AIDOCS_IMPORT_DIR` | überwachter Eingangsordner | leer = aus (im Image: `/import`) |
| `AIDOCS_IMPORT_INTERVAL` | Abrufintervall, z. B. `5m` | `5m` |
| `AIDOCS_AI_PROVIDER` | LLM-Anbieter: `anthropic`, `openai`, `moonshot`, `lm-studio`, `openai-compatible` | `anthropic` |
| `AIDOCS_AI_BASE_URL` | Adresse des LLM-Servers | Standardadresse des Anbieters |
| `AIDOCS_AI_API_KEY` | API-Schlüssel des LLM-Servers (`ANTHROPIC_API_KEY` wird ebenfalls gelesen) | leer |
| `AIDOCS_AI_MODEL` | Modell-ID | `claude-opus-5-5` |
| `SERVER_ADDRESS` / `SERVER_PORT` | Bindeadresse und Port | `127.0.0.1` / `8080` (im Image: `0.0.0.0`) |

## KI-Anbindung

Der Anbieter wird unter *Einstellungen → KI* gewählt:

| Anbieter | Schnittstelle | Standardadresse | Inhalte verlassen den Rechner |
|---|---|---|:-:|
| Anthropic | Anthropic Messages API | `https://api.anthropic.com` | ja |
| OpenAI | OpenAI Chat Completions | `https://api.openai.com/v1` | ja |
| Moonshot | OpenAI Chat Completions | `https://api.moonshot.ai/v1` | ja |
| LM Studio | OpenAI Chat Completions | `http://localhost:1234/v1` | nein |
| OpenAI Kompatibel | OpenAI Chat Completions | frei wählbar | je nach Server |

Bei Anthropic, OpenAI und Moonshot weist die Oberfläche darauf hin, dass die Dokumentinhalte zur Analyse an
Server im Internet übertragen werden, und empfiehlt eine lokale KI. Ein gespeicherter API-Schlüssel gehört
immer zu genau einem Anbieter: Beim Wechsel wird er verworfen und nie an den neuen Anbieter geschickt.

Wie das Dokument beim Modell ankommt:

- **Claude API** (Anthropic mit der Standardadresse): Das PDF geht als Ganzes an das Modell – Layout,
  Tabellen und Scans inklusive –, das Ergebnis kommt als strukturierte Ausgabe zurück.
- **Alle anderen** (OpenAI-Schnittstelle sowie Anthropic-Schnittstelle im *Kompatibilitätsmodus* für eigene
  Server): Das Dokument geht als ausgelesener Text bzw. – bei Scans – als Seitenbilder an das Modell, das
  Ergebnis kommt als Werkzeugaufruf zurück. Das Modell muss dafür Werkzeugaufrufe (Tool Use / Function
  Calling) und für Scans Bildeingabe beherrschen. Grenzen: Von sehr langen Texten wertet die KI nur den Anfang
  aus, von Scans die ersten 8 Seiten; die Anwendung vermerkt das am Dokument. Die Volltextsuche umfasst bei
  Textdokumenten trotzdem den gesamten Text.

Jede Antwort wird geprüft (Titel, Zusammenfassung, Dokumentart, Schlagworte, Datum, bei Scans der Volltext).
Ist sie nicht verarbeitbar, fragt die Anwendung genau einmal nach und nennt dem Modell, was gefehlt hat.
Dokumente gehen strikt nacheinander an das Modell, nie mehrere gleichzeitig.

**Lange Antworten:** Modelle, die vor der Antwort „nachdenken“ (viele lokale Modelle tun das), verbrauchen dafür
ein Vielfaches der eigentlichen Antwort – und das Nachdenken zählt gegen dasselbe Token-Limit. Die Anwendung
gibt deshalb reichlich Platz (16.000 Token, mit Texterkennung 24.000). Wird eine Antwort trotzdem
abgeschnitten, folgt ein zweiter Anlauf mit doppeltem Limit. Reicht auch der nicht oder lässt der Server ihn
nicht zu, wird aus der abgeschnittenen Antwort übernommen, was vollständig ankam – vorausgesetzt, Titel,
Zusammenfassung, Dokumentart und Schlagworte sind komplett. Erst danach gilt die Analyse als fehlgeschlagen.
Das Protokoll nennt bei jedem Abbruch, wie viele Token erzeugt wurden und wie viel davon Nachdenken war.

Ohne KI-Zugang funktioniert die Anwendung weiter: PDFs werden gespeichert und sind über ihre Textebene
durchsuchbar, nur Zusammenfassung und Schlagworte fehlen. Nachträglich analysieren: *Einstellungen → KI →
„Jetzt analysieren“*.

## Sprachen

Die Anwendung ist in 36 Sprachen übersetzt: die 24 Amtssprachen der EU (Bulgarisch, Dänisch, Deutsch,
Englisch, Estnisch, Finnisch, Französisch, Griechisch, Irisch, Italienisch, Kroatisch, Lettisch, Litauisch,
Maltesisch, Niederländisch, Polnisch, Portugiesisch, Rumänisch, Schwedisch, Slowakisch, Slowenisch, Spanisch,
Tschechisch, Ungarisch) sowie Albanisch, Belarussisch, Bosnisch, Isländisch, Katalanisch, Luxemburgisch,
Mazedonisch, Norwegisch (Bokmål), Russisch, Serbisch, Türkisch und Ukrainisch.

- **Welche Sprache gilt:** Beim ersten Start die Systemsprache, wie der Browser sie meldet (`de-AT` wird mit
  Deutsch bedient, `nn`/`no` mit Norwegisch Bokmål). Gibt es dafür keine Übersetzung, gilt Englisch. Die
  Sprache lässt sich im Benutzermenü und auf der Anmeldeseite umschalten; die Wahl merkt sich der Browser.
- **Was übersetzt ist:** die Oberfläche, Fehlermeldungen und Statusmeldungen des Servers (etwa das Ergebnis
  eines Postfachabrufs – sie werden sprachneutral gespeichert und erst beim Anzeigen übersetzt) sowie Datums-,
  Zahlen- und Zeitangaben.
- **Was nicht:** Inhalte, die die KI erzeugt (Titel, Zusammenfassung, Schlagworte, Dokumentart) – deren Sprache
  bestimmt *Einstellungen → KI → Sprache der Ergebnisse*. Die Volltextsuche bildet Stammformen für deutsche
  Texte; Teiltextsuche funktioniert in jeder Sprache.
- **Herkunft:** Deutsch ist der ursprüngliche Wortlaut. Alle anderen Sprachen sind maschinell übersetzt und nicht
  von Muttersprachlern gegengelesen – Korrekturen sind einfache Textänderungen in den Dateien unten.

| | Oberfläche | Server |
|---|---|---|
| Dateien | `frontend/src/locales/<code>.json` | `backend/src/main/resources/messages_<code>.properties` |
| Vorlage und Rückfallsprache (Englisch) | `en.json` | `messages.properties` |
| Regeln für Übersetzungen | `frontend/src/locales/README.md` | Kopf von `messages.properties` |
| Prüfung | `cd frontend && npm run check:locales` | `MessageBundlesTest` (läuft mit `./mvnw test`) |

Eine weitere Sprache hinzufügen: beide Dateien anlegen – sie wird ohne Codeänderung erkannt und erscheint in der
Sprachauswahl. Norwegisch heißt in der Oberfläche `nb.json`, auf dem Server `messages_no.properties`.

## Projektstruktur

```
backend/     Spring-Boot-Anwendung
  src/main/java/de/aidocuments/
    i18n/       Mehrsprachigkeit: sprachneutrale Meldungen, Sprachwahl je Anfrage
    document/   Dokumente, Verarbeitungs-Pipeline, REST-API
    ai/         KI-Analyse (Anthropic- und OpenAI-Schnittstelle)
    search/     Lucene-Volltextindex
    mail/       IMAP-Postfächer und Abruf
    importer/   Eingangsordner und Abrufintervall
    user/       Benutzer, Rollen, Anmeldung
    settings/   KI- und Abruf-Einstellungen
    security/   Zugriffsschutz, Verschlüsselung gespeicherter Geheimnisse
frontend/    React-Oberfläche
docker/      Startskript des Containers
```
