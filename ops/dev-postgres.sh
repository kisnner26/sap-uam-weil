#!/usr/bin/env bash
# PostgreSQL temporal para desarrollo y pruebas. No toca ninguna instalacion existente:
# crea su propio cluster en un directorio aparte, solo escucha en 127.0.0.1 y usa otro puerto.
#
#   ops/dev-postgres.sh start    crea (si hace falta) y arranca el cluster
#   ops/dev-postgres.sh stop     lo detiene
#   ops/dev-postgres.sh url      imprime la URL JDBC
#   ops/dev-postgres.sh psql     abre psql contra la base
#
# Variables: DEV_PG_DIR (por defecto /tmp/sap-uam-pg), DEV_PG_PORT (55432), PG_BIN (carpeta con pg_ctl).
set -euo pipefail
# En macOS PostgreSQL se niega a arrancar sin una configuracion regional valida.
export LC_ALL="${LC_ALL:-en_US.UTF-8}" LANG="${LANG:-en_US.UTF-8}"

DIR="${DEV_PG_DIR:-${TMPDIR:-/tmp}/sap-uam-pg}"
PORT="${DEV_PG_PORT:-55432}"
DB="${DEV_PG_DB:-sapweill}"
USER_NAME="${DEV_PG_USER:-sapweill}"
PASS="${DEV_PG_PASSWORD:-sapweill-dev}"   # solo desarrollo: el servidor no es accesible desde fuera

if [[ -z "${PG_BIN:-}" ]]; then
  if command -v pg_ctl >/dev/null 2>&1; then PG_BIN="$(dirname "$(command -v pg_ctl)")"
  else PG_BIN="$(dirname "$(ls /opt/homebrew/opt/postgresql@*/bin/pg_ctl /usr/lib/postgresql/*/bin/pg_ctl 2>/dev/null | sort -V | tail -1)")"; fi
fi
[[ -x "$PG_BIN/pg_ctl" ]] || { echo "No encuentro PostgreSQL. Defina PG_BIN con la carpeta que contiene pg_ctl." >&2; exit 1; }

case "${1:-}" in
  start)
    if [[ ! -d "$DIR/data" ]]; then
      mkdir -p "$DIR"
      pwfile="$(mktemp)"; printf '%s' "$PASS" > "$pwfile"
      "$PG_BIN/initdb" -D "$DIR/data" -U "$USER_NAME" --auth=scram-sha-256 --pwfile="$pwfile" -E UTF8 --locale=C >/dev/null
      rm -f "$pwfile"
    fi
    if ! "$PG_BIN/pg_ctl" -D "$DIR/data" status >/dev/null 2>&1; then
      "$PG_BIN/pg_ctl" -D "$DIR/data" -l "$DIR/postgres.log" -w \
        -o "-p $PORT -c unix_socket_directories= -c listen_addresses=127.0.0.1" start >/dev/null
    fi
    if ! PGPASSWORD="$PASS" "$PG_BIN/psql" -h 127.0.0.1 -p "$PORT" -U "$USER_NAME" -d postgres -tAc \
         "select 1 from pg_database where datname='$DB'" | grep -q 1; then
      PGPASSWORD="$PASS" "$PG_BIN/createdb" -h 127.0.0.1 -p "$PORT" -U "$USER_NAME" "$DB"
    fi
    echo "PostgreSQL listo en 127.0.0.1:$PORT (base $DB, usuario $USER_NAME)"
    ;;
  stop)
    "$PG_BIN/pg_ctl" -D "$DIR/data" -m fast stop >/dev/null 2>&1 && echo "PostgreSQL detenido" || echo "No estaba en marcha"
    ;;
  url)
    echo "jdbc:postgresql://127.0.0.1:$PORT/$DB"
    ;;
  psql)
    PGPASSWORD="$PASS" exec "$PG_BIN/psql" -h 127.0.0.1 -p "$PORT" -U "$USER_NAME" -d "$DB"
    ;;
  *)
    echo "Uso: $0 start|stop|url|psql" >&2; exit 2
    ;;
esac
