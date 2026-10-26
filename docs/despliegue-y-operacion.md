# Despliegue y operación

## Qué se probó y qué no

| Elemento | Estado |
|---|---|
| Migraciones Flyway V1 a V4 en PostgreSQL 16 (V4 con relleno de un intento ya entregado y borrado en cascada) + validación del esquema contra las entidades | Probado |
| Respaldo, cifrado, checksum y restauración con comparación de conteos; detección de archivo corrupto | Probado (`ops/backup.sh`, `ops/restore-test.sh`) |
| Flujo completo con 300 aspirantes simultáneos (100 a la vez) sobre PostgreSQL | Probado: 300/300, 0 errores, ~1 150 req/s en un portátil |
| Imágenes Docker, `docker-compose.yml`, `nginx.conf` | **Sin construir ni ejecutar** (Docker no está instalado donde se desarrolló). Lo ejercita el job `imagenes` de CI |
| Correo real (SMTP) | **Sin probar**: solo el modo `log` |
| TLS | Pendiente: lo pone el balanceador de la institución o nginx |

La prueba de carga encontró y se corrigieron tres defectos que H2 no mostraba: una auditoría dentro de una transacción de solo lectura,
un bloqueo del pool de conexiones en login/canje con más de 10 personas a la vez, y un límite por IP (20) inviable para un campus tras una sola IP.

## Puesta en marcha (referencia)

```bash
cp .env.example .env        # completar; generar secretos con openssl
docker compose up -d --build
```

El backend con perfil `prod` se niega a arrancar si falta una variable obligatoria (ver `.env.example`).
Flyway crea el esquema; Hibernate solo lo valida. Cada cambio de esquema es un `V2__...sql` nuevo; nunca se edita uno aplicado.

## Capacidad y ajustes

- Hilos de Tomcat (`SERVER_THREADS_MAX`, 32) **deben ser menos** que el pool (`DB_POOL_SIZE`, 40): el control de intentos fallidos usa una segunda conexión.
- Límite por IP en `/api/auth/*` y canje: `app.security.auth-rate-limit.max-requests` (300 cada 5 min por defecto). Si todo el campus sale por una IP,
  súbalo; la protección de contraseñas es por cuenta, no por IP. Detrás de nginx se usa `X-Forwarded-For`.
- Reproducir la medición: backend en perfil `pg` con `--app.mail.poll-ms=1000 --app.security.auth-rate-limit.max-requests=1000000`, luego
  `python3 tools/loadtest.py --base URL --admin-email ... --admin-password ... --users 300 --concurrency 100`.
- La carga real de un examen es menor que la de la prueba (los aspirantes tardan minutos entre respuestas); la prueba mide el pico de entrada y entrega.

## Respaldos

```bash
PGHOST=... PGUSER=... PGPASSWORD=... PGDATABASE=sapuam BACKUP_DIR=/var/backups/sapuam BACKUP_PASSPHRASE=... ops/backup.sh   # diario, por cron
ops/restore-test.sh /var/backups/sapuam/sapuam-AAAAMMDDThhmmssZ.dump.enc                                                    # semanal
```

- Objetivo propuesto: RPO 24 h (respaldo diario; con WAL archivado baja a minutos), RTO < 1 h. **Son objetivos, no garantías**: confírmelos con quien opere el servidor.
- Guarde la frase de cifrado fuera del servidor. Sin ella el respaldo no se puede restaurar.
- Copie los respaldos fuera de la máquina que aloja la base.

## Datos personales

Aviso de privacidad (`backend/src/main/resources/privacy/notice-v1.md`) es un **borrador**: debe revisarlo el área jurídica y quien ejerza como
responsable de datos antes del uso real. La retención (24 meses por defecto, `APP_RETENTION_MONTHS`) y su activación (`APP_RETENTION_ENABLED`) son decisiones
institucionales pendientes.
