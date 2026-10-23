-- ============================================================
-- 07: planes de la pantalla "Elige tu plan"
-- Requiere la migración 06. Se corre UNA sola vez.
--
--   Básico       $99/mes   1 publicación activa     visibilidad 1 (estándar)   1er mes gratis
--   Profesional  $249/mes  3 publicaciones activas  visibilidad 2 (destacada)  "Más popular"
--   Premium      $449/mes  ilimitadas               visibilidad 3 (máxima)
--
-- Precios en pesos mexicanos. "mes" = 30 días.
--
-- En DBeaver: pon el cursor en el bloque y pulsa Ctrl+Enter.
-- Al terminar, en la pestaña "Output" sale: "Planes agregados: 3".
-- ============================================================
DO $$
DECLARE
    v_total INTEGER;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'suscripcion' AND column_name = 'e_precio_suscripcion') THEN
        RAISE EXCEPTION 'Primero aplica la migración 06.';
    END IF;
    IF EXISTS (SELECT 1 FROM suscripcion
               WHERE t_nombre_suscripcion IN ('Básico', 'Profesional', 'Premium')) THEN
        RAISE EXCEPTION 'Los planes ya se habían agregado.';
    END IF;
    INSERT INTO suscripcion (
        t_nombre_suscripcion,
        e_precio_suscripcion,
        e_duracion_dias_suscripcion,
        e_limite_anuncios_suscripcion,
        e_nivel_visibilidad_suscripcion,
        e_dias_prueba_suscripcion,
        b_destacado_suscripcion
    ) VALUES
        ('Básico',      99.00, 30, 1,    1, 30, FALSE),
        ('Profesional', 249.00, 30, 3,   2, 0,  TRUE),
        ('Premium',     449.00, 30, NULL, 3, 0, FALSE);
    GET DIAGNOSTICS v_total = ROW_COUNT;
    RAISE NOTICE 'Planes agregados: %', v_total;
END $$;
