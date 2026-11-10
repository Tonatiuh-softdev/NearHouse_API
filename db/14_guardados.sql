-- ============================================================
-- Migración 14: guardados (publicaciones que cada usuario guarda)
-- ============================================================
DO $$
BEGIN
    IF to_regclass('guardado') IS NOT NULL THEN
        RAISE NOTICE 'La migración 14 ya se había aplicado.';
        RETURN;
    END IF;
    CREATE TABLE guardado (
        pke_id_guardado INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        fke_id_usuario INTEGER NOT NULL,
        fke_id_publicacion INTEGER NOT NULL,
        fh_fecha_registro_guardado TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT fk_guardado_usuario FOREIGN KEY (fke_id_usuario)
            REFERENCES usuario (pke_id_usuario) ON DELETE CASCADE,
        CONSTRAINT fk_guardado_publicacion FOREIGN KEY (fke_id_publicacion)
            REFERENCES publicacion (pke_id_publicacion) ON DELETE CASCADE,
        CONSTRAINT uq_guardado_usuario_publicacion UNIQUE (fke_id_usuario, fke_id_publicacion)
    );
    CREATE INDEX ix_guardado_usuario_fecha ON guardado (fke_id_usuario, fh_fecha_registro_guardado DESC);
    CREATE INDEX ix_guardado_publicacion ON guardado (fke_id_publicacion);
    RAISE NOTICE 'Migración 14 aplicada';
END $$;
