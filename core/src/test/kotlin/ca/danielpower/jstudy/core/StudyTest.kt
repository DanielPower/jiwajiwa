package ca.danielpower.jstudy.core

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudyTest {
    private val zone = ZoneId.of("America/St_Johns")
    private val clock = StudyClock(zone, rolloverHour = 4)
    private val reviewer = Reviewer(Fsrs(), clock)

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `study day rolls over at 4am`() {
        val day = clock.dayOf(at("2026-10-10T12:00"))
        assertEquals(day, clock.dayOf(at("2026-10-11T03:59")))
        assertEquals(day + 1, clock.dayOf(at("2026-10-11T04:00")))
        assertEquals(at("2026-10-11T04:00"), clock.startOf(day + 1))
    }

    @Test
    fun `mastery thresholds pick the furigana mode`() {
        assertEquals(FuriganaMode.SHOWN, MasteryRules.furigana(39))
        assertEquals(FuriganaMode.HIDDEN, MasteryRules.furigana(40))
        assertEquals(FuriganaMode.HIDDEN, MasteryRules.furigana(69))
        assertEquals(FuriganaMode.OFF, MasteryRules.furigana(70))
    }

    @Test
    fun `mastery follows stability`() {
        assertEquals(0, MasteryRules.fromStability(null))
        assertEquals(0, MasteryRules.fromStability(0.5))
        assertEquals(40, MasteryRules.fromStability(7.0))
        assertEquals(55, MasteryRules.fromStability(18.5))
        assertEquals(70, MasteryRules.fromStability(30.0))
        assertEquals(100, MasteryRules.fromStability(400.0))
        for (m in listOf(0, 40, 70, 100)) assertEquals(m, MasteryRules.fromStability(MasteryRules.stabilityDays(m).toDouble()))
    }

    @Test
    fun `new card right twice reaches review and gains mastery once`() {
        val card = Card(id = 1, wordId = 7, direction = Direction.RECOGNITION)
        val first = reviewer.answer(card, WordProgress(7), correct = true, now = at("2026-10-10T20:00"))
        assertEquals(CardState.LEARNING, first.card.state)
        assertEquals(at("2026-10-10T20:10"), first.card.dueAt)
        assertEquals(MasteryRules.fromStability(first.card.stability), first.progress.mastery)
        assertTrue(first.progress.mastery in 1..39)

        val second = reviewer.answer(first.card, first.progress, correct = true, now = at("2026-10-10T20:11"))
        assertEquals(CardState.REVIEW, second.card.state)
        assertEquals(MasteryRules.fromStability(second.card.stability), second.progress.mastery)
        // Review cards fall due at the start of a study day.
        val dueDay = clock.dayOf(second.card.dueAt)
        assertEquals(clock.startOf(dueDay), second.card.dueAt)
        assertTrue(dueDay > clock.dayOf(at("2026-10-10T20:11")))
    }

    @Test
    fun `a miss drops mastery with stability`() {
        val card = Card(
            id = 1, wordId = 7, direction = Direction.RECOGNITION, state = CardState.REVIEW,
            stability = 10.0, difficulty = 5.0, dueAt = at("2026-10-10T04:00"), lastReviewAt = at("2026-10-01T09:00"),
        )
        val miss = reviewer.answer(card, WordProgress(7, mastery = 50), correct = false, now = at("2026-10-10T09:00"))
        assertEquals(CardState.RELEARNING, miss.card.state)
        assertEquals(MasteryRules.fromStability(miss.card.stability), miss.progress.mastery)
        assertTrue(miss.progress.mastery < MasteryRules.fromStability(10.0))
        assertEquals(1, miss.card.lapses)
        assertEquals(9, miss.log.elapsedDays)
        val relearned = reviewer.answer(miss.card, miss.progress, correct = true, now = at("2026-10-10T09:11"))
        assertEquals(CardState.REVIEW, relearned.card.state)
        assertEquals(MasteryRules.fromStability(relearned.card.stability), relearned.progress.mastery)
    }

    @Test
    fun `production reviews leave mastery alone`() {
        val card = Card(
            id = 2, wordId = 7, direction = Direction.PRODUCTION, state = CardState.REVIEW,
            stability = 10.0, difficulty = 5.0, lastReviewAt = at("2026-10-01T09:00"),
        )
        val progress = WordProgress(7, mastery = 50, productionUnlocked = true)
        assertEquals(50, reviewer.answer(card, progress, correct = false, now = at("2026-10-10T09:00")).progress.mastery)
        assertEquals(50, reviewer.answer(card, progress, correct = true, now = at("2026-10-10T09:00")).progress.mastery)
    }

    @Test
    fun `elapsed days count study days not 24 hour periods`() {
        val card = Card(
            id = 1, wordId = 7, direction = Direction.RECOGNITION, state = CardState.REVIEW,
            stability = 3.0, difficulty = 5.0, lastReviewAt = at("2026-10-10T23:00"),
        )
        val answer = reviewer.answer(card, WordProgress(7), correct = true, now = at("2026-10-11T08:00"))
        assertEquals(1, answer.log.elapsedDays)
    }

    @Test
    fun `production unlocks when recognition mastery reaches 40`() {
        val card = Card(id = 1, wordId = 7, direction = Direction.RECOGNITION, state = CardState.REVIEW, stability = 5.0, difficulty = 5.0, lastReviewAt = at("2026-10-01T09:00"))
        val answer = reviewer.answer(card, WordProgress(7, mastery = 30), correct = true, now = at("2026-10-10T09:00"))
        assertTrue(answer.unlockedProduction)
        assertTrue(answer.progress.productionUnlocked)
        val again = reviewer.answer(card.copy(id = 2), answer.progress.copy(mastery = 30), true, at("2026-10-11T09:00"))
        assertFalse(again.unlockedProduction, "unlocks only once")
    }

    @Test
    fun `study more pulls schedules a day forward and adds a day of new cards`() {
        val settings = StudySettings(newPerDay = 10, reviewCap = 200)
        val now = at("2026-10-10T20:00")
        val today = clock.dayOf(now)
        val normal = DailyLimits.of(settings, StudyAhead(), clock, now, newIntroducedToday = 10, reviewsDoneToday = 30)
        assertEquals(0, normal.newCards)
        assertEquals(clock.startOf(today + 1), normal.reviewDueBefore)

        val ahead = StudyAhead().studyMore(today)
        val more = DailyLimits.of(settings, ahead, clock, now, newIntroducedToday = 10, reviewsDoneToday = 30)
        assertEquals(10, more.newCards)
        assertEquals(370, more.reviews)
        assertEquals(clock.startOf(today + 2), more.reviewDueBefore)

        // Tomorrow keeps the shift but gets the normal allowance.
        val tomorrow = at("2026-10-11T09:00")
        val next = DailyLimits.of(settings, ahead, clock, tomorrow, newIntroducedToday = 0, reviewsDoneToday = 0)
        assertEquals(10, next.newCards)
        assertEquals(clock.startOf(today + 3), next.reviewDueBefore)
    }

    @Test
    fun `interleave spreads new cards evenly`() {
        val out = StudySession.interleave(listOf("r1", "r2", "r3", "r4"), listOf("n1", "n2"))
        assertEquals(listOf("r1", "n1", "r2", "r3", "n2", "r4"), out)
        assertEquals(listOf("n1", "n2"), StudySession.interleave(emptyList(), listOf("n1", "n2")))
    }

    @Test
    fun `missed card comes back after its step and session ends when done`() {
        val now = at("2026-10-10T20:00")
        val endOfDay = clock.startOf(clock.dayOf(now) + 1)
        val newCard = Card(id = 1, wordId = 1, direction = Direction.RECOGNITION)
        val review = Card(id = 2, wordId = 2, direction = Direction.RECOGNITION, state = CardState.REVIEW, stability = 5.0, difficulty = 5.0, lastReviewAt = at("2026-10-05T09:00"))
        val session = StudySession(listOf(review), listOf(newCard), emptyList())

        val first = session.next(now)!!
        assertEquals(2, first.id)
        val missed = reviewer.answer(first, WordProgress(2), false, now)
        session.answered(missed.card, endOfDay)
        assertEquals(1, session.learningRemaining)

        // The relearning step isn't due, so the new card comes first.
        assertEquals(1, session.next(now + 1000)!!.id)
        val learned = reviewer.answer(newCard, WordProgress(1), true, now + 1000)
        session.answered(learned.card, endOfDay)

        // Only learning cards remain: shown early when due within 20 minutes.
        val upNext = session.next(now + 2000)!!
        assertEquals(2, upNext.id)
        session.answered(reviewer.answer(upNext, missed.progress, true, now + 2000).card, endOfDay)
        val last = session.next(now + 3000)!!
        assertEquals(1, last.id)
        session.answered(reviewer.answer(last, learned.progress, true, now + 3000).card, endOfDay)
        assertNull(session.next(now + 4000))
        assertTrue(session.isEmpty())
    }

    @Test
    fun `undo puts the card back first`() {
        val a = Card(id = 1, wordId = 1, direction = Direction.RECOGNITION)
        val b = Card(id = 2, wordId = 2, direction = Direction.RECOGNITION)
        val session = StudySession(emptyList(), listOf(a, b), emptyList())
        val now = at("2026-10-10T20:00")
        val shown = session.next(now)!!
        session.answered(reviewer.answer(shown, WordProgress(1), true, now).card, Long.MAX_VALUE)
        session.restore(shown)
        assertEquals(1, session.next(now)!!.id)
        assertEquals(0, session.learningRemaining)
    }

    @Test
    fun `marking a word known schedules it from its mastery`() {
        assertEquals(1, MasteryRules.stabilityDays(0))
        assertEquals(7, MasteryRules.stabilityDays(40))
        assertEquals(30, MasteryRules.stabilityDays(70))
        assertEquals(90, MasteryRules.stabilityDays(100))

        val now = at("2026-10-10T20:00")
        val today = clock.dayOf(now)
        val card = Card(id = 1, wordId = 7, direction = Direction.RECOGNITION)
        val random = kotlin.random.Random(3)
        repeat(50) {
            val known = reviewer.markKnown(card, WordProgress(7), 70, now, random)
            assertEquals(CardState.REVIEW, known.card.state)
            assertEquals(70, known.progress.mastery)
            assertTrue(known.unlockedProduction)
            assertNull(known.card.introducedDay, "doesn't use up a new-card slot")
            val dueDay = clock.dayOf(known.card.dueAt)
            assertTrue(dueDay in (today + 1)..(today + 30), "due $dueDay")
        }
        // The first review after marking behaves like any review.
        val known = reviewer.markKnown(card, WordProgress(7), 70, now, kotlin.random.Random(1))
        val next = reviewer.answer(known.card, known.progress, true, known.card.dueAt + 3_600_000)
        assertEquals(CardState.REVIEW, next.card.state)
        assertTrue(next.progress.mastery > 70)
        assertNull(next.card.introducedDay, "still doesn't use up a new-card slot")
    }

    @Test
    fun `introducing an unknown word starts learning without grading it`() {
        val now = at("2026-10-10T20:00")
        val card = Card(id = 1, wordId = 7, direction = Direction.RECOGNITION)
        val intro = reviewer.introduce(card, now)
        assertEquals(CardState.LEARNING, intro.state)
        assertEquals(now + 60_000, intro.dueAt)
        assertEquals(clock.dayOf(now), intro.introducedDay)
        assertNull(intro.stability)

        // The first test is FSRS's first review, and the first mastery change.
        val first = reviewer.answer(intro, WordProgress(7), correct = true, now = now + 90_000)
        assertNull(first.log.elapsedDays)
        assertEquals(Fsrs().review(FsrsMemory.NEW, Rating.GOOD, null).memory, FsrsMemory(FsrsState.LEARNING, first.card.step, first.card.stability, first.card.difficulty))
        assertEquals(MasteryRules.fromStability(first.card.stability), first.progress.mastery)
    }
}

