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
2. persistir intentos y respuestas en el backend manteniendo el borrador local como recuperación ante fallos, ya aplicado mediante `/api/attempts`.
3. mover la clave y el baremo de weil al adaptador de calificación del backend.
4. cambiar el frontend para que la entrega consuma `POST /submit` y solo muestre el resultado devuelto.
5. agregar el próximo test como otro contrato y otra estrategia, sin copiar el shell ni la autenticación.

## seguridad y privacidad

- la contraseña moodle y su token temporal nunca se almacenan.
- el navegador no debe recibir claves de respuesta en producción.
- los reportes requieren autorización por propietario o rol institucional.
- el log de incidencias tiene retención definida y no se usa como diagnóstico psicológico.
- los datos psicométricos se tratan como información sensible: cifrado en tránsito, acceso mínimo y auditoría de lectura.

## patrones aplicados

### lazy load

- el visor inserta únicamente la imagen del ítem actual y precarga solo la siguiente.
- la imagen define sus dimensiones y presenta estados de carga y error para evitar saltos visuales.
- el índice local de resultados contiene solo resúmenes; respuestas, incidencias y desglose se leen desde una clave de detalle cuando se abre un reporte.
- `GET /api/attempts` devuelve resúmenes y `GET /api/attempts/{id}` carga las respuestas bajo demanda.

### mvc observer

`frontend/platform-state.js` publica eventos de dominio. la vista, el repositorio de borradores y los controles de navegación se suscriben por separado. una respuesta actualiza selección, progreso y estado del ítem sin reconstruir toda la pantalla; un cambio de ítem sí reconstruye el visor porque cambia el recurso principal.

### optimistic y pessimistic offline lock

- al abrir un intento, el servicio bloquea pesimistamente la fila del usuario. así dos solicitudes simultáneas no crean dos intentos activos.
- el intento entrega un arrendamiento exclusivo de dos minutos. el mismo cliente lo renueva; otra ventana o dispositivo recibe `409 conflict` mientras siga vigente.
- cada escritura incluye `expectedVersion`. `@Version` detecta modificaciones concurrentes aunque ambas solicitudes hayan leído el mismo estado.
- el índice parcial `uk_attempt_active` refuerza en postgres la regla de un intento activo por usuario y test.

### client session state

`frontend/client-session.js` guarda en el dispositivo únicamente respuestas, posición, hora de inicio e incidencias. no persiste puntaje, percentil, ci, clasificación ni claves dentro del borrador. los datos locales permiten recuperación, pero no son autoridad para una entrega de producción; el backend vuelve a validar versión, propiedad, sesión exclusiva y respuestas completas.

al entregar, el backend recalcula el puntaje directo y el desglose por lámina con la clave versionada. el frontend usa ese valor en producción; el cálculo local queda limitado a la demostración estática de github pages.

## Limitaciones conocidas

- **La clave de respuestas del Weil también viaja en `frontend/test-catalog.js`.** Con backend, la
  calificación oficial se calcula en el servidor (`AssessmentService`), pero el modo demo
  (GitHub Pages, sin backend) y la revisión de respuestas necesitan la clave en el navegador, así
  que cualquiera puede leerla antes de rendir el test. Para evaluaciones reales, la clave debe
  salir del frontend: el servidor devolvería las respuestas correctas solo después de entregar,
  y el modo demo quedaría sin calificación local.
- **El límite de intentos de acceso es por instancia** (contador en memoria). Con varias réplicas
  del backend habría que moverlo a un almacén compartido.
- **El modo demo** guarda sus cuentas en `localStorage` del navegador (con contraseña protegida
  con PBKDF2); sirve para probar la interfaz, no para uso real.

## Respuesta por arrastre (piezas)

- `tools/build_puzzle_assets.py` corta cada ítem de las láminas completas: localiza los 12 marcos de la
  página, ajusta la cuadrícula de 2×4 opciones y el hueco, y guarda la figura base y 8 piezas
  (`frontend/assets/weill-puzzle`, ~540 archivos) más `frontend/puzzle-data.js` con el tamaño y el hueco.
- Se corta desde las láminas y no desde `weill-items`, porque varios recortes de esa carpeta están
  desplazados y les faltan partes (sobre todo en la lámina 4).
- `frontend/puzzle-view.js` maneja el arrastre con eventos de puntero, no con el arrastre nativo del
  navegador: la prueba bloqueada registra `dragstart` y `drop` como incidencias.
- La respuesta sigue siendo un número 1 a 8 (`selectAnswer`), por lo que no cambian la calificación,
  los locks ni la API.
- Limitación: en la lámina 4 los ítems de la columna derecha vienen cortados por el borde del escaneo
  original, así que a su pieza 4 le falta parte del borde derecho.
