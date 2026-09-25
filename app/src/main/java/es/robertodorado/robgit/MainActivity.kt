package es.robertodorado.robgit

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val diagnosticRoot = File(filesDir, "diagnostics")
        val service = GitRepositoryService()
        setContent {
            MaterialTheme {
                DiagnosticScreen(diagnosticRoot, service)
            }
        }
    }
}

@Composable
private fun DiagnosticScreen(root: File, service: GitRepositoryService) {
    var result by remember { mutableStateOf<DiagnosticResult?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("RobGit — Diagnóstico JGit", style = MaterialTheme.typography.headlineSmall)
            Text("JGit: ${if (running) "ejecutando" else if (result == null) "pendiente" else if (result?.error == null) "correcto" else "error"}")
            Text("Repositorio local: ${result?.repositoryPath ?: "pendiente"}")
            Text("Rama: ${result?.branch ?: "pendiente"}")
            Text("Último commit: ${result?.lastCommit ?: "pendiente"}")
            Text("Estado: ${result?.status ?: "pendiente"}")

            Button(
                onClick = {
                    running = true
                    result = null
                    val directory = File(root, UUID.randomUUID().toString())
                    scope.launch {
                        try {
                            val completed = withContext(Dispatchers.IO) {
                                service.runLocalDiagnostic(directory)
                            }
                            result = completed
                            completed.steps.forEach { Log.i("RobGitDiagnostic", it.description + ": " + it.detail) }
                            completed.error?.let { Log.e("RobGitDiagnostic", it) }
                        } catch (error: Throwable) {
                            if (error !is Exception && error !is LinkageError) throw error
                            val detail = "${error.javaClass.simpleName}: ${error.message ?: "sin detalle"}"
                            result = DiagnosticResult(directory.absolutePath, "Sin consultar", "Sin commits", "Error", emptyList(), detail)
                            Log.e("RobGitDiagnostic", "Error inesperado durante la prueba", error)
                        } finally {
                            running = false
                        }
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
            ) {
                Text("EJECUTAR PRUEBA LOCAL")
            }

            Text("Resultados", style = MaterialTheme.typography.titleMedium)
            result?.steps?.forEach { step ->
                Text("✓ ${step.description}${if (step.detail.isBlank()) "" else ": ${step.detail}"}")
            }
            result?.error?.let { Text("✗ $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}
