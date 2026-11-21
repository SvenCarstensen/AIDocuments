# AIDocuments

Document management for PDFs: the application collects PDF files from IMAP mailboxes, a watched import folder
or by upload, has an AI index them (title, summary, document type, correspondent, date, tags, key facts – and
for scans the full text as well) and makes them searchable.

## Highlights

- **Modern, simple interface** – one search box, filters that stay out of the way, grid and list view with
  thumbnails, light and dark theme, 36 languages. Everything is configured in the browser; there are no
  configuration files to edit.
- **Local installation** – AIDocuments runs on your own computer or server as a single Docker container. PDFs,
  database, search index and keys live in one directory that you control and back up.
- **Data security** – access only after sign-in, with separate roles for administrators and users. Passwords are
  stored as hashes, mailbox passwords and API keys encrypted (AES-256-GCM). Mailboxes are read without changing
  them. An AI in the cloud is used only if you choose one yourself – the interface says so clearly – and a local
  AI server keeps the contents of your documents inside your network.
- **No telemetry** – no analytics, no tracking, no usage statistics, no fonts or scripts loaded from third
  parties. The application connects only to the mailboxes and to the AI server you have configured.
- **Text recognition included** – the Docker image ships Tesseract, which reads scanned PDFs, so they are
  full-text searchable from the first start, without any AI service and without your documents leaving the
  machine.

## Technology

- **Backend:** Spring Boot 4 (Java 21), MongoDB, Apache Lucene (full-text search), PDFBox, Anthropic Java SDK,
  a custom client for OpenAI-compatible servers
- **Frontend:** React 19, TypeScript, Vite, Tailwind CSS 4, TanStack Query

## Features

| Area | What the application does |
|---|---|
| Intake | PDF attachments from IMAP mailboxes (read-only – e-mails stay unchanged and unread), new files from a watched folder, upload by button or drag & drop (several files at once). Files with identical content are recognised and not stored twice; the same letter scanned twice is marked as a possible duplicate (see [Duplicates](#duplicates)). |
| AI | Summary, document type, correspondent, document date, tags and key facts (amounts, deadlines, numbers …). Existing tags and correspondents are reused. Scans without a text layer are transcribed and thus become full-text searchable – by the built-in text recognition as long as no AI is set up. |
| Search | Full text across content, title, tags, correspondent and key facts – with German stemming, results while typing and partial matches in the middle of a word. Filters for document type, tags, correspondent and period (collapsible). With an AI set up, the search box also understands questions in natural language – see [AI search](#ai-search). |
| View | Grid and list view with thumbnails, detail page with PDF viewer, light and dark theme. Metadata can be edited by hand; tags can be removed and added directly on the document. |
| Edit | Highlight, add text, draw, insert images or sign directly in the PDF viewer and save the changes. The original is always kept and can be downloaded or restored at any time. |
| Ask | With an AI set up, ask questions about an open document (“When is the payment due?”) – answered from its content. |
| Users | Initial setup with an administrator account, further accounts with the roles **Admin** and **User**. |
| Settings | Mailboxes, check interval and import folder, AI/LLM server, users – all in the user interface. |
| Languages | User interface and server messages in 36 European languages. The first start uses the system language, English if there is no matching translation; switchable in the user menu. See [Languages](#languages). |

### Roles

| | User | Admin |
|---|:-:|:-:|
| View, search and upload documents, edit metadata | ✓ | ✓ |
| Annotate PDFs and restore originals, ask questions, mark “not a duplicate” | ✓ | ✓ |
| Delete documents, analyze them again | | ✓ |
| Settings (mailboxes, schedule, AI), user management | | ✓ |

### Search

| Input | finds |
|---|---|
| `rechnung strom` | documents containing both words (also “Rechnungen”, “Stromrechnung”) |
| `versicherung` | also “Haftpflichtversicherung” – parts of words count |
| `"zahlbar bis"` | exactly this sequence of words |
| `miete -kaution` | “Miete”, but not “Kaution” |
| `strom \| gas` | either of the two |

### AI search

If an AI is set up, the search box works in two modes; the icon on its left switches between them, and the browser
remembers the choice:

- **AI search** (sparkle icon, the default): type a question such as *“invoices from Kia from 2024”* and press
  Enter. The AI translates it into the regular filters – keywords, document type, correspondent, tags, period – and
  the list shows the result. The filter chips show how the question was understood and can be adjusted by hand.
  Document types, correspondents and tags are only used if they exist in your library; anything else becomes a
  search term, so a misunderstanding never hides documents.
- **Keyword search** (magnifier icon): the full-text search above, with results while typing.

Without an AI the search box is always a keyword search.

## Development

Requirements: Java 21, Node.js 22+, Docker (for MongoDB).

```bash
# 1. Start MongoDB (localhost:27017)
docker compose -f docker-compose.dev.yml up -d

# 2. Start the backend (http://127.0.0.1:8080)
cd backend && ./mvnw spring-boot:run

# 3. Start the frontend (http://127.0.0.1:5173, forwards /api to the backend)
cd frontend && npm install && npm run dev
```

On the first visit to <http://127.0.0.1:5173> the application asks for the name and password of the
administrator account.

**LLM server of the debug build:** If `backend/config/debug.yml` exists, it is loaded automatically on a local
start (template: `backend/config/debug.example.yml`). The file is not in Git and does not end up in the Docker
image – the right place for the server address and API key of the development environment.

Tests (need a running Docker for Testcontainers):

```bash
cd backend && ./mvnw test
```

The integration test runs through initial setup, roles, upload, search, IMAP retrieval (against an embedded
test mail server) and the import folder against a real MongoDB.

## Production with Docker

One image contains the user interface, the backend, the built-in text recognition (Tesseract) and – optionally –
MongoDB.

### Option A: MongoDB inside the container

```bash
docker compose up -d --build
```

Without `MONGODB_URI` a built-in MongoDB starts inside the container. Everything persistent lives in the
volume `/aidocuments`:

```
/aidocuments
├── mongo/        database (only with the built-in MongoDB)
├── files/        the PDF files
├── thumbnails/   thumbnails
├── index/        search index (rebuilt from the database when needed)
└── secret.key    key for stored mailbox passwords and API keys
```

### Option B: external MongoDB

```bash
docker compose -f docker-compose.external-mongo.yml up -d --build
```

With `MONGODB_URI` set, only the application runs in the container. The volume `/aidocuments` is still needed
(PDFs, search index, key file).

```bash
docker run -d -p 8080:8080 \
  -e MONGODB_URI='mongodb://user:password@db.example.com:27017/aidocuments?authSource=admin' \
  -v aidocuments-data:/aidocuments \
  aidocuments
```

### Import folder

Mount the scanner's folder as `/import` (`-v /path/to/scan-folder:/import`) – the image is preconfigured to
use it as the import folder. Switch it off or change it under *Settings → Schedule & import folder*.

### Backup

Back up `/aidocuments` – with an external MongoDB the database as well. The file `secret.key` belongs in the
backup: without it, stored mailbox passwords and API keys can no longer be decrypted.

### Behind a reverse proxy (HTTPS)

The application itself speaks HTTP. For use on a network, put a reverse proxy with TLS in front of it and set
`SERVER_FORWARD_HEADERS_STRATEGY=framework` and `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`.

### Note on the MongoDB version

The built-in MongoDB is version 7.0. MongoDB 8.0 and newer do not start on hosts with Linux kernel 6.19 to
7.0.13 ([SERVER-121912](https://jira.mongodb.org/browse/SERVER-121912)) – this affects current Docker Desktop
versions, among others. If you are not affected, you can build in a newer version with
`docker build --build-arg MONGO_VERSION=8.0 -t aidocuments .`.

## Configuration

Almost everything can be set in the user interface. The environment variables are defaults for the start;
values saved in the user interface take precedence.

| Variable | Meaning | Default |
|---|---|---|
| `MONGODB_URI` | Connection to MongoDB | `mongodb://localhost:27017/aidocuments` (in the image: built-in MongoDB) |
| `AIDOCS_DATA_DIR` | Storage for PDFs, search index, key file | `./data` (in the image: `/aidocuments`) |
| `AIDOCS_IMPORT_DIR` | Watched import folder | empty = off (in the image: `/import`) |
| `AIDOCS_IMPORT_INTERVAL` | Check interval, e.g. `5m` | `5m` |
| `AIDOCS_AI_PROVIDER` | LLM provider: `anthropic`, `openai`, `moonshot`, `lm-studio`, `openai-compatible` | `anthropic` |
| `AIDOCS_AI_BASE_URL` | Address of the LLM server | the provider's default address |
| `AIDOCS_AI_API_KEY` | API key of the LLM server (`ANTHROPIC_API_KEY` is read as well) | empty |
| `AIDOCS_AI_MODEL` | Model ID | `claude-opus-5-5` |
| `AIDOCS_OCR` / `AIDOCS_OCR_LANGUAGES` | Built-in text recognition – see [below](#built-in-text-recognition) | `on` / `deu+eng` |
| `SERVER_ADDRESS` / `SERVER_PORT` | Bind address and port | `127.0.0.1` / `8080` (in the image: `0.0.0.0`) |

## AI integration

The provider is chosen under *Settings → AI*:

| Provider | Interface | Default address | Content leaves the machine |
|---|---|---|:-:|
| Anthropic | Anthropic Messages API | `https://api.anthropic.com` | yes |
| OpenAI | OpenAI Chat Completions | `https://api.openai.com/v1` | yes |
| Moonshot | OpenAI Chat Completions | `https://api.moonshot.ai/v1` | yes |
| LM Studio | OpenAI Chat Completions | `http://localhost:1234/v1` | no |
| OpenAI-compatible | OpenAI Chat Completions | freely selectable | depends on the server |

For Anthropic, OpenAI and Moonshot the user interface points out that document contents are transmitted to
servers on the internet for analysis, and recommends a local AI. A stored API key always belongs to exactly
one provider: when the provider changes, the key is discarded and never sent to the new provider.

How the document reaches the model:

- **Claude API** (Anthropic with the default address): the PDF goes to the model as a whole – layout, tables
  and scans included – and the result comes back as structured output.
- **All others** (the OpenAI interface, and the Anthropic interface in *compatibility mode* for self-hosted
  servers): the document goes to the model as extracted text or – for scans – as page images, and the result
  comes back as a tool call. The model therefore has to support tool calls (tool use / function calling) and,
  for scans, image input. Limits: of very long texts the AI evaluates only the beginning, of scans the first
  8 pages; the application notes this on the document. For text documents the full-text search still covers
  the entire text.

Every answer is checked (title, summary, document type, tags, date, and for scans the full text). If it cannot
be processed, the application asks exactly once more and tells the model what was missing. Documents go to the
model strictly one after another, never several at the same time.

**Long answers:** Models that “think” before answering (many local models do) use many times the size of the
actual answer for that – and the thinking counts against the same token limit. The application therefore
leaves plenty of room (16,000 tokens, 24,000 with text recognition). If an answer is cut off anyway, a second
attempt follows with twice the limit. If that is not enough either, or the server does not allow it, the
application keeps whatever arrived complete from the cut-off answer – provided title, summary, document type
and tags are complete. Only then does the analysis count as failed. For every cut-off the log states how many
tokens were generated and how much of that was thinking.

Without AI access the application keeps working: PDFs are stored and searchable through their text layer, only
summary and tags are missing. To analyze them later: *Settings → AI → “Analyze now”*.

### Asking questions about a document

The tab *Ask* on a document page answers questions about that document. The AI receives the document's details and
its full text (the beginning of very long documents) and answers only from them; it says so if the answer is not in
the document. The conversation stays in the browser and is not stored.

### Built-in text recognition

The Docker image contains [Tesseract](https://github.com/tesseract-ocr/tesseract) (Apache 2.0). As long as no AI
is set up, it reads every PDF that consists of images only (scans without a text layer), so these documents are
full-text searchable as well. It only provides the text – title, summary and tags still need an AI. As soon as
an AI is set up, that AI takes over text recognition.

Pages are rendered at 300 dpi and read one after another – a few seconds per page. At most the first 50 pages of
a document are read; the application notes on the document if there are more.

| Variable | Meaning | Default |
|---|---|---|
| `AIDOCS_OCR` | `off` switches the built-in text recognition off | `on` |
| `AIDOCS_OCR_LANGUAGES` | Languages of your documents as Tesseract codes joined with `+` | `deu+eng` |

The image contains the languages German, English, French, Italian, Spanish and Dutch (`deu eng fra ita spa nld`).
For others, build your own image with e.g. `docker build --build-arg OCR_LANGUAGES="deu eng pol" -t aidocuments .`
and set `AIDOCS_OCR_LANGUAGES` accordingly. Outside the Docker image the built-in text recognition is used as
soon as Tesseract is installed (e.g. `brew install tesseract tesseract-lang`).

**Quick result first:** With an AI set up, *Settings → AI → “Quick text recognition first, then refine with the
AI”* lets Tesseract read new scans first, so they are searchable within seconds; the AI then adds title, summary and
tags based on that text. Independently of this setting, reading PDFs, thumbnails and text recognition run in their
own queue, so new documents never wait behind long AI analyses.

## Duplicates

Files with identical content are recognised on import and not stored twice. Beyond that, the application recognises
the same letter arriving twice as different files – scanned twice, or once by e-mail and once from the scanner. It
compares the text (tolerant of recognition errors) **and** all dates and amounts: series of similar letters such as
monthly invoices or account statements differ in exactly those and are not reported. The later of two documents is
marked as a possible duplicate; the overview offers the filter *Possible duplicates*, and the document page links to
the other document. *Not a duplicate* removes the mark for good. Existing documents are checked once in the
background on start-up.

## Editing PDFs and keeping the original

The PDF viewer on the document page ([PDF.js](https://mozilla.github.io/pdf.js/), Apache 2.0, served by the
application itself) can highlight, add text, draw, insert images and sign. As soon as the document has changed, a
bar offers *Save changes* or *Discard*. On the first save the original file is kept next to it
(`files/…/<id>.original.pdf`); further saves never touch it. The document page offers to download or restore the
original. Title, tags and other details stay as they are; the full text follows the saved version unless it came
from text recognition. The viewer's toolbar adapts to the available width so that it is always completely visible.

PDF.js is fetched in a fixed version with checksum by `frontend/scripts/fetch-pdfjs.mjs` (runs automatically before
`npm run dev` and `npm run build`) into `frontend/public/pdfjs/`, which is not in Git.

## Languages

The application is translated into 36 languages: the 24 official languages of the EU (Bulgarian, Croatian,
Czech, Danish, Dutch, English, Estonian, Finnish, French, German, Greek, Hungarian, Irish, Italian, Latvian,
Lithuanian, Maltese, Polish, Portuguese, Romanian, Slovak, Slovenian, Spanish, Swedish) plus Albanian,
Belarusian, Bosnian, Catalan, Icelandic, Luxembourgish, Macedonian, Norwegian (Bokmål), Russian, Serbian,
Turkish and Ukrainian.

- **Which language applies:** On the first start the system language as reported by the browser (`de-AT` is
  served with German, `nn`/`no` with Norwegian Bokmål). If there is no translation for it, English applies.
  The language can be switched in the user menu and on the sign-in page; the browser remembers the choice.
- **What is translated:** the user interface, error and status messages of the server (such as the result of
  a mailbox check – they are stored language-neutrally and translated only when displayed) as well as dates,
  numbers and times.
- **What is not:** content generated by the AI (title, summary, tags, document type) – its language is set by
  *Settings → AI → Language of the results*. The full-text search applies stemming for German texts; partial
  matching works in every language.
- **Origin:** German is the original wording. All other languages are machine translations and have not been
  proofread by native speakers – corrections are simple text changes in the files below.

| | User interface | Server |
|---|---|---|
| Files | `frontend/src/locales/<code>.json` | `backend/src/main/resources/messages_<code>.properties` |
| Template and fallback language (English) | `en.json` | `messages.properties` |
| Rules for translations | `frontend/src/locales/README.md` | header of `messages.properties` |
| Check | `cd frontend && npm run check:locales` | `MessageBundlesTest` (runs with `./mvnw test`) |

Adding another language: create both files – it is detected without any code change and appears in the
language selection. Norwegian is called `nb.json` in the user interface and `messages_no.properties` on the
server.

## Project structure

```
backend/     Spring Boot application
  src/main/java/de/aidocuments/
    i18n/       multilingual support: language-neutral messages, language per request
    document/   documents, processing pipeline, REST API
    ai/         AI analysis (Anthropic and OpenAI interface)
    search/     Lucene full-text index
    mail/       IMAP mailboxes and retrieval
    importer/   import folder and check interval
    user/       users, roles, sign-in
    settings/   AI and schedule settings
    security/   access control, encryption of stored secrets
frontend/    React user interface
docker/      start script of the container
```
