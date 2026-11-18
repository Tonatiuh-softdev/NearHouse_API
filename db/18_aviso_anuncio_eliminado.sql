-- ============================================================
-- Migración 18: aviso de anuncio eliminado por un administrador
-- Requiere la 16 (apelaciones). Se puede correr más de una vez sin problema.
--
-- Cuando un administrador elimina el anuncio de otra persona, la API le avisa al dueño
-- en la campanita y con una notificación push, con el motivo que escribió el administrador.
--
-- Qué se cambia:
--   * notificacion: se agrega el tipo PUBLICACION_ELIMINADA a la regla ck_notificacion_tipo.
--
-- Mientras no se corra, eliminar anuncios funciona igual; solo no se guarda ni se manda ese aviso.
--
-- En DBeaver o pgAdmin: ejecuta todo el bloque.
-- Si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output"/"Messages" sale: "Migración 18 aplicada".
-- ============================================================

DO $$ BEGIN
  -- Validaciones previas
  IF to_regclass('apelacion') IS NULL THEN
    RAISE EXCEPTION 'Primero aplica la migración 16.';
  END IF;

  ALTER TABLE notificacion DROP CONSTRAINT IF EXISTS ck_notificacion_tipo;
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
    'APELACION_ENVIADA',
    'APELACION_ACEPTADA',
    'APELACION_RECHAZADA',
    'PUBLICACION_ELIMINADA'     -- al dueño: un administrador eliminó su anuncio (con motivo)
  ));

  RAISE NOTICE 'Migración 18 aplicada';
END $$;
