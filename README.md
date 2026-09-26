# RobGit — spike JGit en Android

Este proyecto valida en Android operaciones Git seguras mediante Eclipse JGit 7.8. Conserva los spikes de diagnóstico local, clone HTTPS y push autenticado, y utiliza una copia persistente de `robdor80/Robgit.pruebas` para las funciones de estado, descarga y subida.

## Compilar y probar

Se necesitan Android SDK 36, JDK 17 y Android API 34 o superior en el dispositivo. Desde la raíz del repositorio:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

La APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

**EJECUTAR PRUEBA LOCAL** crea un repositorio nuevo bajo `filesDir/diagnostics/<uuid>` y comprueba inicialización, rama/HEAD, archivo nuevo, estado, índice, commit, árbol limpio y lectura del último commit.

**EJECUTAR PRUEBA REMOTA** clona el repositorio público indicado bajo `filesDir/diagnostics/remote-clones/<uuid>`, sin proveedor de credenciales. Comprueba el repo Git, `HEAD`, rama `main`, URL de `origin`, `origin/main`, igualdad de commits, árbol limpio, `README.md` y el último commit. Ambas acciones se ejecutan en segundo plano y muestran los pasos y errores. Logcat usa la etiqueta `RobGitDiagnostic`.

**PRUEBA PUSH AUTENTICADO** usa exclusivamente `robdor80/Robgit.pruebas` y comparte el campo oculto de token con la función SUBIR. El token permanece solo en memoria durante la operación y se elimina del estado de la interfaz al comenzar. La prueba clona, modifica únicamente `robgit_android_test.txt`, crea un commit local, hace `fetch` para comprobar que GitHub no haya avanzado, ejecuta un push normal y consulta `refs/heads/main` para confirmar el commit remoto. Ante un resultado de red dudoso, consulta el remoto una vez y no repite el push.

Los tests JVM comprueban el contenido local dentro del commit, que la prueba no utilice una carpeta ya existente y que los errores autenticados oculten credenciales. Las operaciones remotas no se ejecutan en la suite JVM: deben probarse desde la app en la Galaxy Tab S9+.

## Estado del repositorio funcional

La sección **RobGit — Estado del repositorio** prepara una única copia persistente de `robdor80/Robgit.pruebas` en `filesDir/repos/robgit-pruebas`. Si la carpeta ya existe, la abre y nunca clona encima ni elimina su contenido.

**ACTUALIZAR ESTADO** realiza primero un `fetch` y después calcula la relación real del grafo entre `HEAD` y `origin/main`: sincronizado, local adelantado, remoto adelantado o divergente, con el número de commits exclusivos de cada lado. El working tree se representa por separado mediante archivos nuevos, modificados, eliminados, staged y en conflicto. Si el fetch falla, el resultado es `ERROR` y nunca se presenta como sincronizado.

Cuando el estado conocido indica que GitHub está adelantado, la rama es `main`, el working tree está limpio y no hay commits locales pendientes, se habilita **↓ DESCARGAR**. La operación no confía en ese estado mostrado: vuelve a ejecutar `fetch`, recalcula el grafo y revalida todas las condiciones antes de aplicar exclusivamente un fast-forward con `FastForwardMode.FF_ONLY`.

DESCARGAR no usa `pull`, `reset`, `force`, stash, checkout destructivo ni crea commits de merge. Se bloquea ante cambios locales, commits locales o divergencia; distingue también repositorio ya sincronizado, error de fetch y error general. Tras un fast-forward verifica que `HEAD` coincide exactamente con `origin/main`, que ahead/behind son cero y que el working tree está limpio.

La suite JVM incluye escenarios deterministas con remotos Git locales para un commit remoto, varios commits, operación idempotente, archivos modificados, untracked y staged, commits locales, divergencia, fallo de fetch y estado de UI obsoleto. También verifica contenido, refs, limpieza del working tree y ausencia de commits extra.

## Subida segura

**↑ SUBIR** vuelve a ejecutar `fetch` y recalcula el estado real antes de modificar el índice. Solo continúa si `main` puede avanzar de forma normal sobre `origin/main`. Incluye archivos nuevos, modificados y eliminados no ignorados; crea exactamente un commit cuando hay cambios pendientes o reutiliza los commits locales existentes cuando el working tree ya está limpio.

Antes del push realiza un segundo `fetch` y bloquea la operación si GitHub cambió. El push se limita a `refs/heads/main:refs/heads/main`, con force desactivado. Un rechazo conserva todo el trabajo local. Si la respuesta del push es ambigua, realiza una única comprobación remota y nunca reintenta automáticamente.

El PAT se mantiene únicamente en memoria, se muestra enmascarado y se limpia al comenzar y finalizar la operación. No se guarda en preferencias, archivos, URLs, logs ni mensajes de error. Los tests de SUBIR utilizan exclusivamente repositorios bare locales y cubren staging, commits existentes, carreras remotas, rechazos non-fast-forward, credenciales, fallos de fetch y respuestas ambiguas. El Hito 6 todavía requiere validación física en la Galaxy Tab S9+.
