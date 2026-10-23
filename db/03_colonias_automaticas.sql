-- ============================================================
-- Migración 03: colonias automáticas
-- Requiere la 02. Se corre UNA sola vez.
--
-- Qué cambia:
--   * La colonia ya no se elige de una lista: llega del celular (Geocoder)
--     y, si no existe, se agrega sola con fn_obtener_colonia().
--   * "Col. Salagua", "SALAGUA" y "salagua" son la misma colonia
--     (se comparan sin mayúsculas, sin acentos y sin "Col."/"Fracc.").
--   * vw_colonia_anuncios: colonias que tienen anuncios publicados,
--     para la lista de Filtros.
--
-- En DBeaver: pon el cursor en el bloque y pulsa Ctrl+Enter.
-- Si algo falla, no se cambia nada.
-- Al terminar, en la pestaña "Output" sale: "Migración 03 aplicada".
-- ============================================================
DO $$
BEGIN
    IF to_regclass('colonia') IS NULL THEN
        RAISE EXCEPTION 'Primero aplica la migración 02 (no existe la tabla colonia).';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'colonia' AND column_name = 't_clave_colonia') THEN
        RAISE EXCEPTION 'La migración 03 ya se había aplicado.';
    END IF;
    -- --------------------------------------------------------
    -- 1. Clave para comparar colonias: minúsculas, sin acentos (ñ = n),
    --    sin "Col.", "Colonia", "Fracc." ni "Fraccionamiento" al inicio,
    --    y con un solo espacio entre palabras.
    --    Ej.: "Col. Centro  Histórico" -> "centro historico"
    -- --------------------------------------------------------
    CREATE FUNCTION fn_clave_colonia(p_nombre TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS $fn$
        SELECT btrim(regexp_replace(
                   regexp_replace(
                       lower(translate(btrim(p_nombre), 'ÁÉÍÓÚÜÑáéíóúüñ', 'AEIOUUNaeiouun')),
                       '^(col\.?|colonia|fracc\.?|fraccionamiento)(\s+|$)', ''),
                   '\s+', ' ', 'g'))
    $fn$;
    ALTER TABLE colonia ADD COLUMN t_clave_colonia TEXT;
    UPDATE colonia SET t_clave_colonia = fn_clave_colonia(t_nombre_colonia);
    ALTER TABLE colonia
        ALTER COLUMN t_clave_colonia SET NOT NULL,
        ADD CONSTRAINT uq_colonia_clave UNIQUE (t_clave_colonia),
        ADD CONSTRAINT ck_colonia_clave CHECK (t_clave_colonia <> '');
    -- La clave reemplaza al índice anterior (que solo ignoraba mayúsculas)
    DROP INDEX uq_colonia_nombre;
    -- --------------------------------------------------------
    -- 2. fn_obtener_colonia('Col. Salagua') -> id de la colonia.
    --    Si no existe, la agrega. La API solo llama a esta función.
    -- --------------------------------------------------------
    CREATE FUNCTION fn_obtener_colonia(p_nombre TEXT) RETURNS INTEGER
    LANGUAGE plpgsql AS $fn$
    DECLARE
        v_clave TEXT := fn_clave_colonia(p_nombre);
        v_nombre TEXT;
        v_id INTEGER;
    BEGIN
        IF v_clave IS NULL OR v_clave = '' THEN
            RAISE EXCEPTION 'El nombre de la colonia está vacío';
        END IF;
        -- Nombre para mostrar: como lo escribió Google o el usuario, sin "Col." y sin espacios de más
        v_nombre := btrim(regexp_replace(
                        regexp_replace(btrim(p_nombre), '^(col\.?|colonia|fracc\.?|fraccionamiento)(\s+|$)', '', 'i'),
                        '\s+', ' ', 'g'));
        SELECT pke_id_colonia INTO v_id FROM colonia WHERE t_clave_colonia = v_clave;
        IF v_id IS NULL THEN
            INSERT INTO colonia (t_nombre_colonia, t_clave_colonia)
            VALUES (v_nombre, v_clave)
            ON CONFLICT (t_clave_colonia) DO NOTHING;  -- por si dos anuncios la agregan al mismo tiempo
            SELECT pke_id_colonia INTO v_id FROM colonia WHERE t_clave_colonia = v_clave;
        END IF;
        RETURN v_id;
    END
    $fn$;
    -- --------------------------------------------------------
    -- 3. Colonias con anuncios publicados y activos, por operación y tipo.
    --    Filtros: SELECT id_colonia, nombre_colonia, SUM(total_anuncios)
    --             FROM vw_colonia_anuncios WHERE modalidad = 'Venta' AND tipo = 'CASA'
    --             GROUP BY 1, 2 ORDER BY 2;
    -- --------------------------------------------------------
    CREATE VIEW vw_colonia_anuncios AS
    SELECT c.pke_id_colonia      AS id_colonia,
           c.t_nombre_colonia    AS nombre_colonia,
           m.t_nombre_modalidad  AS modalidad,
           pr.t_tipo_propiedad   AS tipo,
           COUNT(*)              AS total_anuncios
    FROM colonia c
    JOIN propiedad pr  ON pr.fke_id_colonia = c.pke_id_colonia
    JOIN publicacion p ON p.pke_id_publicacion = pr.fke_id_publicacion
    JOIN modalidad m   ON m.pke_id_modalidad = p.fke_id_modalidad
    WHERE p.t_estado_publicacion = 'PUBLICADA'
      AND p.b_estatus_publicacion
      AND c.b_estatus_colonia
    GROUP BY c.pke_id_colonia, c.t_nombre_colonia, m.t_nombre_modalidad, pr.t_tipo_propiedad;
    RAISE NOTICE 'Migración 03 aplicada';
END $$;
