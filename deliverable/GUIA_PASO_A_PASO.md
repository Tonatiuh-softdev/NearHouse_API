# NearHouse: guía extremadamente detallada para habilitar la publicación de anuncios

Esta guía está escrita para realizar la integración paso por paso. Los nombres de algunos botones de Railway o Android Studio pueden variar ligeramente según la versión.

## Antes de empezar: qué vas a necesitar

- La copia ZIP `NearHouse_publicaciones_integrado.zip` que contiene las carpetas `api`, `mobile` y `deliverable`.
- Acceso a tu proyecto Railway y a su PostgreSQL.
- Acceso al repositorio de la API y al repositorio Android, si vas a desplegar mediante GitHub.
- Android Studio instalado y una conexión a Internet para que Gradle descargue dependencias.
- Una cuenta de NearHouse con rol `Vendedor`, `Administrador` o `Super Administrador`. Una cuenta `Comprador` no puede crear anuncios con este endpoint.

**Importante:** no compartas capturas que muestren contraseñas, JWT, `DATABASE_URL`, tokens, claves privadas ni valores secretos de variables de entorno.

---

## Parte 1. Hacer copias de seguridad

### 1.1 Guardar los proyectos actuales

1. Localiza las carpetas actuales de `NearHouse_API` y `NearHouse_MobileApp` en tu computadora.
2. Cierra Android Studio y el editor que estés usando para la API. Esto evita que algún archivo quede guardándose mientras lo copias.
3. En el Explorador de archivos de Windows, haz clic derecho en la carpeta de la API.
4. Selecciona **Comprimir en archivo ZIP** o **Enviar a → Carpeta comprimida (zip)**, según tu versión de Windows.
5. Ponle un nombre reconocible, por ejemplo `NearHouse_API_antes_publicaciones.zip`.
6. Repite lo mismo con la carpeta Android y nómbrala `NearHouse_MobileApp_antes_publicaciones.zip`.
7. Guarda ambos ZIP fuera de las carpetas originales, por ejemplo en `Documentos\Respaldo_NearHouse`.

### 1.2 Respaldar PostgreSQL en Railway

1. Abre el sitio de Railway e inicia sesión.
2. Abre el proyecto donde está desplegada NearHouse.
3. Identifica el servicio PostgreSQL que usa la API. No confundas la base de datos de producción con otra base de pruebas.
4. Abre el servicio PostgreSQL y busca la opción de conexión o consulta de datos (el nombre exacto cambia según la interfaz).
5. Si Railway te ofrece una opción de backup/snapshot, crea uno y espera a que termine.
6. Si no tienes esa opción disponible, utiliza un cliente PostgreSQL que ya tengas configurado, como `pg_dump`, para exportar un respaldo. No pegues las credenciales en el chat ni las guardes en el repositorio.
7. Comprueba que el respaldo exista y que su archivo no mida 0 KB.

**No continúes con cambios de esquema si no tienes una forma de recuperar la base de datos.**

---

## Parte 2. Descomprimir y reconocer el paquete

1. Descarga `NearHouse_publicaciones_integrado.zip`.
2. Abre el Explorador de archivos y entra a la carpeta donde se descargó (normalmente `Descargas`).
3. Haz clic derecho sobre el ZIP y selecciona **Extraer todo…**.
4. Elige una carpeta sencilla, por ejemplo `C:\NearHouse_Integracion`, y presiona **Extraer**.
5. Abre la carpeta extraída. Debes ver tres carpetas principales:
   - `api`: contiene el proyecto Ktor.
   - `mobile`: contiene el proyecto Android.
   - `deliverable`: contiene el SQL y esta guía.
6. No copies la carpeta `api` dentro de `mobile` ni al revés. Son proyectos separados.
7. Mantén el ZIP original sin modificar como respaldo.

---

## Parte 3. Revisar PostgreSQL sin modificarlo

### 3.1 Confirmar que estás conectado a la base correcta

1. En Railway, abre el proyecto correcto.
2. Selecciona el servicio PostgreSQL que está conectado a la API de NearHouse.
3. Abre el editor de consultas o conéctate con tu cliente PostgreSQL habitual.
4. Si el editor permite seleccionar una base de datos, selecciona la misma que utiliza la API.
5. Antes de ejecutar cambios, corre esta consulta de solo lectura:

```sql
SELECT current_database(), current_schema(), now();
```

6. Confirma que la base de datos corresponde al entorno correcto. Si no estás seguro, detente y verifica la configuración de Railway antes de seguir.

### 3.2 Verificar que existen las tablas

Ejecuta esta consulta de solo lectura:

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN ('publicacion','propiedad','inmueble','terreno','foto','colonia','modalidad','usuario')
ORDER BY table_name;
```

Deben aparecer las tablas de la lista que existan en tu esquema. Si falta una tabla requerida por la API, **no inventes una tabla nueva ni ejecutes el SQL completo de creación desde cero**. Detente y revisa el esquema antes de continuar.

### 3.3 Verificar modalidades Venta y Renta

1. Abre el archivo `deliverable/001_catalogos_publicaciones.sql` con un editor de texto.
2. Selecciona y copia el contenido SQL completo.
3. Vuelve al editor SQL de la base de datos correcta.
4. Pega el contenido en una consulta nueva.
5. Revisa que estás en el entorno correcto y presiona el botón para ejecutar la consulta (suele ser **Run**, **Execute** o un icono de triángulo).
6. Espera el mensaje de finalización. El script inicia una transacción y solo inserta `Venta` y `Renta` si no hay un registro equivalente; no elimina tablas ni filas.
7. En una consulta aparte, ejecuta:

```sql
SELECT pke_id_modalidad, t_nombre_modalidad, b_estatus_modalidad
FROM public.modalidad
ORDER BY t_nombre_modalidad;
```

8. Confirma que aparecen `Venta` y `Renta`, y que ambos registros están activos (`true`). Si ya existen pero están inactivos, no los actives a ciegas: revisa por qué se desactivaron.

### 3.4 Verificar colonias activas

Ejecuta:

```sql
SELECT pke_id_colonia, t_nombre_colonia, b_estatus_colonia
FROM public.colonia
ORDER BY t_nombre_colonia
LIMIT 100;
```

1. Comprueba que hay filas.
2. Comprueba que las colonias que esperas seleccionar tienen `b_estatus_colonia = true`.
3. Si no hay colonias activas, la API no podrá relacionar una publicación con una colonia válida. No uses un ID inventado. Debes cargar las colonias reales autorizadas para tu aplicación siguiendo el procedimiento de tu proyecto.
4. El formulario de Android puede mostrar una lista local, pero la API valida contra la tabla `colonia`. El nombre debe coincidir con el catálogo del servidor.

### 3.5 Confirmar relaciones y columnas clave

Tu resultado de metadatos indica que `foto.by_imagen_foto` es `bytea`; las fotos se guardarán como bytes, no como rutas locales del teléfono. También indica que `propiedad.fke_id_colonia` relaciona la propiedad con `colonia` y que `publicacion` tiene columnas de estado y revisión.

No cambies manualmente tipos de columna ni borres restricciones para solucionar errores. Las llaves foráneas evitan que un anuncio apunte a un usuario, modalidad o colonia inexistente.

---

## Parte 4. Abrir y compilar la API en Android Studio no; en IntelliJ IDEA o tu IDE de Kotlin

La API es un proyecto Kotlin/Ktor. Android Studio también puede abrir proyectos Gradle, pero IntelliJ IDEA suele ser más cómodo para el backend. Puedes usar el IDE que ya utilizas.

### 4.1 Abrir la carpeta correcta

1. Inicia el IDE.
2. En la pantalla inicial, presiona **Open** (Abrir).
3. Navega a la carpeta que extrajiste y abre `api/NearHouse_API-main`.
4. Si aparece una pregunta sobre confiar en el proyecto, confirma solo si reconoces el ZIP que descargaste.
5. Si pregunta si deseas abrir como proyecto Gradle, acepta.
6. Espera a que termine la sincronización inicial. La primera vez puede tardar porque Gradle descarga dependencias.

### 4.2 Revisar variables de entorno sin mostrar secretos

1. En Railway, abre el servicio de la API y entra a **Variables**.
2. Confirma que las variables de conexión a PostgreSQL, JWT y demás configuraciones requeridas siguen configuradas.
3. No borres ni reemplaces variables que ya funcionaban.
4. No pegues sus valores en el código fuente, en archivos versionados ni en capturas públicas.
5. El paquete conserva la configuración del proyecto; el despliegue debe seguir usando las variables de entorno existentes en Railway.

### 4.3 Compilar la API localmente

1. En el IDE, abre la ventana **Terminal** (normalmente en la parte inferior).
2. Confirma que la terminal está en la carpeta `api/NearHouse_API-main`, donde se encuentra `gradlew.bat`.
3. En Windows, ejecuta:

```powershell
.\gradlew.bat build
```

4. Presiona Enter y espera.
5. Si termina con `BUILD SUCCESSFUL`, la compilación local terminó correctamente.
6. Si termina con `BUILD FAILED`, no despliegues todavía. Copia el primer error que menciona un archivo y una línea, además del bloque `What went wrong`, y úsalo para corregir el problema.
7. Si el error dice que no puede descargar Gradle o una dependencia, verifica conexión a Internet, proxy o firewall y vuelve a ejecutar el comando.

### 4.4 Qué endpoints agrega la integración

La API integrada añade:

- `GET /api/v1/colonias`: lista colonias activas.
- `POST /api/v1/publicaciones`: crea un anuncio con datos y archivos de imagen. Requiere JWT.
- `GET /api/v1/publicaciones/mias`: devuelve solo los anuncios del usuario autenticado. Requiere JWT.
- `GET /api/v1/fotos/{id}`: entrega una imagen guardada.

La creación se hace en una transacción para evitar que quede una publicación incompleta si falla el guardado de la propiedad o las fotos.

### 4.5 Desplegar la API a Railway mediante GitHub

Sigue esta parte solo si el servicio Railway ya despliega desde el repositorio Git de la API.

1. No sobrescribas tu repositorio original sin respaldo.
2. Abre el proyecto API integrado y revisa los cambios.
3. Si trabajas con Git, crea una rama nueva para esta funcionalidad (por ejemplo `feature/publicaciones`).
4. Agrega los archivos modificados y nuevos al commit. En GitHub Desktop, revisa la lista de cambios, escribe un mensaje como `Integrar creación de publicaciones` y presiona **Commit**.
5. Presiona **Push origin** para subir la rama.
6. Abre GitHub en el navegador y confirma que el commit aparece en la rama que esperabas.
7. Abre Railway y selecciona el servicio de la API.
8. En **Settings** o la sección de despliegue, confirma que el repositorio y la rama configurados son los correctos.
9. Si Railway despliega automáticamente al recibir cambios en esa rama, espera a que empiece el despliegue. Si no, usa el control de despliegue manual que ofrece tu servicio.
10. Abre la sección **Deployments** y entra al despliegue más reciente.
11. Espera a que el estado sea exitoso. Si falla, abre los logs y busca el primer error de compilación o de arranque.
12. No borres ni recrees el servicio PostgreSQL.

**No hagas push directo a la rama de producción si el equipo usa pull requests o una rama protegida.** Sigue el flujo del repositorio del equipo.

### 4.6 Probar que la API responde

1. Abre un navegador.
2. Escribe `https://nearhouseapi-production.up.railway.app/health` en la barra de direcciones y presiona Enter.
3. La respuesta esperada es un JSON con estado `ok` y base de datos `conectada`.
4. Si recibes `503`, estado `error` o un error de servidor, revisa los logs de Railway y la conexión a PostgreSQL antes de probar Android.
5. Que `/health` responda correctamente solo confirma que la API y la conexión básica a la base de datos funcionan; no prueba todavía la creación de anuncios.

---

## Parte 5. Abrir y compilar la app Android

### 5.1 Abrir el proyecto móvil correcto

1. Inicia Android Studio.
2. Si hay otro proyecto abierto, selecciona **File → Close Project** para volver a la pantalla inicial, o usa **File → Open**.
3. Presiona **Open**.
4. Selecciona la carpeta `mobile/NearHouse_MobileApp-main`. Debes seleccionar la carpeta que contiene `settings.gradle.kts`, no solamente `app`.
5. Presiona **OK/Open**.
6. Si Android Studio pregunta si deseas confiar en el proyecto, confirma si reconoces el paquete.
7. Espera a que aparezca la barra de sincronización de Gradle y termine.
8. Si aparece una ventana que dice **Sync Now**, presiónala.
9. Si Android Studio pregunta si quieres actualizar el plugin de Gradle automáticamente, no aceptes una actualización mayor a ciegas. Primero intenta compilar con las versiones que trae el proyecto.

### 5.2 Seleccionar el JDK

1. Abre **File → Settings** en Windows.
2. En el panel izquierdo, busca **Build, Execution, Deployment → Build Tools → Gradle**.
3. Busca **Gradle JDK**.
4. Selecciona el JDK que el proyecto ya requiere y que esté instalado. Si el proyecto especifica Java 21, selecciona JDK 21.
5. Presiona **Apply** y después **OK**.
6. Selecciona **File → Sync Project with Gradle Files** (o el icono de sincronización de Gradle).
7. Espera a que finalice.

### 5.3 Compilar sin instalar todavía

1. En la barra superior, selecciona la configuración `app` si aparece.
2. Abre **Build → Make Project** o **Build → Assemble Project**, según la versión de Android Studio.
3. Espera a que termine.
4. Si aparece `BUILD SUCCESSFUL`, sigue a la instalación.
5. Si aparece `BUILD FAILED`, abre la ventana **Build** de la parte inferior.
6. Expande el primer error rojo; no te quedes solo con el último mensaje genérico.
7. Copia el mensaje completo que menciona el archivo y la línea. No cambies muchas cosas a la vez.

### 5.4 Instalar la app en un teléfono

**Opción A: teléfono Android por USB**

1. En el teléfono, abre **Ajustes → Acerca del teléfono**.
2. Busca **Número de compilación** y tócalo siete veces, si aún no están activadas las opciones de desarrollador.
3. Regresa a Ajustes, abre **Opciones de desarrollador** y activa **Depuración USB**.
4. Conecta el teléfono a la computadora con un cable USB que permita transferencia de datos.
5. Si el teléfono pregunta si confías en esta computadora para depuración, revisa el equipo y presiona **Permitir**.
6. En Android Studio, abre el selector de dispositivos de la barra superior y elige tu teléfono.
7. Presiona el botón verde **Run ▶**.
8. Espera a que Android Studio instale y abra NearHouse.

**Opción B: emulador**

1. Abre **Tools → Device Manager**.
2. Presiona **+** o **Create Device**.
3. Selecciona un modelo de teléfono, presiona **Next**, selecciona una imagen de Android instalada y termina con **Finish**.
4. Presiona el botón triangular para iniciar el emulador.
5. Cuando termine de arrancar, selecciónalo en el selector de dispositivos de Android Studio.
6. Presiona **Run ▶** para instalar la app.

La pantalla de crear anuncio usa cámara y ubicación; un teléfono físico suele ser más práctico para probarlas. Concede permisos cuando Android los solicite.

---

## Parte 6. Iniciar sesión y llegar al botón «Crear anuncio +»

1. Abre NearHouse en el teléfono.
2. Si aparece la pantalla de inicio de sesión, escribe el correo y la contraseña de tu cuenta de prueba.
3. Presiona **Iniciar sesión**.
4. Espera a que cargue la pantalla principal.
5. Busca la barra de navegación inferior.
6. Presiona la pestaña **Perfil** o el icono de persona, según cómo se muestre en tu versión.
7. En la pantalla de perfil, busca la opción **Mis anuncios**. Si no está a la vista, revisa las opciones del perfil o el menú de la cuenta.
8. Presiona **Mis anuncios**.
9. En la pantalla Mis anuncios, revisa la tarjeta de perfil y los datos de la cuenta.
10. Presiona el botón **Crear anuncio +**. El signo `+` está dentro del botón, a la derecha del texto; no es necesario buscar un botón flotante separado.
11. Se abrirá el flujo de creación en el **Paso 1 de 4**.

Si el botón no aparece o la app regresa al perfil, comprueba el rol de la cuenta. El flujo está limitado a `Vendedor`, `Administrador` y `Super Administrador`; una cuenta `Comprador` no puede entrar al flujo de creación.

---

## Parte 7. Paso 1 de 4: definir la publicación

La pantalla tiene el encabezado **Crear anuncio** y el título **Define el anuncio**. Desplázate hacia abajo si no ves todos los campos.

### 7.1 Seleccionar operación

1. En la sección **Operación**, presiona **Comprar** si vas a vender la propiedad.
2. Presiona **Rentar** si vas a ofrecerla en alquiler.
3. Selecciona solo una de las dos opciones.

En la base de datos, la operación se guardará como modalidad `Venta` o `Renta`.

### 7.2 Seleccionar tipo de inmueble

1. Busca **Tipo de inmueble**.
2. Selecciona **Departamento**, **Casa** o **Terreno**.
3. Espera a que aparezcan los campos correspondientes a esa selección.

### 7.3 Completar precio y superficie

1. En **Precio total (MXN)**, escribe el precio completo si elegiste Comprar/Venta.
2. Si elegiste Rentar, escribe el importe de la renta mensual.
3. Escribe solo números en el campo de precio; no incluyas el signo `$` si el teclado no lo requiere.
4. En **Superficie en m²**, escribe la superficie numérica del inmueble o terreno.
5. El precio y la superficie deben ser mayores que cero. La superficie es necesaria porque la columna de PostgreSQL es obligatoria.

### 7.4 Seleccionar colonia

1. Busca el campo de colonia/ubicación que aparece entre los datos principales.
2. Presiónalo para abrir las opciones.
3. Selecciona la colonia real que corresponda a la propiedad.
4. No escribas una colonia inventada ni elijas una que no corresponda a la dirección.

La API valida que la colonia exista y esté activa en PostgreSQL. Si no aparece una colonia que necesitas, detén la prueba y pide que se agregue al catálogo autorizado; no intentes solucionar el problema inventando un ID.

### 7.5 Completar características

Los campos dependen del tipo seleccionado:

- Para casa o departamento, selecciona el número de recámaras, baños y estacionamientos cuando se soliciten.
- Si aparece el campo **Piso**, elige el piso real. Para planta baja, selecciona **Planta baja**.
- Para casa, completa **Niveles de la casa** si aparece.
- Activa o desactiva **Amueblado**, **Mascotas permitidas**, **Elevador** y **Jardín/Patio** según corresponda.
- Para terreno, selecciona el uso de suelo que corresponda, como habitacional o agrícola si esas son las opciones ofrecidas.

No marques servicios o características que no existan en la propiedad.

### 7.6 Continuar al paso 2

1. Desplázate al final de la pantalla.
2. Busca el botón **Continuar**.
3. Si está deshabilitado, revisa si falta operación, tipo, precio, superficie o colonia.
4. Completa el campo faltante.
5. Cuando el botón se habilite, presiónalo una vez.
6. Se abrirá el **Paso 2 de 4**, dedicado a fotos y ubicación.

---

## Parte 8. Paso 2 de 4: tomar fotos y confirmar ubicación

Esta versión del flujo utiliza la cámara para tomar las fotos; no presupongas que puedes seleccionar imágenes existentes de la galería.

### 8.1 Dar permisos

1. Presiona el control para tomar/agregar una foto.
2. Si Android pregunta si NearHouse puede usar la cámara, presiona **Mientras se usa la aplicación** o la opción equivalente.
3. Si Android pregunta por la ubicación, concede permiso de ubicación precisa o aproximada según las opciones disponibles. La ubicación ayuda a situar el punto inicial en el mapa.
4. Si denegaste permisos anteriormente, abre **Ajustes del teléfono → Aplicaciones → NearHouse → Permisos** y habilita Cámara y Ubicación. Regresa a la app.

### 8.2 Tomar de 1 a 5 fotografías

1. Presiona el botón de cámara o el recuadro para agregar foto.
2. Encuadra una imagen clara y real de la propiedad.
3. Presiona el disparador de la cámara del teléfono.
4. Confirma la foto con la marca de aceptación que muestre la cámara. Si cancelas, la foto no se agrega.
5. Al regresar a NearHouse, verifica que la imagen aparezca en la lista o vista previa de fotos.
6. Repite el proceso para añadir otras vistas.
7. Añade entre una y cinco fotos. Cada imagen debe ser JPEG, PNG o WebP y pesar como máximo 2 MB para que la API la acepte.
8. Si agregaste una imagen equivocada, usa el control de eliminar/cerrar que aparece junto a esa foto, si está disponible, y toma otra.

Consejo: empieza con la fachada o la habitación más representativa y luego agrega interiores. No fotografíes documentos, rostros ni datos personales de terceros sin autorización.

### 8.3 Revisar el mapa y el marcador

1. Después de tomar la primera foto, la app puede intentar obtener la ubicación del teléfono. Esto es solo un punto inicial; no significa que ya sea la ubicación exacta de la propiedad.
2. Busca el mapa que aparece en esta pantalla.
3. Comprueba si el marcador está situado sobre la dirección correcta.
4. Si el marcador no está en el lugar correcto, activa el modo de edición del mapa si la pantalla lo ofrece.
5. Arrastra o mueve el mapa para colocar el marcador sobre la propiedad; no lo dejes en la ubicación actual del teléfono si la casa está en otro lugar.
6. Espera a que la app resuelva la dirección.
7. Si solicita calle, número o colonia manualmente, escribe los datos reales de la propiedad.
8. Selecciona una colonia que coincida con el catálogo disponible.
9. Revisa que calle, número, código postal y colonia correspondan a la propiedad.
10. Presiona el control para confirmar la ubicación, si aparece.
11. Verifica que la pantalla muestre la ubicación como confirmada y que no haya un aviso de ubicación pendiente.

Si no puedes mover el marcador o el mapa no carga, no confirmes una ubicación falsa. Revisa conexión, permisos y la clave/configuración del servicio de mapas del proyecto.

### 8.4 Continuar al paso 3

1. Desplázate al final de la pantalla.
2. Busca **Continuar**.
3. Si está deshabilitado, revisa los avisos de la pantalla. Debe haber al menos una foto, una ubicación válida, colonia y ubicación confirmada.
4. Corrige lo que falte.
5. Presiona **Continuar** para abrir el **Paso 3 de 4**.

---

## Parte 9. Paso 3 de 4: título, descripción y reglas

1. Lee el encabezado para confirmar que estás en el paso de contenido del anuncio.
2. Presiona el campo **Título**.
3. Escribe un título descriptivo, por ejemplo `Casa de 2 recámaras cerca del centro`.
4. Mantén el título en un máximo de 60 caracteres.
5. Presiona el campo **Descripción**.
6. Explica el estado y las características reales de la propiedad. La descripción debe tener entre 30 y 1000 caracteres.
7. Evita publicar teléfonos, contraseñas o información personal que no quieras hacer pública.
8. Si aparece un campo **Reglas**, escribe las condiciones relevantes de renta/uso. No inventes restricciones legales ni datos que no correspondan.
9. Desplázate al final y revisa los mensajes de validación.
10. Cuando el botón **Continuar** esté habilitado, presiónalo.
11. Se abrirá el **Paso 4 de 4**, la revisión de los datos.

Si no te permite continuar, revisa el título y la descripción: no deben estar vacíos y deben respetar las longitudes indicadas.

---

## Parte 10. Paso 4 de 4: revisar y obtener la vista previa

1. Revisa el resumen que aparece en pantalla.
2. Comprueba la operación (venta o renta), el tipo de inmueble, el precio y la superficie.
3. Comprueba la dirección, la colonia y la ubicación del mapa.
4. Revisa que las fotos correspondan a la propiedad anunciada.
5. Revisa título, descripción y reglas.
6. Si ves un dato incorrecto, presiona el control de regreso o **Editar datos**/**Editar contenido** que corresponda. Corrige el dato y vuelve al resumen.
7. Cuando todo sea correcto, presiona **Continuar** para entrar a la vista previa final.
8. Lee el anuncio como lo vería otra persona.
9. En esta etapa, no asumas que el anuncio ya está guardado hasta que confirmes el envío en el siguiente paso.

---

## Parte 11. Enviar el anuncio al servidor

1. En la vista previa, localiza el botón de envío/publicación que aparece al final de la pantalla.
2. Presiónalo una sola vez.
3. No cierres la app ni cambies de pantalla mientras aparece el indicador de envío.
4. La app enviará un formulario `multipart/form-data`: el JSON de datos y los archivos reales de las fotografías.
5. La API comprueba el token de inicio de sesión, rol, precio, superficie, colonia, modalidad, tipo de propiedad y fotos.
6. Si todo es válido, la API crea los registros relacionados en PostgreSQL y devuelve HTTP 201.
7. La publicación empieza con estado **En revisión** (`EN_REVISION`); eso significa que se recibió para revisión, no que ya esté aprobada ni visible públicamente.
8. Si aparece el diálogo de éxito, lee el mensaje y presiona **Aceptar** o el botón de confirmación.
9. La app debe regresar a **Mis anuncios**.

### Si aparece un error al enviar

- **401 / sesión vencida:** vuelve a iniciar sesión y repite la prueba.
- **403 / rol no permitido:** comprueba que el usuario tenga el rol correcto en el sistema; no cambies el rol directamente sin autorización.
- **Colonia inválida:** vuelve al paso 1 o 2 y elige una colonia activa del catálogo del servidor.
- **Foto demasiado grande o tipo no admitido:** toma una foto nueva en formato compatible y asegúrate de que pese menos de 2 MB.
- **Error de red o timeout:** comprueba Internet y el endpoint `/health`; luego revisa los logs de Railway.
- **Error 500:** no repitas muchas veces seguidas. Revisa los logs de la API para identificar qué operación de base de datos falló.

No publiques datos ficticios en producción para probar. Usa una propiedad de prueba autorizada.

---

## Parte 12. Comprobar «Mis anuncios»

1. Después de aceptar el mensaje de éxito, espera a que aparezca la pantalla **Mis anuncios**.
2. Si ya estabas en esa pantalla, usa el control de recarga que tenga la app, si existe; si no, sal de la pantalla y vuelve a entrar.
3. Busca el anuncio por su título.
4. Comprueba que el precio y la ubicación sean los que capturaste.
5. Comprueba que el estado sea **En revisión**.
6. Si el anuncio aparece, la app y el endpoint de consulta están comunicándose correctamente.
7. Si no aparece, no crees el anuncio repetidamente. Sigue las verificaciones de la siguiente sección.

---

## Parte 13. Verificar directamente en PostgreSQL

Ejecuta las consultas siguientes en la base de datos de Railway. Son consultas de lectura.

### 13.1 Ver las publicaciones más recientes

```sql
SELECT pke_id_publicacion,
       fke_id_usuario,
       fke_id_modalidad,
       t_nombre_publicacion,
       e_precio_publicacion,
       t_estado_publicacion,
       fh_fecha_publicacion
FROM public.publicacion
ORDER BY fh_fecha_publicacion DESC NULLS LAST
LIMIT 20;
```

Busca el título de la prueba. Anota el `pke_id_publicacion`, que es el identificador generado por la base.

### 13.2 Ver la propiedad relacionada

Sustituye `123` por el ID real que encontraste:

```sql
SELECT pr.pke_id_propiedad,
       pr.fke_id_publicacion,
       pr.t_tipo_propiedad,
       pr.t_calle_propiedad,
       pr.t_numero_propiedad,
       pr.fke_id_colonia,
       pr.e_superficie_propiedad,
       pr.t_codigo_postal_propiedad,
       pr.n_latitud_propiedad,
       pr.n_longitud_propiedad
FROM public.propiedad pr
WHERE pr.fke_id_publicacion = 123;
```

### 13.3 Ver fotos asociadas

Usa el ID de propiedad que devolvió la consulta anterior:

```sql
SELECT pke_id_foto,
       fke_id_propiedad,
       e_orden_visibilidad_foto,
       t_tipo_imagen_foto,
       octet_length(by_imagen_foto) AS bytes_imagen
FROM public.foto
WHERE fke_id_propiedad = 456
ORDER BY e_orden_visibilidad_foto;
```

Cambia `456` por el `pke_id_propiedad` real. `bytes_imagen` debe ser mayor que cero.

### 13.4 Ver el detalle de casa/departamento o terreno

Para una casa o departamento:

```sql
SELECT * FROM public.inmueble WHERE fke_id_propiedad = 456;
```

Para un terreno:

```sql
SELECT * FROM public.terreno WHERE fke_id_propiedad = 456;
```

Usa el ID real de propiedad. No ejecutes `INSERT`, `UPDATE` o `DELETE` manuales para corregir una prueba sin entender primero el error y sin tener respaldo.

---

## Parte 14. Si algo falla: qué información recopilar

Para poder diagnosticar sin adivinar, reúne solo la información relevante:

1. Indica en qué paso estabas (por ejemplo, `Paso 2, al confirmar ubicación`).
2. Copia el texto exacto del error o toma una captura que no incluya datos secretos.
3. Si falla Android Studio, abre **Build** y copia el primer error completo y el bloque `What went wrong`.
4. Si falla Railway, abre el despliegue más reciente y copia el mensaje de error pertinente de los logs. Oculta tokens, contraseñas, cadenas de conexión y valores secretos.
5. Si falla la publicación en la app, indica el código HTTP si se muestra (400, 401, 403, 500, etc.) y el texto del mensaje.
6. Si la app dice que se publicó pero no aparece en Mis anuncios, ejecuta las consultas de lectura de la Parte 13 y comparte solo las filas pertinentes, ocultando datos personales.

No compartas el archivo completo de variables de entorno, contraseñas ni tokens JWT.

---

## Lista final de comprobación

- [ ] Copia de seguridad de los dos proyectos.
- [ ] Respaldo de PostgreSQL confirmado.
- [ ] `Venta` y `Renta` existen y están activas.
- [ ] Hay colonias activas válidas en PostgreSQL.
- [ ] La API compila localmente.
- [ ] El despliegue de Railway termina correctamente.
- [ ] `/health` indica base de datos conectada.
- [ ] Android Studio sincroniza Gradle y compila.
- [ ] La app se instala y permite iniciar sesión.
- [ ] Se abre Perfil → Mis anuncios → **Crear anuncio +**.
- [ ] Se completa cada paso y se envía una publicación de prueba autorizada.
- [ ] El anuncio aparece en Mis anuncios como **En revisión**.
- [ ] PostgreSQL muestra publicación, propiedad, detalle y fotos relacionados.

## Nota sobre validación

Esta guía describe el flujo implementado en el paquete, pero no afirma que el proyecto haya sido compilado ni que el despliegue se haya realizado en tu cuenta de Railway. Ejecuta la compilación y las pruebas en tu entorno. Si falla, el primer error completo es el dato más útil para corregirlo.
