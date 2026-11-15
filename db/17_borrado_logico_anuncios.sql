-- ============================================================
-- Migración 17: borrado lógico de anuncios (auditoría)
-- Requiere la 13 (cancelar suscripción). Se puede correr más de una vez sin problema.
--
-- La API ya no borra un anuncio de la base cuando el dueño o un administrador lo elimina:
-- lo marca como eliminado, guarda cuándo y quién lo hizo, y lo apaga (b_estatus_publicacion
-- = FALSE), así ya no aparece en Inicio, en el mapa, en "Mis anuncios" ni en Guardados.
-- El anuncio se queda guardado para auditoría.
--
-- Si ya se corrió a mano un script con estas columnas, este archivo NO cambia nada de lo
-- que ya existe: solo agrega lo que falte. Sirve para que el repositorio tenga el mismo
-- esquema que la base de Railway.
--
-- Qué se asegura en publicacion:
--   * b_eliminada_publicacion: TRUE = eliminado (se queda guardado).
--   * fh_eliminacion_publicacion: cuándo se eliminó.
--   * fke_id_usuario_eliminacion: quién lo eliminó (el dueño o un administrador).
--
-- En DBeaver o pgAdmin: ejecuta todo el bloque.
-- Si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output"/"Messages" sale: "Migración 17 aplicada".
-- ============================================================

DO $$ BEGIN
  -- La API ahora lee la fecha de cancelación del plan: sin la 13 no se podría iniciar sesión
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_name = 'usuario' AND column_name = 'fh_cancelacion_suscripcion_usuario') THEN
    RAISE EXCEPTION 'Primero aplica la migración 13.';
  END IF;

  ALTER TABLE publicacion
    ADD COLUMN IF NOT EXISTS b_eliminada_publicacion BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS fh_eliminacion_publicacion TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS fke_id_usuario_eliminacion INTEGER;

  -- Quién lo eliminó apunta a usuario (solo si esa columna todavía no tiene su llave foránea).
  -- Si se borra la cuenta de quien eliminó el anuncio, el anuncio se queda (sin ese dato).
  IF NOT EXISTS (
      SELECT 1
      FROM pg_constraint c
      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
      WHERE c.conrelid = 'publicacion'::regclass
        AND c.contype = 'f'
        AND a.attname = 'fke_id_usuario_eliminacion') THEN
    ALTER TABLE publicacion
      ADD CONSTRAINT fk_publicacion_usuario_eliminacion
        FOREIGN KEY (fke_id_usuario_eliminacion) REFERENCES usuario (pke_id_usuario) ON DELETE SET NULL;
  END IF;

  RAISE NOTICE 'Migración 17 aplicada';
END $$;
