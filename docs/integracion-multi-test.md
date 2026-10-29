# integración de weil en un producto único de tests

## objetivo

la entrega final es una sola solución con la misma arquitectura, nomenclatura, estilos y diseño de interfaz. weil, la batería factorial de aptitudes, thurstone, allport y orientación vocacional no son proyectos separados: son incrementos funcionales del mismo sistema, con un solo repositorio, una sola arquitectura y un solo product backlog.

este documento aterriza `architecture.md` a ese objetivo: qué ya está listo, qué acopla hoy a weil y qué contrato debe cumplir cualquier test nuevo.

## el plan oficial manda

el plan de desarrollo colaborativo (scrum adaptado, 6 semanas, 5 grupos) fija el stack y la arquitectura: spring boot + react + postgresql, un solo repositorio, y una capa de backend con controller, dto, application/use case, domain, repository, infrastructure, evaluator y security. weil es la semana 2 y debe reutilizar lo que la semana 1 (batería factorial) deje como base. cuando este documento y el plan difieran, gana el plan.

| plan oficial | este repositorio hoy | decisión pendiente |
| --- | --- | --- |
| `TestEvaluator` como interfaz común, un evaluador por test | `ScoringStrategy`, `ScoringRegistry` y `WeilScoringStrategy` | acordar el nombre final con integración; el registro por `testId` ya funciona |
| paquete `com.universidad.tests` | `ni.edu.uam.campusfood` | lo fija el grupo integrador de la semana 1; no renombrar antes |
| capas por tipo (`controller/`, `dto/`, `application/{service,usecase,evaluator}`, `domain/{model,repository,exception}`, `infrastructure/{persistence,security,adapter}`) | paquetes por funcionalidad (`assessment`, `admission`, `auth`, `privacy`) | mover a capas al integrar con la base común |
| frontend react (`frontend/` con `package.json`) | javascript sin framework, con scripts globales | portar a react; el contrato de visor (`item`, `renderStage`, `renderResponse`...) se convierte en un componente por test |
| `sistema-evaluaciones/` con `backend/`, `frontend/`, `database/migrations/`, `docs/`, `tests/` | `backend/`, `frontend/`, `docs/`, `deploy/`, `ops/`; migraciones dentro de `backend/src/main/resources/db/migration` | acordar si las migraciones salen a `database/migrations` |
| áreas: sujetos, periodos, catálogo de test, evaluaciones, resultados, informes | usuarios, convocatorias, `TestCatalog`, intentos, `assessment_results`, exportación csv | mapear nombres al integrar |
| rama por funcionalidad, pull requests revisados por el integrador, `main` solo con versiones estables | commits atómicos directos | usar ramas y pull requests cuando el repositorio sea el común |

lo que sí coincide con el plan: monolito, un contrato por test, un evaluador por test que cambia la lógica pero no la arquitectura, y resultados guardados en postgresql.

## lo que ya está alineado

- monolito modular con identidad, auditoría, privacidad y notificaciones compartidos.
- `assessment_attempts` ya guarda `test_id` y `test_version`; la unicidad de intento activo es por usuario y test.
- el frontend ya consulta un catálogo (`SAP_UAM_TESTS`) y claves de almacenamiento por `testId`.
- el backend ya calcula la calificación y nunca envía claves al navegador.

## límites que siguen pendientes para el producto común

| dónde | acoplamiento | consecuencia para otro test |
| --- | --- | --- |
| respuesta persistida | `selected_option` sigue siendo entero | tests con texto, rankings o matrices requieren un contrato de respuesta genérico |
| convocatoria | cada sesión representa un solo `testId` | una batería ordenada necesita `test_assignments` o entidad equivalente |
| material | el adaptador actual conoce el formato de láminas de weil | cada nuevo formato necesita un proveedor de material y autorización de acceso |
| frontend | el visor de weil ya está separado, pero el shell sigue en javascript vanilla | debe portarse a componentes react del proyecto común |
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

## orden de trabajo restante

1. acordar con los demás grupos el repositorio único, la nomenclatura anterior y el product backlog compartido. es decisión de equipo, no solo mía.
2. conservar `ScoringStrategy`, `ScoringRegistry` y `assessment_results` como contrato de referencia; renombrarlos solo al adoptar la nomenclatura común.
3. diseñar una respuesta genérica y asignaciones de varios tests antes de incorporar el segundo módulo.
4. portar el shell y los visores a react sin cambiar el contrato de interacción ya probado. `frontend/test-viewers.js` define el visor actual y weil lo implementa en `frontend/tests/weil-form-a/viewer.js`.
5. mover paquetes y migraciones a la estructura aprobada por el repositorio común.
6. recién entonces sumar el segundo test como incremento del backlog, para validar que el contrato alcanza.
