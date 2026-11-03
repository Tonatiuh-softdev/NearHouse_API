-- ============================================================
-- Migración 10: reportes de publicaciones
-- Requiere la 02 (publicaciones). Se corre UNA sola vez.
--
-- Qué cambia:
--  * reporte:
--    - t_categoria_reporte y t_motivo_reporte guardan CÓDIGOS fijos
--      (por ejemplo INFORMACION_FALSA / PRECIO_FALSO). La app los traduce.
--    - La descripción va de 10 a 200 caracteres.
--    - t_estado_reporte: PENDIENTE, PROCEDENTE o DESCARTADO.
--    - t_resolucion_reporte: la nota del administrador al resolverlo.
--      Quién lo resolvió y cuándo van en fke_id_usuario_actualizacion
--      y fh_fecha_actualizacion_reporte (ya existían).
--    - Un usuario no puede tener dos reportes PENDIENTES de la misma publicación.
--  * evidencia: la imagen se guarda en la base (JPG, PNG o WebP, máx. 2 MB,
--    de 0 a 5 por reporte). Ya no se usa la URL.
--
-- Para agregar un motivo nuevo después: cambiar ck_reporte_motivo y agregar
-- su texto en la app (español e inglés).
--
-- En DBeaver: pon el cursor en el bloque y pulsa Ctrl+Enter.
-- Si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output" sale: "Migración 10 aplicada".
-- ============================================================

DO $$ BEGIN
  -- Validaciones previas
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_name='publicacion' AND column_name='t_estado_publicacion') THEN
    RAISE EXCEPTION 'Primero aplica la migración 02.';
  END IF;

  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_name='reporte' AND column_name='t_estado_reporte') THEN
    RAISE EXCEPTION 'La migración 10 ya se había aplicado.';
  END IF;

  IF EXISTS (SELECT 1 FROM reporte) THEN
    RAISE EXCEPTION 'La tabla reporte ya tiene datos. Bórralos y reintenta.';
  END IF;

  IF EXISTS (SELECT 1 FROM evidencia) THEN
    RAISE EXCEPTION 'La tabla evidencia ya tiene datos. Bórralos y reintenta.';
  END IF;

  -- 1. REPORTE: categoría, motivo, estado y nota del administrador
  ALTER TABLE reporte
    ADD COLUMN t_categoria_reporte TEXT NOT NULL,
    ADD COLUMN t_estado_reporte TEXT NOT NULL DEFAULT 'PENDIENTE',
    ADD COLUMN t_resolucion_reporte TEXT,
    ADD CONSTRAINT ck_reporte_categoria CHECK (
      t_categoria_reporte IN ('INFORMACION_FALSA','CONTENIDO_OFENSIVO','POSIBLE_FRAUDE')),
    ADD CONSTRAINT ck_reporte_motivo CHECK (
      (t_categoria_reporte='INFORMACION_FALSA' AND t_motivo_reporte IN
        ('FOTOS_NO_CORRESPONDEN','PRECIO_FALSO','DATOS_INCORRECTOS','UBICACION_INCORRECTA','PROPIEDAD_NO_EXISTE'))
      OR (t_categoria_reporte='CONTENIDO_OFENSIVO' AND t_motivo_reporte IN
        ('LENGUAJE_OFENSIVO','CONTENIDO_DISCRIMINATORIO','CONTENIDO_SEXUAL','CONTENIDO_VIOLENTO','OTRO'))
      OR (t_categoria_reporte='POSIBLE_FRAUDE' AND t_motivo_reporte IN
        ('PRECIO_FALSO','DATOS_INCORRECTOS','PUBLICADA_POR_VARIOS','YA_NO_DISPONIBLE','OTRO'))),
    ADD CONSTRAINT ck_reporte_descripcion CHECK (
      char_length(btrim(t_descripcion_reporte)) BETWEEN 10 AND 200),
    ADD CONSTRAINT ck_reporte_estado CHECK (
      t_estado_reporte IN ('PENDIENTE','PROCEDENTE','DESCARTADO')),
    ADD CONSTRAINT ck_reporte_resolucion CHECK (
      (t_estado_reporte='PENDIENTE' AND t_resolucion_reporte IS NULL)
      OR (t_estado_reporte<>'PENDIENTE'
          AND char_length(btrim(coalesce(t_resolucion_reporte,''))) BETWEEN 1 AND 300
          AND fh_fecha_actualizacion_reporte IS NOT NULL));

  ALTER TABLE reporte ALTER COLUMN t_motivo_reporte SET NOT NULL;

  -- Un usuario no puede tener dos reportes pendientes de la misma publicación
  CREATE UNIQUE INDEX uq_reporte_pendiente_por_usuario
    ON reporte (fke_id_publicacion, fke_id_usuario_registro)
    WHERE t_estado_reporte='PENDIENTE';

  CREATE INDEX ix_reporte_estado_fecha
    ON reporte (t_estado_reporte, fh_fecha_registro_reporte);
  CREATE INDEX ix_reporte_publicacion ON reporte (fke_id_publicacion);
  CREATE INDEX ix_reporte_usuario_fecha
    ON reporte (fke_id_usuario_registro, fh_fecha_registro_reporte);

  -- 2. EVIDENCIA: la imagen se guarda en la base (máx. 2 MB, de 0 a 5 por reporte)
  ALTER TABLE evidencia
    DROP COLUMN IF EXISTS t_url_imagen_evidencia,
    ADD COLUMN by_imagen_evidencia BYTEA NOT NULL,
    ADD COLUMN t_tipo_imagen_evidencia TEXT NOT NULL,
    ADD COLUMN e_orden_evidencia INTEGER NOT NULL,
    ADD CONSTRAINT ck_evidencia_tamano CHECK (
      octet_length(by_imagen_evidencia) BETWEEN 1 AND 2097152),
    ADD CONSTRAINT ck_evidencia_tipo CHECK (
      t_tipo_imagen_evidencia IN ('image/jpeg','image/png','image/webp')),
    ADD CONSTRAINT ck_evidencia_orden CHECK (e_orden_evidencia BETWEEN 1 AND 5),
    ADD CONSTRAINT uq_evidencia_orden UNIQUE (fke_id_reporte, e_orden_evidencia);

  RAISE NOTICE 'Migración 10 aplicada';
END $$;