# syntax=docker/dockerfile:1
#
# AIDocuments als ein Image: React-Oberfläche + Spring-Boot-Backend + (optional) eingebaute MongoDB
# + eingebaute Texterkennung für Scans (Tesseract).
#
#   docker build -t aidocuments .
#   docker run -d -p 8080:8080 -v aidocuments-data:/aidocuments aidocuments
#
# Mit -e MONGODB_URI=… wird stattdessen eine externe MongoDB verwendet (siehe README).

# Version der eingebauten MongoDB (Basis des Laufzeit-Images).
# MongoDB 7.0 läuft auf jedem Host. MongoDB 8.0 und neuer starten auf Linux-Kernel 6.19 bis 7.0.13 nicht
# (SERVER-121912, betrifft u. a. aktuelle Docker-Desktop-Versionen); wer dort nicht betroffen ist, kann mit
#   docker build --build-arg MONGO_VERSION=8.0 …
# eine neuere eingebaute MongoDB wählen.
ARG MONGO_VERSION=7.0

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
COPY --from=frontend /build/dist src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package \
    && cp target/aidocuments-backend-*.jar /aidocuments.jar

# ---------- 3. Laufzeit: MongoDB-Image als Basis, Java-Laufzeit dazu ----------
FROM mongo:${MONGO_VERSION}
COPY --from=eclipse-temurin:21-jre /opt/java/openjdk /opt/java/openjdk
ARG OCR_LANGUAGES
# Schriften und FreeType braucht die PDF-Vorschau (Rendern der ersten Seite), Tesseract die Texterkennung
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core libfreetype6 \
        tesseract-ocr $(for lang in ${OCR_LANGUAGES}; do printf 'tesseract-ocr-%s ' "$lang"; done) \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /aidocuments /import /app \
    && chown mongodb:mongodb /aidocuments

ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    AIDOCS_DATA_DIR=/aidocuments \
    AIDOCS_IMPORT_DIR=/import \
    AIDOCS_OCR_LANGUAGES=deu+eng \
    JAVA_OPTS="-XX:MaxRAMPercentage=60"

COPY --from=backend /aidocuments.jar /app/aidocuments.jar
COPY docker/entrypoint.sh /usr/local/bin/aidocuments-entrypoint

# Alles Bleibende liegt hier: PDFs, Vorschaubilder, Suchindex, Schlüsseldatei und – ohne externe
# MongoDB – die Datenbank. Dieses eine Verzeichnis als Volume einbinden und sichern.
VOLUME /aidocuments
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["aidocuments-entrypoint"]
