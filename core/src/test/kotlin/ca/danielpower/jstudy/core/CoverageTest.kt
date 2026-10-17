package ca.danielpower.jstudy.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CoverageTest {
    private val ids = mapOf("猫" to 1L, "本" to 2L, "読む" to 3L, "好き" to 4L, "犬" to 5L, "魚" to 6L, "鳥" to 7L, "食べる" to 8L)
    private val reader = Reader { text, reading -> ids[text]?.let { LexiconMatch(it, reading.orEmpty()) } }
    private val known = setOf(1L, 2L, 3L, 4L)

    @Test
    fun coverageCountsDistinctNewWords() {
        val cov = coverage(reader.read("猫は犬が好きです。犬と魚を食べる。タロウは本を読む。"), known::contains)
        assertEquals(listOf("犬", "魚", "食べる"), cov.newWords.map { it.lemma })
        // 猫 犬 好き 犬 魚 食べる 本 読む; タロウ is a name.
        assertEquals(8, cov.words)
        assertEquals(4, cov.knownWords)
    }
}
