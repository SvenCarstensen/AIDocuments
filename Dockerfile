# syntax=docker/dockerfile:1
#
# AIDocuments als ein Image: React-Oberfläche + Spring-Boot-Backend + eingebaute Texterkennung für Scans
# (Tesseract). Die Datenbank ist eingebaut (SQLite, eine Datei im Datenverzeichnis); mit AIDOCS_DB_URL=jdbc:mariadb://…
# wird stattdessen eine externe MariaDB verwendet (siehe README).
#
#   docker build -t aidocuments .
#   docker run -d -p 8080:8080 -v aidocuments-data:/aidocuments aidocuments

# Sprachen der eingebauten Texterkennung (Tesseract-Kürzel, Leerzeichen-getrennt). Welche davon beim Lesen genutzt
# werden, bestimmt AIDOCS_OCR_LANGUAGES; weitere lassen sich hier für einen eigenen Build ergänzen.
ARG OCR_LANGUAGES="deu eng fra ita spa nld"

# ---------- 1. Oberfläche bauen ----------
FROM node:24-alpine AS frontend
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---------- 2. Backend bauen (die Oberfläche wandert als statische Dateien ins JAR) ----------
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /build
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline
COPY backend/src src
COPY backend/tools tools
COPY docker/THIRD-PARTY-RUNTIME.txt /tmp/notices/runtime.txt
COPY --from=frontend /build/dist src/main/resources/static
# Lizenzhinweise in einer Datei: Laufzeitumgebung, Java-Bibliotheken, Weboberfläche. Die Anwendung liefert sie unter
# /THIRD-PARTY-NOTICES.txt aus, das Image enthält sie zusätzlich als /app/THIRD-PARTY-NOTICES.txt.
RUN --mount=type=cache,target=/root/.m2 mv src/main/resources/static/THIRD-PARTY-NOTICES.txt /tmp/notices/frontend.txt \
    && ./mvnw -q -B dependency:list -DincludeScope=runtime -DoutputFile=/tmp/notices/deps.txt \
    && java tools/ThirdPartyNotices.java /tmp/notices/deps.txt /root/.m2/repository /tmp/notices/runtime.txt \
        /tmp/notices/frontend.txt src/main/resources/static/THIRD-PARTY-NOTICES.txt \
    && cp src/main/resources/static/THIRD-PARTY-NOTICES.txt /THIRD-PARTY-NOTICES.txt
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package \
    && cp target/aidocuments-backend-*.jar /aidocuments.jar

# ---------- 3. Laufzeit: Java-Laufzeit (Ubuntu) mit Tesseract ----------
FROM eclipse-temurin:21-jre
ARG OCR_LANGUAGES
# Schriften und FreeType braucht die PDF-Vorschau (Rendern der ersten Seite), Tesseract die Texterkennung,
# gosu das Abgeben der root-Rechte beim Start. Die Anwendung läuft als eigener Benutzer "aidocuments".
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core libfreetype6 gosu \
        tesseract-ocr $(for lang in ${OCR_LANGUAGES}; do printf 'tesseract-ocr-%s ' "$lang"; done) \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 999 aidocuments \
    && useradd --system --uid 999 --gid aidocuments --home-dir /nonexistent --no-create-home aidocuments \
    && mkdir -p /aidocuments /import /app \
    && chown aidocuments:aidocuments /aidocuments \
    && dpkg-query -W -f='${Package} ${Version}\n' > /app/THIRD-PARTY-OS-PACKAGES.txt

ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    AIDOCS_DATA_DIR=/aidocuments \
    AIDOCS_IMPORT_DIR=/import \
    AIDOCS_OCR_LANGUAGES=deu+eng \
    JAVA_OPTS="-XX:MaxRAMPercentage=60"

COPY --from=backend /aidocuments.jar /app/aidocuments.jar
COPY --from=backend /THIRD-PARTY-NOTICES.txt /app/THIRD-PARTY-NOTICES.txt
COPY docker/entrypoint.sh /usr/local/bin/aidocuments-entrypoint

# Alles Bleibende liegt hier: PDFs, Vorschaubilder, Suchindex, Schlüsseldatei und – ohne externe
# MariaDB – die Datenbank (aidocuments.db). Dieses eine Verzeichnis als Volume einbinden und sichern.
VOLUME /aidocuments
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["aidocuments-entrypoint"]
