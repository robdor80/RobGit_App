package es.robertodorado.robgit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Selector only. Execution/state live in RodoSession, outside this modal. */
@Composable
internal fun AiAssistantDialog(
    context: AiContext,
    state: RodoState,
    showResultInDialog: Boolean,
    onSubmit: (AiTask, String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(AiTask.EXPLAIN_STATUS) }
    var menu by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var showExistingResult by remember { mutableStateOf(true) }
    val thinking = state is RodoState.Thinking

    Dialog(
        onDismissRequest = { if (!thinking) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.92f).heightIn(max = 630.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(RobGitDimens.Radius),
            color = RobGitColors.PetroleumDark,
            contentColor = RobGitColors.Ice,
            border = BorderStroke(RobGitDimens.Border, RobGitColors.Ice),
            shadowElevation = 14.dp,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedRodo(state, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Rodo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Asistente personal", color = RobGitColors.IceMuted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }

                Box {
                    OutlinedButton(
                        onClick = { menu = true },
                        enabled = !thinking,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(RobGitDimens.Border, RobGitColors.Ice),
                    ) { Text(selected.label + "  ▾", color = RobGitColors.Ice) }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        modifier = Modifier.background(RobGitColors.PetroleumLight),
                    ) {
                        AiTask.entries.forEach { task ->
                            DropdownMenuItem(
                                text = { Text(task.label, color = RobGitColors.Ice) },
                                onClick = { selected = task; menu = false; showExistingResult = false },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(AiContextFilter.MAX_QUESTION); showExistingResult = false },
                    label = { Text(if (selected == AiTask.CUSTOM_QUESTION)
                        "Pregunta personalizada" else "Detalle adicional (opcional)") },
                    minLines = 3,
                    enabled = !thinking,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RobGitColors.Ice,
                        unfocusedBorderColor = RobGitColors.IceMuted,
                        focusedLabelColor = RobGitColors.Ice,
                        cursorColor = RobGitColors.Ai,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (selected.requiresAnalyzedState && context.gitStatus == null) {
                    Text(AiAssistantService.ANALYSIS_REQUIRED_MESSAGE, color = RobGitColors.Warning)
                }

                if (showResultInDialog && showExistingResult) when (state) {
                    is RodoState.Thinking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp,
                            color = RobGitColors.Ai)
                        Spacer(Modifier.width(10.dp))
                        Text("Rodo está pensando…", color = RobGitColors.Ai)
                    }
                    is RodoState.Completed -> Text(state.text, color = RobGitColors.Ice)
                    is RodoState.Warning -> Text(state.message, color = RobGitColors.Warning)
                    is RodoState.Error -> Text(state.message, color = RobGitColors.Warning)
                    RodoState.Idle -> Unit
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !thinking) { Text("CERRAR", color = RobGitColors.IceMuted) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = !thinking,
                        onClick = {
                            if (onSubmit(selected, question)) {
                                showExistingResult = true
                                if (!showResultInDialog) onDismiss()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RobGitColors.Ice,
                            contentColor = RobGitColors.PetroleumDeep,
                        ),
                    ) { Text("PREGUNTAR") }
                }
            }
        }
    }
}
