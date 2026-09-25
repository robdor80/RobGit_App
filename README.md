# RobGit — spike JGit en Android

Este proyecto comprueba operaciones locales y un clone HTTPS anónimo de solo lectura de `https://github.com/robdor80/RobGit_App.git` mediante Eclipse JGit 7.8. No contiene autenticación ni operaciones que escriban en GitHub.

## Compilar y probar

Se necesitan Android SDK 36, JDK 17 y Android API 34 o superior en el dispositivo. Desde la raíz del repositorio:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

La APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

**EJECUTAR PRUEBA LOCAL** crea un repositorio nuevo bajo `filesDir/diagnostics/<uuid>` y comprueba inicialización, rama/HEAD, archivo nuevo, estado, índice, commit, árbol limpio y lectura del último commit.

**EJECUTAR PRUEBA REMOTA** clona el repositorio público indicado bajo `filesDir/diagnostics/remote-clones/<uuid>`, sin proveedor de credenciales. Comprueba el repo Git, `HEAD`, rama `main`, URL de `origin`, `origin/main`, igualdad de commits, árbol limpio, `README.md` y el último commit. Ambas acciones se ejecutan en segundo plano y muestran los pasos y errores. Logcat usa la etiqueta `RobGitDiagnostic`.

**PRUEBA PUSH AUTENTICADO** usa exclusivamente `robdor80/Robgit.pruebas`. El token se introduce en un campo oculto, permanece solo en memoria durante la operación y se elimina del estado de la interfaz al comenzar. La prueba clona, modifica únicamente `robgit_android_test.txt`, crea un commit local, hace `fetch` para comprobar que GitHub no haya avanzado, ejecuta un push normal y consulta `refs/heads/main` para confirmar el commit remoto. Ante un resultado de red dudoso, consulta el remoto una vez y no repite el push.

Los tests JVM comprueban el contenido local dentro del commit, que la prueba no utilice una carpeta ya existente y que los errores autenticados oculten credenciales. Las operaciones remotas no se ejecutan en la suite JVM: deben probarse desde la app en la Galaxy Tab S9+.
