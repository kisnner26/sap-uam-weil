# CampusFood UAM — Backend

Backend en **Spring Boot 3.3 / Java 17** para la web de pedidos de comida del campus
UAM Managua. Implementa un único login con dos métodos que conviven:

1. **Login con cuenta UAM (Moodle)** — valida CIF + contraseña contra la API de
   Moodle de la UAM, server-to-server (sin CORS).
2. **Registro/login manual** — para externos (no estudiantes), con correo + contraseña.

En ambos casos el backend emite **su propio JWT** de sesión (no reutiliza el token
de Moodle) y lo entrega también como cookie **httpOnly** `campusfood_session`.

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
(JDBC URL `jdbc:h2:mem:campusfood`, usuario `sa`, sin contraseña).

### Tests

```bash
mvn test
```

Incluye un test de integración (`MoodleLoginIntegrationTest`) que simula Moodle con
MockWebServer y cubre: login OK (crea usuario + JWT, **sin** guardar la contraseña),
credenciales inválidas → 401, y Moodle respondiendo HTML → 503.

## Variables de entorno (producción)

| Variable               | Descripción                                              |
|------------------------|----------------------------------------------------------|
| `APP_JWT_SECRET`       | Secreto HMAC ≥ 32 bytes para firmar el JWT propio.       |
| `APP_JWT_EXPIRATION_MS`| Vigencia del token (ms). Default 24h.                    |
| `APP_CORS_ORIGINS`     | Orígenes del frontend separados por coma.                |
| `MOODLE_BASE_URL`      | Base de Moodle. Default `https://uamvirtual.uam.edu.ni/grado`. |
| `DB_URL/DB_USER/DB_PASSWORD` | Conexión Postgres (perfil `prod`).                 |

Arrancar en producción:

```bash
SPRING_PROFILES_ACTIVE=prod APP_JWT_SECRET=... DB_URL=... mvn spring-boot:run
```

> Con el perfil `prod`, JPA usa `ddl-auto: validate`: creá la tabla antes con
> [`src/main/resources/db/schema_postgres.sql`](src/main/resources/db/schema_postgres.sql).

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
También setea `Set-Cookie: campusfood_session=<jwt>; HttpOnly; SameSite=Lax`.
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
Lee la cookie httpOnly `campusfood_session` o el header `Authorization: Bearer <jwt>`.
**401** si falta o es inválido.

### `POST /api/auth/logout`
Borra la cookie httpOnly local del navegador.

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
