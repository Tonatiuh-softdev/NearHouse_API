-- NearHouse: preparación segura del catálogo para publicar anuncios.
-- No elimina ni recrea tablas. Ejecutar en Railway/PostgreSQL una sola vez o repetidamente.
BEGIN;

INSERT INTO public.modalidad (t_nombre_modalidad, b_estatus_modalidad)
SELECT v.nombre, TRUE
FROM (VALUES ('Venta'), ('Renta')) AS v(nombre)
WHERE NOT EXISTS (
    SELECT 1 FROM public.modalidad m
    WHERE lower(trim(m.t_nombre_modalidad)) = lower(v.nombre)
);

COMMIT;

-- Verificación posterior (ejecutar por separado si tu cliente no muestra resultados
-- después de un script con BEGIN/COMMIT):
-- SELECT pke_id_modalidad, t_nombre_modalidad, b_estatus_modalidad
-- FROM public.modalidad ORDER BY t_nombre_modalidad;
-- SELECT pke_id_colonia, t_nombre_colonia, b_estatus_colonia
-- FROM public.colonia ORDER BY t_nombre_colonia LIMIT 30;
