-- ============================================================
-- Migración 04: foto de perfil del usuario
-- Se corre UNA sola vez. No depende de la 02 ni de la 03.
--
-- Agrega a usuario la foto de perfil (opcional), guardada en la base
-- igual que las fotos de los anuncios: máximo 2 MB, JPEG, PNG o WebP.
-- La API la entrega en GET /api/v1/usuarios/me/foto.
--
-- En DBeaver: pon el cursor en el bloque y pulsa Ctrl+Enter.
-- Si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output" sale: "Migración 04 aplicada".
-- ============================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'usuario' AND column_name = 'by_foto_usuario') THEN
        RAISE EXCEPTION 'La migración 04 ya se había aplicado.';
    END IF;
    -- Las tres columnas van juntas: o hay foto completa, o no hay nada.
    -- fh_fecha_foto_usuario sirve para que la app sepa que la foto cambió y no muestre la vieja.
    ALTER TABLE usuario
        ADD COLUMN by_foto_usuario BYTEA,
        ADD COLUMN t_tipo_foto_usuario TEXT,
        ADD COLUMN fh_fecha_foto_usuario TIMESTAMPTZ,
        ADD CONSTRAINT ck_usuario_foto_completa CHECK (
            (by_foto_usuario IS NULL AND t_tipo_foto_usuario IS NULL AND fh_fecha_foto_usuario IS NULL)
            OR (by_foto_usuario IS NOT NULL AND t_tipo_foto_usuario IS NOT NULL AND fh_fecha_foto_usuario IS NOT NULL)
        ),
        ADD CONSTRAINT ck_usuario_foto_tamano
            CHECK (by_foto_usuario IS NULL OR octet_length(by_foto_usuario) BETWEEN 1 AND 2097152),
        ADD CONSTRAINT ck_usuario_foto_tipo
            CHECK (t_tipo_foto_usuario IS NULL OR t_tipo_foto_usuario IN ('image/jpeg', 'image/png', 'image/webp'));
    RAISE NOTICE 'Migración 04 aplicada';
END $$;
