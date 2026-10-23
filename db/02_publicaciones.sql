-- ============================================================
-- Migración 02: publicaciones, filtros y catálogo de colonias
-- Se corre UNA sola vez sobre la base que ya tiene NearHouse.sql.
--
-- En DBeaver: pon el cursor en cualquier parte del bloque y pulsa Ctrl+Enter.
-- Es un solo bloque: si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output" sale: "Migración 02 aplicada".
--
-- Para comprobar después:  SELECT * FROM colonia;   (deben salir 14)
--
-- Prefijos: t_ texto, e_ entero, n_ numérico, b_ booleano,
--           fh_ fecha y hora, by_ bytes (archivo binario).
-- ============================================================
DO $$
DECLARE
    r RECORD;
BEGIN
    -- --------------------------------------------------------
    -- 0. Solo se corre con las publicaciones vacías, para no perder datos
    -- --------------------------------------------------------
    IF EXISTS (SELECT 1 FROM publicacion) THEN
        RAISE EXCEPTION 'La tabla publicacion ya tiene datos. Bórralos o avisa antes de correr esta migración.';
    END IF;
    IF to_regclass('colonia') IS NOT NULL THEN
        RAISE EXCEPTION 'La migración 02 ya se había aplicado (la tabla colonia ya existe).';
    END IF;
    -- --------------------------------------------------------
    -- 1. COLONIA: catálogo para que Crear anuncio y Filtros usen los mismos nombres
    -- --------------------------------------------------------
    CREATE TABLE colonia (
        pke_id_colonia INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        t_nombre_colonia TEXT NOT NULL,
        b_estatus_colonia BOOLEAN NOT NULL DEFAULT TRUE,  -- FALSE = ya no aparece en las listas
        fke_id_usuario_registro INTEGER,
        fke_id_usuario_actualizacion INTEGER,
        CONSTRAINT ck_colonia_nombre CHECK (btrim(t_nombre_colonia) <> ''),
        CONSTRAINT fk_colonia_usuario_registro
            FOREIGN KEY (fke_id_usuario_registro) REFERENCES usuario (pke_id_usuario),
        CONSTRAINT fk_colonia_usuario_actualizacion
            FOREIGN KEY (fke_id_usuario_actualizacion) REFERENCES usuario (pke_id_usuario)
    );
    -- Sin colonias repetidas, aunque cambien mayúsculas ("Salagua" = "salagua")
    CREATE UNIQUE INDEX uq_colonia_nombre ON colonia (LOWER(t_nombre_colonia));
    INSERT INTO colonia (t_nombre_colonia) VALUES
        ('Campos'),
        ('Centro Histórico'),
        ('Club Santiago'),
        ('El Colomo'),
        ('La Joya'),
        ('Las Brisas'),
        ('Las Hadas'),
        ('Miramar'),
        ('Nuevo Salagua'),
        ('Salagua'),
        ('San Pedrito'),
        ('Soleares'),
        ('Tapeixtles'),
        ('Valle de las Garzas');
    -- --------------------------------------------------------
    -- 2. PUBLICACION: estado de revisión, reglas y quién la revisó
    -- --------------------------------------------------------
    -- b_estatus_publicacion se queda: FALSE = el dueño la dio de baja.
    -- t_estado_publicacion es el estado de la revisión del administrador.
    ALTER TABLE publicacion
        ADD COLUMN t_estado_publicacion TEXT NOT NULL DEFAULT 'EN_REVISION',
        ADD COLUMN t_reglas_publicacion TEXT,
        ADD COLUMN t_motivo_rechazo_publicacion TEXT,
        ADD COLUMN fh_fecha_revision_publicacion TIMESTAMPTZ,
        ADD COLUMN fke_id_usuario_revision INTEGER,
        ADD CONSTRAINT ck_publicacion_estado
            CHECK (t_estado_publicacion IN ('EN_REVISION', 'PUBLICADA', 'RECHAZADA')),
        ADD CONSTRAINT ck_publicacion_titulo
            CHECK (char_length(btrim(t_nombre_publicacion)) BETWEEN 1 AND 60),
        ADD CONSTRAINT ck_publicacion_descripcion
            CHECK (char_length(btrim(t_descripcion_publicacion)) BETWEEN 30 AND 1000),
        ADD CONSTRAINT ck_publicacion_reglas
            CHECK (t_reglas_publicacion IS NULL OR char_length(t_reglas_publicacion) <= 300),
        -- Si se rechaza, el vendedor tiene que saber por qué
        ADD CONSTRAINT ck_publicacion_motivo_rechazo
            CHECK (t_estado_publicacion <> 'RECHAZADA' OR btrim(coalesce(t_motivo_rechazo_publicacion, '')) <> ''),
        ADD CONSTRAINT fk_publicacion_usuario_revision
            FOREIGN KEY (fke_id_usuario_revision) REFERENCES usuario (pke_id_usuario);
    -- "Mis anuncios" busca por dueño; Inicio y Filtros buscan las publicadas
    CREATE INDEX ix_publicacion_usuario ON publicacion (fke_id_usuario);
    CREATE INDEX ix_publicacion_estado ON publicacion (t_estado_publicacion);
    -- --------------------------------------------------------
    -- 3. PROPIEDAD: superficie siempre, código postal y colonia del catálogo
    -- --------------------------------------------------------
    ALTER TABLE propiedad
        DROP COLUMN t_colonia_propiedad,
        ADD COLUMN fke_id_colonia INTEGER NOT NULL,
        ADD COLUMN e_superficie_propiedad INTEGER NOT NULL,
        ADD COLUMN t_codigo_postal_propiedad TEXT,
        ADD CONSTRAINT fk_propiedad_colonia
            FOREIGN KEY (fke_id_colonia) REFERENCES colonia (pke_id_colonia),
        ADD CONSTRAINT ck_propiedad_superficie CHECK (e_superficie_propiedad > 0),
        ADD CONSTRAINT ck_propiedad_tipo
            CHECK (t_tipo_propiedad IN ('DEPARTAMENTO', 'CASA', 'TERRENO')),
        ADD CONSTRAINT ck_propiedad_codigo_postal
            CHECK (t_codigo_postal_propiedad IS NULL OR t_codigo_postal_propiedad ~ '^[0-9]{5}$');
    -- Filtros por colonia
    CREATE INDEX ix_propiedad_colonia ON propiedad (fke_id_colonia);
    -- --------------------------------------------------------
    -- 4. INMUEBLE (departamento y casa): elevador, niveles y planta baja
    -- --------------------------------------------------------
    -- Se quita la regla vieja del piso (>= 1), sin importar con qué nombre quedó
    FOR r IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'inmueble'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%e_piso_inmueble%'
    LOOP
        EXECUTE format('ALTER TABLE inmueble DROP CONSTRAINT %I', r.conname);
    END LOOP;
    -- Piso: 0 = planta baja; vacío en casas.  Niveles: solo casas.
    ALTER TABLE inmueble
        ALTER COLUMN e_piso_inmueble DROP NOT NULL,
        ALTER COLUMN e_piso_inmueble DROP DEFAULT,
        ADD COLUMN b_elevador_inmueble BOOLEAN NOT NULL DEFAULT FALSE,
        ADD COLUMN e_niveles_inmueble INTEGER,
        ADD CONSTRAINT ck_inmueble_piso CHECK (e_piso_inmueble IS NULL OR e_piso_inmueble >= 0),
        ADD CONSTRAINT ck_inmueble_niveles CHECK (e_niveles_inmueble IS NULL OR e_niveles_inmueble >= 1);
    -- --------------------------------------------------------
    -- 5. TERRENO: la superficie se movió a propiedad; uso del suelo cerrado
    -- --------------------------------------------------------
    ALTER TABLE terreno
        DROP COLUMN e_superficie_terreno,
        ADD CONSTRAINT ck_terreno_uso_suelo
            CHECK (t_uso_suelo_terreno IN ('HABITACIONAL', 'AGRICOLA'));
    -- --------------------------------------------------------
    -- 6. FOTO: la imagen se guarda en la base (máx. 2 MB, de 1 a 5 por propiedad)
    -- --------------------------------------------------------
    -- La API la entrega en GET /api/v1/fotos/{id}, por eso ya no hace falta la URL.
    ALTER TABLE foto
        DROP COLUMN t_url_imagen_foto,
        ADD COLUMN by_imagen_foto BYTEA NOT NULL,
        ADD COLUMN t_tipo_imagen_foto TEXT NOT NULL DEFAULT 'image/jpeg',
        ADD CONSTRAINT ck_foto_tamano CHECK (octet_length(by_imagen_foto) BETWEEN 1 AND 2097152),
        ADD CONSTRAINT ck_foto_tipo CHECK (t_tipo_imagen_foto IN ('image/jpeg', 'image/png', 'image/webp')),
        -- Con la regla que ya existe (orden >= 1) y la de orden único, quedan máximo 5 fotos
        ADD CONSTRAINT ck_foto_orden_maximo CHECK (e_orden_visibilidad_foto <= 5);
    RAISE NOTICE 'Migración 02 aplicada';
END $$;
