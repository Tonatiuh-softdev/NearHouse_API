-- ============================================================
-- Migración 13: cancelar suscripción y vigencia de los anuncios
-- Requiere la 06 (planes) y la 11 (suspensiones). Se corre UNA sola vez.
--
-- Cómo funcionan los planes desde ahora (el pago sigue siendo simulado):
--   * Un plan se RENUEVA SOLO al llegar su fecha de vencimiento (sin cobrar nada),
--     a menos que el usuario lo haya cancelado.
--   * Si lo cancela, sigue siendo vendedor hasta la fecha de vencimiento. Ese día:
--       - pasa a Comprador y se le quita el plan;
--       - sus anuncios dejan de verse (NO se borran). Si vuelve a contratar un plan,
--         reaparecen solos.
--   * Los anuncios de administradores y del Super Administrador no dependen de un plan.
--
-- Qué cambia:
--   * usuario.fh_cancelacion_suscripcion_usuario: cuándo canceló (vacío = se renueva).
--   * vw_publicacion_visible (y vw_colonia_anuncios): ahora también exige que el dueño
--     tenga un plan activo, o que sea administrador.
--   * fn_procesar_suscripciones(): renueva los planes vencidos que no se cancelaron y
--     termina los cancelados. La API la corre una vez al día. Regresa cuántos renovó
--     y cuántos terminó.
--
-- OJO: los vendedores que hoy no tienen plan (por ejemplo, cuentas de prueba viejas)
-- dejan de mostrar sus anuncios. Al terminar, el script dice cuántos son.
--
-- En DBeaver: pon el cursor en el bloque y pulsa Ctrl+Enter.
-- Si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output" sale: "Migración 13 aplicada".
-- ============================================================
DO $$
DECLARE
    v_habia_vista_colonias BOOLEAN := to_regclass('vw_colonia_anuncios') IS NOT NULL;
    v_vendedores_sin_plan INTEGER;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'usuario' AND column_name = 'fh_vencimiento_suscripcion_usuario') THEN
        RAISE EXCEPTION 'Primero aplica la migración 06.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'usuario' AND column_name = 'fh_suspension_hasta_usuario') THEN
        RAISE EXCEPTION 'Primero aplica la migración 11.';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'usuario' AND column_name = 'fh_cancelacion_suscripcion_usuario') THEN
        RAISE EXCEPTION 'La migración 13 ya se había aplicado.';
    END IF;
    -- --------------------------------------------------------
    -- 1. USUARIO: cuándo canceló su plan
    -- --------------------------------------------------------
    ALTER TABLE usuario
        ADD COLUMN fh_cancelacion_suscripcion_usuario TIMESTAMPTZ,
        -- Solo se puede cancelar un plan que existe
        ADD CONSTRAINT ck_usuario_cancelacion_suscripcion
            CHECK (fh_cancelacion_suscripcion_usuario IS NULL OR fke_id_suscripcion IS NOT NULL);
    -- --------------------------------------------------------
    -- 2. Anuncios visibles: el dueño debe tener un plan activo (o ser administrador)
    -- --------------------------------------------------------
    -- Plan activo = tiene plan y (no lo canceló, o todavía no llega su fecha).
    -- Un plan sin cancelar cuenta como activo aunque ya haya pasado su fecha, porque se
    -- renueva solo; así sus anuncios no desaparecen mientras la API lo renueva.
    DROP VIEW IF EXISTS vw_colonia_anuncios;
    DROP VIEW vw_publicacion_visible;
    CREATE VIEW vw_publicacion_visible AS
    SELECT p.*
    FROM publicacion p
    JOIN usuario u ON u.pke_id_usuario = p.fke_id_usuario
    JOIN rol r ON r.pke_id_rol = u.fke_id_rol
    WHERE p.t_estado_publicacion = 'PUBLICADA'
      AND p.b_estatus_publicacion
      AND (u.fh_suspension_hasta_usuario IS NULL OR u.fh_suspension_hasta_usuario <= now())  -- no suspendido
      AND u.fh_desactivacion_usuario IS NULL                                                 -- ni desactivado ni por eliminarse
      AND (r.t_nombre_rol IN ('Administrador', 'Super Administrador')                        -- plan activo
           OR (u.fke_id_suscripcion IS NOT NULL
               AND (u.fh_cancelacion_suscripcion_usuario IS NULL
                    OR u.fh_vencimiento_suscripcion_usuario > now())));
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
    -- 3. Renovar y terminar planes (la API la corre una vez al día)
    -- --------------------------------------------------------
    CREATE FUNCTION fn_procesar_suscripciones()
    RETURNS TABLE (renovadas INTEGER, terminadas INTEGER)
    LANGUAGE plpgsql AS $fn$
    DECLARE
        v_id_comprador INTEGER;
    BEGIN
        -- a) Sin cancelar y ya vencidos: se renuevan (simulado). Si la API estuvo apagada
        --    varios periodos, se suman los que hagan falta para quedar en el futuro.
        UPDATE usuario u
        SET fh_vencimiento_suscripcion_usuario = u.fh_vencimiento_suscripcion_usuario
                + make_interval(days => s.e_duracion_dias_suscripcion * (
                    floor(extract(epoch FROM (now() - u.fh_vencimiento_suscripcion_usuario))
                          / (s.e_duracion_dias_suscripcion * 86400))::INTEGER + 1)),
            fh_fecha_actualizacion_usuario = now()
        FROM suscripcion s
        WHERE s.pke_id_suscripcion = u.fke_id_suscripcion
          AND u.fh_cancelacion_suscripcion_usuario IS NULL
          AND u.fh_vencimiento_suscripcion_usuario <= now();
        GET DIAGNOSTICS renovadas = ROW_COUNT;
        -- b) Cancelados y ya vencidos: se quita el plan y los vendedores pasan a Comprador.
        --    Sus anuncios se quedan guardados; la vista ya no los muestra.
        SELECT pke_id_rol INTO v_id_comprador FROM rol WHERE t_nombre_rol = 'Comprador';
        UPDATE usuario u
        SET fke_id_rol = CASE
                WHEN u.fke_id_rol = (SELECT pke_id_rol FROM rol WHERE t_nombre_rol = 'Vendedor')
                THEN v_id_comprador ELSE u.fke_id_rol END,
            fke_id_suscripcion = NULL,
            fh_inicio_suscripcion_usuario = NULL,
            fh_vencimiento_suscripcion_usuario = NULL,
            fh_cancelacion_suscripcion_usuario = NULL,
            fh_fecha_actualizacion_usuario = now()
        WHERE u.fh_cancelacion_suscripcion_usuario IS NOT NULL
          AND u.fh_vencimiento_suscripcion_usuario <= now();
        GET DIAGNOSTICS terminadas = ROW_COUNT;
        RETURN NEXT;
    END
    $fn$;
    -- --------------------------------------------------------
    -- 4. Aviso: vendedores que hoy no tienen plan
    -- --------------------------------------------------------
    SELECT count(*) INTO v_vendedores_sin_plan
    FROM usuario u JOIN rol r ON r.pke_id_rol = u.fke_id_rol
    WHERE r.t_nombre_rol = 'Vendedor' AND u.fke_id_suscripcion IS NULL;
    IF v_vendedores_sin_plan > 0 THEN
        RAISE NOTICE 'Hay % vendedor(es) sin plan: sus anuncios ya no se ven hasta que contraten uno.', v_vendedores_sin_plan;
    END IF;
    RAISE NOTICE 'Migración 13 aplicada';
END $$;
