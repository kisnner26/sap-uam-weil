# SAP-UAM — Backend

Backend en **Spring Boot 3.3 / Java 17** para la plataforma de evaluaciones de la
UAM. Implementa una identidad compartida con dos métodos de acceso:

1. **Login con cuenta UAM (Moodle)** — valida CIF + contraseña contra la API de
   Moodle de la UAM, server-to-server (sin CORS).
2. **Registro/login manual** — para externos (no estudiantes), con correo + contraseña.

En ambos casos el backend emite **su propio JWT** de sesión (no reutiliza el token
de Moodle) y lo entrega también como cookie **httpOnly** `sap_uam_session`.

## Stack

- Spring Boot 3.3.5 (Web, Security, Data JPA, Validation)
- Java 17
- PostgreSQL (perfil `prod`) · H2 en memoria (perfil `dev`)
- JWT con `jjwt` 0.12 · contraseñas manuales con BCrypt
- `RestClient` para las 2 llamadas a Moodle

## Cómo correr (desarrollo, H2)

```bash
cd backend
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home)
mvn spring-boot:run
```

Arranca en `http://localhost:8080`. Consola H2 en `/h2-console`
(JDBC URL `jdbc:h2:mem:sapweill`, usuario `sa`, sin contraseña).

### Tests

```bash
mvn test
```

Incluye un test de integración (`MoodleLoginIntegrationTest`) que simula Moodle con
MockWebServer y cubre: login OK (crea usuario + JWT, **sin** guardar la contraseña),
credenciales inválidas → 401, y Moodle respondiendo HTML → 503.

`AssessmentLockIntegrationTest` verifica que una segunda sesión no pueda tomar un
intento activo y que una versión desactualizada no sobrescriba cambios recientes.

## Variables de entorno (producción)

| Variable               | Descripción                                              |
|------------------------|----------------------------------------------------------|
| `APP_JWT_SECRET`       | **Obligatoria.** Secreto HMAC ≥ 32 bytes para firmar el JWT propio. |
| `APP_CORS_ORIGINS`     | **Obligatoria.** Orígenes del frontend separados por coma. |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | **Obligatorias.** Conexión Postgres.       |
| `APP_MFA_ENCRYPTION_KEY` | **Obligatoria en prod.** Clave de 32 bytes que cifra los secretos del segundo factor. |
| `APP_BOOTSTRAP_ADMIN_EMAIL`, `APP_BOOTSTRAP_ADMIN_PASSWORD` | **Obligatorias en prod.** Primer administrador (contraseña ≥ 12 caracteres). Solo se usan si no hay ningún `ADMIN`. |
| `APP_BOOTSTRAP_ADMIN_NAME` | Nombre del primer administrador. |
| `APP_JWT_EXPIRATION_MS`| Vigencia del token (ms). Default 24h.                    |
| `MOODLE_BASE_URL`      | Base de Moodle. Default `https://uamvirtual.uam.edu.ni/grado`. |
| `WEIL_DURATION_SECONDS`| Duración oficial del intento. Default 1200 segundos.           |

Con el perfil `prod` las variables obligatorias **no tienen valor por defecto**: si falta
alguna, la aplicación se niega a arrancar y nombra cuál falta (en vez de iniciar con una
clave o una base de datos conocidas).

Arrancar en producción:

```bash
SPRING_PROFILES_ACTIVE=prod APP_JWT_SECRET=... APP_CORS_ORIGINS=... \
  DB_URL=... DB_USER=... DB_PASSWORD=... mvn spring-boot:run
```

### Límite de intentos de acceso

`/api/auth/login`, `/api/auth/moodle-login` y `/api/auth/register` aceptan, por IP, 20
peticiones cada 5 minutos; pasado el límite responden `429` con `Retry-After`. Se ajusta con
`app.security.auth-rate-limit.max-requests` y `app.security.auth-rate-limit.window-seconds`.
El contador vive en la base de datos, así que se comparte entre instancias.

## Migraciones

Con los perfiles `pg` y `prod`, Flyway aplica `src/main/resources/db/migration/V*.sql` y JPA solo valida (`ddl-auto: validate`).
Un cambio de esquema es un archivo nuevo (`V2__...sql`); nunca se edita uno ya aplicado. Con el perfil `dev` (H2) Hibernate crea las tablas.
Para probar contra PostgreSQL local sin instalarlo en el sistema: `ops/dev-postgres.sh start` y arrancar con `SPRING_PROFILES_ACTIVE=pg`.

## Resultados solo para el personal

Las respuestas de `/api/attempts/**` (las del aspirante) no incluyen puntaje, puntajes por lámina, percentil
ni CI. Los resultados se listan con `GET /api/staff/attempts?testId=weil-form-a`, que exige rol `STAFF` o
`ADMIN` (sin sesión responde 401; con otro rol, 403). Cada fila trae el aspirante, el puntaje directo, el
puntaje por lámina, la edad y el grupo de baremo declarados al entregar.

Los roles los administra un `ADMIN` desde el panel (`/api/admin/users`); no hace falta editar la base.
El primer administrador se crea con `APP_BOOTSTRAP_ADMIN_EMAIL` / `APP_BOOTSTRAP_ADMIN_PASSWORD`.
Una base creada antes de Flyway debe respaldarse y migrarse a mano: la `V1` crea el esquema completo y no sirve sobre tablas existentes.

## Endpoints

### `POST /api/auth/moodle-login` — login con cuenta UAM
```json
// Request
{ "cif": "123456", "password": "secreta" }

// 200 OK
{
  "token": "<jwt-propio>",
  "tokenType": "Bearer",
  "expiresIn": 86400,
  "user": {
    "id": 1, "fullName": "Ana López", "email": "ana@uam.edu.ni",
    "pictureUrl": "https://…", "role": "CLIENTE",
    "authProvider": "moodle", "moodleId": 4567
  }
}
```
También setea `Set-Cookie: sap_uam_session=<jwt>; HttpOnly; SameSite=Lax`.
Errores: **401** credenciales inválidas · **503** `"Plataforma UAM en mantenimiento,
intenta mas tarde"` (Moodle devolvió HTML o no responde).

### `POST /api/auth/register` — registro manual (externos)
```json
{ "fullName": "Juan Pérez", "email": "juan@gmail.com", "password": "min8chars", "cedula": "001-..." }
```
**201** con la misma forma de `AuthResponse`. **409** si el correo ya existe.

### `POST /api/auth/login` — login manual
```json
{ "email": "juan@gmail.com", "password": "min8chars" }
```

### `GET /api/auth/me` — perfil del usuario autenticado
Lee la cookie httpOnly `sap_uam_session` o el header `Authorization: Bearer <jwt>`.
**401** si falta o es inválido.

### `POST /api/auth/logout`
Borra la cookie httpOnly local del navegador.

### Intentos de evaluación

Todos requieren una sesión autenticada:

- `GET /api/attempts`: lista liviana de intentos, sin respuestas.
- `POST /api/attempts`: crea o recupera un intento y entrega un arrendamiento exclusivo.
- `GET /api/attempts/{id}`: carga el detalle y las respuestas cuando se solicitan.
- `POST /api/attempts/{id}/lease`: renueva el arrendamiento exclusivo.
- `PUT /api/attempts/{id}/responses/{itemIndex}`: sincronización idempotente opcional.
- `DELETE /api/attempts/{id}/responses/{itemIndex}`: elimina una respuesta guardada.
- `GET /api/attempts/{id}/material/{itemIndex}/{asset}`: entrega la figura actual o siguiente.
- `POST /api/attempts/{id}/events`: registra una incidencia del modo examen.
- `POST /api/attempts/{id}/submit`: valida y guarda las 60 respuestas en una transacción.

Las escrituras envían `leaseToken` y `expectedVersion`. Un arrendamiento ajeno,
vencido o una versión desactualizada responde **409 Conflict**.

El plazo se fija al crear el intento. Reabrir, recargar o perder conexión conserva la hora límite y
las respuestas ya guardadas. Al vencer, el servidor rechaza renovaciones, cambios y entregas tardías.

## Flujo Moodle (2 llamadas, desde el backend)

1. `GET {base}/login/token.php?username=CIF&password=…&service=moodle_mobile_app` → token.
2. `GET {base}/webservice/rest/server.php?wstoken=TOKEN&wsfunction=core_webservice_get_site_info&moodlewsrestformat=json`
   → `fullname`, `useremail`, `userid`, `userpictureurl`.

Luego: busca usuario por `moodleId` → si no existe lo crea → sincroniza perfil →
emite JWT propio.

### Garantías de seguridad
- La **contraseña de Moodle nunca se persiste**: solo viaja a la llamada 1 y se descarta
  (`User.passwordHash` queda `null` para usuarios Moodle).
- El **token de Moodle no se persiste**: vive dentro de `MoodleClient.authenticate()` y
  se usa solo para la llamada 2.
- Las contraseñas manuales se guardan solo como hash BCrypt.

## Modelo de datos (tabla `users`)

| Columna         | Notas                                              |
|-----------------|----------------------------------------------------|
| `moodle_id`     | `INTEGER` nullable **unique** → `userid` de Moodle |
| `auth_provider` | `manual` \| `moodle`                               |
| `password_hash` | BCrypt; `NULL` para usuarios Moodle                |
| `cedula`        | usado en registro manual                           |

## Frontend de ejemplo

[`frontend-example/login.html`](frontend-example/login.html) — formulario con input CIF
numérico, contraseña, botón "Ingresar con cuenta UAM", estado de carga, manejo de error
(credenciales / mantenimiento) y guardado de la sesión propia.

## Identidad, segundo factor y administración

| Método | Endpoint | Quién | Descripción |
|--------|----------|-------|-------------|
| POST | `/api/auth/login` | público | Aspirantes: sesión directa. `STAFF`/`ADMIN`: responde `202` con un reto de segundo factor |
| POST | `/api/auth/mfa/verify` | público (con reto) | Valida el código TOTP de 6 dígitos y abre la sesión |
| POST | `/api/auth/refresh` | sesión | Renueva el token |
| POST | `/api/auth/logout` | sesión | Cierra la sesión **y la invalida en el servidor** |
| GET | `/api/admin/users` | `ADMIN` | Lista del personal |
| POST | `/api/admin/users` | `ADMIN` | Crea una cuenta `STAFF`/`ADMIN` con contraseña temporal |
| PATCH | `/api/admin/users/{id}` | `ADMIN` | Cambia rol o estado (cierra sus sesiones). No se puede quitar el propio acceso |
| POST | `/api/admin/users/{id}/mfa-reset` | `ADMIN` | Obliga a configurar de nuevo el segundo factor |
| GET | `/api/admin/audit` | `ADMIN` | Registro de auditoría (últimos 200 eventos) |

Cada petición vuelve a leer al usuario en la base: una cuenta desactivada, un rol cambiado o una sesión
revocada dejan de servir al instante. El límite de intentos de acceso se guarda en la base de datos
(por IP y por cuenta, con bloqueo progresivo), por lo que vale para varias instancias del servidor.

Prueba de punta a punta contra un backend en marcha: `python3 tools/e2e_identity.py` (ver su encabezado).
