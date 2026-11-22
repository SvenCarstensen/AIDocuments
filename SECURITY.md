# Security policy

AIDocuments stores personal documents and is meant to be reachable from the internet behind a reverse proxy, so
security reports are very welcome.

## Supported versions

There are no separate release branches yet. Security fixes go into the `main` branch and the next Docker image.
Please check whether a problem still exists on the current `main` before reporting it.

## Reporting a vulnerability

**Please do not open a public issue, pull request or discussion for a security problem.**

Report it privately instead: on the repository page, open the **Security** tab and choose
**Report a vulnerability**. Only the maintainer can see the report, and the fix can be prepared in a private
security advisory.

Helpful details:

- the version or commit, and how AIDocuments runs (Docker or JAR, built-in SQLite or MariaDB, behind which reverse
  proxy, which AI provider if it matters)
- the steps to reproduce, ideally with a minimal request or script
- what an attacker gains – for example access without signing in, another user's documents, administrator rights
  or the content of a shared document

Never attach real documents or personal data; test with made-up files and a throwaway instance.

You will get an answer as soon as possible. Please give us reasonable time to publish a fix before you disclose
details; reporters are credited in the advisory if they wish.

## In scope

- the backend (`backend/`), the web interface (`frontend/`) and the Docker image (`Dockerfile`, `docker/`)
- sign-in, two-factor authentication, roles, sharing links, the handling of stored secrets (mailbox passwords, API
  keys, `secret.key`), mailbox and folder import, PDF processing and the PDF viewer as delivered by AIDocuments

## Out of scope

- installations that ignore the setup in the README, for example the application exposed without HTTPS, or the
  container port published on a public interface instead of behind the reverse proxy
- the behaviour and data handling of an external AI service you have configured
- denial of service by sheer volume (many large uploads by a signed-in user, flooding a mail account)
- vulnerabilities in third-party components without a way to exploit them through AIDocuments – please report those
  to the component's maintainers (they are listed in `THIRD-PARTY-NOTICES.txt`)

## For operators

How AIDocuments protects itself, and what you have to take care of when running it, is described in the README:
[Behind a reverse proxy (HTTPS)](README.md#behind-a-reverse-proxy-https) (including the built-in protection),
[Backup](README.md#backup) and [Sharing documents](README.md#sharing-documents).
