#!/bin/bash
# Startet AIDocuments im Container.
#
# Die Datenbank ist eingebaut: eine SQLite-Datei im Datenverzeichnis ($AIDOCS_DATA_DIR/aidocuments.db), im selben
# Volume wie PDFs, Suchindex und Schlüsseldatei. Mit AIDOCS_DB_URL=jdbc:mariadb://… nutzt die Anwendung stattdessen
# eine externe MariaDB. Die eingebaute Texterkennung (Tesseract) startet die Anwendung selbst, je Seite.
set -euo pipefail

DATA_DIR="${AIDOCS_DATA_DIR:-/aidocuments}"
RUN_AS=aidocuments

# Als root gestartet: Datenverzeichnis dem Dienstbenutzer übergeben, dann die Rechte abgeben.
if [ "$(id -u)" = "0" ]; then
  mkdir -p "$DATA_DIR"
  if [ "$(stat -c %U "$DATA_DIR")" != "$RUN_AS" ]; then
    chown -R "$RUN_AS:$RUN_AS" "$DATA_DIR"
  fi
  exec gosu "$RUN_AS" "$0" "$@"
fi

export HOME=/tmp
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar /app/aidocuments.jar "$@"
