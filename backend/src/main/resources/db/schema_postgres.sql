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
    active          BOOLEAN         NOT NULL DEFAULT true,
    session_version BIGINT          NOT NULL DEFAULT 0,
    mfa_secret      VARCHAR(512),
    mfa_enabled     BOOLEAN         NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT uk_users_email     UNIQUE (email),
    CONSTRAINT uk_users_moodle_id UNIQUE (moodle_id),
    CONSTRAINT ck_users_provider  CHECK (auth_provider IN ('manual', 'moodle')),
    -- Coherencia: los usuarios Moodle tienen moodle_id; los manuales tienen hash.
    CONSTRAINT ck_users_moodle    CHECK (auth_provider <> 'moodle' OR moodle_id IS NOT NULL),
    CONSTRAINT ck_users_manual    CHECK (auth_provider <> 'manual' OR password_hash IS NOT NULL)
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE users ADD COLUMN IF NOT EXISTS session_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_secret VARCHAR(512);
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_enabled BOOLEAN NOT NULL DEFAULT false;

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
    deadline_at      TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    submitted_at     TIMESTAMPTZ,
    direct_score     INTEGER,
    participant_age  INTEGER,
    norm_group       VARCHAR(8),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_attempt_status CHECK (status IN ('IN_PROGRESS', 'SUBMITTED'))
);

-- Bases creadas antes de guardar la edad y el grupo de baremo del aspirante:
ALTER TABLE assessment_attempts ADD COLUMN IF NOT EXISTS participant_age INTEGER;
ALTER TABLE assessment_attempts ADD COLUMN IF NOT EXISTS norm_group VARCHAR(8);
ALTER TABLE assessment_attempts ADD COLUMN IF NOT EXISTS deadline_at TIMESTAMPTZ;
UPDATE assessment_attempts SET deadline_at = started_at + INTERVAL '20 minutes' WHERE deadline_at IS NULL;
ALTER TABLE assessment_attempts ALTER COLUMN deadline_at SET NOT NULL;

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

CREATE TABLE IF NOT EXISTS assessment_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_id  BIGINT      NOT NULL REFERENCES assessment_attempts(id) ON DELETE CASCADE,
    type        VARCHAR(48) NOT NULL,
    detail      VARCHAR(240),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_event_attempt_time ON assessment_events (attempt_id, occurred_at);

CREATE TABLE IF NOT EXISTS audit_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id    BIGINT       NOT NULL,
    actor_name  VARCHAR(160) NOT NULL,
    action      VARCHAR(64)  NOT NULL,
    target_type VARCHAR(48)  NOT NULL,
    target_id   VARCHAR(96),
    detail      VARCHAR(500),
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_audit_time ON audit_events (occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_actor ON audit_events (actor_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS login_throttles (
    throttle_key      VARCHAR(200) PRIMARY KEY,
    attempt_count     INTEGER     NOT NULL DEFAULT 0,
    window_started_at TIMESTAMPTZ NOT NULL,
    blocked_until     TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0
);
