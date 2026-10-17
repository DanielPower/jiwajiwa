# AGENTS.md

Notes for coding agents working on jstudy. Read this first, then the
AGENTS.md in the module you're changing (`core/`, `app/`, `tools/dictionary/`).

## What this is

jstudy is Daniel's Android app for learning Japanese vocabulary, JLPT N5 to
N1. It's Anki-style (you grade yourself Wrong or Right, FSRS schedules the
card) with a friendlier, Renshuu-like feel. It's built for Daniel's own use
first; publishing on Google Play may come later.

Guiding rules from Daniel:

- **Personal use first.** Never trade quality for publishability (for
  example, the dictionary keeps the best community sources even where their
  licences are awkward). Groundwork that makes publishing easier later is
  welcome.
- **Go ahead on best guesses.** Pick a reasonable default, say which, and
  keep going. Save questions for the end.
- Don't re-open the decisions below without a reason; they were made on
  purpose.

## Layout

| Path | What it is |
| --- | --- |
| `core/` | Pure-Kotlin (JVM) study logic: FSRS, cards and mastery, the daily queue, kanji readings, romaji, the story path, Kuromoji word splitting, Renshuu import planning. No Android imports. |
| `app/` | The Android app: Jetpack Compose UI, SQLite storage, reminder, backup. |
| `tools/dictionary/` | Python (stdlib only) build of the bundled dictionary DB from pinned sources. |
| `tools/fsrs/` | Generates `core/src/test/resources/fsrs_vectors.tsv` from py-fsrs. |
| `.github/workflows/` | CI (see below). |

Put logic in `core` whenever it doesn't need Android, so it can be tested
anywhere. `app` should mostly be wiring, storage and UI.

## Building and testing

```sh
./gradlew -PcoreOnly=true :core:test      # core only, no Android SDK needed
./gradlew :app:testDebugUnitTest          # app JVM tests (Robolectric), needs the SDK
python3 -m unittest discover tools/dictionary
python3 tools/dictionary/build_dictionary.py   # needed before assembling an APK
./gradlew :app:assembleDebug
```

- Cloud agent containers usually can't reach `dl.google.com` or
  `maven.google.com`, so the Android app can't be built or tested there. Run
  the core tests locally and let CI build the app. `-PcoreOnly=true` drops
  `:app` from `settings.gradle.kts` for this.
- Maven Central sometimes answers 429 from those containers; retry, with
  `--max-workers=1` if it keeps happening.
- Tests use JUnit 5 + `kotlin.test` in core, JUnit 4 + Robolectric in app
  (pinned to SDK 34 in `app/src/test/resources/robolectric.properties`,
  because Robolectric needs Java 21 for SDK 35 and CI uses Java 17).
- Core test names are backtick sentences describing behaviour; follow that.

## CI

| Workflow | When | What |
| --- | --- | --- |
| `test.yml` (Tests) | every PR, every push to main | core tests, app unit tests, dictionary tests, as separate checks |
| `check.yml` (Check pull request) | every PR, or by hand on any branch | builds the dictionary and the release + debug APKs; attaches the debug APK (app id `com.crispyumbrella.jiwajiwa.debug`, installs next to the real app as "Jiwa Jiwa Debug") and comments on the PR with a link to it |
| `release.yml` | every push to main | tests, builds, signs and publishes a GitHub release `v0.1.<run number>` |
| `dictionary.yml` | pushes touching `tools/dictionary/` | rebuilds and checks the digest |

Every push to main ships a release, so main must always be releasable.
Release signing comes from repository secrets (see the README); never put
keys, passwords or API keys in the repo. Daniel may open-source it later.

## Working in the repo

- Several agents work here at once. Fetch and rebase on `origin/main` before
  each push.
- Open a pull request for feature work rather than pushing to main. Daniel
  sometimes okays a direct push for a small tweak; ask or follow his word.
- Commit messages are a short imperative summary of what the user sees
  ("Teach hiragana and katakana before the words that need them").
- Comments explain why, briefly, in plain sentences. Match the surrounding
  density; don't narrate code.
- User-facing text is plain and friendly, sentence case, no jargon ("Right" /
  "Wrong", not "Good" / "Again").

## Product decisions (don't re-ask)

Study and scheduling:

- Two grade buttons only: **Wrong** (red) and **Right** (green), mapped to
  FSRS Again and Good. FSRS-6, 90% target retention.
- Study days roll over at 4am local time (`StudyClock`).
- **Mastery** (0–100) is derived from the recognition card's FSRS stability:
  7 days = 40, 30 days = 70, 90 days = 100 (`MasteryRules.fromStability`).
  It replaced an older +10/−30 points score (progress DB version 4 migrates).
- Furigana: shown below mastery 40, hidden with a tap-to-show from 40 to 69,
  off from 70. The answer side always shows it.
- Recognition (Japanese → English) comes first. The production card
  (English → Japanese) unlocks when mastery reaches 40. Production reviews
  schedule their own card but don't change mastery.
- Target retention and the mastery thresholds (furigana and production)
  are fixed constants (`MasteryRules`), not settings, so users aren't
  overwhelmed with choices. Don't add them back unless Daniel asks.
- New words start when a story is finished; there's no new-word queue or
  "Learn next" (removed 2026-10-17). The only new cards in reviews are
  unlocked production cards. Within a sitting, reviews and new cards are
  shuffled.
- "Mark known" lets you set the word's mastery; it gets a review schedule to
  match, first due on a random day in that interval.
- "Study more" pulls every schedule one day earlier (a `daysAhead` counter)
  and adds another day's worth of production cards for today.
- v1 card back is minimal: word, reading, English meanings. No example
  sentences, TTS or pitch accent yet.

Kana:

- Not taught (Daniel, 2026-10-17): kana cards, the kana setup page and
  setting were removed to keep the focus on stories. The app assumes you
  read hiragana and katakana. Progress DB version 9 deletes old kana cards
  (direction 2, negative word ids) and their history.
- No romaji on vocabulary cards.

Other features (all shipped):

- Dictionary search and word pages; non-JLPT words show as "Other".
- Kanji pages, not kanji cards (Daniel, 2026-10-13): meanings, on and kun
  readings (the ones your words use in red), animated stroke order
  (KanjiVG), JLPT level, your words written with it and more words to
  learn. Linked from the word page, the card's answer side and dictionary
  search. Kanji have no mastery or stats of their own; Daniel saw no use
  for them without kanji cards.
- First-run setup: "Which level can you already read?" (None, N5..N1) and
  an optional reminder time; both can change later in Settings. A level
  pre-fills mastery (`ReadLevel`, Daniel 2026-10-17): the chosen level's
  words at mastery 50 (15-day stability), one level easier 70 (30 days),
  two easier 90 days, three or more 365 days. First reviews are spread so
  the fill adds at most ~100 a day. Words already higher keep their
  progress. Raising the level in Settings asks first; lowering changes
  nothing. No random mastery jitter (the random first-due date spreads them).
- Reader word pane: "I forgot this" on words with a review card (not on
  grammar pages), shown as "✓ Forgot" with Undo once tapped; each gives one
  Wrong review when the story is finished. Tapping a word never counts as
  wrong (Daniel: checking conjugations and accidental taps shouldn't
  punish). No burning of words; FSRS long intervals handle mature words.
- Daily reminder has **no default time**; the user picks one. Don't request
  notification permission until they turn it on.
- Manual backup/restore only (`jiwajiwa-<date>.jstudy`, a copy of the progress
  DB). No automatic backups.
- Settings has a reset button that erases all user data.
- Tabs (Daniel, 2026-10-17): Home, Read (the stories), Review, Dictionary.
  Settings opens from a gear on Home; it's rarely needed. The Review tab
  has today's counts, the review heatmap, a link to the Stats page
  (recall, forecast, per-level progress) and Start studying, with the due
  count as a badge on the tab.
- Home (Daniel, 2026-10-17, layout "Journal"): one next-step card with a
  quiet blue "or ..." line under it (`core/Home.kt` `Suggest`): reviews
  first once due reviews reach the review nudge; else the chapter you left
  part way, or the next one in the story you read last; past today's
  new-word limit, an open chapter with nothing new; then reviews alone;
  then "Done for today" with a grained 済 stamp and no button. Under it: a
  Mon–Sun streak (a day counts with a review answered or a chapter
  finished; arrows go back a week at a time), the words graph (seen, known
  and mastered as nested bands, 30 days or all, replayed from the review
  log by `WordHistory`; words marked known or imported count from the
  start), the words started this week ("New words", in the seen colour),
  and the grammar topics met. A chapter left part way reopens on its page
  (the `reader_place` value), unless the reader style has changed since.
- Renshuu import: takes the higher of the two masteries per word, skips
  suspended words, never stores the Renshuu key. Renshuu's `edict_ent` is
  the JMdict id, which is jstudy's word id. Entries without one (kana,
  する verbs, phrases, names) are skipped on purpose.
- Claude-written stories, and the story server they went through, were
  removed (Daniel, 2026-10-15); they may be rebuilt later. Stories are the
  bundled ones only. Tapping a word
  shows its meaning (and conjugation, when conjugated), plus any grammar
  point it's part of (`core/Grammar.kt`); particles and endings are tappable
  for their grammar alone. The meaning opens in a
  pane fixed at the top of the reader, always visible with the story below
  it so it never covers the text (as in Yomu Yomu), not a popover. Long-pressing a word shows its sentence's English,
  bundled with the story. Story
  words you haven't started join your reviews when you finish the story;
  the pane has no way to mark one known. Stories show a page at a time, swiping between
  pages (no scrolling); horizontal pages are top-aligned, vertical pages
  are centred and turn right to left. The text ends with a 読了 seal stamp
  (`FinishStamp`, grained like the icon), under the last line or beside the
  last column, or on a page of its own if the last is full. Tapping it
  marks the story read (timed from opening it) and puts the summary in
  the text's place, showing the
  read's stats (characters, share of words known, time, characters a
  minute), what it did for your words, and what to read next. A gear at the top right of the
  reader opens its settings in the pane: text size (four steps), font (Zen
  Kaku Gothic New by default, Zen Old Mincho serif, Zen Maru Gothic rounded)
  and horizontal or vertical (tategaki) text. They live only there, not in
  Settings, and fit the pane without scrolling.
  Both layouts follow Japanese line-break rules (`core/Kinsoku.kt`,
  `VerticalText.kt`, `HorizontalText.kt`).
- The app's own text turns Japanese as you learn: each label shows in
  English until every word in its Japanese is mastered (the furigana-off
  mastery, 70), then in Japanese. Labels are written as
  `ui("English", "日本語")`; the words are found by reading the Japanese with
  the story reader, so there's no word list to maintain. Explanations and
  text with numbers in it stay English for now.
- Visual design is "Washi" (paper, ink, vermilion seal) with light, dark and
  auto themes. See `app/AGENTS.md`.

The app is named **Jiwa Jiwa** (app id `com.crispyumbrella.jiwajiwa`);
"jstudy" survives only in the repo, code packages and backup file extension.
Google Play holds the app signing key and the CI key is the upload key; see
`PRIVACY.md` and "Google Play" in the README. The first Play release goes to internal
testing.

Every bundled data source allows redistribution: word frequency comes from
Jiten (CC BY-SA 4.0), not JPDB, which has no licence. The dictionary database
is CC BY-SA 4.0, and the EDRDG licence requires regular JMdict updates
(`.github/workflows/update-dictionary.yml`). New data sources must have a
licence that allows this.

Story path (Daniel, 2026-10-14; chapters since 2026-10-15; merged into main 2026-10-17):

- New words and grammar come from the path instead of reviews.
  Reviews and the home count no longer introduce words; only
  unlocked production cards are new there.
- The path (`app/src/main/assets/stories/path.json`, written from
  `tools/stories`, a file per chapter named by its slug, with each story's
  order in its `story.json`; see `tools/stories/AGENTS.md`) is
  stories made of chapters, plus grammar topics. Each chapter's words and
  grammar are worked out when the stories change and shipped in
  `stories/needs.json` (Daniel, 2026-10-17), so the app never reads every
  chapter: at a few hundred chapters that would take seconds on a phone.
  All of it is our own writing for adults, casual speech first: Alex, on a
  one-year working holiday in Tokyo, a share house, a ramen shop job, a
  cast met a few at a time. **Arrival** is the prologue, then **Ramen
  night**, then **The Share House** (24 chapters, Alex's year from spring
  to his flight home), each following the one before (`after`, which can
  also name a chapter to mean the story up to there). The next series
  (Daniel, 2026-10-17) follows a housemate who stays in Japan after Alex
  goes home; The Share House ends on Yuki and a new song she might sing.
  Formal speech comes later as its own topic.
- Dialogue shows who's speaking, script style (台本形式, Daniel 2026-10-17):
  a cast member's quote is written `ユキ|「でしょ？」` in the story and
  shown with the name before it (core `Speech`), at 80% size in a quieter
  colour. In a run of labelled lines the quotes start level, after that
  run's longest name (a long name only widens its own run); a quote that
  wraps carries on one character further in. Same in vertical text, names
  at the top of the column. The cast speaks only once introduced; staff
  and strangers go unnamed or take a description (店員, 男の人), and
  thinking aloud has no name. Names and labels aren't words: not
  tappable, not counted (Daniel, 2026-10-17: fine for now).
- Numbers in kanji and counters are words (三, 時, 円); a number taught
  with its counter is one word (一つ, 二人, 二十歳), the rest are read a
  piece at a time. Numbers in digits are names.
- Vocabulary by frequency (Daniel, 2026-10-17): the stories teach the
  words used most in casual Japanese (Jiten's frequency list), from the
  whole dictionary. JLPT levels are only a hint, never a rule: they label
  words, and break ties between entries spelled the same way.
- Grammar pace (Daniel, 2026-10-17): don't teach too much too fast. Keep
  chapters with no new grammar, using what came before, between the ones
  that teach a topic, and reuse each topic in the chapters after it.
  Stories no longer follow a kana order (kana aren't taught).
- Side stories and one-shots (`side`) use only what the stories they
  follow teach: no new words or grammar. They're there so you can
  keep reading something new without learning anything new.
- Pace (`core/Pace`, `Repository.pace`): a soft daily limit on new words
  started (`newPerDay`, default 20, from the rollover hour), and a
  review nudge (`reviewNudge`, default 50 due, 0 for off). "I know this"
  and imports start nothing, so they don't count. Past either, the Read
  tab leads with open chapters that have nothing new, tapping a chapter
  that adds more shows a sheet suggesting them first ("Read it anyway"
  carries on), and a chapter's end offers one before the next chapter.
  Nothing is ever locked by it.
  Unlocked production cards in reviews fill what's left of the limit.
- A story with one chapter opens straight to it; one with more has a page
  listing its chapters and the people met so far. Titles are English, with
  Japanese where it fits. Chapters and grammar pages are saved in the
  `story` table by `path_id`, with when you read them; rows the app no
  longer ships are removed when it syncs.
- A chapter opens on its own once it's within reach (`StoryPath.gap`): new
  words at most 3% of its running words, and at least 3; and no grammar
  point you haven't seen, other than its own topic. Tapping a locked chapter shows what it's missing and
  the open chapters with the most of it.
- Grammar comes in as topics (one or more related points, like から/まで),
  at most one per chapter; the same topic can be listed on several
  chapters, and whichever you reach first teaches it. Each topic has a full
  page: opened before the chapter, it ends with a button into the chapter,
  which marks its points seen (the `grammar_seen` value, no cards). It's
  also on the Grammar list on the Words tab, and a grammar point's note in
  the reader links to its topic's page; Back returns to the same page of
  the story.
- `StoryPathTest` checks the stories against the built dictionary (skipped
  when it isn't): each story, read in order after the ones it follows,
  opens chapter by chapter, so side stories are never required, and a
  `side` story has nothing new at all; and
  `StoryPath.solve` reaches every chapter from knowing nothing.
  Readiness only grows as you learn, so no reading order can get
  stuck. It also checks path.json, needs.json, the summaries and
  `tools/stories/COVERAGE.md` are up to date. After editing a story, run
  `./gradlew -PcoreOnly=true :core:stories :core:test`; when those files
  conflict in a merge or rebase, rerun `:core:stories` rather than resolving
  them by hand.
- Words: **seen** (has a card, mastery below 40), **known** (40, where
  furigana hides), **mastered** (70).
- Opening a chapter shows its new grammar's page, then the chapter. New words aren't listed up front:
  they're red and underlined in the text, and tapping one shows it.
  Finishing starts every word
  in it you haven't started as a card (`Repository.startStoryWords`).
- At the end of a chapter, each word due for review that you read without
  tapping counts as an FSRS Hard review (`Reviewer.read`): less than a
  Right answer, since context helps. The end page shows how many words were
  seen, moved to known and moved to mastered, the grammar topic, anyone
  you met, and the next chapter.

Not decided or not built yet: example sentences, audio.
