package es.robertodorado.robgit

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PullPerformanceTest {
    @Test fun recordsDurationsAndPhaseCompletionOrderUsingOnlyInjectedClock() {
        var now = 100L
        val recorder = PullPerformanceRecorder { now }
        assertEquals("value", recorder.measure(PullPhase.FETCH) { now += 250; "value" })
        recorder.measure(PullPhase.INITIAL_STATUS) { now += 500 }
        assertEquals(listOf(PullPhaseTiming(PullPhase.FETCH, 250),
            PullPhaseTiming(PullPhase.INITIAL_STATUS, 500)), recorder.snapshot().timings)
    }

    @Test fun suspendedMeasurementIncludesElapsedTimeAcrossSuspension() = runTest {
        var now = 0L
        val recorder = PullPerformanceRecorder { now }
        recorder.measureSuspending(PullPhase.OAUTH) {
            now += 150
            yield()
            now += 350
        }
        assertEquals(listOf(PullPhaseTiming(PullPhase.OAUTH, 500)), recorder.snapshot().timings)
    }

    @Test fun nestedTotalAndRepeatedPhasesAccumulateWithoutDoubleCountingTotal() = runTest {
        var now = 0L
        val recorder = PullPerformanceRecorder { now }
        recorder.measureSuspending(PullPhase.TOTAL) {
            recorder.measure(PullPhase.FETCH) { now += 1_000_000 }
            now += 500_000
            recorder.measure(PullPhase.FETCH) { now += 2_000_000 }
        }
        val report = recorder.snapshot()
        assertEquals(3_500_000L, report.totalNanos)
        assertEquals(500_000L, report.unattributedNanos)
        assertTrue(pullPerformanceLines(report).contains("Fetch: 3,000 ms (2 llamadas)"))
        assertEquals("Tiempo total: 3,500 ms", pullPerformanceLines(report).first())
    }

    @Test fun exceptionsArePropagatedAndTimedWithoutRecordingTheirMessages() {
        var now = 0L
        val recorder = PullPerformanceRecorder { now }
        val failure = IllegalStateException("Authorization: Bearer private-test-token")
        try {
            recorder.measure(PullPhase.FETCH) { now += 10; throw failure }
            fail("Expected original failure")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertEquals(10L, recorder.snapshot().timings.single().durationNanos)
        assertFalse(recorder.snapshot().toString().contains("private-test-token"))
    }

    @Test fun countersAndSnapshotsAreIndependentFromLaterMeasurements() {
        val recorder = PullPerformanceRecorder { 0 }
        recorder.count(PullCounter.STATUS_CALLS)
        val before = recorder.snapshot()
        recorder.count(PullCounter.STATUS_CALLS)
        recorder.count(PullCounter.REV_WALK_CALLS)
        recorder.measure(PullPhase.INITIAL_GRAPH) { }
        assertEquals(mapOf(PullCounter.STATUS_CALLS to 1), before.counters)
        assertTrue(before.timings.isEmpty())
        assertEquals(mapOf(PullCounter.STATUS_CALLS to 2, PullCounter.REV_WALK_CALLS to 1),
            recorder.snapshot().counters)
    }

    @Test fun emptyAndPartialReportsAreSafeForTechnicalDialog() {
        val empty = listOf("Todavía no hay un diagnóstico de PULL.")
        assertEquals(empty, pullPerformanceLines(null))
        assertEquals(empty, pullPerformanceLines(PullPerformanceReport()))
        val partial = PullPerformanceReport(listOf(PullPhaseTiming(PullPhase.OAUTH, 1_500_000)))
        assertEquals(listOf("OAuth: adquisición/validación: 1,500 ms"), pullPerformanceLines(partial))
        assertNull(partial.totalNanos)
        assertNull(partial.unattributedNanos)
    }

    @Test fun returnValuesCredentialsPathsAndRepositoryContentsNeverEnterDiagnostic() {
        val recorder = PullPerformanceRecorder { 0 }
        val privateData = "client_secret=test-secret https://user:password@host.invalid .git/config local.properties file contents"
        val returned = recorder.measure(PullPhase.OAUTH) { privateData }
        assertEquals(privateData, returned)
        val rendered = (recorder.snapshot().toString() + pullPerformanceLines(recorder.snapshot()))
        listOf("client_secret", "test-secret", "user:password", "host.invalid", ".git/config",
            "local.properties", "file contents").forEach { assertFalse(rendered.contains(it)) }
    }

    @Test fun absentRecorderStillExecutesAndPropagatesOriginalReturnValue() = runTest {
        val absent: PullPerformanceRecorder? = null
        var calls = 0
        assertEquals(42, absent.measure(PullPhase.FETCH) { calls++; 42 })
        assertEquals(12, absent.measureSuspending(PullPhase.OAUTH) { calls++; 12 })
        assertEquals(2, calls)
    }

    @Test fun latestReportIsKeptSeparatelyForEachRepository() {
        val store = PullPerformanceStore()
        val first = PullPerformanceReport(listOf(PullPhaseTiming(PullPhase.TOTAL, 10)))
        val second = PullPerformanceReport(listOf(PullPhaseTiming(PullPhase.TOTAL, 20)))
        val latest = PullPerformanceReport(listOf(PullPhaseTiming(PullPhase.TOTAL, 30)))
        store.record("repo-a", first)
        store.record("repo-b", second)
        store.record("repo-a", latest)
        assertEquals(mapOf("repo-a" to latest, "repo-b" to second), store.reports.value)
    }

    @Test fun newStoreStartsEmptyAndNewObserversStillSeeCompletedReports() {
        val store = PullPerformanceStore()
        assertTrue(store.reports.value.isEmpty())
        val report = PullPerformanceReport(listOf(PullPhaseTiming(PullPhase.OAUTH, 10)))
        store.record("repo-a", report)
        assertSame(report, store.reports.value["repo-a"])
        assertTrue(PullPerformanceStore().reports.value.isEmpty())
    }
}
