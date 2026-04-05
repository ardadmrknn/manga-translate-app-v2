package com.vibecode.mangaceviriv2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

data class FeedbackEntry(
    val id: String,
    val original: String,
    val translated: String,
    val dictionaryProfile: String,
    val modelFileName: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translationTone: String,
    val literalMode: Boolean,
    val updatedAtMs: Long
) {
    fun toContextConfig(): TranslationContextConfig {
        return TranslationContextConfig(
            modelFileName = modelFileName,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            translationTone = translationTone,
            literalMode = literalMode,
            dictionaryProfile = dictionaryProfile
        )
    }
}

data class TranslationMetricsSnapshot(
    val totalRequests: Int = 0,
    val averageDurationMs: Long = 0L,
    val cacheHits: Int = 0,
    val dictionaryOnlyHits: Int = 0,
    val cacheHitRatePercent: Int = 0,
    val lastDurationMs: Long = 0L
)

data class DictionaryTransferResult(
    val importedCount: Int,
    val skippedCount: Int,
    val message: String
)

data class TranslationDebugRecord(
    val createdAtMs: Long,
    val sourceType: String,
    val cacheReason: String,
    val blockCount: Int,
    val totalDurationMs: Long,
    val captureMs: Long,
    val ocrMs: Long,
    val dictionaryMs: Long,
    val inferenceMs: Long,
    val parseMs: Long,
    val renderMs: Long,
    val modelRetryUsed: Boolean
)

object TranslationFeedbackStore {
    private const val PREFS_RECENT = "translation_feedback_recent"
    private const val KEY_RECENT = "recent_entries"
    private const val MAX_RECENT_ITEMS = 8

    private const val DICTIONARY_PREFS = "translation_dictionary_prefs"
    private const val DICTIONARY_KEY = "entries"
    private const val TRANSFER_VERSION = 1

    fun saveRecentTranslations(
        context: Context,
        config: TranslationContextConfig,
        blocks: List<TranslationBlock>
    ) {
        if (blocks.isEmpty()) return
        val now = System.currentTimeMillis()

        val fresh = blocks
            .asSequence()
            .mapNotNull { block ->
                val original = TranslationCoreUtils.normalizeOcrText(block.original)
                val translated = TranslationCoreUtils.normalizeOcrText(block.translated)
                if (original.isBlank() || translated.isBlank()) {
                    null
                } else {
                    val id = TranslationCoreUtils.buildDictionaryKey(config, original)
                    FeedbackEntry(
                        id = id,
                        original = original,
                        translated = translated,
                        dictionaryProfile = config.dictionaryProfile,
                        modelFileName = config.modelFileName,
                        sourceLanguage = config.sourceLanguage,
                        targetLanguage = config.targetLanguage,
                        translationTone = config.translationTone,
                        literalMode = config.literalMode,
                        updatedAtMs = now
                    )
                }
            }
            .distinctBy { it.id }
            .take(MAX_RECENT_ITEMS)
            .toList()

        if (fresh.isEmpty()) return

        val merged = LinkedHashMap<String, FeedbackEntry>()
        fresh.forEach { merged[it.id] = it }
        loadRecentTranslations(context).forEach { entry ->
            if (!merged.containsKey(entry.id)) {
                merged[entry.id] = entry
            }
        }

        persistRecent(
            context = context,
            entries = merged.values
                .sortedByDescending { it.updatedAtMs }
                .take(MAX_RECENT_ITEMS)
        )
    }

    fun loadRecentTranslations(context: Context): List<FeedbackEntry> {
        val prefs = context.getSharedPreferences(PREFS_RECENT, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val original = item.optString("original").trim()
                    val translated = item.optString("translated").trim()
                    val sourceLanguage = item.optString("sourceLanguage").trim()
                    val targetLanguage = item.optString("targetLanguage").trim()
                    val translationTone = item.optString("translationTone").trim()
                    val modelFileName = item.optString("modelFileName").trim()
                    val dictionaryProfile = item
                        .optString("dictionaryProfile")
                        .trim()
                        .ifBlank { TranslationCoreUtils.DEFAULT_DICTIONARY_PROFILE }
                    val id = item.optString("id").trim()

                    if (
                        id.isBlank() ||
                        original.isBlank() ||
                        translated.isBlank() ||
                        sourceLanguage.isBlank() ||
                        targetLanguage.isBlank() ||
                        translationTone.isBlank() ||
                        modelFileName.isBlank()
                    ) {
                        continue
                    }

                    add(
                        FeedbackEntry(
                            id = id,
                            original = original,
                            translated = translated,
                            dictionaryProfile = dictionaryProfile,
                            modelFileName = modelFileName,
                            sourceLanguage = sourceLanguage,
                            targetLanguage = targetLanguage,
                            translationTone = translationTone,
                            literalMode = item.optBoolean("literalMode", false),
                            updatedAtMs = item.optLong("updatedAtMs", 0L)
                        )
                    )
                }
            }
        }.getOrElse {
            emptyList()
        }
    }

    fun applyCorrections(context: Context, editsById: Map<String, String>): Int {
        if (editsById.isEmpty()) return 0

        val currentEntries = loadRecentTranslations(context)
        if (currentEntries.isEmpty()) return 0

        val dictionaryUpdates = LinkedHashMap<String, String>()
        val now = System.currentTimeMillis()
        var appliedCount = 0

        val updatedRecentEntries = currentEntries.map { entry ->
            val updatedValue = editsById[entry.id]?.trim().orEmpty()
            if (updatedValue.isBlank() || updatedValue == entry.translated) {
                entry
            } else {
                val dictionaryKey = TranslationCoreUtils.buildDictionaryKey(entry.toContextConfig(), entry.original)
                dictionaryUpdates[dictionaryKey] = updatedValue
                appliedCount += 1
                entry.copy(
                    translated = updatedValue,
                    updatedAtMs = now
                )
            }
        }

        if (appliedCount == 0) return 0

        upsertDictionary(context, dictionaryUpdates)
        persistRecent(
            context = context,
            entries = updatedRecentEntries
                .sortedByDescending { it.updatedAtMs }
                .take(MAX_RECENT_ITEMS)
        )

        return appliedCount
    }

    fun listAvailableProfiles(context: Context): List<String> {
        val dictionary = loadDictionaryMap(context)
        if (dictionary.isEmpty()) {
            return listOf(TranslationCoreUtils.DEFAULT_DICTIONARY_PROFILE)
        }

        val normalizedProfiles = dictionary.keys
            .mapNotNull { key -> parseDictionaryKey(key)?.profile }
            .map { TranslationCoreUtils.normalizeProfileName(it) }
            .toMutableSet()

        normalizedProfiles.add(TranslationCoreUtils.DEFAULT_DICTIONARY_PROFILE)

        return normalizedProfiles
            .sorted()
            .toList()
    }

    fun exportProfileDictionaryJson(context: Context, profileName: String): String {
        val targetProfile = TranslationCoreUtils.normalizeProfileName(profileName)
        val dictionary = loadDictionaryMap(context)

        val entries = JSONArray()
        dictionary.forEach { (key, value) ->
            val parts = parseDictionaryKey(key) ?: return@forEach
            if (TranslationCoreUtils.normalizeProfileName(parts.profile) != targetProfile) return@forEach

            entries.put(
                JSONObject().apply {
                    put("sourceLanguage", parts.sourceLanguage)
                    put("targetLanguage", parts.targetLanguage)
                    put("translationTone", parts.translationTone)
                    put("literalMode", parts.literalMode)
                    put("original", parts.original)
                    put("translated", value)
                }
            )
        }

        return JSONObject().apply {
            put("version", TRANSFER_VERSION)
            put("profile", targetProfile)
            put("exportedAtMs", System.currentTimeMillis())
            put("entries", entries)
        }.toString(2)
    }

    fun importProfileDictionaryJson(
        context: Context,
        rawJson: String,
        targetProfile: String
    ): DictionaryTransferResult {
        val normalizedTargetProfile = TranslationCoreUtils.normalizeProfileName(targetProfile)
        val updates = LinkedHashMap<String, String>()
        var skipped = 0

        val parsed = runCatching {
            val root = JSONObject(rawJson)
            root.optJSONArray("entries") ?: JSONArray()
        }.recoverCatching {
            JSONArray(rawJson)
        }.getOrElse {
            return DictionaryTransferResult(
                importedCount = 0,
                skippedCount = 0,
                message = "Geçersiz JSON formatı"
            )
        }

        for (index in 0 until parsed.length()) {
            val item = parsed.optJSONObject(index)
            if (item == null) {
                skipped += 1
                continue
            }

            val sourceLanguage = item.optString("sourceLanguage").trim().ifBlank { "english" }
            val targetLanguage = item.optString("targetLanguage").trim().ifBlank { "turkish" }
            val translationTone = item.optString("translationTone").trim().ifBlank { "dogal" }
            val original = item.optString("original").trim()
            val translated = item.optString("translated").trim()
            if (original.isBlank() || translated.isBlank()) {
                skipped += 1
                continue
            }

            val config = TranslationContextConfig(
                modelFileName = "imported",
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translationTone = translationTone,
                literalMode = item.optBoolean("literalMode", false),
                dictionaryProfile = normalizedTargetProfile
            )

            val key = TranslationCoreUtils.buildDictionaryKey(config, original)
            updates[key] = translated
        }

        if (updates.isEmpty()) {
            return DictionaryTransferResult(
                importedCount = 0,
                skippedCount = skipped,
                message = "İçe aktarılacak uygun kayıt bulunamadı"
            )
        }

        upsertDictionary(context, updates)
        return DictionaryTransferResult(
            importedCount = updates.size,
            skippedCount = skipped,
            message = "${updates.size} kayıt içe aktarıldı"
        )
    }

    fun clearProfileDictionary(context: Context, profileName: String): Int {
        val targetProfile = TranslationCoreUtils.normalizeProfileName(profileName)
        val dictionary = loadDictionaryMap(context)
        if (dictionary.isEmpty()) return 0

        val toRemove = dictionary.keys.filter { key ->
            val parts = parseDictionaryKey(key)
            parts != null && TranslationCoreUtils.normalizeProfileName(parts.profile) == targetProfile
        }
        if (toRemove.isEmpty()) return 0

        toRemove.forEach { dictionary.remove(it) }
        persistDictionaryMap(context, dictionary)
        return toRemove.size
    }

    private data class DictionaryKeyParts(
        val profile: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val translationTone: String,
        val literalMode: Boolean,
        val original: String
    )

    private fun parseDictionaryKey(key: String): DictionaryKeyParts? {
        val modernParts = key.split('|', limit = 6)
        if (modernParts.size == 6) {
            val literal = modernParts[4]
            return DictionaryKeyParts(
                profile = modernParts[0],
                sourceLanguage = modernParts[1],
                targetLanguage = modernParts[2],
                translationTone = modernParts[3],
                literalMode = literal == "literal",
                original = modernParts[5]
            )
        }

        val legacyParts = key.split('|', limit = 5)
        if (legacyParts.size == 5) {
            val literal = legacyParts[3]
            return DictionaryKeyParts(
                profile = TranslationCoreUtils.DEFAULT_DICTIONARY_PROFILE,
                sourceLanguage = legacyParts[0],
                targetLanguage = legacyParts[1],
                translationTone = legacyParts[2],
                literalMode = literal == "literal",
                original = legacyParts[4]
            )
        }

        return null
    }

    private fun upsertDictionary(context: Context, updates: Map<String, String>) {
        if (updates.isEmpty()) return

        val dictionary = loadDictionaryMap(context)
        updates.forEach { (key, value) ->
            val cleanValue = value.trim()
            if (cleanValue.isNotBlank()) {
                dictionary[key] = cleanValue
            }
        }

        persistDictionaryMap(context, dictionary)
    }

    private fun loadDictionaryMap(context: Context): LinkedHashMap<String, String> {
        val prefs = context.getSharedPreferences(DICTIONARY_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(DICTIONARY_KEY, null)
        val dictionary = LinkedHashMap<String, String>()

        if (!raw.isNullOrBlank()) {
            runCatching {
                val old = JSONObject(raw)
                val keys = old.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = old.optString(key).trim()
                    if (value.isNotBlank()) {
                        dictionary[key] = value
                    }
                }
            }
        }

        return dictionary
    }

    private fun persistDictionaryMap(context: Context, dictionary: Map<String, String>) {
        val prefs = context.getSharedPreferences(DICTIONARY_PREFS, Context.MODE_PRIVATE)
        val obj = JSONObject()
        dictionary.forEach { (key, value) ->
            obj.put(key, value)
        }
        prefs.edit().putString(DICTIONARY_KEY, obj.toString()).apply()
    }

    private fun persistRecent(context: Context, entries: List<FeedbackEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("original", entry.original)
                    put("translated", entry.translated)
                    put("dictionaryProfile", entry.dictionaryProfile)
                    put("modelFileName", entry.modelFileName)
                    put("sourceLanguage", entry.sourceLanguage)
                    put("targetLanguage", entry.targetLanguage)
                    put("translationTone", entry.translationTone)
                    put("literalMode", entry.literalMode)
                    put("updatedAtMs", entry.updatedAtMs)
                }
            )
        }

        context
            .getSharedPreferences(PREFS_RECENT, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT, array.toString())
            .apply()
    }
}

object TranslationDebugStore {
    private const val PREFS_DEBUG = "translation_debug"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 20

    fun appendRecord(context: Context, record: TranslationDebugRecord) {
        val existing = loadRecentRecords(context).toMutableList()
        existing.add(0, record)
        val trimmed = existing.take(MAX_RECORDS)
        persistRecords(context, trimmed)
    }

    fun loadRecentRecords(context: Context): List<TranslationDebugRecord> {
        val prefs = context.getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        TranslationDebugRecord(
                            createdAtMs = item.optLong("createdAtMs", 0L),
                            sourceType = item.optString("sourceType").ifBlank { "unknown" },
                            cacheReason = item.optString("cacheReason").ifBlank { "none" },
                            blockCount = item.optInt("blockCount", 0),
                            totalDurationMs = item.optLong("totalDurationMs", 0L),
                            captureMs = item.optLong("captureMs", 0L),
                            ocrMs = item.optLong("ocrMs", 0L),
                            dictionaryMs = item.optLong("dictionaryMs", 0L),
                            inferenceMs = item.optLong("inferenceMs", 0L),
                            parseMs = item.optLong("parseMs", 0L),
                            renderMs = item.optLong("renderMs", 0L),
                            modelRetryUsed = item.optBoolean("modelRetryUsed", false)
                        )
                    )
                }
            }
        }.getOrElse {
            emptyList()
        }
    }

    private fun persistRecords(context: Context, records: List<TranslationDebugRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("createdAtMs", record.createdAtMs)
                    put("sourceType", record.sourceType)
                    put("cacheReason", record.cacheReason)
                    put("blockCount", record.blockCount)
                    put("totalDurationMs", record.totalDurationMs)
                    put("captureMs", record.captureMs)
                    put("ocrMs", record.ocrMs)
                    put("dictionaryMs", record.dictionaryMs)
                    put("inferenceMs", record.inferenceMs)
                    put("parseMs", record.parseMs)
                    put("renderMs", record.renderMs)
                    put("modelRetryUsed", record.modelRetryUsed)
                }
            )
        }

        context
            .getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, array.toString())
            .apply()
    }
}

object TranslationMetricsStore {
    private const val PREFS_METRICS = "translation_metrics"
    private const val KEY_TOTAL_REQUESTS = "total_requests"
    private const val KEY_TOTAL_DURATION_MS = "total_duration_ms"
    private const val KEY_CACHE_HITS = "cache_hits"
    private const val KEY_DICTIONARY_ONLY_HITS = "dictionary_only_hits"
    private const val KEY_LAST_DURATION_MS = "last_duration_ms"

    fun recordTranslation(
        context: Context,
        durationMs: Long,
        fromCache: Boolean,
        fromDictionaryOnly: Boolean
    ) {
        val prefs = context.getSharedPreferences(PREFS_METRICS, Context.MODE_PRIVATE)
        val total = prefs.getInt(KEY_TOTAL_REQUESTS, 0) + 1
        val cumulativeDuration = prefs.getLong(KEY_TOTAL_DURATION_MS, 0L) + durationMs.coerceAtLeast(0L)
        val cacheHits = prefs.getInt(KEY_CACHE_HITS, 0) + if (fromCache) 1 else 0
        val dictionaryHits = prefs.getInt(KEY_DICTIONARY_ONLY_HITS, 0) + if (fromDictionaryOnly) 1 else 0

        prefs.edit()
            .putInt(KEY_TOTAL_REQUESTS, total)
            .putLong(KEY_TOTAL_DURATION_MS, cumulativeDuration)
            .putInt(KEY_CACHE_HITS, cacheHits)
            .putInt(KEY_DICTIONARY_ONLY_HITS, dictionaryHits)
            .putLong(KEY_LAST_DURATION_MS, durationMs.coerceAtLeast(0L))
            .apply()
    }

    fun snapshot(context: Context): TranslationMetricsSnapshot {
        val prefs = context.getSharedPreferences(PREFS_METRICS, Context.MODE_PRIVATE)
        val total = prefs.getInt(KEY_TOTAL_REQUESTS, 0)
        val cumulativeDuration = prefs.getLong(KEY_TOTAL_DURATION_MS, 0L)
        val cacheHits = prefs.getInt(KEY_CACHE_HITS, 0)
        val dictionaryHits = prefs.getInt(KEY_DICTIONARY_ONLY_HITS, 0)
        val lastDuration = prefs.getLong(KEY_LAST_DURATION_MS, 0L)

        val average = if (total > 0) cumulativeDuration / total else 0L
        val cacheRatePercent = if (total > 0) {
            ((cacheHits * 100f) / total.toFloat()).toInt()
        } else {
            0
        }

        return TranslationMetricsSnapshot(
            totalRequests = total,
            averageDurationMs = average,
            cacheHits = cacheHits,
            dictionaryOnlyHits = dictionaryHits,
            cacheHitRatePercent = cacheRatePercent,
            lastDurationMs = lastDuration
        )
    }
}
