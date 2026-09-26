package es.robertodorado.robgit

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val diagnosticRoot = File(filesDir, "diagnostics")
        val service = GitRepositoryService()
        val repositoryStateService = RepositoryStateService()
        val functionalRepository = File(filesDir, "repos/robgit-pruebas")
        setContent {
            MaterialTheme {
                DiagnosticScreen(
                    diagnosticRoot = diagnosticRoot,
                    service = service,
                    repositoryStateService = repositoryStateService,
                    functionalRepository = functionalRepository,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticScreen(
    diagnosticRoot: File,
    service: GitRepositoryService,
    repositoryStateService: RepositoryStateService,
    functionalRepository: File,
) {
    var repositoryPrepared by remember { mutableStateOf<Boolean?>(null) }
    var preparationResult by remember { mutableStateOf<RepositoryPreparationResult?>(null) }
    var repositoryState by remember { mutableStateOf<RepositoryStateSnapshot?>(null) }
    var downloadResult by remember { mutableStateOf<DownloadResult?>(null) }
    var uploadResult by remember { mutableStateOf<UploadResult?>(null) }
    var synchronizationResult by remember { mutableStateOf<SynchronizationResult?>(null) }
    var commitMessage by remember { mutableStateOf("Cambios desde RobGit") }
    var localResult by remember { mutableStateOf<DiagnosticResult?>(null) }
    var remoteResult by remember { mutableStateOf<DiagnosticResult?>(null) }
    var pushResult by remember { mutableStateOf<DiagnosticResult?>(null) }
    var token by remember { mutableStateOf("") }
    var runningOperation by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(functionalRepository.absolutePath) {
        repositoryPrepared = withContext(Dispatchers.IO) {
            repositoryStateService.isPrepared(functionalRepository)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("RobGit", style = MaterialTheme.typography.headlineSmall)
            Text("RobGit — Estado del repositorio", style = MaterialTheme.typography.headlineSmall)
            Text("robdor80/Robgit.pruebas")
            Text("Directorio: ${functionalRepository.absolutePath}")
            if (repositoryPrepared == null) {
                Text("Comprobando repositorio persistente…")
            } else if (repositoryPrepared == false) {
                Button(
                    onClick = {
                        runningOperation = "preparar repositorio"
                        preparationResult = null
                        repositoryState = null
                        downloadResult = null
                        uploadResult = null
                        synchronizationResult = null
                        scope.launch {
                            try {
                                val completed = withContext(Dispatchers.IO) {
                                    repositoryStateService.prepare(functionalRepository)
                                }
                                preparationResult = completed
                                repositoryPrepared = completed.success
                                if (completed.success) {
                                    Log.i("RobGitState", completed.message)
                                } else {
                                    Log.e("RobGitState", completed.error ?: completed.message)
                                }
                            } finally {
                                runningOperation = null
                            }
                        }
                    },
                    enabled = runningOperation == null,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text("PREPARAR REPOSITORIO")
                }
            } else {
                Button(
                    onClick = {
                        runningOperation = "actualizar estado"
                        repositoryState = null
                        downloadResult = null
                        uploadResult = null
                        synchronizationResult = null
                        scope.launch {
                            try {
                                val completed = withContext(Dispatchers.IO) {
                                    repositoryStateService.refreshState(functionalRepository)
                                }
                                repositoryState = completed
                                if (completed.error == null) {
                                    Log.i("RobGitState", "${completed.type}: ${completed.message}")
                                } else {
                                    Log.e("RobGitState", completed.error)
                                }
                            } finally {
                                runningOperation = null
                            }
                        }
                    },
                    enabled = runningOperation == null,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text("ACTUALIZAR ESTADO")
                }
            }
            if (runningOperation == "preparar repositorio" ||
                runningOperation == "actualizar estado" ||
                runningOperation == "descargar" ||
                runningOperation == "subir" ||
                runningOperation == "sincronizar"
            ) {
                Text("Consultando repositorio…")
            }
            preparationResult?.let { result ->
                Text(if (result.success) "✓ ${result.message}" else "✗ ${result.message}")
                result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            repositoryState?.let { RepositoryStatePanel(it) }
            if (repositoryPrepared == true) {
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = { commitMessage = it },
                    label = { Text("Mensaje del cambio") },
                    enabled = runningOperation == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token GitHub") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    enabled = runningOperation == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val tokenForRun = token.toCharArray()
                        token = ""
                        runningOperation = "sincronizar"
                        synchronizationResult = null
                        downloadResult = null
                        uploadResult = null
                        scope.launch {
                            try {
                                val completed = withContext(Dispatchers.IO) {
                                    repositoryStateService.synchronizeSafely(
                                        functionalRepository,
                                        tokenForRun,
                                        commitMessage,
                                    )
                                }
                                synchronizationResult = completed
                                completed.finalState?.let { repositoryState = it }
                                if (completed.outcome == SynchronizationOutcome.SUCCESS_DOWNLOADED ||
                                    completed.outcome == SynchronizationOutcome.SUCCESS_UPLOADED ||
                                    completed.outcome == SynchronizationOutcome.NOTHING_TO_DO
                                ) {
                                    Log.i("RobGitState", "${completed.outcome}: ${completed.message}")
                                } else {
                                    Log.w(
                                        "RobGitState",
                                        "${completed.outcome}: ${completed.error ?: completed.message}",
                                    )
                                }
                            } finally {
                                tokenForRun.fill('\u0000')
                                token = ""
                                runningOperation = null
                            }
                        }
                    },
                    enabled = runningOperation == null,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(20.dp),
                ) {
                    Text("↕ SINCRONIZAR")
                }
                val canDownload = repositoryState?.let { state ->
                    state.type == RepositoryStateType.REMOTE_AHEAD &&
                        state.relation == CommitRelation.REMOTE_AHEAD &&
                        state.remoteStateIsFresh &&
                        !state.changes.hasChanges &&
                        state.ahead == 0 &&
                        state.behind > 0
                } == true
                Button(
                    onClick = {
                        runningOperation = "descargar"
                        downloadResult = null
                        uploadResult = null
                        synchronizationResult = null
                        scope.launch {
                            try {
                                val completed = withContext(Dispatchers.IO) {
                                    repositoryStateService.downloadFastForward(functionalRepository)
                                }
                                downloadResult = completed
                                completed.finalState?.let { repositoryState = it }
                                if (completed.outcome == DownloadOutcome.SUCCESS ||
                                    completed.outcome == DownloadOutcome.ALREADY_SYNCHRONIZED
                                ) {
                                    Log.i("RobGitState", "${completed.outcome}: ${completed.message}")
                                } else {
                                    Log.w(
                                        "RobGitState",
                                        "${completed.outcome}: ${completed.error ?: completed.message}",
                                    )
                                }
                            } finally {
                                runningOperation = null
                            }
                        }
                    },
                    enabled = runningOperation == null && canDownload,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text("↓ DESCARGAR")
                }
                val canUpload = repositoryState?.let { state ->
                    state.remoteStateIsFresh &&
                        state.changes.conflictingFiles.isEmpty() &&
                        (state.relation == CommitRelation.SYNCHRONIZED ||
                            state.relation == CommitRelation.LOCAL_AHEAD)
                } == true
                Button(
                    onClick = {
                        val tokenForRun = token.toCharArray()
                        token = ""
                        runningOperation = "subir"
                        uploadResult = null
                        downloadResult = null
                        synchronizationResult = null
                        scope.launch {
                            try {
                                val completed = withContext(Dispatchers.IO) {
                                    repositoryStateService.uploadSafely(
                                        functionalRepository,
                                        tokenForRun,
                                        commitMessage,
                                    )
                                }
                                uploadResult = completed
                                completed.finalState?.let { repositoryState = it }
                                if (completed.outcome == UploadOutcome.SUCCESS ||
                                    completed.outcome == UploadOutcome.NOTHING_TO_UPLOAD
                                ) {
                                    Log.i("RobGitState", "${completed.outcome}: ${completed.message}")
                                } else {
                                    Log.w(
                                        "RobGitState",
                                        "${completed.outcome}: ${completed.error ?: completed.message}",
                                    )
                                }
                            } finally {
                                tokenForRun.fill('\u0000')
                                token = ""
                                runningOperation = null
                            }
                        }
                    },
                    enabled = runningOperation == null && canUpload &&
                        token.isNotBlank() && commitMessage.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text("↑ SUBIR")
                }
            }
            DownloadResultPanel(downloadResult)
            UploadResultPanel(uploadResult)
            SynchronizationResultPanel(synchronizationResult)

            Text("Herramientas de diagnóstico", style = MaterialTheme.typography.headlineSmall)
            Text("Prueba local", style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = {
                    runningOperation = "local"
                    localResult = null
                    val directory = File(diagnosticRoot, UUID.randomUUID().toString())
                    scope.launch {
                        try {
                            val completed = withContext(Dispatchers.IO) {
                                service.runLocalDiagnostic(directory)
                            }
                            localResult = completed
                            logResult("local", completed)
                        } catch (error: Throwable) {
                            if (error !is Exception && error !is LinkageError) throw error
                            val detail = describeFailure(error)
                            localResult = DiagnosticResult(directory.absolutePath, "Sin consultar", "Sin commits", "Error", emptyList(), detail)
                            Log.e("RobGitDiagnostic", "Fallo en la prueba local: $detail", error)
                        } finally {
                            runningOperation = null
                        }
                    }
                },
                enabled = runningOperation == null,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
            ) {
                Text("EJECUTAR PRUEBA LOCAL")
            }
            DiagnosticResultPanel(localResult, runningOperation == "local")

            Text("Clone HTTPS de GitHub (solo lectura)", style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = {
                    runningOperation = "remoto"
                    remoteResult = null
                    val directory = File(File(diagnosticRoot, "remote-clones"), UUID.randomUUID().toString())
                    scope.launch {
                        try {
                            val completed = withContext(Dispatchers.IO) {
                                service.runRemoteCloneDiagnostic(directory)
                            }
                            remoteResult = completed
                            logResult("remoto", completed)
                        } catch (error: Throwable) {
                            if (error !is Exception && error !is LinkageError) throw error
                            val detail = describeFailure(error)
                            remoteResult = DiagnosticResult(directory.absolutePath, "Sin consultar", "Sin leer", "Error", emptyList(), detail)
                            Log.e("RobGitDiagnostic", "Fallo en la prueba remota: $detail", error)
                        } finally {
                            runningOperation = null
                        }
                    }
                },
                enabled = runningOperation == null,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
            ) {
                Text("EJECUTAR PRUEBA REMOTA")
            }
            DiagnosticResultPanel(remoteResult, runningOperation == "remoto")

            Text("Push HTTPS autenticado", style = MaterialTheme.typography.titleLarge)
            Text("Repositorio desechable: robdor80/Robgit.pruebas")
            Text("Usa el mismo Token GitHub introducido en la zona funcional.")
            Button(
                onClick = {
                    val tokenForRun = token.toCharArray()
                    token = ""
                    runningOperation = "push"
                    pushResult = null
                    val directory = File(File(diagnosticRoot, "authenticated-pushes"), UUID.randomUUID().toString())
                    scope.launch {
                        try {
                            val completed = withContext(Dispatchers.IO) {
                                service.runAuthenticatedPushDiagnostic(directory, tokenForRun)
                            }
                            pushResult = completed
                            logResult("push autenticado", completed)
                        } catch (error: Throwable) {
                            if (error !is Exception && error !is LinkageError) throw error
                            pushResult = DiagnosticResult(
                                directory.absolutePath,
                                "Sin consultar",
                                "Sin leer",
                                "Error inesperado",
                                emptyList(),
                                "La prueba terminó con un error inesperado sin detalles sensibles.",
                            )
                            Log.e("RobGitDiagnostic", "Fallo inesperado en la prueba de push autenticado")
                        } finally {
                            tokenForRun.fill('\u0000')
                            token = ""
                            runningOperation = null
                        }
                    }
                },
                enabled = runningOperation == null && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
            ) {
                Text("PRUEBA PUSH AUTENTICADO")
            }
            DiagnosticResultPanel(pushResult, runningOperation == "push")
        }
    }
}

@Composable
private fun RepositoryStatePanel(snapshot: RepositoryStateSnapshot) {
    val title = when (snapshot.type) {
        RepositoryStateType.SYNCHRONIZED -> "✅ Sincronizado"
        RepositoryStateType.LOCAL_CHANGES -> "⬆ Cambios locales pendientes"
        RepositoryStateType.LOCAL_AHEAD -> "⬆ Local adelantado"
        RepositoryStateType.REMOTE_AHEAD -> "⬇ GitHub adelantado"
        RepositoryStateType.DIVERGED -> "↕ Repositorio divergente"
        RepositoryStateType.ERROR -> "⚠ Estado no determinable"
    }
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(snapshot.message)
    Text("Rama: ${snapshot.branch ?: "desconocida"}")
    Text("HEAD local: ${snapshot.localHead?.take(7) ?: "desconocido"}")
    Text("origin/main: ${snapshot.remoteHead?.take(7) ?: "desconocido"}")
    Text("Ahead: ${snapshot.ahead} · Behind: ${snapshot.behind}")
    if (snapshot.type == RepositoryStateType.LOCAL_CHANGES) {
        Text("Relación de commits: ${snapshot.relation}")
    }
    StateFiles("Nuevos", snapshot.changes.newFiles)
    StateFiles("Modificados", snapshot.changes.modifiedFiles)
    StateFiles("Eliminados", snapshot.changes.deletedFiles)
    StateFiles("Staged", snapshot.changes.stagedFiles)
    StateFiles("En conflicto", snapshot.changes.conflictingFiles)
    Text("Remoto actualizado: ${if (snapshot.remoteStateIsFresh) "sí" else "no"}")
    Text("Última comprobación: ${snapshot.checkedAt}")
    snapshot.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun StateFiles(label: String, files: Set<String>) {
    if (files.isNotEmpty()) {
        Text("$label (${files.size}): ${files.joinToString()}")
    }
}

@Composable
private fun DownloadResultPanel(result: DownloadResult?) {
    result ?: return
    val prefix = when (result.outcome) {
        DownloadOutcome.SUCCESS -> "✓"
        DownloadOutcome.ALREADY_SYNCHRONIZED -> "="
        else -> "⚠"
    }
    Text("$prefix ${result.message}")
    Text("Resultado: ${result.outcome}")
    if (result.previousHead != null || result.newHead != null) {
        Text(
            "HEAD: ${result.previousHead?.take(7) ?: "desconocido"} → " +
                (result.newHead?.take(7) ?: "desconocido"),
        )
    }
    if (result.outcome == DownloadOutcome.SUCCESS) {
        Text("Commits descargados: ${result.commitsDownloaded}")
        Text("Working tree limpio: ${if (result.workingTreeClean) "sí" else "no"}")
    }
    result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun UploadResultPanel(result: UploadResult?) {
    result ?: return
    val prefix = when (result.outcome) {
        UploadOutcome.SUCCESS -> "✓"
        UploadOutcome.NOTHING_TO_UPLOAD -> "="
        else -> "⚠"
    }
    Text("$prefix ${result.message}")
    Text("Resultado: ${result.outcome}")
    if (result.previousHead != null || result.attemptedHead != null) {
        Text(
            "HEAD: ${result.previousHead?.take(7) ?: "desconocido"} → " +
                (result.attemptedHead?.take(7) ?: "desconocido"),
        )
    }
    if (result.commitCreated) {
        Text("Commit local creado y conservado: sí")
    }
    if (result.outcome == UploadOutcome.SUCCESS) {
        Text("Commits subidos: ${result.commitsUploaded}")
    }
    result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun SynchronizationResultPanel(result: SynchronizationResult?) {
    result ?: return
    val prefix = when (result.outcome) {
        SynchronizationOutcome.SUCCESS_DOWNLOADED,
        SynchronizationOutcome.SUCCESS_UPLOADED,
        -> "✓"
        SynchronizationOutcome.NOTHING_TO_DO -> "="
        else -> "⚠"
    }
    Text("$prefix ${result.message}")
    Text("Resultado: ${result.outcome}")
    result.finalState?.let { state ->
        Text("Ahead: ${state.ahead} · Behind: ${state.behind}")
    }
    result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun DiagnosticResultPanel(result: DiagnosticResult?, running: Boolean) {
    Text("JGit: ${if (running) "ejecutando" else if (result == null) "pendiente" else if (result.error == null) "correcto" else "error"}")
    Text("Directorio del repositorio: ${result?.repositoryPath ?: "pendiente"}")
    Text("Rama: ${result?.branch ?: "pendiente"}")
    Text("Último commit: ${result?.lastCommit ?: "pendiente"}")
    Text("Estado: ${result?.status ?: "pendiente"}")
    result?.steps?.forEach { step ->
        Text("✓ ${step.description}${if (step.detail.isBlank()) "" else ": ${step.detail}"}")
    }
    result?.error?.let { Text("✗ $it", color = MaterialTheme.colorScheme.error) }
}

private fun logResult(operation: String, result: DiagnosticResult) {
    result.steps.forEach { Log.i("RobGitDiagnostic", "$operation — ${it.description}: ${it.detail}") }
    result.error?.let { Log.e("RobGitDiagnostic", "$operation — $it") }
}

private fun describeFailure(error: Throwable): String = generateSequence(error) { it.cause }
    .take(5)
    .joinToString(" ← ") { "${it.javaClass.name}: ${it.message ?: "sin detalle"}" }
