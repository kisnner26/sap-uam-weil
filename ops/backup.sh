#!/usr/bin/env bash
# Respaldo logico de PostgreSQL: pg_dump formato custom + checksum + rotacion.
# Variables: PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE, BACKUP_DIR (def. ./backups), KEEP_DAYS (def. 14),
#            BACKUP_PASSPHRASE (opcional: cifra con openssl AES-256).
set -euo pipefail
BACKUP_DIR="${BACKUP_DIR:-./backups}"; KEEP_DAYS="${KEEP_DAYS:-14}"
PGBIN="${PGBIN:-}"; [ -n "$PGBIN" ] && PGBIN="$PGBIN/"
mkdir -p "$BACKUP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="$BACKUP_DIR/sapuam-$stamp.dump"
"${PGBIN}pg_dump" -Fc --no-owner --no-privileges -f "$file"
"${PGBIN}pg_restore" -l "$file" > /dev/null          # el archivo debe ser legible
if [ -n "${BACKUP_PASSPHRASE:-}" ]; then
  openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:BACKUP_PASSPHRASE -in "$file" -out "$file.enc"
  rm "$file"; file="$file.enc"
fi
( cd "$BACKUP_DIR" && shasum -a 256 "$(basename "$file")" > "$(basename "$file").sha256" )
find "$BACKUP_DIR" -name 'sapuam-*' -mtime +"$KEEP_DAYS" -delete
echo "$file"
