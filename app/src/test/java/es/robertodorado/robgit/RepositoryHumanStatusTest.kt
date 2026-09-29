package es.robertodorado.robgit

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RepositoryHumanStatusTest {
    @Test fun cachedLocalChangesOfferRevalidatedPushWithoutClaimingFreshRemote() {
        val fresh = state(RepositoryStateType.LOCAL_CHANGES, CommitRelation.SYNCHRONIZED,
            changes = WorkingTreeChanges(modifiedFiles = setOf("file")))
        val local = fresh.copy(remoteStateIsFresh = false)
        val shown = RepositoryStatusPresenter.present(local)
        assertEquals("Tienes trabajo local pendiente.", shown.title)
        assertTrue(shown.pushEnabled)
        assertTrue(shown.recommendation.orEmpty().contains("comprobará GitHub"))
        assertFalse(shown.explanation.contains("misma versión"))
        assertFalse(shown.pullEnabled)
    }

    @Test fun cachedRemoteAheadAndDivergedStatesNeverEnableDirectionalActions() {
        for (relation in listOf(CommitRelation.REMOTE_AHEAD, CommitRelation.DIVERGED)) {
            val cached = state(RepositoryStateType.LOCAL_CHANGES, relation, behind = 1,
                changes = WorkingTreeChanges(newFiles = setOf("file"))).copy(remoteStateIsFresh = false)
            val shown = RepositoryStatusPresenter.present(cached)
            assertFalse(shown.pullEnabled || shown.pushEnabled)
            assertTrue(shown.blocked)
        }
    }

    @Test fun cachedCleanStateNeverClaimsEverythingIsUpToDate() {
        val cached = state(RepositoryStateType.SYNCHRONIZED, CommitRelation.SYNCHRONIZED).copy(remoteStateIsFresh = false)
        assertEquals("Estado local actualizado.", RepositoryStatusPresenter.present(cached).title)
        assertTrue(RepositoryStatusPresenter.present(cached).blocked)
    }

    @Test fun incompleteLocalOperationBlocksEvenWithKnownRemote() {
        val local = state(RepositoryStateType.LOCAL_CHANGES, CommitRelation.SYNCHRONIZED,
            changes = WorkingTreeChanges(modifiedFiles = setOf("file")))
            .copy(remoteStateIsFresh = false, localRepositoryIsSafe = false)
        val shown = RepositoryStatusPresenter.present(local)
        assertFalse(shown.pushEnabled)
        assertTrue(shown.blocked)
    }
    @Test fun noRepositoriesShowsAnEmptyState() {
        assertEquals("No tienes repositorios configurados.", RepositoryStatusPresenter.noRepositories.title)
    }

    @Test fun preparedRepositoryWithoutFreshStateRequestsManualAnalysis() {
        val shown = RepositoryStatusPresenter.notUpdated
        assertEquals("Estado sin actualizar", shown.title)
        assertEquals("Pulsa el logo de RobGit para analizar el repositorio.", shown.explanation)
        assertFalse(shown.blocked)
    }

    @Test fun missingConfiguredBranchGetsHumanExplanation() {
        val shown = RepositoryStatusPresenter.present(
            state(RepositoryStateType.ERROR, CommitRelation.UNDETERMINED, fresh = false)
                .copy(error = "refs/remotes/origin/develop no existe después del fetch"),
        )
        assertEquals("La rama configurada no está disponible.", shown.title)
    }

    private fun state(
        type: RepositoryStateType,
        relation: CommitRelation,
        ahead: Int = 0,
        behind: Int = 0,
        changes: WorkingTreeChanges = WorkingTreeChanges(),
        fresh: Boolean = true,
    ) = RepositoryStateSnapshot(
        type, relation, "technical", "main", "a".repeat(40), "b".repeat(40),
        ahead, behind, changes, Instant.EPOCH, fresh,
    )

    @Test fun synchronizedIsHumanAndNeedsNoAction() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.SYNCHRONIZED, CommitRelation.SYNCHRONIZED))
        assertEquals("Todo está al día.", shown.title)
        assertNull(shown.recommendedAction)
    }

    @Test fun remoteAheadRecommendsAndEnablesPull() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.REMOTE_AHEAD, CommitRelation.REMOTE_AHEAD, behind = 2))
        assertEquals(RepositoryAction.PULL, shown.recommendedAction)
        assertTrue(shown.pullEnabled)
        assertFalse(shown.pushEnabled)
    }

    @Test fun localChangesRecommendAndEnablePush() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.LOCAL_CHANGES, CommitRelation.SYNCHRONIZED, changes = WorkingTreeChanges(modifiedFiles = setOf("a"))))
        assertEquals(RepositoryAction.PUSH, shown.recommendedAction)
        assertTrue(shown.pushEnabled)
    }

    @Test fun localAheadRecommendsAndEnablesPush() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.LOCAL_AHEAD, CommitRelation.LOCAL_AHEAD, ahead = 1))
        assertEquals(RepositoryAction.PUSH, shown.recommendedAction)
        assertTrue(shown.pushEnabled)
    }

    @Test fun pendingChangesAndRemoteAheadRecommendSynchronizationWithoutDirectionalActions() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.LOCAL_CHANGES, CommitRelation.REMOTE_AHEAD, behind = 1, changes = WorkingTreeChanges(newFiles = setOf("a"))))
        assertFalse(shown.blocked)
        assertEquals(RepositoryAction.SYNCHRONIZE, shown.recommendedAction)
        assertTrue(shown.title.contains("dos sitios"))
        assertTrue(shown.recommendation.orEmpty().contains("comprobará"))
        assertFalse(shown.pullEnabled || shown.pushEnabled)
    }

    @Test fun combinedSynchronizationSuccessConfirmsCleanAndSynchronizedFinalState() {
        val finalState = state(RepositoryStateType.SYNCHRONIZED, CommitRelation.SYNCHRONIZED)
        val shown = RepositoryStatusPresenter.present(SynchronizationResult(
            SynchronizationOutcome.SUCCESS_DOWNLOADED_AND_UPLOADED,
            "Los cambios remotos se descargaron y el trabajo local se subió.",
            finalState,
            downloadResult = downloaded(finalState),
            uploadResult = uploaded(UploadOutcome.SUCCESS, finalState),
        ))
        assertEquals("Sincronización completada.", shown.title)
        assertEquals("Este dispositivo y GitHub están al día.", shown.explanation)
        assertFalse(shown.blocked)
    }

    @Test fun overlappingFilesExplainCollisionAndNoMutation() {
        val shown = RepositoryStatusPresenter.present(SynchronizationResult(
            SynchronizationOutcome.BLOCKED_OVERLAPPING_FILES,
            "Hay rutas locales y remotas que colisionan.",
            state(RepositoryStateType.LOCAL_CHANGES, CommitRelation.REMOTE_AHEAD, behind = 1,
                changes = WorkingTreeChanges(modifiedFiles = setOf("a"))),
        ))
        assertTrue(shown.title.contains("incompatibles"))
        assertTrue(shown.explanation.contains("mismos archivos"))
        assertTrue(shown.explanation.contains("rutas pueden colisionar"))
        assertTrue(shown.explanation.contains("no ha realizado ningún cambio"))
        assertEquals(RepositoryAction.AI, shown.recommendedAction)
        assertTrue(shown.blocked)
        assertFalse(shown.pullEnabled || shown.pushEnabled)
    }

    @Test fun successfulDownloadAndIncompleteUploadAlwaysPreservePartialResultMessage() {
        val outcomes = listOf(
            SynchronizationOutcome.PUSH_REJECTED_REMOTE_CHANGED to UploadOutcome.PUSH_REJECTED_REMOTE_CHANGED,
            SynchronizationOutcome.FETCH_ERROR to UploadOutcome.FETCH_ERROR,
            SynchronizationOutcome.AUTH_REQUIRED to UploadOutcome.AUTH_REQUIRED,
            SynchronizationOutcome.AUTH_FAILED to UploadOutcome.AUTH_FAILED,
            SynchronizationOutcome.PUSH_UNCERTAIN to UploadOutcome.PUSH_UNCERTAIN,
            SynchronizationOutcome.BLOCKED_DIVERGED to UploadOutcome.BLOCKED_DIVERGED,
            SynchronizationOutcome.BLOCKED_CONFLICTS to UploadOutcome.BLOCKED_CONFLICTS,
            SynchronizationOutcome.ERROR to UploadOutcome.ERROR,
        )
        val finalState = state(RepositoryStateType.LOCAL_CHANGES, CommitRelation.SYNCHRONIZED,
            changes = WorkingTreeChanges(modifiedFiles = setOf("a")))
        for ((syncOutcome, uploadOutcome) in outcomes) {
            val message = "Los cambios de GitHub se descargaron. La subida quedó pendiente: $syncOutcome. El trabajo local permanece intacto."
            val shown = RepositoryStatusPresenter.present(SynchronizationResult(
                syncOutcome, message, finalState,
                downloadResult = downloaded(finalState),
                uploadResult = uploaded(uploadOutcome, finalState),
            ))
            assertEquals(syncOutcome.name, message, shown.explanation)
            assertTrue(syncOutcome.name, shown.title.contains("Descarga completada"))
            assertTrue(syncOutcome.name, shown.blocked)
            assertFalse(syncOutcome.name, shown.explanation.contains("no ha modificado nada"))
            assertFalse(syncOutcome.name, shown.explanation.contains("no han sido modificados"))
            assertFalse(syncOutcome.name, shown.title == "Sincronización completada.")
            if (syncOutcome == SynchronizationOutcome.PUSH_UNCERTAIN) {
                assertTrue(shown.title.contains("sin confirmar"))
            }
        }
    }

    @Test fun verificationErrorAfterDownloadKeepsPartialMessageWithoutAnUploadResult() {
        val message = "El avance remoto se aplicó, pero no se pudo completar su verificación. La subida no se ha iniciado."
        val shown = RepositoryStatusPresenter.present(SynchronizationResult(
            SynchronizationOutcome.ERROR, message, null,
            downloadResult = downloaded(null),
        ))
        assertEquals(message, shown.explanation)
        assertTrue(shown.title.contains("Descarga completada"))
        assertTrue(shown.blocked)
    }

    @Test fun failedDownloadDoesNotClaimNothingChangedOrThatLocalWorkWasVerified() {
        val message = "No se pudo verificar el fast-forward. Comprueba el estado antes de continuar."
        val shown = RepositoryStatusPresenter.present(SynchronizationResult(
            SynchronizationOutcome.ERROR, message, null,
            downloadResult = downloaded(null).copy(outcome = DownloadOutcome.ERROR),
        ))
        assertEquals(message, shown.explanation)
        assertFalse(shown.title.contains("completada"))
        assertTrue(shown.blocked)
    }

    @Test fun completedSynchronizationDoesNotClaimCurrentAgreementIfRemoteAdvancedAgain() {
        val finalState = state(RepositoryStateType.REMOTE_AHEAD, CommitRelation.REMOTE_AHEAD, behind = 1)
        val shown = RepositoryStatusPresenter.present(SynchronizationResult(
            SynchronizationOutcome.SUCCESS_DOWNLOADED_AND_UPLOADED,
            "Descarga y subida completadas.", finalState,
        ))
        assertTrue(shown.title.contains("cambios pendientes"))
        assertTrue(shown.explanation.contains("GitHub tiene cambios"))
        assertFalse(shown.explanation.contains("están al día"))
        assertEquals(RepositoryAction.PULL, shown.recommendedAction)
    }

    @Test fun completedSynchronizationWithUnverifiedFinalStateRequestsAnalysis() {
        val finalStates = listOf(null, state(RepositoryStateType.ERROR, CommitRelation.UNDETERMINED, fresh = false))
        for (finalState in finalStates) {
            val shown = RepositoryStatusPresenter.present(SynchronizationResult(
                SynchronizationOutcome.SUCCESS_DOWNLOADED_AND_UPLOADED,
                "Descarga y subida completadas.", finalState,
            ))
            assertTrue(shown.title.contains("falta comprobar"))
            assertFalse(shown.explanation.contains("están al día"))
            assertTrue(shown.recommendation.orEmpty().contains("Analiza"))
            assertTrue(shown.blocked)
        }
    }

    @Test fun remoteRejectionAfterCreatingCommitPreservesSavedWorkMessageInPushAndSync() {
        val finalState = state(RepositoryStateType.DIVERGED, CommitRelation.DIVERGED, ahead = 1, behind = 1)
        val message = "GitHub cambió mientras se preparaba la subida. No se intentó hacer push. El trabajo está guardado localmente; no se ha perdido."
        val upload = uploaded(UploadOutcome.PUSH_REJECTED_REMOTE_CHANGED, finalState).copy(message = message)
        val presentations = listOf(
            RepositoryStatusPresenter.present(upload),
            RepositoryStatusPresenter.present(SynchronizationResult(
                SynchronizationOutcome.PUSH_REJECTED_REMOTE_CHANGED, message, finalState,
                uploadResult = upload,
            )),
        )
        for (shown in presentations) {
            assertEquals(message, shown.explanation)
            assertTrue(shown.blocked)
            assertTrue(shown.explanation.contains("guardado localmente"))
            assertFalse(shown.recommendation.orEmpty().contains("no ha modificado nada"))
        }
    }

    @Test fun secondFetchFailureAfterCreatingCommitPreservesSavedWorkMessageInPushAndSync() {
        val message = "No se pudo volver a comprobar GitHub antes del push. El trabajo está guardado localmente; no se ha perdido."
        val upload = uploaded(UploadOutcome.FETCH_ERROR, null).copy(message = message)
        val presentations = listOf(
            RepositoryStatusPresenter.present(upload),
            RepositoryStatusPresenter.present(SynchronizationResult(
                SynchronizationOutcome.FETCH_ERROR, message, null, uploadResult = upload,
            )),
        )
        for (shown in presentations) {
            assertEquals(message, shown.explanation)
            assertTrue(shown.title.contains("guardados localmente"))
            assertTrue(shown.blocked)
            assertFalse(shown.explanation.contains("no han sido modificados"))
        }
    }

    private fun downloaded(finalState: RepositoryStateSnapshot?) = DownloadResult(
        DownloadOutcome.SUCCESS, "Descarga completada.", "a".repeat(40), "b".repeat(40),
        commitsDownloaded = 1, workingTreeClean = false, finalState = finalState,
    )

    private fun uploaded(outcome: UploadOutcome, finalState: RepositoryStateSnapshot?) = UploadResult(
        outcome, "Resultado de subida.", "b".repeat(40), "c".repeat(40),
        commitCreated = true, commitsUploaded = if (outcome == UploadOutcome.SUCCESS) 1 else 0,
        finalState = finalState,
    )

    @Test fun divergedIsBlocked() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.DIVERGED, CommitRelation.DIVERGED, ahead = 1, behind = 1))
        assertTrue(shown.blocked)
        assertTrue(shown.title.contains("separado"))
    }

    @Test fun conflictsTakePriorityAndAreBlocked() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.LOCAL_CHANGES, CommitRelation.SYNCHRONIZED, changes = WorkingTreeChanges(conflictingFiles = setOf("a"))))
        assertTrue(shown.blocked)
        assertEquals("Hay conflictos pendientes.", shown.title)
    }

    @Test fun fetchErrorDoesNotClaimSynchronization() {
        val shown = RepositoryStatusPresenter.present(state(RepositoryStateType.ERROR, CommitRelation.UNDETERMINED, fresh = false))
        assertEquals("No he podido comprobar GitHub.", shown.title)
        assertTrue(shown.blocked)
    }

    @Test fun authenticationMessagesAreExplicit() {
        assertTrue(RepositoryStatusPresenter.authRequired().explanation.contains("Ajustes"))
        assertTrue(RepositoryStatusPresenter.authFailed().title.contains("volver a conectar"))
        assertTrue(RepositoryStatusPresenter.pushUncertain().title.contains("confirmar"))
    }

    @Test fun actionMatrixDisablesUnsafeDirectionalActions() {
        val synchronized = RepositoryStatusPresenter.present(state(RepositoryStateType.SYNCHRONIZED, CommitRelation.SYNCHRONIZED))
        val diverged = RepositoryStatusPresenter.present(state(RepositoryStateType.DIVERGED, CommitRelation.DIVERGED))
        assertFalse(synchronized.pullEnabled || synchronized.pushEnabled)
        assertFalse(diverged.pullEnabled || diverged.pushEnabled)
    }

    @Test fun rotationDoesNotRequestRefresh() {
        val policy = ForegroundRefreshPolicy()
        assertFalse(policy.onStart())
        policy.onStop(isChangingConfigurations = true)
        assertFalse(policy.onStart())
    }

    @Test fun realBackgroundReturnRequestsRefresh() {
        val policy = ForegroundRefreshPolicy()
        assertFalse(policy.onStart())
        policy.onStop(isChangingConfigurations = false)
        assertTrue(policy.onStart())
    }
}
