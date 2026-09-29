package es.robertodorado.robgit

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SyncPerformanceTest {
    @Test fun nestedPhasesHaveInclusiveAndOwnTimeWithoutDoubleCounting() = runTest {
        var now = 0L
        val recorder = SyncPerformanceRecorder { now }
        recorder.measureSuspending(SyncPhase.TOTAL) {
            now += 10
            SyncPerformance.withRecorder(recorder) {
                syncMeasure(SyncPhase.CAPTURE) {
                    now += 20
                    syncMeasure(SyncPhase.STATUS) { now += 30; syncCount(SyncCounter.STATUS_CALLS) }
                    now += 40
                }
            }
            now += 50
        }
        val report = recorder.snapshot()
        assertEquals(150L, report.totalNanos)
        assertEquals(150L, report.accountedNanos)
        assertEquals(90L, report.securityNanos)
        assertEquals(listOf(60L, 60L, 30L), report.timings.map { it.ownNanos })
        assertEquals(listOf(null, 0, 1), report.timings.map { it.parentId })
        assertEquals(1L, report.timings[1].counters[SyncCounter.STATUS_CALLS])
    }

    @Test fun individualFetchesAndCapturesRemainSeparateWhileFileSamplesAreBounded() {
        var now = 0L
        val recorder = SyncPerformanceRecorder { now }
        SyncPerformance.withRecorder(recorder) {
            repeat(2) {
                syncMeasure(SyncPhase.FETCH) { now++; syncCount(SyncCounter.FETCH_CALLS) }
                syncMeasure(SyncPhase.CAPTURE) {
                    repeat(1000) {
                        syncMeasure(SyncPhase.FINGERPRINT) {
                            now += 4
                            recorder.sample(SyncPhase.SHA_READ, 2, 1)
                            recorder.count(SyncCounter.SHA256_BYTES, 3)
                        }
                    }
                }
            }
        }
        val report = recorder.snapshot()
        assertEquals(8, report.timings.size)
        assertEquals(2, report.timings.count { it.phase == SyncPhase.FETCH })
        assertEquals(2, report.timings.count { it.phase == SyncPhase.CAPTURE })
        assertEquals(listOf(1000L, 1000L), report.timings.filter { it.phase == SyncPhase.FINGERPRINT }.map { it.calls })
        assertEquals(6000L, report.counters[SyncCounter.SHA256_BYTES])
        assertEquals(listOf(3000L, 3000L), report.timings.filter { it.phase == SyncPhase.CAPTURE }
            .map { it.counters[SyncCounter.SHA256_BYTES] })
        assertEquals(8002L, report.accountedNanos)
    }

    @Test fun originalFailuresAndValuesPropagateWithoutEnteringReportAndScopeIsRestored() {
        val recorder = SyncPerformanceRecorder { 0 }
        val sensitive = "token-secret C:/private/file.txt contents 0123456789abcdef"
        val failure = IllegalStateException(sensitive)
        SyncPerformance.withRecorder(recorder) {
            assertEquals(sensitive, syncMeasure(SyncPhase.STATUS) { sensitive })
            try {
                syncMeasure(SyncPhase.CAPTURE) { throw failure }
                fail("Expected failure")
            } catch (caught: IllegalStateException) { assertSame(failure, caught) }
            val nested = SyncPerformanceRecorder { 0 }
            SyncPerformance.withRecorder(nested) { assertSame(nested, SyncPerformance.current()) }
            assertSame(recorder, SyncPerformance.current())
        }
        assertNull(SyncPerformance.current())
        val rendered = recorder.snapshot().toString() + syncPerformanceLines(recorder.snapshot())
        listOf("token-secret", "C:/private", "contents", "0123456789abcdef").forEach {
            assertFalse(rendered.contains(it))
        }
        assertEquals(2, recorder.snapshot().timings.size)
    }

    @Test fun suspensionAndFailureStillRecordOAuthAndTotal() = runTest {
        var now = 0L
        val recorder = SyncPerformanceRecorder { now }
        val failure = IllegalStateException("secret")
        try {
            recorder.measureSuspending(SyncPhase.TOTAL) {
                recorder.measureSuspending(SyncPhase.OAUTH) { now += 10; yield(); now += 20; throw failure }
            }
        } catch (caught: IllegalStateException) { assertSame(failure, caught) }
        assertEquals(30L, recorder.snapshot().totalNanos)
        assertEquals(30L, recorder.snapshot().accountedNanos)
        assertFalse(recorder.snapshot().toString().contains("secret"))
    }

    @Test fun completedReportsAndSnapshotsAreIndependentAndSeparatedPerRepository() {
        val recorder = SyncPerformanceRecorder { 0 }
        SyncPerformance.withRecorder(recorder) { syncMeasure(SyncPhase.FETCH) { syncCount(SyncCounter.FETCH_CALLS) } }
        val first = recorder.snapshot()
        SyncPerformance.withRecorder(recorder) { syncMeasure(SyncPhase.FETCH) { syncCount(SyncCounter.FETCH_CALLS) } }
        assertEquals(1L, first.counters[SyncCounter.FETCH_CALLS])
        assertEquals(1, first.timings.size)
        val store = SyncPerformanceStore()
        store.record("a", first)
        store.record("b", recorder.snapshot())
        assertEquals(2, store.reports.value.size)
        assertSame(first, store.reports.value["a"])
        assertTrue(SyncPerformanceStore().reports.value.isEmpty())
        assertTrue(syncPerformanceLines(null).single().contains("Todavía"))
    }

    @Test fun renameMonitorIgnoresTitlesAndClosesPartialTasksWithoutCancelling() {
        var now = 0L
        val recorder = SyncPerformanceRecorder { now }
        SyncPerformance.withRecorder(recorder) {
            syncMeasure(SyncPhase.DIFF_SCAN) {
                val monitor = SyncRenameMonitor(recorder)
                monitor.beginTask("private/path credential", 10)
                now += 30
                monitor.update(7)
                monitor.finish()
                monitor.finish()
                assertFalse(monitor.isCancelled)
            }
        }
        val report = recorder.snapshot()
        assertEquals(30L, report.timings.single { it.phase == SyncPhase.RENAMES }.durationNanos)
        assertEquals(7L, report.counters[SyncCounter.RENAME_WORK])
        assertEquals(30L, report.accountedNanos)
        assertFalse(report.toString().contains("private/path"))
    }
}
