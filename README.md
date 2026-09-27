# RobGit — Git seguro con una interfaz humana

La pantalla principal de RobGit presenta el estado del repositorio en castellano claro, sin exponer HEAD, refs, ahead/behind ni otros conceptos técnicos. Utiliza una identidad propia azul petróleo y blanco hielo, ofrece PULL, PUSH y SINCRONIZAR mediante el motor seguro existente y analiza automáticamente al abrir la app, al volver realmente desde segundo plano, al cambiar de repositorio y después de cada operación. La rotación conserva el estado y no provoca una consulta remota nueva.

## Mis repositorios

El selector permite añadir y elegir repositorios GitHub con nombre, URL HTTPS y rama configurables. Un registro persistente en el almacenamiento privado de la app guarda la lista, la selección y la ubicación de cada workspace, sin credenciales. La primera apertura incorpora `Robgit.pruebas` y reutiliza `filesDir/repos/robgit-pruebas` si ya existe. Los registros anteriores a Hito 10C se interpretan como `APP_PRIVATE` sin mover sus archivos. Cada repositorio nuevo utiliza `SHARED_DOCUMENTS` bajo `Documents/RobGit/<nombre-seguro>-<id-corto>`.

En Ajustes se pueden consultar las configuraciones, su ubicación y quitarlas de la lista; esta acción conserva todos los archivos locales, tanto privados como compartidos. Los repositorios existentes permanecen privados hasta que el usuario elija **MOVER AL WORKSPACE COMPARTIDO**. El acceso compartido utiliza `MANAGE_EXTERNAL_STORAGE` y archivos `java.io.File`; no se utiliza SAF. Si se revoca el permiso, RobGit bloquea ese repositorio y ofrece **CONCEDER ACCESO** sin volver a utilizar el backup privado.

## Workspace compartido y migración

La raíz de producción es `Documents/RobGit/`. Los repositorios nuevos se preparan allí mediante un clone a una carpeta temporal, verificación y traslado a su carpeta definitiva. No se clona encima de una carpeta existente. El diagnóstico **PRUEBA WORKSPACE COMPARTIDO** del Hito 10B sigue disponible en Ajustes y conserva su carpeta de prueba independiente.

La migración de un repositorio antiguo es manual. RobGit muestra una confirmación y copia todos los bytes del repositorio privado, incluidos `.git`, archivos ocultos, trabajo sin commit, archivos no rastreados y commits locales. Rechaza enlaces simbólicos. Verifica estructura, tamaños y SHA-256 de todos los archivos, además de HEAD, rama, origin, tracking, relación de commits y estado Git. Después renombra el temporal, repite la verificación, actualiza `repositories.properties` mediante escritura temporal sincronizada y reemplazo atómico cuando el sistema lo permite, abre el nuevo workspace y ejecuta un análisis. Un journal privado en `filesDir/migrations/` registra las fases y permite reanudar explícitamente una interrupción. El directorio privado original permanece intacto como backup; RobGit no lo elimina ni lo usa como fallback automático.

El menú superior reúne el análisis manual, los detalles técnicos y los ajustes de diagnóstico. El PAT se solicita al subir o cuando GitHub lo requiere para preparar, analizar, descargar, sincronizar o completar una migración; el mensaje de cambio solo aparece para una subida. El token vive exclusivamente en memoria. La autenticación moderna de GitHub queda pendiente para el Hito 11. La interfaz del Asistente RobGit ya permite escoger una orden y escribir una pregunta, pero **la IA todavía no está conectada** y no realiza ninguna llamada de red.

## Diseño adaptable

En móviles la aplicación funciona solo en vertical. Las tablets admiten vertical y horizontal; en apaisado, el selector y el estado ocupan la zona izquierda y la cuadrícula de acciones la derecha, con un ancho máximo para evitar controles sobredimensionados.

Este proyecto valida en Android operaciones Git seguras mediante Eclipse JGit 7.8. Conserva los spikes de diagnóstico local, clone HTTPS y push autenticado. El motor funcional actúa sobre el repositorio seleccionado.

## Compilar y probar

Se necesitan Android SDK 36, JDK 17 y Android API 33 o superior en el dispositivo. Desde la raíz del repositorio:

```powershell
.\gradlew.bat test lint assembleDebug
```

La APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

**EJECUTAR PRUEBA LOCAL** crea un repositorio nuevo bajo `filesDir/diagnostics/<uuid>` y comprueba inicialización, rama/HEAD, archivo nuevo, estado, índice, commit, árbol limpio y lectura del último commit.

**EJECUTAR PRUEBA REMOTA** clona el repositorio público indicado bajo `filesDir/diagnostics/remote-clones/<uuid>`, sin proveedor de credenciales. Comprueba el repo Git, `HEAD`, rama `main`, URL de `origin`, `origin/main`, igualdad de commits, árbol limpio, `README.md` y el último commit. Ambas acciones se ejecutan en segundo plano y muestran los pasos y errores. Logcat usa la etiqueta `RobGitDiagnostic`.

**PRUEBA PUSH AUTENTICADO** usa exclusivamente `robdor80/Robgit.pruebas` y comparte el campo oculto de token con la función SUBIR. El token permanece solo en memoria durante la operación y se elimina del estado de la interfaz al comenzar. La prueba clona, modifica únicamente `robgit_android_test.txt`, crea un commit local, hace `fetch` para comprobar que GitHub no haya avanzado, ejecuta un push normal y consulta `refs/heads/main` para confirmar el commit remoto. Ante un resultado de red dudoso, consulta el remoto una vez y no repite el push.

Los tests JVM comprueban el contenido local dentro del commit, que la prueba no utilice una carpeta ya existente y que los errores autenticados oculten credenciales. Las operaciones remotas no se ejecutan en la suite JVM: deben probarse desde la app en la Galaxy Tab S9+.

## Estado del repositorio funcional

Cada repositorio configurado se prepara en su ubicación registrada. El repositorio original `Robgit.pruebas` conserva `filesDir/repos/robgit-pruebas` hasta una migración manual. Si una carpeta ya existe, RobGit verifica remoto y rama y no clona encima ni elimina su contenido.

**ANALIZAR AHORA** realiza primero un `fetch` y después calcula la relación real del grafo entre `HEAD` y la rama remota configurada: sincronizado, local adelantado, remoto adelantado o divergente, con el número de commits exclusivos de cada lado. El working tree se representa por separado mediante archivos nuevos, modificados, eliminados, staged y en conflicto. Si el fetch falla, el resultado es `ERROR` y nunca se presenta como sincronizado.

Cuando el estado conocido indica que GitHub está adelantado, el working tree está limpio y no hay commits locales pendientes, se habilita **↓ PULL**. La operación no confía en ese estado mostrado: vuelve a ejecutar `fetch`, recalcula el grafo y revalida todas las condiciones antes de aplicar exclusivamente un fast-forward con `FastForwardMode.FF_ONLY`.

PULL no usa `pull` de JGit, `reset`, `force`, stash, checkout destructivo ni crea commits de merge. Se bloquea ante cambios locales, commits locales o divergencia; distingue también repositorio ya sincronizado, error de fetch y error general. Tras un fast-forward verifica que `HEAD` coincide exactamente con `origin/<rama configurada>`, que ahead/behind son cero y que el working tree está limpio.

La suite JVM incluye escenarios deterministas con remotos Git locales para un commit remoto, varios commits, operación idempotente, archivos modificados, untracked y staged, commits locales, divergencia, fallo de fetch y estado de UI obsoleto. También verifica contenido, refs, limpieza del working tree y ausencia de commits extra.

## Subida segura

**↑ PUSH** vuelve a ejecutar `fetch` y recalcula el estado real antes de modificar el índice. Solo continúa si la rama configurada puede avanzar de forma normal sobre su rama remota. Incluye archivos nuevos, modificados y eliminados no ignorados; crea exactamente un commit cuando hay cambios pendientes o reutiliza los commits locales existentes cuando el working tree ya está limpio.

Antes del push realiza un segundo `fetch` y bloquea la operación si GitHub cambió. El push se limita a `refs/heads/<rama configurada>:refs/heads/<rama configurada>`, con force desactivado. Un rechazo conserva todo el trabajo local. Si la respuesta del push es ambigua, realiza una única comprobación remota y nunca reintenta automáticamente.

El PAT se mantiene únicamente en memoria, se muestra enmascarado y se limpia al comenzar y finalizar la operación. No se guarda en preferencias, archivos, URLs, logs ni mensajes de error. Los tests de SUBIR utilizan exclusivamente repositorios bare locales y cubren staging, commits existentes, carreras remotas, rechazos non-fast-forward, credenciales, fallos de fetch y respuestas ambiguas. El Hito 6 todavía requiere validación física en la Galaxy Tab S9+.

## Sincronización segura

**↕ SINCRONIZAR** es el flujo principal conservador. Siempre hace `fetch` y recalcula el estado real antes de decidir: si ya está sincronizado no actúa; si GitHub está adelantado y el árbol está limpio delega en DESCARGAR; si solo hay trabajo local delega en SUBIR. Cada ejecución realiza como máximo una de esas operaciones, nunca ambas.

Si hay cambios en este dispositivo y en GitHub, divergencia, conflictos o fallo de fetch, se bloquea sin intentar merge, rebase, reset ni resolución automática. El PAT también puede ser necesario para consultar o descargar de un repositorio privado. La validación física multi-repositorio de este hito queda pendiente.
