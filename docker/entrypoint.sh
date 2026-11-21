#!/bin/bash
# Startet AIDocuments im Container.
#
#   MONGODB_URI gesetzt   → externe MongoDB wird verwendet, im Container läuft nur die Anwendung.
#   MONGODB_URI nicht gesetzt → eine eingebaute MongoDB startet mit; ihre Daten liegen im
#                               Datenverzeichnis ($AIDOCS_DATA_DIR/mongo) und damit im selben Volume
#                               wie PDFs, Suchindex und Schlüsseldatei.
#
# Die eingebaute Texterkennung (Tesseract) startet die Anwendung selbst, je Seite; dafür läuft hier nichts.
set -euo pipefail

DATA_DIR="${AIDOCS_DATA_DIR:-/aidocuments}"
RUN_AS=mongodb

# Als root gestartet: Datenverzeichnis dem Dienstbenutzer übergeben, dann die Rechte abgeben.
if [ "$(id -u)" = "0" ]; then
  mkdir -p "$DATA_DIR"
  if [ "$(stat -c %U "$DATA_DIR")" != "$RUN_AS" ]; then
    chown -R "$RUN_AS:$RUN_AS" "$DATA_DIR"
  fi
  exec gosu "$RUN_AS" "$0" "$@"
fi

export HOME=/tmp
MONGO_PID=""

if [ -z "${MONGODB_URI:-}" ]; then
  echo "[aidocuments] Keine MONGODB_URI gesetzt – starte die eingebaute MongoDB (Daten: $DATA_DIR/mongo)"
  mkdir -p "$DATA_DIR/mongo"
  mongod --dbpath "$DATA_DIR/mongo" --bind_ip 127.0.0.1 --port 27017 \
         --logpath "$DATA_DIR/mongo/mongod.log" --logappend --quiet &
  MONGO_PID=$!
  ready=""
  for _ in $(seq 1 90); do
    if mongosh --quiet --host 127.0.0.1 --port 27017 --eval 'db.runCommand({ ping: 1 }).ok' >/dev/null 2>&1; then
      ready=1
      break
    fi
    if ! kill -0 "$MONGO_PID" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if [ -z "$ready" ]; then
    echo "[aidocuments] Die eingebaute MongoDB ist nicht gestartet. Letzte Logzeilen:" >&2
    tail -n 30 "$DATA_DIR/mongo/mongod.log" >&2 || true
    exit 1
  fi
  export MONGODB_URI="mongodb://127.0.0.1:27017/aidocuments"
else
  echo "[aidocuments] Verwende die externe MongoDB aus MONGODB_URI"
fi

# shellcheck disable=SC2086
java ${JAVA_OPTS:-} -jar /app/aidocuments.jar "$@" &
APP_PID=$!

shutdown() {
  kill -TERM "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  if [ -n "$MONGO_PID" ]; then
    # erst nach der Anwendung, damit sie ihre letzten Schreibvorgänge abschließen kann
    kill -TERM "$MONGO_PID" 2>/dev/null || true
    wait "$MONGO_PID" 2>/dev/null || true
  fi
}
trap 'shutdown; exit 0' TERM INT

# Endet einer der Prozesse von selbst, den anderen sauber beenden und den Container stoppen.
set +e
if [ -n "$MONGO_PID" ]; then
  wait -n "$APP_PID" "$MONGO_PID"
else
  wait "$APP_PID"
fi
STATUS=$?
shutdown
exit "$STATUS"
