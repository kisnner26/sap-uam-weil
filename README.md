# SAP-UAM / CampusFood UAM

Frontend estático con login único y backend **Spring Boot** para autenticar usuarios
contra Moodle UAM o mediante cuenta manual para externos.

## Stack real

- Frontend: HTML, CSS y JavaScript vanilla.
- Backend: Spring Boot 3.3.5, Java 17, Spring Security, JPA, Validation.
- Base de datos: H2 en desarrollo, PostgreSQL en producción.
- Sesión: JWT propio de la app, enviado también como cookie httpOnly
  `campusfood_session`.
- Integración UAM: Moodle server-to-server con `RestClient`.

## Estructura

```text
index.html
styles.css
app.js
assets/
backend/
  pom.xml
  src/main/java/ni/edu/uam/campusfood/
  src/main/resources/application.yml
  src/main/resources/db/schema_postgres.sql
```

## Ejecutar frontend

```bash
python3 -m http.server 8765
```

Abrir:

```text
http://127.0.0.1:8765/index.html
```

## Ejecutar backend

```bash
cd backend
mvn spring-boot:run
```

Backend:

```text
http://localhost:8080
```

## Autenticación

- `POST /api/auth/moodle-login`: CIF + contraseña UAM. El backend hace las 2
  llamadas a Moodle, nunca el navegador.
- `POST /api/auth/register`: cuenta manual para externos con correo + contraseña + cédula.
- `POST /api/auth/login`: login manual.
- `GET /api/auth/me`: sesión actual por cookie httpOnly o bearer token.
- `POST /api/auth/logout`: borra la cookie de sesión.

La contraseña de Moodle y el token de Moodle nunca se guardan en base de datos.

## Pruebas

```bash
cd backend
mvn test
```

Las pruebas usan un Moodle simulado con MockWebServer.
