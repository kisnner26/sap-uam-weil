-- ============================================================================
-- SAP-UAM — identidad compartida de la plataforma de evaluaciones
-- Ejecutar en la BD Postgres antes de arrancar con el perfil prod
-- (que usa ddl-auto: validate y no crea tablas).
-- ============================================================================

CREATE TABLE IF NOT EXISTS users (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name       VARCHAR(160)    NOT NULL,
    email           VARCHAR(160),
    password_hash   VARCHAR(256),                      -- bcrypt; NULL para usuarios Moodle
    cedula          VARCHAR(32),
    moodle_id       INTEGER,                           -- userid de Moodle; NULL para manuales
    picture_url     VARCHAR(512),
    career          VARCHAR(160),                      -- carrera si Moodle la expone; NULL si no
    auth_provider   VARCHAR(16)     NOT NULL,          -- 'manual' | 'moodle'
    role            VARCHAR(16)     NOT NULL DEFAULT 'CLIENTE',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT uk_users_email     UNIQUE (email),
    CONSTRAINT uk_users_moodle_id UNIQUE (moodle_id),
    CONSTRAINT ck_users_provider  CHECK (auth_provider IN ('manual', 'moodle')),
    -- Coherencia: los usuarios Moodle tienen moodle_id; los manuales tienen hash.
    CONSTRAINT ck_users_moodle    CHECK (auth_provider <> 'moodle' OR moodle_id IS NOT NULL),
    CONSTRAINT ck_users_manual    CHECK (auth_provider <> 'manual' OR password_hash IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS ix_users_moodle_id ON users (moodle_id);
CREATE INDEX IF NOT EXISTS ix_users_email     ON users (lower(email));

CREATE TABLE IF NOT EXISTS assessment_attempts (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES users(id),
    test_id          VARCHAR(80)  NOT NULL,
    test_version     INTEGER      NOT NULL,
    status           VARCHAR(24)  NOT NULL,
    lease_token      VARCHAR(64)  NOT NULL,
    lease_expires_at TIMESTAMPTZ  NOT NULL,
    started_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    submitted_at     TIMESTAMPTZ,
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_attempt_status CHECK (status IN ('IN_PROGRESS', 'SUBMITTED'))
);

CREATE INDEX IF NOT EXISTS ix_attempt_user_test
    ON assessment_attempts (user_id, test_id, status, started_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uk_attempt_active
    ON assessment_attempts (user_id, test_id)
    WHERE status = 'IN_PROGRESS';

CREATE TABLE IF NOT EXISTS assessment_responses (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_id      BIGINT  NOT NULL REFERENCES assessment_attempts(id) ON DELETE CASCADE,
    item_index      INTEGER NOT NULL,
    selected_option INTEGER NOT NULL,
    CONSTRAINT uk_response_attempt_item UNIQUE (attempt_id, item_index),
    CONSTRAINT ck_response_item CHECK (item_index >= 0),
    CONSTRAINT ck_response_option CHECK (selected_option > 0)
);
