-- ================================================================
--  SAP-UAM · Test de Inteligencia de Weil
--  Script de base de datos — PostgreSQL 14+
--  Grupo: Kisnner Obando, Fernando Reyes, Jimmy Galo,
--         Samuel Chavarria, Adrián Vallecidos
--  SIS-IS1 · Universidad Americana · Managua, Nicaragua
-- ================================================================
--
--  INSTRUCCIONES DE INSTALACIÓN
--  1. Conectarse como superusuario:
--       psql -U postgres
--  2. Crear la base de datos (sólo la primera vez):
--       CREATE DATABASE sap_uam_weil
--           ENCODING    'UTF8'
--           LC_COLLATE  'es_NI.UTF-8'
--           LC_CTYPE    'es_NI.UTF-8'
--           TEMPLATE    template0;
--     (Si la locale es_NI.UTF-8 no está disponible usar 'es_ES.UTF-8' o 'C.UTF-8')
--  3. Conectarse a la nueva BD y ejecutar este script:
--       \c sap_uam_weil
--       \i backend_postgres.sql
--  O en una sola línea desde la shell:
--       psql -U postgres -d sap_uam_weil -f backend_postgres.sql
--
--  DIFERENCIAS RESPECTO AL SCRIPT SQL SERVER
--  · IDENTITY(1,1)    → SERIAL / GENERATED ALWAYS AS IDENTITY
--  · TINYINT          → SMALLINT  (PostgreSQL no tiene TINYINT)
--  · BIT              → BOOLEAN   (TRUE/FALSE en lugar de 1/0)
--  · DATETIME2        → TIMESTAMPTZ
--  · GETUTCDATE()     → NOW()
--  · NVARCHAR(MAX)    → TEXT
--  · NVARCHAR(n)      → VARCHAR(n)  (UTF-8 nativo, no requiere prefijo)
--  · N'...'           → '...'
--  · LEN()            → LENGTH() / CHAR_LENGTH()
--  · LIKE '[^0-9]'    → regex  ~ '^[0-9]+$'
--  · SET NOCOUNT ON   → (eliminado, no aplica)
--  · CREATE PROCEDURE → CREATE OR REPLACE PROCEDURE (PL/pgSQL)
--  · Resultado de SELECT en SP → CREATE OR REPLACE FUNCTION ... RETURNS TABLE
--  · GO               → (eliminado, no aplica)
--  · CAST(x AS FLOAT) → x::FLOAT
-- ================================================================

-- ================================================================
--  EXTENSIONES
-- ================================================================
-- pg_trgm acelera búsquedas ILIKE/SIMILAR en campos de texto
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ================================================================
--  TABLAS DE CONFIGURACIÓN Y AUTENTICACIÓN
-- ================================================================

CREATE TABLE roles (
    rol_id      SMALLINT     PRIMARY KEY,
    nombre      VARCHAR(30)  NOT NULL UNIQUE,
    descripcion VARCHAR(200)
);

INSERT INTO roles (rol_id, nombre, descripcion) VALUES
(1, 'admin',   'Administrador — Departamento de Psicología'),
(2, 'junta',   'Junta de Admisión'),
(3, 'docente', 'Docente supervisor SIS-IS1');

CREATE TABLE usuarios (
    usuario_id      SERIAL          PRIMARY KEY,
    username        VARCHAR(80)     NOT NULL UNIQUE,
    nombre_completo VARCHAR(160)    NOT NULL,
    email           VARCHAR(160),
    rol_id          SMALLINT        NOT NULL REFERENCES roles(rol_id),
    password_hash   VARCHAR(256)    NOT NULL,   -- bcrypt hash, NUNCA texto plano
    salt            VARCHAR(64)     NOT NULL,
    primer_acceso   BOOLEAN         NOT NULL DEFAULT TRUE,
    activo          BOOLEAN         NOT NULL DEFAULT TRUE,
    creado_en       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    ultimo_acceso   TIMESTAMPTZ,
    CONSTRAINT ck_email CHECK (email LIKE '%_@_%._%' OR email IS NULL)
);

CREATE TABLE solicitudes_reset (
    solicitud_id    SERIAL          PRIMARY KEY,
    usuario_id      INTEGER         NOT NULL REFERENCES usuarios(usuario_id),
    token           VARCHAR(128)    NOT NULL UNIQUE,
    expira_en       TIMESTAMPTZ     NOT NULL,
    usado           BOOLEAN         NOT NULL DEFAULT FALSE,
    creado_en       TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TABLE sesiones (
    sesion_id       VARCHAR(128)    PRIMARY KEY,
    usuario_id      INTEGER         NOT NULL REFERENCES usuarios(usuario_id),
    ip_origen       VARCHAR(45),
    user_agent      VARCHAR(500),
    creada_en       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expira_en       TIMESTAMPTZ     NOT NULL,
    activa          BOOLEAN         NOT NULL DEFAULT TRUE
);

-- ================================================================
--  INSTRUMENTO
-- ================================================================

CREATE TABLE periodos_admision (
    periodo_id      SERIAL          PRIMARY KEY,
    codigo          VARCHAR(20)     NOT NULL UNIQUE,
    descripcion     VARCHAR(120),
    fecha_inicio    DATE            NOT NULL,
    fecha_fin       DATE            NOT NULL,
    activo          BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_fechas CHECK (fecha_fin >= fecha_inicio)
);

CREATE TABLE versiones_formulario (
    version_id      SERIAL          PRIMARY KEY,
    periodo_id      INTEGER         NOT NULL REFERENCES periodos_admision(periodo_id),
    codigo_version  SMALLINT        NOT NULL CHECK (codigo_version BETWEEN 1 AND 3),
    tiempo_seg_fila SMALLINT        NOT NULL DEFAULT 40 CHECK (tiempo_seg_fila BETWEEN 10 AND 300),
    activa          BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_version_periodo UNIQUE (periodo_id, codigo_version)
);

CREATE TABLE items (
    item_id         SERIAL          PRIMARY KEY,
    version_id      INTEGER         NOT NULL REFERENCES versiones_formulario(version_id),
    numero_item     SMALLINT        NOT NULL CHECK (numero_item BETWEEN 1 AND 50),
    fila            SMALLINT        NOT NULL CHECK (fila BETWEEN 1 AND 4),
    tipo            VARCHAR(15)     NOT NULL CHECK (tipo IN ('matrix_3x3','serie_visual')),
    enunciado       VARCHAR(500)    NOT NULL,
    figura_patron   TEXT,                       -- JSON con la definición de la figura
    activo          BOOLEAN         NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_item_version UNIQUE (version_id, numero_item)
);

CREATE TABLE opciones_item (
    opcion_id       SERIAL          PRIMARY KEY,
    item_id         INTEGER         NOT NULL REFERENCES items(item_id) ON DELETE CASCADE,
    letra           CHAR(1)         NOT NULL CHECK (letra IN ('A','B','C','D')),
    figura_svg      TEXT,
    CONSTRAINT uq_opcion UNIQUE (item_id, letra)
);

CREATE TABLE clave_respuestas (
    clave_id        SERIAL          PRIMARY KEY,
    item_id         INTEGER         NOT NULL UNIQUE REFERENCES items(item_id),
    opcion_correcta CHAR(1)         NOT NULL CHECK (opcion_correcta IN ('A','B','C','D')),
    validada_por    INTEGER         REFERENCES usuarios(usuario_id),
    validada_en     TIMESTAMPTZ
);

CREATE TABLE baremos (
    baremo_id           SERIAL          PRIMARY KEY,
    version_id          INTEGER         NOT NULL REFERENCES versiones_formulario(version_id),
    grupo               VARCHAR(10)     NOT NULL CHECK (grupo IN ('UN','AD','7-16','AN')),
    puntuacion_directa  SMALLINT        NOT NULL CHECK (puntuacion_directa >= 0),
    percentil           SMALLINT        NOT NULL CHECK (percentil BETWEEN 1 AND 99),
    ci_equivalente      SMALLINT        NOT NULL CHECK (ci_equivalente BETWEEN 50 AND 160),
    clase               VARCHAR(30)     NOT NULL,
    CONSTRAINT uq_baremo UNIQUE (version_id, grupo, puntuacion_directa)
);

-- ================================================================
--  ASPIRANTES Y APLICACIONES
-- ================================================================

CREATE TABLE aspirantes (
    aspirante_id    SERIAL          PRIMARY KEY,
    -- CIF: solo dígitos, longitud 6–9  (regex sustituye el LIKE '[^0-9]' de T-SQL)
    cif             VARCHAR(9)      NOT NULL UNIQUE CHECK (cif ~ '^[0-9]{6,9}$'),
    nombre_completo VARCHAR(160)    NOT NULL,
    carrera         VARCHAR(120)    NOT NULL,
    grupo_baremo    VARCHAR(10)     NOT NULL DEFAULT 'UN' CHECK (grupo_baremo IN ('UN','AD','7-16','AN')),
    email           VARCHAR(160),
    creado_en       TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_aspirantes_cif ON aspirantes(cif);

CREATE TABLE aplicaciones_test (
    aplicacion_id   SERIAL          PRIMARY KEY,
    aspirante_id    INTEGER         NOT NULL REFERENCES aspirantes(aspirante_id),
    version_id      INTEGER         NOT NULL REFERENCES versiones_formulario(version_id),
    periodo_id      INTEGER         NOT NULL REFERENCES periodos_admision(periodo_id),
    estado          VARCHAR(15)     NOT NULL DEFAULT 'en_progreso'
                        CHECK (estado IN ('en_progreso','completado','expirado','anulado')),
    iniciado_en     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    completado_en   TIMESTAMPTZ,
    ip_origen       VARCHAR(45),
    CONSTRAINT uq_aplicacion UNIQUE (aspirante_id, periodo_id)  -- un acceso único por período
);

CREATE INDEX ix_aplicaciones_periodo ON aplicaciones_test(periodo_id, estado);

CREATE TABLE respuestas (
    respuesta_id    SERIAL          PRIMARY KEY,
    aplicacion_id   INTEGER         NOT NULL REFERENCES aplicaciones_test(aplicacion_id) ON DELETE CASCADE,
    item_id         INTEGER         NOT NULL REFERENCES items(item_id),
    opcion_elegida  CHAR(1)         CHECK (opcion_elegida IN ('A','B','C','D')),
    es_correcta     BOOLEAN,
    tiempo_ms       INTEGER,
    respondido_en   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_respuesta UNIQUE (aplicacion_id, item_id)
);

CREATE INDEX ix_respuestas_aplicacion ON respuestas(aplicacion_id);

CREATE TABLE resultados (
    resultado_id        SERIAL          PRIMARY KEY,
    aplicacion_id       INTEGER         NOT NULL UNIQUE REFERENCES aplicaciones_test(aplicacion_id),
    puntuacion_directa  SMALLINT        NOT NULL CHECK (puntuacion_directa >= 0),
    percentil           SMALLINT        NOT NULL CHECK (percentil BETWEEN 1 AND 99),
    ci                  SMALLINT        NOT NULL CHECK (ci BETWEEN 50 AND 160),
    clase               VARCHAR(30)     NOT NULL,
    clase_desc          VARCHAR(300),
    alpha_cronbach      NUMERIC(5,4)    CHECK (alpha_cronbach BETWEEN 0 AND 1),
    tiempo_total_seg    INTEGER         NOT NULL CHECK (tiempo_total_seg >= 0),
    calculado_en        TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_resultados_ci ON resultados(ci);

-- ================================================================
--  PSICOPATOLOGÍAS
-- ================================================================

CREATE TABLE psicopatologias (
    -- SMALLINT GENERATED ALWAYS AS IDENTITY sustituye a TINYINT IDENTITY(1,1)
    psicopatologia_id   SMALLINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo              VARCHAR(10)     NOT NULL UNIQUE,
    nombre              VARCHAR(120)    NOT NULL,
    descripcion         TEXT
);

INSERT INTO psicopatologias (codigo, nombre) VALUES
('Hs', 'Hipocondría'),
('D',  'Depresión'),
('Hy', 'Histeria de conversión'),
('Pd', 'Desviación psicopática'),
('Pa', 'Paranoia'),
('Pt', 'Psicastenia'),
('Sc', 'Esquizofrenia'),
('Ma', 'Hipomanía');

CREATE TABLE alertas_psicopatologia (
    alerta_id           SERIAL          PRIMARY KEY,
    resultado_id        INTEGER         NOT NULL REFERENCES resultados(resultado_id) ON DELETE CASCADE,
    psicopatologia_id   SMALLINT        NOT NULL REFERENCES psicopatologias(psicopatologia_id),
    nivel               VARCHAR(10)     NOT NULL CHECK (nivel IN ('leve','moderado','severo')),
    puntuacion_t        NUMERIC(5,2),
    generado_en         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_alerta UNIQUE (resultado_id, psicopatologia_id)
);

-- ================================================================
--  ENTREVISTAS
-- ================================================================

CREATE TABLE entrevistadores (
    entrevistador_id    SERIAL          PRIMARY KEY,
    usuario_id          INTEGER         NOT NULL UNIQUE REFERENCES usuarios(usuario_id),
    facultad            VARCHAR(120),
    activo              BOOLEAN         NOT NULL DEFAULT TRUE
);

CREATE TABLE disponibilidad_entrevistador (
    disponibilidad_id   SERIAL          PRIMARY KEY,
    entrevistador_id    INTEGER         NOT NULL REFERENCES entrevistadores(entrevistador_id),
    periodo_id          INTEGER         NOT NULL REFERENCES periodos_admision(periodo_id),
    dia_semana          SMALLINT        NOT NULL CHECK (dia_semana BETWEEN 1 AND 7),
    hora_inicio         TIME            NOT NULL,
    hora_fin            TIME            NOT NULL,
    incluye_almuerzo    BOOLEAN         NOT NULL DEFAULT FALSE,
    registrado_por      INTEGER         REFERENCES usuarios(usuario_id),
    registrado_en       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_horario CHECK (hora_fin > hora_inicio)
);

CREATE TABLE entrevistas (
    entrevista_id           SERIAL          PRIMARY KEY,
    aplicacion_id           INTEGER         NOT NULL UNIQUE REFERENCES aplicaciones_test(aplicacion_id),
    entrevistador_id        INTEGER         NOT NULL REFERENCES entrevistadores(entrevistador_id),
    fecha_hora              TIMESTAMPTZ     NOT NULL,
    duracion_min            SMALLINT        DEFAULT 30,
    estado                  VARCHAR(15)     NOT NULL DEFAULT 'programada'
                                CHECK (estado IN ('programada','realizada','cancelada','reprogramada')),
    -- Los 5 ejes de la entrevista (stakeholder 2026)
    notas_vida_academica    TEXT,
    notas_vida_familiar     TEXT,
    notas_vida_personal     TEXT,
    notas_proyecto_vida     TEXT,
    notas_aspiraciones      TEXT,
    puntuacion_rubrica      NUMERIC(5,2),
    resultado_entrevista    VARCHAR(15)     CHECK (resultado_entrevista IN ('aprobado','reprobado')),
    asignado_por            INTEGER         REFERENCES usuarios(usuario_id),
    asignado_en             TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
-- NOTA: reprobar la entrevista = reprobar todo el proceso (stakeholder 2026)

-- ================================================================
--  DICTÁMENES
-- ================================================================

CREATE TABLE rubricas (
    rubrica_id      SERIAL          PRIMARY KEY,
    nombre          VARCHAR(120)    NOT NULL,
    descripcion     TEXT,
    puntaje_max     NUMERIC(5,2)    NOT NULL,
    activa          BOOLEAN         NOT NULL DEFAULT TRUE,
    creado_en       TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TABLE dictamenes (
    dictamen_id         SERIAL          PRIMARY KEY,
    aplicacion_id       INTEGER         NOT NULL UNIQUE REFERENCES aplicaciones_test(aplicacion_id),
    resultado_dictamen  VARCHAR(15)     NOT NULL DEFAULT 'pendiente'
                            CHECK (resultado_dictamen IN ('admitido','no_admitido','pendiente','en_revision')),
    rubrica_id          INTEGER         REFERENCES rubricas(rubrica_id),
    puntuacion_total    NUMERIC(5,2),
    observaciones       TEXT,
    decidido_por        INTEGER         REFERENCES usuarios(usuario_id),
    decidido_en         TIMESTAMPTZ,
    revisado_comaen     BOOLEAN         NOT NULL DEFAULT FALSE,  -- ciencias de la salud requieren COMAEN
    creado_en           TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- ================================================================
--  AUDITORÍA
-- ================================================================

CREATE TABLE auditoria (
    auditoria_id    BIGINT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id      INTEGER         REFERENCES usuarios(usuario_id),
    aspirante_id    INTEGER         REFERENCES aspirantes(aspirante_id),
    tabla           VARCHAR(60)     NOT NULL,
    operacion       VARCHAR(10)     NOT NULL CHECK (operacion IN ('INSERT','UPDATE','DELETE','SELECT')),
    registro_id     VARCHAR(50),
    datos_antes     TEXT,           -- JSON
    datos_despues   TEXT,           -- JSON
    ip_origen       VARCHAR(45),
    ocurrido_en     TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_auditoria_tabla     ON auditoria(tabla, ocurrido_en DESC);
CREATE INDEX ix_auditoria_aspirante ON auditoria(aspirante_id, ocurrido_en DESC);

-- ================================================================
--  ÍNDICES DE DESEMPEÑO
-- ================================================================

CREATE INDEX ix_resultados_aplicacion ON resultados(aplicacion_id);
CREATE INDEX ix_sesiones_usuario      ON sesiones(usuario_id, activa);
CREATE INDEX ix_entrevistas_fecha     ON entrevistas(fecha_hora, estado);
CREATE INDEX ix_dictamenes_resultado  ON dictamenes(resultado_dictamen);

-- ================================================================
--  VISTAS DE REPORTE
-- ================================================================

-- Nota: 'at' es alias de aplicaciones_test (no es palabra reservada en PostgreSQL)
CREATE OR REPLACE VIEW vw_reporte_completo AS
SELECT
    a.cif,
    a.nombre_completo,
    a.carrera,
    a.grupo_baremo,
    pa.codigo           AS periodo,
    at.estado           AS estado_test,
    at.completado_en,
    r.puntuacion_directa,
    r.percentil,
    r.ci,
    r.clase,
    r.alpha_cronbach,
    r.tiempo_total_seg,
    e.estado            AS estado_entrevista,
    e.resultado_entrevista,
    d.resultado_dictamen,
    d.decidido_en,
    d.revisado_comaen
FROM aspirantes a
JOIN aplicaciones_test at  ON at.aspirante_id = a.aspirante_id
JOIN periodos_admision pa  ON pa.periodo_id   = at.periodo_id
LEFT JOIN resultados r     ON r.aplicacion_id = at.aplicacion_id
LEFT JOIN entrevistas e    ON e.aplicacion_id = at.aplicacion_id
LEFT JOIN dictamenes d     ON d.aplicacion_id = at.aplicacion_id;

CREATE OR REPLACE VIEW vw_estadisticas_periodo AS
SELECT
    pa.codigo                                                           AS periodo,
    COUNT(*)                                                            AS total_aplicaciones,
    -- ::FLOAT es el cast de PostgreSQL (equivalente a CAST(x AS FLOAT) de T-SQL)
    AVG(r.ci::FLOAT)                                                    AS ci_promedio,
    AVG(r.percentil::FLOAT)                                             AS percentil_promedio,
    SUM(CASE WHEN d.resultado_dictamen = 'admitido'    THEN 1 ELSE 0 END) AS admitidos,
    SUM(CASE WHEN d.resultado_dictamen = 'no_admitido' THEN 1 ELSE 0 END) AS no_admitidos,
    SUM(CASE WHEN d.resultado_dictamen = 'pendiente'   THEN 1 ELSE 0 END) AS pendientes,
    AVG(r.alpha_cronbach::FLOAT)                                        AS alpha_promedio
FROM aplicaciones_test at
JOIN periodos_admision pa ON pa.periodo_id   = at.periodo_id
LEFT JOIN resultados r    ON r.aplicacion_id = at.aplicacion_id
LEFT JOIN dictamenes d    ON d.aplicacion_id = at.aplicacion_id
GROUP BY pa.codigo, pa.periodo_id;

-- ================================================================
--  FUNCIONES Y PROCEDIMIENTOS  (PL/pgSQL)
-- ================================================================

-- FUNCIÓN: Autenticar usuario staff
-- En T-SQL era un PROCEDURE con SELECT; en PostgreSQL se modela como FUNCTION
-- que retorna una tabla para poder usar RETURN QUERY.
-- Uso desde Node.js: SELECT * FROM fn_auth_login($1, $2)
CREATE OR REPLACE FUNCTION fn_auth_login(
    p_username  VARCHAR(80),
    p_password  VARCHAR(256)    -- el cliente envía el hash; el servidor compara
)
RETURNS TABLE (
    usuario_id      INTEGER,
    username        VARCHAR(80),
    nombre_completo VARCHAR(160),
    rol_id          SMALLINT,
    rol             VARCHAR(30),
    primer_acceso   BOOLEAN,
    activo          BOOLEAN
)
LANGUAGE plpgsql
AS $$
BEGIN
    -- Actualizar último acceso (sólo si el usuario existe y está activo)
    UPDATE usuarios u
       SET ultimo_acceso = NOW()
     WHERE u.username = p_username
       AND u.activo   = TRUE;

    -- Retornar fila si credenciales son válidas
    -- En producción: bcrypt.compare() en Node.js antes de llamar esta función
    RETURN QUERY
    SELECT
        u.usuario_id,
        u.username,
        u.nombre_completo,
        u.rol_id,
        r.nombre    AS rol,
        u.primer_acceso,
        u.activo
    FROM usuarios u
    JOIN roles r ON r.rol_id = u.rol_id
    WHERE u.username      = p_username
      AND u.password_hash = p_password
      AND u.activo        = TRUE;
END;
$$;

-- PROCEDIMIENTO: Registrar resultado de test
-- Uso desde Node.js: CALL sp_registrar_resultado($1,$2,$3,$4,$5,$6,$7,$8)
CREATE OR REPLACE PROCEDURE sp_registrar_resultado(
    IN p_aplicacion_id      INTEGER,
    IN p_puntuacion_directa SMALLINT,
    IN p_percentil          SMALLINT,
    IN p_ci                 SMALLINT,
    IN p_clase              VARCHAR(30),
    IN p_clase_desc         VARCHAR(300),
    IN p_alpha_cronbach     NUMERIC(5,4),
    IN p_tiempo_total_seg   INTEGER
)
LANGUAGE plpgsql
AS $$
BEGIN
    -- Validar que la aplicación exista y esté en progreso
    IF NOT EXISTS (
        SELECT 1
          FROM aplicaciones_test
         WHERE aplicacion_id = p_aplicacion_id
           AND estado        = 'en_progreso'
    ) THEN
        RETURN;  -- sale sin error, la aplicación no aplica
    END IF;

    INSERT INTO resultados (
        aplicacion_id, puntuacion_directa, percentil, ci,
        clase, clase_desc, alpha_cronbach, tiempo_total_seg
    ) VALUES (
        p_aplicacion_id, p_puntuacion_directa, p_percentil, p_ci,
        p_clase, p_clase_desc, p_alpha_cronbach, p_tiempo_total_seg
    );

    UPDATE aplicaciones_test
       SET estado        = 'completado',
           completado_en = NOW()
     WHERE aplicacion_id = p_aplicacion_id;
END;
$$;

-- PROCEDIMIENTO: Registrar o actualizar dictamen de la junta
-- Uso desde Node.js: CALL sp_registrar_dictamen($1,$2,$3,$4,$5)
CREATE OR REPLACE PROCEDURE sp_registrar_dictamen(
    IN p_aplicacion_id      INTEGER,
    IN p_resultado          VARCHAR(15),
    IN p_observaciones      TEXT,
    IN p_usuario_id         INTEGER,
    IN p_revisado_comaen    BOOLEAN DEFAULT FALSE
)
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM dictamenes WHERE aplicacion_id = p_aplicacion_id) THEN
        UPDATE dictamenes
           SET resultado_dictamen = p_resultado,
               observaciones      = p_observaciones,
               decidido_por       = p_usuario_id,
               decidido_en        = NOW(),
               revisado_comaen    = p_revisado_comaen
         WHERE aplicacion_id      = p_aplicacion_id;
    ELSE
        INSERT INTO dictamenes (
            aplicacion_id, resultado_dictamen, observaciones,
            decidido_por, decidido_en, revisado_comaen
        ) VALUES (
            p_aplicacion_id, p_resultado, p_observaciones,
            p_usuario_id, NOW(), p_revisado_comaen
        );
    END IF;
END;
$$;

-- ================================================================
--  DATOS INICIALES
-- ================================================================

INSERT INTO periodos_admision (codigo, descripcion, fecha_inicio, fecha_fin, activo)
VALUES ('2026-I', 'Primer período de admisión 2026', '2026-05-01', '2026-07-31', TRUE);

-- Los usuarios staff se crean desde el panel de administración.
-- El hash de contraseña lo genera el backend (bcrypt, 12 rounds).
-- Ejemplo de insert (el hash real lo genera Node.js/bcrypt):
-- INSERT INTO usuarios (username, nombre_completo, email, rol_id, password_hash, salt, primer_acceso)
-- VALUES ('psicologia', 'Departamento de Psicología', 'psicologia@uam.edu.ni', 1,
--         '$2b$12$HASH_GENERADO_POR_BCRYPT', 'SALT', TRUE);

-- ================================================================
--  FIN DEL SCRIPT
-- ================================================================
