package es.robertodorado.robgit

internal enum class RobGitLayoutMode {
    PHONE,
    TABLET_PORTRAIT,
    TABLET_LANDSCAPE;

    val hasPersistentRodoCard: Boolean get() = this == TABLET_PORTRAIT
    val hasSingleRowActions: Boolean get() = this == TABLET_PORTRAIT
}

internal fun robGitLayoutMode(smallestWidthDp: Int, widthDp: Float, heightDp: Float): RobGitLayoutMode = when {
    smallestWidthDp < 600 -> RobGitLayoutMode.PHONE
    widthDp > heightDp -> RobGitLayoutMode.TABLET_LANDSCAPE
    else -> RobGitLayoutMode.TABLET_PORTRAIT
}

/** Only very short tablet windows need to scroll the cards; actions remain outside that region. */
internal fun portraitNeedsScrollableFallback(heightDp: Float): Boolean = heightDp < 650f

/** Bounds the response viewport in dp; the card itself still wraps short content. */
internal fun rodoCardMaxHeight(availableHeightDp: Float): Float =
    (availableHeightDp * .58f).coerceIn(240f, 440f)

internal data class ActionIdentity(val icon: String, val label: String, val action: RepositoryAction)

internal val aiActionIdentity = ActionIdentity("✦", "IA", RepositoryAction.AI)
internal val tabletPortraitActions = listOf(
    ActionIdentity("↓", "PULL", RepositoryAction.PULL),
    ActionIdentity("↑", "PUSH", RepositoryAction.PUSH),
    ActionIdentity("↕", "SINCRONIZAR", RepositoryAction.SYNCHRONIZE),
    aiActionIdentity,
)
