package com.vibecode.mangaceviriv2

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.LinkedHashMap

class MainActivity : ComponentActivity() {

    private val overlayPermissionState = mutableStateOf(false)
    private val hasModelAsset = mutableStateOf(false)
    private val captureStepCompleted = mutableStateOf(false)
    private val serviceStarted = mutableStateOf(false)
    private val availableModels = mutableStateOf(listOf(DEFAULT_MODEL_FILE))
    private val selectedModel = mutableStateOf(DEFAULT_MODEL_FILE)
    private val selectedSourceLanguage = mutableStateOf(SOURCE_LANGUAGE_OPTIONS.first())
    private val selectedTargetLanguage = mutableStateOf(TARGET_LANGUAGE_OPTIONS.first())
    private val selectedTone = mutableStateOf(TONE_OPTIONS.first())
    private val selectedLiteralMode = mutableStateOf(false)
    private val selectedDictionaryProfile = mutableStateOf(TranslationCoreUtils.DEFAULT_DICTIONARY_PROFILE)
    private val dictionaryProfileOptions = mutableStateOf(listOf(TranslationCoreUtils.DEFAULT_DICTIONARY_PROFILE))
    private val dictionaryTransferText = mutableStateOf("")
    private val feedbackEntries = mutableStateOf<List<FeedbackEntry>>(emptyList())
    private val feedbackStatusMessage = mutableStateOf("")
    private val metricsSnapshot = mutableStateOf(TranslationMetricsSnapshot())
    private val debugRecords = mutableStateOf<List<TranslationDebugRecord>>(emptyList())
    private var cachedProjectionResultCode: Int? = null
    private var cachedProjectionData: Intent? = null

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
            cacheProjectionGrant(result.resultCode, result.data!!)
            startTranslationService(result.resultCode, result.data!!)
            serviceStarted.value = true
            captureStepCompleted.value = true
            onboardingPrefs()
                .edit()
                .putBoolean(KEY_CAPTURE_STEP_DONE, true)
                .putBoolean(KEY_CAPTURE_APPROVED_ONCE, true)
                .apply()
            refreshDashboardData()
        } else {
            serviceStarted.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        overlayPermissionState.value = hasOverlayPermission()
        captureStepCompleted.value = onboardingPrefs().getBoolean(KEY_CAPTURE_STEP_DONE, false)
        if (onboardingPrefs().getBoolean(KEY_CAPTURE_APPROVED_ONCE, false)) {
            captureStepCompleted.value = true
        }
        selectedDictionaryProfile.value = TranslationCoreUtils.normalizeProfileName(
            onboardingPrefs().getString(KEY_DICTIONARY_PROFILE, TranslationCoreUtils.DEFAULT_DICTIONARY_PROFILE)
                ?: TranslationCoreUtils.DEFAULT_DICTIONARY_PROFILE
        )

        val assetModels = listModelAssets()
        hasModelAsset.value = assetModels.isNotEmpty()
        availableModels.value = if (assetModels.isNotEmpty()) assetModels else listOf(DEFAULT_MODEL_FILE)
        if (selectedModel.value !in availableModels.value) {
            selectedModel.value = availableModels.value.first()
        }

        refreshDashboardData()

        setContent {
            MaterialTheme(colorScheme = AmoledDarkColorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        hasOverlayPermission = overlayPermissionState.value,
                        hasModelAsset = hasModelAsset.value,
                        captureStepCompleted = captureStepCompleted.value,
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
                        selectedDictionaryProfile = selectedDictionaryProfile.value,
                        dictionaryProfileOptions = dictionaryProfileOptions.value,
                        dictionaryTransferText = dictionaryTransferText.value,
                        feedbackEntries = feedbackEntries.value,
                        feedbackStatusMessage = feedbackStatusMessage.value,
                        metricsSnapshot = metricsSnapshot.value,
                        debugRecords = debugRecords.value,
                        onRequestOverlay = ::requestOverlayPermission,
                        onStart = ::requestMediaProjectionAndStart,
                        onStop = {
                            stopTranslationService()
                            serviceStarted.value = false
                        },
                        onDictionaryProfileChanged = ::saveDictionaryProfile,
                        onDictionaryTransferTextChanged = { dictionaryTransferText.value = it },
                        onExportDictionary = ::exportDictionaryProfile,
                        onImportDictionary = ::importDictionaryProfile,
                        onPasteDictionaryJson = ::pasteDictionaryJsonFromClipboard,
                        onClearCurrentProfileDictionary = ::clearCurrentProfileDictionary,
                        onSaveFeedbackEdits = ::saveFeedbackEdits,
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
        refreshDashboardData()
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
        if (startWithCachedProjectionIfPossible()) {
            captureStepCompleted.value = true
            feedbackStatusMessage.value = "Ekran kaydi izni bu oturumda otomatik kullanildi"
            return
        }

        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun cacheProjectionGrant(resultCode: Int, resultData: Intent) {
        cachedProjectionResultCode = resultCode
        cachedProjectionData = Intent(resultData)
    }

    private fun startWithCachedProjectionIfPossible(): Boolean {
        val cachedCode = cachedProjectionResultCode ?: return false
        val cachedData = cachedProjectionData ?: return false
        startTranslationService(cachedCode, Intent(cachedData))
        serviceStarted.value = true
        return true
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
            .putExtra(MangaTranslationService.EXTRA_DICTIONARY_PROFILE, selectedDictionaryProfile.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopTranslationService() {
        stopService(Intent(this, MangaTranslationService::class.java))
    }

    private fun listModelAssets(): List<String> {
        return runCatching {
            assets.list("")
                ?.filter { it.endsWith(".litertlm") || it.endsWith(".task") }
                ?.sorted()
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun saveFeedbackEdits(editsById: Map<String, String>) {
        val applied = TranslationFeedbackStore.applyCorrections(this, editsById)
        feedbackStatusMessage.value = if (applied > 0) {
            "$applied duzeltme kaydedildi. Sonraki cevirilerde kullanilacak."
        } else {
            "Kaydedilecek degisiklik bulunamadi."
        }
        refreshDashboardData()
    }

    private fun saveDictionaryProfile(profile: String) {
        val normalized = TranslationCoreUtils.normalizeProfileName(profile)
        selectedDictionaryProfile.value = normalized
        onboardingPrefs().edit().putString(KEY_DICTIONARY_PROFILE, normalized).apply()
        feedbackStatusMessage.value = "Aktif profil: $normalized"
        refreshDashboardData()
    }

    private fun exportDictionaryProfile() {
        val exported = TranslationFeedbackStore.exportProfileDictionaryJson(
            context = this,
            profileName = selectedDictionaryProfile.value
        )
        dictionaryTransferText.value = exported
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("dictionary_export", exported))
        feedbackStatusMessage.value = "Profil JSON disa aktarildi ve panoya kopyalandi"
    }

    private fun importDictionaryProfile() {
        val result = TranslationFeedbackStore.importProfileDictionaryJson(
            context = this,
            rawJson = dictionaryTransferText.value,
            targetProfile = selectedDictionaryProfile.value
        )
        feedbackStatusMessage.value = "${result.message} (atlanan: ${result.skippedCount})"
        refreshDashboardData()
    }

    private fun pasteDictionaryJsonFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            feedbackStatusMessage.value = "Panoda yapistirilacak JSON bulunamadi"
            return
        }
        dictionaryTransferText.value = text
        feedbackStatusMessage.value = "Pano JSON metni alindi"
    }

    private fun clearCurrentProfileDictionary() {
        val removed = TranslationFeedbackStore.clearProfileDictionary(
            context = this,
            profileName = selectedDictionaryProfile.value
        )
        feedbackStatusMessage.value = "$removed kayit temizlendi"
        refreshDashboardData()
    }

    private fun refreshDashboardData() {
        val availableProfiles = TranslationFeedbackStore.listAvailableProfiles(this).toMutableList()
        if (selectedDictionaryProfile.value !in availableProfiles) {
            availableProfiles.add(selectedDictionaryProfile.value)
        }
        dictionaryProfileOptions.value = availableProfiles.sorted()
        feedbackEntries.value = TranslationFeedbackStore.loadRecentTranslations(this)
        metricsSnapshot.value = TranslationMetricsStore.snapshot(this)
        debugRecords.value = TranslationDebugStore.loadRecentRecords(this)
    }

    private fun onboardingPrefs() = getSharedPreferences(ONBOARDING_PREFS, MODE_PRIVATE)

    companion object {
        private const val DEFAULT_MODEL_FILE = "gemma-4-E2B-it.litertlm"
        private const val ONBOARDING_PREFS = "main_onboarding_prefs"
        private const val KEY_CAPTURE_STEP_DONE = "capture_step_done"
        private const val KEY_CAPTURE_APPROVED_ONCE = "capture_approved_once"
        private const val KEY_DICTIONARY_PROFILE = "dictionary_profile"
        private val SOURCE_LANGUAGE_OPTIONS = listOf("English", "Japanese")
        private val TARGET_LANGUAGE_OPTIONS = listOf("Turkish", "English")
        private val TONE_OPTIONS = listOf(
            "Dogal",
            "Kisa",
            "Resmi",
            "Samimi",
            "Akici",
            "Dramatik",
            "Mizahi",
            "Sert",
            "Edebi",
            "Manga Diyalog",
            "Birebir",
            "Hafif Argo"
        )
    }
}

private val AmoledDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color.Black,
    secondary = Color(0xFF00B8D4),
    onSecondary = Color.Black,
    tertiary = Color(0xFF80CBC4),
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFE8EEF2),
    surface = Color.Black,
    onSurface = Color(0xFFE8EEF2),
    surfaceVariant = Color(0xFF050607),
    onSurfaceVariant = Color(0xFFB5C0C8),
    outline = Color(0xFF2A3A44)
)

@Composable
private fun MainScreen(
    hasOverlayPermission: Boolean,
    hasModelAsset: Boolean,
    captureStepCompleted: Boolean,
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
    selectedDictionaryProfile: String,
    dictionaryProfileOptions: List<String>,
    dictionaryTransferText: String,
    feedbackEntries: List<FeedbackEntry>,
    feedbackStatusMessage: String,
    metricsSnapshot: TranslationMetricsSnapshot,
    debugRecords: List<TranslationDebugRecord>,
    onRequestOverlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDictionaryProfileChanged: (String) -> Unit,
    onDictionaryTransferTextChanged: (String) -> Unit,
    onExportDictionary: () -> Unit,
    onImportDictionary: () -> Unit,
    onPasteDictionaryJson: () -> Unit,
    onClearCurrentProfileDictionary: () -> Unit,
    onSaveFeedbackEdits: (Map<String, String>) -> Unit,
    onModelSelected: (String) -> Unit,
    onSourceSelected: (String) -> Unit,
    onTargetSelected: (String) -> Unit,
    onToneSelected: (String) -> Unit,
    onLiteralModeChanged: (Boolean) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
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

        OnboardingCard(
            hasOverlayPermission = hasOverlayPermission,
            hasModelAsset = hasModelAsset,
            captureStepCompleted = captureStepCompleted,
            onRequestOverlay = onRequestOverlay,
            onStartCapture = onStart
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
                enabled = hasOverlayPermission && hasModelAsset && !started
            ) {
                Text(if (started) "Servis Çalışıyor" else "Çeviriyi Başlat")
            }

            if (started) {
                TextButton(onClick = onStop) {
                    Text("Durdur")
                }
            }
        }

        DictionaryProfileCard(
            selectedDictionaryProfile = selectedDictionaryProfile,
            dictionaryProfileOptions = dictionaryProfileOptions,
            dictionaryTransferText = dictionaryTransferText,
            onDictionaryProfileChanged = onDictionaryProfileChanged,
            onDictionaryTransferTextChanged = onDictionaryTransferTextChanged,
            onExportDictionary = onExportDictionary,
            onImportDictionary = onImportDictionary,
            onPasteDictionaryJson = onPasteDictionaryJson,
            onClearCurrentProfileDictionary = onClearCurrentProfileDictionary
        )

        FeedbackCard(
            entries = feedbackEntries,
            statusMessage = feedbackStatusMessage,
            onSaveFeedbackEdits = onSaveFeedbackEdits
        )

        MetricsCard(snapshot = metricsSnapshot)

        DebugRecordsCard(records = debugRecords)
    }
}

@Composable
private fun OnboardingCard(
    hasOverlayPermission: Boolean,
    hasModelAsset: Boolean,
    captureStepCompleted: Boolean,
    onRequestOverlay: () -> Unit,
    onStartCapture: () -> Unit
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
                text = "Kurulum Sihirbazi",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = if (hasOverlayPermission) "✓ 1) Overlay izni" else "• 1) Overlay izni",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (hasModelAsset) "✓ 2) Model dosyasi" else "• 2) Model dosyasi",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (captureStepCompleted) "✓ 3) Ekran yakalama izni" else "• 3) Ekran yakalama izni",
                style = MaterialTheme.typography.bodyMedium
            )

            if (!hasOverlayPermission) {
                Button(onClick = onRequestOverlay) {
                    Text("Overlay iznini ver")
                }
            } else if (!hasModelAsset) {
                Text(
                    text = "Assets altinda .litertlm veya .task model dosyasi bulunamadi.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (!captureStepCompleted) {
                Button(onClick = onStartCapture) {
                    Text("Ekran yakalama iznini tamamla")
                }
            } else {
                Text(
                    text = "Kurulum adimlari tamamlandi. Ilk onaydan sonra bu oturumda ekran kaydi izni otomatik kullanilir.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun FeedbackCard(
    entries: List<FeedbackEntry>,
    statusMessage: String,
    onSaveFeedbackEdits: (Map<String, String>) -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Son Ceviri Duzeltmeleri", style = MaterialTheme.typography.titleMedium)

            if (entries.isEmpty()) {
                Text(
                    text = "Henuz duzeltme icin kayitli ceviri yok. Bir ceviri yapildiktan sonra burada duzenlenebilir.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (statusMessage.isNotBlank()) {
                    Text(statusMessage, style = MaterialTheme.typography.bodySmall)
                }
                return@Column
            }

            val visibleEntries = entries.take(MAX_FEEDBACK_ITEMS_ON_SCREEN)
            val editedValues = remember(visibleEntries) {
                visibleEntries.associate { entry -> entry.id to mutableStateOf(entry.translated) }
            }

            visibleEntries.forEach { entry ->
                Text(
                    text = "Orijinal: ${entry.original.take(120)}",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = editedValues.getValue(entry.id).value,
                    onValueChange = { editedValues.getValue(entry.id).value = it },
                    label = { Text("Duzeltilmis Ceviri") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${entry.sourceLanguage} -> ${entry.targetLanguage} | Ton: ${entry.translationTone}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(onClick = {
                val updates = LinkedHashMap<String, String>()
                visibleEntries.forEach { entry ->
                    val updated = editedValues.getValue(entry.id).value.trim()
                    if (updated.isNotBlank() && updated != entry.translated) {
                        updates[entry.id] = updated
                    }
                }
                onSaveFeedbackEdits(updates)
            }) {
                Text("Duzeltmeleri Kaydet")
            }

            if (statusMessage.isNotBlank()) {
                Text(statusMessage, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DictionaryProfileCard(
    selectedDictionaryProfile: String,
    dictionaryProfileOptions: List<String>,
    dictionaryTransferText: String,
    onDictionaryProfileChanged: (String) -> Unit,
    onDictionaryTransferTextChanged: (String) -> Unit,
    onExportDictionary: () -> Unit,
    onImportDictionary: () -> Unit,
    onPasteDictionaryJson: () -> Unit,
    onClearCurrentProfileDictionary: () -> Unit
) {
    var profileInput by remember(selectedDictionaryProfile) { mutableStateOf(selectedDictionaryProfile) }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sozluk Profili Ayarlari", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (settingsExpanded) {
                            "Profil secimi ve JSON aktarim paneli acik."
                        } else {
                            "Aktif profil: $selectedDictionaryProfile"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = { settingsExpanded = !settingsExpanded }) {
                    Text(if (settingsExpanded) "Kapat" else "Ac")
                }
            }

            if (!settingsExpanded) {
                return@Column
            }

            DropdownSetting(
                label = "Aktif Profil",
                value = selectedDictionaryProfile,
                options = if (dictionaryProfileOptions.isEmpty()) {
                    listOf(selectedDictionaryProfile)
                } else {
                    dictionaryProfileOptions
                },
                onSelected = onDictionaryProfileChanged
            )

            OutlinedTextField(
                value = profileInput,
                onValueChange = { profileInput = it },
                label = { Text("Yeni / Ozel Profil Adi") },
                modifier = Modifier.fillMaxWidth()
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDictionaryProfileChanged(profileInput) }) {
                    Text("Profili Kaydet")
                }
                Button(onClick = onExportDictionary) {
                    Text("JSON Disa Aktar")
                }
                Button(onClick = onPasteDictionaryJson) {
                    Text("Panodan Yapistir")
                }
                Button(onClick = onImportDictionary) {
                    Text("JSON Ice Aktar")
                }
                TextButton(onClick = onClearCurrentProfileDictionary) {
                    Text("Profili Temizle")
                }
            }

            OutlinedTextField(
                value = dictionaryTransferText,
                onValueChange = onDictionaryTransferTextChanged,
                label = { Text("JSON Metni") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
        }
    }
}

@Composable
private fun DebugRecordsCard(records: List<TranslationDebugRecord>) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Debug Panel", style = MaterialTheme.typography.titleMedium)

            if (records.isEmpty()) {
                Text(
                    text = "Henuz debug kaydi yok.",
                    style = MaterialTheme.typography.bodySmall
                )
                return@Column
            }

            records.take(MAX_DEBUG_RECORDS_ON_SCREEN).forEach { record ->
                Text(
                    text = "Kaynak: ${record.sourceType} | Sebep: ${record.cacheReason} | Blok: ${record.blockCount} | Toplam: ${record.totalDurationMs} ms | Retry: ${if (record.modelRetryUsed) "evet" else "hayir"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Capture/OCR/Sozluk/Infer/Parse/Render: ${record.captureMs}/${record.ocrMs}/${record.dictionaryMs}/${record.inferenceMs}/${record.parseMs}/${record.renderMs} ms",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun MetricsCard(snapshot: TranslationMetricsSnapshot) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Performans Metrikleri", style = MaterialTheme.typography.titleMedium)
            Text("Toplam ceviri: ${snapshot.totalRequests}", style = MaterialTheme.typography.bodySmall)
            Text("Ortalama sure: ${snapshot.averageDurationMs} ms", style = MaterialTheme.typography.bodySmall)
            Text("Son ceviri suresi: ${snapshot.lastDurationMs} ms", style = MaterialTheme.typography.bodySmall)
            Text("Cache isabeti: %${snapshot.cacheHitRatePercent}", style = MaterialTheme.typography.bodySmall)
            Text("Sozlukten tam karsilanan: ${snapshot.dictionaryOnlyHits}", style = MaterialTheme.typography.bodySmall)
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
                    FilterChip(
                        selected = tone == selectedTone,
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

private const val MAX_FEEDBACK_ITEMS_ON_SCREEN = 3
private const val MAX_DEBUG_RECORDS_ON_SCREEN = 20
