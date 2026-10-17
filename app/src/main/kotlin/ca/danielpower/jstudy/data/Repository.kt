package ca.danielpower.jstudy.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import ca.danielpower.jstudy.core.Answer
import ca.danielpower.jstudy.core.Card
import ca.danielpower.jstudy.core.CardState
import ca.danielpower.jstudy.core.DailyLimits
import ca.danielpower.jstudy.core.Direction
import ca.danielpower.jstudy.core.Fsrs
import ca.danielpower.jstudy.core.ImportPlan
import ca.danielpower.jstudy.core.Kanji
import ca.danielpower.jstudy.core.Kanjis
import ca.danielpower.jstudy.core.LocalWord
import ca.danielpower.jstudy.core.MasteryRules
import ca.danielpower.jstudy.core.ReadLevel
import ca.danielpower.jstudy.core.RenshuuImport
import ca.danielpower.jstudy.core.Speech
import ca.danielpower.jstudy.core.RenshuuTerm
import ca.danielpower.jstudy.core.Reviewer
import ca.danielpower.jstudy.core.StudyAhead
import ca.danielpower.jstudy.core.StudyClock
import ca.danielpower.jstudy.core.StudySession
import ca.danielpower.jstudy.core.StudySettings
import ca.danielpower.jstudy.core.WordProgress
import ca.danielpower.jstudy.core.Coverage
import ca.danielpower.jstudy.core.Reader
import ca.danielpower.jstudy.core.ReadingWord
import ca.danielpower.jstudy.core.CastMember
import ca.danielpower.jstudy.core.Chapter
import ca.danielpower.jstudy.core.GrammarTopic
import ca.danielpower.jstudy.core.Candidate
import ca.danielpower.jstudy.core.NextUp
import ca.danielpower.jstudy.core.Pace
import ca.danielpower.jstudy.core.Streak
import ca.danielpower.jstudy.core.Suggest
import ca.danielpower.jstudy.core.WordDay
import ca.danielpower.jstudy.core.WordHistory
import ca.danielpower.jstudy.core.PathSeries
import ca.danielpower.jstudy.core.PathStory
import ca.danielpower.jstudy.core.StepGap
import ca.danielpower.jstudy.core.StepNeeds
import ca.danielpower.jstudy.core.StoryPath
import ca.danielpower.jstudy.core.UiLabel
import ca.danielpower.jstudy.core.UiLabels
import ca.danielpower.jstudy.core.coverage
import ca.danielpower.jstudy.core.sentenceRanges
import kotlin.random.Random

data class Summary(
    val newCards: Int,
    val learning: Int,
    val reviews: Int,
    val streak: Int,
    val daysAhead: Int,
    val reviewedToday: Int,
    val correctToday: Int,
    val wordsStarted: Int,
    val wordsTotal: Int,
    val today: Long,
    /** Cards answered per study day over the last year. */
    val answersPerDay: Map<Long, Int>,
) {
    val anythingDue get() = newCards + learning + reviews > 0
}

/** Everything Home shows: what to do next, and how far you've come. */
data class Dashboard(
    val today: Long,
    val next: NextUp<ChapterEntry>,
    val pace: Pace,
    /** Study days with a review answered or a chapter finished. */
    val activeDays: Set<Long>,
    val streak: Int,
    /** Words seen, known and mastered at the end of each day, up to today. */
    val words: List<WordDay>,
    /** Words started in the last week, newest first, and how many there are in all. */
    val newWords: List<Word>,
    val newWordCount: Int,
    val grammar: List<GrammarRow>,
)

/** Where you left a chapter: its page, in the reader style the pages were laid out with. */
data class ReaderPlace(val storyId: Long, val page: Int, val style: String)

data class WordDetail(
    val word: Word,
    val forms: List<String>,
    /** Part-of-speech names for each sense, in order. */
    val partsOfSpeech: List<String>,
    val cards: List<Card>,
    val mastery: Int,
    val suspended: Boolean,
    val history: List<Pair<Direction, ReviewEntry>>,
    /** The kanji in the word's spelling, in order. */
    val kanji: List<Kanji>,
)

/** A kanji's page: its entry and the words written with it. */
data class KanjiDetail(
    val kanji: Kanji,
    /** Words you've started that are written with it, best known first. */
    val yourWords: List<KanjiWordRow>,
    /** Other common words written with it, in study order. */
    val moreWords: List<Entry>,
    /** The dictionary readings your words use. */
    val readingsMet: Set<String>,
)

/** One of your words on a kanji's page, with the kanji's reading in it (its dictionary form when it has one). */
data class KanjiWordRow(val entry: Entry, val mastery: Int, val reading: String?)

/** Everything needed to take back the last answer. */
data class Undo(
    val cardBefore: Card,
    val progressBefore: WordProgress,
    val logId: Long,
    val productionCardId: Long?,
)

/** A saved story split into words, with what's needed to show each one. Its English title isn't read. */
data class StoryReading(
    val story: SavedStory,
    val paragraphs: List<List<ReadingWord>>,
    /** Who says each paragraph, when it's a line of dialogue. */
    val speakers: List<String?> = paragraphs.map { null },
    /** Dictionary entries for the story's words. */
    val words: Map<Long, Word>,
    val statuses: Map<Long, WordStatus>,
    val coverage: Coverage,
    /** For a grammar topic's page: its English notes between the examples. */
    val lesson: PathStory? = null,
    /** A chapter's English notes, by the paragraph whose end has their marker. */
    val notes: Map<Int, String> = emptyMap(),
    /** The saved page of the grammar topic each grammar point belongs to, by point id. */
    val lessons: Map<String, Long> = emptyMap(),
) {
    /** The word at [i] in paragraph [p]. */
    fun word(p: Int, i: Int): ReadingWord? = paragraphs.getOrNull(p)?.getOrNull(i)

    /** Each paragraph's sentences; the translations follow this order. */
    private val sentences = paragraphs.map(::sentenceRanges)

    /** The sentence holding word [i] of paragraph [p]. */
    fun sentence(p: Int, i: Int): StorySentence? {
        val j = sentences.getOrNull(p)?.indexOfFirst { i in it }?.takeIf { it >= 0 } ?: return null
        val translations = story.translations.takeIf { it.size == sentences.sumOf { s -> s.size } }
        return StorySentence(sentences[p][j], translations?.get(sentences.take(p).sumOf { it.size } + j))
    }
}

/** What a chapter brings that you haven't learned: how many new words, and the grammar topic it brings in. */
data class StepToLearn(val newWords: Int, val topic: GrammarTopic?)

/** A chapter of a story that comes with the app, and what of it you haven't learned. */
data class ChapterEntry(
    val series: PathSeries,
    val chapter: Chapter,
    val story: SavedStory,
    val gap: StepGap,
    /** The grammar topic it teaches, when you haven't seen it yet. */
    val newTopic: GrammarTopic?,
) {
    val ready get() = gap.ready
    val read get() = story.readAt != null

    /** Open, with no new words or grammar: something new to read with nothing to learn. */
    val nothingNew get() = ready && gap.words.isEmpty() && newTopic == null
}

/** A story that comes with the app, with each of its chapters. */
data class SeriesEntry(val series: PathSeries, val chapters: List<ChapterEntry>) {
    val readCount get() = chapters.count { it.read }

    /** The first chapter that's open and unread. */
    val next get() = chapters.firstOrNull { it.ready && !it.read }

    /** On the list once something in it is open, or read. */
    val shown get() = chapters.any { it.ready || it.read }

    /** The people you've met in the chapters you've read, in order. */
    val cast: List<CastMember> get() = chapters.filter { it.read }.flatMap { it.chapter.meets }.distinctBy { it.name }

    /** Whether chapters you haven't read introduce anyone else. */
    val moreCast get() = chapters.any { !it.read && it.chapter.meets.isNotEmpty() }
}

/** Why a chapter is locked, and what would open it. */
data class LockInfo(
    val entry: ChapterEntry,
    /** New words with dictionary entries, and how many it allows. */
    val words: List<Word>,
    /** Grammar it uses that you haven't seen, with the chapters that teach it. */
    val grammar: List<Pair<GrammarTopic, List<ChapterEntry>>>,
    /** Open, unread chapters that have some of the new words, most first, with how many. */
    val sources: List<Pair<ChapterEntry, Int>>,
    /** Reading this story's earlier chapters gives you every one of the new words. */
    val earlierCover: Boolean,
)

/** A grammar topic for the Words tab: whether you've met it, where it's taught, and its page. */
data class GrammarRow(val topic: GrammarTopic, val seen: Boolean, val taughtIn: ChapterEntry?, val pageId: Long)

/** A sentence of a story: its words in their paragraph, and its English if the story has translations. */
/** The story reader's typefaces: two sans serifs and a serif, all bundled under the OFL. */
enum class ReaderFont { SANS, SERIF, ROUNDED }

/** How stories are set: text size in sp, typeface, and whether columns run top to bottom. */
data class ReaderStyle(
    val size: Int = 26,
    val font: ReaderFont = ReaderFont.SANS,
    /** Set top to bottom, right to left, as Japanese books are. */
    val vertical: Boolean = false,
) {
    companion object {
        val SIZES = listOf(22, 26, 30, 34)
    }
}

data class StorySentence(val words: IntRange, val translation: String?)

/** Numbers for the stats screen. */
data class Stats(
    val today: Long,
    /** Reviews due on each of the next 30 days; today includes anything overdue and still learning. */
    val forecast: List<Int>,
    /** Answers and right answers on each of the last 30 days, oldest first. */
    val answers: List<Pair<Int, Int>>,
    /** Right and total answers on cards in review, over the last 30 days and ever. */
    val recall30: Pair<Int, Int>,
    val recallAll: Pair<Int, Int>,
    val levels: List<LevelStats>,
    val wordsStarted: Int,
    val totalAnswers: Int,
    val studyDays: Int,
)

/** Words of one JLPT level by how far along they are. Each count includes the ones after it. */
data class LevelStats(val label: String, val total: Int, val started: Int, val known: Int, val mastered: Int)

/** The daily reminder. There's no default time: [minuteOfDay] is null until you pick one. */
data class Reminder(val enabled: Boolean, val minuteOfDay: Int?) {
    val time: Int? get() = minuteOfDay?.takeIf { enabled }
}

class BackupException(message: String) : Exception(message)

class Repository(context: Context) {
    private val appContext = context.applicationContext
    val dictionary = Dictionary(context)
    private val store = ProgressStore(context).also {
        // Where the story server sign-in was kept, before Claude stories were removed.
        context.deleteSharedPreferences("secrets")
    }

    private val reader by lazy { Reader(dictionary) }

    /**
     * The words each UI label needs, kept between launches so labels are in
     * the right language from the first frame. Derived, so not in a backup.
     */
    private val labels = context.getSharedPreferences("labels", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ settings

    fun settings(): StudySettings {
        val d = StudySettings()
        fun int(key: String, default: Int) = store.getValue(key)?.toIntOrNull() ?: default
        return runCatching {
            StudySettings(
                newPerDay = int("daily_new", d.newPerDay),
                reviewNudge = int("review_nudge", d.reviewNudge),
                reviewCap = int("review_cap", d.reviewCap),
                rolloverHour = int("rollover_hour", d.rolloverHour),
            )
        }.getOrDefault(d)
    }

    fun saveSettings(s: StudySettings) {
        store.setValues(
            mapOf(
                "daily_new" to s.newPerDay.toString(),
                "review_nudge" to s.reviewNudge.toString(),
                "review_cap" to s.reviewCap.toString(),
                "rollover_hour" to s.rolloverHour.toString(),
            ),
        )
    }

    private fun ahead(): StudyAhead = StudyAhead(
        daysAhead = store.getValue("days_ahead")?.toIntOrNull() ?: 0,
        day = store.getValue("ahead_day")?.toLongOrNull() ?: 0,
        extraDaysToday = store.getValue("ahead_extra")?.toIntOrNull() ?: 0,
    )

    private fun saveAhead(a: StudyAhead) {
        store.setValues(
            mapOf(
                "days_ahead" to a.daysAhead.toString(),
                "ahead_day" to a.day.toString(),
                "ahead_extra" to a.extraDaysToday.toString(),
            ),
        )
    }

    fun clock(settings: StudySettings = settings()) = StudyClock(rolloverHour = settings.rolloverHour)

    // ------------------------------------------------------------ queue

    private fun limits(settings: StudySettings, clock: StudyClock, now: Long): DailyLimits {
        val today = clock.dayOf(now)
        return DailyLimits.of(
            settings = settings,
            ahead = ahead(),
            clock = clock,
            now = now,
            // Production cards fill what today's new words leave of the daily limit.
            newIntroducedToday = store.count("SELECT COUNT(*) FROM card WHERE introduced_day = ?", today),
            reviewsDoneToday = store.count(
                "SELECT COUNT(*) FROM review_log WHERE day = ? AND state_before = ?",
                today,
                CardState.REVIEW.value,
            ),
        )
    }

    fun session(now: Long): StudySession {
        val settings = settings()
        val clock = clock(settings)
        val limits = limits(settings, clock, now)
        val learning = store.cards(
            "state IN (?, ?) AND suspended = 0",
            arrayOf(CardState.LEARNING.value.toString(), CardState.RELEARNING.value.toString()),
            "due_at",
        )
        val reviews = store.cards(
            "state = ? AND due_at < ? AND suspended = 0",
            arrayOf(CardState.REVIEW.value.toString(), limits.reviewDueBefore.toString()),
            "due_at, id",
            limits.reviews,
        )
        // New words come from the story path, not reviews; only unlocked production cards are new here.
        val newWords = store.cards(
            "state = ? AND direction = ? AND suspended = 0",
            arrayOf(CardState.NEW.value.toString(), Direction.PRODUCTION.value.toString()),
            "sort_key, id",
            limits.newCards,
        )
        return StudySession(reviews, newWords, learning, random = Random.Default)
    }

    /**
     * The new words you've started today, against the daily limit,
     * and the reviews due today. "I know these" and imports start nothing,
     * so they don't count.
     */
    fun pace(now: Long): Pace {
        val settings = settings()
        val clock = clock(settings)
        val today = clock.dayOf(now)
        return Pace(
            newToday = store.count(
                "SELECT COUNT(*) FROM card WHERE introduced_day = ? AND direction != ?",
                today,
                Direction.PRODUCTION.value,
            ),
            limit = settings.newPerDay,
            reviewsDue = store.count(
                "SELECT COUNT(*) FROM card WHERE state = ? AND due_at < ? AND suspended = 0",
                CardState.REVIEW.value,
                clock.startOf(today + 1),
            ),
            reviewNudge = settings.reviewNudge,
        )
    }

    fun endOfToday(now: Long): Long {
        val clock = clock()
        return clock.startOf(clock.dayOf(now) + 1)
    }

    // ------------------------------------------------------------ answers

    fun progress(wordId: Long): WordProgress = store.progress(wordId)

    fun answer(card: Card, correct: Boolean, now: Long): Pair<Answer, Undo> {
        val settings = settings()
        val reviewer = Reviewer(Fsrs(), clock(settings))
        val before = store.progress(card.wordId)
        return save(card, before, reviewer.answer(card, before, correct, now, Random.Default), now)
    }

    private fun save(card: Card, before: WordProgress, answer: Answer, now: Long): Pair<Answer, Undo> =
        store.transaction { db ->
            store.updateCard(db, answer.card)
            store.saveProgress(db, answer.progress)
            val logId = store.insertLog(db, answer.log)
            val production = if (answer.unlockedProduction) {
                // Unlocked production cards come before any new word, oldest first.
                store.insertNewCard(db, card.wordId, Direction.PRODUCTION, now / 1000 - PRODUCTION_FIRST)
                    .takeIf { it > 0 }
            } else {
                null
            }
            answer to Undo(card, before, logId, production)
        }

    /** First sight of a word you don't know yet: start learning it, ungraded. */
    private fun introduce(card: Card, now: Long) {
        val reviewer = Reviewer(Fsrs(), clock(settings()))
        store.transaction { db -> store.updateCard(db, reviewer.introduce(card, now)) }
    }

    /** Mark a word you already know at [mastery]. */
    private fun markKnown(card: Card, mastery: Int, now: Long) {
        val settings = settings()
        val reviewer = Reviewer(Fsrs(), clock(settings))
        val before = store.progress(card.wordId)
        val known = reviewer.markKnown(card, before, mastery, now, Random.Default)
        store.transaction { db ->
            store.updateCard(db, known.card)
            store.saveProgress(db, known.progress)
            if (known.unlockedProduction) {
                store.insertNewCard(db, card.wordId, Direction.PRODUCTION, now / 1000 - PRODUCTION_FIRST)
            }
        }
    }

    fun undo(undo: Undo) {
        store.transaction { db ->
            store.updateCard(db, undo.cardBefore)
            store.saveProgress(db, undo.progressBefore)
            store.deleteLog(db, undo.logId)
            undo.productionCardId?.let { store.deleteCard(db, it) }
        }
    }

    fun studyMore(now: Long) {
        val clock = clock()
        saveAhead(ahead().studyMore(clock.dayOf(now)))
    }

    // ------------------------------------------------------------ dictionary

    fun wordStatuses(): Map<Long, WordStatus> = store.wordStatuses()

    fun wordDetail(wordId: Long): WordDetail? {
        val word = dictionary.word(wordId) ?: return null
        val names = dictionary.partOfSpeechNames()
        return WordDetail(
            word = word,
            forms = dictionary.forms(wordId),
            partsOfSpeech = word.senses.map { sense -> sense.partsOfSpeech.joinToString(", ") { names[it] ?: it } },
            cards = store.cards("word_id = ?", arrayOf(wordId.toString()), "direction"),
            mastery = store.progress(wordId).mastery,
            suspended = store.isSuspended(wordId),
            history = store.logs(wordId),
            kanji = kanjiIn(word),
        )
    }

    /** The card mastery follows. */
    private fun recognitionCard(wordId: Long): Card? =
        store.cards(
            "word_id = ? AND direction = ?",
            arrayOf(wordId.toString(), Direction.RECOGNITION.value.toString()),
            "id",
        ).firstOrNull()

    /** Mark a word from the dictionary as known at [mastery], starting it if needed. */
    fun markKnown(wordId: Long, mastery: Int, now: Long) {
        store.transaction { db ->
            store.setSuspended(db, wordId, false)
            if (recognitionCard(wordId) == null) {
                store.insertNewCard(db, wordId, Direction.RECOGNITION, 0)
            }
        }
        markKnown(recognitionCard(wordId) ?: return, mastery, now)
    }

    /** Forget a word: its cards, mastery and history. */
    fun reset(wordId: Long) {
        store.transaction { db -> store.deleteWord(db, wordId) }
    }

    fun setSuspended(wordId: Long, suspended: Boolean) {
        store.transaction { db ->
            if (suspended && recognitionCard(wordId) == null) {
                // Suspension lives on the card, so an unstarted word gets one.
                store.insertNewCard(db, wordId, Direction.RECOGNITION, 0, suspended = true)
            }
            store.setSuspended(db, wordId, suspended)
            if (!suspended) {
                // An unstarted word goes back to having no card.
                val card = recognitionCard(wordId)
                if (card != null && card.state == CardState.NEW && card.reps == 0) store.deleteCard(db, card.id)
            }
        }
    }

    // ------------------------------------------------------------ kanji

    /** The kanji in a word's spelling, in order; none for a word shown in kana. */
    fun kanjiIn(word: Word): List<Kanji> =
        if (word.showsKanji) dictionary.kanji(Kanjis.inText(word.display)) else emptyList()

    fun kanjiDetail(literal: String): KanjiDetail? {
        val kanji = dictionary.kanji(literal) ?: return null
        // Words you've started and not set aside that are written with it.
        val started = store.wordStatuses().filter { (_, st) -> st.state != CardState.NEW && !st.suspended }
        val mine = dictionary.kanjiSpellings(started.keys).mapNotNull { (id, segments) ->
            Kanjis.inWord(segments).firstOrNull { it.kanji == literal }?.let { id to it }
        }
        val entries = dictionary.entries(mine.map { it.first }).associateBy { it.id }
        val yourWords = mine.mapNotNull { (id, use) ->
            val entry = entries[id] ?: return@mapNotNull null
            val reading = use.reading?.let { Kanjis.dictionaryReading(kanji, it, use.after) ?: it }
            KanjiWordRow(entry, started.getValue(id).mastery, reading)
        }.sortedByDescending { it.mastery }
        val yours = yourWords.mapTo(HashSet()) { it.entry.id }
        return KanjiDetail(
            kanji = kanji,
            yourWords = yourWords,
            moreWords = dictionary.wordsWithKanji(literal, MORE_WORDS + yours.size).filter { it.id !in yours }.take(MORE_WORDS),
            readingsMet = yourWords.mapNotNullTo(HashSet()) { it.reading },
        )
    }

    // ------------------------------------------------------------ renshuu

    /** What importing [terms] would change. */
    fun renshuuPlan(terms: List<RenshuuTerm>): ImportPlan {
        val ids = dictionary.wordIds()
        val local = store.wordStatuses().mapValues { (_, s) ->
            LocalWord(s.mastery, started = s.state != CardState.NEW, suspended = s.suspended)
        }
        return RenshuuImport.plan(terms, { it in ids }, local)
    }

    /** Mark each word known at its mastery, as "Mark known" does, in one transaction. */
    fun importMastery(updates: Map<Long, Int>, now: Long) {
        val settings = settings()
        val reviewer = Reviewer(Fsrs(), clock(settings))
        store.transaction { db ->
            for ((wordId, mastery) in updates) {
                if (recognitionCard(wordId) == null) {
                    store.insertNewCard(db, wordId, Direction.RECOGNITION, 0)
                }
                val card = recognitionCard(wordId) ?: continue
                val known = reviewer.markKnown(card, store.progress(wordId), mastery, now, Random.Default)
                store.updateCard(db, known.card)
                store.saveProgress(db, known.progress)
                if (known.unlockedProduction) {
                    store.insertNewCard(db, wordId, Direction.PRODUCTION, now / 1000 - PRODUCTION_FIRST)
                }
            }
        }
    }

    // ------------------------------------------------------------ app language

    /** The label words looked up before, unless the dictionary has changed since. */
    fun cachedLabelWords(): Map<String, List<Long>?> {
        if (labels.getLong(LABELS_VERSION, -1) != dictionary.version) return emptyMap()
        return labels.all.filterKeys { it != LABELS_VERSION }.mapValues { (_, v) ->
            (v as? String)?.takeIf { it.isNotEmpty() }?.split(',')?.map { it.toLong() }
        }
    }

    /** Look up the words in [found] labels, and remember them. */
    fun labelWords(found: Collection<UiLabel>): Map<String, List<Long>?> {
        val words = found.associate { it.key to UiLabels.words(it, reader, dictionary) }
        val edit = labels.edit()
        if (labels.getLong(LABELS_VERSION, -1) != dictionary.version) edit.clear().putLong(LABELS_VERSION, dictionary.version)
        for ((key, ids) in words) edit.putString(key, ids?.joinToString(",").orEmpty())
        edit.apply()
        return words
    }

    /** Words mastered well enough for the app to use them: the mastery where furigana turns off. */
    fun masteredWords(): Set<Long> = store.wordsAtMastery(MasteryRules.OFF_AT)

    // ------------------------------------------------------------ stories

    /** How the story reader sets text, chosen in its own settings menu. */
    fun readerStyle(): ReaderStyle {
        val default = ReaderStyle()
        return ReaderStyle(
            size = store.getValue("story_text_size")?.toIntOrNull()?.takeIf { it in ReaderStyle.SIZES } ?: default.size,
            font = ReaderFont.entries.firstOrNull { it.name == store.getValue("story_font") } ?: default.font,
            vertical = store.getValue("story_vertical") == "1",
        )
    }

    fun setReaderStyle(style: ReaderStyle) {
        store.setValues(
            mapOf(
                "story_text_size" to style.size.toString(),
                "story_font" to style.font.name,
                "story_vertical" to if (style.vertical) "1" else "0",
            ),
        )
    }

    fun story(id: Long): SavedStory? = store.story(id)

    /** Words you've started, or suspended to leave alone, count as known while reading. */
    private fun known(statuses: Map<Long, WordStatus>): (Long) -> Boolean = { id ->
        val s = statuses[id]
        s != null && (s.state != CardState.NEW || s.suspended)
    }

    fun markStoryRead(id: Long, now: Long, millis: Long) = store.markStoryRead(id, now, millis)

    fun readStory(id: Long): StoryReading? {
        val story = store.story(id) ?: return null
        val paragraphs = reader.read(story.body, readings(story), names(story))
        val ids = paragraphs.flatten().mapNotNull { it.wordId }.toSet()
        val statuses = store.wordStatuses()
        return StoryReading(
            story = story,
            paragraphs = paragraphs,
            speakers = Speech.speakers(story.body),
            words = ids.mapNotNull { dictionary.word(it) }.associateBy { it.id },
            statuses = statuses,
            coverage = coverage(paragraphs, known(statuses)),
            lesson = topicOfPage(story)?.page,
            notes = notes(story),
            lessons = lessons(),
        )
    }

    /** The bundled chapter [story] was saved from, if it's one. */
    private fun chapterOf(story: SavedStory): PathStory? =
        catalog.chapters.firstOrNull { it.second.story.id == story.pathId }?.second?.story

    /** A chapter's notes, each shown from the end of the paragraph before it; two after one paragraph share its marker. */
    private fun notes(story: SavedStory): Map<Int, String> =
        chapterOf(story)?.notes.orEmpty()
            .groupBy({ it.before - 1 }, { it.text })
            .mapValues { it.value.joinToString("\n\n") }

    /** The words the reader gets wrong in a chapter, pinned to their readings. */
    private fun readings(story: SavedStory): Map<String, String> = chapterOf(story)?.readings.orEmpty()

    /** The cast names a chapter or grammar page reads as names, not words. */
    private fun names(story: SavedStory): Set<String> = (chapterOf(story) ?: topicOfPage(story)?.page)?.names.orEmpty()

    /** Each grammar point's topic page, saved when the path was. */
    private fun lessons(): Map<String, Long> {
        val saved = store.pathStories().mapValues { it.value.id }
        return catalog.grammar.flatMap { t -> saved[t.page.id]?.let { id -> t.points.map { it to id } }.orEmpty() }.toMap()
    }

    /** The grammar topic whose page [story] is, if it's one. */
    fun topicOfPage(story: SavedStory): GrammarTopic? = catalog.grammar.firstOrNull { it.page.id == story.pathId }

    // ------------------------------------------------------------ story path

    /** The stories and grammar topics that come with the app. */
    private val catalog by lazy {
        StoryPath.parse(appContext.assets.open("stories/path.json").bufferedReader().use { it.readText() })
    }

    /**
     * What each chapter asks of you, by its id. The story tools work it out
     * when the stories change (core StoryTools), so the app doesn't read every chapter.
     */
    private val needs: Map<String, StepNeeds> by lazy {
        StoryPath.parseNeeds(appContext.assets.open("stories/needs.json").bufferedReader().use { it.readText() }, catalog)
    }
    private var pathSynced = false

    /** Saves the chapters and grammar pages on first use, so they can be opened like any story. */
    private fun savedPath(now: Long): Map<String, SavedStory> {
        if (!pathSynced) {
            val stories = catalog.chapters.map { it.second.story } + catalog.grammar.map { it.page }
            store.syncPathStories(stories.map { p -> SavedStory(0, p.id, p.title, p.body, p.translations) })
            pathSynced = true
        }
        return store.pathStories()
    }

    /** What's left of a step to learn: words you haven't started, and grammar you haven't seen. */
    private fun gaps(): (StepNeeds) -> StepGap {
        val isKnown = known(store.wordStatuses())
        val grammar = seenGrammar()
        return { needs -> StoryPath.gap(needs, isKnown, grammar) }
    }

    /** Every story that comes with the app, in order, with each chapter and what you haven't learned of it. */
    fun storyPath(now: Long): List<SeriesEntry> {
        val saved = savedPath(now)
        val gap = gaps()
        val seen = seenGrammar()
        return catalog.series.map { series ->
            SeriesEntry(series, series.chapters.mapNotNull { c ->
                val story = saved[c.story.id] ?: return@mapNotNull null
                val topic = catalog.topic(c.topic)?.takeIf { !seen.containsAll(it.points) }
                ChapterEntry(series, c, story, gap(needs.getValue(c.story.id)), topic)
            })
        }
    }

    /** The story a saved chapter belongs to, and the chapter. */
    fun chapterOf(story: SavedStory, now: Long): ChapterEntry? =
        storyPath(now).flatMap { it.chapters }.firstOrNull { it.chapter.story.id == story.pathId }

    /** How many words in a chapter you haven't learned yet, and its new grammar, or null off the path. */
    fun stepToLearn(story: SavedStory, now: Long): StepToLearn? {
        val entry = chapterOf(story, now) ?: return null
        return StepToLearn(entry.gap.words.count { it.wordId != null }, entry.newTopic)
    }

    /** Why [chapterId] is locked: what it's missing, and where to pick that up. */
    fun lockInfo(chapterId: String, now: Long): LockInfo? {
        val path = storyPath(now)
        val chapters = path.flatMap { it.chapters }
        val entry = chapters.firstOrNull { it.chapter.story.id == chapterId } ?: return null
        val missing = entry.gap.words.mapNotNull { it.wordId }.toSet()
        val grammar = entry.gap.grammar.mapNotNull { catalog.topicOf(it) }.distinct().map { topic ->
            topic to chapters.filter { it.chapter.topic == topic.id }
        }
        val sources = chapters
            .filter { it.ready && !it.read && it !== entry }
            .map { c -> c to needs.getValue(c.chapter.story.id).words.count { it.wordId in missing } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
        val earlier = entry.series.chapters.takeWhile { it !== entry.chapter }
            .filter { c -> chapters.firstOrNull { it.chapter === c }?.read == false }
            .flatMap { c -> needs.getValue(c.story.id).words.mapNotNull { it.wordId } }
            .toSet()
        return LockInfo(
            entry = entry,
            words = entry.gap.words.mapNotNull { w -> w.wordId?.let { dictionary.word(it) } },
            grammar = grammar,
            sources = sources,
            earlierCover = missing.isNotEmpty() && earlier.containsAll(missing),
        )
    }

    /** Every grammar topic, newest you've met first, then the ones you haven't, in the order they come. */
    fun grammarRows(now: Long): List<GrammarRow> {
        val saved = savedPath(now)
        val seen = seenGrammar()
        val chapters = storyPath(now).flatMap { it.chapters }
        val rows = catalog.grammar.mapNotNull { topic ->
            val page = saved[topic.page.id] ?: return@mapNotNull null
            val taughtIn = chapters.filter { it.chapter.topic == topic.id }.let { c -> c.firstOrNull { it.read } ?: c.firstOrNull() }
            GrammarRow(topic, seen.containsAll(topic.points), taughtIn, page.id)
        }
        val (met, unmet) = rows.partition { it.seen }
        return met.reversed() + unmet
    }

    /** The saved story holding [topic]'s page. */
    fun grammarPageId(topic: GrammarTopic, now: Long): Long? = savedPath(now)[topic.page.id]?.id

    /** Grammar points you've been taught, or said you knew. */
    fun seenGrammar(): Set<String> =
        store.getValue("grammar_seen")?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    fun markGrammarSeen(ids: Collection<String>) {
        store.setValues(mapOf("grammar_seen" to (seenGrammar() + ids).sorted().joinToString(",")))
    }

    /**
     * Finishing a path story: each word due for review that you read without
     * looking it up counts as a lighter review than a Right answer ([Reviewer.read]).
     */
    fun readWords(story: SavedStory, lookedUp: Set<Long>, now: Long) {
        val reviewer = Reviewer(Fsrs(), clock())
        val ids = StoryPath.words(reader, story.body, readings(story), names(story)).mapNotNull { it.wordId }
        for (id in ids.distinct() - lookedUp) {
            val card = recognitionCard(id) ?: continue
            if (card.state != CardState.REVIEW || card.dueAt > now || store.isSuspended(id)) continue
            val before = store.progress(id)
            save(card, before, reviewer.read(card, before, now, Random.Default), now)
        }
    }

    /** Finishing a story: each of [ids] you said you forgot gets a Wrong review. */
    fun forgetWords(ids: Set<Long>, now: Long) {
        for (id in ids) {
            val card = recognitionCard(id) ?: continue
            if (card.state != CardState.REVIEW || store.isSuspended(id)) continue
            answer(card, false, now)
        }
    }

    /**
     * Finishing a story: its words you haven't started, or said you know,
     * start as cards, as though just shown. Returns their ids.
     */
    fun startStoryWords(story: SavedStory, now: Long): List<Long> {
        val isKnown = known(store.wordStatuses())
        val ids = StoryPath.words(reader, story.body, readings(story), names(story)).mapNotNull { it.wordId }.distinct()
            .filter { !isKnown(it) && dictionary.word(it) != null }
        for (id in ids) start(id, now)
        return ids
    }

    /**
     * A word a story just started that you already know: known now, and no
     * longer counted against today's new words.
     */
    fun knowStartedWord(wordId: Long, now: Long) {
        markKnown(wordId, MasteryRules.HIDE_AT, now)
        val card = recognitionCard(wordId) ?: return
        store.transaction { db -> store.updateCard(db, card.copy(introducedDay = null)) }
    }

    /** Undo [knowStartedWord]: new again, as the story started it. */
    fun unknowStartedWord(wordId: Long, now: Long) {
        reset(wordId)
        start(wordId, now)
    }

    private fun start(wordId: Long, now: Long) {
        store.transaction { db -> if (recognitionCard(wordId) == null) store.insertNewCard(db, wordId, Direction.RECOGNITION, 0) }
        recognitionCard(wordId)?.takeIf { it.state == CardState.NEW }?.let { introduce(it, now) }
    }

    /** Mastery of each of [ids] you've started. */
    fun masteries(ids: Collection<Long>): Map<Long, Int> {
        val statuses = store.wordStatuses()
        return ids.mapNotNull { id -> statuses[id]?.takeIf { it.state != CardState.NEW }?.let { id to it.mastery } }.toMap()
    }

    // ------------------------------------------------------------ first run

    /** The JLPT level you can already read: 5 for N5 down to 1 for N1, or [ReadLevel.NONE]. */
    fun readLevel(): Int = store.getValue("read_level")?.toIntOrNull()?.takeIf { it in 1..5 } ?: ReadLevel.NONE

    /**
     * Read at [level] from now on. Raising it fills in its words and every
     * easier level's ([ReadLevel]); lowering it changes nothing else.
     */
    fun setReadLevel(level: Int, now: Long) {
        val before = readLevel()
        store.setValues(mapOf("read_level" to level.toString()))
        val raised = level != ReadLevel.NONE && (before == ReadLevel.NONE || level < before)
        if (raised) fillReadLevel(level, now)
    }

    /**
     * Start the words of [level] and easier as known, each at its
     * [ReadLevel] stability. Words you've studied keep their progress where
     * it's higher, and suspended words are left alone. English-to-Japanese
     * cards wait until each word is next answered right, so the batch
     * doesn't crowd reviews with new cards.
     */
    private fun fillReadLevel(level: Int, now: Long) {
        val statuses = store.wordStatuses()
        val fill = dictionary.levels().mapNotNull { (id, jlpt) ->
            val days = ReadLevel.stabilityDays(level, jlpt) ?: return@mapNotNull null
            val status = statuses[id]
            val skip = status != null && (status.suspended || status.mastery >= MasteryRules.fromStability(days.toDouble()))
            if (skip) null else id to days
        }
        val reviewer = Reviewer(Fsrs(), clock())
        store.transaction { db ->
            for ((id, days) in fill) {
                if (recognitionCard(id) == null) store.insertNewCard(db, id, Direction.RECOGNITION, 0)
                val card = recognitionCard(id) ?: continue
                val before = store.progress(id)
                val within = ReadLevel.firstDueWithin(days, fill.size)
                val known = reviewer.markKnownFor(card, before, days, within, now, Random.Default)
                store.updateCard(db, known.card)
                store.saveProgress(db, known.progress.copy(productionUnlocked = before.productionUnlocked))
            }
        }
    }

    /** True on a fresh install, before you've been through setup. Existing progress counts as set up. */
    fun needsSetup(): Boolean {
        if (store.getValue("setup_done") == "1") return false
        val started = store.count("SELECT COUNT(*) FROM card WHERE state != ?", CardState.NEW.value) +
            store.count("SELECT COUNT(*) FROM review_log")
        if (started > 0) {
            store.setValues(mapOf("setup_done" to "1"))
            return false
        }
        return true
    }

    fun levelCounts(): Map<Int, Int> = dictionary.levelCounts()

    /** Finish the first run: the level you read at, filled in, and the reminder. */
    fun finishSetup(readLevel: Int, reminderMinute: Int?, now: Long) {
        if (reminderMinute != null) saveReminder(Reminder(enabled = true, minuteOfDay = reminderMinute))
        setReadLevel(readLevel, now)
        store.setValues(mapOf("setup_done" to "1"))
    }

    // ------------------------------------------------------------ backup

    /** Write a copy of all your progress, settings and stories. The story sign-in isn't included. */
    fun exportBackup(out: OutputStream) {
        store.checkpoint()
        appContext.getDatabasePath(ProgressStore.NAME).inputStream().use { it.copyTo(out) }
    }

    /** Replace all progress with a backup. The file is checked before anything is replaced. */
    fun importBackup(input: InputStream) {
        val incoming = File(appContext.cacheDir, "import.db")
        try {
            incoming.outputStream().use { input.copyTo(it) }
            checkBackup(incoming)
            store.close()
            val target = appContext.getDatabasePath(ProgressStore.NAME)
            for (suffix in listOf("-wal", "-shm", "-journal")) File(target.path + suffix).delete()
            incoming.copyTo(target, overwrite = true)
        } finally {
            incoming.delete()
        }
        // The next query reopens the database, upgrading an older backup's schema.
        store.count("SELECT COUNT(*) FROM card")
    }

    private fun checkBackup(file: File) {
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            throw BackupException("That file isn't a Jiwa Jiwa backup.")
        }
        db.use {
            val tables = it.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            if (!tables.containsAll(listOf("card", "word_progress", "review_log", "kv"))) {
                throw BackupException("That file isn't a Jiwa Jiwa backup.")
            }
            if (it.version > ProgressStore.VERSION) {
                throw BackupException("That backup is from a newer version of Jiwa Jiwa. Update the app first.")
            }
        }
    }

    // ------------------------------------------------------------ reset

    /**
     * Erase everything you've done here: cards, mastery, history, settings
     * and stories read. The next query recreates an empty database, so the
     * app starts again at first-run setup.
     */
    fun resetAll() {
        store.close()
        val target = appContext.getDatabasePath(ProgressStore.NAME)
        SQLiteDatabase.deleteDatabase(target)
        store.count("SELECT COUNT(*) FROM card")
    }

    // ------------------------------------------------------------ reminder

    fun reminder(): Reminder = Reminder(
        enabled = store.getValue("reminder_enabled") == "1",
        minuteOfDay = store.getValue("reminder_minute")?.toIntOrNull()?.coerceIn(0, 24 * 60 - 1),
    )

    fun saveReminder(r: Reminder) {
        store.setValues(
            mapOf(
                "reminder_enabled" to if (r.enabled) "1" else "0",
            ) + (r.minuteOfDay?.let { mapOf("reminder_minute" to it.toString()) } ?: emptyMap()),
        )
    }

    // ------------------------------------------------------------ stats

    fun stats(now: Long): Stats {
        val settings = settings()
        val clock = clock(settings)
        val today = clock.dayOf(now)
        val forecast = IntArray(FORECAST_DAYS)
        for (dueAt in store.reviewDueTimes(clock.startOf(today + FORECAST_DAYS))) {
            forecast[(clock.dayOf(dueAt) - today).toInt().coerceIn(0, FORECAST_DAYS - 1)]++
        }
        forecast[0] += store.count(
            "SELECT COUNT(*) FROM card WHERE state IN (?, ?) AND suspended = 0",
            CardState.LEARNING.value,
            CardState.RELEARNING.value,
        )
        val perDay = store.answersAndRightPerDay(today - (HISTORY_DAYS - 1))
        val answers = (0 until HISTORY_DAYS).map { perDay[today - (HISTORY_DAYS - 1) + it] ?: (0 to 0) }

        val levelOf = dictionary.levels()
        val counts = dictionary.levelCounts()
        val statuses = store.wordStatuses()
        val levels = (5 downTo 1).map { level ->
            val mine = statuses.filter { (id, st) -> levelOf[id] == level && st.state != CardState.NEW && !st.suspended }.values
            LevelStats(
                label = "N$level",
                total = counts[level] ?: 0,
                started = mine.size,
                known = mine.count { it.mastery >= MasteryRules.HIDE_AT },
                mastered = mine.count { it.mastery >= MasteryRules.OFF_AT },
            )
        }
        return Stats(
            today = today,
            forecast = forecast.toList(),
            answers = answers,
            recall30 = store.reviewRecall(today - (HISTORY_DAYS - 1)),
            recallAll = store.reviewRecall(Long.MIN_VALUE),
            levels = levels,
            wordsStarted = statuses.values.count { it.state != CardState.NEW },
            totalAnswers = store.count("SELECT COUNT(*) FROM review_log"),
            studyDays = store.studyDays().size,
        )
    }

    // ------------------------------------------------------------ home

    fun dashboard(now: Long): Dashboard {
        val clock = clock()
        val today = clock.dayOf(now)
        val active = activeDays(clock)
        val started = store.startedWords()
        // A word that came in with mastery has no real first day, even once it's reviewed.
        val firstDays = started.associate { w -> w.wordId to w.introducedDay?.takeIf { (w.firstBefore ?: 0) == 0 } }
        val firsts = listOfNotNull(firstDays.values.filterNotNull().minOrNull(), active.minOrNull())
        val from = minOf(firsts.minOrNull() ?: today, today - MIN_GRAPH_DAYS + 1)
        val words = WordHistory.days(firstDays, started.associate { it.wordId to it.mastery }, store.masteryChanges(), from, today)
        val recent = firstDays.filterValues { it != null && it > today - NEW_WORD_DAYS }.entries
            .sortedWith(compareByDescending<Map.Entry<Long, Long?>> { it.value }.thenByDescending { it.key })
        return Dashboard(
            today = today,
            next = nextUp(now),
            pace = pace(now),
            activeDays = active,
            streak = Streak.current(active, today),
            words = words,
            newWords = recent.take(NEW_WORDS_SHOWN).mapNotNull { dictionary.word(it.key) },
            newWordCount = recent.size,
            grammar = grammarRows(now),
        )
    }

    /** Study days with a review answered or a chapter finished. */
    private fun activeDays(clock: StudyClock): Set<Long> = (store.studyDays() + store.storyReadTimes().map(clock::dayOf)).toSet()

    /** What Home suggests: see [Suggest.next]. */
    private fun nextUp(now: Long): NextUp<ChapterEntry> {
        val path = storyPath(now)
        val chapters = path.flatMap { it.chapters }
        val place = readerPlace()
        val open = chapters.filter { it.ready && !it.read }
        val resumed = open.firstOrNull { it.story.id == place?.storyId }
        val lastSeries = chapters.filter { it.read }.maxByOrNull { it.story.readAt ?: 0 }?.series?.id
        // The chapter you left part way, else the next in the story you read last, else the first open on the path.
        val current = resumed
            ?: path.firstOrNull { it.series.id == lastSeries }?.next
            ?: path.firstOrNull { !it.series.side && it.next != null }?.next
            ?: open.firstOrNull()
        val side = open.firstOrNull { it.nothingNew && it.story.id != current?.story?.id }
        fun candidate(entry: ChapterEntry) = Candidate(
            entry,
            newWords = entry.gap.words.size,
            teachesGrammar = entry.newTopic != null,
            resumePage = place?.takeIf { it.storyId == entry.story.id }?.page ?: 0,
        )
        val summary = summary(now)
        return Suggest.next(
            pace(now),
            due = summary.newCards + summary.learning + summary.reviews,
            current = current?.let(::candidate),
            side = side?.let(::candidate),
            anyRead = chapters.any { it.read },
        )
    }

    fun readerPlace(): ReaderPlace? {
        val parts = store.getValue("reader_place")?.split(':', limit = 3) ?: return null
        if (parts.size < 3) return null
        return ReaderPlace(parts[0].toLongOrNull() ?: return null, parts[1].toIntOrNull() ?: return null, parts[2])
    }

    fun saveReaderPlace(place: ReaderPlace) = store.setValues(mapOf("reader_place" to "${place.storyId}:${place.page}:${place.style}"))

    fun clearReaderPlace() = store.deleteValue("reader_place")

    // ------------------------------------------------------------ summary

    fun summary(now: Long): Summary {
        val settings = settings()
        val clock = clock(settings)
        val today = clock.dayOf(now)
        val limits = limits(settings, clock, now)
        val reviewsDue = store.count(
            "SELECT COUNT(*) FROM card WHERE state = ? AND due_at < ? AND suspended = 0",
            CardState.REVIEW.value,
            limits.reviewDueBefore,
        )
        val started = store.count("SELECT COUNT(*) FROM card WHERE direction = ?", Direction.RECOGNITION.value)
        val total = dictionary.wordCount()
        // New words come from the story path, so only unlocked production cards wait here.
        val newWaiting = store.count(
            "SELECT COUNT(*) FROM card WHERE state = ? AND direction = ? AND suspended = 0",
            CardState.NEW.value,
            Direction.PRODUCTION.value,
        )
        return Summary(
            newCards = minOf(limits.newCards, newWaiting),
            learning = store.count(
                "SELECT COUNT(*) FROM card WHERE state IN (?, ?) AND suspended = 0",
                CardState.LEARNING.value,
                CardState.RELEARNING.value,
            ),
            reviews = minOf(limits.reviews, reviewsDue),
            streak = Streak.current(activeDays(clock), today),
            daysAhead = ahead().daysAhead,
            reviewedToday = store.count("SELECT COUNT(*) FROM review_log WHERE day = ?", today),
            correctToday = store.count("SELECT COUNT(*) FROM review_log WHERE day = ? AND correct = 1", today),
            wordsStarted = store.count(
                "SELECT COUNT(*) FROM card WHERE direction = ? AND state != ?",
                Direction.RECOGNITION.value,
                CardState.NEW.value,
            ).coerceAtMost(started),
            wordsTotal = total,
            today = today,
            answersPerDay = store.answersPerDay(today - 370),
        )
    }

    companion object {
        private const val PRODUCTION_FIRST = 10_000_000_000L
        private const val FORECAST_DAYS = 30
        private const val HISTORY_DAYS = 30

        /** The words graph covers at least this many days, so a new start isn't a single point. */
        private const val MIN_GRAPH_DAYS = 7

        /** Words started within this many days are new on Home. */
        private const val NEW_WORD_DAYS = 7
        private const val NEW_WORDS_SHOWN = 6

        /** Words you haven't started to list on a kanji's page. */
        private const val MORE_WORDS = 12

        /** Never a label key, which is Japanese text. */
        private const val LABELS_VERSION = "@dictionary"
    }
}
