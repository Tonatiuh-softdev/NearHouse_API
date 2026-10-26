BEGIN;

-- ------------------------------------------------------------
-- 1. CREACIÓN DE TABLAS
-- ------------------------------------------------------------

CREATE TABLE suscripcion (
    pke_id_suscripcion INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    t_nombre_suscripcion TEXT NOT NULL UNIQUE,  -- [CAMBIO] sin planes repetidos
    fh_fecha_registro_suscripcion TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fh_fecha_actualizacion_suscripcion TIMESTAMPTZ,
    fke_id_usuario_registro INTEGER,
    fke_id_usuario_actualizacion INTEGER
);

CREATE TABLE rol (
    pke_id_rol INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    t_nombre_rol TEXT NOT NULL UNIQUE,  -- [CAMBIO] sin roles repetidos
    b_estatus_rol BOOLEAN NOT NULL DEFAULT TRUE,
    fh_fecha_registro_rol TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fh_fecha_actualizacion_rol TIMESTAMPTZ,
    fke_id_usuario_registro INTEGER,
    fke_id_usuario_actualizacion INTEGER
);

CREATE TABLE usuario (
    pke_id_usuario INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fke_id_suscripcion INTEGER,
    fke_id_rol INTEGER NOT NULL,  -- [CAMBIO] todo usuario debe tener un rol
    t_nombre_usuario TEXT NOT NULL,
    t_primer_apellido_usuario TEXT NOT NULL,
    t_segundo_apellido_usuario TEXT,
    t_contrasena_usuario TEXT NOT NULL,  -- Guarda el HASH (bcrypt/argon2), nunca la contraseña en texto plano
    t_correo_usuario TEXT NOT NULL,  -- [CAMBIO] la unicidad se controla con el índice uq_usuario_correo (sin distinguir mayúsculas)
    t_telefono_usuario TEXT NOT NULL,
    b_estatus_usuario BOOLEAN NOT NULL DEFAULT TRUE,                -- [CAMBIO] FALSE = cuenta suspendida
    b_correo_verificado_usuario BOOLEAN NOT NULL DEFAULT FALSE,     -- [CAMBIO] flujo de verificación de correo
    b_telefono_verificado_usuario BOOLEAN NOT NULL DEFAULT FALSE,   -- [CAMBIO] flujo de verificación por SMS
    fh_fecha_registro_usuario TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fh_fecha_actualizacion_usuario TIMESTAMPTZ,
    fke_id_usuario_registro INTEGER,
    fke_id_usuario_actualizacion INTEGER,

    -- [CAMBIO] Validaciones básicas como respaldo de las que haga la API
    CONSTRAINT ck_usuario_nombre CHECK (btrim(t_nombre_usuario) <> ''),
    CONSTRAINT ck_usuario_primer_apellido CHECK (btrim(t_primer_apellido_usuario) <> ''),
    CONSTRAINT ck_usuario_correo CHECK (t_correo_usuario ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    CONSTRAINT ck_usuario_telefono CHECK (t_telefono_usuario ~ '^\+?[0-9]{10,15}$'),
    CONSTRAINT ck_usuario_contrasena_hash CHECK (char_length(t_contrasena_usuario) >= 60),
    CONSTRAINT uq_usuario_telefono UNIQUE (t_telefono_usuario)
);

-- [CAMBIO] Correo único sin importar mayúsculas: "Ana@correo.com" y "ana@correo.com" son el mismo
CREATE UNIQUE INDEX uq_usuario_correo ON usuario (LOWER(t_correo_usuario));

CREATE TABLE modalidad (
    pke_id_modalidad INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fke_id_usuario_registro INTEGER,
    fke_id_usuario_actualizacion INTEGER,
    t_nombre_modalidad TEXT NOT NULL UNIQUE,  -- [CAMBIO] sin modalidades repetidas
    b_estatus_modalidad BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE publicacion (
    pke_id_publicacion INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fke_id_usuario INTEGER NOT NULL,
    fke_id_modalidad INTEGER NOT NULL,
    t_nombre_publicacion TEXT NOT NULL,
    t_descripcion_publicacion TEXT NOT NULL,
    b_estatus_publicacion BOOLEAN NOT NULL DEFAULT TRUE,
    e_precio_publicacion NUMERIC(12, 2) NOT NULL CHECK (e_precio_publicacion > 0),  -- [CAMBIO]
    fh_fecha_publicacion TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fh_fecha_actualizacion_publicacion TIMESTAMPTZ,
    fke_id_usuario_registro INTEGER,
    fke_id_usuario_actualizacion INTEGER
);

CREATE TABLE reporte (
    pke_id_reporte INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fke_id_publicacion INTEGER NOT NULL,
    t_motivo_reporte TEXT NOT NULL,
    t_descripcion_reporte TEXT NOT NULL,
    b_estatus_reporte BOOLEAN NOT NULL DEFAULT TRUE,
    fh_fecha_registro_reporte TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fh_fecha_actualizacion_reporte TIMESTAMPTZ,
    fke_id_usuario_registro INTEGER,
    fke_id_usuario_actualizacion INTEGER
);

CREATE TABLE evidencia (
    pke_id_captura INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fke_id_reporte INTEGER NOT NULL,
    t_url_imagen_evidencia TEXT NOT NULL,
    fh_fecha_subida_evidencia TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE propiedad (
    pke_id_propiedad INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fke_id_publicacion INTEGER UNIQUE NOT NULL,
    t_tipo_propiedad TEXT NOT NULL,
    t_calle_propiedad TEXT NOT NULL,
    t_numero_propiedad TEXT NOT NULL,
    t_colonia_propiedad TEXT NOT NULL,
    n_latitud_propiedad NUMERIC(9, 6) NOT NULL CHECK (n_latitud_propiedad BETWEEN -90 AND 90),        -- [CAMBIO]
    n_longitud_propiedad NUMERIC(9, 6) NOT NULL CHECK (n_longitud_propiedad BETWEEN -180 AND 180)    -- [CAMBIO]
);

CREATE TABLE foto (
    pke_id_foto INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fke_id_propiedad INTEGER NOT NULL,
    e_orden_visibilidad_foto INTEGER NOT NULL CHECK (e_orden_visibilidad_foto >= 1),  -- [CAMBIO]
    t_url_imagen_foto TEXT NOT NULL,
    CONSTRAINT uq_foto_orden UNIQUE (fke_id_propiedad, e_orden_visibilidad_foto)     -- [CAMBIO] dos fotos no comparten posición
);

CREATE TABLE inmueble (
    pke_id_inmueble INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fke_id_propiedad INTEGER UNIQUE NOT NULL,
    b_amueblado_inmueble BOOLEAN NOT NULL DEFAULT FALSE,
    e_dormitorios_inmueble INTEGER NOT NULL DEFAULT 0 CHECK (e_dormitorios_inmueble >= 0),  -- [CAMBIO]
    e_banos_inmueble INTEGER NOT NULL DEFAULT 0 CHECK (e_banos_inmueble >= 0),  -- [CAMBIO]
    b_mascotas_inmueble BOOLEAN NOT NULL DEFAULT FALSE,
    e_cochera_inmueble INTEGER NOT NULL DEFAULT 0 CHECK (e_cochera_inmueble >= 0),  -- [CAMBIO]
    b_patio_inmueble BOOLEAN NOT NULL DEFAULT FALSE,
    e_piso_inmueble INTEGER NOT NULL DEFAULT 1 CHECK (e_piso_inmueble >= 1)  -- [CAMBIO]
);

CREATE TABLE terreno (
    pke_id_terreno INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fke_id_propiedad INTEGER UNIQUE NOT NULL,
    e_superficie_terreno INTEGER NOT NULL CHECK (e_superficie_terreno > 0),  -- [CAMBIO]
    t_uso_suelo_terreno TEXT NOT NULL
);

-- ------------------------------------------------------------
-- 2. RESTRICCIONES FOREIGN KEY
-- ------------------------------------------------------------

-- Suscripcion -> Usuario
ALTER TABLE suscripcion
    ADD CONSTRAINT fk_suscripcion_usuario_registro
    FOREIGN KEY (fke_id_usuario_registro) REFERENCES usuario (pke_id_usuario),
    ADD CONSTRAINT fk_suscripcion_usuario_actualizacion
    FOREIGN KEY (fke_id_usuario_actualizacion) REFERENCES usuario (pke_id_usuario);

-- Rol -> Usuario
ALTER TABLE rol
    ADD CONSTRAINT fk_rol_usuario_registro
    FOREIGN KEY (fke_id_usuario_registro) REFERENCES usuario (pke_id_usuario),
    ADD CONSTRAINT fk_rol_usuario_actualizacion
    FOREIGN KEY (fke_id_usuario_actualizacion) REFERENCES usuario (pke_id_usuario);

-- Usuario -> Suscripcion, Rol y Usuario
ALTER TABLE usuario
    ADD CONSTRAINT fk_usuario_suscripcion
    FOREIGN KEY (fke_id_suscripcion) REFERENCES suscripcion (pke_id_suscripcion),
    ADD CONSTRAINT fk_usuario_rol
    FOREIGN KEY (fke_id_rol) REFERENCES rol (pke_id_rol),
    ADD CONSTRAINT fk_usuario_usuario_registro
    FOREIGN KEY (fke_id_usuario_registro) REFERENCES usuario (pke_id_usuario),
    ADD CONSTRAINT fk_usuario_usuario_actualizacion
    FOREIGN KEY (fke_id_usuario_actualizacion) REFERENCES usuario (pke_id_usuario);

-- Modalidad -> Usuario
ALTER TABLE modalidad
    ADD CONSTRAINT fk_modalidad_usuario_registro
    FOREIGN KEY (fke_id_usuario_registro) REFERENCES usuario (pke_id_usuario),
    ADD CONSTRAINT fk_modalidad_usuario_actualizacion
    FOREIGN KEY (fke_id_usuario_actualizacion) REFERENCES usuario (pke_id_usuario);

-- Publicacion -> Usuario y Modalidad
ALTER TABLE publicacion
    ADD CONSTRAINT fk_publicacion_usuario
    FOREIGN KEY (fke_id_usuario) REFERENCES usuario (pke_id_usuario),
    ADD CONSTRAINT fk_publicacion_modalidad
    FOREIGN KEY (fke_id_modalidad) REFERENCES modalidad (pke_id_modalidad),
    ADD CONSTRAINT fk_publicacion_usuario_registro
    FOREIGN KEY (fke_id_usuario_registro) REFERENCES usuario (pke_id_usuario),
    ADD CONSTRAINT fk_publicacion_usuario_actualizacion
    FOREIGN KEY (fke_id_usuario_actualizacion) REFERENCES usuario (pke_id_usuario);

-- Reporte -> Publicacion y Usuario
ALTER TABLE reporte
    ADD CONSTRAINT fk_reporte_publicacion
    FOREIGN KEY (fke_id_publicacion) REFERENCES publicacion (pke_id_publicacion) ON DELETE CASCADE,
    ADD CONSTRAINT fk_reporte_usuario_registro
    FOREIGN KEY (fke_id_usuario_registro) REFERENCES usuario (pke_id_usuario),
    ADD CONSTRAINT fk_reporte_usuario_actualizacion
    FOREIGN KEY (fke_id_usuario_actualizacion) REFERENCES usuario (pke_id_usuario);

-- Evidencia -> Reporte
ALTER TABLE evidencia
    ADD CONSTRAINT fk_evidencia_reporte
    FOREIGN KEY (fke_id_reporte) REFERENCES reporte (pke_id_reporte) ON DELETE CASCADE;

-- Propiedad -> Publicacion (1:1)
ALTER TABLE propiedad
    ADD CONSTRAINT fk_propiedad_publicacion
    FOREIGN KEY (fke_id_publicacion) REFERENCES publicacion (pke_id_publicacion) ON DELETE CASCADE;

-- Foto -> Propiedad
ALTER TABLE foto
    ADD CONSTRAINT fk_foto_propiedad
    FOREIGN KEY (fke_id_propiedad) REFERENCES propiedad (pke_id_propiedad) ON DELETE CASCADE;

-- Inmueble -> Propiedad (1:1)
ALTER TABLE inmueble
    ADD CONSTRAINT fk_inmueble_propiedad
    FOREIGN KEY (fke_id_propiedad) REFERENCES propiedad (pke_id_propiedad) ON DELETE CASCADE;

-- Terreno -> Propiedad (1:1)
ALTER TABLE terreno
    ADD CONSTRAINT fk_terreno_propiedad
    FOREIGN KEY (fke_id_propiedad) REFERENCES propiedad (pke_id_propiedad) ON DELETE CASCADE;

-- ------------------------------------------------------------
-- 3. DATOS INICIALES (CATÁLOGOS)  -- [CAMBIO] sección nueva
-- ------------------------------------------------------------
-- Sin estos registros no se puede crear ningún usuario, porque fke_id_rol es obligatorio.
-- La API debe buscar el rol por nombre, no asumir que su id es 1, 2, 3...

INSERT INTO rol (t_nombre_rol) VALUES
    ('Comprador'),
    ('Vendedor'),
    ('Administrador'),
    ('Super Administrador');

INSERT INTO modalidad (t_nombre_modalidad) VALUES
    ('Venta'),
    ('Renta');

-- Usuario Super Administrador inicial
-- La contraseña se guarda como hash bcrypt (nunca en texto plano).
-- Se marca con correo y teléfono verificados porque es una cuenta creada por el equipo.
INSERT INTO usuario (
    fke_id_rol,
    t_nombre_usuario,
    t_primer_apellido_usuario,
    t_segundo_apellido_usuario,
    t_contrasena_usuario,
    t_correo_usuario,
    t_telefono_usuario,
    b_correo_verificado_usuario,
    b_telefono_verificado_usuario
) VALUES (
    (SELECT pke_id_rol FROM rol WHERE t_nombre_rol = 'Super Administrador'),
    'Roberto Tonatiuh',
    'Preciado',
    'Soto',
    '$2a$12$Z9J.TUHqQpw1Qwmgko/FI.qZ83GFNvieIR1qvX3J.GKx7LejjPYge',
    'rtonapreciado@gmail.com',
    '3141246312',
    TRUE,
    TRUE
);

COMMIT;