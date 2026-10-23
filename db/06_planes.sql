-- ============================================================
-- Migración 06: planes (suscripciones)
-- Se corre UNA sola vez. No depende de las migraciones 02 a 05.
--
-- Qué cambia:
--   * suscripcion (catálogo de planes): precio, duración, límite de anuncios,
--     nivel de visibilidad, días de prueba gratis, si es el "Más popular"
--     y si está activo. Los planes se agregan con 07_planes.sql.
--   * usuario: fecha de inicio y de vencimiento de su plan actual, y si ya
--     usó su mes gratis. El plan sigue siendo fke_id_suscripcion (ya existía).
--
-- En DBeaver: pon el cursor en el bloque y pulsa Ctrl+Enter.
-- Si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output" sale: "Migración 06 aplicada".
-- ============================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'suscripcion' AND column_name = 'e_precio_suscripcion') THEN
        RAISE EXCEPTION 'La migración 06 ya se había aplicado.';
    END IF;
    -- --------------------------------------------------------
    -- 1. SUSCRIPCION: datos de cada plan
    -- --------------------------------------------------------
    -- Los valores por defecto solo sirven para llenar planes que ya existieran;
    -- después se quitan, para que todo plan nuevo diga su precio y duración.
    ALTER TABLE suscripcion
        ADD COLUMN e_precio_suscripcion NUMERIC(12, 2) NOT NULL DEFAULT 0,
        ADD COLUMN e_duracion_dias_suscripcion INTEGER NOT NULL DEFAULT 30,
        ADD COLUMN e_limite_anuncios_suscripcion INTEGER,          -- vacío = publicaciones ilimitadas
        ADD COLUMN e_nivel_visibilidad_suscripcion INTEGER NOT NULL DEFAULT 1,  -- 1 estándar, 2 destacada, 3 máxima
        ADD COLUMN e_dias_prueba_suscripcion INTEGER NOT NULL DEFAULT 0,        -- 0 = sin prueba gratis
        ADD COLUMN b_destacado_suscripcion BOOLEAN NOT NULL DEFAULT FALSE,      -- etiqueta "Más popular"
        ADD COLUMN b_estatus_suscripcion BOOLEAN NOT NULL DEFAULT TRUE,         -- FALSE = ya no se puede contratar
        ADD CONSTRAINT ck_suscripcion_precio CHECK (e_precio_suscripcion >= 0),
        ADD CONSTRAINT ck_suscripcion_duracion CHECK (e_duracion_dias_suscripcion > 0),
        ADD CONSTRAINT ck_suscripcion_limite
            CHECK (e_limite_anuncios_suscripcion IS NULL OR e_limite_anuncios_suscripcion >= 1),
        ADD CONSTRAINT ck_suscripcion_visibilidad CHECK (e_nivel_visibilidad_suscripcion BETWEEN 1 AND 3),
        ADD CONSTRAINT ck_suscripcion_prueba CHECK (e_dias_prueba_suscripcion >= 0),
        ADD CONSTRAINT ck_suscripcion_nombre CHECK (btrim(t_nombre_suscripcion) <> '');
    ALTER TABLE suscripcion
        ALTER COLUMN e_precio_suscripcion DROP DEFAULT,
        ALTER COLUMN e_duracion_dias_suscripcion DROP DEFAULT;
    -- --------------------------------------------------------
    -- 2. USUARIO: vigencia de su plan actual y si ya usó su mes gratis
    -- --------------------------------------------------------
    ALTER TABLE usuario
        ADD COLUMN fh_inicio_suscripcion_usuario TIMESTAMPTZ,
        ADD COLUMN fh_vencimiento_suscripcion_usuario TIMESTAMPTZ,
        ADD COLUMN b_prueba_gratis_usada_usuario BOOLEAN NOT NULL DEFAULT FALSE;  -- el mes gratis es una sola vez
    -- Si algún usuario ya tenía plan asignado, su vigencia empieza hoy
    UPDATE usuario u
    SET fh_inicio_suscripcion_usuario = now(),
        fh_vencimiento_suscripcion_usuario = now() + make_interval(days => s.e_duracion_dias_suscripcion)
    FROM suscripcion s
    WHERE s.pke_id_suscripcion = u.fke_id_suscripcion;
    -- O tiene plan con sus dos fechas, o no tiene nada
    ALTER TABLE usuario
        ADD CONSTRAINT ck_usuario_suscripcion_completa CHECK (
            (fke_id_suscripcion IS NULL
                AND fh_inicio_suscripcion_usuario IS NULL
                AND fh_vencimiento_suscripcion_usuario IS NULL)
            OR (fke_id_suscripcion IS NOT NULL
                AND fh_inicio_suscripcion_usuario IS NOT NULL
                AND fh_vencimiento_suscripcion_usuario > fh_inicio_suscripcion_usuario)
        );
    RAISE NOTICE 'Migración 06 aplicada';
END $$;
