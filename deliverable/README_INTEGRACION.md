# NearHouse — publicación de anuncios (integración)

Este paquete integra los proyectos fuente de la API Ktor y de Android que se recibieron, conservando las tablas actuales de PostgreSQL. No crea una tabla duplicada `publicaciones`.

## Guía detallada

Para los pasos de clic por clic, desde respaldar los proyectos y ejecutar SQL hasta presionar **Crear anuncio +**, tomar fotografías, confirmar ubicación y verificar PostgreSQL, consulta `GUIA_PASO_A_PASO.md`.

## Qué se cambió

### API Ktor
- `POST /api/v1/publicaciones` (requiere `Authorization: Bearer <JWT>`): recibe `multipart/form-data`, un campo `datos` JSON y de 1 a 5 partes `fotos`.
- `GET /api/v1/publicaciones/mias` (JWT): devuelve únicamente los anuncios del usuario que sale del token.
- `GET /api/v1/colonias`: devuelve colonias activas para resolver su ID.
- `GET /api/v1/fotos/{id}`: devuelve los bytes de una fotografía.
- Guarda el anuncio y sus dependencias en una sola transacción: `publicacion`, `propiedad`, `inmueble` o `terreno`, y `foto`.
- Valida el rol del JWT, el catálogo de colonia/modalidad, el tipo de inmueble, campos, descripción, título y tamaño/tipo de las fotos.

### Android
- El botón de publicar deja de simular éxito y envía el anuncio a la API.
- La superficie se pide para casa/departamento y terreno porque `propiedad.e_superficie_propiedad` es obligatoria en PostgreSQL.
- Envía los archivos de fotos, no las rutas locales del teléfono.
- Muestra los errores devueltos por la API en la vista previa.
- `Mis anuncios` consulta el endpoint autenticado y muestra título, precio, ubicación y estado.

## Antes de desplegar

1. Haz commit o copia de seguridad de los proyectos originales.
2. Revisa que Railway apunte a la base de datos correcta y que tenga las tablas/columnas del resultado de `columns_202610221510.txt`.
3. Ejecuta `db/001_catalogos_publicaciones.sql` en PostgreSQL. Solo agrega `Venta` y `Renta` si no existen; no borra datos ni tablas.
4. Verifica que `colonia` tenga colonias activas. La app compara el nombre elegido con el catálogo real. Si una colonia no está registrada, la API rechaza el anuncio para no guardar una referencia inválida.
5. Despliega el proyecto `NearHouse_API-main` actualizado en Railway. Mantén las variables de entorno existentes; no subas secretos al repositorio.
6. Abre `NearHouse_MobileApp-main` en Android Studio y sincroniza Gradle. Genera e instala la app.
7. Inicia sesión con una cuenta cuyo rol JWT sea `Vendedor`, `Administrador` o `Super Administrador`. El backend rechaza a `Comprador`.

## Contrato del endpoint

`POST /api/v1/publicaciones`, multipart:
- `datos`: JSON con `titulo`, `descripcion`, `precio`, `modalidad` (`Venta`/`Renta`), `tipo` (`DEPARTAMENTO`/`CASA`/`TERRENO`), dirección, coordenadas, `idColonia`, `superficie`, características y `reglas`.
- `fotos`: 1 a 5 archivos JPEG/PNG/WebP, máximo 2 MB por foto.

La respuesta de creación es HTTP 201 e incluye `id`, `titulo`, `estado` y los IDs de las fotos. El estado inicial válido en el esquema es `EN_REVISION`.

## Prueba funcional recomendada

1. Consulta `GET https://nearhouseapi-production.up.railway.app/health` y confirma que la base de datos esté conectada.
2. En la app, inicia sesión como vendedor.
3. Crea un anuncio con título de hasta 60 caracteres, descripción de 30–1000 caracteres, precio, superficie, colonia registrada, ubicación confirmada y 1–5 fotos.
4. Confirma que la vista previa termine mostrando el diálogo de envío exitoso.
5. Abre `Mis anuncios` y comprueba que aparezca el anuncio con estado `En revisión`.
6. En PostgreSQL, verifica el registro en `publicacion`, su fila relacionada en `propiedad`, la tabla de detalle (`inmueble` o `terreno`) y las fotos en `foto`.

## Validación de compilación

Se intentó ejecutar Gradle en el entorno de preparación, pero el wrapper necesitaba descargar Gradle desde `services.gradle.org` y el entorno no tenía acceso DNS/red. Por ello, **no puedo afirmar que ambos proyectos hayan compilado aquí**. Sincroniza y compila en Android Studio/tu entorno con acceso a Gradle antes de desplegar. Si aparece un error, comparte el primer error de compilación completo y lo corregimos.
