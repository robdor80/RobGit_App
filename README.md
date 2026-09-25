# RobGit — spike JGit en Android

Este proyecto comprueba operaciones **locales** de Eclipse JGit 7.8 dentro de una app Android. No conecta con GitHub ni contiene autenticación.

## Compilar y probar

Se necesitan Android SDK 36, JDK 17 y Android API 34 o superior en el dispositivo. Desde la raíz del repositorio:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

La APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

Al pulsar **EJECUTAR PRUEBA LOCAL**, la app crea un repositorio nuevo bajo `filesDir/diagnostics/<uuid>` y comprueba inicialización, rama/HEAD, archivo nuevo, estado, índice, commit, árbol limpio y lectura del último commit. Cada ejecución usa un directorio independiente. La pantalla muestra los pasos y el error concreto; Logcat usa la etiqueta `RobGitDiagnostic`.

La prueba JVM comprueba también el contenido del archivo dentro del commit. La compatibilidad real con Android sigue pendiente hasta ejecutar la APK en la Galaxy Tab S9+.
