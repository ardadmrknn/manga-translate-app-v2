package com.vibecode.mangaceviriv2

import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

data class TranslationContextConfig(
    val modelFileName: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translationTone: String,
    val literalMode: Boolean,
    val dictionaryProfile: String = "genel"
)

object TranslationCoreUtils {

    fun normalizeOcrText(text: String): String {
        return text
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun parseModelJson(jsonText: String, json: Json): List<TranslationBlock> {
        return runCatching {
            var cleanJson = jsonText
            val startIdx = jsonText.indexOf('[')
            val endIdx = jsonText.lastIndexOf(']')
            if (startIdx != -1 && endIdx != -1 && endIdx >= startIdx) {
                cleanJson = jsonText.substring(startIdx, endIdx + 1)
            }
            json.decodeFromString<List<TranslationBlock>>(cleanJson)
        }.getOrElse {
            emptyList()
        }
    }

    fun mergeNearbyBlocks(blocks: List<TranslationBlock>): List<TranslationBlock> {
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

    fun prioritizeBlocksForInference(
        blocks: List<TranslationBlock>,
        maxBlockCount: Int,
        maxTextPerBlock: Int
    ): List<TranslationBlock> {
        if (blocks.isEmpty()) return emptyList()

        val normalized = blocks
            .asSequence()
            .map { block -> block.copy(original = normalizeOcrText(block.original).take(maxTextPerBlock)) }
            .filter { it.original.isNotBlank() }
            .toList()

        if (normalized.size <= maxBlockCount) {
            return normalized.sortedWith(compareBy({ it.box.getOrElse(1) { 0 } }, { it.box.getOrElse(0) { 0 } }))
        }

        val selected = normalized
            .map { block -> block to blockPriorityScore(block) }
            .sortedByDescending { it.second }
            .take(maxBlockCount)
            .map { it.first }

        return selected.sortedWith(compareBy({ it.box.getOrElse(1) { 0 } }, { it.box.getOrElse(0) { 0 } }))
    }

    fun adaptiveOutputTokenLimit(blocks: List<TranslationBlock>): Int {
        if (blocks.isEmpty()) return 140
        val totalChars = blocks.sumOf { it.original.length }
        val byChars = (totalChars * 0.42f).toInt()
        val byCount = 72 + blocks.size * 24
        return maxOf(byChars, byCount).coerceIn(120, 420)
    }

    fun adaptiveInputCharLimit(blocks: List<TranslationBlock>): Int {
        if (blocks.isEmpty()) return 900
        val totalChars = blocks.sumOf { it.original.length }
        return (totalChars + blocks.size * 40).coerceIn(700, 1800)
    }

    fun buildDictionaryKey(config: TranslationContextConfig, text: String): String {
        val normalized = normalizeOcrText(text).lowercase(Locale.ROOT)
        val profile = normalizeProfileName(config.dictionaryProfile)
        val source = config.sourceLanguage.lowercase(Locale.ROOT)
        val target = config.targetLanguage.lowercase(Locale.ROOT)
        val tone = config.translationTone.lowercase(Locale.ROOT)
        val literal = if (config.literalMode) "literal" else "standard"
        return "$profile|$source|$target|$tone|$literal|$normalized"
    }

    fun normalizeProfileName(profile: String): String {
        val normalized = normalizeOcrText(profile).lowercase(Locale.ROOT)
        return if (normalized.isBlank()) DEFAULT_DICTIONARY_PROFILE else normalized
    }

    fun buildInferenceCacheKey(
        config: TranslationContextConfig,
        selection: FrameSelection,
        ocrBlocks: List<TranslationBlock>
    ): String {
        val literal = if (config.literalMode) "literal" else "standard"
        val configPart = listOf(
            config.modelFileName,
            config.sourceLanguage,
            config.targetLanguage,
            config.translationTone,
            literal
        ).joinToString("|")

        val selectionPart = "${selection.left},${selection.top},${selection.width},${selection.height}"
        val blocksPart = ocrBlocks.joinToString("|") { block ->
            val normalized = normalizeOcrText(block.original)
            "$normalized#${block.box.joinToString(",")}"
        }

        return sha256Hex("$configPart::$selectionPart::$blocksPart")
    }

    private fun blockPriorityScore(block: TranslationBlock): Float {
        val box = block.toBox()
        val area = (box.width * box.height).toFloat().coerceAtLeast(1f)
        val areaScore = (area / 7000f).coerceAtMost(2.4f)

        val text = block.original
        val length = text.length.coerceAtLeast(1)
        val letterOrDigit = text.count { it.isLetterOrDigit() }
        val alnumRatio = letterOrDigit.toFloat() / length.toFloat()

        val lengthScore = when {
            length in 4..72 -> 1.2f
            length in 73..140 -> 1.0f
            else -> 0.72f
        }

        val horizontalBonus = if (box.width >= box.height) 0.25f else 0.1f
        val verticalPenalty = (box.top.toFloat() / 2200f).coerceAtMost(0.8f)

        return areaScore + lengthScore + (alnumRatio * 0.8f) + horizontalBonus - verticalPenalty
    }

    private fun canMerge(left: TranslationBlock, right: TranslationBlock): Boolean {
        val l = left.toBox()
        val r = right.toBox()
        val leftCenterY = l.top + l.height / 2
        val rightCenterY = r.top + r.height / 2
        val verticalCenterDiff = abs(leftCenterY - rightCenterY)
        val averageHeight = (l.height + r.height) / 2
        val gap = r.left - l.right

        return verticalCenterDiff <= averageHeight / 2 && gap in -16..48
    }

    private fun mergeBlocks(left: TranslationBlock, right: TranslationBlock): TranslationBlock {
        val l = left.toBox()
        val r = right.toBox()

        val mergedLeft = minOf(l.left, r.left)
        val mergedTop = minOf(l.top, r.top)
        val mergedRight = maxOf(l.right, r.right)
        val mergedBottom = maxOf(l.bottom, r.bottom)

        return TranslationBlock(
            original = "${left.original} ${right.original}".trim(),
            translated = "",
            box = listOf(
                mergedLeft,
                mergedTop,
                (mergedRight - mergedLeft).coerceAtLeast(1),
                (mergedBottom - mergedTop).coerceAtLeast(1)
            )
        )
    }

    private fun TranslationBlock.toBox(): Box {
        val x = box.getOrNull(0)?.coerceAtLeast(0) ?: 0
        val y = box.getOrNull(1)?.coerceAtLeast(0) ?: 0
        val width = box.getOrNull(2)?.coerceAtLeast(1) ?: 1
        val height = box.getOrNull(3)?.coerceAtLeast(1) ?: 1
        return Box(
            left = x,
            top = y,
            right = x + width,
            bottom = y + height,
            width = width,
            height = height
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                append((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
            }
        }
    }

    private data class Box(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val width: Int,
        val height: Int
    )

    const val DEFAULT_DICTIONARY_PROFILE = "genel"
}
