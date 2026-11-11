-- ============================================================
-- Migración 12: notificaciones
-- Va después de la 11. Se corre UNA sola vez.
--
-- Qué agrega:
--   * notificacion: los avisos de cada usuario (lo que ve en la pantalla
--     Notificaciones). Se guarda el TIPO y sus DATOS, no el texto: la app
--     arma el texto en español o inglés con sus strings.xml.
--       - jb_datos_notificacion: los datos para el texto, en JSON
--         (por ejemplo {"titulo": "Casa en Santiago", "motivo": "Fotos borrosas"}).
--       - fke_id_publicacion y fke_id_reporte: a qué se refiere (para abrir la
--         pantalla correcta y mostrar la foto). Si se borra la publicación o el
--         reporte, quedan vacíos y la notificación se queda.
--       - Si se borra el usuario, sus notificaciones se borran con él.
--   * dispositivo: los celulares de cada usuario que reciben notificaciones en la
--     barra (token de Firebase Cloud Messaging) y en qué idioma.
--       - Un token es de un solo celular: si en ese celular entra otra cuenta,
--         el token pasa a la cuenta nueva.
--       - Cuando sube e_version_sesion_usuario (cambio de contraseña, desactivar,
--         eliminar o suspender la cuenta) se borran TODOS sus dispositivos, para que
--         un celular que ya no tiene sesión no siga recibiendo avisos de esa cuenta.
--         El celular que sigue dentro vuelve a registrar su token.
--   * fn_limpiar_notificaciones(): borra notificaciones de más de 90 días y
--     dispositivos sin usar en 60 días. La API la corre una vez al día.
--
-- Para agregar un tipo nuevo después: se cambia la regla ck_notificacion_tipo
-- y se agregan sus textos (app y API).
--
-- En DBeaver: pon el cursor en el bloque y pulsa Ctrl+Enter.
-- Si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output" sale: "Migración 12 aplicada".
-- ============================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'usuario' AND column_name = 'e_version_sesion_usuario') THEN
        RAISE EXCEPTION 'Primero aplica la migración 05.';
    END IF;
    IF to_regclass('notificacion') IS NOT NULL THEN
        RAISE EXCEPTION 'La migración 12 ya se había aplicado.';
    END IF;
    -- --------------------------------------------------------
    -- 1. NOTIFICACION
    -- --------------------------------------------------------
    CREATE TABLE notificacion (
        pke_id_notificacion INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        fke_id_usuario INTEGER NOT NULL,
        t_tipo_notificacion TEXT NOT NULL,
        jb_datos_notificacion JSONB NOT NULL DEFAULT '{}'::jsonb,
        fke_id_publicacion INTEGER,
        fke_id_reporte INTEGER,
        b_leida_notificacion BOOLEAN NOT NULL DEFAULT FALSE,
        fh_leida_notificacion TIMESTAMPTZ,
        fh_fecha_registro_notificacion TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT fk_notificacion_usuario FOREIGN KEY (fke_id_usuario)
            REFERENCES usuario (pke_id_usuario) ON DELETE CASCADE,
        CONSTRAINT fk_notificacion_publicacion FOREIGN KEY (fke_id_publicacion)
            REFERENCES publicacion (pke_id_publicacion) ON DELETE SET NULL,
        CONSTRAINT fk_notificacion_reporte FOREIGN KEY (fke_id_reporte)
            REFERENCES reporte (pke_id_reporte) ON DELETE SET NULL,
        CONSTRAINT ck_notificacion_tipo CHECK (t_tipo_notificacion IN (
            'BIENVENIDA',               -- se registró
            'PLAN_CONTRATADO',          -- "pagó" un plan: agradecimiento y confirmación
            'PUBLICACION_EN_REVISION',  -- creó (o editó) una publicación y quedó en revisión
            'PUBLICACION_APROBADA',     -- un administrador la aprobó
            'PUBLICACION_RECHAZADA',    -- un administrador la rechazó (con motivo)
            'PUBLICACION_RETIRADA',     -- al dueño: un administrador la retiró por un reporte
            'REPORTE_ENVIADO',          -- a quien reportó: su reporte se mandó
            'REPORTE_ATENDIDO',         -- a quien reportó: se tomó una medida
            'REPORTE_DESCARTADO'        -- a quien reportó: se revisó y no procedió
        )),
        CONSTRAINT ck_notificacion_datos CHECK (jsonb_typeof(jb_datos_notificacion) = 'object'),
        -- Leída: con fecha. No leída: sin fecha.
        CONSTRAINT ck_notificacion_leida CHECK (b_leida_notificacion = (fh_leida_notificacion IS NOT NULL))
    );
    -- Lista del usuario, de la más nueva a la más vieja
    CREATE INDEX ix_notificacion_usuario_fecha
        ON notificacion (fke_id_usuario, fh_fecha_registro_notificacion DESC);
    -- Contador de no leídas (el puntito de la campana)
    CREATE INDEX ix_notificacion_no_leidas
        ON notificacion (fke_id_usuario) WHERE NOT b_leida_notificacion;
    CREATE INDEX ix_notificacion_publicacion ON notificacion (fke_id_publicacion);
    CREATE INDEX ix_notificacion_reporte ON notificacion (fke_id_reporte);
    -- --------------------------------------------------------
    -- 2. DISPOSITIVO (tokens para las notificaciones en la barra del celular)
    -- --------------------------------------------------------
    CREATE TABLE dispositivo (
        pke_id_dispositivo INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        fke_id_usuario INTEGER NOT NULL,
        t_token_dispositivo TEXT NOT NULL,
        t_idioma_dispositivo TEXT NOT NULL DEFAULT 'es',
        fh_fecha_registro_dispositivo TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
        fh_ultimo_uso_dispositivo TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT fk_dispositivo_usuario FOREIGN KEY (fke_id_usuario)
            REFERENCES usuario (pke_id_usuario) ON DELETE CASCADE,
        CONSTRAINT uq_dispositivo_token UNIQUE (t_token_dispositivo),
        CONSTRAINT ck_dispositivo_token CHECK (char_length(t_token_dispositivo) BETWEEN 20 AND 4096),
        CONSTRAINT ck_dispositivo_idioma CHECK (t_idioma_dispositivo IN ('es', 'en'))
    );
    CREATE INDEX ix_dispositivo_usuario ON dispositivo (fke_id_usuario);
    -- --------------------------------------------------------
    -- 3. Al cerrar las sesiones de un usuario, sus celulares dejan de recibir avisos
    -- --------------------------------------------------------
    CREATE FUNCTION fn_borrar_dispositivos_al_cerrar_sesiones()
    RETURNS TRIGGER LANGUAGE plpgsql AS $fn$
    BEGIN
        DELETE FROM dispositivo WHERE fke_id_usuario = NEW.pke_id_usuario;
        RETURN NEW;
    END
    $fn$;
    CREATE TRIGGER tg_usuario_version_sesion
        AFTER UPDATE OF e_version_sesion_usuario ON usuario
        FOR EACH ROW
        WHEN (OLD.e_version_sesion_usuario IS DISTINCT FROM NEW.e_version_sesion_usuario)
        EXECUTE FUNCTION fn_borrar_dispositivos_al_cerrar_sesiones();
    -- --------------------------------------------------------
    -- 4. Limpieza diaria (la corre la API)
    -- --------------------------------------------------------
    -- Regresa cuántas notificaciones borró.
    CREATE FUNCTION fn_limpiar_notificaciones()
    RETURNS INTEGER LANGUAGE plpgsql AS $fn$
    DECLARE
        v_borradas INTEGER;
    BEGIN
        DELETE FROM notificacion
        WHERE fh_fecha_registro_notificacion < CURRENT_TIMESTAMP - INTERVAL '90 days';
        GET DIAGNOSTICS v_borradas = ROW_COUNT;
        DELETE FROM dispositivo
        WHERE fh_ultimo_uso_dispositivo < CURRENT_TIMESTAMP - INTERVAL '60 days';
        RETURN v_borradas;
    END
    $fn$;
    RAISE NOTICE 'Migración 12 aplicada';
END $$;
