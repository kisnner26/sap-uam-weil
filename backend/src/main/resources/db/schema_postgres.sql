-- ============================================================================
-- SAP-UAM Weill — tabla de usuarios
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
