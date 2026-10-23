# arquitectura de la plataforma de evaluaciones sap-uam

## decisión

el sistema debe crecer como un monolito modular. autenticación, usuarios, auditoría y reportes son capacidades compartidas; la presentación de ítems y la calificación cambian según el test. separar estos límites permite agregar evaluaciones sin duplicar aplicaciones y mantiene un despliegue sencillo para la uam.

no conviene dividirlo en microservicios mientras el volumen y los equipos no lo exijan. la separación inicial debe existir en el código y en el modelo de datos, no en infraestructura adicional.

## módulos

| módulo | responsabilidad | no debe conocer |
| --- | --- | --- |
| identidad | acceso moodle, cuentas externas, roles y sesión | claves o baremos de tests |
| catálogo | definición versionada, secciones, ítems y disponibilidad | respuestas de aspirantes |
| aplicaciones | asignación, intento activo, respuestas, tiempo e incidencias | detalles del algoritmo de calificación |
| calificación | estrategia específica por test y versión | autenticación o interfaz |
| reportes | resultado inmutable, exportación y consulta autorizada | credenciales moodle |
| administración | publicación de versiones y asignaciones | implementación interna de cada estrategia |

## contrato de un test

cada test se registra mediante una definición inmutable:

```text
testDefinition
├── id estable: weil-form-a
├── version: 1
├── nombre, categoría e instrucciones
├── secciones e ítems
├── opciones permitidas
├── política de navegación y tiempo
├── adaptador de recursos visuales
└── scoringStrategy: weil-v1
```

la primera implementación está en `frontend/test-catalog.js`. la interfaz consume el contrato y ya no construye directamente las rutas de imágenes ni los tamaños del test.

## modelo de datos objetivo

- `test_definitions`: identidad pública, versión, estado y metadatos. una versión publicada no se edita.
- `test_assignments`: test asignado a un usuario, ventana de disponibilidad y número de intentos.
- `assessment_attempts`: estado `CREATED`, `IN_PROGRESS`, `SUBMITTED` o `SCORED`, marcas de tiempo y versión optimista.
- `assessment_responses`: una respuesta por intento e ítem, guardada mediante upsert idempotente.
- `assessment_results`: puntaje directo y resultado derivado junto con la versión exacta del algoritmo.
- `assessment_events`: incidencias técnicas o de modo examen; es auditoría, no calificación.

invariantes:

1. un intento siempre referencia una versión publicada del test.
2. una respuesta pertenece al usuario dueño del intento.
3. solo puede existir un intento activo por asignación.
4. la entrega es transaccional y solo ocurre cuando están completas las respuestas obligatorias.
5. el resultado no se recalcula silenciosamente si cambia un baremo; se crea una nueva versión.
6. las claves y baremos de producción se calculan en el backend. el modo estático de github pages es únicamente una demostración local.

## api objetivo

```text
GET    /api/tests
GET    /api/tests/{testId}
GET    /api/me/assignments
POST   /api/assignments/{assignmentId}/attempts
GET    /api/attempts/{attemptId}
PUT    /api/attempts/{attemptId}/responses/{itemId}
POST   /api/attempts/{attemptId}/submit
GET    /api/attempts/{attemptId}/result
```

`PUT responses` acepta reintentos de red sin crear duplicados. `submit` usa bloqueo optimista: si dos solicitudes intentan cerrar la misma aplicación, solo una puede cambiar el estado.

## flujo de frontend

```text
sesión compartida
  → evaluaciones asignadas
  → definición del test
  → intento y borrador
  → aplicación genérica
  → entrega
  → reporte específico
```

las pantallas de acceso, inicio, perfil y resultados pertenecen a la plataforma. el visor, las opciones y la calificación reciben la definición activa; no deben contener condicionales dispersos por nombre de test.

## migración recomendada

1. consolidar el catálogo y las claves de almacenamiento por `testId`, ya aplicado en el frontend.
2. persistir asignaciones, intentos y respuestas en el backend manteniendo el borrador local como recuperación ante fallos.
3. mover la clave y el baremo de weil al adaptador de calificación del backend.
4. cambiar el frontend para que la entrega consuma `POST /submit` y solo muestre el resultado devuelto.
5. agregar el próximo test como otro contrato y otra estrategia, sin copiar el shell ni la autenticación.

## seguridad y privacidad

- la contraseña moodle y su token temporal nunca se almacenan.
- el navegador no debe recibir claves de respuesta en producción.
- los reportes requieren autorización por propietario o rol institucional.
- el log de incidencias tiene retención definida y no se usa como diagnóstico psicológico.
- los datos psicométricos se tratan como información sensible: cifrado en tránsito, acceso mínimo y auditoría de lectura.
