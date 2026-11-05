-- ============================================================
-- Migración 11: suspensión de cuentas por un administrador
-- Requiere la 05 (seguridad de la cuenta) y la 08 (desactivar y eliminar cuenta).
-- Se corre UNA sola vez.
--
-- Qué cambia en usuario:
--   * fh_suspension_hasta_usuario: hasta cuándo está suspendida la cuenta.
--       vacío      = nunca se ha suspendido
--       una fecha  = suspendida hasta esa fecha (al pasar, vuelve a estar activa sola)
--       'infinity' = suspendida hasta que un administrador la reactive
--   * t_motivo_suspension_usuario: por qué se suspendió (se le muestra al usuario).
--   * fh_suspension_usuario y fke_id_usuario_suspension: cuándo y qué administrador.
--     Al terminar o levantarse una suspensión, estos datos se quedan como
--     registro de la última; lo que manda es la fecha "hasta".
--   * Se BORRA b_estatus_usuario. Las cuentas que tenían FALSE quedan
--     suspendidas hasta que un administrador las reactive.
--
-- Qué se agrega:
--   * vw_publicacion_visible y vw_colonia_anuncios se rehacen con la regla nueva.
--   * vw_usuario_estado: el estatus de cada cuenta ya calculado
--     (ACTIVO, SUSPENDIDO o DESACTIVADO). La API y el panel leen de aquí.
--   * fn_suspender_usuario(id, id_admin, dias, motivo): dias vacío = indefinida.
--     También cierra todas sus sesiones (sube e_version_sesion_usuario).
--   * fn_levantar_suspension(id): quita la suspensión en ese momento.
--
-- IMPORTANTE: una API que todavía lea b_estatus_usuario deja de funcionar en
-- cuanto se aplique este script. En Railway, aplícalo justo cuando subas la
-- API que ya usa vw_usuario_estado.
--
-- En DBeaver: pon el cursor en el bloque y pulsa Ctrl+Enter.
-- Si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output" sale: "Migración 11 aplicada".
-- ============================================================
DO $$
DECLARE
    v_habia_vista_colonias BOOLEAN := to_regclass('vw_colonia_anuncios') IS NOT NULL;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'usuario' AND column_name = 'e_version_sesion_usuario') THEN
        RAISE EXCEPTION 'Primero aplica la migración 05.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'usuario' AND column_name = 'fh_desactivacion_usuario') THEN
        RAISE EXCEPTION 'Primero aplica la migración 08.';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'usuario' AND column_name = 'fh_suspension_hasta_usuario') THEN
        RAISE EXCEPTION 'La migración 11 ya se había aplicado.';
    END IF;
    -- --------------------------------------------------------
    -- 1. USUARIO: datos de la suspensión
    -- --------------------------------------------------------
    ALTER TABLE usuario
        ADD COLUMN fh_suspension_hasta_usuario TIMESTAMPTZ,
        ADD COLUMN t_motivo_suspension_usuario TEXT,
        ADD COLUMN fh_suspension_usuario TIMESTAMPTZ,
        ADD COLUMN fke_id_usuario_suspension INTEGER,
        ADD CONSTRAINT fk_usuario_suspension
            FOREIGN KEY (fke_id_usuario_suspension) REFERENCES usuario (pke_id_usuario),
        -- Nunca suspendida: todo vacío. Suspendida alguna vez: fecha, inicio y motivo (1 a 300).
        ADD CONSTRAINT ck_usuario_suspension CHECK (
            (fh_suspension_hasta_usuario IS NULL
                AND fh_suspension_usuario IS NULL
                AND t_motivo_suspension_usuario IS NULL
                AND fke_id_usuario_suspension IS NULL)
            OR (fh_suspension_hasta_usuario IS NOT NULL
                AND fh_suspension_usuario IS NOT NULL
                AND fh_suspension_hasta_usuario >= fh_suspension_usuario
                AND char_length(btrim(coalesce(t_motivo_suspension_usuario, ''))) BETWEEN 1 AND 300)
        );
    CREATE INDEX ix_usuario_suspension ON usuario (fh_suspension_hasta_usuario)
        WHERE fh_suspension_hasta_usuario IS NOT NULL;
    -- --------------------------------------------------------
    -- 2. Las cuentas que ya estaban suspendidas (b_estatus_usuario = FALSE)
    -- --------------------------------------------------------
    UPDATE usuario
    SET fh_suspension_hasta_usuario = 'infinity',
        fh_suspension_usuario = now(),
        t_motivo_suspension_usuario = 'Suspensión anterior al nuevo sistema de suspensiones.'
    WHERE NOT b_estatus_usuario;
    -- --------------------------------------------------------
    -- 3. Se quita b_estatus_usuario y se rehacen las vistas que lo usaban
    -- --------------------------------------------------------
    DROP VIEW IF EXISTS vw_colonia_anuncios;
    DROP VIEW vw_publicacion_visible;
    ALTER TABLE usuario DROP COLUMN b_estatus_usuario;
    CREATE VIEW vw_publicacion_visible AS
    SELECT p.*
    FROM publicacion p
    JOIN usuario u ON u.pke_id_usuario = p.fke_id_usuario
    WHERE p.t_estado_publicacion = 'PUBLICADA'
      AND p.b_estatus_publicacion
      AND (u.fh_suspension_hasta_usuario IS NULL OR u.fh_suspension_hasta_usuario <= now())  -- no suspendido
      AND u.fh_desactivacion_usuario IS NULL;                                                -- ni desactivado ni por eliminarse
    IF v_habia_vista_colonias THEN
        CREATE VIEW vw_colonia_anuncios AS
        SELECT c.pke_id_colonia      AS id_colonia,
               c.t_nombre_colonia    AS nombre_colonia,
               m.t_nombre_modalidad  AS modalidad,
               pr.t_tipo_propiedad   AS tipo,
               COUNT(*)              AS total_anuncios
        FROM colonia c
        JOIN propiedad pr ON pr.fke_id_colonia = c.pke_id_colonia
        JOIN vw_publicacion_visible p ON p.pke_id_publicacion = pr.fke_id_publicacion
        JOIN modalidad m  ON m.pke_id_modalidad = p.fke_id_modalidad
        WHERE c.b_estatus_colonia
        GROUP BY c.pke_id_colonia, c.t_nombre_colonia, m.t_nombre_modalidad, pr.t_tipo_propiedad;
    END IF;
    -- --------------------------------------------------------
    -- 4. Estatus de cada cuenta, ya calculado
    -- --------------------------------------------------------
    -- Si está suspendida y además desactivada, gana SUSPENDIDO.
    -- suspendida_hasta va vacía si no está suspendida o si es indefinida.
    CREATE VIEW vw_usuario_estado AS
    SELECT u.pke_id_usuario AS id_usuario,
           CASE
               WHEN u.fh_suspension_hasta_usuario > now() THEN 'SUSPENDIDO'
               WHEN u.fh_desactivacion_usuario IS NOT NULL THEN 'DESACTIVADO'
               ELSE 'ACTIVO'
           END AS estado,
           CASE WHEN u.fh_suspension_hasta_usuario > now()
                 AND u.fh_suspension_hasta_usuario <> 'infinity'
                THEN u.fh_suspension_hasta_usuario END AS suspendida_hasta,
           coalesce(u.fh_suspension_hasta_usuario = 'infinity', FALSE) AS suspension_indefinida,
           CASE WHEN u.fh_suspension_hasta_usuario > now()
                THEN u.t_motivo_suspension_usuario END AS motivo_suspension,
           CASE WHEN u.fh_suspension_hasta_usuario > now()
                THEN u.fh_suspension_usuario END AS suspendida_desde,
           CASE WHEN u.fh_suspension_hasta_usuario > now()
                THEN u.fke_id_usuario_suspension END AS id_admin_suspension
    FROM usuario u;
    -- --------------------------------------------------------
    -- 5. Suspender y levantar la suspensión
    -- --------------------------------------------------------
    -- p_dias vacío = indefinida. Cierra todas las sesiones del usuario.
    CREATE FUNCTION fn_suspender_usuario(p_id INTEGER, p_id_admin INTEGER, p_dias INTEGER, p_motivo TEXT)
    RETURNS VOID LANGUAGE plpgsql AS $fn$
    BEGIN
        IF p_dias IS NOT NULL AND (p_dias < 1 OR p_dias > 365) THEN
            RAISE EXCEPTION 'Los días de suspensión van de 1 a 365 (o vacío para indefinida).';
        END IF;
        UPDATE usuario
        SET fh_suspension_hasta_usuario = CASE WHEN p_dias IS NULL THEN 'infinity'::timestamptz
                                               ELSE now() + make_interval(days => p_dias) END,
            fh_suspension_usuario = now(),
            t_motivo_suspension_usuario = btrim(p_motivo),
            fke_id_usuario_suspension = p_id_admin,
            e_version_sesion_usuario = e_version_sesion_usuario + 1
        WHERE pke_id_usuario = p_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'No existe el usuario %.', p_id;
        END IF;
    END
    $fn$;
    -- Termina la suspensión en este momento (si no estaba suspendido, no hace nada).
    CREATE FUNCTION fn_levantar_suspension(p_id INTEGER)
    RETURNS VOID LANGUAGE plpgsql AS $fn$
    BEGIN
        UPDATE usuario
        SET fh_suspension_hasta_usuario = now()
        WHERE pke_id_usuario = p_id
          AND fh_suspension_hasta_usuario > now();
    END
    $fn$;
    RAISE NOTICE 'Migración 11 aplicada';
END $$;
