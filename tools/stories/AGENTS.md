# tools/stories/AGENTS.md

The stories that come with the app, as they're written. The app reads
`app/src/main/assets/stories/path.json` (the text) and `needs.json` (each
chapter's words and grammar), both put together from here by the story
tools (core `src/stories`).

## Layout

| Path | What's in it |
| --- | --- |
| `stories.json` | Every story's id, in the order the Stories tab lists them: the main stories, each following the one before, then side stories and one-shots. Add a new story here. |
| `grammar.json` | The grammar topics, in Grammar-list order. Each has `parts`: Japanese lines with their English (`["来た。", "I'm here."]`), and notes in English (plain strings) between them. The first line is the example shown before the chapter that teaches it. Chapters name topics by `id`. |
| `series/<id>/story.json` | A story's `title`, `blurb`, the stories it follows (`after`: story ids, or a chapter id such as `share-house/yukis-gig` to mean the story up to that chapter), `side` for a side story, and `chapters`: its chapters' slugs in reading order. |
| `series/<id>/<slug>.json` | One chapter each, named by its slug (lowercase words joined by hyphens, usually from its title: `trash-day.json`): `title`, a one-line `plot`, optional `topic` (one grammar topic it teaches), `meets` (people it introduces, `{"name", "about"}`) and `readings` (see below), and `paragraphs`, each a list of `[Japanese sentence, English]`, or a note (`{"note": "English…"}`) between two of them. Its id is the story's id and its slug (`share-house/trash-day`), which saved progress uses. To add or move a chapter, add its file and put its slug where it goes in `chapters`; never rename a slug or delete a chapter people may have read. |
| `series/<id>/SUMMARY.md` | Written by the tools, never by hand: the cast so far, each chapter's plot, words and new words, and every word and grammar topic known by the end. |
| `PLAN.md` | The approved plan for the stories being written next: grammar topics in order, and each story's cast and outline. |
| `COVERAGE.md` | Written by the tools: how many of the most common words the stories use, the words left (most common first), and the grammar points left. |

## Conventions

- Titles are English, with Japanese where it fits.
- Speech is casual from the start: Alex and his friends talk the way friends
  do. Staff and strangers are polite, and です/ます come in as their own topic.
- A quote from one of the cast starts with their name and a bar,
  `ユキ|「でしょ？」`, and is shown with the name before it, script style; it
  goes on a line of its own. Someone in the cast speaks only from the
  chapter that introduces them (`meets`) on. Staff and strangers can go
  unnamed, or take a description as their label (`店員|「…」`,
  `男の人|「…」`), which may be used anywhere. Thinking aloud has no name.
  A cast name written in katakana is always read as a name, even where the
  dictionary has a word spelled the same (リン is the guitarist, not 燐).
- English quotes are escaped in JSON: `"\"Morning!\""`.
- When the reader picks the wrong word, pin it in the chapter's `readings`:
  the word as the text writes it, and its reading (`"readings": {"方":
  "かた"}` for あの方, where the reader sees the ほう of ～のほうが). A
  pinned dictionary form pins its conjugations too: `{"描く": "かく"}`
  makes 描いた かいた and 描けたら かけたら.
  Check the new words in the report: a wrong reading there means a wrong
  card. `StoryPathTest` fails if a pin isn't needed, or doesn't take.
- Notes are rare: an aid for the odd spot where the reader needs cultural
  context the dialogue doesn't make clear, or an exception in the language
  explained. Most chapters have none. A note goes after the paragraph it's
  about (never first), and shows as a 注 marker at that paragraph's end,
  opening the note in the reader's pane. Notes aren't read for words.

## Writing a chapter

To continue a story you don't need the others: read its `SUMMARY.md`, its
last chapter or two, and the conventions above. The summary's "Known at
the end" is what a reader has by then. Use those words freely and bring
back ones met only once or twice. Pick new words from `COVERAGE.md`, most
common first: the stories teach the words used most in casual Japanese,
whatever their JLPT level, and any word in the dictionary can be used.

Chapters grow as the reader's words do: the allowance grows with length,
and the next few hundred common words need chapters of 150 to 300 words.
A short chapter now and then is fine, but one under half as long as the
chapters before it fails the check.

A chapter's new words must fit its allowance (3% of its running words, and at
least 3), it may teach one grammar topic (and should usually teach none,
see the grammar pace in the root AGENTS.md), and every word should appear in
at least two chapters. Side stories have nothing new at all.

Every particle, ending and changed verb or adjective in a chapter (and on a
grammar page) must belong to a grammar point in `core/Grammar.kt`, or the
check fails with "no grammar point explains …". If the point exists, its
topic must be taught by then; if none does, add the point (with an example
`GrammarTest` checks) and a topic for it in `grammar.json`, or reword the
sentence. A changed form no point explains but the dictionary lists as one
entry (いらっしゃいませ) is read as that word instead. でも, けど, だけど and
だって opening a sentence are words, not grammar.

The reader reads ～られる on a る verb (食べられる) and spoken 食べれる, 来れる
as "can" (the `potential` point), and ～れる / ～られる on a う verb or する
as the passive. For a passive on a る verb, reword. It writes 来られる
in kanji poorly (as 来る きたる), so use 来れる.

Known misreadings to pin (`readings`) or reword around: 何時 reads as いつ
(pin `"何時": "なんじ"`), 描く as えがく (pin `"描く": "かく"` when it means
drawing), 弦 as つる (pin `"弦": "げん"`), 弾ける as はじける (reword: 弾けない,
弾けた are fine), 勝ったら opening a sentence as 勝る (put a subject or もし
first), ありがとうって as ありがと + 売る (write 『ありがとう』って), よし as 葦, いつか as 五日, いい straight after ！ as
言う, and the ます of a quoted 「です、ます」 as
増す. な before a noun (きれいな部屋) and before ん / の (そうなんだ) is taught
with だ / です, so it's fine anywhere. ただいま, おかえり(なさい), いってきます
and いってらっしゃい read as words, and ～ないで来る as "come without doing".
ない on its own (ゴミがない) is the word 無い, and ありがとうございました is a
different entry from ありがとうございます: both count as new words.

More to reword around, found writing Phase 1: ～たことはない (write
～たことがない), 大きすぎ and a bare し before すぎ (練習しすぎ is fine), 静かに
as an adverb, 来るみたい (来る as きたる), 弾きたい (as はじく), 休めない (as
休める), 聞けた (as 聞ける), ～てたんだって (たん as 歎), 変わったって (as
～たって), なんで / 何の (the なん entry), よかった (as よい), あげる "give"
(the 上げる entry), a stuttered ご、ごめん (後), 送りに行く (送り as a noun),
終わり on its own (the stem of 終わる), and 何ページ (the なん entry).
Also: 弾きました (as はじく; 弾いた is fine), の marking the subject in a
description (ソラの笑う声), ～そうに as an adverb, ～かもしれません (しれる is
a new word; かも is taught), 男の人 in narration (男 is a new word; it's fine
as a speaker label, or write あの人), and と opening a sentence (…と、思う).

Sentences split at 。, ！ and ？ only: a line ending in … alone runs into
the next one, and so do several 『…』 lyric lines in one paragraph, which
breaks the sentence and translation count. End it with 。 or give each line
its own paragraph. A side story is held to the length rule too, counting
the main chapters before the one it follows.

Then run, with the dictionary built (`python3 tools/dictionary/build_dictionary.py`):

    ./gradlew -PcoreOnly=true :core:stories :core:test

`:core:stories` puts path.json together, reads every chapter with the app's
reader and dictionary lookup, and writes `needs.json`, each `SUMMARY.md` and
`COVERAGE.md`. `StoryPathTest` checks the rules above, that the solver
reaches every chapter, and that all of those files are up to date; its
report (`core/build/reports/story-path.txt`) lists each chapter's new words.
Commit all of them with the chapter.

Those files are worked out from every story, so two branches that each
change a story both change them, and conflict when the second is merged or
rebased. Never resolve them by hand: take either side, rerun
`:core:stories`, and commit what it writes.
