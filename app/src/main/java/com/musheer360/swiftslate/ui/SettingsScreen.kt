package com.musheer360.swiftslate.ui

import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musheer360.swiftslate.BuildConfig
import com.musheer360.swiftslate.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.musheer360.swiftslate.api.CodexApiClient
import com.musheer360.swiftslate.api.CopilotApiClient
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.model.ProviderType
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.ui.components.SlateTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(commandManager: CommandManager, prefs: SharedPreferences) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current

    val scope = rememberCoroutineScope()
    var saveEndpointJob by remember { mutableStateOf<Job?>(null) }
    var saveModelJob by remember { mutableStateOf<Job?>(null) }

    var providerType by remember { mutableStateOf(prefs.getString("provider_type", ProviderType.GEMINI) ?: ProviderType.GEMINI) }
    var providerExpanded by remember { mutableStateOf(false) }

    var selectedModel by remember { mutableStateOf(prefs.getString("model", "gemini-2.5-flash-lite") ?: "gemini-2.5-flash-lite") }
    var modelExpanded by remember { mutableStateOf(false) }
    val geminiModels = listOf("gemini-2.5-flash-lite", "gemini-3-flash-preview", "gemini-3.1-flash-lite-preview")

    var groqModel by remember { mutableStateOf(prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile") }
    var groqModelExpanded by remember { mutableStateOf(false) }
    val groqModels = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "meta-llama/llama-4-scout-17b-16e-instruct")

    var codexApiModel by remember { mutableStateOf(prefs.getString("codex_api_model", CodexApiClient.RANDOM_MODEL_ID) ?: CodexApiClient.RANDOM_MODEL_ID) }
    var codexApiModelExpanded by remember { mutableStateOf(false) }
    var codexApiModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingCodexModels by remember { mutableStateOf(false) }

    var customEndpoint by rememberSaveable { mutableStateOf(prefs.getString("custom_endpoint", "") ?: "") }
    var customModel by rememberSaveable { mutableStateOf(prefs.getString("custom_model", "") ?: "") }
    var endpointError by remember { mutableStateOf<String?>(null) }

    var triggerPrefix by remember { mutableStateOf(commandManager.getTriggerPrefix()) }
    var prefixError by remember { mutableStateOf<String?>(null) }
    var temperature by remember { mutableStateOf(prefs.getFloat("temperature", 0.5f)) }

    val prefixErrorLength = stringResource(R.string.settings_prefix_error_length)
    val prefixErrorWhitespace = stringResource(R.string.settings_prefix_error_whitespace)
    val prefixErrorAlphanumeric = stringResource(R.string.settings_prefix_error_alphanumeric)
    val endpointErrorScheme = stringResource(R.string.settings_endpoint_error_scheme)
    val endpointErrorSpaces = stringResource(R.string.settings_endpoint_error_spaces)

    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupSuccess by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }

    val codexApiClient = remember { CodexApiClient() }
    val copilotApiClient = remember { CopilotApiClient() }

    LaunchedEffect(providerType) {
        if (providerType == ProviderType.CODEX_API && codexApiModels.isEmpty()) {
            loadingCodexModels = true
            val result = codexApiClient.getAvailableModels()
            if (result.isSuccess) {
                codexApiModels = result.getOrNull() ?: emptyList()
            }
            loadingCodexModels = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            saveEndpointJob?.cancel()
            saveModelJob?.cancel()
            val editor = prefs.edit()
            var needsWrite = false
            if (customEndpoint != (prefs.getString("custom_endpoint", "") ?: "")) {
                val isValid = customEndpoint.isBlank() || customEndpoint.startsWith("https://") ||
                    (customEndpoint.startsWith("http://") && try {
                        val host = java.net.URL(customEndpoint).host
                        host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
                    } catch (_: Exception) { false })
                if (isValid) {
                    editor.putString("custom_endpoint", customEndpoint)
                    needsWrite = true
                }
            }
            if (customModel != (prefs.getString("custom_model", "") ?: "")) {
                editor.putString("custom_model", customModel)
                needsWrite = true
            }
            if (needsWrite) editor.apply()
        }
    }
    val exportSuccessMsg = stringResource(R.string.backup_export_success)
    val exportErrorMsg = stringResource(R.string.backup_export_error)
    val importSuccessMsg = stringResource(R.string.backup_import_success)
    val importErrorMsg = stringResource(R.string.backup_import_error)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(it)?.use { os ->
                            os.write(commandManager.exportCommands().toByteArray())
                        }
                    }
                    backupMessage = exportSuccessMsg
                    backupSuccess = true
                } catch (_: Exception) {
                    backupMessage = exportErrorMsg
                    backupSuccess = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                            val text = reader.readText()
                            if (text.length > 1_000_000) null else text
                        } ?: ""
                    }
                    if (commandManager.importCommands(json)) {
                        backupMessage = importSuccessMsg
                        backupSuccess = true
                    } else {
                        backupMessage = importErrorMsg
                        backupSuccess = false
                    }
                } catch (_: Exception) {
                    backupMessage = importErrorMsg
                    backupSuccess = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        ScreenTitle(stringResource(R.string.settings_title))

        SlateCard {
            Text(
                text = stringResource(R.string.settings_provider_title),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = !providerExpanded }
            ) {
                SlateTextField(
                    value = when (providerType) {
                        ProviderType.GEMINI -> stringResource(R.string.settings_provider_gemini)
                        ProviderType.GROQ -> stringResource(R.string.settings_provider_groq)
                        ProviderType.CODEX_API -> "CodexAPI (Free)"
                        ProviderType.COPILOT -> "Copilot (Free - Unofficial)"
                        else -> stringResource(R.string.settings_provider_custom)
                    },
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_provider_gemini)) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            providerType = ProviderType.GEMINI
                            prefs.edit().putString("provider_type", ProviderType.GEMINI).remove("structured_output_disabled_at").apply()
                            providerExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_provider_groq)) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            providerType = ProviderType.GROQ
                            prefs.edit().putString("provider_type", ProviderType.GROQ).remove("structured_output_disabled_at").apply()
                            providerExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("CodexAPI (Free)") },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            providerType = ProviderType.CODEX_API
                            prefs.edit().putString("provider_type", ProviderType.CODEX_API).apply()
                            providerExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Copilot (Free - Unofficial)") },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            providerType = ProviderType.COPILOT
                            prefs.edit().putString("provider_type", ProviderType.COPILOT).apply()
                            providerExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_provider_custom)) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            providerType = ProviderType.CUSTOM
                            prefs.edit().putString("provider_type", ProviderType.CUSTOM).remove("structured_output_disabled_at").apply()
                            providerExpanded = false
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            if (providerType == ProviderType.GEMINI) {
                Text(
                    text = stringResource(R.string.settings_model_title),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = !modelExpanded }
                ) {
                    SlateTextField(
                        value = selectedModel,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        geminiModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selectedModel = model
                                    prefs.edit().putString("model", model).remove("structured_output_disabled_at").apply()
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                }
            } else if (providerType == ProviderType.GROQ) {
                Text(
                    text = stringResource(R.string.settings_model_title),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                ExposedDropdownMenuBox(
                    expanded = groqModelExpanded,
                    onExpandedChange = { groqModelExpanded = !groqModelExpanded }
                ) {
                    SlateTextField(
                        value = groqModel,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        expanded = groqModelExpanded,
                        onDismissRequest = { groqModelExpanded = false }
                    ) {
                        groqModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    groqModel = model
                                    prefs.edit().putString("groq_model", model).remove("structured_output_disabled_at").apply()
                                    groqModelExpanded = false
                                }
                            )
                        }
                    }
                }
            } else if (providerType == ProviderType.CODEX_API) {
                Text(
                    text = stringResource(R.string.settings_model_title),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                ExposedDropdownMenuBox(
                    expanded = codexApiModelExpanded && codexApiModels.isNotEmpty(),
                    onExpandedChange = { 
                        if (codexApiModels.isEmpty() && !loadingCodexModels) {
                            scope.launch {
                                loadingCodexModels = true
                                val result = codexApiClient.getAvailableModels()
                                if (result.isSuccess) {
                                    codexApiModels = result.getOrNull() ?: emptyList()
                                }
                                loadingCodexModels = false
                            }
                        }
                        codexApiModelExpanded = !codexApiModelExpanded 
                    }
                ) {
                    SlateTextField(
                        value = if (loadingCodexModels) "Loading..." else if (codexApiModel == CodexApiClient.RANDOM_MODEL_ID) "Random (Any Model)" else codexApiModel,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    if (codexApiModels.isNotEmpty()) {
                        ExposedDropdownMenu(
                            containerColor = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                            expanded = codexApiModelExpanded,
                            onDismissRequest = { codexApiModelExpanded = false }
                        ) {
                            codexApiModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(if (model == CodexApiClient.RANDOM_MODEL_ID) "Random (Any Model)" else model) },
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        codexApiModel = model
                                        prefs.edit().putString("codex_api_model", model).apply()
                                        codexApiModelExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            } else if (providerType == ProviderType.COPILOT) {
                Text(
                    text = "Using Copilot Model",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                SlateTextField(
                    value = "copilot",
                    onValueChange = {},
                    readOnly = true
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_endpoint_title),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                SlateTextField(
                    value = customEndpoint,
                    onValueChange = {
                        customEndpoint = it
                        endpointError = when {
                            it.isBlank() -> null
                            it.contains(" ") -> endpointErrorSpaces
                            it.startsWith("https://") -> null
                            it.startsWith("http://") -> {
                                val host = try { java.net.URL(it).host } catch (_: Exception) { "" }
                                if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") null
                                else endpointErrorScheme
                            }
                            else -> endpointErrorScheme
                        }
                        if (endpointError == null) {
                            saveEndpointJob?.cancel()
                            saveEndpointJob = scope.launch {
                                delay(500)
                                prefs.edit().putString("custom_endpoint", it).remove("structured_output_disabled_at").apply()
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.settings_endpoint_placeholder)) },
                    isError = endpointError != null
                )
                endpointError?.let { msg ->
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_model_title),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                SlateTextField(
                    value = customModel,
                    onValueChange = {
                        customModel = it
                        saveModelJob?.cancel()
                        saveModelJob = scope.launch {
                            delay(500)
                            prefs.edit().putString("custom_model", it).remove("structured_output_disabled_at").apply()
                        }
                    },
                    placeholder = { Text(stringResource(R.string.settings_model_placeholder)) },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_temperature_title),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = String.format("%.1f", temperature),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Slider(
                value = temperature,
                onValueChange = {
                    val newVal = Math.round(it * 10) / 10f
                    if (newVal != temperature) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        temperature = newVal
                    }
                },
                onValueChangeFinished = {
                    prefs.edit().putFloat("temperature", temperature).apply()
                },
                valueRange = 0f..2f,
                steps = 19,
                modifier = Modifier.fillMaxWidth().height(26.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outline
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        SlateCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_trigger_prefix_desc, triggerPrefix),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 16.dp)
                )
                SlateTextField(
                    value = triggerPrefix,
                    onValueChange = { input ->
                        val filtered = input.take(1)
                        triggerPrefix = filtered
                        prefixError = when {
                            filtered.length != 1 -> prefixErrorLength
                            filtered[0].isWhitespace() -> prefixErrorWhitespace
                            !filtered[0].isLetterOrDigit() && filtered != "#" && filtered != "@" && filtered != "!" -> prefixErrorAlphanumeric
                            else -> null
                        }
                        if (prefixError == null) {
                            commandManager.setTriggerPrefix(filtered)
                            prefs.edit().putString("trigger_prefix", filtered).apply()
                        }
                    },
                    placeholder = { Text(triggerPrefix) },
                    singleLine = true,
                    modifier = Modifier.width(60.dp),
                    isError = prefixError != null
                )
            }
            prefixError?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        SlateCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { exportLauncher.launch("commands.json") }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_backup_export),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { importLauncher.launch(arrayOf("application/json")) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_backup_import),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (backupMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            SlateCard {
                Text(
                    text = backupMessage!!,
                    fontSize = 13.sp,
                    color = if (backupSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        SlateCard {
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}