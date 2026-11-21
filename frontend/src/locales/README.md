# Translations

Every language of the user interface is one file `<code>.json` in this folder (`de.json`, `fr.json`, `pt-BR.json` …).
The file name is the language tag according to BCP 47. Once the file is here, the language appears in the language
selection by itself – nothing has to be changed in the code.

- **`en.json` is the template** and also the fallback language: if a translation lacks a text, it is shown in
  English.
- `de.json` is the original German wording and helps where the English text is ambiguous.

## Adding a language

1. Copy `en.json` and name it after the language, e.g. `fr.json`.
2. Translate only the **texts** (to the right of the colon). Keys (on the left) and structure stay unchanged.
3. Check in the `frontend` folder: `npm run check:locales` (or a single language: `npm run check:locales -- fr`).

## Keys

The keys are grouped by area of the user interface and named in English:

| Area | Content |
| --- | --- |
| `common` | Words used in several places: Save, Cancel, Username … |
| `errors` | Error messages of the user interface |
| `auth` | Sign-in and initial setup |
| `shell` | Header bar, search, user menu, drop zone |
| `upload` | Messages during upload |
| `documents` | Document list, filters, sorting |
| `document` | Single document: view, edit, delete |
| `settings.mailboxes` / `.import` / `.ai` / `.users` | The four settings pages |

Key parts in CAPITAL LETTERS (`ADMIN`, `MAIL`, `LM_STUDIO`) correspond to values supplied by the server.

## Placeholders `{{…}}`

`{{name}}` is replaced at runtime. Keep placeholders **unchanged** – also with an addition such as
`{{count, number}}` (the number is then written the way the language writes numbers). Their position in the
sentence is free:

```json
"deleteUser": "{{name}} löschen"      →      "deleteUser": "Delete {{name}}"
```

## Plurals

Texts with a count exist in several forms, distinguished by the ending of the key. English and German have two:

```json
"documentCount_one": "{{count, number}} document",
"documentCount_other": "{{count, number}} documents"
```

Other languages need other forms. Create **exactly** the forms of the target language, with these endings:
`_zero`, `_one`, `_two`, `_few`, `_many`, `_other`. Polish, for example: `_one`, `_few`, `_many`, `_other`;
French: `_one`, `_many`, `_other`. `npm run check:locales` tells you which forms a language has.

Careful: `_one` does not mean “exactly 1” everywhere – in some languages the form also applies to 21, 31 … So
never spell out the number; use `{{count, number}}` wherever the English text does.

## Tags `<…>`

Some sentences contain a button or an emphasis in the middle:

```json
"none": "No tags. <add>Add some</add>"
```

Translate the text between the tags and leave the tags themselves (`<add>`, `</add>`) unchanged. They may move
within the sentence, but must not be renamed, dropped or nested differently. The sentence stays **one** sentence.

## Do not translate

- the product name **AIDocuments**
- names of providers and models: Anthropic, Claude, OpenAI, Moonshot AI (Kimi), LM Studio, Opus, Sonnet, Haiku
- technical abbreviations and fixed terms: PDF, IMAP, API, LLM, Docker, `/v1`, Tool Use
- everything inside `{{…}}` and the names of the tags in `<…>`

Addresses, paths and folder names (`/import`, `INBOX`, example URLs) appear as placeholders in the text and come
from the code.

## Style

- In German the user interface addresses the user informally (“du”); in the target language choose the form of
  address that is customary for software there and stick to it.
- Use the quotation marks of the target language („…“, “…”, «…»). Straight quotation marks `"` would have to be
  written as `\"` in JSON.
- Ellipsis as a single character `…`, dash as `–`.
- Keep short texts (buttons, column titles, labels) short – space is tight.
- Use terms consistently: the text `documents.empty.libraryHint`, for example, mentions the button `shell.upload`.

## What is not in these files

- Date, time, numbers, durations (“3 hours ago”, “30 min”): the browser formats these to suit the language.
- Texts supplied by the server (error details, status messages of the mailboxes) – they are translated in the
  backend.
- Content of the documents: titles, tags, summaries.

## What the check reports

`npm run check:locales` compares every file with `en.json`: missing and surplus keys, wrong or missing plural
forms, differing placeholders and tags, empty texts, invalid JSON. As long as it reports errors, the translation
is not finished.
