package es.robertodorado.robgit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun RodoImage(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.rodo_v0_1),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
    )
}

/** One static WebP, with Compose-only motion independent from request dispatch. */
@Composable
internal fun AnimatedRodo(state: RodoState, size: Dp, modifier: Modifier = Modifier) {
    val thinking = state is RodoState.Thinking
    val motion = rememberInfiniteTransition(label = "Movimiento de Rodo")
    val bob by motion.animateFloat(
        initialValue = if (thinking) -4f else -3f,
        targetValue = if (thinking) 4f else 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (thinking) 900 else 2100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Flotación de Rodo",
    )
    val pulse by motion.animateFloat(
        initialValue = 1f,
        targetValue = if (thinking) 1.035f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (thinking) 900 else 2100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Pulso de Rodo",
    )
    val bounce = remember { Animatable(0f) }
    val completedId = (state as? RodoState.Completed)?.requestId
    LaunchedEffect(completedId) {
        bounce.snapTo(0f)
        if (completedId != null) repeat(3) {
            bounce.animateTo(-6f, tween(115, easing = FastOutSlowInEasing))
            bounce.animateTo(0f, tween(120, easing = FastOutSlowInEasing))
        }
    }
    RodoImage(size, modifier.graphicsLayer {
        translationY = (bob + bounce.value).dp.toPx()
        scaleX = pulse * (1f + -bounce.value / 150f)
        scaleY = pulse * (1f + -bounce.value / 150f)
    })
}

/** Persistent only in tablet portrait; the animated mascot lives in the title, never the body. */
@Composable
internal fun RodoPanel(state: RodoState, modifier: Modifier = Modifier) {
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth()
                .padding(top = 68.dp)
                .border(RobGitDimens.Border, RobGitColors.Ice, RoundedCornerShape(RobGitDimens.Radius))
                .background(RobGitColors.PetroleumDark.copy(alpha = .58f), RoundedCornerShape(RobGitDimens.Radius))
                .padding(start = 24.dp, end = 24.dp, top = 78.dp, bottom = 20.dp),
        ) {
            when (state) {
                RodoState.Idle, is RodoState.Thinking -> {
                    if (state is RodoState.Thinking) {
                        CircularProgressIndicator(
                            Modifier.size(24.dp), strokeWidth = 2.dp, color = RobGitColors.Ai,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        if (state is RodoState.Thinking) "Rodo está pensando…"
                        else "Aquí aparecerán las recomendaciones de Rodo.",
                        color = if (state is RodoState.Thinking) RobGitColors.Ai else RobGitColors.IceMuted,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                is RodoState.Completed, is RodoState.Warning, is RodoState.Error -> {
                    val message = when (state) {
                        is RodoState.Completed -> state.text
                        is RodoState.Warning -> state.message
                        is RodoState.Error -> state.message
                        else -> error("Estado de Rodo inesperado")
                    }
                    SelectionContainer {
                        Text(
                            message,
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            color = if (state is RodoState.Completed) RobGitColors.Ice else RobGitColors.Warning,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (state is RodoState.Completed) FontWeight.Normal else FontWeight.Medium,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 27.dp)
                .background(RobGitColors.Petroleum).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(144.dp), contentAlignment = Alignment.Center) {
                AnimatedRodo(state, 112.dp)
            }
            Spacer(Modifier.width(5.dp))
            Text("Rodo", color = RobGitColors.Ice, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
        }
    }
}
