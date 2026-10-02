# Rodo en tablet vertical y horizontal

Rodo es el asistente personal de Roberto dentro de RobGit. Su selector mantiene las cinco tareas informativas, el filtro de contexto, las validaciones y Firebase/Gemini. Rodo no recibe un motor Git ni un handle de repositorio; no puede ejecutar PULL, PUSH, SINCRONIZAR u operaciones sobre archivos.

## Implementación

- `RodoSession` conserva `Idle`, `Thinking`, `Completed`, `Warning` y `Error` en un `StateFlow`. `submit` establece `Thinking` antes de lanzar la petición; rechaza una segunda petición mientras haya otra activa. Cada consulta tiene un ID y las respuestas de generaciones anteriores se descartan. Cambiar de repositorio cancela la petición y borra su respuesta visible. Un `RodoViewModel` conserva la sesión al recrear la Activity por rotación.
- `AiAssistantDialog` es un selector `Dialog`/`Surface` con la paleta Petroleum, borde Ice, icono de Rodo, las cinco tareas, detalle adicional, validación y botones PREGUNTAR/CERRAR. El resultado vive en `RodoSession`, fuera del modal.
- `RodoUi` pinta la tarjeta y anima el WebP estático mediante Compose: flotación idle lenta, pulso al pensar y tres impulsos breves al completar. La única mascota de la tarjeta está junto al título, a 112 dp dentro de una caja de 144 dp que deja margen a la animación. El cuerpo contiene solo texto y, al pensar, un indicador de progreso. El ID de respuesta dispara también un rebote cuando el texto coincide con la respuesta anterior.
- `RodoLayoutPolicy` conserva el criterio de 600 dp para tablet y usa ancho mayor que alto para horizontal. Las dos orientaciones de tablet muestran la misma tarjeta persistente; solo `TABLET_PORTRAIT` usa la fila inferior de cuatro botones.
- La tarjeta de Rodo usa un marco propio con la paleta y el borde Ice de RobGit. El borde superior se dibuja con una abertura real bajo la cabecera. No se pinta ningún fondo detrás de la mascota transparente. Las demás tarjetas siguen usando `TitledFrame` sin cambios de diseño.
- El texto del prompt establece la identidad de Rodo y su tono en castellano de España; conserva las reglas informativas, de privacidad y de Git.

El asset [rodo_v0_1.webp](../app/src/main/res/drawable-nodpi/rodo_v0_1.webp) es una copia binaria idéntica de `C:\Users\andro\Downloads\rodo_v0.1.webp`. El original no se ha movido ni modificado. El recurso está en el APK como `res/drawable-nodpi-v4/rodo_v0_1.webp`.

El WebP es RGBA de 1024 × 1024, tiene las cuatro esquinas con alfa 0 y aproximadamente el 59 % de los píxeles totalmente transparentes. La zona rectangular visible provenía de `background(RobGitColors.Petroleum)` en la fila de cabecera de Compose, que tapaba el fondo real bajo esos píxeles. El asset no se modificó.

## Layouts

| Entorno | Estructura y respuesta |
| --- | --- |
| Móvil | Estructura anterior y ActionGrid de dos filas. El botón vuelve a mostrar ✦ IA. La respuesta se lee en el selector/modal de Rodo. |
| Tablet vertical | REPOSITORIO, ESTADO, tarjeta persistente de Rodo y una fila inferior de PULL/PUSH/SINCRONIZAR/IA. El selector se cierra al enviar y la tarjeta muestra carga, resultado o error. La tarjeta se ajusta al contenido, con límite máximo responsivo de 240-440 dp, y solo el texto largo tiene scroll interno. La fila de acciones permanece fuera de la zona desplazable, con `navigationBarsPadding`, el margen exterior de tablet y 18 dp adicionales abajo. En ventanas menores de 650 dp de alto, las tarjetas superiores pueden desplazarse, pero no la botonera. |
| Tablet horizontal | Se mantienen las dos columnas: REPOSITORIO, ESTADO y la misma tarjeta de Rodo en la izquierda; ActionGrid con ✦ IA en la derecha. El modal se cierra al preguntar y la respuesta pasa a la tarjeta. La tarjeta se ajusta al contenido, con máximo responsivo de 240-440 dp reservado tras las tarjetas superiores; su texto largo tiene scroll interno. Cada columna puede desplazarse independientemente si la ventana es demasiado baja, sin desplazar los controles por el crecimiento de Rodo. |

## Prueba física en Galaxy Tab S9+ vertical

Instalar `app/build/outputs/apk/debug/app-debug.apk` y abrir RobGit con un repositorio de prueba:

1. Comprobar que la tarjeta inicial de Rodo es compacta, que su única mascota está junto al título sin rozar la tarjeta superior, y que los cuatro botones cuadrados ocupan una sola fila con espacio respecto a la barra de navegación.
2. Pulsar ✦ IA, revisar las cinco tareas, el campo adicional y el aviso de análisis requerido. Enviar una pregunta personalizada. El modal debe cerrarse y la tarjeta mostrar «Rodo está pensando…» inmediatamente.
3. Al responder, comprobar el texto en la tarjeta y los tres impulsos de la mascota. Con una respuesta larga, desplazar el texto dentro de la tarjeta y confirmar que los botones inferiores siguen accesibles.
4. Probar un resultado no disponible y un fallo de proveedor; ambos deben aparecer en la tarjeta con estilo de advertencia.
5. Cambiar de repositorio durante una consulta y comprobar que la respuesta antigua no aparece en el nuevo repositorio.
6. Rotar a horizontal: confirmar las dos columnas, con Rodo debajo de las tarjetas de la izquierda y la respuesta conservada en su tarjeta. Hacer otra consulta en horizontal; el modal debe cerrarse y la respuesta debe aparecer en la tarjeta. Comprobar el fondo transparente del muñeco en ambas orientaciones. En móvil, comprobar el ActionGrid anterior de dos filas y la respuesta en el modal.
7. Verificar que editar un archivo sigue actualizando el estado local y que volver del background conserva el análisis remoto. Probar PULL/PUSH/SINCRONIZAR únicamente con el repositorio de pruebas.

Las pruebas unitarias y el build validan código y recursos. La composición final y las animaciones en hardware requieren esta prueba en la tablet física.

El estado de Rodo y una petición pendiente sobreviven a los cambios de configuración. Se cancelan al finalizar realmente la Activity o al seleccionar otro repositorio; no se guardan después de la muerte del proceso.

## Validación de escritorio

`./gradlew.bat testDebugUnitTest lint assembleDebug --console=plain`: BUILD SUCCESSFUL.

- 375 casos: 374 aprobados, 0 fallos, 0 errores y 1 omitido. La omisión es la prueba existente de creación de enlaces simbólicos en Windows.
- Lint: 0 errores y 30 avisos preexistentes.
- APK debug: `app/build/outputs/apk/debug/app-debug.apk`. El WebP empaquetado conserva el mismo SHA-256 que el original de Descargas.
- Informe HTML de tests: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Informe HTML de lint: `app/build/reports/lint-results-debug.html`.
