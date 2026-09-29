package es.robertodorado.robgit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.revwalk.RevWalk
import java.text.NumberFormat
import java.util.Locale

enum class SyncCategory(val label: String) {
    CONTROL("Coordinación y resto medido"),
    OAUTH("OAuth"),
    TRANSPORT("Fetch/push: transporte y procesamiento JGit"),
    JGIT("JGit local: status, grafo, árboles y contenido"),
    LOCAL_IO("I/O local observable y gestión del filesystem"),
    HASH("CPU SHA-256: actualizaciones del digest"),
    SAFETY("Comprobaciones propias de seguridad"),
}

/** Fixed labels only. No API accepts a path, ObjectId, credential, file content or error. */
enum class SyncPhase(val label: String, val category: SyncCategory = SyncCategory.CONTROL,
                     val aggregate: Boolean = false, val security: Boolean = false) {
    TOTAL("SYNC TOTAL"),
    OAUTH("OAuth: adquisición/validación", SyncCategory.OAUTH),
    ENGINE("Motor de sincronización"),
    CONFLICT_CHECK("Conflictos iniciales"),
    REFRESH("Fetch e inspección inicial"),
    INSPECT("inspectFetchedState", SyncCategory.JGIT),
    STATUS("git.status", SyncCategory.JGIT),
    RELATION("calculateRelation", SyncCategory.JGIT),
    REV_WALK("RevWalk explícito de RobGit", SyncCategory.JGIT),
    FETCH("Fetch", SyncCategory.TRANSPORT),
    CAPTURE("captureLocalWork", SyncCategory.SAFETY, security = true),
    INDEX_SNAPSHOT("readIndex: snapshot semántico", SyncCategory.SAFETY),
    INDEX_READ("DirCache.read explícito", SyncCategory.LOCAL_IO),
    WORKING_OBJECTS("readWorkingObjects", SyncCategory.JGIT),
    GIT_OBJECT_HASH("ObjectId del working tree (contenido Git)", SyncCategory.JGIT, aggregate = true),
    FINGERPRINT("fingerprint / SHA-256", SyncCategory.LOCAL_IO, aggregate = true),
    SHA_READ("Lecturas reales del stream SHA-256", SyncCategory.LOCAL_IO, aggregate = true),
    SHA_DIGEST("digest.update SHA-256", SyncCategory.HASH, aggregate = true),
    IGNORED("Expansión de ignorados", SyncCategory.LOCAL_IO),
    REMOTE_PATHS("affectedRemotePaths", SyncCategory.SAFETY, security = true),
    DIFF_SCAN("DiffFormatter.scan", SyncCategory.JGIT),
    RENAMES("Detección efectiva de renames: tareas JGit", SyncCategory.JGIT, aggregate = true),
    COLLISION("hasCollision", SyncCategory.SAFETY, security = true),
    TREE("readTree remoto", SyncCategory.JGIT),
    CHECKOUT_SAFETY("verifyCheckoutIsSafe", SyncCategory.SAFETY, security = true),
    PREFLIGHT("DirCacheCheckout.preScanTwoTrees", SyncCategory.JGIT),
    FAST_FORWARD("FF_ONLY / MergeCommand.call", SyncCategory.JGIT),
    PRESERVATION("verifyLocalWorkPreserved", SyncCategory.SAFETY, security = true),
    REMOTE_APPLIED("verifyRemoteFilesApplied", SyncCategory.SAFETY, security = true),
    UPLOAD("uploadSafely total"),
    ADD("add dirigido", SyncCategory.JGIT),
    ADD_UPDATE("add dirigido update (eliminaciones)", SyncCategory.JGIT),
    COMMIT("commit", SyncCategory.JGIT),
    PUSH("Push normal", SyncCategory.TRANSPORT),
    PUSH_VERIFICATION("Verificación posterior al push"),
}

enum class SyncCounter(val label: String) {
    STATUS_CALLS("Status explícitos"), FETCH_CALLS("Fetch"), PUSH_CALLS("Push"),
    RELATION_CALLS("Cálculos de relación"), REV_WALK_CALLS("RevWalk explícitos (sin internos JGit)"),
    CAPTURE_CALLS("Capturas locales"), INDEX_READS("Lecturas explícitas de índice (sin internos JGit)"),
    INDEX_ENTRIES("Entradas de índice procesadas, con repeticiones"),
    WORKING_PATHS("Rutas solicitadas a readWorkingObjects, con repeticiones"),
    WORKING_OBJECTS("Solicitudes de ObjectId del working tree"),
    FINGERPRINTS("Fingerprints solicitados"), SHA256_HASHES("SHA-256 completados"),
    SHA256_FILES("Archivos regulares abiertos para SHA-256, con repeticiones"),
    SHA256_BYTES("Bytes reales entregados por streams SHA-256, con repeticiones"),
    IGNORED_ROOTS("Rutas ignoradas comunicadas por status, con repeticiones"),
    IGNORED_VISITED("Entradas visitadas al expandir ignorados, con repeticiones"),
    IGNORED_EXPANDED("Rutas ignoradas expandidas, con repeticiones"),
    REMOTE_PATHS("Rutas remotas calculadas, con repeticiones"),
    DIFF_ENTRIES("Entradas de diff remoto, con repeticiones"),
    RENAME_ENABLED("Scans con rename detection habilitada"),
    RENAME_DISABLED("Scans con rename detection deshabilitada"),
    RENAMES_RETURNED("Entradas RENAME/COPY devueltas"),
    RENAME_WORK("Unidades de trabajo de renames comunicadas por JGit"),
    TREE_ENTRIES("Entradas de árboles remotos procesadas, con repeticiones"),
    NORMALIZATIONS("Normalizaciones de rutas"), COMPARISONS("Comparaciones de colisión de rutas"),
    PREFLIGHT_CALLS("Preflights explícitos (sin checkout interno JGit)"),
    FF_CALLS("FF_ONLY"), ADD_CALLS("Add"), COMMIT_CALLS("Commit"),
    STAGING_PATHS("Rutas de staging dirigido (contenido)"),
    STAGING_DELETE_PATHS("Rutas de staging dirigido (eliminaciones)"),
    GLOBAL_ADD_CALLS("Add globales"),
    STAGING_STREAMS("Streams abiertos por add dirigido (sin lecturas internas JGit)"),
    STAGING_STREAM_BYTES("Bytes reales leídos de streams de staging, tras filtros JGit"),
}

data class SyncTiming(val id: Int, val parentId: Int?, val phase: SyncPhase,
                      val calls: Long, val durationNanos: Long, val ownNanos: Long,
                      val counters: Map<SyncCounter, Long>)

data class SyncPerformanceReport(val timings: List<SyncTiming> = emptyList(),
                                 val counters: Map<SyncCounter, Long> = emptyMap()) {
    val totalNanos: Long? get() = timings.firstOrNull { it.phase == SyncPhase.TOTAL }?.durationNanos
    val accountedNanos: Long get() = timings.sumOf { it.ownNanos }
    val securityNanos: Long get() {
        val byId = timings.associateBy { it.id }
        return timings.filter { timing -> timing.phase.security &&
            generateSequence(timing.parentId) { byId[it]?.parentId }.none { byId[it]?.phase?.security == true }
        }.sumOf { it.durationNanos }
    }
}

/** One sequential operation. Per-file/chunk measurements are aggregated, not retained as events. */
class SyncPerformanceRecorder(private val nanoTime: () -> Long = System::nanoTime) {
    private class Node(val id: Int, val parentId: Int?, val phase: SyncPhase) {
        var calls = 0L
        var duration = 0L
        var own = 0L
        val counters = mutableMapOf<SyncCounter, Long>()
    }
    class Frame internal constructor(internal val nodeId: Int, internal val start: Long) {
        internal var children = 0L
    }
    private val nodes = mutableListOf<Node>()
    private val aggregates = mutableMapOf<Pair<Int?, SyncPhase>, Int>()
    private val stack = mutableListOf<Frame>()
    private val counters = mutableMapOf<SyncCounter, Long>()

    fun now(): Long = nanoTime()
    private fun node(phase: SyncPhase): Node {
        val parent = stack.lastOrNull()?.nodeId
        val key = parent to phase
        if (phase.aggregate) aggregates[key]?.let { return nodes[it] }
        return Node(nodes.size, parent, phase).also {
            nodes.add(it)
            if (phase.aggregate) aggregates[key] = it.id
        }
    }
    fun begin(phase: SyncPhase): Frame = Frame(node(phase).id, now()).also { stack.add(it) }
    fun end(frame: Frame) {
        val elapsed = (now() - frame.start).coerceAtLeast(0)
        stack.removeAt(stack.lastIndex)
        val node = nodes[frame.nodeId]
        node.calls++
        node.duration += elapsed
        node.own += (elapsed - frame.children).coerceAtLeast(0)
        stack.lastOrNull()?.let { it.children += elapsed }
    }
    /** Already timed read/digest slices; avoids allocating a frame for every 8 KiB chunk. */
    fun sample(phase: SyncPhase, durationNanos: Long, calls: Long) {
        if (calls == 0L) return
        val elapsed = durationNanos.coerceAtLeast(0)
        val node = node(phase)
        node.calls += calls
        node.duration += elapsed
        node.own += elapsed
        stack.lastOrNull()?.let { it.children += elapsed }
    }
    fun count(counter: SyncCounter, amount: Long = 1) {
        counters[counter] = (counters[counter] ?: 0) + amount
        stack.lastOrNull()?.let { frame ->
            nodes[frame.nodeId].counters.let { it[counter] = (it[counter] ?: 0) + amount }
        }
    }
    suspend fun <T> measureSuspending(phase: SyncPhase, action: suspend () -> T): T {
        val frame = begin(phase)
        try { return action() } finally { end(frame) }
    }
    fun snapshot(): SyncPerformanceReport {
        // Roll up only when rendering a completed report, not for every read/comparison.
        val rolledUp = nodes.map { it.counters.toMutableMap() }
        nodes.asReversed().forEach { node -> node.parentId?.let { parent ->
            rolledUp[node.id].forEach { (counter, value) ->
                rolledUp[parent][counter] = (rolledUp[parent][counter] ?: 0) + value
            }
        } }
        return SyncPerformanceReport(nodes.map {
            SyncTiming(it.id, it.parentId, it.phase, it.calls, it.duration, it.own, rolledUp[it.id].toMap())
        }, counters.toMap())
    }
}

internal suspend fun <T> SyncPerformanceRecorder?.measureSuspending(phase: SyncPhase,
                                                                  action: suspend () -> T): T =
    if (this == null) action() else measureSuspending(phase, action)

/** Observes JGit's existing rename tasks. Titles are deliberately ignored; cancellation unchanged. */
internal class SyncRenameMonitor(private val recorder: SyncPerformanceRecorder) : ProgressMonitor {
    private var started: Long? = null
    override fun start(totalTasks: Int) = Unit
    override fun beginTask(title: String?, totalWork: Int) {
        finish()
        started = recorder.now()
    }
    override fun update(completed: Int) { recorder.count(SyncCounter.RENAME_WORK, completed.toLong()) }
    override fun endTask() { finish() }
    override fun isCancelled() = false
    override fun showDuration(enabled: Boolean) = Unit
    fun finish() {
        started?.let { recorder.sample(SyncPhase.RENAMES, recorder.now() - it, 1) }
        started = null
    }
}

/** Scoped only around the synchronous engine. Never held across a coroutine suspension. */
internal object SyncPerformance {
    private val active = ThreadLocal<SyncPerformanceRecorder?>()
    fun current(): SyncPerformanceRecorder? = active.get()
    fun <T> withRecorder(recorder: SyncPerformanceRecorder, action: () -> T): T {
        val previous = active.get()
        active.set(recorder)
        try { return action() } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }
}

internal inline fun <T> syncMeasure(phase: SyncPhase, action: () -> T): T {
    val recorder = SyncPerformance.current() ?: return action()
    val frame = recorder.begin(phase)
    try { return action() } finally { recorder.end(frame) }
}

internal fun syncCount(counter: SyncCounter, amount: Long = 1) {
    SyncPerformance.current()?.count(counter, amount)
}

internal inline fun <T> syncRevWalk(repository: Repository, action: (RevWalk) -> T): T =
    syncMeasure(SyncPhase.REV_WALK) {
        syncCount(SyncCounter.REV_WALK_CALLS)
        RevWalk(repository).use(action)
    }

internal fun syncReadIndex(repository: Repository): DirCache = syncMeasure(SyncPhase.INDEX_READ) {
    syncCount(SyncCounter.INDEX_READS)
    DirCache.read(repository).also { syncCount(SyncCounter.INDEX_ENTRIES, it.entryCount.toLong()) }
}

/** Process memory only, separated per repository; retained across Activity recreation. */
internal class SyncPerformanceStore {
    private val mutableReports = MutableStateFlow<Map<String, SyncPerformanceReport>>(emptyMap())
    val reports: StateFlow<Map<String, SyncPerformanceReport>> = mutableReports
    fun record(repositoryId: String, report: SyncPerformanceReport) {
        mutableReports.update { it + (repositoryId to report) }
    }
    companion object { val process = SyncPerformanceStore() }
}

internal fun syncPerformanceLines(report: SyncPerformanceReport?): List<String> {
    if (report == null || report.timings.isEmpty()) return listOf("Todavía no hay un diagnóstico de SINCRONIZAR.")
    val number = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-ES")).apply {
        minimumFractionDigits = 3
        maximumFractionDigits = 3
    }
    fun duration(nanos: Long) = number.format(nanos / 1_000_000.0) + " ms"
    return buildList {
        report.totalNanos?.let { add("SYNC TOTAL: " + duration(it)) }
        add("Resumen por tiempo propio (sin sumar dos veces las fases anidadas):")
        report.timings.groupBy { it.phase.category }.entries.sortedByDescending { it.value.sumOf { t -> t.ownNanos } }
            .forEach { (category, samples) -> add(category.label + ": " + duration(samples.sumOf { it.ownNanos })) }
        add("Sistema de seguridad nuevo, incluidos sus hijos: " + duration(report.securityNanos) +
            " (se solapa con el resumen anterior)")
        add("Fases en orden de inicio. Total de fase / tiempo propio; los hijos están incluidos en el total:")
        val byId = report.timings.associateBy { it.id }
        val children = report.timings.groupBy { it.parentId }
        fun appendTiming(timing: SyncTiming, depth: Int) {
            val prefix = "  ".repeat(depth)
            if (timing.phase != SyncPhase.TOTAL) {
                add(prefix + "#${timing.id + 1} " + timing.phase.label + ": " + duration(timing.durationNanos) +
                    " / propio " + duration(timing.ownNanos) + " · ${timing.calls} llamadas")
                if (timing.phase in setOf(SyncPhase.CAPTURE, SyncPhase.WORKING_OBJECTS,
                        SyncPhase.FINGERPRINT, SyncPhase.IGNORED, SyncPhase.REMOTE_PATHS,
                        SyncPhase.INDEX_READ, SyncPhase.TREE, SyncPhase.RENAMES)) {
                    timing.counters.forEach { (counter, value) -> add(prefix + "  " + counter.label + ": " + value) }
                }
            }
            children[timing.id].orEmpty().forEach { appendTiming(it, depth + if (timing.phase == SyncPhase.TOTAL) 0 else 1) }
        }
        report.timings.filter { it.parentId == null || it.parentId !in byId }.forEach { appendTiming(it, 0) }
        add("CONTADORES TOTALES (las repeticiones cuentan; no son archivos únicos):")
        report.counters.forEach { (counter, value) -> add(counter.label + ": " + value) }
        add("Bytes exactos solo para streams SHA-256. Bytes internos de JGit y de red no contabilizados; nunca se sustituyen por tamaños declarados.")
        add("I/O observable incluye llamadas al filesystem/caché; no mide bytes físicos de flash. Status, FF y fetch/push incluyen trabajo interno JGit.")
        add("Rename detection conserva la configuración efectiva; sus tareas se miden dentro de DiffFormatter.scan.")
        add("Informe agregado sin rutas, contenido, hashes ni credenciales. Disponible mientras siga vivo el proceso de la aplicación.")
    }
}
