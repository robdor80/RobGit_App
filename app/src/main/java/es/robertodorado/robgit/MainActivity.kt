package es.robertodorado.robgit

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val foregroundPolicy = ForegroundRefreshPolicy()
    private var foregroundReturn by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        val tablet = resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (tablet) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
        val diagnosticRoot = File(filesDir, "diagnostics")
        val functionalRepository = File(filesDir, "repos/robgit-pruebas")
        setContent {
            RobGitTheme {
                RobGitScreen(
                    diagnosticRoot, remember { GitRepositoryService() }, remember { RepositoryStateService() },
                    functionalRepository, foregroundReturn,
                )
            }
        }
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

private enum class AuthPurpose { PUSH, SYNCHRONIZE, DIAGNOSTIC }

@Composable
private fun RobGitScreen(
    diagnosticRoot: File,
    gitService: GitRepositoryService,
    repositoryService: RepositoryStateService,
    functionalRepository: File,
    foregroundReturn: Int,
) {
    var repositoryPrepared by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var repositoryState by rememberSaveable(stateSaver = repositoryStateSaver) { mutableStateOf<RepositoryStateSnapshot?>(null) }
    var lastOperation by rememberSaveable(stateSaver = lastOperationSaver) { mutableStateOf<RestoredOperation?>(null) }
    var noticeTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var noticeBody by rememberSaveable { mutableStateOf<String?>(null) }
    var noticeRecommendation by rememberSaveable { mutableStateOf<String?>(null) }
    var runningOperation by remember { mutableStateOf<String?>(null) }
    var showTechnical by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var authPurpose by remember { mutableStateOf<AuthPurpose?>(null) }
    var localDiagnostic by remember { mutableStateOf<DiagnosticResult?>(null) }
    var remoteDiagnostic by remember { mutableStateOf<DiagnosticResult?>(null) }
    var pushDiagnostic by remember { mutableStateOf<DiagnosticResult?>(null) }
    val scope = rememberCoroutineScope()

    fun clearNotice() { noticeTitle = null; noticeBody = null; noticeRecommendation = null }
    fun setNotice(title: String, body: String, recommendation: String? = null) {
        noticeTitle = title; noticeBody = body; noticeRecommendation = recommendation
    }
    fun analyze() {
        if (runningOperation != null || repositoryPrepared != true) return
        runningOperation = "analizando"; clearNotice()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repositoryService.refreshState(functionalRepository) }
                repositoryState = result
                lastOperation = RestoredOperation("ANALIZAR AHORA", result.type.name, result.message, result.error)
            } finally { runningOperation = null }
        }
    }
    fun prepareRepository() {
        if (runningOperation != null) return
        runningOperation = "preparando"; clearNotice()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repositoryService.prepare(functionalRepository) }
                repositoryPrepared = result.success
                lastOperation = RestoredOperation("PREPARAR REPOSITORIO", if (result.success) "OK" else "ERROR", result.message, result.error)
                if (result.success) repositoryState = withContext(Dispatchers.IO) { repositoryService.refreshState(functionalRepository) }
                else setNotice("No se ha podido preparar el repositorio.", "Tus archivos están protegidos.")
            } finally { runningOperation = null }
        }
    }
    fun pull() {
        if (runningOperation != null) return
        runningOperation = "PULL"; clearNotice()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repositoryService.downloadFastForward(functionalRepository) }
                result.finalState?.let { repositoryState = it }
                lastOperation = RestoredOperation("PULL", result.outcome.name, result.message, result.error)
                RepositoryStatusPresenter.present(result).let { setNotice(it.title, it.explanation, it.recommendation) }
            } finally { runningOperation = null }
        }
    }
    fun upload(token: CharArray, commitMessage: String) {
        runningOperation = "PUSH"; clearNotice()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repositoryService.uploadSafely(functionalRepository, token, commitMessage) }
                result.finalState?.let { repositoryState = it }
                lastOperation = RestoredOperation("PUSH", result.outcome.name, result.message, result.error)
                RepositoryStatusPresenter.present(result).let { setNotice(it.title, it.explanation, it.recommendation) }
                if (result.outcome == UploadOutcome.AUTH_REQUIRED) authPurpose = AuthPurpose.PUSH
            } finally { token.fill('\u0000'); runningOperation = null }
        }
    }
    fun synchronize(token: CharArray = charArrayOf(), commitMessage: String = "Cambios desde RobGit") {
        runningOperation = "SINCRONIZAR"; clearNotice()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repositoryService.synchronizeSafely(functionalRepository, token, commitMessage) }
                result.finalState?.let { repositoryState = it }
                lastOperation = RestoredOperation("SINCRONIZAR", result.outcome.name, result.message, result.error)
                RepositoryStatusPresenter.present(result).let { setNotice(it.title, it.explanation, it.recommendation) }
                if (result.outcome == SynchronizationOutcome.AUTH_REQUIRED) authPurpose = AuthPurpose.SYNCHRONIZE
            } finally { token.fill('\u0000'); runningOperation = null }
        }
    }

    LaunchedEffect(functionalRepository.absolutePath) {
        if (repositoryPrepared == null) {
            val prepared = withContext(Dispatchers.IO) { repositoryService.isPrepared(functionalRepository) }
            repositoryPrepared = prepared
            if (prepared) {
                runningOperation = "analizando"
                try { repositoryState = withContext(Dispatchers.IO) { repositoryService.refreshState(functionalRepository) } }
                finally { runningOperation = null }
            }
        }
    }
    LaunchedEffect(foregroundReturn) { if (foregroundReturn > 0 && repositoryPrepared == true) analyze() }

    val baseStatus = when {
        repositoryPrepared == false -> RepositoryStatusPresenter.notPrepared
        repositoryState != null -> RepositoryStatusPresenter.present(requireNotNull(repositoryState))
        else -> RepositoryStatusPresenter.analyzing
    }
    val displayedStatus = when {
        runningOperation == "analizando" -> RepositoryStatusPresenter.analyzing
        noticeTitle != null -> RepositoryHumanStatus(requireNotNull(noticeTitle), noticeBody.orEmpty(), noticeRecommendation)
        else -> baseStatus
    }

    Surface(color = RobGitColors.Petroleum, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
            val landscapeTablet = tablet && maxWidth > maxHeight
            val outerPadding = if (tablet) RobGitDimens.TabletPadding else RobGitDimens.PhonePadding
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(outerPadding), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.fillMaxWidth().widthIn(max = RobGitDimens.ContentMax)) {
                    RobGitHeader(::analyze, { showTechnical = true }, { showSettings = true })
                    Spacer(Modifier.height(18.dp))
                    if (landscapeTablet) {
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1.15f)) {
                                RepositorySelector(); Spacer(Modifier.height(22.dp))
                                HumanStatusPanel(displayedStatus, runningOperation != null, repositoryPrepared == false, ::prepareRepository)
                            }
                            ActionGrid(baseStatus, repositoryPrepared == true && runningOperation == null, ::pull,
                                { authPurpose = AuthPurpose.PUSH },
                                { if (baseStatus.recommendedAction == RepositoryAction.PUSH) authPurpose = AuthPurpose.SYNCHRONIZE else synchronize() },
                                { showAi = true }, Modifier.weight(.85f))
                        }
                    } else {
                        RepositorySelector(); Spacer(Modifier.height(22.dp))
                        HumanStatusPanel(displayedStatus, runningOperation != null, repositoryPrepared == false, ::prepareRepository)
                        Spacer(Modifier.height(if (tablet) 28.dp else 22.dp))
                        ActionGrid(baseStatus, repositoryPrepared == true && runningOperation == null, ::pull,
                            { authPurpose = AuthPurpose.PUSH },
                            { if (baseStatus.recommendedAction == RepositoryAction.PUSH) authPurpose = AuthPurpose.SYNCHRONIZE else synchronize() },
                            { showAi = true }, Modifier.align(Alignment.CenterHorizontally).widthIn(max = 440.dp))
                    }
                }
            }
        }
    }

    if (showTechnical) TechnicalDetailsDialog(repositoryState, lastOperation) { showTechnical = false }
    if (showAi) AiAssistantDialog { showAi = false }
    if (showSettings) SettingsDialog(runningOperation, localDiagnostic, remoteDiagnostic, pushDiagnostic, { showSettings = false },
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
        }, { authPurpose = AuthPurpose.DIAGNOSTIC })
    authPurpose?.let { purpose ->
        AuthenticationDialog(purpose, { authPurpose = null }) { token, message ->
            authPurpose = null
            when (purpose) {
                AuthPurpose.PUSH -> upload(token, message)
                AuthPurpose.SYNCHRONIZE -> synchronize(token, message)
                AuthPurpose.DIAGNOSTIC -> {
                    runningOperation = "diagnóstico push"; scope.launch {
                        try {
                            pushDiagnostic = withContext(Dispatchers.IO) {
                                gitService.runAuthenticatedPushDiagnostic(File(diagnosticRoot, "authenticated-push/${UUID.randomUUID()}"), token)
                            }
                        } finally { token.fill('\u0000'); runningOperation = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun RobGitHeader(onAnalyze: () -> Unit, onTechnical: () -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Image(painterResource(R.drawable.ic_robgit_logo), "Icono de RobGit", contentScale = ContentScale.Fit, modifier = Modifier.size(42.dp))
        Spacer(Modifier.width(12.dp)); Text("RobGit", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f))
        Box {
            Text("⋮", fontSize = 30.sp, textAlign = TextAlign.Center, modifier = Modifier.size(48.dp).semantics { contentDescription = "Abrir menú" }.clickable { open = true })
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem({ Text("ANALIZAR AHORA") }, { open = false; onAnalyze() })
                DropdownMenuItem({ Text("DETALLES TÉCNICOS") }, { open = false; onTechnical() })
                DropdownMenuItem({ Text("AJUSTES") }, { open = false; onSettings() })
            }
        }
    }
}

@Composable
private fun RepositorySelector() {
    var open by remember { mutableStateOf(false) }
    Box {
        TitledFrame(title = "REPOSITORIO", modifier = Modifier.fillMaxWidth()) {
            Box {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 18.dp, vertical = 15.dp)) {
                    Text("Robgit.pruebas", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Text("▾", fontSize = 22.sp)
                }
                DropdownMenu(open, { open = false }) { DropdownMenuItem({ Text("Robgit.pruebas") }, { open = false }) }
            }
        }
    }
}

@Composable
private fun HumanStatusPanel(status: RepositoryHumanStatus, running: Boolean, notPrepared: Boolean, onPrepare: () -> Unit) {
    TitledFrame(title = "ESTADO DEL REPOSITORIO", modifier = Modifier.fillMaxWidth(), contentColor = RobGitColors.PetroleumDark.copy(alpha = .48f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp)) {
            AnimatedContent(status.title, label = "estado") { Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(12.dp)); Text(status.explanation, color = RobGitColors.IceMuted, style = MaterialTheme.typography.bodyLarge)
            status.recommendation?.let { Spacer(Modifier.height(14.dp)); Text(it, color = if (status.blocked) RobGitColors.Warning else RobGitColors.Success, fontWeight = FontWeight.SemiBold) }
            if (running) { Spacer(Modifier.height(18.dp)); Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = RobGitColors.Ice); Spacer(Modifier.width(12.dp)); Text("RobGit está trabajando…") } }
            if (notPrepared) { Spacer(Modifier.height(18.dp)); OutlinedButton(onPrepare, modifier = Modifier.fillMaxWidth()) { Text("PREPARAR REPOSITORIO") } }
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
private fun AuthenticationDialog(purpose: AuthPurpose, onDismiss: () -> Unit, onConfirm: (CharArray, String) -> Unit) {
    var token by remember { mutableStateOf("") }; var message by remember { mutableStateOf("Cambios desde RobGit") }
    AlertDialog(onDismissRequest = { token = ""; onDismiss() }, title = { Text(if (purpose == AuthPurpose.DIAGNOSTIC) "Prueba autenticada" else "Autorizar en GitHub") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("El token solo se conserva en memoria durante esta operación.")
            OutlinedTextField(token, { token = it }, label = { Text("Token GitHub") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true)
            if (purpose != AuthPurpose.DIAGNOSTIC) OutlinedTextField(message, { message = it }, label = { Text("Mensaje del cambio") }, singleLine = true)
        }
    }, confirmButton = { Button(enabled = token.isNotBlank() && (purpose == AuthPurpose.DIAGNOSTIC || message.isNotBlank()), onClick = { val secret = token.toCharArray(); token = ""; onConfirm(secret, message.trim()) }) { Text("CONTINUAR") } }, dismissButton = { TextButton(onClick = { token = ""; onDismiss() }) { Text("CANCELAR") } })
}

@Composable
private fun AiAssistantDialog(onDismiss: () -> Unit) {
    val commands = listOf("Analizar el repositorio", "Explícame el estado actual", "¿Qué debería hacer ahora?", "Resumir los cambios", "Explicar un bloqueo o conflicto", "Pregunta personalizada")
    var selected by remember { mutableStateOf(commands.first()) }; var menu by remember { mutableStateOf(false) }; var question by remember { mutableStateOf("") }; var attempted by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Asistente RobGit") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box { OutlinedButton({ menu = true }, Modifier.fillMaxWidth()) { Text("$selected  ▾") }; DropdownMenu(menu, { menu = false }) { commands.forEach { c -> DropdownMenuItem({ Text(c) }, { selected = c; menu = false }) } } }
            OutlinedTextField(question, { question = it }, label = { Text("Pregunta libre") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            if (attempted) Text("El asistente IA todavía no está conectado. La interfaz está preparada para el próximo hito.", color = RobGitColors.Ai)
        }
    }, confirmButton = { Button({ attempted = true }) { Text("PREGUNTAR") } }, dismissButton = { TextButton(onDismiss) { Text("CERRAR") } })
}

@Composable
private fun TechnicalDetailsDialog(state: RepositoryStateSnapshot?, operation: RestoredOperation?, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Detalles técnicos") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state == null) Text("Todavía no hay un análisis disponible.") else {
                Text("Rama: ${state.branch ?: "desconocida"}"); Text("HEAD: ${state.localHead ?: "desconocido"}"); Text("origin/main: ${state.remoteHead ?: "desconocido"}")
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
private fun SettingsDialog(running: String?, localResult: DiagnosticResult?, remoteResult: DiagnosticResult?, pushResult: DiagnosticResult?, onDismiss: () -> Unit, onLocal: () -> Unit, onRemote: () -> Unit, onAuthenticated: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ajustes · Avanzado / Diagnóstico") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Herramientas de laboratorio", color = RobGitColors.IceMuted)
            Button(onLocal, enabled = running == null, modifier = Modifier.fillMaxWidth()) { Text("PRUEBA LOCAL JGIT") }; DiagnosticSummary(localResult)
            Button(onRemote, enabled = running == null, modifier = Modifier.fillMaxWidth()) { Text("CLONE HTTPS") }; DiagnosticSummary(remoteResult)
            Button(onAuthenticated, enabled = running == null, modifier = Modifier.fillMaxWidth()) { Text("PUSH HTTPS AUTENTICADO") }; DiagnosticSummary(pushResult)
            running?.let { Text("Ejecutando: $it…") }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("CERRAR") } })
}
@Composable private fun DiagnosticSummary(result: DiagnosticResult?) { result?.let { Text(if (it.error == null) "Correcto · ${it.status}" else "Error · ${it.error}", style = MaterialTheme.typography.bodySmall) } }

internal data class RestoredOperation(val operation: String, val outcome: String, val message: String, val detail: String?)
internal val lastOperationSaver = Saver<RestoredOperation?, Any>(save = { it?.let { o -> listOf(o.operation, o.outcome, o.message, o.detail) } }, restore = { saved ->
    val v = saved as? List<*> ?: return@Saver null
    RestoredOperation(v.getOrNull(0) as? String ?: return@Saver null, v.getOrNull(1) as? String ?: return@Saver null, v.getOrNull(2) as? String ?: return@Saver null, v.getOrNull(3) as? String)
})
internal val repositoryStateSaver = Saver<RepositoryStateSnapshot?, Any>(save = { s -> s?.let { listOf(it.type.name, it.relation.name, it.message, it.branch, it.localHead, it.remoteHead, it.ahead, it.behind, it.changes.newFiles.toList(), it.changes.modifiedFiles.toList(), it.changes.deletedFiles.toList(), it.changes.stagedFiles.toList(), it.changes.conflictingFiles.toList(), it.checkedAt.toString(), it.remoteStateIsFresh, it.error) } }, restore = { saved ->
    val v = saved as? List<*> ?: return@Saver null
    fun str(i: Int) = v.getOrNull(i) as? String
    fun files(i: Int) = (v.getOrNull(i) as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
    RepositoryStateSnapshot(str(0)?.let { runCatching { RepositoryStateType.valueOf(it) }.getOrNull() } ?: return@Saver null, str(1)?.let { runCatching { CommitRelation.valueOf(it) }.getOrNull() } ?: return@Saver null, str(2) ?: return@Saver null, str(3), str(4), str(5), v.getOrNull(6) as? Int ?: return@Saver null, v.getOrNull(7) as? Int ?: return@Saver null, WorkingTreeChanges(files(8), files(9), files(10), files(11), files(12)), str(13)?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } ?: return@Saver null, v.getOrNull(14) as? Boolean ?: return@Saver null, str(15))
})
