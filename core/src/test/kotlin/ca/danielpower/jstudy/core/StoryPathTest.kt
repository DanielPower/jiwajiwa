package ca.danielpower.jstudy.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryPathTest {
    private val ids = mapOf("猫" to 1L, "犬" to 2L, "魚" to 3L, "好き" to 4L, "食べる" to 5L)
    private val reader = Reader { text, reading -> ids[text]?.let { LexiconMatch(it, reading.orEmpty()) } }

    @Test
    fun `a story counts each word once, and skips grammar and names`() {
        val words = StoryPath.words(reader, "タマは猫が好きです。猫は魚を食べます。")
        assertEquals(listOf("猫", "好き", "魚", "食べる"), words.map { it.lemma })
    }

    @Test
    fun `words with no dictionary entry always count as new`() {
        val words = needs("猫はりんごを食べます。").words
        val new = StoryPath.newWords(words) { it == 1L || it == 5L }
        assertEquals(listOf("りんご"), new.map { it.lemma })
    }

    private fun needs(body: String, teaches: List<String> = emptyList()) =
        StoryPath.needs(reader, PathStory("x", "A title in English", body, emptyList(), teaches))

    @Test
    fun `a step needs its grammar, but not its English title's`() {
        val n = needs("猫と犬です。")
        assertEquals(setOf("to", "desu"), n.grammar)
        assertEquals(setOf("wa", "desu"), needs("猫は犬です。").grammar)
    }

    @Test
    fun `the new word allowance is 3 percent of running words, and at least 3`() {
        fun allowance(running: Int) = StepNeeds(emptyList(), running, emptySet(), emptySet()).allowance
        assertEquals(3, allowance(10))
        assertEquals(3, allowance(133))
        assertEquals(6, allowance(200))
        assertEquals(30, allowance(1000))
    }

    @Test
    fun `a chapter waits for its grammar to be taught, unless it teaches it`() {
        val story = needs("猫は犬です。")
        val none = StoryPath.gap(story, { true }, emptySet())
        assertEquals(listOf("desu", "wa"), none.grammar)
        assertFalse(none.ready)
        assertTrue(StoryPath.gap(story, { true }, setOf("wa", "desu")).ready)
        assertTrue(StoryPath.gap(needs("猫は犬です。", teaches = listOf("wa", "desu")), { true }, emptySet()).ready)
    }

    @Test
    fun `the solver reads whatever is open until nothing is, and finds what can't be reached`() {
        val lesson = needs("猫は猫です。", teaches = listOf("wa", "desu"))
        val story = needs("犬は魚です。")
        val far = needs("犬は魚と好きです。")
        assertEquals(listOf(1, 0), StoryPath.solve(listOf(story, lesson)))
        assertEquals(listOf(0, 1), StoryPath.solve(listOf(lesson, story, far)))
    }

    @Test
    fun `reads the bundled stories file`() {
        val catalog = StoryPath.parse(bundled.readText())
        assertTrue(catalog.series.isNotEmpty())
        val ids = catalog.chapters.map { it.second.story.id } + catalog.grammar.map { it.page.id }
        assertEquals(ids.size, ids.toSet().size, "ids are unique")
        assertEquals(catalog.series.size, catalog.series.map { it.id }.toSet().size, "story ids are unique")
        // A chapter's note follows a paragraph, whose end has its marker.
        for ((_, c) in catalog.chapters) for (note in c.story.notes) {
            assertTrue(note.before in 1..paragraphs(c.story.body).size, "${c.story.id}: note before paragraph ${note.before}")
        }
        assertTrue(catalog.chapters.any { it.second.story.notes.isNotEmpty() }, "chapter notes are read")
    }

    /** path.json is put together from tools/stories; this catches a chapter changed without rewriting it. */
    @Test
    fun `the bundled stories file is up to date with tools stories`() {
        assertEquals(StorySource.pathJson(File("..")), bundled.readText(), "path.json is out of date: run ./gradlew -PcoreOnly=true :core:stories")
    }

    /**
     * Checks the bundled stories against the real dictionary: every chapter
     * and grammar page has English for each sentence, every counted word is
     * in the dictionary, topics name real grammar points, each story read in
     * order (after the stories it follows) opens chapter by chapter, with
     * its cast speaking only once introduced and no chapter under half the
     * length of those before it, the solver reaches every chapter from
     * knowing nothing, and the files the story tools write are up to date.
     * Needs the dictionary built (python3 tools/dictionary/build_dictionary.py),
     * so it's skipped where it isn't. Writes build/reports/story-path.txt.
     */
    @Test
    fun `every chapter of the bundled stories can be reached from knowing nothing`() {
        val root = File("..")
        assumeTrue(StoryTools.dictionary(root).exists(), "dictionary not built")
        StoryTools.open(root).use { tools ->
            val catalog = tools.catalog
            val reader = tools.reader
            val needs = tools.needs
            val report = StringBuilder()
            val problems = ArrayList<String>()

            fun sentences(story: PathStory) = reader.read(story.body).sumOf { sentenceRanges(it).size }
            // Grammar the reader can't explain would go untaught and unchecked: a particle or ending
            // with no grammar point, or a form of a word that no point covers and the dictionary
            // doesn't have whole (いらっしゃいませ is a word; いいじゃん needs じゃん).
            fun unexplained(story: PathStory): String? = reader.read(story.body, story.readings, story.names).flatten()
                .filter { it.grammar.isEmpty() && (it.kind == WordKind.GRAMMAR || it.conjugated) }
                .takeIf { it.isNotEmpty() }
                ?.let { w -> "no grammar point explains " + w.joinToString { it.surface } + " (add one to Grammar.kt)" }
            for ((_, c) in catalog.chapters) {
                val s = c.story
                if (sentences(s) != s.translations.size) problems += "${s.id}: ${sentences(s)} sentences, ${s.translations.size} translations"
                for (w in needs.getValue(s.id).words.filter { it.wordId == null }) problems += "${s.id}: ${w.lemma} isn't in the dictionary"
                if (c.topic != null && catalog.topic(c.topic) == null) problems += "${s.id}: no grammar topic ${c.topic}"
                unexplained(s)?.let { problems += "${s.id}: $it" }
                // A pinned reading must be needed, and must take: the reader agrees once it's pinned.
                for ((text, reading) in s.readings) {
                    // A dictionary form pins its conjugations too (描く pins 描いた).
                    fun read(pins: Map<String, String>) = reader.read(s.body, pins, s.names).flatten().filter { it.surface == text || (it.kind == WordKind.WORD && it.lemma == text) }
                    val pinned = read(s.readings)
                    if (pinned.isEmpty()) problems += "${s.id}: readings: no word written $text"
                    else if (pinned.any { (it.surface == text && it.reading != reading) || it.wordId == null }) problems += "${s.id}: readings: the dictionary has no $text read $reading"
                    else if (read(emptyMap()) == pinned) problems += "${s.id}: readings: $text is already read $reading"
                }
            }
            for (t in catalog.grammar) {
                unexplained(t.page)?.let { problems += "${t.id}: $it" }
                if (sentences(t.page) != t.page.translations.size) problems += "${t.id}: ${sentences(t.page)} sentences, ${t.page.translations.size} translations"
                for (id in t.points.filter { grammarPoint(it) == null }) problems += "${t.id}: no grammar point $id"
                if (catalog.teaching(t.id).isEmpty()) problems += "${t.id}: no chapter teaches it"
            }

            // Anyone in a story's cast. Any other speaker is a description (店員, 男の人), fine anywhere.
            val cast = catalog.chapters.flatMap { it.second.meets.map { m -> m.name } }.toSet()
            // Each story read in order, after the ones it follows, opens one chapter at a time.
            // A side story opens with nothing new at all. The stories it follows are checked on their own turn.
            for (series in catalog.series) {
                val steps = tools.walk(series) { problems += "${series.id}: no story or chapter $it" }
                val met = HashSet<String>()
                for ((i, step) in steps.withIndex()) {
                    val c = step.chapter
                    met += c.meets.map { it.name }
                    if (!step.own) continue
                    val gap = step.gap
                    val id = c.story.id
                    val early = Speech.speakers(c.story.body).filterNotNull().filter { it in cast && it !in met }.distinct()
                    if (early.isNotEmpty()) problems += "$id: ${early.joinToString()} ${if (early.size == 1) "speaks" else "speak"} before they're introduced"
                    // Chapters grow as the words do; a short one now and then is fine, but not half the length of what came before.
                    val recent = steps.subList(maxOf(0, i - 5), i).map { it.needs.running }.sorted()
                    if (recent.isNotEmpty() && step.needs.running * 2 < recent[recent.size / 2])
                        problems += "$id: ${step.needs.running} words, under half the ${recent[recent.size / 2]} of the chapters before it"
                    if (gap.words.size > gap.allowance) problems += "$id: ${gap.words.size} new words, ${gap.allowance} allowed"
                    if (gap.grammar.isNotEmpty()) problems += "$id: grammar not taught yet: ${gap.grammar}"
                    if (series.side && (gap.words.isNotEmpty() || c.topic != null))
                        problems += "$id: side story has something new: ${gap.words.map { it.lemma }} ${c.topic.orEmpty()}"
                    if (c.plot.isBlank()) problems += "$id: no plot line"
                    // The topic's example, shown before reading, uses only words you have by then.
                    catalog.topic(c.topic)?.example?.let { (jp, _) ->
                        val unknown = StoryPath.words(reader, jp).filter { it.wordId !in step.words }
                        if (unknown.isNotEmpty()) problems += "$id: ${c.topic} example has new words ${unknown.map { it.lemma }}"
                    }
                    report.appendLine("$id ${c.story.title}  words ${gap.words.size}/${gap.allowance}, total ${step.words.size}${c.topic?.let { "; teaches $it" }.orEmpty()}")
                    report.appendLine("    grammar ${step.needs.grammar.sorted()}")
                    for (w in gap.words) report.appendLine("    ${w.lemma} ${w.wordId} ${tools.lexicon.describe(w.wordId)}")
                }
                report.appendLine()
            }

            val all = catalog.chapters.map { needs.getValue(it.second.story.id) }
            val order = StoryPath.solve(all)
            val stuck = all.indices - order.toSet()
            if (stuck.isNotEmpty()) problems += "the solver can't reach ${stuck.map { catalog.chapters[it].second.story.id }}"
            // Words met in only one chapter get no reinforcement from reading; aim to bring them back.
            val chaptersWith = HashMap<Long, Int>()
            for (n in all) for (id in n.words.mapNotNull { it.wordId }.distinct()) chaptersWith.merge(id, 1, Int::plus)
            val once = chaptersWith.filterValues { it == 1 }.keys
            report.appendLine("Words in one chapter only (${once.size} of ${chaptersWith.size}): ${once.joinToString(" ") { tools.lexicon.describe(it).substringBefore('/') }}")

            // The app reads the index in place of every chapter, so it must match the stories.
            for ((file, text) in tools.outputs()) {
                if (!file.exists() || file.readText() != text) problems += "${file.relativeTo(root)} is out of date: run ./gradlew -PcoreOnly=true :core:stories"
            }
            assertEquals(needs, StoryPath.parseNeeds(StoryPath.writeNeeds(needs), catalog), "the index reads back as written")

            File("build/reports").mkdirs()
            File("build/reports/story-path.txt").writeText(report.toString() + problems.distinct().joinToString("\n", "\nProblems:\n"))
            assertTrue(problems.isEmpty(), problems.distinct().joinToString("\n"))
        }
    }

    private val bundled = File("../app/src/main/assets/stories/path.json")
}
