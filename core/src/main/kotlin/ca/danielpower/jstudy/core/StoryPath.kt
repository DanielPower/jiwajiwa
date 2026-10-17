package ca.danielpower.jstudy.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * English beside the Japanese, shown before paragraph [before] (after the
 * last one when it's the paragraph count). In a grammar topic's page, a
 * paragraph is a line, and the note sits between them; in a chapter, it
 * opens from a marker at the end of the paragraph before.
 */
data class LessonNote(val before: Int, val text: String)

/**
 * Japanese that comes with the app: a chapter of a story, or a grammar
 * topic's page. Its [title] is English (or, for a grammar topic, the
 * pattern) and isn't read; only the [body] counts.
 */
data class PathStory(
    /** Stable across app updates, so a read chapter stays read. */
    val id: String,
    val title: String,
    val body: String,
    /** English for each sentence of the body, in order. */
    val translations: List<String>,
    /** The grammar points it teaches (ids from [grammarPoint]): a chapter's topic, before you read it. */
    val teaches: List<String> = emptyList(),
    /** English between the paragraphs: a grammar topic's explanations, or a chapter's culture notes. */
    val notes: List<LessonNote> = emptyList(),
    /** A grammar topic's page, rather than a chapter. */
    val lesson: Boolean = false,
    /** Words the reader gets wrong in it, as written, with their reading ([Reader.words]). */
    val readings: Map<String, String> = emptyMap(),
    /** Cast names written in katakana, read as names even where the dictionary has a word spelled the same (リン, not 燐). */
    val names: Set<String> = emptySet(),
)

/** Someone in a story, as the chapter that introduces them describes them. */
data class CastMember(val name: String, val about: String)

/** One chapter of a story: [number] counts from 1. */
data class Chapter(
    val story: PathStory,
    val number: Int,
    /** The grammar topic it introduces before you read it, if any. */
    val topic: String?,
    /** The people you meet in it. */
    val meets: List<CastMember>,
    /** What happens in it, in a line, for the story tools' summaries. */
    val plot: String = "",
)

/**
 * A story that comes with the app, as one or more chapters. Each chapter
 * opens on its own once its words and grammar are within reach.
 * Read in order, after the stories in [after], every chapter is.
 */
data class PathSeries(
    val id: String,
    /** English, with Japanese where it fits. */
    val title: String,
    val blurb: String,
    /** Story ids, or chapter ids for the chapters up to and including that one. */
    val after: List<String>,
    val chapters: List<Chapter>,
    /** Uses only what [after] teaches, so it's something new to read with nothing new to learn. */
    val side: Boolean = false,
) {
    /** A one-chapter story opens straight to its chapter, with no chapter list. */
    val single get() = chapters.size == 1
}

/**
 * Grammar taught before the chapter that first needs it: one or more
 * related points (から and まで), a line or two on them, and a page to look
 * back at with the examples and fuller notes.
 */
data class GrammarTopic(
    val id: String,
    /** The pattern, as textbooks write it ("～て (ください)"). */
    val title: String,
    /** A few words of English ("Asking, linking"). */
    val titleEn: String,
    val points: List<String>,
    /** What it does, in a sentence or two. */
    val summary: String,
    /** Its full page: the examples, with notes between them. */
    val page: PathStory,
) {
    /** The first example and its English, shown with the summary. */
    val example: Pair<String, String>? get() = page.body.lineSequence().firstOrNull()?.let { it to page.translations.first() }
}

/** Every story and grammar topic that comes with the app. */
data class StoryCatalog(val series: List<PathSeries>, val grammar: List<GrammarTopic>) {
    val chapters: List<Pair<PathSeries, Chapter>> get() = series.flatMap { s -> s.chapters.map { s to it } }

    fun topic(id: String?): GrammarTopic? = grammar.firstOrNull { it.id == id }

    /** The topic a grammar point belongs to. */
    fun topicOf(point: String): GrammarTopic? = grammar.firstOrNull { point in it.points }

    /** The chapters that introduce [topic], in order. */
    fun teaching(topic: String): List<Pair<PathSeries, Chapter>> = chapters.filter { it.second.topic == topic }
}

/** A word a step counts: its dictionary entry, or only its dictionary form when it has none. */
data class StepWord(val wordId: Long?, val lemma: String) {
    /** Identifies the word for counting, as [ReadingWord.key] does. */
    val key: String get() = wordId?.toString() ?: lemma
}

/** What a step asks of you: the words and grammar its Japanese uses. */
data class StepNeeds(
    /** Each counted word once, in the order they first appear. */
    val words: List<StepWord>,
    /** How many counted words there are, repeats included. */
    val running: Int,
    /** The grammar points its Japanese uses. */
    val grammar: Set<String>,
    /** The grammar points it teaches, for a lesson. */
    val teaches: Set<String>,
) {
    /** How many new words it may have: 3% of its running words, and at least [StoryPath.MIN_NEW]. */
    val allowance get() = (running * 3 / 100).coerceAtLeast(StoryPath.MIN_NEW)
}

/** What a step's [needs] leave to learn, for someone who knows some of it. */
data class StepGap(val words: List<StepWord>, val grammar: List<String>, val allowance: Int) {
    /** Open to read: few enough new words, and no grammar that hasn't been taught. */
    val ready get() = words.size <= allowance && grammar.isEmpty()
}

/**
 * The bundled stories, whose chapters introduce new words and grammar in
 * place of the study queue. A chapter opens once it has few enough new
 * words and uses only grammar you've seen or it teaches. A chapter
 * teaches one grammar topic at most, before you read it.
 */
object StoryPath {
    const val MIN_NEW = 3

    fun parse(json: String): StoryCatalog {
        val root = Json.parseToJsonElement(json).jsonObject
        fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        fun JsonObject.texts(key: String) = this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        fun JsonObject.notes() = this["notes"]?.jsonArray.orEmpty().map { n ->
            val note = n.jsonObject
            LessonNote(note.getValue("before").jsonPrimitive.int, note.text("text"))
        }
        val names = root.getValue("series").jsonArray.flatMap { e ->
            e.jsonObject.getValue("chapters").jsonArray.flatMap { c -> c.jsonObject["meets"]?.jsonArray.orEmpty() }
        }.map { it.jsonObject.text("name") }.filter { Kana.isKana(it) }.toSet()
        val grammar = root["grammar"]?.jsonArray.orEmpty().map { e ->
            val o = e.jsonObject
            val id = o.text("id")
            GrammarTopic(
                id = id,
                title = o.text("title"),
                titleEn = o.text("title_en"),
                points = o.texts("points"),
                summary = o.text("summary"),
                page = PathStory(
                    id = "grammar/$id",
                    title = o.text("title"),
                    body = o.text("body"),
                    translations = o.texts("translations"),
                    notes = o.notes(),
                    lesson = true,
                    names = names,
                ),
            )
        }
        val topics = grammar.associateBy { it.id }
        val series = root.getValue("series").jsonArray.map { e ->
            val o = e.jsonObject
            PathSeries(
                id = o.text("id"),
                title = o.text("title"),
                blurb = o["blurb"]?.jsonPrimitive?.content.orEmpty(),
                after = o.texts("after"),
                side = o["side"]?.jsonPrimitive?.boolean ?: false,
                chapters = o.getValue("chapters").jsonArray.mapIndexed { i, c ->
                    val ch = c.jsonObject
                    val topic = ch["topic"]?.jsonPrimitive?.content
                    Chapter(
                        story = PathStory(
                            id = ch.text("id"),
                            title = ch.text("title"),
                            body = ch.text("body"),
                            translations = ch.texts("translations"),
                            teaches = topic?.let { topics[it]?.points }.orEmpty(),
                            notes = ch.notes(),
                            readings = ch["readings"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content },
                            names = names,
                        ),
                        number = i + 1,
                        topic = topic,
                        meets = ch["meets"]?.jsonArray.orEmpty().map { m ->
                            val member = m.jsonObject
                            CastMember(member.text("name"), member.text("about"))
                        },
                        plot = ch["plot"]?.jsonPrimitive?.content.orEmpty(),
                    )
                },
            )
        }
        return StoryCatalog(series, grammar)
    }

    /**
     * Each chapter's needs, as the story tools write them to stories/needs.json
     * when the stories change, so the app doesn't read every chapter to find them.
     * A word is its entry id and dictionary form, or just the form when it has no entry.
     */
    fun writeNeeds(needs: Map<String, StepNeeds>): String =
        needs.entries.joinToString(",\n", "{\n", "\n}\n") { (id, n) ->
            val entry = buildJsonObject {
                put("running", n.running)
                putJsonArray("grammar") { n.grammar.sorted().forEach { add(it) } }
                putJsonArray("words") {
                    for (w in n.words) addJsonArray { w.wordId?.let { add(it) }; add(w.lemma) }
                }
            }
            // A chapter a line, so a change to one shows as a change to its line.
            " ${JsonPrimitive(id)}: $entry"
        }

    /** The needs [writeNeeds] wrote, with what each chapter teaches from [catalog]. */
    fun parseNeeds(json: String, catalog: StoryCatalog): Map<String, StepNeeds> {
        val root = Json.parseToJsonElement(json).jsonObject
        return catalog.chapters.associate { (_, c) ->
            val o = root[c.story.id]?.jsonObject ?: error("stories/needs.json has no ${c.story.id}: run ./gradlew -PcoreOnly=true :core:stories")
            c.story.id to StepNeeds(
                words = o.getValue("words").jsonArray.map { w ->
                    val parts = w.jsonArray.map { it.jsonPrimitive }
                    StepWord(parts.first().longOrNull.takeIf { parts.size == 2 }, parts.last().content)
                },
                running = o.getValue("running").jsonPrimitive.int,
                grammar = o.getValue("grammar").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                teaches = c.story.teaches.toSet(),
            )
        }
    }

    /** The words a story's body counts, each once, in the order they first appear. */
    fun words(reader: Reader, body: String, readings: Map<String, String> = emptyMap(), names: Set<String> = emptySet()): List<ReadingWord> =
        reader.read(body, readings, names).flatten().filter { it.kind == WordKind.WORD }.distinctBy { it.key }

    fun needs(reader: Reader, story: PathStory): StepNeeds {
        val all = reader.read(story.body, story.readings, story.names).flatten()
        val counted = all.filter { it.kind == WordKind.WORD }
        return StepNeeds(
            words = counted.distinctBy { it.key }.map { StepWord(it.wordId, it.lemma) },
            running = counted.size,
            grammar = all.flatMap { w -> w.grammar.map { it.point.id } }.toSet(),
            teaches = story.teaches.toSet(),
        )
    }

    /** Of a story's [words], the ones still to learn. A word with no dictionary entry can't be learned, so it always counts. */
    fun newWords(words: List<StepWord>, isKnown: (Long) -> Boolean): List<StepWord> =
        words.filter { w -> w.wordId?.let(isKnown) != true }

    /** What's left of [needs] to learn, given the words and grammar you know. */
    fun gap(needs: StepNeeds, isKnown: (Long) -> Boolean, seenGrammar: Set<String>) = StepGap(
        words = newWords(needs.words, isKnown),
        grammar = (needs.grammar - needs.teaches - seenGrammar).sorted(),
        allowance = needs.allowance,
    )

    /**
     * Reads every step that's open, learning what it teaches, until none is
     * left, starting from no words and no grammar. Returns the
     * indexes read, in order; any step missing can never be reached.
     *
     * Reading a step only adds to what you know, and knowing more never
     * closes a step, so this finds everything reachable. It also means no
     * order can get stuck: whatever you've read, the first step of this
     * order you haven't is open to you.
     */
    fun solve(steps: List<StepNeeds>): List<Int> {
        val words = HashSet<String>()
        val grammar = HashSet<String>()
        val order = ArrayList<Int>()
        val left = steps.indices.toMutableList()
        while (true) {
            val i = left.firstOrNull { i ->
                val s = steps[i]
                s.words.count { it.wordId == null || it.key !in words } <= s.allowance &&
                    grammar.containsAll(s.grammar - s.teaches)
            } ?: return order
            left.remove(i)
            order += i
            words += steps[i].words.map { it.key }
            grammar += steps[i].grammar + steps[i].teaches
        }
    }
}
