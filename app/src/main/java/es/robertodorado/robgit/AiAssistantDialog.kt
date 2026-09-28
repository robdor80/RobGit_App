package es.robertodorado.robgit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Displays text responses. Receives no callback for analysis or any Git action. */
@Composable
internal fun AiAssistantDialog(
    context: AiContext,
    service: AiAssistantService,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(AiTask.EXPLAIN_STATUS) }
    var menu by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember(context, selected) {
        mutableStateOf<AiAssistantResult?>(null)
    }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Asistente RobGit")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box {
                    OutlinedButton(
                        onClick = { menu = true },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(selected.label + "  ▾")
                    }

                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                    ) {
                        AiTask.entries.forEach { task ->
                            DropdownMenuItem(
                                text = { Text(task.label) },
                                onClick = {
                                    selected = task
                                    menu = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = question,
                    onValueChange = {
                        question = it.take(AiContextFilter.MAX_QUESTION)
                        result = null
                    },
                    label = {
                        Text(
                            if (selected == AiTask.CUSTOM_QUESTION) {
                                "Pregunta personalizada"
                            } else {
                                "Detalle adicional (opcional)"
                            }
                        )
                    },
                    minLines = 3,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (
                    selected.requiresAnalyzedState &&
                    context.gitStatus == null &&
                    result == null
                ) {
                    Text(
                        AiAssistantService.ANALYSIS_REQUIRED_MESSAGE,
                        color = RobGitColors.Warning,
                    )
                }

                if (running) {
                    Text(
                        "Consultando a Gemini…",
                        color = RobGitColors.Ai,
                    )
                }

                when (val latest = result) {
                    is AiAssistantResult.Completed -> {
                        Text(
                            latest.response.text,
                            color = RobGitColors.Ai,
                        )
                    }

                    is AiAssistantResult.Unavailable -> {
                        Text(
                            latest.message,
                            color = RobGitColors.Warning,
                        )
                    }

                    is AiAssistantResult.Failed -> {
                        Text(
                            latest.message,
                            color = RobGitColors.Warning,
                        )
                    }

                    null -> Unit
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !running,
                onClick = {
                    running = true
                    result = null

                    scope.launch {
                        try {
                            result = service.execute(
                                selected,
                                context,
                                question,
                            )
                        } finally {
                            running = false
                        }
                    }
                },
            ) {
                Text("PREGUNTAR")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !running,
            ) {
                Text("CERRAR")
            }
        },
    )
}