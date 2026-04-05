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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
            feedbackStatusMessage.value = "Ekran kaydı izni bu oturumda otomatik kullanıldı"
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
            "$applied düzeltme kaydedildi. Sonraki çevirilerde kullanılacak."
        } else {
            "Kaydedilecek değişiklik bulunamadı."
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
        feedbackStatusMessage.value = "Profil JSON dışa aktarıldı ve panoya kopyalandı"
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
            feedbackStatusMessage.value = "Panoda yapıştırılacak JSON bulunamadı"
            return
        }
        dictionaryTransferText.value = text
        feedbackStatusMessage.value = "Pano JSON metni alındı"
    }

    private fun clearCurrentProfileDictionary() {
        val removed = TranslationFeedbackStore.clearProfileDictionary(
            context = this,
            profileName = selectedDictionaryProfile.value
        )
        feedbackStatusMessage.value = "$removed kayıt temizlendi"
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
            "Doğal",
            "Kısa",
            "Resmi",
            "Samimi",
            "Akıcı",
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
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF02222D),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF03231B),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF05231B),
    background = Color(0xFF02090B),
    onBackground = Color(0xFFE9F8FC),
    surface = Color(0xFF061217),
    onSurface = Color(0xFFE9F8FC),
    surfaceVariant = Color(0xFF0C1D25),
    onSurfaceVariant = Color(0xFF9EC6D3),
    outline = Color(0xFF2D4F5C)
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
    val canStartService = hasOverlayPermission && hasModelAsset && !started

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CompactHeader(
            hasOverlayPermission = hasOverlayPermission,
            hasModelAsset = hasModelAsset,
            captureStepCompleted = captureStepCompleted,
            started = started,
            selectedSource = selectedSource,
            selectedTarget = selectedTarget,
            selectedTone = selectedTone
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = onStart,
                enabled = canStartService
            ) {
                Text(if (started) "Servis Çalışıyor" else "Çeviriyi Başlat")
            }

            if (started) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onStop
                ) {
                    Text("Durdur")
                }
            }
        }

        CompactSetupCard(
            hasOverlayPermission = hasOverlayPermission,
            hasModelAsset = hasModelAsset,
            captureStepCompleted = captureStepCompleted,
            onRequestOverlay = onRequestOverlay,
            onStartCapture = onStart
        )

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactHeader(
    hasOverlayPermission: Boolean,
    hasModelAsset: Boolean,
    captureStepCompleted: Boolean,
    started: Boolean,
    selectedSource: String,
    selectedTarget: String,
    selectedTone: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Manga Çeviri",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Dil: $selectedSource → $selectedTarget  •  Ton: $selectedTone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill("Servis", started)
                StatusPill("Overlay", hasOverlayPermission)
                StatusPill("Model", hasModelAsset)
                StatusPill("Capture", captureStepCompleted)
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (active) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            }
        )
    ) {
        Text(
            text = "$label: ${if (active) "Hazır" else "Eksik"}",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CompactSetupCard(
    hasOverlayPermission: Boolean,
    hasModelAsset: Boolean,
    captureStepCompleted: Boolean,
    onRequestOverlay: () -> Unit,
    onStartCapture: () -> Unit
) {
    val totalSteps = 3
    val completedSteps = listOf(hasOverlayPermission, hasModelAsset, captureStepCompleted).count { it }
    val missing = buildList {
        if (!hasOverlayPermission) add("Overlay izni")
        if (!hasModelAsset) add("Model dosyası")
        if (!captureStepCompleted) add("Ekran yakalama izni")
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Kurulum Sihirbazı",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "$completedSteps/$totalSteps adım tamamlandı",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!hasModelAsset) {
                Text(
                    text = "Assets altında .litertlm veya .task model dosyası bulunamadı.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (missing.isEmpty()) {
                Text(
                    text = "Kurulum tamamlandı. İstersen bu paneli görmezden gelip ayarlardan devam edebilirsin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "Eksik adımlar: ${missing.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!hasOverlayPermission) {
                        OutlinedButton(onClick = onRequestOverlay) {
                            Text("Overlay izni")
                        }
                    }
                    if (hasOverlayPermission && hasModelAsset && !captureStepCompleted) {
                        Button(onClick = onStartCapture) {
                            Text("Ekran iznini tamamla")
                        }
                    }
                }
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
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Son Çeviri Düzeltmeleri", style = MaterialTheme.typography.titleMedium)

            if (entries.isEmpty()) {
                Text(
                    text = "Henüz düzeltme için kayıtlı çeviri yok. Bir çeviri yapıldıktan sonra burada düzenlenebilir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (statusMessage.isNotBlank()) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            val visibleEntries = entries.take(MAX_FEEDBACK_ITEMS_ON_SCREEN)
            val editedValues = remember(visibleEntries) {
                visibleEntries.associate { entry -> entry.id to mutableStateOf(entry.translated) }
            }

            visibleEntries.forEachIndexed { index, entry ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF050E13)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Kaynak Metin",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = entry.original.take(140),
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = editedValues.getValue(entry.id).value,
                            onValueChange = { editedValues.getValue(entry.id).value = it },
                            label = { Text("Düzeltilmiş Çeviri") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedLabelColor = MaterialTheme.colorScheme.primary,
                                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                                focusedContainerColor = Color(0xFF050E13),
                                unfocusedContainerColor = Color(0xFF050E13),
                                cursorColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${entry.sourceLanguage} -> ${entry.targetLanguage} | Ton: ${entry.translationTone}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (index != visibleEntries.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                val updates = LinkedHashMap<String, String>()
                visibleEntries.forEach { entry ->
                    val updated = editedValues.getValue(entry.id).value.trim()
                    if (updated.isNotBlank() && updated != entry.translated) {
                        updates[entry.id] = updated
                    }
                }
                onSaveFeedbackEdits(updates)
            }) {
                Text("Düzeltmeleri Kaydet")
            }

            if (statusMessage.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF031019),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
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
                    Text("Sözlük Profili Ayarları", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (settingsExpanded) {
                            "Profil seçimi ve JSON aktarım paneli açık."
                        } else {
                            "Aktif profil: $selectedDictionaryProfile"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = { settingsExpanded = !settingsExpanded }) {
                    Text(if (settingsExpanded) "Kapat" else "Aç")
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
                label = { Text("Yeni / Özel Profil Adı") },
                modifier = Modifier.fillMaxWidth()
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDictionaryProfileChanged(profileInput) }) {
                    Text("Profili Kaydet")
                }
                Button(onClick = onExportDictionary) {
                    Text("JSON Dışa Aktar")
                }
                Button(onClick = onPasteDictionaryJson) {
                    Text("Panodan Yapıştır")
                }
                Button(onClick = onImportDictionary) {
                    Text("JSON İçe Aktar")
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
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Debug Panel", style = MaterialTheme.typography.titleMedium)

            if (records.isEmpty()) {
                Text(
                    text = "Henüz debug kaydı yok.",
                    style = MaterialTheme.typography.bodySmall
                )
                return@Column
            }

            records.take(MAX_DEBUG_RECORDS_ON_SCREEN).forEach { record ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF050E13),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Kaynak: ${record.sourceType} | Sebep: ${record.cacheReason}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Blok: ${record.blockCount} | Toplam: ${record.totalDurationMs} ms | Retry: ${if (record.modelRetryUsed) "evet" else "hayır"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Capture/OCR/Sözlük/Infer/Parse/Render: ${record.captureMs}/${record.ocrMs}/${record.dictionaryMs}/${record.inferenceMs}/${record.parseMs}/${record.renderMs} ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricsCard(snapshot: TranslationMetricsSnapshot) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Performans Metrikleri", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = "Toplam Çeviri",
                    value = snapshot.totalRequests.toString()
                )
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = "Ortalama Süre",
                    value = "${snapshot.averageDurationMs} ms"
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = "Son Süre",
                    value = "${snapshot.lastDurationMs} ms"
                )
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = "Cache İsabeti",
                    value = "%${snapshot.cacheHitRatePercent}"
                )
            }
            MetricCell(
                modifier = Modifier.fillMaxWidth(),
                label = "Sözlükten Tam Karşılanan",
                value = snapshot.dictionaryOnlyHits.toString()
            )
        }
    }
}

@Composable
private fun MetricCell(
    modifier: Modifier,
    label: String,
    value: String
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
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
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Çeviri Ayarları", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Model, dil ve ton ayarları canlı çeviri sırasında kullanılır.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            DropdownSetting(
                label = "Model",
                value = selectedModel,
                options = modelOptions,
                onSelected = onModelSelected,
                amoled = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    DropdownSetting(
                        label = "Kaynak Dil",
                        value = selectedSource,
                        options = sourceOptions,
                        onSelected = onSourceSelected,
                        amoled = true
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    DropdownSetting(
                        label = "Hedef Dil",
                        value = selectedTarget,
                        options = targetOptions,
                        onSelected = onTargetSelected,
                        amoled = true
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))

            Text("Ton", style = MaterialTheme.typography.bodyMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                toneOptions.forEach { tone ->
                    val selected = tone == selectedTone
                    FilterChip(
                        selected = selected,
                        onClick = { onToneSelected(tone) },
                        label = { Text(tone) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                            containerColor = Color(0xFF051017),
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                    )
                }
            }
            Text(
                text = "Seçili ton: $selectedTone",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF030B10),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sansürsüz/Birebir Çeviri", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Açık olduğunda model metni yumuşatmadan çevirir (uygunsa).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = literalMode,
                        onCheckedChange = onLiteralModeChanged,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                            checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = Color(0xFF08131A),
                            uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                        )
                    )
                }
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
    onSelected: (String) -> Unit,
    amoled: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val safeOptions = if (options.isEmpty()) listOf(value) else options
    val fieldColors = if (amoled) {
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
            focusedContainerColor = Color(0xFF050E13),
            unfocusedContainerColor = Color(0xFF050E13),
            cursorColor = MaterialTheme.colorScheme.primary
        )
    } else {
        OutlinedTextFieldDefaults.colors()
    }

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
            colors = fieldColors,
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
            safeOptions.forEach { option ->
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
