# pbi 2 · test de inteligencia no verbal de weil, forma a

documento de requerimientos en formato de product backlog item. describe únicamente lo que el código ya implementa; cada afirmación se puede comprobar en la clase o prueba que se indica. no incluye la clave de respuestas ni las tablas de baremo: el repositorio es público y esas reglas viven solo en el backend (`WeilScoringStrategy`).

| campo | valor |
| --- | --- |
| id de test | `weil-form-a`, versión 1 |
| estado | integrado y funcionando en la plataforma |
| módulo | evaluaciones (backend spring boot, postgresql, frontend web) |
| depende de | identidad, convocatorias, consentimiento de privacidad, auditoría |

## 1. descripción del test

- 60 ítems en 5 láminas de 12 ítems cada una.
- cada ítem es una figura incompleta con un hueco y 8 piezas candidatas. la respuesta es un número del 1 al 8 (la pieza elegida).
- duración oficial: 20 minutos (`WEIL_DURATION_SECONDS=1200`), ajustable por convocatoria y por aspirante.
- el backend es la única fuente de la clave, del puntaje y del baremo.

## 2. roles

| rol | qué hace con el test |
| --- | --- |
| `CLIENTE` (aspirante) | canjea su código, acepta el aviso de privacidad, rinde la prueba y recibe una constancia |
| `STAFF` | crea convocatorias, habilita aspirantes, consulta resultados, revisa, anula, reaplica y exporta |
| `ADMIN` | lo de `STAFF`, más gestión de personal y auditoría |

el aspirante nunca recibe puntaje, percentil, CI, clasificación ni claves (prueba `laVistaDelAspiranteNoTieneCamposDePuntaje`).

## 3. historias de usuario y criterios de aceptación

### h1 · habilitar aspirantes

como personal de admisión quiero habilitar aspirantes en una convocatoria para que puedan rendir el test.

- se habilita por correo, con hasta 500 aspirantes por operación.
- los correos inválidos o ya habilitados en la misma convocatoria se omiten y se informa el motivo.
- cada habilitación admite de 0 a 120 minutos adicionales, una nota (máximo 300 caracteres), un grupo de baremo y una edad.
- el grupo de baremo por defecto es `UN`; el grupo `7-16` exige una edad entre 7 y 16 años.
- el aspirante recibe una invitación por correo con un código de un solo uso de 12 caracteres.
- el código se guarda solo como huella; nunca en claro.

pruebas: `noSeDuplicaUnaHabilitacionNiSeAceptanCorreosInvalidos`, `elCodigoGuardadoEsUnHashYNoSePuedeAdivinarEnRafaga`, `rechazaUnGrupoDeBaremoInvalidoOUnaEdadFueraDeRango`.

### h2 · canjear el código y comenzar

como aspirante quiero canjear mi código para comenzar la prueba cuando mi convocatoria esté abierta.

- sin habilitación no se puede abrir un intento.
- sin aceptar el aviso de privacidad no se puede comenzar.
- la convocatoria solo abre dentro de su ventana; fuera de ella el sistema informa si aún no abre, ya cerró o fue cancelada.
- el código se canjea una sola vez y una habilitación permite un único intento.
- los intentos fallidos de canje se limitan por cuenta.
- el grupo de baremo y la edad los fija la habilitación; lo que envíe el navegador se ignora.

pruebas: `sinHabilitacionNoSePuedeAbrirUnIntento`, `sinAceptarElAvisoDePrivacidadNoSePuedeComenzar`, `laConvocatoriaSoloSeAbreDentroDeSuVentana`, `elPersonalHabilitaYElAspiranteCanjeaSuCodigoUnaSolaVez`, `unaHabilitacionPermiteUnSoloIntento`, `elGrupoDeBaremoLoFijaLaHabilitacionNoElNavegador`.

### h3 · responder la prueba

como aspirante quiero ver cada figura y responder arrastrando, tocando una pieza o con las teclas del 1 al 8.

- se muestra un ítem a la vez; el material del ítem actual y el siguiente se carga bajo demanda y solo se entrega con sesión autenticada.
- se puede avanzar a un ítem solo si ya está respondido o es el siguiente pendiente.
- cada respuesta se guarda de inmediato en el servidor y es idempotente: reenviarla no crea duplicados.
- se puede quitar la pieza colocada y volver a responder mientras haya tiempo.
- el progreso y el plazo se recuperan al recargar o reconectar; el plazo original no se reinicia ni se pausa.
- la prueba corre en modo bloqueado: salir de pantalla completa, copiar, pegar, clic derecho y similares se registran como incidencias.
- la interfaz es usable en portátil, tablet y móvil sin desplazar la pantalla entre la figura y las piezas.

pruebas: `elMaterialSoloSeEntregaAutenticadoYDeFormaProgresiva`, `unUsuarioNoPuedeEscribirEnElIntentoDeOtro`, `unIntentoAjenoSeComportaComoSiNoExistiera`.

### h4 · un solo dispositivo y sin pérdidas

como aspirante quiero que mi intento no se corrompa si abro otra ventana o falla la red.

- un intento activo por usuario y test, garantizado por un índice único en la base.
- el intento se reserva con un arrendamiento exclusivo de 2 minutos que el mismo cliente renueva; otra ventana o dispositivo recibe `409` mientras siga vigente.
- cada escritura lleva la versión esperada; una versión vieja no puede sobrescribir cambios recientes.

pruebas: `unSegundoClienteNoPuedeAbrirElIntentoMientrasElLeaseEsteActivo`, `unaVersionViejaNoPuedeSobrescribirElIntento`.

### h5 · entregar y calificar

como aspirante quiero entregar mi prueba y recibir una constancia.

- la entrega exige las 60 respuestas válidas y se guarda en una sola transacción.
- el backend calcula el puntaje directo y el desglose por lámina con la clave versionada.
- el resultado se guarda como escalas (`assessment_results`): total y una escala por lámina, con la versión del algoritmo (`weil-v1`).
- el aspirante recibe una constancia con número de intento; no recibe resultados.
- ningún correo lleva puntajes, percentil ni CI.

pruebas: `laEntregaGuardaTodasLasRespuestasEnUnaTransaccion`, `elPuntajeUsaLaClaveDelServidor`, `elAspiranteEntregaSinRecibirPuntajeYElPersonalLoVe`, `ningunCorreoLlevaPuntajesNiPercentilNiCi`.

### h6 · cierre automático por tiempo

como institución quiero que un intento vencido no quede abierto para siempre.

- un proceso programado cierra los intentos cuyo plazo venció (con 5 segundos de margen).
- se califica lo respondido; lo no respondido cuenta como incorrecto.
- el intento queda marcado como entregado automáticamente y su hora de entrega es el vencimiento.

prueba: `unIntentoVencidoSeCierraSoloYSeCalificaLoRespondido`.

### h7 · interpretar y revisar resultados

como personal de psicología quiero consultar resultados con su interpretación.

- el listado es paginado, filtrable y muestra puntaje directo, percentil, CI y clasificación; el detalle se carga bajo demanda.
- la clasificación usa las categorías: inteligencia muy superior, superior, buena inteligencia, inteligencia media, rango bajo y rango muy bajo.
- el personal puede marcar un intento como revisado.
- la exportación csv tiene 18 columnas, comienza con BOM y neutraliza celdas que empiezan como fórmula.
- consultar, exportar, revisar, anular y reaplicar quedan en el registro de auditoría.
- los endpoints de personal exigen rol `STAFF` o `ADMIN`.

pruebas: `elListadoDeResultadosExigeRolDePersonal`, `exportarResultadosExigeRolYQuedaAuditado`, `elArchivoEmpiezaConBomYTrae18Columnas`, `lasCeldasQueEmpiezanComoFormulaSeNeutralizan`, `lasComillasYLosSaltosDeLineaNoRompenElArchivo`.

### h8 · anular y reaplicar

como personal quiero anular un intento inválido y permitir que el aspirante rinda de nuevo.

- anular exige un motivo real; el aspirante recibe un aviso neutro sin el motivo interno.
- solo se puede reaplicar un intento ya anulado; la reaplicación crea una nueva habilitación en una convocatoria.
- el intento anulado se excluye de los promedios.

pruebas: `anularExigeMotivoAvisaNeutralmenteYPermiteReaplicar`, `anularYReaplicarRequierenPersonalYUnMotivoReal`.

### h9 · privacidad y retención

como titular de datos quiero que mi información sensible se trate con cuidado.

- el consentimiento está versionado y es obligatorio antes de comenzar.
- el titular puede recibir sus datos y queda auditado.
- la anonimización borra lo que identifica y conserva las estadísticas; solo aplica a aspirantes.
- un barrido por plazo anonimiza a quien lo superó y nunca al personal.

pruebas: las cinco de `RetentionIntegrationTest`.

## 4. reglas de calificación (estructura, sin valores)

1. el puntaje directo es el número de ítems cuya respuesta coincide con la clave, de 0 a 60; la suma de las 5 láminas.
2. el grupo de baremo se resuelve así: `7-16` con edad entre 7 y 16 usa la columna de esa edad; `AN` y `AD` usan su columna; cualquier otro caso usa `UN`.
3. el percentil es el de la primera fila de la tabla de baremo cuyo umbral es menor o igual al puntaje en la columna del grupo; si ninguna alcanza, es 1.
4. el CI se obtiene del percentil con una tabla de equivalencias y de él, la clasificación.
5. el resultado no se recalcula en silencio si cambia un baremo: se guarda con la versión del algoritmo.

los valores de la clave y de los baremos están en `backend/.../assessment/WeilScoringStrategy.java` y se sirven solo al personal en `/api/staff/attempts/norms`.

## 5. casos especiales

| caso | comportamiento |
| --- | --- |
| tiempo vencido sin entregar | cierre automático y calificación de lo respondido |
| segunda ventana o dispositivo | `409` mientras el arrendamiento siga vigente |
| escritura con versión vieja | rechazada, no sobrescribe |
| grupo de baremo inválido o edad fuera de rango | rechazado |
| código ya usado, inválido o en ráfaga | error genérico y límite por cuenta |
| convocatoria cancelada o cerrada | el aspirante recibe el estado y no puede comenzar |
| material de otro intento | se responde como si no existiera |
| intento anulado | el aspirante ve que fue anulado, sin el motivo |

## 6. api

aspirante (`CLIENTE`): `GET /api/applicant/availability`, `POST /api/applicant/claim`, `GET|POST /api/attempts`, `GET /api/attempts/{id}`, `POST /api/attempts/{id}/lease`, `PUT|DELETE /api/attempts/{id}/responses/{item}`, `POST /api/attempts/{id}/events`, `POST /api/attempts/{id}/submit`, `GET /api/attempts/{id}/material/{item}/{asset}`.

personal (`STAFF`, `ADMIN`): `/api/staff/sessions`, `/api/staff/enrollments`, `/api/staff/attempts` (listado, resumen, detalle, `export`, `review`, `invalidate`, `reapply`, `norms`).

## 7. fuera de alcance y límites conocidos

- el plazo es global por intento; no se pausa por desconexión.
- el límite de intentos de acceso es por instancia del backend.
- el modo demo no contiene material real ni califica.
- en la lámina 4 algunas piezas vienen cortadas por el borde del escaneo original.
- el frontend aún no es react, que es lo que pide el plan de desarrollo colaborativo.
- el correo real (smtp) no se ha probado, solo el modo `log`.
- la disposición móvil y tablet se verificó en emulación; el arrastre táctil no se ha probado en un dispositivo real.

## 8. trazabilidad

las pruebas citadas están en `backend/src/test/java/ni/edu/uam/campusfood/{assessment,admission,privacy}/` (62 en total en el backend) y en `frontend/*.test.js` y `frontend/tests/weil-form-a/` (45 en el frontend). `mvn test` y `npm test` las ejecutan.
