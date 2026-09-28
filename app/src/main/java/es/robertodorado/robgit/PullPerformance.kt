package es.robertodorado.robgit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.NumberFormat
import java.util.Locale

/** Fixed labels only: diagnostics never accept repository data, errors or credentials. */
enum class PullPhase(val label: String) {
    TOTAL("Tiempo total"),
    OAUTH("OAuth: adquisición/validación"),
    TOKEN_COPY("OAuth: copia temporal"),
    JGIT_CREDENTIALS("Preparar credenciales JGit"),
    OPEN_REPOSITORY("Abrir repositorio"),
    VALIDATE_REPOSITORY("Validar repositorio"),
    FETCH("Fetch"),
    INITIAL_REFS("Estado inicial: leer rama/refs"),
    INITIAL_GRAPH("Estado inicial: grafo Git"),
    INITIAL_STATUS("Estado inicial: git.status"),
    INITIAL_CHANGES("Estado inicial: clasificar/ordenar archivos"),
    INITIAL_POLICY("Estado inicial: comprobar condiciones de PULL"),
    BASELINE_REFS("Leer HEAD y ref remota de referencia"),
    GUARD_STATUS("Guardia previa: git.status"),
    GUARD_CHANGES("Guardia previa: clasificar/ordenar archivos"),
    GUARD_HEAD("Guardia previa: leer HEAD"),
    GUARD_REMOTE_REF("Guardia previa: leer ref remota"),
    GUARD_VALIDATE("Guardia previa: comprobar cambios y refs"),
    FAST_FORWARD("MergeCommand FF_ONLY"),
    FINAL_REFS("Estado final: leer rama/refs"),
    FINAL_GRAPH("Estado final: grafo Git"),
    FINAL_STATUS("Estado final: git.status"),
    FINAL_CHANGES("Estado final: clasificar/ordenar archivos"),
    FINAL_VALIDATE("Validación final: HEAD, ref remota y estado"),
    RECHECK_REFS("Recomprobación: leer rama/refs"),
    RECHECK_GRAPH("Recomprobación: grafo Git"),
    RECHECK_STATUS("Recomprobación: git.status"),
    RECHECK_CHANGES("Recomprobación: clasificar/ordenar archivos"),
    ENGINE_CREDENTIAL_CLEANUP("Limpiar credenciales del motor"),
    OAUTH_CLEANUP("OAuth: limpiar copia temporal"),
}

enum class PullCounter(val label: String) {
    STATUS_CALLS("Llamadas explícitas de RobGit a git.status"),
    REV_WALK_CALLS("RevWalk de RobGit (sin internos de JGit)"),
}

data class PullPhaseTiming(val phase: PullPhase, val durationNanos: Long)

data class PullPerformanceReport(
    val timings: List<PullPhaseTiming> = emptyList(),
    val counters: Map<PullCounter, Int> = emptyMap(),
) {
    val totalNanos: Long? get() = timings.filter { it.phase == PullPhase.TOTAL }
        .takeIf { it.isNotEmpty() }?.sumOf { it.durationNanos }
    val unattributedNanos: Long? get() = totalNanos?.let { total ->
        (total - timings.filter { it.phase != PullPhase.TOTAL }.sumOf { it.durationNanos }).coerceAtLeast(0)
    }
}

/** One recorder per PULL. Sequential use; snapshots are copied after the operation finishes. */
class PullPerformanceRecorder(private val nanoTime: () -> Long = System::nanoTime) {
    private val timings = mutableListOf<PullPhaseTiming>()
    private val counters = mutableMapOf<PullCounter, Int>()

    fun <T> measure(phase: PullPhase, action: () -> T): T {
        val started = nanoTime()
        try {
            return action()
        } finally {
            timings += PullPhaseTiming(phase, (nanoTime() - started).coerceAtLeast(0))
        }
    }

    suspend fun <T> measureSuspending(phase: PullPhase, action: suspend () -> T): T {
        val started = nanoTime()
        try {
            return action()
        } finally {
            timings += PullPhaseTiming(phase, (nanoTime() - started).coerceAtLeast(0))
        }
    }

    fun count(counter: PullCounter) {
        counters[counter] = (counters[counter] ?: 0) + 1
    }

    fun snapshot(): PullPerformanceReport = PullPerformanceReport(timings.toList(), counters.toMap())
}

internal fun <T> PullPerformanceRecorder?.measure(phase: PullPhase, action: () -> T): T =
    if (this == null) action() else measure(phase, action)

internal suspend fun <T> PullPerformanceRecorder?.measureSuspending(phase: PullPhase, action: suspend () -> T): T =
    if (this == null) action() else measureSuspending(phase, action)

/** In-memory only; survives Activity recreation while the app process remains alive. */
internal class PullPerformanceStore {
    private val mutableReports = MutableStateFlow<Map<String, PullPerformanceReport>>(emptyMap())
    val reports: StateFlow<Map<String, PullPerformanceReport>> = mutableReports

    fun record(repositoryId: String, report: PullPerformanceReport) {
        mutableReports.update { it + (repositoryId to report) }
    }

    companion object {
        val process = PullPerformanceStore()
    }
}

/** Pure presentation seam also used by the technical dialog; handles missing/partial reports. */
internal fun pullPerformanceLines(report: PullPerformanceReport?): List<String> {
    if (report == null || report.timings.isEmpty()) return listOf("Todavía no hay un diagnóstico de PULL.")
    val number = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-ES")).apply {
        minimumFractionDigits = 3
        maximumFractionDigits = 3
    }
    fun duration(nanos: Long) = number.format(nanos / 1_000_000.0) + " ms"
    return buildList {
        report.totalNanos?.let { add("Tiempo total: " + duration(it)) }
        report.timings.filter { it.phase != PullPhase.TOTAL }.groupBy { it.phase }.forEach { (phase, samples) ->
            add(phase.label + ": " + duration(samples.sumOf { it.durationNanos }) +
                if (samples.size > 1) " (" + samples.size + " llamadas)" else "")
        }
        report.unattributedNanos?.let { add("Resto y coste de medición: " + duration(it)) }
        report.counters.forEach { (counter, calls) -> add(counter.label + ": " + calls) }
    }
}
