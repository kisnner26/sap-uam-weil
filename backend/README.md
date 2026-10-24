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
El contador vive en memoria: con varias instancias del backend cada una lleva el suyo.

> Con el perfil `prod`, JPA usa `ddl-auto: validate`: creá la tabla antes con
> [`src/main/resources/db/schema_postgres.sql`](src/main/resources/db/schema_postgres.sql).

## Resultados solo para el personal

Las respuestas de `/api/attempts/**` (las del aspirante) no incluyen puntaje, puntajes por lámina, percentil
ni CI. Los resultados se listan con `GET /api/staff/attempts?testId=weil-form-a`, que exige rol `STAFF` o
`ADMIN` (sin sesión responde 401; con otro rol, 403). Cada fila trae el aspirante, el puntaje directo, el
puntaje por lámina, la edad y el grupo de baremo declarados al entregar.

Los roles se asignan en la base de datos: `UPDATE users SET role = 'STAFF' WHERE email = '...';`.
Las bases que ya existían necesitan las dos columnas nuevas del intento: están como `ALTER TABLE ... IF NOT EXISTS`
en [`schema_postgres.sql`](src/main/resources/db/schema_postgres.sql).

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
