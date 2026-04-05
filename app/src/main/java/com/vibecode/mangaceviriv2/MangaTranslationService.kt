package com.vibecode.mangaceviriv2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.Locale
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class MangaTranslationService : LifecycleService() {

    private lateinit var overlayManager: OverlayManager
    private var gemmaInferenceEngine: GemmaInferenceEngine? = null
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var latestBlocks: List<TranslationBlock> = emptyList()

    private lateinit var windowManager: WindowManager
    private var translationOverlayView: FrameLayout? = null
    private var translationOverlaySavedStateOwner: OverlaySavedStateOwner? = null
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var screenDensity: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private val projectionStarted = AtomicBoolean(false)
    private val translationInProgress = AtomicBoolean(false)
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var translationConfig = TranslationConfig()
    private lateinit var translationDictionary: TranslationDictionary
    private var overlayDismissJob: Job? = null
    private var modelUnloadJob: Job? = null
    private var currentStage: TranslationStage = TranslationStage.IDLE
    private var activeRequestStartedAtMs: Long = 0L
    private var blockedRequestCount: Int = 0

    @Volatile
    private var lastInferenceKey: String = ""

    @Volatile
    private var lastTranslatedBlocks: List<TranslationBlock> = emptyList()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundSafely()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        initDisplayMetrics()
        translationDictionary = TranslationDictionary(
            getSharedPreferences(TRANSLATION_DICTIONARY_PREFS, MODE_PRIVATE)
        )

        overlayManager = OverlayManager(this) { selection ->
            runTranslationFlow(selection)
        }

        overlayManager.setModelLoading("Ayarlar bekleniyor")

        overlayManager.show()
    }

    private fun startForegroundSafely() {
        val notification = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.e(TAG, "startForeground failed", it)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_START_PROJECTION) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (resultCode != Int.MIN_VALUE && resultData != null) {
                val config = TranslationConfig(
                    modelFileName = intent.getStringExtra(EXTRA_MODEL_FILE_NAME).orEmpty().ifBlank { DEFAULT_MODEL_FILE_NAME },
                    sourceLanguage = intent.getStringExtra(EXTRA_SOURCE_LANGUAGE).orEmpty().ifBlank { "English" },
                    targetLanguage = intent.getStringExtra(EXTRA_TARGET_LANGUAGE).orEmpty().ifBlank { "Turkish" },
                    translationTone = intent.getStringExtra(EXTRA_TRANSLATION_TONE).orEmpty().ifBlank { "Dogal" },
                    literalMode = intent.getBooleanExtra(EXTRA_LITERAL_TRANSLATION, false)
                )
                applyTranslationConfig(config)
                startProjection(resultCode, resultData)
            } else {
                overlayManager.setModelError("Ekran kaydi izni eksik")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopProjection()
        textRecognizer.close()
        overlayManager.hide()
        clearTranslationOverlay()
        modelUnloadJob?.cancel()
        modelUnloadJob = null
        gemmaInferenceEngine?.close()
        super.onDestroy()
    }

    private fun applyTranslationConfig(newConfig: TranslationConfig) {
        val modelChanged = translationConfig.modelFileName != newConfig.modelFileName
        translationConfig = newConfig
        val literalStatus = if (newConfig.literalMode) "Birebir" else "Standart"
        overlayManager.setOperationStatus("${newConfig.sourceLanguage} â†’ ${newConfig.targetLanguage} | Ton: ${newConfig.translationTone} | Mod: $literalStatus")

        if (modelChanged || gemmaInferenceEngine == null) {
            gemmaInferenceEngine?.close()
            gemmaInferenceEngine = GemmaInferenceEngine(this, newConfig.modelFileName)
        }

        modelUnloadJob?.cancel()
        modelUnloadJob = null
        overlayManager.setModelReady("Model beklemede: ${newConfig.modelFileName} (ilk istekte yuklenecek)")
    }

    private fun initDisplayMetrics() {
        val metrics: DisplayMetrics = resources.displayMetrics
        screenDensity = metrics.densityDpi
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        if (projectionStarted.get()) return

        runCatching {
            captureThread = HandlerThread("ocr-capture-thread").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)

            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "manga-ocr-capture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                captureHandler
            )

            projectionStarted.set(true)
            overlayManager.setOperationStatus("Gercek ekran yakalama aktif")
        }.onFailure {
            Log.e(TAG, "MediaProjection baslatilamadi", it)
            overlayManager.setModelError("Ekran yakalama baslatilamadi")
            stopProjection()
        }
    }

    private fun stopProjection() {
        projectionStarted.set(false)
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        runCatching { captureThread?.quitSafely() }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        captureHandler = null
        captureThread = null
    }

    private suspend fun captureSelection(selection: FrameSelection): Bitmap? {
        if (!projectionStarted.get()) {
            overlayManager.setModelError("MediaProjection aktif degil")
            return null
        }

        val reader = imageReader ?: return null
        val image = withContext(Dispatchers.IO) {
            delay(CAPTURE_SETTLE_DELAY_MS)
            var attempt = 0
            var latest: Image? = reader.acquireLatestImage()
            while (latest == null && attempt < CAPTURE_RETRY_COUNT) {
                delay(CAPTURE_RETRY_DELAY_MS)
                latest = reader.acquireLatestImage()
                attempt++
            }
            latest
        } ?: return null

        return image.useToSelectionBitmap(selection)
    }

    private fun Image.useToSelectionBitmap(selection: FrameSelection): Bitmap? {
        return runCatching {
            val plane = planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            buffer.rewind()
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val fullBitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            val cropLeft = selection.left.coerceIn(0, width - 1)
            val cropTop = selection.top.coerceIn(0, height - 1)
            val cropWidth = selection.width.coerceAtLeast(1).coerceAtMost(width - cropLeft)
            val cropHeight = selection.height.coerceAtLeast(1).coerceAtMost(height - cropTop)

            val selectionBitmap = Bitmap.createBitmap(fullBitmap, cropLeft, cropTop, cropWidth, cropHeight)
            if (selectionBitmap != fullBitmap) {
                fullBitmap.recycle()
            }
            selectionBitmap
        }.onFailure {
            Log.e(TAG, "Image to bitmap donusumu basarisiz", it)
        }.also {
            close()
        }.getOrNull()
    }

    private suspend fun extractOcrBlocks(bitmap: Bitmap): List<TranslationBlock> {
        val ocrInput = prepareBitmapForOcr(bitmap)
        return try {
            val inputImage = InputImage.fromBitmap(ocrInput.bitmap, 0)
            val visionText = awaitTask(textRecognizer.process(inputImage))

            val rawBlocks = visionText.textBlocks.flatMap { block ->
                if (block.lines.isEmpty()) {
                    listOfNotNull(block.toTranslationBlock(ocrInput.scaleX, ocrInput.scaleY))
                } else {
                    block.lines.mapNotNull { line ->
                        val rect = line.boundingBox ?: return@mapNotNull null
                        val normalizedText = normalizeOcrText(line.text)
                        if (normalizedText.isBlank() || !isUsefulOcrText(normalizedText)) return@mapNotNull null
                        scaledBlock(normalizedText, rect, ocrInput.scaleX, ocrInput.scaleY)
                    }
                }
            }

            mergeNearbyBlocks(rawBlocks)
        } finally {
            if (ocrInput.shouldRecycle) {
                ocrInput.bitmap.recycle()
            }
        }
    }

    private fun buildOcrPrompt(ocrBlocks: List<TranslationBlock>): String {
        val payload = JSONArray().apply {
            ocrBlocks.forEachIndexed { index, block ->
                put(
                    org.json.JSONObject().apply {
                        put("i", index)
                        put("o", block.original)
                        put("b", JSONArray(block.box))
                    }
                )
            }
        }.toString()

        return """
            SRC=${translationConfig.sourceLanguage}; TGT=${translationConfig.targetLanguage}; TONE=${translationConfig.translationTone}
            INPUT i=sira, o=metin, b=[x,y,w,h]
            BLOKLAR ayri satir parcasi olabilir; tum girdiyi birlikte okuyup baglami koru.
            CEVIRI kelime kelime degil, cumle anlami dogal kalacak sekilde olsun.
            ${if (translationConfig.literalMode) "ARGO, kufur ve +18 ifadeleri sansurleme; ton sertligini koru ve yildizlama yapma." else "Uygun olmayan ifadeleri dogal ve okunur bicimde yumusatabilirsin."}
            CIKTI SADECE JSON: [{"original":"...","translated":"...","box":[x,y,w,h]}]
            BOX AYNI KALSIN.
            OCR_INPUT=$payload
        """.trimIndent()
    }

    private fun parseTranslation(jsonText: String): List<TranslationBlock> {
        return runCatching {
            var cleanJson = jsonText
            val startIdx = jsonText.indexOf('[')
            val endIdx = jsonText.lastIndexOf(']')
            if (startIdx != -1 && endIdx != -1 && endIdx >= startIdx) {
                cleanJson = jsonText.substring(startIdx, endIdx + 1)
            }
            json.decodeFromString<List<TranslationBlock>>(cleanJson)
        }.onSuccess {
            Log.d(TAG, "JSON parse basarili, blok sayisi=${it.size}")
        }.onFailure {
            Log.e(TAG, "JSON parse basarisiz. Ham metin: $jsonText", it)
        }.getOrElse {
            emptyList()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Manga Translation",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Manga ceviri servisi")
            .setContentText("Yuzeyde ceviri icin hazir")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pi)
            .build()
    }

    private fun runTranslationFlow(selection: FrameSelection) {
        if (!translationInProgress.compareAndSet(false, true)) {
            blockedRequestCount += 1
            val elapsed = requestElapsedSeconds()
            overlayManager.setOperationStatus(
                "Mesgul: ${currentStage.label} adiminda devam ediyor (${elapsed}s). Yeni istek isleme alinmadi (tekrar: $blockedRequestCount)."
            )
            return
        }

        modelUnloadJob?.cancel()
        modelUnloadJob = null
        blockedRequestCount = 0
        activeRequestStartedAtMs = System.currentTimeMillis()

        pushStageStatus(
            stage = TranslationStage.CAPTURE,
            detail = "Adim 1/6: secilen alan yakalaniyor"
        )

        lifecycleScope.launch {
            var bitmap: Bitmap? = null
            try {
                bitmap = withContext(Dispatchers.Default) {
                    runCatching { captureSelection(selection) }
                        .onSuccess {
                            if (it != null) {
                                Log.d(TAG, "Bitmap basariyla kirpildi (Boyut: ${it.width}x${it.height})")
                            }
                        }
                        .onFailure {
                            Log.e(TAG, "captureSelection failed", it)
                        }
                        .getOrNull()
                }

                if (bitmap == null) {
                    overlayManager.setModelError("Adim 1/6 basarisiz: secim goruntusu alinamadi")
                    return@launch
                }

                pushStageStatus(
                    stage = TranslationStage.OCR,
                    detail = "Adim 2/6: OCR metni okunuyor"
                )
                val ocrBlocks = withContext(Dispatchers.Default) {
                    runCatching { extractOcrBlocks(bitmap) }
                        .onFailure { Log.e(TAG, "OCR hatasi", it) }
                        .getOrElse { emptyList() }
                }

                if (ocrBlocks.isEmpty()) {
                    clearTranslationOverlay()
                    overlayManager.setModelError("Adim 2/6: OCR metin bulamadi, farkli bir alan deneyin")
                    return@launch
                }

                val optimizedOcrBlocks = optimizeBlocksForInference(ocrBlocks)
                val contextualBlocks = buildContextualInferenceBlocks(optimizedOcrBlocks)
                val dictionaryBlocks = applyDictionaryTranslations(contextualBlocks)
                val dictionaryHitCount = dictionaryBlocks.count { it.translated.isNotBlank() }

                pushStageStatus(
                    stage = TranslationStage.DICTIONARY,
                    detail = "Adim 3/6: sozluk eslesmesi $dictionaryHitCount/${dictionaryBlocks.size}"
                )

                if (dictionaryBlocks.all { it.translated.isNotBlank() }) {
                    latestBlocks = dictionaryBlocks
                    pushStageStatus(
                        stage = TranslationStage.RENDER,
                        detail = "Adim 4/6: tum metinler sozlukten geldi, model cagrisi yok"
                    )
                    showTranslationOverlay(selection, dictionaryBlocks)
                    overlayManager.setModelReady("Ceviri tamamlandi (${requestElapsedSeconds()}s, sozluk)")
                    return@launch
                }

                val unknownBlocks = dictionaryBlocks.filter { it.translated.isBlank() }
                val inferenceKey = buildInferenceKey(unknownBlocks)
                if (inferenceKey == lastInferenceKey && lastTranslatedBlocks.isNotEmpty()) {
                    pushStageStatus(
                        stage = TranslationStage.CACHE,
                        detail = "Adim 4/6: onceki ceviri onbellegi kullanildi"
                    )
                    latestBlocks = lastTranslatedBlocks
                    showTranslationOverlay(selection, lastTranslatedBlocks)
                    overlayManager.setModelReady("Ceviri tamamlandi (${requestElapsedSeconds()}s, onbellek)")
                    return@launch
                }

                var canCacheInferenceResult = false
                pushStageStatus(
                    stage = TranslationStage.MODEL,
                    detail = "Adim 4/6: model cevirisi yapiliyor (${unknownBlocks.size} blok)"
                )

                var errorMessage: String? = null
                val translationJson = withContext(Dispatchers.IO) {
                    runCatching {
                        Log.d(TAG, "Gemma modeline girdi gonderildi")
                        ensureModelReady()
                        val engine = gemmaInferenceEngine ?: throw IllegalStateException("Model hazir degil")
                        engine.runTranslation(
                            visionDescription = buildOcrPrompt(unknownBlocks),
                            sourceLanguage = translationConfig.sourceLanguage,
                            targetLanguage = translationConfig.targetLanguage,
                            translationTone = translationConfig.translationTone
                        )
                    }.onFailure {
                        Log.e(TAG, "runTranslation failed", it)
                        errorMessage = it.message ?: it.toString()
                    }.getOrNull()
                }

                if (translationJson != null) {
                    Log.d(TAG, "Modelden ham yanit geldi: $translationJson")
                    pushStageStatus(
                        stage = TranslationStage.PARSE,
                        detail = "Adim 5/6: model yaniti ayrisiyor"
                    )
                } else {
                    overlayManager.setModelError(
                        "Adim 4/6 hata: model yanit vermedi (${errorMessage?.take(96) ?: "bilinmeyen hata"})"
                    )
                }

                val translatedBlocks = if (translationJson != null) {
                    val newlyTranslatedBlocks = reconcileTranslatedBlocks(unknownBlocks, parseTranslation(translationJson))
                    val hasMeaningfulModelOutput = newlyTranslatedBlocks.any { block ->
                        block.translated.isNotBlank() && !block.translated.equals(block.original, ignoreCase = true)
                    }
                    withContext(Dispatchers.IO) {
                        updateTranslationDictionary(newlyTranslatedBlocks)
                    }
                    canCacheInferenceResult = hasMeaningfulModelOutput
                    mergeDictionaryAndInferenceBlocks(dictionaryBlocks, newlyTranslatedBlocks)
                } else {
                    canCacheInferenceResult = false
                    dictionaryBlocks.map { block ->
                        if (block.translated.isNotBlank()) {
                            block
                        } else {
                            block.copy(translated = block.original)
                        }
                    }
                }

                latestBlocks = translatedBlocks

                if (translatedBlocks.isNotEmpty()) {
                    if (canCacheInferenceResult) {
                        lastInferenceKey = inferenceKey
                        lastTranslatedBlocks = translatedBlocks
                    } else {
                        lastInferenceKey = ""
                        lastTranslatedBlocks = emptyList()
                    }
                    pushStageStatus(
                        stage = TranslationStage.RENDER,
                        detail = "Adim 6/6: sonuc gosteriliyor (${translatedBlocks.size} blok)"
                    )
                    showTranslationOverlay(selection, translatedBlocks)
                    overlayManager.setModelReady("Ceviri tamamlandi (${requestElapsedSeconds()}s)")
                } else {
                    clearTranslationOverlay()
                    overlayManager.setModelError("Adim 6/6: ceviri sonucu bos dondu")
                }

                Log.d(TAG, "Parsed blocks: ${latestBlocks.size}")
            } finally {
                bitmap?.recycle()
                currentStage = TranslationStage.IDLE
                translationInProgress.set(false)
                scheduleModelUnloadIfIdle()
            }
        }
    }

    private fun pushStageStatus(stage: TranslationStage, detail: String) {
        currentStage = stage
        overlayManager.setModelRunning("${stage.label} | $detail")
    }

    private fun requestElapsedSeconds(): Long {
        if (activeRequestStartedAtMs <= 0L) return 0L
        return ((System.currentTimeMillis() - activeRequestStartedAtMs) / 1000L).coerceAtLeast(0L)
    }
    private suspend fun ensureModelReady() {
        val engine = gemmaInferenceEngine ?: throw IllegalStateException("Model secimi yapilmadi")
        if (engine.isReady()) return

        withContext(Dispatchers.Main) {
            overlayManager.setModelLoading("Model yukleniyor: ${translationConfig.modelFileName}")
        }
        engine.initialize()
        withContext(Dispatchers.Main) {
            overlayManager.setModelReady("Model hazir: ${translationConfig.modelFileName}")
        }
    }

    private fun scheduleModelUnloadIfIdle() {
        modelUnloadJob?.cancel()
        modelUnloadJob = lifecycleScope.launch {
            delay(MODEL_IDLE_UNLOAD_MS)
            if (translationInProgress.get()) return@launch

            withContext(Dispatchers.IO) {
                gemmaInferenceEngine?.close()
            }
            overlayManager.setOperationStatus("Model bosta oldugu icin gecici olarak kapatildi")
        }
    }

    private fun showTranslationOverlay(selection: FrameSelection, blocks: List<TranslationBlock>) {
        clearTranslationOverlay()
        val optimizedOverlayBlocks = optimizeBlocksForOverlay(blocks)

        val savedStateOwner = OverlaySavedStateOwner()
        val overlayContainer = FrameLayout(this).apply {
            visibility = View.VISIBLE
            alpha = 1f
            isClickable = true
        }
        val composeView = ComposeView(this).apply {
            visibility = View.VISIBLE
            alpha = 1f
            setContent {
                OverlayTranslationRenderer(
                    blocks = optimizedOverlayBlocks,
                    offsetX = selection.left,
                    offsetY = selection.top
                )
            }
        }
        overlayContainer.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Compose icin lifecycle ve savedState baglantisi
        overlayContainer.setViewTreeLifecycleOwner(this)
        overlayContainer.setViewTreeSavedStateRegistryOwner(savedStateOwner)
        composeView.setViewTreeSavedStateRegistryOwner(savedStateOwner)
        translationOverlaySavedStateOwner = savedStateOwner

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        overlayContainer.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                clearTranslationOverlay()
                overlayManager.setOperationStatus("Overlay dokunma ile kapatildi")
                return@setOnTouchListener true
            }
            false
        }

        runCatching {
            windowManager.addView(overlayContainer, params)
            translationOverlayView = overlayContainer
            scheduleOverlayDismiss()
        }.onFailure {
            Log.e(TAG, "translation overlay add failed", it)
            overlayManager.setModelError("Overlay gosterilemedi")
        }
    }

    private fun optimizeBlocksForOverlay(blocks: List<TranslationBlock>): List<TranslationBlock> {
        if (blocks.size <= 1) return blocks

        val sorted = blocks.sortedWith(compareBy({ it.box.getOrElse(1) { 0 } }, { it.box.getOrElse(0) { 0 } }))
        val merged = mutableListOf<TranslationBlock>()

        sorted.forEach { candidate ->
            val targetIndex = merged.indexOfLast { existing -> shouldMergeForOverlay(existing, candidate) }
            if (targetIndex == -1) {
                merged.add(candidate)
            } else {
                merged[targetIndex] = mergeOverlayBlocks(merged[targetIndex], candidate)
            }
        }

        return merged
    }

    private fun shouldMergeForOverlay(left: TranslationBlock, right: TranslationBlock): Boolean {
        val leftRect = left.toRect()
        val rightRect = right.toRect()
        val maxGap = OVERLAY_MERGE_MAX_GAP_PX

        val horizontalGap = when {
            rightRect.left > leftRect.right -> rightRect.left - leftRect.right
            leftRect.left > rightRect.right -> leftRect.left - rightRect.right
            else -> 0
        }
        val verticalGap = when {
            rightRect.top > leftRect.bottom -> rightRect.top - leftRect.bottom
            leftRect.top > rightRect.bottom -> leftRect.top - rightRect.bottom
            else -> 0
        }

        val horizontalOverlap = (minOf(leftRect.right, rightRect.right) - maxOf(leftRect.left, rightRect.left)).coerceAtLeast(0)
        val minWidth = minOf(leftRect.width(), rightRect.width()).coerceAtLeast(1)
        val overlapRatio = horizontalOverlap.toFloat() / minWidth.toFloat()
        val closeEnough = horizontalGap <= maxGap && verticalGap <= maxGap
        val stackedClose = overlapRatio >= OVERLAY_MERGE_MIN_OVERLAP_RATIO && verticalGap <= maxGap * 2

        if (!closeEnough && !stackedClose) return false
        return semanticSimilarity(left, right) >= OVERLAY_MERGE_MIN_SIMILARITY
    }

    private fun semanticSimilarity(left: TranslationBlock, right: TranslationBlock): Float {
        val leftText = normalizeOverlaySemanticText(left.translated.ifBlank { left.original })
        val rightText = normalizeOverlaySemanticText(right.translated.ifBlank { right.original })
        if (leftText.isBlank() || rightText.isBlank()) return 0f
        if (leftText == rightText) return 1f
        if (leftText.contains(rightText) || rightText.contains(leftText)) return 0.95f

        val leftTokens = leftText.split(' ').filter { it.length >= 2 }.toSet()
        val rightTokens = rightText.split(' ').filter { it.length >= 2 }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0f

        val intersection = leftTokens.intersect(rightTokens).size.toFloat()
        val union = leftTokens.union(rightTokens).size.toFloat().coerceAtLeast(1f)
        return intersection / union
    }

    private fun normalizeOverlaySemanticText(text: String): String {
        return text
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun mergeOverlayBlocks(left: TranslationBlock, right: TranslationBlock): TranslationBlock {
        val leftRect = left.toRect()
        val rightRect = right.toRect()
        val mergedRect = Rect(
            minOf(leftRect.left, rightRect.left),
            minOf(leftRect.top, rightRect.top),
            maxOf(leftRect.right, rightRect.right),
            maxOf(leftRect.bottom, rightRect.bottom)
        )

        val mergedOriginal = listOf(left.original.trim(), right.original.trim())
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")

        val mergedTranslated = listOf(
            left.translated.ifBlank { left.original }.trim(),
            right.translated.ifBlank { right.original }.trim()
        )
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")

        return TranslationBlock(
            original = mergedOriginal,
            translated = mergedTranslated,
            box = listOf(
                mergedRect.left,
                mergedRect.top,
                mergedRect.width(),
                mergedRect.height()
            )
        )
    }

    private fun clearTranslationOverlay() {
        overlayDismissJob?.cancel()
        overlayDismissJob = null
        translationOverlaySavedStateOwner?.destroy()
        translationOverlaySavedStateOwner = null
        translationOverlayView?.let {
            runCatching { windowManager.removeView(it) }
        }
        translationOverlayView = null
    }

    private fun scheduleOverlayDismiss() {
        overlayDismissJob?.cancel()
        overlayDismissJob = lifecycleScope.launch {
            delay(OVERLAY_VISIBLE_MS)
            clearTranslationOverlay()
            overlayManager.setOperationStatus("Overlay 20 saniye sonra kapatildi")
        }
    }

    private fun optimizeBlocksForInference(ocrBlocks: List<TranslationBlock>): List<TranslationBlock> {
        if (ocrBlocks.isEmpty()) return emptyList()

        return ocrBlocks
            .asSequence()
            .map { block ->
                block.copy(original = normalizeOcrText(block.original).take(MAX_TEXT_PER_BLOCK))
            }
            .filter { it.original.isNotBlank() }
            .sortedWith(compareBy({ it.box.getOrElse(1) { 0 } }, { it.box.getOrElse(0) { 0 } }))
            .take(MAX_BLOCK_COUNT_FOR_INFERENCE)
            .toList()
    }

    private fun buildContextualInferenceBlocks(blocks: List<TranslationBlock>): List<TranslationBlock> {
        if (blocks.size <= 1) return blocks

        val sorted = blocks.sortedWith(compareBy({ it.box.getOrElse(1) { 0 } }, { it.box.getOrElse(0) { 0 } }))
        val merged = mutableListOf<TranslationBlock>()

        sorted.forEach { candidate ->
            val previous = merged.lastOrNull()
            if (previous != null && shouldMergeForInferenceContext(previous, candidate)) {
                merged[merged.lastIndex] = mergeInferenceContextBlocks(previous, candidate)
            } else {
                merged.add(candidate)
            }
        }

        return merged.take(MAX_BLOCK_COUNT_FOR_INFERENCE)
    }

    private fun shouldMergeForInferenceContext(left: TranslationBlock, right: TranslationBlock): Boolean {
        val leftText = normalizeOcrText(left.original)
        if (leftText.endsWith(".") || leftText.endsWith("!") || leftText.endsWith("?")) {
            return false
        }

        val leftRect = left.toRect()
        val rightRect = right.toRect()
        val verticalGap = rightRect.top - leftRect.bottom
        val centerYDiff = kotlin.math.abs(leftRect.centerY() - rightRect.centerY())
        val avgHeight = ((leftRect.height() + rightRect.height()) / 2).coerceAtLeast(1)

        val horizontalGap = when {
            rightRect.left > leftRect.right -> rightRect.left - leftRect.right
            leftRect.left > rightRect.right -> leftRect.left - rightRect.right
            else -> 0
        }

        val horizontalOverlap = (minOf(leftRect.right, rightRect.right) - maxOf(leftRect.left, rightRect.left)).coerceAtLeast(0)
        val minWidth = minOf(leftRect.width(), rightRect.width()).coerceAtLeast(1)
        val overlapRatio = horizontalOverlap.toFloat() / minWidth.toFloat()

        val stackedLine = verticalGap in -8..CONTEXT_VERTICAL_GAP_MAX && overlapRatio >= CONTEXT_MIN_OVERLAP_RATIO
        val sameLine = centerYDiff <= (avgHeight / 2) && horizontalGap in -8..CONTEXT_HORIZONTAL_GAP_MAX
        if (!stackedLine && !sameLine) return false

        val leftWords = leftText.split(' ').count { it.isNotBlank() }
        val rightWords = normalizeOcrText(right.original).split(' ').count { it.isNotBlank() }
        val lexicalSimilarity = semanticSimilarityByOriginal(left.original, right.original)

        return lexicalSimilarity >= CONTEXT_MIN_SIMILARITY || leftWords <= 4 || rightWords <= 4
    }

    private fun semanticSimilarityByOriginal(left: String, right: String): Float {
        val leftNorm = normalizeOverlaySemanticText(left)
        val rightNorm = normalizeOverlaySemanticText(right)
        if (leftNorm.isBlank() || rightNorm.isBlank()) return 0f
        if (leftNorm == rightNorm) return 1f

        val leftTokens = leftNorm.split(' ').filter { it.length >= 2 }.toSet()
        val rightTokens = rightNorm.split(' ').filter { it.length >= 2 }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0f

        val intersection = leftTokens.intersect(rightTokens).size.toFloat()
        val union = leftTokens.union(rightTokens).size.toFloat().coerceAtLeast(1f)
        return intersection / union
    }

    private fun mergeInferenceContextBlocks(left: TranslationBlock, right: TranslationBlock): TranslationBlock {
        val leftRect = left.toRect()
        val rightRect = right.toRect()
        val mergedRect = Rect(
            minOf(leftRect.left, rightRect.left),
            minOf(leftRect.top, rightRect.top),
            maxOf(leftRect.right, rightRect.right),
            maxOf(leftRect.bottom, rightRect.bottom)
        )

        val mergedOriginal = listOf(left.original.trim(), right.original.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .take(MAX_TEXT_PER_BLOCK * 2)

        return TranslationBlock(
            original = mergedOriginal,
            translated = "",
            box = listOf(
                mergedRect.left,
                mergedRect.top,
                mergedRect.width(),
                mergedRect.height()
            )
        )
    }

    private fun applyDictionaryTranslations(ocrBlocks: List<TranslationBlock>): List<TranslationBlock> {
        return ocrBlocks.map { block ->
            val cached = translationDictionary.get(dictionaryKey(block.original))
            if (!cached.isNullOrBlank()) {
                block.copy(translated = cached)
            } else {
                block
            }
        }
    }

    private fun mergeDictionaryAndInferenceBlocks(
        dictionaryBlocks: List<TranslationBlock>,
        newlyTranslatedBlocks: List<TranslationBlock>
    ): List<TranslationBlock> {
        if (dictionaryBlocks.isEmpty()) return emptyList()

        val translatedMap = newlyTranslatedBlocks.associateBy(
            keySelector = { dictionaryKey(it.original) },
            valueTransform = { it.translated }
        )

        return dictionaryBlocks.map { block ->
            if (block.translated.isNotBlank()) {
                block
            } else {
                val fresh = translatedMap[dictionaryKey(block.original)]
                block.copy(translated = fresh?.takeIf { it.isNotBlank() } ?: block.original)
            }
        }
    }

    private fun updateTranslationDictionary(blocks: List<TranslationBlock>) {
        if (blocks.isEmpty()) return
        val newEntries = blocks.mapNotNull { block ->
            val translated = block.translated.trim()
            if (translated.isBlank()) {
                null
            } else {
                dictionaryKey(block.original) to translated
            }
        }.toMap()

        if (newEntries.isNotEmpty()) {
            translationDictionary.putAll(newEntries)
        }
    }

    private fun dictionaryKey(text: String): String {
        val normalized = normalizeOcrText(text).lowercase(Locale.ROOT)
        val source = translationConfig.sourceLanguage.lowercase(Locale.ROOT)
        val target = translationConfig.targetLanguage.lowercase(Locale.ROOT)
        val tone = translationConfig.translationTone.lowercase(Locale.ROOT)
        val literal = if (translationConfig.literalMode) "literal" else "standard"
        return "$source|$target|$tone|$literal|$normalized"
    }

    private fun buildInferenceKey(ocrBlocks: List<TranslationBlock>): String {
        val configPart = "${translationConfig.modelFileName}|${translationConfig.sourceLanguage}|${translationConfig.targetLanguage}|${translationConfig.translationTone}"
        val blocksPart = ocrBlocks.joinToString("|") { block ->
            "${block.original}#${block.box.joinToString(",")}"
        }
        return sha256Hex("$configPart::$blocksPart")
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                append((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
            }
        }
    }

    private fun reconcileTranslatedBlocks(
        sourceBlocks: List<TranslationBlock>,
        translatedBlocks: List<TranslationBlock>
    ): List<TranslationBlock> {
        if (sourceBlocks.isEmpty()) return emptyList()
        if (translatedBlocks.isEmpty()) return sourceBlocks

        return sourceBlocks.mapIndexed { index, source ->
            val translated = translatedBlocks.getOrNull(index)
            val text = translated?.translated?.takeIf { it.isNotBlank() } ?: source.original
            val box = translated?.box?.takeIf { it.size == 4 } ?: source.box
            TranslationBlock(
                original = source.original,
                translated = text,
                box = box
            )
        }
    }

    private fun prepareBitmapForOcr(source: Bitmap): OcrInput {
        val maxEdge = maxOf(source.width, source.height)
        if (maxEdge <= OCR_MAX_EDGE) {
            return OcrInput(source, 1f, 1f, false)
        }

        val ratio = OCR_MAX_EDGE.toFloat() / maxEdge.toFloat()
        val targetW = (source.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (source.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true)
        return OcrInput(
            bitmap = scaled,
            scaleX = source.width.toFloat() / targetW.toFloat(),
            scaleY = source.height.toFloat() / targetH.toFloat(),
            shouldRecycle = true
        )
    }

    private fun mergeNearbyBlocks(blocks: List<TranslationBlock>): List<TranslationBlock> {
        if (blocks.isEmpty()) return emptyList()
        val sorted = blocks.sortedWith(compareBy({ it.box.getOrElse(1) { 0 } }, { it.box.getOrElse(0) { 0 } }))
        val merged = ArrayList<TranslationBlock>(sorted.size)

        for (block in sorted) {
            val previous = merged.lastOrNull()
            if (previous != null && canMerge(previous, block)) {
                merged[merged.lastIndex] = mergeBlocks(previous, block)
            } else {
                merged.add(block)
            }
        }
        return merged
    }

    private fun canMerge(left: TranslationBlock, right: TranslationBlock): Boolean {
        val l = left.toRect()
        val r = right.toRect()
        val verticalCenterDiff = kotlin.math.abs(l.centerY() - r.centerY())
        val averageHeight = (l.height() + r.height()) / 2
        val gap = r.left - l.right

        return verticalCenterDiff <= averageHeight / 2 && gap in -16..48
    }

    private fun mergeBlocks(left: TranslationBlock, right: TranslationBlock): TranslationBlock {
        val l = left.toRect()
        val r = right.toRect()
        val mergedRect = Rect(
            minOf(l.left, r.left),
            minOf(l.top, r.top),
            maxOf(l.right, r.right),
            maxOf(l.bottom, r.bottom)
        )

        return TranslationBlock(
            original = "${left.original} ${right.original}".trim(),
            translated = "",
            box = listOf(
                mergedRect.left,
                mergedRect.top,
                mergedRect.width(),
                mergedRect.height()
            )
        )
    }

    private fun normalizeOcrText(text: String): String {
        return text
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isUsefulOcrText(text: String): Boolean {
        if (text.length < MIN_OCR_TEXT_LENGTH) return false
        val letterOrDigitCount = text.count { it.isLetterOrDigit() }
        return letterOrDigitCount >= MIN_OCR_ALNUM_COUNT
    }

    private fun com.google.mlkit.vision.text.Text.TextBlock.toTranslationBlock(
        scaleX: Float,
        scaleY: Float
    ): TranslationBlock? {
        val rect = boundingBox ?: return null
        val normalizedText = normalizeOcrText(text)
        if (normalizedText.isBlank() || !isUsefulOcrText(normalizedText)) return null
        return scaledBlock(normalizedText, rect, scaleX, scaleY)
    }

    private fun scaledBlock(text: String, rect: Rect, scaleX: Float, scaleY: Float): TranslationBlock {
        val scaledLeft = (rect.left * scaleX).toInt().coerceAtLeast(0)
        val scaledTop = (rect.top * scaleY).toInt().coerceAtLeast(0)
        val scaledWidth = (rect.width() * scaleX).toInt().coerceAtLeast(1)
        val scaledHeight = (rect.height() * scaleY).toInt().coerceAtLeast(1)
        return TranslationBlock(
            original = text,
            translated = "",
            box = listOf(scaledLeft, scaledTop, scaledWidth, scaledHeight)
        )
    }

    private class OverlaySavedStateOwner : SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)

        init {
            controller.performAttach()
            controller.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = controller.savedStateRegistry

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }

    companion object {
        private const val TAG = "MangaTranslationService"
        private const val CHANNEL_ID = "manga_translate_service"
        private const val NOTIFICATION_ID = 1201
        private const val DEFAULT_MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val OVERLAY_VISIBLE_MS = 20_000L
        private const val CAPTURE_RETRY_COUNT = 3
        private const val CAPTURE_RETRY_DELAY_MS = 45L
        private const val CAPTURE_SETTLE_DELAY_MS = 120L
        private const val OCR_MAX_EDGE = 1280
        private const val MAX_BLOCK_COUNT_FOR_INFERENCE = 24
        private const val MAX_TEXT_PER_BLOCK = 100
        private const val MIN_OCR_TEXT_LENGTH = 2
        private const val MIN_OCR_ALNUM_COUNT = 2
        private const val MODEL_IDLE_UNLOAD_MS = 90_000L
        private const val TRANSLATION_DICTIONARY_PREFS = "translation_dictionary_prefs"
        private const val OVERLAY_MERGE_MAX_GAP_PX = 48
        private const val OVERLAY_MERGE_MIN_OVERLAP_RATIO = 0.25f
        private const val OVERLAY_MERGE_MIN_SIMILARITY = 0.34f
        private const val CONTEXT_VERTICAL_GAP_MAX = 56
        private const val CONTEXT_HORIZONTAL_GAP_MAX = 40
        private const val CONTEXT_MIN_OVERLAP_RATIO = 0.22f
        private const val CONTEXT_MIN_SIMILARITY = 0.14f

        const val ACTION_START_PROJECTION = "com.vibecode.mangaceviriv2.action.START_PROJECTION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_MODEL_FILE_NAME = "extra_model_file_name"
        const val EXTRA_SOURCE_LANGUAGE = "extra_source_language"
        const val EXTRA_TARGET_LANGUAGE = "extra_target_language"
        const val EXTRA_TRANSLATION_TONE = "extra_translation_tone"
        const val EXTRA_LITERAL_TRANSLATION = "extra_literal_translation"
    }
}

private enum class TranslationStage(val label: String) {
    IDLE("Beklemede"),
    CAPTURE("Yakalama"),
    OCR("OCR"),
    DICTIONARY("Sozluk"),
    CACHE("Onbellek"),
    MODEL("Model"),
    PARSE("Ayrisma"),
    RENDER("Overlay")
}

private data class OcrInput(
    val bitmap: Bitmap,
    val scaleX: Float,
    val scaleY: Float,
    val shouldRecycle: Boolean
)

private data class TranslationConfig(
    val modelFileName: String = "gemma-4-E2B-it.litertlm",
    val sourceLanguage: String = "English",
    val targetLanguage: String = "Turkish",
    val translationTone: String = "Dogal",
    val literalMode: Boolean = false
)

private class TranslationDictionary(private val prefs: SharedPreferences) {
    private val map = LinkedHashMap<String, String>()

    init {
        load()
    }

    fun get(key: String): String? = map[key]

    fun putAll(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        var changed = false
        entries.forEach { (key, value) ->
            val cleanValue = value.trim()
            if (cleanValue.isBlank()) return@forEach
            if (map[key] != cleanValue) {
                map[key] = cleanValue
                changed = true
            }
        }

        if (!changed) return

        while (map.size > MAX_ENTRIES) {
            val oldest = map.entries.firstOrNull()?.key ?: break
            map.remove(oldest)
        }
        persist()
    }

    private fun load() {
        val raw = prefs.getString(KEY_DICTIONARY, null) ?: return
        runCatching {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optString(key)
                if (value.isNotBlank()) {
                    map[key] = value
                }
            }
        }
    }

    private fun persist() {
        val obj = JSONObject()
        map.forEach { (key, value) -> obj.put(key, value) }
        prefs.edit().putString(KEY_DICTIONARY, obj.toString()).apply()
    }

    companion object {
        private const val KEY_DICTIONARY = "entries"
        private const val MAX_ENTRIES = 4000
    }
}

private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { cont ->
    task.addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }.addOnFailureListener { exception ->
        if (cont.isActive) cont.cancel(exception)
    }
}

