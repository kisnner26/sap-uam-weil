# integración de weil en un producto único de tests

## objetivo

la entrega final es una sola solución con la misma arquitectura, nomenclatura, estilos y diseño de interfaz. weil, la batería factorial de aptitudes, thurstone, allport y orientación vocacional no son proyectos separados: son incrementos funcionales del mismo sistema, con un solo repositorio, una sola arquitectura y un solo product backlog.

este documento aterriza `architecture.md` a ese objetivo: qué ya está listo, qué acopla hoy a weil y qué contrato debe cumplir cualquier test nuevo.

## lo que ya está alineado

- monolito modular con identidad, auditoría, privacidad y notificaciones compartidos.
- `assessment_attempts` ya guarda `test_id` y `test_version`; la unicidad de intento activo es por usuario y test.
- el frontend ya consulta un catálogo (`SAP_UAM_TESTS`) y claves de almacenamiento por `testId`.
- el backend ya calcula la calificación y nunca envía claves al navegador.

## acoplamientos a weil que hay que romper

| dónde | acoplamiento | consecuencia para otro test |
| --- | --- | --- |
| `AssessmentService` | constantes `WEIL_ITEMS=60`, `WEIL_OPTIONS=8`, `WEIL_VERSION` | un test de 100 ítems o con otra escala no cabe |
| `WeilScoringStrategy` | clase concreta inyectada en `AssessmentService` y `RetentionService` | no hay interfaz común ni registro de estrategias |
| `V1__esquema_inicial.sql` | `selected_option BETWEEN 1 AND 8`, `direct_score`, `participant_age`, `norm_group` en `assessment_attempts` | thurstone y allport usan escalas y rankings; la batería factorial puntúa por subtest y producen perfiles, no un solo número |
| `AdmissionService` | `WEIL = "weil-form-a"` fijo al crear sesiones | una sesión de examen solo puede ser de weil |
| `AssessmentMaterialController` | ruta `test-material/weil-form-a/puzzle/...` | el material de otros tests necesita otro adaptador |
| `app.js` (3131 líneas) | visor de láminas y copy de weil mezclados con el shell | cada test nuevo engordaría el mismo archivo |
| paquete `ni.edu.uam.campusfood` | nombre heredado de otro proyecto | rompe la nomenclatura común; hay que fijarla ahora |

## contrato de un test

todo test se entrega como un módulo con cuatro piezas y se registra en un catálogo; ningún código de plataforma pregunta por el nombre del test.

```text
testModule
├── definition      id estable, versión, categoría, secciones, ítems, tiempo, navegación
├── itemFormat      cómo se responde: single-choice, likert, forced-choice, ranking, timed-subtest
├── scoringStrategy interfaz única en backend: score(answers, context) -> ScoreResult
└── reportView      cómo se presenta el resultado: puntaje directo, perfil por escalas, intereses, valores
```

`ScoreResult` es genérico: una lista de escalas (`code`, `label`, `raw`, `derived`, `band`) más la versión del algoritmo. weil produce una escala; thurstone, allport y la batería producen varias. así el reporte consolidado de un aspirante puede mezclar resultados de tests distintos.

## cambios de modelo de datos

migración `V4` (no se toca `V1`):

1. `assessment_responses`: reemplazar el `CHECK` de 1 a 8 por una validación en la estrategia del test; guardar `answer` como valor genérico (entero o texto corto) según el `itemFormat`.
2. `assessment_results`: tabla nueva con `attempt_id`, `scoring_version` y `scales` (json). `direct_score`, `participant_age` y `norm_group` salen de `assessment_attempts` y pasan a ser escalas o contexto del resultado.
3. `exam_sessions` y `test_assignments`: una asignación puede incluir varios tests en orden (una batería de admisión o de orientación).
4. estado del intento: ampliar `ck_attempt_status` para `CREATED` y `SCORED`, como ya prevé `architecture.md`.

invariantes que se conservan: un intento activo por usuario y test, respuestas idempotentes, entrega transaccional, resultado inmutable por versión de algoritmo.

## nomenclatura común

- identificadores de test: `<nombre>-<forma>`, minúsculas y guiones (`weil-form-a`, `thurstone-intereses-v1`).
- código de interfaz y de datos en inglés técnico (`attempt`, `scale`, `assignment`); textos visibles al usuario en español.
- un test nuevo vive en `backend/.../tests/<testId>/` (estrategia y material) y `frontend/tests/<testId>/` (formato de ítem y reporte).
- el paquete base pasa a `ni.edu.uam.sap`.

## diseño de interfaz común

el shell (acceso, inicio, perfil, ajustes, entrega) es de la plataforma y usa los tokens de `theme-v2.css`. un test solo aporta su visor de ítems dentro de la pantalla de examen. mientras tanto se aplican las reglas ya medidas en este repo: acción principal visible a 1366x720 y formularios que quepan sin desplazamiento.

## orden de trabajo propuesto

1. acordar con los demás grupos el repositorio único, la nomenclatura anterior y el product backlog compartido. es decisión de equipo, no solo mía.
2. extraer la interfaz `ScoringStrategy` y un registro por `testId`; weil pasa a ser su primera implementación sin cambiar su comportamiento. las pruebas existentes deben seguir en verde.
3. migración `V4` con `assessment_results` genérico y responder sin el límite de 8 opciones.
4. quitar las constantes de weil de `AssessmentService` y `AdmissionService`; leerlas de la definición del test.
5. partir `app.js` en shell y módulos por test, empezando por mover el visor de láminas a `frontend/tests/weil-form-a/`.
6. recién entonces sumar el segundo test como incremento del backlog, para validar que el contrato alcanza.
