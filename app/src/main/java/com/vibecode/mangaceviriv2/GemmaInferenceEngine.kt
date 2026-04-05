package com.vibecode.mangaceviriv2

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.io.FileOutputStream

class GemmaInferenceEngine(
    private val context: Context,
    private val modelPath: String = "gemma-4-E2B-it.litertlm"
) : AutoCloseable {

    @Volatile
    private var initialized = false
    private var initializationError: Exception? = null
    private var engine: Engine? = null
    @Volatile
    private var lastPromptKey: String = ""
    @Volatile
    private var lastResponse: String? = null
    @Volatile
    private var lastRunUsedRetry: Boolean = false

    fun isReady(): Boolean = initialized
    fun wasLastRunRetried(): Boolean = lastRunUsedRetry

    fun initialize() {
        if (initialized) return

        runCatching {
            Log.d(TAG, "LlmInference baslatiliyor...")

            val modelFile = File(context.filesDir, modelPath)

            val assetLength = getAssetLength(modelPath)
            val needsCopy = !modelFile.exists() || (assetLength > 0 && modelFile.length() != assetLength)

            if (needsCopy) {
                if (modelFile.exists()) modelFile.delete()

                Log.d(TAG, "Model dosyasi cihaza kopyalaniyor: $modelPath")
                context.assets.open(modelPath).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Model kopyalama tamamlandi: ${modelFile.absolutePath} (Buyukluk: ${modelFile.length()} bayt)")
            } else {
                Log.d(TAG, "Model dosyasi zaten mevcut ve boyutu gecerli: ${modelFile.absolutePath}")
            }

            if (!modelFile.exists() || modelFile.length() <= 0L) {
                throw IllegalStateException("Model dosyasi bulunamadi veya bos: $modelPath")
            }

            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                maxNumTokens = MAX_CONTEXT_TOKENS,
                cacheDir = context.cacheDir.absolutePath
            )

            engine = Engine(engineConfig)
            engine?.initialize()
            initialized = true
            initializationError = null
            Log.d(TAG, "LiteRT-LM engine basariyla olusturuldu.")
        }.onFailure {
            val details = "model=$modelPath"
            val message = "LlmInference baslatma hatasi ($details): ${it.message}"
            val error = if (it is Exception) IllegalStateException(message, it) else IllegalStateException(message, Exception(it))
            initializationError = error
            Log.e(TAG, message, it)
            throw error // Serviste hatanin UI'a yansimasi icin Exception'i firlatiyoruz
        }
    }

    private fun getAssetLength(assetPath: String): Long {
        return runCatching {
            context.assets.openFd(assetPath).use(AssetFileDescriptor::close)
            context.assets.openFd(assetPath).length
        }.getOrElse {
            -1L
        }
    }

    @Synchronized
    fun runTranslation(
        visionDescription: String,
        sourceLanguage: String,
        targetLanguage: String,
        translationTone: String,
        outputTokenLimit: Int = DEFAULT_OUTPUT_TOKENS,
        maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS
    ): String {
        if (!initialized) {
            throw IllegalStateException("Gemma baslatilamadi: ${initializationError?.message}")
        }
        lastRunUsedRetry = false

        val safeOutputTokenLimit = outputTokenLimit.coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)
        val safeInputChars = maxInputChars.coerceIn(MIN_INPUT_CHARS, MAX_INPUT_CHARS)
        val retryInputChars = (safeInputChars * RETRY_INPUT_RATIO).toInt().coerceAtLeast(MIN_RETRY_INPUT_CHARS)
        val retryOutputTokens = (safeOutputTokenLimit * RETRY_OUTPUT_RATIO).toInt().coerceAtLeast(MIN_OUTPUT_TOKENS)

        val compactInput = visionDescription
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(safeInputChars)
        val userPrompt = buildUserPrompt(
            compactInput = compactInput,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            translationTone = translationTone,
            outputTokenLimit = safeOutputTokenLimit
        )

        if (userPrompt == lastPromptKey && !lastResponse.isNullOrBlank()) {
            Log.d(TAG, "Ayni istem tekrarlandigi icin model yaniti onbellekten dondu")
            lastRunUsedRetry = false
            return lastResponse!!
        }

        Log.d(TAG, "Gemma modeline girdi gonderildi (${userPrompt.length} karakter)")

        val currentEngine = engine ?: throw IllegalStateException("LiteRT-LM engine null")
        var lastError: Throwable? = null

        var response = runCatching {
            val conversationConfig = buildConversationConfig()
            currentEngine.createConversation(conversationConfig).use { conversation ->
                conversation.sendMessage(userPrompt).toString()
            }
        }.onFailure {
            lastError = it
            Log.e(TAG, "Model generateResponse hatasi", it)
        }.getOrNull()

        if (response.isNullOrBlank() && isLikelyContextOverflow(lastError)) {
            Log.w(TAG, "Context asimi algilandi, kompakt prompt ile tek sefer retry")
            lastRunUsedRetry = true
            val retryPrompt = buildUserPrompt(
                compactInput = compactInput.take(retryInputChars),
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translationTone = translationTone,
                outputTokenLimit = retryOutputTokens
            )

            response = runCatching {
                val retryConversationConfig = buildConversationConfig()
                currentEngine.createConversation(retryConversationConfig).use { conversation ->
                    conversation.sendMessage(retryPrompt).toString()
                }
            }.onFailure {
                Log.e(TAG, "Model retry generateResponse hatasi", it)
            }.getOrNull()
        }

        if (!response.isNullOrBlank()) {
            Log.d(TAG, "Modelden ham yanit geldi (${response.length} karakter)")
            lastPromptKey = userPrompt
            lastResponse = response
            return response
        }

        Log.d(TAG, "Model yaniti bos, bos JSON donuldu")
        return "[]"
    }

    private fun buildConversationConfig(): ConversationConfig {
        return ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = SAMPLER_TOP_K,
                topP = SAMPLER_TOP_P,
                temperature = SAMPLER_TEMPERATURE,
                seed = SAMPLER_SEED
            )
        )
    }

    private fun buildUserPrompt(
        compactInput: String,
        sourceLanguage: String,
        targetLanguage: String,
        translationTone: String,
        outputTokenLimit: Int
    ): String {
        return """
            Kaynak Dil: $sourceLanguage
            Hedef Dil: $targetLanguage
            Ton: $translationTone
            Gorev: Manga metinlerini cevir.
            Kurallar:
            1) Cikti sadece JSON olsun.
            2) box koordinatlari (x,y,w,h) ayni kalsin.
            3) Format: [{"original":"...","translated":"...","box":[x,y,w,h]}]
            4) Maksimum $outputTokenLimit token.
            OCR=$compactInput
        """.trimIndent()
    }

    private fun isLikelyContextOverflow(error: Throwable?): Boolean {
        if (error == null) return false

        var current: Throwable? = error
        while (current != null) {
            val msg = current.message.orEmpty().lowercase()
            if (
                msg.contains("dynamic_update_slice") ||
                msg.contains("failed to allocate tensors") ||
                msg.contains("failed to invoke the compiled model") ||
                msg.contains("internal: error")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    override fun close() {
        runCatching {
            engine?.close()
        }.onFailure {
            Log.e(TAG, "LLM close hatasi", it)
        }
        engine = null
        initialized = false
        lastPromptKey = ""
        lastResponse = null
        lastRunUsedRetry = false
        Log.d(TAG, "Engine kapatildi")
    }

    companion object {
        private const val TAG = "GemmaInferenceEngine"
        private const val DEFAULT_MAX_INPUT_CHARS = 1400
        private const val MIN_INPUT_CHARS = 700
        private const val MIN_RETRY_INPUT_CHARS = 500
        private const val MAX_INPUT_CHARS = 1800
        private const val DEFAULT_OUTPUT_TOKENS = 256
        private const val MIN_OUTPUT_TOKENS = 120
        private const val MAX_OUTPUT_TOKENS = 420
        private const val RETRY_INPUT_RATIO = 0.7f
        private const val RETRY_OUTPUT_RATIO = 0.7f
        private const val MAX_CONTEXT_TOKENS = 1536
        private const val SAMPLER_TOP_K = 24
        private const val SAMPLER_TOP_P = 0.85
        private const val SAMPLER_TEMPERATURE = 0.2
        private const val SAMPLER_SEED = 17
    }
}

