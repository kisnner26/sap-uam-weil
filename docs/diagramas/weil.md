# diagramas del test de weil

diagramas en mermaid, que github renderiza directamente. salen del código actual; si cambia el código, se actualizan aquí.

## 1. contexto y capas

```mermaid
flowchart TB
    subgraph FE["frontend (navegador)"]
        SHELL["shell de la plataforma<br/>acceso, inicio, perfil, ajustes, entrega"]
        VIEWERS["test-viewers.js<br/>registro de visores"]
        WEILV["tests/weil-form-a/viewer.js<br/>figura con hueco y 8 piezas"]
        SHELL --> VIEWERS --> WEILV
    end

    subgraph BE["backend (spring boot)"]
        CTRL["controladores REST<br/>AssessmentController, AdmissionStaffController, StaffAssessmentController"]
        SVC["servicios<br/>AssessmentService, AdmissionService"]
        CAT["TestCatalog<br/>TestDefinition por test"]
        REG["ScoringRegistry"]
        WEILS["WeilScoringStrategy"]
        REPO["repositorios JPA"]
        SEC["seguridad<br/>JWT, MFA, roles"]
        CTRL --> SEC
        CTRL --> SVC
        SVC --> CAT
        SVC --> REG --> WEILS
        SVC --> REPO
    end

    DB[("PostgreSQL<br/>migraciones Flyway V1 a V4")]
    FE -- "HTTP / JSON" --> CTRL
    REPO --> DB
```

## 2. un intento de principio a fin

```mermaid
sequenceDiagram
    actor A as aspirante
    participant F as frontend
    participant API as backend
    participant DB as postgresql

    A->>F: ingresa su código de acceso
    F->>API: POST /api/applicant/claim
    API->>DB: busca la huella del código y lo asocia al usuario
    API-->>F: disponibilidad (OPEN)
    A->>F: acepta el aviso de privacidad y pulsa comenzar
    F->>API: POST /api/attempts (testId, versión)
    API->>DB: bloquea la fila del usuario, crea el intento y el arrendamiento
    API-->>F: intento (plazo, lease, versión)

    loop cada ítem
        A->>F: arrastra o toca la pieza N
        F->>API: PUT /api/attempts/{id}/responses/{item}
        API->>DB: upsert idempotente, versión +1
        API-->>F: intento actualizado
        F->>API: GET /material/{item+1}/{asset} (precarga)
    end

    A->>F: finalizar y entregar
    F->>API: POST /api/attempts/{id}/submit
    API->>API: ScoringRegistry.forTest(testId).score()
    API->>DB: estado SUBMITTED, puntaje y resultado por escalas
    API-->>F: constancia (sin puntajes)
```

## 3. estados de una convocatoria y de una habilitación

el estado de la convocatoria se calcula con la hora actual y `cancelled_at`; no se guarda.

```mermaid
stateDiagram-v2
    direction LR
    [*] --> UPCOMING: se crea
    UPCOMING --> OPEN: llega opens_at
    OPEN --> CLOSED: pasa closes_at
    UPCOMING --> CANCELLED: personal cancela
    OPEN --> CANCELLED: personal cancela
    CLOSED --> [*]
    CANCELLED --> [*]
```

```mermaid
stateDiagram-v2
    direction LR
    [*] --> ENABLED: personal habilita (código por correo)
    ENABLED --> ENABLED: aspirante canjea el código (user_id)
    ENABLED --> REVOKED: personal revoca
    ENABLED --> [*]: genera un único intento
```

## 4. estados de un intento

```mermaid
stateDiagram-v2
    [*] --> IN_PROGRESS: POST /api/attempts
    IN_PROGRESS --> IN_PROGRESS: respuesta, renovación del lease
    IN_PROGRESS --> SUBMITTED: entrega del aspirante
    IN_PROGRESS --> SUBMITTED: cierre automático al vencer el plazo
    SUBMITTED --> SUBMITTED: personal marca como revisado
    SUBMITTED --> INVALIDADO: personal anula con motivo
    INVALIDADO --> [*]: reaplicación crea una nueva habilitación
```

`INVALIDADO` no es un valor de `status`: es un intento `SUBMITTED` con `invalidated_at`. la restricción `ck_attempt_invalid` solo permite anular intentos entregados.

## 5. modelo de datos

```mermaid
erDiagram
    users ||--o{ exam_enrollments : "canjea"
    exam_sessions ||--o{ exam_enrollments : "habilita"
    exam_enrollments ||--o| assessment_attempts : "un intento"
    users ||--o{ assessment_attempts : "rinde"
    assessment_attempts ||--o{ assessment_responses : "60 respuestas"
    assessment_attempts ||--o{ assessment_events : "incidencias"
    assessment_attempts ||--o| assessment_results : "escalas"
    users ||--o{ consent_records : "acepta"

    exam_sessions {
        bigint id PK
        varchar test_id
        timestamptz opens_at
        timestamptz closes_at
        int time_limit_minutes
        timestamptz cancelled_at
    }
    exam_enrollments {
        bigint id PK
        varchar email
        int extra_minutes
        varchar norm_group
        int participant_age
        varchar access_code_hash UK
        varchar status
    }
    assessment_attempts {
        bigint id PK
        varchar test_id
        int test_version
        varchar status
        timestamptz deadline_at
        int direct_score
        timestamptz invalidated_at
        bigint version
    }
    assessment_responses {
        bigint id PK
        int item_index
        int selected_option
    }
    assessment_events {
        bigint id PK
        varchar type
        timestamptz occurred_at
    }
    assessment_results {
        bigint id PK
        varchar scoring_version
        text scales
    }
```

restricciones relevantes: un intento activo por usuario y test (`uk_attempt_active`), un intento por habilitación (`uk_attempt_enrollment`), una respuesta por ítem (`uk_response_attempt_item`) y un resultado por intento (`uk_result_attempt`).

## 6. contrato para agregar un test

```mermaid
classDiagram
    class TestDefinition {
        +String id
        +int version
        +int itemCount
        +int optionCount
        +accepts(item, option) boolean
    }
    class TestCatalog {
        +get(testId) TestDefinition
        +defaultTest() TestDefinition
    }
    class ScoringStrategy {
        <<interface>>
        +testId() String
        +scoringVersion() String
        +score(answers) Score
        +scales(score) List~Scale~
        +interpret(score, age, group) Interpretation
    }
    class ScoringRegistry {
        +forTest(testId) ScoringStrategy
    }
    class WeilScoringStrategy
    class PublishedTests {
        +weilFormA() TestDefinition
    }
    class AssessmentService
    class AdmissionService

    PublishedTests ..> TestDefinition : registra
    TestCatalog o-- TestDefinition
    ScoringRegistry o-- ScoringStrategy
    ScoringStrategy <|.. WeilScoringStrategy
    AssessmentService --> TestCatalog
    AssessmentService --> ScoringRegistry
    AdmissionService --> TestCatalog
```

un test nuevo agrega una `TestDefinition` en `PublishedTests`, una implementación de `ScoringStrategy` y, en el frontend, un visor registrado en `SAP_UAM_TEST_VIEWERS`. el plan de desarrollo colaborativo llama `TestEvaluator` a la interfaz; el renombre está pendiente de la arquitectura base.
