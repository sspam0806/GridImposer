package com.gridimpose

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            AppTheme { MainScreen(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share -> Grid Imposer" from another app. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { vm.setInput(it) }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF9CCBFB),
            onPrimary = Color(0xFF003355),
            primaryContainer = Color(0xFF00497D),
            onPrimaryContainer = Color(0xFFCAEBFD),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1F6FB2),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFCAEBFD), // same blue the ruled lines are drawn in
            onPrimaryContainer = Color(0xFF0B3556),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setInput(uri)
    }
    val savePdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) vm.run(uri)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Grid Imposer", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Puts a ruled page next to every page of your PDF, side by side on one wider " +
                    "sheet, each page outlined and 12 pt apart.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- Input file
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        state.inputName ?: "No PDF selected",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(
                        onClick = { pickPdf.launch(arrayOf("application/pdf")) },
                        enabled = state.status !is Status.Working,
                    ) {
                        Text(if (state.inputUri == null) "Choose PDF" else "Choose a different PDF")
                    }
                }
            }

            // --- Ruling style
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RuleStyle.entries.forEachIndexed { index, style ->
                    SegmentedButton(
                        selected = state.style == style,
                        onClick = { vm.setStyle(style) },
                        shape = SegmentedButtonDefaults.itemShape(index, RuleStyle.entries.size),
                    ) { Text(style.label) }
                }
            }

            OutlinedTextField(
                value = state.spacingText,
                onValueChange = vm::setSpacingText,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (state.style == RuleStyle.GRID) "Square size" else "Line spacing") },
                suffix = { Text("mm") },
                singleLine = true,
                isError = state.spacingMm == null,
                supportingText = if (state.spacingMm == null) {
                    { Text("Enter a number from 1 to 50") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            // --- Go
            Button(
                onClick = { savePdf.launch(vm.suggestedOutputName()) },
                enabled = state.canCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) { Text("Create PDF") }

            // --- Result
            when (val s = state.status) {
                Status.Idle -> Unit
                is Status.Working -> {
                    if (s.total > 0) {
                        LinearProgressIndicator(
                            progress = { s.done.toFloat() / s.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Processing page ${s.done} of ${s.total}")
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Reading PDF…")
                    }
                }
                is Status.Done -> {
                    Text("Saved ${s.name}", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { openPdf(context, s.uri) }) { Text("Open") }
                        OutlinedButton(onClick = { sharePdf(context, s.uri) }) { Text("Share") }
                    }
                }
                is Status.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun openPdf(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No PDF viewer installed", Toast.LENGTH_SHORT).show()
    }
}

private fun sharePdf(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}
