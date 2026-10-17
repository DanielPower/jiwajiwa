package ca.danielpower.jstudy.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.danielpower.jstudy.core.Card
import ca.danielpower.jstudy.core.CardState
import ca.danielpower.jstudy.core.FuriganaMode
import ca.danielpower.jstudy.core.GrammarTopic
import ca.danielpower.jstudy.core.ImportPlan
import ca.danielpower.jstudy.core.Kanji
import ca.danielpower.jstudy.core.MasteryRules
import ca.danielpower.jstudy.core.Pace
import ca.danielpower.jstudy.core.ReadLevel
import ca.danielpower.jstudy.core.StudyClock
import ca.danielpower.jstudy.core.StudySession
import ca.danielpower.jstudy.core.StudySettings
import ca.danielpower.jstudy.core.UiLabel
import ca.danielpower.jstudy.core.UiLanguage
import ca.danielpower.jstudy.data.BackupException
import ca.danielpower.jstudy.data.ChapterEntry
import ca.danielpower.jstudy.data.Dashboard
import ca.danielpower.jstudy.data.Entry
import ca.danielpower.jstudy.data.GrammarRow
import ca.danielpower.jstudy.data.KanjiDetail
import ca.danielpower.jstudy.data.LockInfo
import ca.danielpower.jstudy.data.ReaderPlace
import ca.danielpower.jstudy.data.ReaderStyle
import ca.danielpower.jstudy.data.Reminder
import ca.danielpower.jstudy.data.Stats
import ca.danielpower.jstudy.reminder.DailyReminder
import ca.danielpower.jstudy.data.RenshuuClient
import ca.danielpower.jstudy.data.RenshuuException
import ca.danielpower.jstudy.data.Repository
import ca.danielpower.jstudy.data.SavedStory
import ca.danielpower.jstudy.data.SeriesEntry
import ca.danielpower.jstudy.data.StepToLearn
import ca.danielpower.jstudy.data.StoryReading
import ca.danielpower.jstudy.data.Summary
import ca.danielpower.jstudy.data.Undo
import ca.danielpower.jstudy.data.Word
import ca.danielpower.jstudy.data.WordDetail
import ca.danielpower.jstudy.data.WordStatus
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ReviewCard(
    val card: Card,
    val word: Word,
    /** Furigana on the front, from the word's mastery. */
    val furigana: FuriganaMode,
    val mastery: Int,
    /** The kanji in the word's spelling, linked from the answer side. */
    val kanji: List<Kanji> = emptyList(),
)

data class ReviewCounts(val new: Int, val learning: Int, val review: Int)

enum class StatusFilter(val label: UiLabel) {
    ALL(UiLabel("All", "全部")),
    NOT_STARTED(UiLabel("Not started", "未学習")),
    STUDYING(UiLabel("Studying", "勉強中", listOf("勉強"))),
    SUSPENDED(UiLabel("Suspended", "保留中")),
    ;

    fun matches(status: WordStatus?): Boolean {
        val started = status != null && status.state != CardState.NEW
        val suspended = status?.suspended == true
        return when (this) {
            ALL -> true
            NOT_STARTED -> !started && !suspended
            STUDYING -> started && !suspended
            SUSPENDED -> suspended
        }
    }
}

data class DictionaryRow(val entry: Entry, val status: WordStatus?)

sealed interface Screen {
    data object Loading : Screen
    data class Home(val dashboard: Dashboard) : Screen

    /** The Review tab: today's cards, study days, and the way into a session. */
    data class ReviewTab(val summary: Summary) : Screen
    data class Review(
        val current: ReviewCard,
        val revealed: Boolean,
        /** Whether furigana is currently visible on the front. */
        val furiganaVisible: Boolean,
        val counts: ReviewCounts,
        val canUndo: Boolean,
    ) : Screen

    /** Only learning steps remain and none is due yet. */
    data class Waiting(val dueAt: Long, val counts: ReviewCounts) : Screen
    data class Done(val summary: Summary) : Screen
    data class Settings(
        val settings: StudySettings,
        val attribution: String,
        /** The JLPT level you can already read, or [ReadLevel.NONE]. */
        val readLevel: Int,
        val levelCounts: Map<Int, Int>,
        val reminder: Reminder,
        /** The result of the last backup export or import. */
        val backupMessage: String? = null,
    ) : Screen

    /** First run, a page at a time: the level you can already read, and the reminder. */
    data class Setup(
        val levelCounts: Map<Int, Int>,
        val readLevel: Int = ReadLevel.NONE,
        /** The daily reminder's time, if you chose one. */
        val reminderMinute: Int?,
        val page: SetupPage = SetupPage.WELCOME,
    ) : Screen

    data class StatsPage(val stats: Stats) : Screen

    data class Dictionary(
        val query: String,
        /** JLPT levels to show; empty shows all. */
        val levels: Set<Int>,
        val status: StatusFilter,
        val rows: List<DictionaryRow>,
        /** The grammar topics, while the Grammar tab is showing in place of the words. */
        val grammar: List<GrammarRow>? = null,
    ) : Screen

    data class WordPage(val detail: WordDetail, val clock: StudyClock) : Screen

    data class KanjiPage(val detail: KanjiDetail) : Screen

    data class RenshuuImport(val step: ImportStep) : Screen

    data class Stories(
        /** The stories that come with the app, in order, ready or not. */
        val path: List<SeriesEntry>,
        /** Today's new words against the daily limit, and reviews waiting. */
        val pace: Pace? = null,
        /** Open chapters you haven't read that have nothing new to learn. */
        val nothingNew: List<ChapterEntry> = emptyList(),
    ) : Screen

    /** A story with more than one chapter: its chapters, and why one is locked once you tap it. */
    data class Series(val entry: SeriesEntry, val lock: LockInfo? = null) : Screen

    data class StoryReader(
        val reading: StoryReading,
        val style: ReaderStyle,
        /** For a chapter, where it sits in its story. */
        val chapter: ChapterEntry? = null,
        /** For a chapter, what to read next: the story's next open chapter, or the next story on the path. */
        val next: NextStep? = null,
        /** For a path story, once read: what reading it did for your words. */
        val impact: ReadImpact? = null,
        /** For a path story, once read: today's pace, and something with nothing new to read next. */
        val pace: Pace? = null,
        val rest: NextStep? = null,
        /** For a grammar page opened before a chapter: that chapter, to read once it's done. */
        val then: NextStep? = null,
        /** The page to open on, when coming back from a grammar page. */
        val startPage: Int = 0,
        /** Words you said you forgot: each gets a Wrong review when you finish. */
        val forgotten: Set<Long> = emptySet(),
        /** Words the finished story started that you said you already know. */
        val knewNew: Set<Long> = emptySet(),
    ) : Screen
}

/** Past today's limit, a story that would add [adding] new words, before it opens. */
data class LimitPrompt(
    val storyId: Long,
    val title: String,
    /** "Chapter 2", for a story with more than one. */
    val chapter: String?,
    val adding: Int,
    val newToday: Int,
    /** Whether anything with nothing new is open to read instead. */
    val nothingNewOpen: Boolean,
)

/**
 * What reading a path story did: the words you first saw for it, and the
 * ones that crossed into known (mastery 40, where furigana hides) or
 * mastered (70, where it's gone).
 */
data class ReadImpact(
    val seen: List<Word>,
    val known: List<Word>,
    val mastered: List<Word>,
    /** The grammar topic learned before reading it. */
    val topic: GrammarTopic? = null,
)

/** The next chapter to read, and how to name it on the button. */
data class NextStep(val storyId: Long, val label: String)

enum class SetupPage { WELCOME, LEVEL, REMINDER }

sealed interface ImportStep {
    data class EnterKey(val error: String? = null) : ImportStep
    data class Fetching(val page: Int, val pages: Int) : ImportStep
    data class Preview(val plan: ImportPlan) : ImportStep
    data object Applying : ImportStep
    data class Done(val updated: Int) : ImportStep
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    // Kept outside the database so the theme is right from the first frame.
    private val display = app.getSharedPreferences("display", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == display.getString("theme", null) } ?: ThemeMode.AUTO,
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    /** Shown over the screen you tapped a story on. */
    private val _limit = MutableStateFlow<LimitPrompt?>(null)
    val limit: StateFlow<LimitPrompt?> = _limit.asStateFlow()

    /** Which UI labels are in Japanese: the ones whose words you've mastered. */
    private val _language = MutableStateFlow(UiLanguage.ENGLISH)
    val language: StateFlow<UiLanguage> = _language.asStateFlow()

    /** Cards a review session would show now, for the Review tab's badge. */
    private val _due = MutableStateFlow(0)
    val due: StateFlow<Int> = _due.asStateFlow()
    private val labelRequests = Channel<UiLabel>(Channel.UNLIMITED)
    private val requestedLabels = ConcurrentHashMap.newKeySet<String>()

    private lateinit var repo: Repository
    private var session: StudySession? = null
    // The grammar topic read before the open chapter, for its end page.
    private var topicForStory: GrammarTopic? = null
    // Where Back goes from a grammar page: the screen it was opened from.
    private var beforeGrammar: Screen? = null
    // The page the open story is on, set from the main thread.
    @Volatile private var readerPage = 0
    private var undo: Undo? = null
    private val lock = Mutex()

    /** The JLPT words. Other words join the list when a search finds them or you've started them. */
    private var entries: List<Entry>? = null
    private var entryIds: Set<Long> = emptySet()
    private var statuses: Map<Long, WordStatus> = emptyMap()
    private var lastDictionary: Screen.Dictionary? = null

    /** Where Back goes from kanji pages and the words opened from them, most recent last. */
    private val trail = ArrayDeque<Screen>()
    private var searchJob: Job? = null
    private var importJob: Job? = null

    // When the open story was opened, to time the reading, and whether its end has been reached.
    private var readingSince = 0L
    private var readingFinished = false
    // For the open path story: your mastery when it opened, and the words you
    // looked up, which reading doesn't credit.
    private var masteryAtOpen: Map<Long, Int> = emptyMap()
    // Tapped on the main thread, read when the story ends.
    private val lookedUpWords: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    init {
        launchIo {
            repo = Repository(getApplication())
            val cached = repo.cachedLabelWords()
            requestedLabels += cached.keys
            _language.value = UiLanguage(cached, repo.masteredWords())
            lookUpLabels()
            DailyReminder.schedule(getApplication(), repo.reminder())
            if (repo.needsSetup()) {
                _screen.value = Screen.Setup(repo.levelCounts(), reminderMinute = null)
            } else {
                showHome()
            }
        }
    }

    private fun launchIo(block: suspend () -> Unit) {
        viewModelScope.launch {
            lock.withLock { withContext(Dispatchers.IO) { block() } }
        }
    }

    private fun now() = System.currentTimeMillis()

    /**
     * Find the words in a label the first time it's shown. It stays English
     * until then, which is only ever once: the answer is kept.
     */
    fun lookUpLabel(label: UiLabel) {
        if (!_language.value.knows(label) && requestedLabels.add(label.key)) labelRequests.trySend(label)
    }

    // Outside the lock, so a screen full of new labels doesn't hold up the screen itself.
    private fun lookUpLabels() = viewModelScope.launch(Dispatchers.IO) {
        for (first in labelRequests) {
            val batch = mutableListOf(first)
            while (true) batch += labelRequests.tryReceive().getOrNull() ?: break
            // A label that can't be looked up just stays English.
            val words = runCatching { repo.labelWords(batch) }.getOrNull() ?: continue
            _language.update { it.withWords(words) }
        }
    }

    /** Pick up mastery changes, so labels turn Japanese as you learn their words. */
    private fun refreshLanguage() {
        val mastered = repo.masteredWords()
        _language.update { it.withMastered(mastered) }
        // Most screens refresh this on the way in, so the badge rides along.
        val summary = repo.summary(now())
        _due.value = summary.newCards + summary.learning + summary.reviews
    }


    private fun showHome() {
        refreshLanguage()
        trail.clear()
        session = null
        undo = null
        _screen.value = Screen.Home(repo.dashboard(now()))
    }

    fun goHome() = launchIo { showHome() }

    fun openReviewTab() = launchIo { showReviewTab() }

    private fun showReviewTab() {
        refreshLanguage()
        trail.clear()
        session = null
        undo = null
        _screen.value = Screen.ReviewTab(repo.summary(now()))
    }

    /** The system back button: word page to dictionary, import to settings, story to stories, anything else home. */
    fun back() {
        when (_screen.value) {
            is Screen.WordPage -> closeWord()
            is Screen.KanjiPage -> goBack()
            is Screen.RenshuuImport -> closeImport()
            is Screen.StoryReader, is Screen.Series -> storyBack()
            is Screen.Setup -> setupBack()
            else -> goHome()
        }
    }

    fun startReview() = launchIo {
        session = repo.session(now())
        undo = null
        advance()
    }

    fun studyMore() = launchIo {
        repo.studyMore(now())
        session = repo.session(now())
        undo = null
        advance()
    }

    /** Show the next card, or the waiting or done screen. */
    private fun advance() {
        val s = session ?: return showHome()
        refreshLanguage()
        val counts = ReviewCounts(s.newRemaining, s.learningRemaining, s.reviewRemaining)
        val card = s.next(now())
        if (card == null) {
            val due = s.nextLearningDue
            _screen.value = if (due != null) Screen.Waiting(due, counts) else Screen.Done(repo.summary(now()))
            return
        }
        val word = repo.dictionary.word(card.wordId)
        if (word == null) {
            // A word removed from the dictionary in an update; skip it.
            s.answered(card.copy(state = CardState.REVIEW), 0)
            return advance()
        }
        val mastery = repo.progress(card.wordId).mastery
        val mode = MasteryRules.furigana(mastery)
        _screen.value = Screen.Review(
            current = ReviewCard(card, word, mode, mastery, kanji = repo.kanjiIn(word)),
            revealed = false,
            furiganaVisible = mode == FuriganaMode.SHOWN,
            counts = counts,
            canUndo = undo != null,
        )
    }

    fun reveal() = _screen.update { if (it is Screen.Review) it.copy(revealed = true) else it }

    fun toggleFurigana() = _screen.update {
        if (it is Screen.Review && it.current.furigana != FuriganaMode.OFF && it.current.word.showsKanji) {
            it.copy(furiganaVisible = !it.furiganaVisible)
        } else {
            it
        }
    }

    fun answer(correct: Boolean) = launchIo {
        val state = _screen.value as? Screen.Review ?: return@launchIo
        if (!state.revealed) return@launchIo
        val s = session ?: return@launchIo
        val now = now()
        val (answer, u) = repo.answer(state.current.card, correct, now)
        s.answered(answer.card, repo.endOfToday(now))
        undo = u
        advance()
    }

    fun undoLast() = launchIo {
        val u = undo ?: return@launchIo
        repo.undo(u)
        session?.restore(u.cardBefore)
        undo = null
        advance()
    }

    /** Re-check the queue, e.g. when a learning step falls due. */
    fun refresh() = launchIo { if (session != null) advance() }

    fun openSettings() = launchIo {
        trail.clear()
        showSettings(null)
    }

    private fun showSettings(backupMessage: String?) {
        refreshLanguage()
        val attribution = repo.dictionary.meta("attribution").orEmpty()
        _screen.value = Screen.Settings(
            settings = repo.settings(),
            attribution = attribution,
            readLevel = repo.readLevel(),
            levelCounts = repo.levelCounts(),
            reminder = repo.reminder(),
            backupMessage = backupMessage,
        )
    }

    /** Read at [level] from now on; raising it fills in that level's words and the easier ones. */
    fun setReadLevel(level: Int) = launchIo {
        repo.setReadLevel(level, now())
        refreshLanguage()
        _screen.update { if (it is Screen.Settings) it.copy(readLevel = repo.readLevel()) else it }
    }

    fun setReminder(reminder: Reminder) = launchIo {
        repo.saveReminder(reminder)
        DailyReminder.schedule(getApplication(), reminder)
        _screen.update { if (it is Screen.Settings) it.copy(reminder = reminder) else it }
    }

    // ------------------------------------------------------------ backup

    fun exportBackup(uri: Uri) = launchIo {
        val message = try {
            val out = getApplication<Application>().contentResolver.openOutputStream(uri) ?: error("no stream")
            out.use { repo.exportBackup(it) }
            "Backup saved."
        } catch (e: Exception) {
            "Couldn't save the backup: ${e.message}"
        }
        _screen.update { if (it is Screen.Settings) it.copy(backupMessage = message) else it }
    }

    fun importBackup(uri: Uri) = launchIo {
        val message = try {
            val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: error("no stream")
            input.use { repo.importBackup(it) }
            // Everything cached came from the old progress.
            session = null
            undo = null
            statuses = emptyMap()
            lastDictionary = null
            DailyReminder.schedule(getApplication(), repo.reminder())
            "Backup restored."
        } catch (e: BackupException) {
            e.message
        } catch (e: Exception) {
            "Couldn't restore the backup: ${e.message}"
        }
        showSettings(message)
    }

    // ------------------------------------------------------------ reset

    /** Wipe all progress, settings and stories read, and start again at first-run setup. */
    fun resetAll() {
        importJob?.cancel()
        searchJob?.cancel()
        launchIo {
            _screen.value = Screen.Loading
            repo.resetAll()
            session = null
            undo = null
            entries = null
            entryIds = emptySet()
            statuses = emptyMap()
            lastDictionary = null
            display.edit().clear().commit()
            _theme.value = ThemeMode.AUTO
            DailyReminder.schedule(getApplication(), repo.reminder())
            refreshLanguage()
            _screen.value = Screen.Setup(repo.levelCounts(), reminderMinute = null)
        }
    }

    // ------------------------------------------------------------ first run

    fun editSetup(change: (Screen.Setup) -> Screen.Setup) = _screen.update { if (it is Screen.Setup) change(it) else it }

    fun setupNext() = editSetup { s -> SetupPage.entries.getOrNull(s.page.ordinal + 1)?.let { s.copy(page = it) } ?: s }

    fun setupBack() = editSetup { s -> SetupPage.entries.getOrNull(s.page.ordinal - 1)?.let { s.copy(page = it) } ?: s }

    fun finishSetup() = launchIo {
        val setup = _screen.value as? Screen.Setup ?: return@launchIo
        _screen.value = Screen.Loading
        repo.finishSetup(setup.readLevel, setup.reminderMinute, now())
        DailyReminder.schedule(getApplication(), repo.reminder())
        showHome()
    }

    // ------------------------------------------------------------ stats

    fun openStats() = launchIo { _screen.value = Screen.StatsPage(repo.stats(now())) }

    fun saveSettings(settings: StudySettings) = launchIo {
        repo.saveSettings(settings)
        _screen.update { if (it is Screen.Settings) it.copy(settings = settings) else it }
    }

    fun setTheme(mode: ThemeMode) {
        display.edit().putString("theme", mode.name).apply()
        _theme.value = mode
    }

    fun setReaderStyle(style: ReaderStyle) {
        _screen.update { if (it is Screen.StoryReader) it.copy(style = style) else it }
        launchIo { repo.setReaderStyle(style) }
    }

    // ------------------------------------------------------------ renshuu import

    fun openImport() {
        _screen.value = Screen.RenshuuImport(ImportStep.EnterKey())
    }

    fun closeImport() {
        importJob?.cancel()
        importJob = null
        openSettings()
    }

    private fun importStep(step: ImportStep) =
        _screen.update { if (it is Screen.RenshuuImport) Screen.RenshuuImport(step) else it }

    /** Download your studied words and show what importing them would change. Nothing is written yet. */
    fun fetchRenshuu(apiKey: String) {
        val key = apiKey.trim()
        if (key.isEmpty()) return
        importJob?.cancel()
        importStep(ImportStep.Fetching(0, 0))
        // Not under the lock: the download can take a while and shouldn't block leaving the screen.
        importJob = viewModelScope.launch {
            try {
                val terms = withContext(Dispatchers.IO) {
                    RenshuuClient(key).studiedVocab { page, pages ->
                        ensureActive()
                        importStep(ImportStep.Fetching(page, pages))
                    }
                }
                lock.withLock {
                    val plan = withContext(Dispatchers.IO) { repo.renshuuPlan(terms) }
                    importStep(ImportStep.Preview(plan))
                }
            } catch (e: RenshuuException) {
                importStep(ImportStep.EnterKey(e.message))
            }
        }
    }

    fun applyImport() = launchIo {
        val step = (_screen.value as? Screen.RenshuuImport)?.step as? ImportStep.Preview ?: return@launchIo
        importStep(ImportStep.Applying)
        repo.importMastery(step.plan.updates, now())
        importStep(ImportStep.Done(step.plan.updates.size))
    }

    // ------------------------------------------------------------ stories

    fun openStories() = launchIo {
        trail.clear()
        showStories()
    }

    private fun showStories() {
        val path = repo.storyPath(now())
        _screen.value = Screen.Stories(
            path = path,
            pace = repo.pace(now()),
            nothingNew = nothingNew(path),
        )
    }

    /** Open chapters you haven't read with nothing new to learn, story order. */
    private fun nothingNew(path: List<SeriesEntry>) = path.flatMap { it.chapters }.filter { it.nothingNew && !it.read }

    /** A story from the path: one chapter opens straight away, more show their chapters. */
    fun openPathStory(entry: SeriesEntry) {
        if (entry.series.single) entry.chapters.firstOrNull()?.let { openStory(it.story.id) } else openSeries(entry.series.id)
    }

    fun openSeries(id: String) = launchIo { showSeries(id) }

    private fun showSeries(id: String) {
        val entry = repo.storyPath(now()).firstOrNull { it.series.id == id } ?: return showStories()
        _screen.value = Screen.Series(entry)
    }

    /** Tapping a locked chapter: what it's missing, and where to find it. */
    fun showLock(chapterId: String) = launchIo {
        val lock = repo.lockInfo(chapterId, now()) ?: return@launchIo
        _screen.update { if (it is Screen.Series) it.copy(lock = lock) else it }
    }

    fun closeLock() = _screen.update { if (it is Screen.Series) it.copy(lock = null) else it }

    /** Back from a story screen: a grammar page to where it was opened, a chapter to its story's list, the rest to the stories. */
    private fun storyBack() = launchIo {
        val screen = _screen.value
        val grammarFrom = beforeGrammar
        val chapter = when (screen) {
            // A grammar page before a chapter goes back to its chapters.
            is Screen.StoryReader -> if (screen.reading.lesson == null) screen.chapter else screen.then?.let { repo.story(it.storyId) }?.let { repo.chapterOf(it, now()) }
            else -> null
        }
        when {
            screen is Screen.Series && screen.lock != null -> closeLock()
            screen is Screen.StoryReader && screen.reading.lesson != null && grammarFrom != null -> {
                beforeGrammar = null
                _screen.value = if (grammarFrom is Screen.Dictionary) grammarFrom.copy(grammar = repo.grammarRows(now())) else grammarFrom
            }
            chapter != null && !chapter.series.single -> showSeries(chapter.series.id)
            else -> showStories()
        }
    }

    /** The back arrow on story screens. */
    fun storyBackPressed() = storyBack()

    /**
     * Open a story. A chapter's new grammar's page comes first, then the
     * chapter; its new words you learn by reading.
     * Past today's limit, a chapter that adds more asks first, unless [anyway].
     */
    fun openStory(id: Long, anyway: Boolean = false) = launchIo {
        _limit.value = null
        val story = repo.story(id) ?: return@launchIo showStories()
        val learn = repo.stepToLearn(story, now())
        val adding = learn?.newWords ?: 0
        val pace = repo.pace(now())
        if (!anyway && pace.over(adding)) {
            val chapter = repo.chapterOf(story, now())
            _limit.value = LimitPrompt(
                id, story.title, chapter?.takeIf { !it.series.single }?.let { "Chapter ${it.chapter.number}" },
                adding, pace.newToday, nothingNew(repo.storyPath(now())).isNotEmpty(),
            )
            return@launchIo
        }
        topicForStory = null
        continueChapter(story, learn)
    }

    /** On towards reading [story]: its new grammar's page, then the story. */
    private fun continueChapter(story: SavedStory, learn: StepToLearn?) {
        val page = learn?.topic?.let { repo.grammarPageId(it, now()) } ?: return showStory(story.id)
        val chapter = repo.chapterOf(story, now())
        val name = if (chapter != null && !chapter.series.single) "chapter ${chapter.chapter.number}: ${chapter.chapter.story.title}" else story.title
        beforeGrammar = null
        showStory(page, then = NextStep(story.id, "Read $name"))
    }

    /** The button at the end of a grammar page before a chapter: the grammar counts as seen, and the chapter opens. */
    fun readAfterLesson() = launchIo {
        val state = _screen.value as? Screen.StoryReader ?: return@launchIo
        val then = state.then ?: return@launchIo
        repo.topicOfPage(state.reading.story)?.let { topic ->
            repo.markGrammarSeen(topic.points)
            topicForStory = topic
        }
        showStory(then.storyId)
    }

    /** Past today's limit, before a chapter: the stories with nothing new, or reviews if none are open. */
    fun readNothingNew() {
        val prompt = _limit.value
        _limit.value = null
        if (prompt?.nothingNewOpen == false) startReview() else openStories()
    }

    fun closeLimit() {
        _limit.value = null
    }

    /**
     * A grammar topic's page, from the Words tab or a grammar point in a
     * story; Back returns there, to the same page of the story. From one
     * grammar page to another, Back skips the first.
     */
    fun openGrammar(pageId: Long) = launchIo {
        val screen = _screen.value
        if (screen !is Screen.StoryReader || screen.reading.lesson == null) {
            beforeGrammar = if (screen is Screen.StoryReader) screen.copy(startPage = readerPage) else screen
        }
        showStory(pageId)
    }

    /** The page the open story has settled on; for an unfinished chapter, kept so Home can come back to it. */
    fun readerAt(page: Int) {
        readerPage = page
        val state = _screen.value as? Screen.StoryReader ?: return
        if (state.reading.lesson != null || state.impact != null || state.reading.story.readAt != null) return
        val place = ReaderPlace(state.reading.story.id, page, state.style.toString())
        viewModelScope.launch(Dispatchers.IO) { repo.saveReaderPlace(place) }
    }

    /** Open a story, or a grammar page; a grammar page leaves the story it was opened from as it was. */
    private fun showStory(id: Long, then: NextStep? = null) {
        val reading = repo.readStory(id) ?: return showStories()
        val style = repo.readerStyle()
        var startPage = 0
        if (reading.lesson == null) {
            readingSince = now()
            readingFinished = false
            masteryAtOpen = repo.masteries(reading.words.keys)
            lookedUpWords.clear()
            // Back where you left it, unless the pages have been laid out differently since.
            startPage = repo.readerPlace()?.takeIf { it.storyId == id && it.style == style.toString() }?.page ?: 0
            readerPage = startPage
        }
        val chapter = repo.chapterOf(reading.story, now())
        _screen.value = Screen.StoryReader(reading, style, chapter = chapter, next = chapter?.let(::nextStep), then = then, startPage = startPage)
    }

    /** After [chapter]: its story's next open, unread chapter, or else the next story on the path that has one. */
    private fun nextStep(chapter: ChapterEntry): NextStep? {
        val path = repo.storyPath(now())
        val here = path.firstOrNull { it.series.id == chapter.series.id }
        val sameStory = here?.chapters?.let { c -> c.drop(c.indexOfFirst { it.story.id == chapter.story.id } + 1) + c }
            ?.firstOrNull { it.ready && !it.read && it.story.id != chapter.story.id }
        if (sameStory != null) return NextStep(sameStory.story.id, "Next: ${sameStory.chapter.number}. ${sameStory.chapter.story.title}")
        val other = path.firstOrNull { it.series.id != chapter.series.id && it.next != null } ?: return null
        val next = other.next!!
        return NextStep(next.story.id, "Next story: ${other.series.title}")
    }

    /** The finish stamp at the end of the text marks the story read, timed from when it was opened, and shows the summary. */
    fun finishStory() = launchIo {
        val state = _screen.value as? Screen.StoryReader ?: return@launchIo
        if (readingFinished) return@launchIo
        readingFinished = true
        val at = now()
        val story = state.reading.story
        repo.markStoryRead(story.id, at, at - readingSince)
        if (repo.readerPlace()?.storyId == story.id) repo.clearReaderPlace()
        repo.readWords(story, lookedUpWords.toSet(), at)
        repo.forgetWords(state.forgotten, at)
        val started = repo.startStoryWords(story, at)
        val after = repo.masteries(state.reading.words.keys)
        fun crossed(line: Int) = after.filter { (id, m) -> (masteryAtOpen[id] ?: 0) < line && m >= line }.keys
        val words = state.reading.words
        val impact = ReadImpact(
            seen = started.mapNotNull { words[it] },
            known = crossed(MasteryRules.HIDE_AT).mapNotNull { words[it] },
            mastered = crossed(MasteryRules.OFF_AT).mapNotNull { words[it] },
            topic = topicForStory,
        )
        val rest = nothingNew(repo.storyPath(at)).firstOrNull { it.story.id != story.id }?.let {
            val name = if (it.series.single) it.series.title else "${it.series.title} ${it.chapter.number}"
            NextStep(it.story.id, "Next: $name · nothing new")
        }
        val pace = repo.pace(at)
        _screen.update { if (it is Screen.StoryReader) it.copy(impact = impact, pace = pace, rest = rest) else it }
        refreshStory()
    }

    /** "I know this" on a word the finished story started, or its Undo: it stops counting against today's new words. */
    fun toggleKnewNew(wordId: Long) = launchIo {
        val state = _screen.value as? Screen.StoryReader ?: return@launchIo
        if (state.impact == null) return@launchIo
        val at = now()
        val knew = wordId in state.knewNew
        if (knew) repo.unknowStartedWord(wordId, at) else repo.knowStartedWord(wordId, at)
        val pace = repo.pace(at)
        _screen.update {
            if (it !is Screen.StoryReader) it
            else it.copy(knewNew = if (knew) it.knewNew - wordId else it.knewNew + wordId, pace = pace)
        }
    }

    /** "I forgot this" on a word in the open story, or its Undo. */
    fun toggleForgot(wordId: Long) {
        _screen.update {
            if (it !is Screen.StoryReader) it
            else it.copy(forgotten = if (wordId in it.forgotten) it.forgotten - wordId else it.forgotten + wordId)
        }
    }

    /** A word tapped in the open story: you looked it up, so reading it doesn't count as recalling it. */
    fun lookedUp(wordId: Long) {
        lookedUpWords += wordId
    }

    private fun refreshStory() {
        val state = _screen.value as? Screen.StoryReader ?: return
        val reading = repo.readStory(state.reading.story.id) ?: return
        val chapter = repo.chapterOf(reading.story, now())
        val next = chapter?.let(::nextStep)
        _screen.update { if (it is Screen.StoryReader) it.copy(reading = reading, chapter = chapter, next = next) else it }
    }

    // ------------------------------------------------------------ dictionary

    fun openDictionary(status: StatusFilter = StatusFilter.ALL) = launchIo {
        trail.clear()
        if (entries == null) {
            entries = repo.dictionary.entries()
            entryIds = entries!!.mapTo(HashSet()) { it.id }
        }
        statuses = repo.wordStatuses()
        val state = Screen.Dictionary("", emptySet(), status, emptyList())
        _screen.value = state.copy(rows = rows(state))
    }

    /** Words matching the query and filters, best match first. */
    private fun rows(state: Screen.Dictionary): List<DictionaryRow> {
        val jlpt = entries ?: return emptyList()
        val ranks = if (state.query.isBlank()) null else repo.dictionary.search(state.query)
        val others = if (ranks == null) {
            statuses.keys.filter { it !in entryIds }
        } else {
            ranks.keys.filter { it !in entryIds }.sortedBy { ranks.getValue(it) }.take(MAX_OTHER_HITS)
        }
        val all = jlpt + repo.dictionary.entries(others)
        val matching = all.filter { e ->
            (ranks == null || e.id in ranks) &&
                (state.levels.isEmpty() || e.jlpt in state.levels) &&
                state.status.matches(statuses[e.id])
        }
        // Entries are in study order, and the sort is stable.
        val sorted = if (ranks == null) matching else matching.sortedBy { ranks.getValue(it.id) }
        return sorted.map { DictionaryRow(it, statuses[it.id]) }
    }

    private fun filterDictionary(debounce: Long, change: (Screen.Dictionary) -> Screen.Dictionary) {
        val current = _screen.value as? Screen.Dictionary ?: return
        val next = change(current)
        _screen.value = next
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(debounce)
            val rows = withContext(Dispatchers.IO) { rows(next) }
            _screen.update {
                val same = it is Screen.Dictionary && it.query == next.query && it.levels == next.levels && it.status == next.status
                if (same) (it as Screen.Dictionary).copy(rows = rows) else it
            }
        }
    }

    /** The Words tab's Grammar list in place of the words, or back to the words. */
    fun showGrammar(show: Boolean) = launchIo {
        val rows = if (show) repo.grammarRows(now()) else null
        _screen.update { if (it is Screen.Dictionary) it.copy(grammar = rows) else it }
    }

    fun search(query: String) = filterDictionary(150) { it.copy(query = query) }

    fun toggleLevel(level: Int) = filterDictionary(0) {
        it.copy(levels = if (level in it.levels) it.levels - level else it.levels + level)
    }

    fun setStatusFilter(status: StatusFilter) = filterDictionary(0) { it.copy(status = status) }

    fun openWord(wordId: Long) = launchIo {
        when (val current = _screen.value) {
            is Screen.Dictionary -> lastDictionary = current
            is Screen.KanjiPage, is Screen.StoryReader -> trail.addLast(current)
            else -> {}
        }
        showWord(wordId)
    }

    private fun showWord(wordId: Long) {
        refreshLanguage()
        val detail = repo.wordDetail(wordId) ?: return
        val settings = repo.settings()
        _screen.value = Screen.WordPage(detail, repo.clock(settings))
    }

    fun closeWord() = launchIo {
        trail.removeLastOrNull()?.let { return@launchIo returnTo(it) }
        val state = lastDictionary
        if (state == null) {
            showHome()
            return@launchIo
        }
        statuses = repo.wordStatuses()
        _screen.value = state.copy(rows = rows(state))
    }

    private fun wordAction(action: (wordId: Long) -> Unit) = launchIo {
        val page = _screen.value as? Screen.WordPage ?: return@launchIo
        val wordId = page.detail.word.id
        action(wordId)
        showWord(wordId)
    }

    // ------------------------------------------------------------ kanji

    /** Open a kanji's page; Back returns to the screen it was opened from. */
    fun openKanji(literal: String) = launchIo {
        val detail = repo.kanjiDetail(literal) ?: return@launchIo
        trail.addLast(_screen.value)
        _screen.value = Screen.KanjiPage(detail)
    }

    fun goBack() = launchIo { returnTo(trail.removeLastOrNull()) }

    /** Show a screen from the trail again, reloading the ones whose progress may have changed meanwhile. */
    private fun returnTo(screen: Screen?) {
        when (screen) {
            null, is Screen.Home -> showHome()
            is Screen.WordPage -> showWord(screen.detail.word.id)
            is Screen.KanjiPage -> repo.kanjiDetail(screen.detail.kanji.literal)?.let { _screen.value = Screen.KanjiPage(it) }
            is Screen.StatsPage -> _screen.value = Screen.StatsPage(repo.stats(now()))
            else -> _screen.value = screen
        }
    }

    fun markKnown(mastery: Int) = wordAction { repo.markKnown(it, mastery, now()) }

    fun resetWord() = wordAction { repo.reset(it) }

    fun setSuspended(suspended: Boolean) = wordAction { repo.setSuspended(it, suspended) }

    companion object {
        /** Search hits beyond the JLPT words to list, best first. */
        private const val MAX_OTHER_HITS = 200
    }
}
