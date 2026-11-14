-- ============================================================
-- Migración 16: apelaciones
-- Requiere la 15 (reportes a usuarios). Se corre UNA sola vez.
--
-- Una apelación es la defensa de un usuario contra una medida de un administrador:
--   * PUBLICACION: su anuncio fue retirado por un reporte.
--   * SUSPENSION: su cuenta fue suspendida.
-- Solo hay UNA apelación por medida. La medida se identifica por su fecha
-- (fh_fecha_medida_apelacion): la fecha de revisión del anuncio retirado o la
-- fecha en que empezó la suspensión. Si después hay otra medida, se puede apelar otra vez.
-- La revisa un administrador distinto al que tomó la medida (eso lo valida la API).
--
-- Qué se agrega:
--   * apelacion: la apelación y su resolución (ACEPTADA = se revierte la medida,
--     RECHAZADA = la medida se mantiene). La nota del administrador SÍ la ve quien apeló.
--   * evidencia_apelacion: imágenes de la apelación (JPG, PNG o WebP, máx. 2 MB, de 0 a 5).
--   * notificacion: tipos APELACION_ENVIADA, APELACION_ACEPTADA y APELACION_RECHAZADA.
--
-- En DBeaver o pgAdmin: ejecuta todo el bloque.
-- Si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output"/"Messages" sale: "Migración 16 aplicada".
-- ============================================================

DO $$ BEGIN
  -- Validaciones previas
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_name='reporte' AND column_name='fke_id_usuario_reportado') THEN
    RAISE EXCEPTION 'Primero aplica la migración 15.';
  END IF;

  IF to_regclass('apelacion') IS NOT NULL THEN
    RAISE EXCEPTION 'La migración 16 ya se había aplicado.';
  END IF;

  -- 1. APELACION
  CREATE TABLE apelacion (
    pke_id_apelacion INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fke_id_usuario INTEGER NOT NULL,                  -- quien apela
    t_tipo_apelacion TEXT NOT NULL,                   -- PUBLICACION o SUSPENSION
    fke_id_publicacion INTEGER,                       -- solo en PUBLICACION
    fh_fecha_medida_apelacion TIMESTAMPTZ NOT NULL,   -- identifica la medida apelada
    t_motivo_medida_apelacion TEXT NOT NULL,          -- motivo que dio el administrador (copia)
    fke_id_usuario_medida INTEGER,                    -- administrador que tomó la medida
    t_descripcion_apelacion TEXT NOT NULL,
    t_estado_apelacion TEXT NOT NULL DEFAULT 'PENDIENTE',
    t_resolucion_apelacion TEXT,                      -- respuesta del administrador
    fke_id_usuario_resolucion INTEGER,
    fh_fecha_resolucion_apelacion TIMESTAMPTZ,
    fh_fecha_registro_apelacion TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_apelacion_usuario FOREIGN KEY (fke_id_usuario)
      REFERENCES usuario (pke_id_usuario) ON DELETE CASCADE,
    CONSTRAINT fk_apelacion_publicacion FOREIGN KEY (fke_id_publicacion)
      REFERENCES publicacion (pke_id_publicacion) ON DELETE CASCADE,
    CONSTRAINT fk_apelacion_usuario_medida FOREIGN KEY (fke_id_usuario_medida)
      REFERENCES usuario (pke_id_usuario),
    CONSTRAINT fk_apelacion_usuario_resolucion FOREIGN KEY (fke_id_usuario_resolucion)
      REFERENCES usuario (pke_id_usuario),
    CONSTRAINT ck_apelacion_tipo CHECK (
      (t_tipo_apelacion = 'PUBLICACION' AND fke_id_publicacion IS NOT NULL)
      OR (t_tipo_apelacion = 'SUSPENSION' AND fke_id_publicacion IS NULL)),
    CONSTRAINT ck_apelacion_motivo_medida CHECK (char_length(btrim(t_motivo_medida_apelacion)) >= 1),
    CONSTRAINT ck_apelacion_descripcion CHECK (
      char_length(btrim(t_descripcion_apelacion)) BETWEEN 10 AND 500),
    CONSTRAINT ck_apelacion_estado CHECK (
      t_estado_apelacion IN ('PENDIENTE','ACEPTADA','RECHAZADA')),
    CONSTRAINT ck_apelacion_resolucion CHECK (
      (t_estado_apelacion = 'PENDIENTE'
        AND t_resolucion_apelacion IS NULL AND fh_fecha_resolucion_apelacion IS NULL)
      OR (t_estado_apelacion <> 'PENDIENTE'
        AND char_length(btrim(coalesce(t_resolucion_apelacion, ''))) BETWEEN 1 AND 300
        AND fh_fecha_resolucion_apelacion IS NOT NULL))
  );

  -- Una sola apelación por medida
  CREATE UNIQUE INDEX uq_apelacion_publicacion
    ON apelacion (fke_id_publicacion, fh_fecha_medida_apelacion)
    WHERE t_tipo_apelacion = 'PUBLICACION';
  CREATE UNIQUE INDEX uq_apelacion_suspension
    ON apelacion (fke_id_usuario, fh_fecha_medida_apelacion)
    WHERE t_tipo_apelacion = 'SUSPENSION';

  CREATE INDEX ix_apelacion_estado_fecha ON apelacion (t_estado_apelacion, fh_fecha_registro_apelacion);
  CREATE INDEX ix_apelacion_usuario ON apelacion (fke_id_usuario);

  -- 2. EVIDENCIA_APELACION (igual que las evidencias de los reportes)
  CREATE TABLE evidencia_apelacion (
    pke_id_evidencia_apelacion INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fke_id_apelacion INTEGER NOT NULL,
    by_imagen_evidencia_apelacion BYTEA NOT NULL,
    t_tipo_imagen_evidencia_apelacion TEXT NOT NULL,
    e_orden_evidencia_apelacion INTEGER NOT NULL,
    fh_fecha_subida_evidencia_apelacion TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evidencia_apelacion FOREIGN KEY (fke_id_apelacion)
      REFERENCES apelacion (pke_id_apelacion) ON DELETE CASCADE,
    CONSTRAINT ck_evidencia_apelacion_tamano CHECK (
      octet_length(by_imagen_evidencia_apelacion) BETWEEN 1 AND 2097152),
    CONSTRAINT ck_evidencia_apelacion_tipo CHECK (
      t_tipo_imagen_evidencia_apelacion IN ('image/jpeg','image/png','image/webp')),
    CONSTRAINT ck_evidencia_apelacion_orden CHECK (e_orden_evidencia_apelacion BETWEEN 1 AND 5),
    CONSTRAINT uq_evidencia_apelacion_orden UNIQUE (fke_id_apelacion, e_orden_evidencia_apelacion)
  );

  -- 3. Tipos nuevos de notificación
  ALTER TABLE notificacion DROP CONSTRAINT ck_notificacion_tipo;
  ALTER TABLE notificacion ADD CONSTRAINT ck_notificacion_tipo CHECK (t_tipo_notificacion IN (
    'BIENVENIDA',
    'PLAN_CONTRATADO',
    'PUBLICACION_EN_REVISION',
    'PUBLICACION_APROBADA',
    'PUBLICACION_RECHAZADA',
    'PUBLICACION_RETIRADA',
    'REPORTE_ENVIADO',
    'REPORTE_ATENDIDO',
    'REPORTE_DESCARTADO',
    'APELACION_ENVIADA',        -- a quien apeló: su apelación se mandó
    'APELACION_ACEPTADA',       -- a quien apeló: se revirtió la medida
    'APELACION_RECHAZADA'       -- a quien apeló: la medida se mantiene (con la respuesta)
  ));

  RAISE NOTICE 'Migración 16 aplicada';
END $$;
