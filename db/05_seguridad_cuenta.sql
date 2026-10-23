-- ============================================================
-- Migración 05: seguridad de la cuenta
-- Se corre UNA sola vez. No depende de las migraciones 02, 03 ni 04.
--
-- Agrega a usuario:
--   * e_version_sesion_usuario: va dentro del token de sesión. Al cambiar o
--     recuperar la contraseña sube en 1, y la API rechaza los tokens con la
--     versión anterior: así se cierran las sesiones de otros celulares.
--   * e_intentos_fallidos_usuario y fh_bloqueo_hasta_usuario: después de
--     5 contraseñas equivocadas, se bloquea un rato.
--   * fh_cambio_contrasena_usuario: cuándo se cambió la contraseña por última vez.
--
-- En DBeaver: pon el cursor en el bloque y pulsa Ctrl+Enter.
-- Si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output" sale: "Migración 05 aplicada".
-- ============================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'usuario' AND column_name = 'e_version_sesion_usuario') THEN
        RAISE EXCEPTION 'La migración 05 ya se había aplicado.';
    END IF;
    ALTER TABLE usuario
        ADD COLUMN e_version_sesion_usuario INTEGER NOT NULL DEFAULT 0,
        ADD COLUMN e_intentos_fallidos_usuario INTEGER NOT NULL DEFAULT 0,
        ADD COLUMN fh_bloqueo_hasta_usuario TIMESTAMPTZ,
        ADD COLUMN fh_cambio_contrasena_usuario TIMESTAMPTZ,
        ADD CONSTRAINT ck_usuario_version_sesion CHECK (e_version_sesion_usuario >= 0),
        ADD CONSTRAINT ck_usuario_intentos_fallidos CHECK (e_intentos_fallidos_usuario >= 0);
    RAISE NOTICE 'Migración 05 aplicada';
END $$;
