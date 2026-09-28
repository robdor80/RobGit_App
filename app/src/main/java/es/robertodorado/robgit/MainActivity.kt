package es.robertodorado.robgit

import android.content.pm.ActivityInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val foregroundPolicy = ForegroundRefreshPolicy()
    private var foregroundReturn by mutableIntStateOf(0)
    private var authCallback by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val tablet = resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (tablet) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
        authCallback = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
        val oauthConfig = GitHubOAuthConfig(BuildConfig.ROBGIT_GITHUB_CLIENT_ID, BuildConfig.ROBGIT_GITHUB_CLIENT_SECRET)
        val authService = GitHubAuthService(oauthConfig, EncryptedGitHubAuthStore(this), HttpGitHubOAuthClient(oauthConfig))
        val diagnosticRoot = File(filesDir, "diagnostics")
        val registry = RepositoryRegistry(File(filesDir, "repositories.properties"), File(filesDir, "repos"))
        @Suppress("DEPRECATION")
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val workspaceResolver = RepositoryWorkspaceResolver(File(filesDir, "repos"), documents) {
            Environment.isExternalStorageManager()
        }
        val migrationManager = RepositoryMigrationManager(registry, workspaceResolver, File(filesDir, "migrations"))
        setContent {
            RobGitTheme {
                RobGitScreen(
                    diagnosticRoot, remember { GitRepositoryService() }, registry, workspaceResolver,
                    migrationManager, foregroundReturn, authService, authCallback, { authCallback = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        authCallback = intent.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
    }

    override fun onStart() {
        super.onStart()
        if (foregroundPolicy.onStart()) foregroundReturn++
    }

    override fun onStop() {
        foregroundPolicy.onStop(isChangingConfigurations)
        super.onStop()
    }
}

private enum class CommitPurpose { PUSH, SYNCHRONIZE, WORKSPACE_PUSH, WORKSPACE_SYNCHRONIZE }
private enum class SharedWorkspaceAction { PREPARE, ANALYZE, PULL, PUSH, SYNCHRONIZE, FILESYSTEM }

@Composable
private fun RobGitScreen(
    diagnosticRoot: File,
    gitService: GitRepositoryService,
    registry: RepositoryRegistry,
    workspaceResolver: RepositoryWorkspaceResolver,
    migrationManager: RepositoryMigrationManager,
    foregroundReturn: Int,
    authService: GitHubAuthService,
    authCallback: String?,
    onAuthCallbackConsumed: () -> Unit,
) {
    var catalog by remember { mutableStateOf<RepositoryCatalog?>(null) }
    var uiRepositoryId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedRepository = catalog?.selected
    val repositoryService = remember(selectedRepository?.id) {
        selectedRepository?.let { RepositoryStateService(repositoryUrl = it.remoteUrl, branch = it.branch) }
    }
    var repositoryPrepared by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var repositoryState by remember { mutableStateOf<RepositoryStateSnapshot?>(null) }
    var lastOperation by remember { mutableStateOf<RestoredOperation?>(null) }
    val pullPerformanceByRepository by PullPerformanceStore.process.reports.collectAsState()
    var noticeTitle by remember { mutableStateOf<String?>(null) }
    var noticeBody by remember { mutableStateOf<String?>(null) }
    var noticeRecommendation by remember { mutableStateOf<String?>(null) }
    var runningOperation by remember { mutableStateOf<String?>(null) }
    var showTechnical by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var commitPurpose by remember { mutableStateOf<CommitPurpose?>(null) }
    var localDiagnostic by remember { mutableStateOf<DiagnosticResult?>(null) }
    var remoteDiagnostic by remember { mutableStateOf<DiagnosticResult?>(null) }
    var pushDiagnostic by remember { mutableStateOf<DiagnosticResult?>(null) }
    var showAddRepository by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<RepositoryConfig?>(null) }
    var preparedRepositories by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var showSharedWorkspace by remember { mutableStateOf(false) }
    var sharedWorkspaceResult by remember { mutableStateOf<SharedWorkspaceResult?>(null) }
    var activeWorkspaceOperation by remember { mutableStateOf<String?>(null) }
    var hasAllFilesAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var pendingMigrations by remember { mutableStateOf<List<MigrationRecord>?>(null) }
    var migrationJournalError by remember { mutableStateOf<String?>(null) }
    var confirmMigration by remember { mutableStateOf<RepositoryConfig?>(null) }
    var migrationDialogVisible by remember { mutableStateOf(false) }
    var migrationDialogError by remember { mutableStateOf<String?>(null) }
    var migrationPhase by remember { mutableStateOf<MigrationPhase?>(null) }
    val selectedPendingMigration = pendingMigrations?.firstOrNull { it.repositoryId == selectedRepository?.id }
    val sharedAccessMissing = selectedRepository?.workspaceLocation == WorkspaceLocation.SHARED_DOCUMENTS &&
        !Environment.isExternalStorageManager()
    val workspaceResolution = selectedRepository?.takeIf {
        pendingMigrations != null && migrationJournalError == null && selectedPendingMigration == null
    }?.let { runCatching { workspaceResolver.resolve(it) } }
    val functionalRepository = workspaceResolution?.getOrNull()
    val workspaceResolutionError = workspaceResolution?.exceptionOrNull()?.message
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val documentsDirectory = remember(context) {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    }
    val sharedWorkspaceProbe = remember(documentsDirectory) {
        SharedWorkspaceProbe(documentsDirectory, { Environment.isExternalStorageManager() })
    }
    val workspacePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasAllFilesAccess = Environment.isExternalStorageManager()
    }
    val scope = rememberCoroutineScope()
    val githubState by authService.state.collectAsState()
    val gitOperations = remember(authService) { GitHubGitOperations(authService) }
    val aiContextBuilder = remember { AiContextBuilder() }
    val aiAssistantService = remember { AiAssistantService(FirebaseGeminiAiProvider()) }

    LaunchedEffect(authService) {
        withContext(Dispatchers.IO) { authService.restore() }
    }
    LaunchedEffect(foregroundReturn) {
        if (foregroundReturn > 0 && authCallback == null) {
            withContext(Dispatchers.IO) { authService.restore() }
        }
    }
    LaunchedEffect(authService) {
        while (true) {
            delay(60_000)
            if (authService.state.value is GitHubConnectionState.Connected ||
                (authService.state.value as? GitHubConnectionState.Error)?.retryable == true
            ) withContext(Dispatchers.IO) { authService.validAccessToken() }
        }
    }
    LaunchedEffect(authService, authCallback) {
        if (authCallback != null) {
            withContext(Dispatchers.IO) { authService.handleCallback(authCallback) }
            onAuthCallbackConsumed()
        }
    }

    fun connectGitHub() {
        scope.launch {
            val authorizationUrl = withContext(Dispatchers.IO) { authService.beginAuthorization() } ?: return@launch
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { authService.cancelAuthorization() }
            }
        }
    }

    fun disconnectGitHub() {
        scope.launch { withContext(Dispatchers.IO) { authService.disconnect() } }
    }

    fun requestWorkspaceAccess() {
        val packageSettings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
        val intent = if (context.packageManager.resolveActivity(packageSettings, 0) != null) packageSettings
            else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        workspacePermissionLauncher.launch(intent)
    }

    fun runSharedWorkspace(action: SharedWorkspaceAction, message: String = "Prueba workspace compartido") {
        if (runningOperation != null) return
        val operationLabel = when (action) {
            SharedWorkspaceAction.PREPARE -> "PREPARAR WORKSPACE"
            SharedWorkspaceAction.ANALYZE -> "ANALIZAR WORKSPACE"
            SharedWorkspaceAction.PULL -> "PULL DE PRUEBA"
            SharedWorkspaceAction.PUSH -> "PUSH DE PRUEBA"
            SharedWorkspaceAction.SYNCHRONIZE -> "SINCRONIZAR DE PRUEBA"
            SharedWorkspaceAction.FILESYSTEM -> "PROBAR FILESYSTEM"
        }
        sharedWorkspaceResult = null
        activeWorkspaceOperation = operationLabel
        runningOperation = operationLabel
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (action) {
                        SharedWorkspaceAction.PREPARE -> gitOperations.sharedPrepare(sharedWorkspaceProbe)
                        SharedWorkspaceAction.ANALYZE -> gitOperations.sharedAnalyze(sharedWorkspaceProbe)
                        SharedWorkspaceAction.PULL -> gitOperations.sharedPull(sharedWorkspaceProbe)
                        SharedWorkspaceAction.PUSH -> gitOperations.sharedPush(sharedWorkspaceProbe, message)
                        SharedWorkspaceAction.SYNCHRONIZE -> gitOperations.sharedSynchronize(sharedWorkspaceProbe, message)
                        SharedWorkspaceAction.FILESYSTEM -> sharedWorkspaceProbe.probeFilesystem()
                    }
                }
                sharedWorkspaceResult = result
                if (result.authenticationRequired) sharedWorkspaceResult = result.copy(message = when (authService.state.value) {
                    GitHubConnectionState.NeedsReauth -> "Es necesario volver a conectar GitHub desde Ajustes."
                    GitHubConnectionState.NotConfigured -> "La conexión con GitHub no está configurada localmente."
                    is GitHubConnectionState.Connected -> "RobGit no tiene acceso a este repositorio desde GitHub."
                    else -> "Conecta RobGit con GitHub desde Ajustes para continuar."
                })
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                sharedWorkspaceResult = SharedWorkspaceResult(
                    operation = operationLabel,
                    success = false,
                    message = if (failure is GitAccessUnavailableException) failure.message.orEmpty()
                        else "No se pudo completar la operación (${failure.javaClass.simpleName}).",
                    repositoryPath = sharedWorkspaceProbe.repositoryDirectory.absolutePath,
                )
            } finally {
                activeWorkspaceOperation = null
                runningOperation = null
            }
        }
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { registry.load() }
        val migrations = withContext(Dispatchers.IO) { runCatching { migrationManager.pending() } }
        migrations.onSuccess { pendingMigrations = it }.onFailure {
            migrationJournalError = "El registro de una migración está dañado. RobGit ha bloqueado las operaciones para proteger tus repositorios."
        }
        if (repositorySelectionNeedsReset(uiRepositoryId, loaded.selectedRepositoryId)) {
            repositoryPrepared = null
            repositoryState = null
            lastOperation = null
            noticeTitle = null
            noticeBody = null
            noticeRecommendation = null
        }
        uiRepositoryId = loaded.selectedRepositoryId
        catalog = loaded
    }

    fun clearNotice() { noticeTitle = null; noticeBody = null; noticeRecommendation = null }
    fun setNotice(title: String, body: String, recommendation: String? = null) {
        noticeTitle = title; noticeBody = body; noticeRecommendation = recommendation
    }
    fun gitAuthMessage(): String = when (authService.state.value) {
        GitHubConnectionState.NeedsReauth -> "Es necesario volver a conectar GitHub desde Ajustes."
        GitHubConnectionState.NotConfigured -> "La conexión con GitHub no está configurada localmente."
        is GitHubConnectionState.Connected -> "RobGit no tiene acceso a este repositorio desde GitHub."
        else -> "Conecta RobGit con GitHub desde Ajustes para continuar."
    }
    fun operationDirectory(): File? {
        val config = selectedRepository ?: return null
        if (pendingMigrations == null || selectedPendingMigration != null || migrationJournalError != null) return null
        return try { workspaceResolver.resolve(config) } catch (failure: Exception) {
            setNotice("No se puede acceder al workspace de este repositorio.", failure.message.orEmpty())
            null
        }
    }
    fun clearRepositoryUi() {
        repositoryPrepared = null
        repositoryState = null
        lastOperation = null
        clearNotice()
    }
    fun runMigration(config: RepositoryConfig) {
        if (runningOperation != null || migrationJournalError != null) return
        if (!hasAllFilesAccess) {
            migrationPhase = null
            migrationDialogError = "RobGit necesita acceso al workspace compartido para migrar este repositorio."
            migrationDialogVisible = true
            return
        }
        migrationDialogVisible = true
        migrationDialogError = null
        migrationPhase = MigrationPhase.PLANNED
        runningOperation = "migrando"
        scope.launch {
            try {
                val completed = withContext(Dispatchers.IO) {
                    var analyzed: RepositoryStateSnapshot? = null
                    gitOperations.migrate(migrationManager, config.id, onAnalyzed = { analyzed = it }) { phase ->
                        scope.launch { if (migrationPhase != MigrationPhase.COMPLETED) migrationPhase = phase }
                    }
                    Triple(analyzed, registry.load(), migrationManager.pending())
                }
                clearRepositoryUi()
                catalog = completed.second
                pendingMigrations = completed.third
                repositoryPrepared = completed.first?.let { true }
                repositoryState = null
                lastOperation = RestoredOperation("MIGRACIÓN", "OK", "Workspace compartido activado y verificado.", null)
                migrationPhase = MigrationPhase.COMPLETED
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                hasAllFilesAccess = Environment.isExternalStorageManager()
                migrationDialogError = failure.message ?: "La migración se detuvo. La copia privada permanece intacta."
                if (failure is MigrationAuthenticationRequiredException || failure is GitAccessUnavailableException)
                    migrationDialogError = gitAuthMessage()
                runCatching { withContext(Dispatchers.IO) { registry.load() } }.onSuccess { catalog = it }
                runCatching { withContext(Dispatchers.IO) { migrationManager.pending() } }.onSuccess { pendingMigrations = it }
            } finally {
                runningOperation = null
            }
        }
    }
    fun selectRepository(id: String) {
        if (!repositorySelectorEnabled(runningOperation) || selectedRepository?.id == id) return
        runningOperation = "seleccionando"
        scope.launch {
            try {
                val next = withContext(Dispatchers.IO) { registry.select(id) }
                clearRepositoryUi()
                uiRepositoryId = next.selectedRepositoryId
                runningOperation = null
                catalog = next
            } catch (failure: Exception) {
                runningOperation = null
                setNotice("No se pudo cambiar de repositorio.", failure.message.orEmpty())
            }
        }
    }
    fun addRepository(name: String, url: String, branch: String) {
        if (runningOperation != null) return
        runningOperation = "añadiendo"
        addError = null
        scope.launch {
            try {
                val next = withContext(Dispatchers.IO) { registry.add(name, url, branch) }
                clearRepositoryUi()
                uiRepositoryId = next.selectedRepositoryId
                showAddRepository = false
                runningOperation = null
                catalog = next
            } catch (failure: Exception) {
                addError = failure.message ?: "No se pudo añadir el repositorio."
                runningOperation = null
            }
        }
    }
    fun analyze() {
        val directory = operationDirectory() ?: return
        val service = repositoryService ?: return
        if (runningOperation != null) return
        runningOperation = "analizando"; clearNotice()
        scope.launch {
            try {
                val prepared = repositoryPrepared == true || withContext(Dispatchers.IO) { service.isPrepared(directory) }
                if (!prepared) {
                    repositoryPrepared = false
                    return@launch
                }
                repositoryPrepared = true
                val result = withContext(Dispatchers.IO) { gitOperations.analyze(service, directory) }
                repositoryState = result
                lastOperation = RestoredOperation("ANALIZAR AHORA", result.type.name, result.message, result.error)
                if (result.authenticationRequired) setNotice(gitAuthMessage(), "Tus archivos locales permanecen intactos.")
            } catch (failure: GitAccessUnavailableException) {
                setNotice(failure.message.orEmpty(), "Tus archivos locales permanecen intactos.")
            } finally { runningOperation = null }
        }
    }
    fun prepareRepository() {
        val directory = operationDirectory() ?: return
        val service = repositoryService ?: return
        if (runningOperation != null) return
        runningOperation = "preparando"; clearNotice()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { gitOperations.prepare(service, directory) }
                repositoryPrepared = result.success
                lastOperation = RestoredOperation("PREPARAR REPOSITORIO", if (result.success) "OK" else "ERROR", result.message, result.error)
                if (result.success) {
                    repositoryState = null
                }
                else {
                    setNotice(if (result.authenticationRequired) gitAuthMessage() else result.message,
                        "Tus archivos locales están protegidos.")
                }
            } catch (failure: GitAccessUnavailableException) {
                setNotice(failure.message.orEmpty(), "Tus archivos locales permanecen intactos.")
            } finally { runningOperation = null }
        }
    }
    fun pull() {
        val directory = operationDirectory() ?: return
        val service = repositoryService ?: return
        val repositoryId = selectedRepository?.id ?: return
        if (runningOperation != null) return
        runningOperation = "PULL"; clearNotice()
        scope.launch {
            val performance = PullPerformanceRecorder()
            try {
                val result = withContext(Dispatchers.IO) { gitOperations.pull(service, directory, performance) }
                result.finalState?.let { repositoryState = it }
                lastOperation = RestoredOperation("PULL", result.outcome.name, result.message, result.error)
                RepositoryStatusPresenter.present(result).let { setNotice(it.title, it.explanation, it.recommendation) }
                if (result.finalState?.authenticationRequired == true) setNotice(gitAuthMessage(), "Tus archivos locales permanecen intactos.")
            } catch (failure: GitAccessUnavailableException) {
                setNotice(failure.message.orEmpty(), "Tus archivos locales permanecen intactos.")
            } finally {
                PullPerformanceStore.process.record(repositoryId, performance.snapshot())
                runningOperation = null
            }
        }
    }
    fun upload(commitMessage: String) {
        val directory = operationDirectory() ?: return
        val service = repositoryService ?: return
        if (runningOperation != null) return
        runningOperation = "PUSH"; clearNotice()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { gitOperations.push(service, directory, commitMessage) }
                result.finalState?.let { repositoryState = it }
                lastOperation = RestoredOperation("PUSH", result.outcome.name, result.message, result.error)
                RepositoryStatusPresenter.present(result).let { setNotice(it.title, it.explanation, it.recommendation) }
            } catch (failure: GitAccessUnavailableException) {
                setNotice(failure.message.orEmpty(), "No se realizó ningún push; tu trabajo local sigue intacto.")
            } finally { runningOperation = null }
        }
    }
    fun requestPushCommitMessage() {
        if (runningOperation != null) return
        runningOperation = "comprobando conexión con GitHub"
        clearNotice()
        scope.launch {
            try {
                val authenticated = withContext(Dispatchers.IO) { hasPushAuthentication(authService) }
                if (!authenticated) {
                    commitPurpose = null
                    setNotice(gitAuthMessage(), "No se realizó ningún push; tu trabajo local sigue intacto.")
                } else {
                    commitPurpose = CommitPurpose.PUSH
                }
            } catch (failure: Exception) {
                commitPurpose = null
                setNotice(gitAuthMessage(), "No se realizó ningún push; tu trabajo local sigue intacto.")
            } finally { runningOperation = null }
        }
    }
    fun synchronize(commitMessage: String = "Cambios desde RobGit") {
        val directory = operationDirectory() ?: return
        val service = repositoryService ?: return
        if (runningOperation != null) return
        runningOperation = "SINCRONIZAR"; clearNotice()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { gitOperations.synchronize(service, directory, commitMessage) }
                result.finalState?.let { repositoryState = it }
                lastOperation = RestoredOperation("SINCRONIZAR", result.outcome.name, result.message, result.error)
                RepositoryStatusPresenter.present(result).let { setNotice(it.title, it.explanation, it.recommendation) }
                if (result.outcome == SynchronizationOutcome.AUTH_REQUIRED) setNotice(gitAuthMessage(), "Tus archivos locales permanecen intactos.")
            } catch (failure: GitAccessUnavailableException) {
                setNotice(failure.message.orEmpty(), "Tus archivos locales permanecen intactos.")
            } finally { runningOperation = null }
        }
    }

    LaunchedEffect(selectedRepository?.id, selectedRepository?.workspaceLocation, hasAllFilesAccess, pendingMigrations) {
        val directory = selectedRepository?.takeIf { pendingMigrations != null &&
            pendingMigrations?.none { pending -> pending.repositoryId == it.id } == true && migrationJournalError == null
        }?.let { runCatching { workspaceResolver.resolve(it) }.getOrNull() }
        val service = repositoryService
        if (directory != null && service != null && repositoryPrepared == null) {
            try {
                val prepared = withContext(Dispatchers.IO) { service.isPrepared(directory) }
                repositoryPrepared = prepared
            } catch (_: Exception) {
                repositoryPrepared = false
            }
        }
    }
    LaunchedEffect(foregroundReturn) {
        if (foregroundReturn > 0) {
            hasAllFilesAccess = Environment.isExternalStorageManager()
        }
    }

    val baseStatus = when {
        migrationJournalError != null -> RepositoryHumanStatus("Migración pendiente de revisión.",
            requireNotNull(migrationJournalError), blocked = true)
        selectedPendingMigration != null -> RepositoryHumanStatus("Migración pendiente de completar.",
            "La copia privada sigue protegida. Reanuda la migración antes de utilizar este repositorio.", blocked = true)
        sharedAccessMissing -> RepositoryHumanStatus("Workspace compartido sin acceso.",
            "RobGit necesita acceso al workspace compartido para utilizar este repositorio.", blocked = true)
        workspaceResolutionError != null -> RepositoryHumanStatus("Workspace no disponible.",
            "RobGit no puede acceder al workspace de este repositorio. ${workspaceResolutionError}", blocked = true)
        catalog != null && selectedRepository == null -> RepositoryStatusPresenter.noRepositories
        repositoryPrepared == false -> RepositoryStatusPresenter.notPrepared
        repositoryState != null -> RepositoryStatusPresenter.present(requireNotNull(repositoryState))
        else -> RepositoryStatusPresenter.notUpdated
    }
    val displayedStatus = when {
        runningOperation == "migrando" -> RepositoryHumanStatus("Migrando repositorio…",
            migrationPhase?.humanLabel() ?: "Preparando…", blocked = true)
        migrationJournalError != null || selectedPendingMigration != null || sharedAccessMissing || workspaceResolutionError != null -> baseStatus
        noticeTitle != null -> RepositoryHumanStatus(requireNotNull(noticeTitle), noticeBody.orEmpty(), noticeRecommendation)
        else -> baseStatus
    }
    val supportActionLabel = when {
        migrationJournalError != null -> null
        selectedPendingMigration != null && !hasAllFilesAccess -> "CONCEDER ACCESO"
        selectedPendingMigration != null -> "REANUDAR MIGRACIÓN"
        sharedAccessMissing -> "CONCEDER ACCESO"
        else -> null
    }
    val supportAction: () -> Unit = {
        if (supportActionLabel == "CONCEDER ACCESO") requestWorkspaceAccess()
        else selectedRepository?.let(::runMigration)
    }

    Surface(color = RobGitColors.Petroleum, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
            val landscapeTablet = tablet && maxWidth > maxHeight
            val outerPadding = if (tablet) RobGitDimens.TabletPadding else RobGitDimens.PhonePadding
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(outerPadding), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.fillMaxWidth().widthIn(max = RobGitDimens.ContentMax)) {
                    RobGitHeader({ analyze() }, { showTechnical = true }, { showSettings = true })
                    Spacer(Modifier.height(18.dp))
                    if (landscapeTablet) {
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1.15f)) {
                                RepositorySelector(catalog, repositorySelectorEnabled(runningOperation), ::selectRepository, { showAddRepository = true }); Spacer(Modifier.height(22.dp))
                                HumanStatusPanel(displayedStatus, runningOperation != null, repositoryPrepared == false && supportActionLabel == null && workspaceResolutionError == null && migrationJournalError == null, selectedRepository == null && catalog != null, { prepareRepository() }, { showAddRepository = true }, supportActionLabel, supportAction)
                            }
                            ActionGrid(baseStatus, functionalRepository != null && repositoryPrepared == true && runningOperation == null, { pull() },
                                ::requestPushCommitMessage,
                                { if (baseStatus.recommendedAction == RepositoryAction.PUSH) commitPurpose = CommitPurpose.SYNCHRONIZE else synchronize() },
                                { showAi = true }, Modifier.weight(.85f))
                        }
                    } else {
                        RepositorySelector(catalog, repositorySelectorEnabled(runningOperation), ::selectRepository, { showAddRepository = true }); Spacer(Modifier.height(22.dp))
                        HumanStatusPanel(displayedStatus, runningOperation != null, repositoryPrepared == false && supportActionLabel == null && workspaceResolutionError == null && migrationJournalError == null, selectedRepository == null && catalog != null, { prepareRepository() }, { showAddRepository = true }, supportActionLabel, supportAction)
                        Spacer(Modifier.height(if (tablet) 28.dp else 22.dp))
                        ActionGrid(baseStatus, functionalRepository != null && repositoryPrepared == true && runningOperation == null, { pull() },
                            ::requestPushCommitMessage,
                            { if (baseStatus.recommendedAction == RepositoryAction.PUSH) commitPurpose = CommitPurpose.SYNCHRONIZE else synchronize() },
                            { showAi = true }, Modifier.align(Alignment.CenterHorizontally).widthIn(max = 440.dp))
                    }
                }
            }
        }
    }

    LaunchedEffect(showSettings, catalog) {
        if (showSettings) {
            preparedRepositories = withContext(Dispatchers.IO) {
                catalog?.repositories?.associate { config ->
                    config.id to runCatching {
                        RepositoryStateService(repositoryUrl = config.remoteUrl, branch = config.branch)
                            .isPrepared(workspaceResolver.resolve(config))
                    }.getOrDefault(false)
                }.orEmpty()
            }
        }
    }
    val technicalDirectory = functionalRepository ?: selectedRepository?.let {
        runCatching { File(workspaceResolver.rootFor(it.workspaceLocation), it.localDirectoryName) }.getOrNull()
    }
    val backupDirectory = remember(selectedRepository?.id, selectedRepository?.workspaceLocation, pendingMigrations) {
        selectedRepository?.id?.let { id ->
            runCatching { migrationManager.receipt(id) }.getOrNull()?.let { receipt ->
                File(workspaceResolver.rootFor(WorkspaceLocation.APP_PRIVATE), receipt.sourceDirectory)
            }
        }
    }
    if (showTechnical) TechnicalDetailsDialog(selectedRepository, technicalDirectory, backupDirectory, repositoryState,
        lastOperation, pullPerformanceByRepository[selectedRepository?.id]) { showTechnical = false }
    if (showAi) AiAssistantDialog(
        context = aiContextBuilder.build(selectedRepository, repositoryState, displayedStatus,
            lastOperation?.let { AiLastOperation(it.operation, it.outcome, it.message, it.detail) }),
        service = aiAssistantService,
        onDismiss = { showAi = false },
    )
    if (showAddRepository) AddRepositoryDialog(
        error = addError,
        busy = runningOperation != null,
        onDismiss = { showAddRepository = false; addError = null },
        onAdd = ::addRepository,
    )
    pendingRemoval?.let { config ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null; showSettings = true },
            title = { Text("Quitar de RobGit") },
            text = { Text(if (config.workspaceLocation == WorkspaceLocation.SHARED_DOCUMENTS)
                "Se quitará este repositorio de RobGit. Su carpeta en Documents/RobGit permanecerá intacta."
                else "Se quitará este repositorio de RobGit. Su carpeta privada permanecerá intacta.") },
            confirmButton = { TextButton(onClick = {
                if (runningOperation != null) return@TextButton
                runningOperation = "quitando"
                scope.launch {
                    try {
                        val next = withContext(Dispatchers.IO) { registry.remove(config.id) }
                        if (repositorySelectionNeedsReset(uiRepositoryId, next.selectedRepositoryId)) clearRepositoryUi()
                        uiRepositoryId = next.selectedRepositoryId
                        pendingRemoval = null
                        runningOperation = null
                        catalog = next
                        showSettings = true
                    } catch (failure: Exception) {
                        pendingRemoval = null
                        runningOperation = null
                        setNotice("No se pudo quitar el repositorio.", failure.message.orEmpty())
                    }
                }
            }) { Text("QUITAR DE ROBGIT") } },
            dismissButton = { TextButton(onClick = { pendingRemoval = null; showSettings = true }) { Text("CANCELAR") } },
        )
    }
    confirmMigration?.let { config ->
        AlertDialog(
            onDismissRequest = { confirmMigration = null; showSettings = true },
            title = { Text(if (pendingMigrations?.any { it.repositoryId == config.id } == true)
                "Reanudar migración" else "Mover al workspace compartido") },
            text = { Text("RobGit copiará el repositorio completo a Documents/RobGit y comprobará la copia antes de utilizarla. La copia privada original NO se eliminará.") },
            confirmButton = {
                if (!hasAllFilesAccess) TextButton(onClick = ::requestWorkspaceAccess) { Text("CONCEDER ACCESO") }
                else Button(onClick = { confirmMigration = null; runMigration(config) }) { Text("CONTINUAR") }
            },
            dismissButton = { TextButton(onClick = { confirmMigration = null; showSettings = true }) { Text("CANCELAR") } },
        )
    }
    if (migrationDialogVisible) AlertDialog(
        onDismissRequest = { if (runningOperation != "migrando") migrationDialogVisible = false },
        title = { Text("Migración del repositorio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (runningOperation == "migrando") CircularProgressIndicator(Modifier.size(24.dp))
                Text(migrationPhase?.humanLabel() ?: "Preparando…")
                migrationDialogError?.let { Text(it, color = RobGitColors.Warning) }
                if (migrationPhase == MigrationPhase.COMPLETED) Text("El workspace compartido está activo. La copia privada permanece como backup.")
            }
        },
        confirmButton = {
            if (runningOperation != "migrando") TextButton(onClick = { migrationDialogVisible = false }) { Text("CERRAR") }
        },
        dismissButton = {
            if (runningOperation != "migrando" && !hasAllFilesAccess) TextButton(onClick = ::requestWorkspaceAccess) { Text("CONCEDER ACCESO") }
        },
    )
    if (showSharedWorkspace) SharedWorkspaceDiagnosticDialog(
        permissionGranted = hasAllFilesAccess,
        rootPath = sharedWorkspaceProbe.rootDirectory.absolutePath,
        repositoryPath = sharedWorkspaceProbe.repositoryDirectory.absolutePath,
        result = sharedWorkspaceResult,
        runningLabel = activeWorkspaceOperation,
        busy = runningOperation != null,
        onGrantAccess = ::requestWorkspaceAccess,
        onAction = { action ->
            when (action) {
                SharedWorkspaceAction.PUSH -> commitPurpose = CommitPurpose.WORKSPACE_PUSH
                SharedWorkspaceAction.PREPARE -> runSharedWorkspace(action)
                SharedWorkspaceAction.ANALYZE -> runSharedWorkspace(action)
                SharedWorkspaceAction.PULL -> runSharedWorkspace(action)
                SharedWorkspaceAction.SYNCHRONIZE -> commitPurpose = CommitPurpose.WORKSPACE_SYNCHRONIZE
                SharedWorkspaceAction.FILESYSTEM -> runSharedWorkspace(action)
            }
        },
        onDismiss = { showSharedWorkspace = false; sharedWorkspaceResult = null; activeWorkspaceOperation = null },
    )
    if (showSettings) SettingsDialog(runningOperation, catalog, preparedRepositories, pendingMigrations.orEmpty(), hasAllFilesAccess,
        migrationJournalError != null, githubState, ::connectGitHub, ::disconnectGitHub,
        localDiagnostic, remoteDiagnostic, pushDiagnostic, { showSettings = false },
        { config -> showSettings = false; pendingRemoval = config },
        { config -> showSettings = false; confirmMigration = config },
        {
            runningOperation = "diagnóstico local"; scope.launch {
                localDiagnostic = withContext(Dispatchers.IO) { gitService.runLocalDiagnostic(File(diagnosticRoot, UUID.randomUUID().toString())) }
                runningOperation = null
            }
        }, {
            runningOperation = "diagnóstico clone"; scope.launch {
                remoteDiagnostic = withContext(Dispatchers.IO) { gitService.runRemoteCloneDiagnostic(File(diagnosticRoot, "remote-clones/${UUID.randomUUID()}")) }
                runningOperation = null
            }
        }, {
            if (runningOperation == null) {
                runningOperation = "diagnóstico push"
                scope.launch {
                    try {
                        pushDiagnostic = withContext(Dispatchers.IO) {
                            gitOperations.authenticatedDiagnostic(gitService,
                                File(diagnosticRoot, "authenticated-push/${UUID.randomUUID()}"))
                        }
                    } catch (failure: GitAccessUnavailableException) {
                        setNotice(failure.message.orEmpty(), "No se realizó ningún push de diagnóstico.")
                    } finally { runningOperation = null }
                }
            }
        }, {
            showSettings = false
            sharedWorkspaceResult = null
            activeWorkspaceOperation = null
            showSharedWorkspace = true
        })
    commitPurpose?.let { purpose ->
        CommitMessageDialog({ commitPurpose = null }) { message ->
            commitPurpose = null
            when (purpose) {
                CommitPurpose.PUSH -> upload(message)
                CommitPurpose.SYNCHRONIZE -> synchronize(message)
                CommitPurpose.WORKSPACE_PUSH -> runSharedWorkspace(SharedWorkspaceAction.PUSH, message)
                CommitPurpose.WORKSPACE_SYNCHRONIZE -> runSharedWorkspace(SharedWorkspaceAction.SYNCHRONIZE, message)
            }
        }
    }
}

@Composable
private fun RobGitHeader(onAnalyze: () -> Unit, onTechnical: () -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Image(
            painterResource(R.drawable.ic_robgit_logo), null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(42.dp)
                .semantics { contentDescription = "Analizar ahora" }
                .clickable(role = Role.Button, onClick = onAnalyze),
        )
        Spacer(Modifier.width(12.dp)); Text("RobGit", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f))
        Box {
            Text("⋮", fontSize = 30.sp, textAlign = TextAlign.Center, modifier = Modifier.size(48.dp).semantics { contentDescription = "Abrir menú" }.clickable { open = true })
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem({ Text("DETALLES TÉCNICOS") }, { open = false; onTechnical() })
                DropdownMenuItem({ Text("AJUSTES") }, { open = false; onSettings() })
            }
        }
    }
}

@Composable
private fun RepositorySelector(
    catalog: RepositoryCatalog?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TitledFrame(title = "REPOSITORIO", modifier = Modifier.fillMaxWidth()) {
            Box {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }.padding(horizontal = 18.dp, vertical = 15.dp)) {
                    Text(catalog?.selected?.displayName ?: "Sin repositorios", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Text("▾", fontSize = 22.sp)
                }
                DropdownMenu(open && enabled, { open = false }) {
                    catalog?.repositories?.forEach { config ->
                        DropdownMenuItem(
                            { Text("${if (config.id == catalog.selectedRepositoryId) "✓  " else ""}${config.displayName}") },
                            { open = false; onSelect(config.id) },
                        )
                    }
                    DropdownMenuItem({ Text("+ AÑADIR REPOSITORIO") }, { open = false; onAdd() })
                }
            }
        }
    }
}

@Composable
private fun HumanStatusPanel(status: RepositoryHumanStatus, running: Boolean, notPrepared: Boolean, noRepositories: Boolean, onPrepare: () -> Unit, onAdd: () -> Unit, supportActionLabel: String? = null, onSupportAction: () -> Unit = {}) {
    TitledFrame(title = "ESTADO DEL REPOSITORIO", modifier = Modifier.fillMaxWidth(), contentColor = RobGitColors.PetroleumDark.copy(alpha = .48f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp)) {
            AnimatedContent(status.title, label = "estado") { Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(12.dp)); Text(status.explanation, color = RobGitColors.IceMuted, style = MaterialTheme.typography.bodyLarge)
            status.recommendation?.let { Spacer(Modifier.height(14.dp)); Text(it, color = if (status.blocked) RobGitColors.Warning else RobGitColors.Success, fontWeight = FontWeight.SemiBold) }
            if (running) { Spacer(Modifier.height(18.dp)); Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = RobGitColors.Ice); Spacer(Modifier.width(12.dp)); Text("RobGit está trabajando…") } }
            if (notPrepared) { Spacer(Modifier.height(18.dp)); OutlinedButton(onPrepare, modifier = Modifier.fillMaxWidth()) { Text("PREPARAR REPOSITORIO") } }
            if (noRepositories) { Spacer(Modifier.height(18.dp)); OutlinedButton(onAdd, modifier = Modifier.fillMaxWidth()) { Text("AÑADIR REPOSITORIO") } }
            supportActionLabel?.let { label -> Spacer(Modifier.height(18.dp)); OutlinedButton(onSupportAction, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text(label) } }
        }
    }
}

/** Rounded frame whose top stroke is drawn in two pieces around its integrated title. */
@Composable
private fun TitledFrame(
    title: String,
    modifier: Modifier = Modifier,
    contentColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val titleStyle = MaterialTheme.typography.labelLarge
    val titleLayout = androidx.compose.ui.text.rememberTextMeasurer().measure(
        androidx.compose.ui.text.AnnotatedString(title),
        style = titleStyle.copy(fontWeight = FontWeight.Bold),
    )
    val shape = RoundedCornerShape(RobGitDimens.Radius)
    val titleStart = RobGitDimens.Radius + 24.dp
    Box(modifier.padding(top = 10.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .padding(top = 22.dp)
                .then(if (contentColor != null) Modifier.background(contentColor, shape) else Modifier),
        ) {
            content()
        }
        Canvas(Modifier.matchParentSize()) {
            val stroke = RobGitDimens.Border.toPx()
            val radius = RobGitDimens.Radius.toPx()
            val inset = stroke / 2f
            val top = titleLayout.size.height / 2f
            val gapStart = titleStart.toPx() - 8.dp.toPx()
            val gapEnd = titleStart.toPx() + titleLayout.size.width + 8.dp.toPx()
            val width = size.width
            val height = size.height
            val path = Path().apply {
                moveTo(inset + radius, top)
                lineTo(gapStart, top)
                moveTo(gapEnd, top)
                lineTo(width - inset - radius, top)
                arcTo(Rect(width - inset - 2 * radius, top, width - inset, top + 2 * radius), 270f, 90f, false)
                lineTo(width - inset, height - inset - radius)
                arcTo(Rect(width - inset - 2 * radius, height - inset - 2 * radius, width - inset, height - inset), 0f, 90f, false)
                lineTo(inset + radius, height - inset)
                arcTo(Rect(inset, height - inset - 2 * radius, inset + 2 * radius, height - inset), 90f, 90f, false)
                lineTo(inset, top + radius)
                arcTo(Rect(inset, top, inset + 2 * radius, top + 2 * radius), 180f, 90f, false)
            }
            drawPath(path, RobGitColors.Ice, style = Stroke(width = stroke))
        }
        Text(
            title,
            style = titleStyle,
            fontWeight = FontWeight.Bold,
            color = RobGitColors.Ice,
            modifier = Modifier.align(Alignment.TopStart).padding(start = titleStart),
        )
    }
}

@Composable
private fun ActionGrid(status: RepositoryHumanStatus, enabled: Boolean, onPull: () -> Unit, onPush: () -> Unit, onSync: () -> Unit, onAi: () -> Unit, modifier: Modifier = Modifier) {
    val pullVisual = actionButtonVisualState(status, enabled, RepositoryAction.PULL)
    val pushVisual = actionButtonVisualState(status, enabled, RepositoryAction.PUSH)
    val syncVisual = actionButtonVisualState(status, enabled, RepositoryAction.SYNCHRONIZE)
    val aiVisual = actionButtonVisualState(status, enabled, RepositoryAction.AI)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            RobGitActionButton("↓", "PULL", pullVisual, onPull, Modifier.weight(1f))
            RobGitActionButton("↑", "PUSH", pushVisual, onPush, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            RobGitActionButton("↕", "SINCRONIZAR", syncVisual, onSync, Modifier.weight(1f))
            RobGitActionButton("✦", "IA", aiVisual, onAi, Modifier.weight(1f), RobGitColors.Ai)
        }
    }
}

internal data class ActionButtonVisualState(
    val enabled: Boolean,
    val recommended: Boolean,
    val borderWidth: androidx.compose.ui.unit.Dp,
    val borderAlpha: Float,
    val contentAlpha: Float,
)

internal fun actionButtonVisualState(
    status: RepositoryHumanStatus,
    screenEnabled: Boolean,
    action: RepositoryAction,
): ActionButtonVisualState {
    val enabled = screenEnabled && when (action) {
        RepositoryAction.PULL -> status.pullEnabled
        RepositoryAction.PUSH -> status.pushEnabled
        RepositoryAction.SYNCHRONIZE, RepositoryAction.AI -> true
    }
    return ActionButtonVisualState(
        enabled = enabled,
        recommended = status.recommendedAction == action,
        borderWidth = RobGitDimens.Border,
        borderAlpha = if (enabled) 1f else .6f,
        contentAlpha = if (enabled) 1f else .42f,
    )
}

@Composable
private fun RobGitActionButton(icon: String, label: String, visual: ActionButtonVisualState, onClick: () -> Unit, modifier: Modifier, iconColor: Color = RobGitColors.Ice) {
    val interaction = remember { MutableInteractionSource() }; val pressed by interaction.collectIsPressedAsState()
    val elevation by animateDpAsState(if (pressed) 1.dp else 5.dp, label = "relieve"); val shape = RoundedCornerShape(RobGitDimens.Radius)
    Box(contentAlignment = Alignment.Center, modifier = modifier.widthIn(max = RobGitDimens.ButtonMax).aspectRatio(1f).shadow(elevation, shape, ambientColor = RobGitColors.PetroleumDeep, spotColor = RobGitColors.PetroleumDeep).background(RobGitColors.PetroleumDark, shape).clickable(interactionSource = interaction, indication = null, enabled = visual.enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label }) {
        Column(Modifier.alpha(visual.contentAlpha), horizontalAlignment = Alignment.CenterHorizontally) { Text(icon, fontSize = 42.sp, color = iconColor, lineHeight = 44.sp); Spacer(Modifier.height(7.dp)); Text(label, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
        Box(Modifier.matchParentSize().border(visual.borderWidth, (if (visual.recommended) Color.White else RobGitColors.Ice).copy(alpha = visual.borderAlpha), shape))
    }
}

@Composable
private fun CommitMessageDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var message by remember { mutableStateOf("Cambios desde RobGit") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Mensaje del cambio") }, text = {
        OutlinedTextField(message, { message = it }, label = { Text("Mensaje del cambio") }, singleLine = true)
    }, confirmButton = { Button(enabled = message.isNotBlank(), onClick = { onConfirm(message.trim()) }) {
        Text("CONTINUAR")
    } }, dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR") } })
}

@Composable
private fun AddRepositoryDialog(
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir repositorio") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("NOMBRE") }, placeholder = { Text("Nimroel RPG") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("URL DE GITHUB") }, placeholder = { Text("https://github.com/usuario/repositorio") }, singleLine = true)
                OutlinedTextField(branch, { branch = it }, label = { Text("RAMA") }, singleLine = true)
                error?.let { Text(it, color = RobGitColors.Error) }
            }
        },
        confirmButton = { Button(onClick = { onAdd(name, url, branch) }, enabled = !busy) { Text("AÑADIR") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("CANCELAR") } },
    )
}

@Composable
private fun TechnicalDetailsDialog(config: RepositoryConfig?, directory: File?, backupDirectory: File?, state: RepositoryStateSnapshot?,
                                   operation: RestoredOperation?, pullPerformance: PullPerformanceReport?, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Detalles técnicos") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("RENDIMIENTO ÚLTIMO PULL", color = RobGitColors.IceMuted, fontWeight = FontWeight.Bold)
            pullPerformanceLines(pullPerformance).forEach { Text(it) }
            if (pullPerformance?.timings?.isNotEmpty() == true) {
                Text("Total desde OAuth hasta completar la operación. Solo se muestran las fases ejecutadas.",
                    style = MaterialTheme.typography.bodySmall)
            }
            config?.let {
                Text("Nombre: ${it.displayName}")
                Text("URL: ${it.remoteUrl}")
                Text("Rama configurada: ${it.branch}")
                Text("Ubicación: ${if (it.workspaceLocation == WorkspaceLocation.SHARED_DOCUMENTS) "Workspace compartido" else "Privada"}")
                Text("Directorio local: ${directory?.absolutePath ?: "desconocido"}")
                backupDirectory?.let { backup -> Text("Backup privado conservado: ${backup.absolutePath}") }
            }
            if (state == null) Text("Todavía no hay un análisis disponible.") else {
                Text("Rama actual: ${state.branch ?: "desconocida"}"); Text("HEAD: ${state.localHead ?: "desconocido"}"); Text("origin/${config?.branch ?: "?"}: ${state.remoteHead ?: "desconocido"}")
                Text("Ahead: ${state.ahead} · Behind: ${state.behind}"); Text("Working tree: ${if (state.changes.hasChanges) "con cambios" else "limpio"}")
                TechnicalFiles("Nuevos", state.changes.newFiles); TechnicalFiles("Modificados", state.changes.modifiedFiles); TechnicalFiles("Eliminados", state.changes.deletedFiles); TechnicalFiles("Staged", state.changes.stagedFiles); TechnicalFiles("Conflictos", state.changes.conflictingFiles)
                Text("Remoto actualizado: ${if (state.remoteStateIsFresh) "sí" else "no"}"); Text("Comprobado: ${state.checkedAt}"); state.error?.let { Text("Error: $it", color = RobGitColors.Error) }
            }
            operation?.let { Text("Última operación: ${it.operation} — ${it.outcome}") }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("CERRAR") } })
}

@Composable private fun TechnicalFiles(label: String, files: Set<String>) { Text("$label (${files.size}): ${if (files.isEmpty()) "—" else files.joinToString()}") }

@Composable
private fun SettingsDialog(running: String?, catalog: RepositoryCatalog?, prepared: Map<String, Boolean>, pending: List<MigrationRecord>, permissionGranted: Boolean, journalBlocked: Boolean, githubState: GitHubConnectionState, onGitHubConnect: () -> Unit, onGitHubDisconnect: () -> Unit, localResult: DiagnosticResult?, remoteResult: DiagnosticResult?, pushResult: DiagnosticResult?, onDismiss: () -> Unit, onRemove: (RepositoryConfig) -> Unit, onMigrate: (RepositoryConfig) -> Unit, onLocal: () -> Unit, onRemote: () -> Unit, onAuthenticated: () -> Unit, onSharedWorkspace: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ajustes") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CONEXIÓN CON GITHUB", color = RobGitColors.IceMuted, fontWeight = FontWeight.Bold)
            Text(when (githubState) {
                GitHubConnectionState.NotConfigured -> "OAuth no está configurado localmente."
                GitHubConnectionState.Disconnected -> "No conectado"
                GitHubConnectionState.Authorizing -> "Esperando autorización de GitHub…"
                is GitHubConnectionState.Connected -> "Conectado como ${githubState.login}"
                GitHubConnectionState.Refreshing -> "Renovando conexión con GitHub…"
                GitHubConnectionState.NeedsReauth -> "Es necesario volver a conectar GitHub"
                is GitHubConnectionState.Error -> githubState.message
            })
            Button(onGitHubConnect, enabled = running == null && githubState != GitHubConnectionState.NotConfigured &&
                githubState != GitHubConnectionState.Authorizing && githubState != GitHubConnectionState.Refreshing,
                modifier = Modifier.fillMaxWidth()) { Text("CONECTAR CON GITHUB") }
            OutlinedButton(onGitHubDisconnect, enabled = running == null && githubState != GitHubConnectionState.Disconnected &&
                githubState != GitHubConnectionState.NotConfigured && githubState != GitHubConnectionState.Refreshing,
                modifier = Modifier.fillMaxWidth()) { Text("DESCONECTAR") }
            HorizontalDivider(color = RobGitColors.Ice.copy(alpha = .35f))
            Text("REPOSITORIOS", color = RobGitColors.IceMuted, fontWeight = FontWeight.Bold)
            catalog?.repositories?.forEach { config ->
                Text(config.displayName, fontWeight = FontWeight.Bold)
                Text(config.remoteUrl, style = MaterialTheme.typography.bodySmall)
                Text("Rama: ${config.branch} · ${if (config.workspaceLocation == WorkspaceLocation.SHARED_DOCUMENTS && !permissionGranted) "sin acceso" else if (prepared[config.id] == true) "preparado" else "no preparado"}", style = MaterialTheme.typography.bodySmall)
                Text("Ubicación: ${if (config.workspaceLocation == WorkspaceLocation.SHARED_DOCUMENTS) "Documents/RobGit" else "Privada"}", style = MaterialTheme.typography.bodySmall)
                if (config.workspaceLocation == WorkspaceLocation.APP_PRIVATE || pending.any { it.repositoryId == config.id }) {
                    TextButton(onClick = { onMigrate(config) }, enabled = running == null && !journalBlocked) {
                        Text(if (pending.any { it.repositoryId == config.id }) "REANUDAR MIGRACIÓN" else "MOVER AL WORKSPACE COMPARTIDO")
                    }
                }
                TextButton(onClick = { onRemove(config) }, enabled = running == null && !journalBlocked && pending.none { it.repositoryId == config.id }) { Text("QUITAR DE ROBGIT") }
            }
            Text("AVANZADO / DIAGNÓSTICO", color = RobGitColors.IceMuted, fontWeight = FontWeight.Bold)
            Button(onSharedWorkspace, enabled = running == null, modifier = Modifier.fillMaxWidth()) { Text("PRUEBA WORKSPACE COMPARTIDO") }
            Button(onLocal, enabled = running == null, modifier = Modifier.fillMaxWidth()) { Text("PRUEBA LOCAL JGIT") }; DiagnosticSummary(localResult)
            Button(onRemote, enabled = running == null, modifier = Modifier.fillMaxWidth()) { Text("CLONE HTTPS") }; DiagnosticSummary(remoteResult)
            Button(onAuthenticated, enabled = running == null, modifier = Modifier.fillMaxWidth()) { Text("PUSH HTTPS AUTENTICADO") }; DiagnosticSummary(pushResult)
            running?.let { Text("Ejecutando: $it…") }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("CERRAR") } })
}

private fun MigrationPhase.humanLabel(): String = when (this) {
    MigrationPhase.PLANNED -> "Preparando…"
    MigrationPhase.COPYING -> "Copiando archivos…"
    MigrationPhase.VERIFYING_TEMP -> "Verificando copia temporal…"
    MigrationPhase.FINALIZING -> "Activando carpeta final…"
    MigrationPhase.VERIFYING_FINAL -> "Verificando carpeta final…"
    MigrationPhase.SWITCHING_REGISTRY -> "Activando nuevo workspace y analizando…"
    MigrationPhase.COMPLETED -> "Completado."
}

@Composable
private fun SharedWorkspaceDiagnosticDialog(
    permissionGranted: Boolean,
    rootPath: String,
    repositoryPath: String,
    result: SharedWorkspaceResult?,
    runningLabel: String?,
    busy: Boolean,
    onGrantAccess: () -> Unit,
    onAction: (SharedWorkspaceAction) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Prueba workspace compartido") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("ACCESO AL WORKSPACE", fontWeight = FontWeight.Bold)
                Text(if (permissionGranted) "Concedido" else "No concedido", color = if (permissionGranted) RobGitColors.Success else RobGitColors.Warning)
                Text("Ruta raíz: $rootPath")
                Text("Repositorio de prueba: $SHARED_WORKSPACE_DIRECTORY")
                Text("Ruta del repositorio: $repositoryPath", style = MaterialTheme.typography.bodySmall)
                Text("Remoto: $SHARED_WORKSPACE_REMOTE", style = MaterialTheme.typography.bodySmall)
                Text("Rama: $SHARED_WORKSPACE_BRANCH")
                if (!permissionGranted) OutlinedButton(onGrantAccess, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("CONCEDER ACCESO") }
                HorizontalDivider(color = RobGitColors.Ice.copy(alpha = .35f))
                Text("RESULTADO", fontWeight = FontWeight.Bold, color = RobGitColors.IceMuted)
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = RobGitColors.Warning)
                    Spacer(Modifier.width(10.dp))
                    Text("Ejecutando ${runningLabel ?: "operación"}…", color = RobGitColors.Warning)
                }
                result?.let { latest ->
                    val feedback = sharedWorkspaceFeedback(latest)
                    Text(feedback.title, fontWeight = FontWeight.Bold,
                        color = if (latest.success) RobGitColors.Success else RobGitColors.Warning)
                    Text(feedback.detail, color = RobGitColors.IceMuted)
                }
                if (!busy && result == null) Text("Todavía no se ha ejecutado ninguna operación.", color = RobGitColors.IceMuted)
                val actions = listOf(
                    SharedWorkspaceAction.PREPARE to "PREPARAR WORKSPACE",
                    SharedWorkspaceAction.ANALYZE to "ANALIZAR WORKSPACE",
                    SharedWorkspaceAction.PULL to "PULL DE PRUEBA",
                    SharedWorkspaceAction.PUSH to "PUSH DE PRUEBA",
                    SharedWorkspaceAction.SYNCHRONIZE to "SINCRONIZAR DE PRUEBA",
                    SharedWorkspaceAction.FILESYSTEM to "PROBAR FILESYSTEM",
                )
                actions.forEach { (action, label) ->
                    OutlinedButton(onClick = { onAction(action) }, enabled = permissionGranted && !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss, enabled = !busy) { Text("CERRAR") } },
    )
}
@Composable private fun DiagnosticSummary(result: DiagnosticResult?) { result?.let { Text(if (it.error == null) "Correcto · ${it.status}" else "Error · ${it.error}", style = MaterialTheme.typography.bodySmall) } }

internal data class RestoredOperation(val operation: String, val outcome: String, val message: String, val detail: String?)
