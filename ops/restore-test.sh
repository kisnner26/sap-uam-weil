#!/usr/bin/env bash
# Prueba de restauracion: verifica checksum, restaura en una base temporal y compara conteos con el origen.
# Uso: ops/restore-test.sh <archivo.dump[.enc]>   (mismas variables PG* que backup.sh; PGDATABASE = origen)
set -euo pipefail
src="${1:?uso: restore-test.sh archivo}"; PGBIN="${PGBIN:-}"; [ -n "$PGBIN" ] && PGBIN="$PGBIN/"
( cd "$(dirname "$src")" && shasum -a 256 -c "$(basename "$src").sha256" >/dev/null ) || { echo "FALLO: checksum no coincide"; exit 2; }
dump="$src"
if [[ "$src" == *.enc ]]; then
  dump="$(mktemp)"; openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_PASSPHRASE -in "$src" -out "$dump"
fi
scratch="restore_test_$$"; origin="${PGDATABASE:?}"
"${PGBIN}psql" -d postgres -qc "create database $scratch"
trap '"${PGBIN}psql" -d postgres -qc "drop database if exists $scratch" >/dev/null' EXIT
"${PGBIN}pg_restore" --no-owner -d "$scratch" "$dump"
tables="users exam_sessions exam_enrollments assessment_attempts assessment_responses assessment_events consent_records notification_outbox audit_events"
status=0
for t in $tables; do
  a="$("${PGBIN}psql" -d "$origin" -Atc "select count(*) from $t")"
  b="$("${PGBIN}psql" -d "$scratch" -Atc "select count(*) from $t")"
  [ "$a" = "$b" ] && echo "ok   $t: $b" || { echo "DIFF $t: origen=$a restaurado=$b"; status=1; }
done
v="$("${PGBIN}psql" -d "$scratch" -Atc "select max(version::int) from flyway_schema_history where success" 2>/dev/null || echo ?)"
echo "version de esquema restaurada: $v"
[ $status = 0 ] && echo "RESTAURACION VERIFICADA" || echo "RESTAURACION CON DIFERENCIAS"
exit $status
