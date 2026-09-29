# Refresco automático de repositorios

El estado local se actualiza tras 750 ms sin eventos relevantes. Este flujo no usa OAuth, red, fetch, staging, commits ni escrituras de refs o del working tree. El logo conserva el análisis completo manual y una vuelta real del background solicita ese mismo análisis con el flujo remoto existente.

## Arquitectura

- `RepositoryStateService.refreshLocalState` reutiliza `git.status()` y la lectura de refs/grafo de JGit. No llama a las capturas, fingerprints, SHA-256 ni staging de la sincronización segura.
- `RepositoryLocalStateReader` separa la API local de `RepositoryGitEngine`: no acepta credenciales ni ofrece operaciones remotas.
- `GitHubGitOperations.refreshLocal` usa esa API sin consultar el proveedor OAuth. `analyze`, PULL, PUSH y SINCRONIZAR mantienen sus flujos existentes.
- `RepositoryGitConcurrency` serializa las operaciones y las comprobaciones Git mediante un mutex de proceso. Una recreación de Activity no libera la exclusión mientras una llamada síncrona de JGit sigue terminando en IO.
- `RepositoryAutoRefresh` mantiene un solo consumidor y un canal conflado de capacidad acotada. Cada evento recibido reinicia el debounce. Los eventos durante una lectura se agrupan para una lectura posterior, sin cancelar la actual. Los errores se conservan por separado y cierran la observación.
- `RecursiveRepositoryWatcher` registra directorios, añade solo los subárboles creados o movidos y cierra los observadores retirados. La enumeración inicial lee metadatos de directorios; no abre el contenido de los assets ni sigue enlaces simbólicos. Una eliminación de archivo normal no recorre el mapa completo de observadores.
- `AndroidRepositoryWatcherFactory` adapta `FileObserver` y realiza el registro inicial en IO. El mantenimiento de subdirectorios se realiza en el watcher; las notificaciones hacia la UI únicamente escriben en el canal. Compose no procesa un evento por coroutine.
- `MainActivity` conecta la selección, los permisos, las migraciones y `ForegroundRefreshPolicy` con el controlador. Cierra la observación al disponer la pantalla.

## Qué observa `.git`

Se observa el working tree recursivamente. Dentro de `.git` solo se registran el propio directorio y `refs/heads` con sus padres/subdirectorios.

Se notifican cambios en `index`, `HEAD`, refs locales, `config`, `packed-refs` y marcadores de operaciones incompletas. Así, el staging o los commits realizados por otra herramienta también actualizan la UI. Se ignoran locks, objetos, logs, `FETCH_HEAD`, `ORIG_HEAD`, refs remotas y `.git` anidados.

Antes de cualquier operación propia se cierra el watcher y se invalida su generación. Se descartan el timer y los callbacks pendientes. Al finalizar se abre una nueva generación, sin solicitar una lectura adicional por los eventos que generó la operación. El `finalState` de PULL/PUSH/SINCRONIZAR sigue siendo la fuente principal. Una lectura local anterior cancelada no puede publicarse después de ese resultado.

Las modificaciones externas realizadas durante una operación propia quedan sujetas a las comprobaciones de esa operación; el watcher está suspendido durante ese intervalo.

## Frescura y acciones

- `checkedAt`: momento de la lectura del snapshot actual.
- `localStateIsFresh`: la lectura local terminó correctamente.
- `remoteStateIsFresh`: este snapshot procede de una comprobación remota, no de un refresco local.
- `remoteCheckedAt`: última comprobación remota válida que todavía corresponde a la rama y ref remota conocidas. El refresco local nunca adelanta esta fecha; si no hay prueba compatible, queda desconocida.
- `localRepositoryIsSafe`: no hay una operación Git local incompleta.

Después de una lectura exclusivamente local la UI no afirma que GitHub está al día. Si había conocimiento remoto válido, la relación conocida permite PUSH únicamente en los casos sincronizado/local-ahead con trabajo pendiente. El mensaje aclara que RobGit comprobará GitHub antes de subir, y el motor mantiene todas sus verificaciones remotas. Sin conocimiento remoto válido, con remoto-ahead, divergencia, conflictos u operación incompleta se bloquean las acciones correspondientes hasta analizar.

Los detalles técnicos muestran las fechas local y remota por separado.

## Foreground y exclusión

`ForegroundRefreshPolicy` distingue el primer arranque, un retorno real del background y un cambio de configuración. El contador del retorno se consume una sola vez; las recomposiciones no solicitan otro análisis.

El análisis automático exige repositorio preparado, acceso, ausencia de migración bloqueante y ausencia de otra operación. Se omite un retorno que encuentre Git ocupado, sin cancelar la operación ni encolar otro fetch. Se vuelve a comprobar la preparación al regresar, permitiendo recuperar la observación tras restablecer el acceso.

No se han cambiado FF_ONLY, controles de divergencia/conflictos, solapamiento conservador de rutas, comprobaciones previas al push, rechazo de cambios remotos durante la operación ni instrumentación de PULL/SYNC. No se han añadido force push, reset-hard o resolución automática de conflictos.

## Archivos

Nuevos en `app/src/main/java/es/robertodorado/robgit/`:

- `AndroidRepositoryWatcher.kt`
- `RecursiveRepositoryWatcher.kt`
- `RepositoryAutoRefresh.kt`
- `RepositoryGitConcurrency.kt`

Modificados en ese mismo directorio:

- `MainActivity.kt`
- `RepositoryStateService.kt`
- `RepositoryHumanStatus.kt`
- `GitHubGitOperations.kt`

Nuevos en `app/src/test/java/es/robertodorado/robgit/`:

- `RecursiveRepositoryWatcherTest.kt`
- `RepositoryAutoRefreshTest.kt`
- `RepositoryGitConcurrencyTest.kt`

Ampliados en ese mismo directorio:

- `RepositoryStateServiceTest.kt`
- `RepositoryHumanStatusTest.kt`
- `GitHubGitOperationsTest.kt`

## Cobertura de los requisitos de prueba

| Requisito | Pruebas |
| --- | --- |
| Cambio local, nuevo, eliminado, staged | `simpleLocalEventRefreshesOnlyAfterDebounce`, `localRefreshReportsModifiedNewDeletedAndStagedWithoutFetch` |
| Rename/move y guardado temporal | `burstIncludingRenameAndTemporarySaveRestartsTimerOnce`, `localRefreshReportsAtomicRenameAsNewAndDeletedFiles`, `movedDirectoriesCloseOldSubtreeAndRegisterNewPaths` |
| Debounce, ráfagas y eventos separados | `separatedEventsRefreshSeparately`, `largeBurstUsesABoundedMailbox`, prueba de ráfaga anterior |
| `.git` y staging externo | `gitObjectsLogsRemoteRefsAndLockFilesAreIgnoredButIndexAndLocalRefsAreDetected` y prueba local de staged |
| Cambio de repo y retirada/no preparado | `changingRepositoryClosesPreviousAndDiscardsItsLateCallbacks`, `removedUnpreparedOrInaccessibleTargetStopsObservation`, `removingGitDirectoryStopsAnUnpreparedRepository` |
| Background real y rotación | `realBackgroundReturnRequestsFullAnalyzeOnceAndRotationDoesNot`, pruebas existentes de `ForegroundRefreshPolicy` |
| Operación activa y finalState | `busyForegroundReturnIsSkippedRatherThanQueued`, `ownGitOperationsDiscardPendingEventsAndKeepTheirFinalState`, `inFlightOldReadCannotOverwriteOperationFinalState`, `RepositoryGitConcurrencyTest` |
| Acceso perdido | `accessFailureClosesObserversWithoutRetryLoop`, `workspaceDeletionDegradesSafelyAndClosesEveryObserver`, `missingWorkspaceReturnsSafeLocalError`, `errorCannotBeOverwrittenByAConflatedChangeEvent` |
| Lectura pura | `watcherNeverChangesFileContentsOrMetadataFiles`, `localRefreshIsPureReadOfWorkingTreeIndexAndRefs` |
| Sin fetch/OAuth local; remoto en análisis completo | `localBridgeBypassesOAuthAndRemoteEngine`, `localRefreshDoesNotPretendToKnowNewRemoteCommits`, pruebas existentes de `GitHubGitOperationsTest` |
| Rendimiento estructural | `localRefreshNeverInvokesHeavySynchronizationPhases` verifica contadores/fases reales de instrumentación |
| Conflictos y conocimiento remoto conservador | `externalMergeConflictsAreReadAndRemainBlocked`, pruebas nuevas de frescura y presentación |

## Validación y APK

Comando: `./gradlew.bat testDebugUnitTest lint assembleDebug --console=plain`.

Resultado final: BUILD SUCCESSFUL. 353 casos registrados: 352 aprobados, 0 fallos, 0 errores y 1 omitido. Se han añadido 42 tests. La omisión corresponde a la prueba existente `RepositoryMigrationManagerTest.symlinkInSourceIsRejectedWithoutFollowingIt`, que no pudo crear un enlace simbólico en este entorno Windows.

Lint: 0 errores y 30 avisos en elementos existentes (manifest/almacenamiento compartido, preferencias/OAuth, versiones de dependencias, iconos, recomendaciones KTX y dos avisos de TrustManager dentro del JAR de JGit). Los nuevos componentes del watcher no generan avisos. No se han alterado esas dependencias ni configuraciones.

`assembleDebug`: correcto. El APK final ocupa 43.497.626 bytes. Los logs de las ejecuciones quedan en `app/build/reports/autorefresh/`.

Los resultados finales se conservan en `app/build/test-results/testDebugUnitTest/`, `app/build/reports/tests/testDebugUnitTest/` y `app/build/reports/lint-results-debug.html`. El APK para instalar está en `app/build/outputs/apk/debug/app-debug.apk`.

## Prueba física pendiente

La entrega de eventos de `FileObserver` en almacenamiento compartido debe comprobarse en Realme y Galaxy Tab S9+. No se ha conectado un dispositivo para esta validación. Android no expone un error de registro mediante el retorno de `startWatching`; la prueba real confirma la entrega en cada dispositivo y su capa de almacenamiento. No se ha añadido polling como alternativa. El análisis de foreground y el logo permiten recuperar el estado tras suspensión del proceso o pérdida de eventos.

Prueba recomendada con un repositorio de pruebas:

1. Instalar el APK y conceder acceso al workspace. Preparar el repositorio y pulsar el logo para comprobar local y remoto.
2. Editar un archivo desde otra app, regresar y verificar el trabajo local pendiente. Crear, eliminar y renombrar archivos; incluir un guardado con temporales y una carpeta nueva con subdirectorios.
3. Para comprobar exclusivamente el watcher sin activar el análisis de foreground, mantener RobGit visible junto al editor en pantalla dividida. Confirmar que el cambio aparece tras aproximadamente 750 ms de estabilización y que la fecha remota no avanza.
4. Cambiar un archivo desde GitHub Web mientras RobGit está en background. Regresar y comprobar remoto-ahead y PULL cuando sea seguro.
5. Rotar la tablet y verificar que no aparece un análisis remoto adicional por la rotación.
6. Ejecutar PULL, PUSH y SINCRONIZAR; comprobar sus resultados y los informes de rendimiento sin una ráfaga posterior de análisis.
7. Cambiar de repositorio, retirar una selección y revocar/restablecer permisos. Comprobar bloqueo seguro y recuperación mediante foreground o logo.

Los eventos de filesystem son señales de invalidación, no una transacción con los editores externos. Las operaciones Git siguen revalidando antes de escribir.
