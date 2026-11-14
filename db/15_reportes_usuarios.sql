-- ============================================================
-- Migración 15: reportes a usuarios
-- Requiere la 10 (reportes). Se corre UNA sola vez.
--
-- Qué cambia:
--  * reporte:
--    - fke_id_usuario_reportado: la cuenta reportada (se entra por la bandera
--      del perfil del anunciante).
--    - fke_id_publicacion ahora es opcional: cada reporte es de una publicación
--      O de un usuario, nunca de los dos ni de ninguno (ck_reporte_objetivo).
--    - Categorías y motivos de usuario (la app los traduce):
--        PERFIL_FALSO: SUPLANTACION, DATOS_FALSOS, FOTO_INAPROPIADA, OTRO
--        COMPORTAMIENTO_INAPROPIADO: ACOSO, LENGUAJE_OFENSIVO, AMENAZAS,
--                                    CONTENIDO_DISCRIMINATORIO, OTRO
--        ESTAFA: PIDE_PAGO_ANTICIPADO, ANUNCIOS_FALSOS, NO_ES_EL_DUENO, OTRO
--    - Un usuario no puede tener dos reportes PENDIENTES de la misma cuenta.
--  * fn_eliminar_usuario: antes de borrar una cuenta, borra los reportes
--    que otros hicieron sobre ella (si no, quedarían sin publicación ni usuario).
--
-- Para agregar un motivo nuevo después: cambiar ck_reporte_motivo, la lista
-- MOTIVOS_USUARIO_POR_CATEGORIA de la API y su texto en la app (español e inglés).
--
-- En DBeaver: pon el cursor en el bloque y pulsa Ctrl+Enter.
-- Si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output" sale: "Migración 15 aplicada".
-- ============================================================

DO $$ BEGIN
  -- Validaciones previas
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_name='reporte' AND column_name='t_estado_reporte') THEN
    RAISE EXCEPTION 'Primero aplica la migración 10.';
  END IF;

  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_name='reporte' AND column_name='fke_id_usuario_reportado') THEN
    RAISE EXCEPTION 'La migración 15 ya se había aplicado.';
  END IF;

  -- 1. La cuenta reportada. Si se borra la cuenta, se borran sus reportes.
  ALTER TABLE reporte
    ADD COLUMN fke_id_usuario_reportado INTEGER,
    ADD CONSTRAINT fk_reporte_usuario_reportado
      FOREIGN KEY (fke_id_usuario_reportado) REFERENCES usuario (pke_id_usuario) ON DELETE CASCADE;

  ALTER TABLE reporte ALTER COLUMN fke_id_publicacion DROP NOT NULL;

  -- 2. Cada reporte es de una publicación o de un usuario (solo uno)
  ALTER TABLE reporte
    ADD CONSTRAINT ck_reporte_objetivo CHECK (
      (fke_id_publicacion IS NULL) <> (fke_id_usuario_reportado IS NULL));

  -- 3. Categorías y motivos según lo que se reporta
  ALTER TABLE reporte
    DROP CONSTRAINT ck_reporte_categoria,
    DROP CONSTRAINT ck_reporte_motivo;

  ALTER TABLE reporte
    ADD CONSTRAINT ck_reporte_categoria CHECK (
      (fke_id_publicacion IS NOT NULL AND t_categoria_reporte IN
        ('INFORMACION_FALSA','CONTENIDO_OFENSIVO','POSIBLE_FRAUDE'))
      OR (fke_id_usuario_reportado IS NOT NULL AND t_categoria_reporte IN
        ('PERFIL_FALSO','COMPORTAMIENTO_INAPROPIADO','ESTAFA'))),
    ADD CONSTRAINT ck_reporte_motivo CHECK (
      -- Publicaciones (igual que la migración 10)
      (t_categoria_reporte='INFORMACION_FALSA' AND t_motivo_reporte IN
        ('FOTOS_NO_CORRESPONDEN','PRECIO_FALSO','DATOS_INCORRECTOS','UBICACION_INCORRECTA','PROPIEDAD_NO_EXISTE'))
      OR (t_categoria_reporte='CONTENIDO_OFENSIVO' AND t_motivo_reporte IN
        ('LENGUAJE_OFENSIVO','CONTENIDO_DISCRIMINATORIO','CONTENIDO_SEXUAL','CONTENIDO_VIOLENTO','OTRO'))
      OR (t_categoria_reporte='POSIBLE_FRAUDE' AND t_motivo_reporte IN
        ('PRECIO_FALSO','DATOS_INCORRECTOS','PUBLICADA_POR_VARIOS','YA_NO_DISPONIBLE','OTRO'))
      -- Usuarios
      OR (t_categoria_reporte='PERFIL_FALSO' AND t_motivo_reporte IN
        ('SUPLANTACION','DATOS_FALSOS','FOTO_INAPROPIADA','OTRO'))
      OR (t_categoria_reporte='COMPORTAMIENTO_INAPROPIADO' AND t_motivo_reporte IN
        ('ACOSO','LENGUAJE_OFENSIVO','AMENAZAS','CONTENIDO_DISCRIMINATORIO','OTRO'))
      OR (t_categoria_reporte='ESTAFA' AND t_motivo_reporte IN
        ('PIDE_PAGO_ANTICIPADO','ANUNCIOS_FALSOS','NO_ES_EL_DUENO','OTRO')));

  -- 4. Un usuario no puede tener dos reportes pendientes de la misma cuenta
  CREATE UNIQUE INDEX uq_reporte_usuario_pendiente
    ON reporte (fke_id_usuario_reportado, fke_id_usuario_registro)
    WHERE t_estado_reporte='PENDIENTE' AND fke_id_usuario_reportado IS NOT NULL;

  CREATE INDEX ix_reporte_usuario_reportado ON reporte (fke_id_usuario_reportado);

  -- 5. Al borrar una cuenta, primero se borran los reportes que hay sobre ella.
  --    Lo demás es igual a la migración 08.
  CREATE OR REPLACE FUNCTION fn_eliminar_usuario(p_id INTEGER) RETURNS VOID
  LANGUAGE plpgsql AS $fn$
  DECLARE
      r RECORD;
  BEGIN
      DELETE FROM reporte WHERE fke_id_usuario_reportado = p_id;
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

  RAISE NOTICE 'Migración 15 aplicada';
END $$;
