package com.vibecode.mangaceviriv2

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCoreUtilsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun mergeNearbyBlocks_mergesNeighbouringLines() {
        val blocks = listOf(
            TranslationBlock(
                original = "Hello",
                translated = "",
                box = listOf(10, 20, 30, 10)
            ),
            TranslationBlock(
                original = "world",
                translated = "",
                box = listOf(45, 21, 34, 10)
            ),
            TranslationBlock(
                original = "far",
                translated = "",
                box = listOf(220, 100, 20, 10)
            )
        )

        val merged = TranslationCoreUtils.mergeNearbyBlocks(blocks)

        assertEquals(2, merged.size)
        assertTrue(merged[0].original.contains("Hello world"))
        assertEquals(listOf(10, 20, 69, 11), merged[0].box)
    }

    @Test
    fun parseModelJson_extractsArrayFromWrappedText() {
        val raw = """
            model output:
            ```json
            [{"original":"A","translated":"B","box":[1,2,3,4]}]
            ```
        """.trimIndent()

        val parsed = TranslationCoreUtils.parseModelJson(raw, json)

        assertEquals(1, parsed.size)
        assertEquals("A", parsed.first().original)
        assertEquals("B", parsed.first().translated)
        assertEquals(listOf(1, 2, 3, 4), parsed.first().box)
    }

    @Test
    fun buildInferenceCacheKey_isStableAndConfigSensitive() {
        val configA = TranslationContextConfig(
            modelFileName = "gemma-4-E2B-it.litertlm",
            sourceLanguage = "English",
            targetLanguage = "Turkish",
            translationTone = "Dogal",
            literalMode = false
        )
        val configB = configA.copy(targetLanguage = "English")

        val selectionA = FrameSelection(left = 10, top = 10, width = 120, height = 120)
        val selectionB = FrameSelection(left = 10, top = 10, width = 140, height = 120)
        val blocks = listOf(
            TranslationBlock(
                original = "Where are you?",
                translated = "",
                box = listOf(5, 6, 50, 20)
            )
        )

        val key1 = TranslationCoreUtils.buildInferenceCacheKey(configA, selectionA, blocks)
        val key2 = TranslationCoreUtils.buildInferenceCacheKey(configA, selectionA, blocks)
        val keySelectionChanged = TranslationCoreUtils.buildInferenceCacheKey(configA, selectionB, blocks)
        val keyConfigChanged = TranslationCoreUtils.buildInferenceCacheKey(configB, selectionA, blocks)

        assertEquals(key1, key2)
        assertNotEquals(key1, keySelectionChanged)
        assertNotEquals(key1, keyConfigChanged)
    }
}
