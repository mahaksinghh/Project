package com.appguardian.mobile

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val colors = lightColorScheme(
    primary = Color(0xFF175DA3),
    background = Color(0xFFF7FBFF),
    onBackground = Color(0xFF193455),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9F3FA),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = colors) { Guardian() } }
    }
}

@Composable
private fun Guardian() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inspection by remember { mutableStateOf<Inspection?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var sharing by remember { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

    fun runOperation(action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = error.message ?: "Couldn't finish the operation."
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            inspection = null
            message = "Inspecting locally…"
            runOperation {
                inspection = withContext(Dispatchers.IO) { inspect(context, uri) }
                message = "Inspection complete."
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("AppGuardian", style = MaterialTheme.typography.titleLarge)
            Column(
                Modifier.fillMaxWidth().background(
                    Brush.linearGradient(listOf(Color(0xFFEAF2FF), Color(0xFFE3FAF1))),
                    RoundedCornerShape(28.dp),
                ).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Inspect an APK", style = MaterialTheme.typography.headlineLarge)
                Text("See the permissions an app declares. Your selected APK is inspected on this device.")
                Button(enabled = !busy, onClick = {
                    picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip"))
                }) { Text("Choose APK") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            AnimatedVisibility(visible = inspection != null) {
                inspection?.let { result ->
                    ReportCard(result, busy, onShare = { sharing = true }, onClear = {
                        inspection = null
                        key = ""
                        message = "Local report cleared."
                    })
                }
            }
            HorizontalDivider()
            Text("Privacy controls", style = MaterialTheme.typography.titleLarge)
            Text("Android Settings lets you review and change permissions for installed apps.")
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
            }) { Text("Open Android app settings") }
        }
    }

    val selected = inspection
    if (sharing && selected != null) {
        AlertDialog(
            onDismissRequest = { sharing = false; key = "" },
            title = { Text("Share this inspection?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Send the package name, SHA-256 fingerprint and declared permissions to your private workspace.")
                    OutlinedTextField(address, { address = it }, label = { Text("HTTPS API address") }, singleLine = true)
                    OutlinedTextField(key, { key = it }, label = { Text("Workspace key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && address.isNotBlank() && key.isNotBlank(), onClick = {
                    val destination = address
                    val credential = key.trim()
                    key = ""
                    sharing = false
                    message = "Sharing report…"
                    runOperation {
                        withContext(Dispatchers.IO) { share(selected, destination, credential) }
                        message = "Report shared with your workspace."
                    }
                }) { Text("Share report") }
            },
            dismissButton = { TextButton(onClick = { sharing = false; key = "" }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReportCard(result: Inspection, busy: Boolean, onShare: () -> Unit, onClear: () -> Unit) {
    var showPermissions by remember(result) { mutableStateOf(false) }
    val assessment = result.assessment
    val progress by animateFloatAsState(assessment.score / 100f, label = "Risk score")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(result.packageName, style = MaterialTheme.typography.titleLarge)
            Text("${assessment.level} · ${assessment.score}/100")
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("This score reflects declared permissions. Review the source and purpose of the APK.")
            if (assessment.findings.isEmpty()) Text("No rules matched the declared permissions.")
            assessment.findings.forEach { finding ->
                Text("${finding.permission} · +${finding.weight}", style = MaterialTheme.typography.titleSmall)
                Text(finding.explanation)
            }
            Text("SHA-256 · ${result.sha256}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showPermissions = !showPermissions }) {
                Text(if (showPermissions) "Hide permissions" else "Show all permissions (${result.permissions.size})")
            }
            AnimatedVisibility(showPermissions) {
                Column { result.permissions.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
            }
            Column {
                Button(enabled = !busy, onClick = onShare) { Text("Share to dashboard") }
                TextButton(enabled = !busy, onClick = onClear) { Text("Clear local report") }
            }
        }
    }
}
