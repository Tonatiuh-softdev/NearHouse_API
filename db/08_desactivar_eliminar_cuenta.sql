-- ============================================================
-- Migración 08: desactivar y eliminar cuenta
-- Requiere la 02 (publicaciones) y la 05 (seguridad de la cuenta).
-- Se corre UNA sola vez.
--
-- Qué cambia:
--   * usuario.fh_desactivacion_usuario: el usuario pausó su cuenta (vacío = activa).
--   * usuario.fh_eliminacion_programada_usuario: el usuario pidió borrar su cuenta;
--     se borra de verdad en esa fecha (30 días después de pedirlo).
--   * vw_publicacion_visible: las publicaciones que el público puede ver
--     (publicadas, activas y de dueños activos). Inicio, Mapa, Filtros y el
--     detalle deben leer de aquí, no de la tabla publicacion directamente.
--   * vw_colonia_anuncios (de la 03, si existe): ahora tampoco cuenta los
--     anuncios de cuentas desactivadas, suspendidas o por eliminarse.
--   * fn_eliminar_usuario(id): borra al usuario y todos sus datos.
--   * fn_purgar_cuentas_eliminadas(): borra las cuentas cuya fecha ya llegó.
--     La API la llama una vez al día.
--
-- En DBeaver: pon el cursor en el bloque y pulsa Ctrl+Enter.
-- Si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output" sale: "Migración 08 aplicada".
-- ============================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'publicacion' AND column_name = 't_estado_publicacion') THEN
        RAISE EXCEPTION 'Primero aplica la migración 02.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'usuario' AND column_name = 'e_version_sesion_usuario') THEN
        RAISE EXCEPTION 'Primero aplica la migración 05.';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'usuario' AND column_name = 'fh_desactivacion_usuario') THEN
        RAISE EXCEPTION 'La migración 08 ya se había aplicado.';
    END IF;
    -- --------------------------------------------------------
    -- 1. USUARIO: cuenta desactivada y eliminación programada
    -- --------------------------------------------------------
    -- Una cuenta por eliminarse también queda desactivada (oculta) mientras llega la fecha.
    ALTER TABLE usuario
        ADD COLUMN fh_desactivacion_usuario TIMESTAMPTZ,
        ADD COLUMN fh_eliminacion_programada_usuario TIMESTAMPTZ,
        ADD CONSTRAINT ck_usuario_eliminacion_desactivada CHECK (
            fh_eliminacion_programada_usuario IS NULL OR fh_desactivacion_usuario IS NOT NULL
        );
    CREATE INDEX ix_usuario_eliminacion_programada ON usuario (fh_eliminacion_programada_usuario)
        WHERE fh_eliminacion_programada_usuario IS NOT NULL;
    -- --------------------------------------------------------
    -- 2. Publicaciones que el público puede ver
    -- --------------------------------------------------------
    CREATE VIEW vw_publicacion_visible AS
    SELECT p.*
    FROM publicacion p
    JOIN usuario u ON u.pke_id_usuario = p.fke_id_usuario
    WHERE p.t_estado_publicacion = 'PUBLICADA'
      AND p.b_estatus_publicacion
      AND u.b_estatus_usuario                       -- no suspendido por un administrador
      AND u.fh_desactivacion_usuario IS NULL;       -- ni desactivado ni por eliminarse
    -- La vista de colonias (migración 03) se rehace sobre la vista anterior
    IF to_regclass('vw_colonia_anuncios') IS NOT NULL THEN
        CREATE OR REPLACE VIEW vw_colonia_anuncios AS
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
    -- 3. Borrar un usuario con todos sus datos
    -- --------------------------------------------------------
    -- a) Sus publicaciones (con ellas se borran en cascada propiedad, inmueble,
    --    terreno, fotos, reportes y evidencias).
    -- b) En cualquier otra tabla donde aparezca como quien registró, actualizó
    --    o revisó algo, se deja vacío (así no se pierde lo que hicieron otros).
    -- c) El usuario.
    CREATE FUNCTION fn_eliminar_usuario(p_id INTEGER) RETURNS VOID
    LANGUAGE plpgsql AS $fn$
    DECLARE
        r RECORD;
    BEGIN
        DELETE FROM publicacion WHERE fke_id_usuario = p_id;
        FOR r IN
            SELECT c.conrelid::regclass AS tabla, a.attname AS columna
            FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
            WHERE c.contype = 'f'
              AND c.confrelid = 'usuario'::regclass
              AND NOT a.attnotnull
        LOOP
            EXECUTE format('UPDATE %s SET %I = NULL WHERE %I = $1', r.tabla, r.columna, r.columna)
            USING p_id;
        END LOOP;
        DELETE FROM usuario WHERE pke_id_usuario = p_id;
    END
    $fn$;
    -- --------------------------------------------------------
    -- 4. Borrar las cuentas cuya fecha de eliminación ya llegó
    -- --------------------------------------------------------
    -- Regresa cuántas borró. La API la llama al arrancar y cada 24 horas.
    CREATE FUNCTION fn_purgar_cuentas_eliminadas() RETURNS INTEGER
    LANGUAGE plpgsql AS $fn$
    DECLARE
        v_id INTEGER;
        v_total INTEGER := 0;
    BEGIN
        FOR v_id IN
            SELECT pke_id_usuario FROM usuario
            WHERE fh_eliminacion_programada_usuario <= now()
            FOR UPDATE SKIP LOCKED
        LOOP
            PERFORM fn_eliminar_usuario(v_id);
            v_total := v_total + 1;
        END LOOP;
        RETURN v_total;
    END
    $fn$;
    RAISE NOTICE 'Migración 08 aplicada';
END $$;
