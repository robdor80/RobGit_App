package es.robertodorado.robgit

import org.junit.Assert.*
import org.junit.Test

class RodoLayoutPolicyTest {
    @Test fun phoneKeepsOriginalStructureEvenWhenLandscape() {
        for ((width, height) in listOf(390f to 840f, 840f to 390f)) {
            val layout = robGitLayoutMode(390, width, height)
            assertEquals(RobGitLayoutMode.PHONE, layout)
            assertFalse(layout.hasPersistentRodoCard || layout.hasSingleRowActions)
        }
    }

    @Test fun portraitTabletGetsPersistentCardAndSingleBottomRow() {
        val layout = robGitLayoutMode(600, 800f, 1280f)
        assertEquals(RobGitLayoutMode.TABLET_PORTRAIT, layout)
        assertTrue(layout.hasPersistentRodoCard)
        assertTrue(layout.hasSingleRowActions)
    }

    @Test fun landscapeTabletKeepsExistingStructure() {
        val layout = robGitLayoutMode(600, 1280f, 800f)
        assertEquals(RobGitLayoutMode.TABLET_LANDSCAPE, layout)
        assertFalse(layout.hasPersistentRodoCard || layout.hasSingleRowActions)
    }

    @Test fun squareTabletUsesPortraitPolicyUntilWidthExceedsHeight() {
        assertEquals(RobGitLayoutMode.TABLET_PORTRAIT, robGitLayoutMode(600, 900f, 900f))
        assertEquals(RobGitLayoutMode.TABLET_LANDSCAPE, robGitLayoutMode(600, 901f, 900f))
    }

    @Test fun onlyShortPortraitWindowsScrollCardsWhileActionsRemainSeparate() {
        assertTrue(portraitNeedsScrollableFallback(600f))
        assertFalse(portraitNeedsScrollableFallback(800f))
    }

    @Test fun aiActionRestoresOriginalLabelIconAndCategory() {
        assertEquals("IA", aiActionIdentity.label)
        assertEquals("✦", aiActionIdentity.icon)
        assertEquals(RepositoryAction.AI, aiActionIdentity.action)
    }

    @Test fun portraitTabletHasExactlyFourActionsInOneRow() {
        assertEquals(listOf("PULL", "PUSH", "SINCRONIZAR", "IA"),
            tabletPortraitActions.map { it.label })
        assertEquals(4, tabletPortraitActions.size)
        assertSame(aiActionIdentity, tabletPortraitActions.last())
    }

    @Test fun rodoCardHasFiniteResponsiveMaximumAndCompactIdleContent() {
        assertEquals(240f, rodoCardMaxHeight(300f))
        assertEquals(348f, rodoCardMaxHeight(600f), .001f)
        assertEquals(440f, rodoCardMaxHeight(1200f))
        assertTrue(rodoCardMaxHeight(1200f) < 1200f)
    }
}
