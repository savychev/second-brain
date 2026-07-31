# 🧠 Second Brain

<p>
  <a href="https://github.com/savychev/second-brain/actions/workflows/ci.yml"><img src="https://github.com/savychev/second-brain/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Maven-C71A36?logo=apachemaven&logoColor=white" alt="Maven">
  <img src="https://img.shields.io/badge/tests-32%20passing-brightgreen" alt="32 tests">
  <img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT">
</p>

**A single capture point for your thoughts.** Type (or dictate) a thought as one line — the
system figures out what it is (an idea, a task, a link or a note) and files it in the right
place. Your head stays clear; nothing gets lost.

> 🇳🇱 **Eén invoerpunt voor al je gedachten.** Typ een gedachte als één regel — het systeem
> bepaalt zelf wat het is (idee, taak, link of notitie) en bergt ze op de juiste plek op.
> Hoofd leeg, niets gaat verloren.
>
> 🇷🇺 Русская версия: [README.ru.md](README.ru.md)

> Status: **Stage 2 — Notion integration** ✅ · Java 21 · Maven · no runtime dependencies except Jackson

---

## Why

Thoughts, ideas, tasks and links come up all day and get lost: some stay in your head, some
end up scattered across chats and note apps. There is no single entry point to quickly "dump"
a thought into — and no system that files it automatically.

**Second Brain** is that entry funnel: capture in seconds, automatic classification, a local
copy guaranteeing zero loss. Storage and browsing move to Notion in stage 2+.

## What it does today

- ✅ Accepts a thought of any length and stores it with a timestamp
- ✅ Classifies it as **IDEA / TASK / LINK / NOTE** using transparent rules
  (≥ 80% accuracy on the test set)
- ✅ Extracts `#tags`
- ✅ Persists everything locally as JSON — nothing is lost across restarts
- ✅ Interactive mode (REPL) and one-shot quick capture
- ✅ **Pushes notes to Notion** — each type into its own database
- ✅ **Retry queue**: if Notion is down, the note waits locally and is sent on the next run
- ✅ 32 automated tests; correct Cyrillic handling on Windows

## Quick start

All you need is **Java 21+**. No Maven required — the wrapper (`mvnw`) is included.

```bash
# build
./mvnw clean package        # Windows: .\mvnw.cmd clean package

# run interactive mode
java -jar target/second-brain.jar
```

Example session:

```
› need to buy plane tickets #trip
  ✓ [TASK] saved
    action word found: "need"
    tags: trip
› idea: dark theme for the app
  ✓ [IDEA] saved
    idea marker found: "idea"
› https://spring.io/guides
  ✓ [LINK] saved
    text contains a link
› :stats
  Total notes: 3
    IDEA : 1
    TASK : 1
    LINK : 1
    NOTE : 0
› :quit
```

REPL commands: `:list [TYPE]`, `:stats`, `:sync`, `:help`, `:quit`.

> Notes are stored in `data/notes.json`. Override the path with the `SECOND_BRAIN_DATA`
> environment variable. The `data/` folder is not committed (personal thoughts).

## Notion setup

The app works without it — notes are simply kept locally. To push them to Notion:

1. **Create an integration** at [notion.so/my-integrations](https://www.notion.so/my-integrations)
   → *New integration* → copy the **Internal Integration Secret**.
2. **Create 4 databases** (ideas / tasks / links / notes) with these properties:
   `Мысль` (title), `Захвачено` (date), `Теги` (multi-select), `Источник` (select),
   `ID` (text); the links database also needs `Ссылка` (url).
3. **Grant the integration access**: on the page holding the databases → `···` →
   *Connections* → pick your integration.
4. **Fill in the config**:

```bash
cp notion.properties.example notion.properties
# set the token and the database ids (an id is part of the database URL)
```

The token can also come from the `NOTION_TOKEN` environment variable, which takes
precedence over the file. `notion.properties` is never committed.

### The zero-loss guarantee

A note is saved locally **first** and pushed to Notion **second**. If Notion is
unreachable the capture still succeeds and the note stays queued:

```
› need to call the doctor
  ✓ [TASK] saved
    action word found: "need"
    → Notion unreachable, queued (...)
```

The queue is flushed automatically on the next run, or on demand:

```bash
java -jar target/second-brain.jar --sync    # or the :sync command in the REPL
```

## How classification works

Stage 1 uses transparent rules ([`RuleBasedClassifier`](src/main/java/com/secondbrain/classify/RuleBasedClassifier.java)),
priority top-down:

1. contains an **action word** (`need`, `buy`, `call`, `todo`…) → **TASK**
2. contains an **idea marker** (`idea`, `what if`, `would be cool`…) → **IDEA**
3. contains a **link** (URL or domain) → **LINK**
4. otherwise → **NOTE**

A task deliberately outranks a link: "send the link to…" is a task, not a bookmark.

Accuracy is enforced by a test over ~30 real thoughts
([`RuleBasedClassifierTest`](src/test/java/com/secondbrain/classify/RuleBasedClassifierTest.java)).
The rules' blind spots will be covered by smart classification via the Anthropic API
(stage 4) — the [`Classifier`](src/main/java/com/secondbrain/classify/Classifier.java)
interface is ready for a drop-in replacement.

## Architecture

```
[Input: console → REST API → Telegram bot]
              ↓
        [Java backend]
              ↓
 [Classifier: rules → Anthropic API]
              ↓
[Local store (JSON → SQLite)] → [Notion API]
```

The seams are designed for the next stages: behind the `Classifier` and `NoteRepository`
interfaces, implementations can be swapped without rewriting the rest.

```
src/main/java/com/secondbrain/
├── App.java                     entry point (REPL / one-shot)
├── model/       Note, NoteType  domain model
├── classify/    Classifier, RuleBasedClassifier, Tags, Urls
├── storage/     NoteRepository, JsonNoteRepository
├── notion/      NotionClient, HttpNotionClient, NotionSyncService, NotionConfig
├── core/        CaptureService  orchestration: classify → tags → save → push
└── cli/         ConsoleApp      console interface
```

## Tests

```bash
./mvnw test
```

Covered: classification accuracy (≥ 80% threshold), persistence across restarts, filtering
by type, tag extraction, end-to-end capture scenario, Notion request building, and retry-queue
behaviour when Notion is unreachable.

## Roadmap

| Stage | What | Status |
|-------|------|--------|
| 1 | Console core: model, rule-based classifier, JSON storage | ✅ done |
| 2 | Notion API integration + retry queue | ✅ done |
| 3 | REST API on Spring Boot + SQLite | ⬜ |
| 4 | Smart classification via the Anthropic API | ⬜ |
| 5 | Telegram bot as a mobile entry point | ⬜ (optional) |

## License

[MIT](LICENSE)
