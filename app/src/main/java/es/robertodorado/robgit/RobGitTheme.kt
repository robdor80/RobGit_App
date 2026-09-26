package es.robertodorado.robgit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object RobGitColors {
    val Petroleum = Color(0xFF05131A)
    val PetroleumDark = Color(0xFF040F15)
    val PetroleumDeep = Color(0xFF020B10)
    val PetroleumLight = Color(0xFF0B202B)
    val Ice = Color(0xFFEAF6F8)
    val IceMuted = Color(0xFFB8D6DB)
    val Success = Color(0xFF9ED9C2)
    val Warning = Color(0xFFF1CF8A)
    val Error = Color(0xFFFFB4AB)
    val Ai = Color(0xFFC8C4FF)
}

object RobGitDimens {
    val PhonePadding = 20.dp
    val TabletPadding = 30.dp
    val Radius = 18.dp
    val Border = 1.dp
    val Gap = 16.dp
    val ButtonMax = 190.dp
    val ContentMax = 1040.dp
}

private val RobGitScheme = darkColorScheme(
    primary = RobGitColors.Ice,
    onPrimary = RobGitColors.PetroleumDeep,
    background = RobGitColors.Petroleum,
    onBackground = RobGitColors.Ice,
    surface = RobGitColors.PetroleumDark,
    onSurface = RobGitColors.Ice,
    surfaceVariant = RobGitColors.PetroleumLight,
    onSurfaceVariant = RobGitColors.IceMuted,
    error = RobGitColors.Error,
)

@Composable
fun RobGitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RobGitScheme, content = content)
}
