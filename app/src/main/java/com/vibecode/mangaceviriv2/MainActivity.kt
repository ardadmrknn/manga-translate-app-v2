package com.vibecode.mangaceviriv2

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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

class MainActivity : ComponentActivity() {

    private val overlayPermissionState = mutableStateOf(false)
    private val serviceStarted = mutableStateOf(false)
    private val availableModels = mutableStateOf(listOf(DEFAULT_MODEL_FILE))
    private val selectedModel = mutableStateOf(DEFAULT_MODEL_FILE)
    private val selectedSourceLanguage = mutableStateOf(SOURCE_LANGUAGE_OPTIONS.first())
    private val selectedTargetLanguage = mutableStateOf(TARGET_LANGUAGE_OPTIONS.first())
    private val selectedTone = mutableStateOf(TONE_OPTIONS.first())
    private val selectedLiteralMode = mutableStateOf(false)

    private val mediaProjectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        overlayPermissionState.value = hasOverlayPermission()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startTranslationService(result.resultCode, result.data!!)
            serviceStarted.value = true
        } else {
            serviceStarted.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        overlayPermissionState.value = hasOverlayPermission()
        val models = loadModelOptions()
        availableModels.value = models
        if (selectedModel.value !in models) {
            selectedModel.value = models.first()
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        hasOverlayPermission = overlayPermissionState.value,
                        started = serviceStarted.value,
                        selectedModel = selectedModel.value,
                        modelOptions = availableModels.value,
                        selectedSource = selectedSourceLanguage.value,
                        sourceOptions = SOURCE_LANGUAGE_OPTIONS,
                        selectedTarget = selectedTargetLanguage.value,
                        targetOptions = TARGET_LANGUAGE_OPTIONS,
                        selectedTone = selectedTone.value,
                        literalMode = selectedLiteralMode.value,
                        toneOptions = TONE_OPTIONS,
                        onRequestOverlay = ::requestOverlayPermission,
                        onStart = ::requestMediaProjectionAndStart,
                        onStop = {
                            stopTranslationService()
                            serviceStarted.value = false
                        },
                        onModelSelected = { selectedModel.value = it },
                        onSourceSelected = { selectedSourceLanguage.value = it },
                        onTargetSelected = { selectedTargetLanguage.value = it },
                        onToneSelected = { selectedTone.value = it },
                        onLiteralModeChanged = { selectedLiteralMode.value = it }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayPermissionState.value = hasOverlayPermission()
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun requestMediaProjectionAndStart() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun startTranslationService(resultCode: Int, resultData: Intent) {
        val intent = Intent(this, MangaTranslationService::class.java)
            .setAction(MangaTranslationService.ACTION_START_PROJECTION)
            .putExtra(MangaTranslationService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(MangaTranslationService.EXTRA_RESULT_DATA, resultData)
            .putExtra(MangaTranslationService.EXTRA_MODEL_FILE_NAME, selectedModel.value)
            .putExtra(MangaTranslationService.EXTRA_SOURCE_LANGUAGE, selectedSourceLanguage.value)
            .putExtra(MangaTranslationService.EXTRA_TARGET_LANGUAGE, selectedTargetLanguage.value)
            .putExtra(MangaTranslationService.EXTRA_TRANSLATION_TONE, selectedTone.value)
            .putExtra(MangaTranslationService.EXTRA_LITERAL_TRANSLATION, selectedLiteralMode.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopTranslationService() {
        stopService(Intent(this, MangaTranslationService::class.java))
    }

    private fun loadModelOptions(): List<String> {
        val fromAssets = runCatching {
            assets.list("")
                ?.filter { it.endsWith(".litertlm") || it.endsWith(".task") }
                ?.sorted()
                ?: emptyList()
        }.getOrDefault(emptyList())

        return if (fromAssets.isNotEmpty()) fromAssets else listOf(DEFAULT_MODEL_FILE)
    }

    companion object {
        private const val DEFAULT_MODEL_FILE = "gemma-4-E2B-it.litertlm"
        private val SOURCE_LANGUAGE_OPTIONS = listOf("English", "Japanese")
        private val TARGET_LANGUAGE_OPTIONS = listOf("Turkish", "English")
        private val TONE_OPTIONS = listOf("Dogal", "Resmi", "Kisa")
    }
}

@Composable
private fun MainScreen(
    hasOverlayPermission: Boolean,
    started: Boolean,
    selectedModel: String,
    modelOptions: List<String>,
    selectedSource: String,
    sourceOptions: List<String>,
    selectedTarget: String,
    targetOptions: List<String>,
    selectedTone: String,
    literalMode: Boolean,
    toneOptions: List<String>,
    onRequestOverlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onModelSelected: (String) -> Unit,
    onSourceSelected: (String) -> Unit,
    onTargetSelected: (String) -> Unit,
    onToneSelected: (String) -> Unit,
    onLiteralModeChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Manga Çeviri",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Model ve dil ayarlarını seç, ardından çeviriyi başlat.",
            style = MaterialTheme.typography.bodyMedium
        )

        PermissionCard(
            hasOverlayPermission = hasOverlayPermission,
            onRequestOverlay = onRequestOverlay
        )

        SettingsCard(
            selectedModel = selectedModel,
            modelOptions = modelOptions,
            selectedSource = selectedSource,
            sourceOptions = sourceOptions,
            selectedTarget = selectedTarget,
            targetOptions = targetOptions,
            selectedTone = selectedTone,
            literalMode = literalMode,
            toneOptions = toneOptions,
            onModelSelected = onModelSelected,
            onSourceSelected = onSourceSelected,
            onTargetSelected = onTargetSelected,
            onToneSelected = onToneSelected,
            onLiteralModeChanged = onLiteralModeChanged
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStart,
                enabled = hasOverlayPermission && !started
            ) {
                Text(if (started) "Servis Çalışıyor" else "Çeviriyi Başlat")
            }

            if (started) {
                TextButton(onClick = onStop) {
                    Text("Durdur")
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    hasOverlayPermission: Boolean,
    onRequestOverlay: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (hasOverlayPermission) "Overlay izni hazır" else "Overlay izni gerekli",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Baloncuk ve seçim popup’ı için bu izin zorunlu.",
                style = MaterialTheme.typography.bodySmall
            )
            if (!hasOverlayPermission) {
                Button(onClick = onRequestOverlay) {
                    Text("Overlay iznini ver")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(
    selectedModel: String,
    modelOptions: List<String>,
    selectedSource: String,
    sourceOptions: List<String>,
    selectedTarget: String,
    targetOptions: List<String>,
    selectedTone: String,
    literalMode: Boolean,
    toneOptions: List<String>,
    onModelSelected: (String) -> Unit,
    onSourceSelected: (String) -> Unit,
    onTargetSelected: (String) -> Unit,
    onToneSelected: (String) -> Unit,
    onLiteralModeChanged: (Boolean) -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Çeviri Ayarları", style = MaterialTheme.typography.titleMedium)
            DropdownSetting(
                label = "Model",
                value = selectedModel,
                options = modelOptions,
                onSelected = onModelSelected
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    DropdownSetting(
                        label = "Kaynak Dil",
                        value = selectedSource,
                        options = sourceOptions,
                        onSelected = onSourceSelected
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    DropdownSetting(
                        label = "Hedef Dil",
                        value = selectedTarget,
                        options = targetOptions,
                        onSelected = onTargetSelected
                    )
                }
            }

            Text("Ton", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                toneOptions.forEach { tone ->
                    AssistChip(
                        onClick = { onToneSelected(tone) },
                        label = { Text(tone) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sansursuz/Birebir Ceviri", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Acik oldugunda model metni yumusatmadan cevirir (uygunsa).",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = literalMode,
                    onCheckedChange = onLiteralModeChanged
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSetting(
    label: String,
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = true
                )
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
