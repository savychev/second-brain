# 🧠 Second Brain

<p>
  <a href="https://github.com/savychev/second-brain/actions/workflows/ci.yml"><img src="https://github.com/savychev/second-brain/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 4.1">
  <img src="https://img.shields.io/badge/SQLite-003B57?logo=sqlite&logoColor=white" alt="SQLite">
  <img src="https://img.shields.io/badge/tests-139%20passing-brightgreen" alt="139 tests">
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

> Status: **Stage 3 — REST API + SQLite** ✅ · Java 21 · Spring Boot 4.1 · SQLite

---

## Why

Thoughts, ideas, tasks and links come up all day and get lost: some stay in your head, some
end up scattered across chats and note apps. There is no single entry point to quickly "dump"
a thought into — and no system that files it automatically.

**Second Brain** is that entry funnel: capture in seconds, automatic classification, and a
local copy that guarantees zero loss. Notion remains the place where notes are stored and read.

## What it does today

- ✅ Accepts a thought of any length and stores it with a timestamp
- ✅ Classifies it as **IDEA / TASK / LINK / NOTE** using transparent rules
  (≥ 80% accuracy on the test set)
- ✅ Extracts `#tags`
- ✅ Persists everything in **SQLite** — nothing is lost across restarts
- ✅ **Pushes notes to Notion** — each type into its own database
- ✅ **Retry queue**: if Notion is down the note waits locally and is sent later,
  automatically, on a schedule
- ✅ Three ways in: interactive REPL, one-shot quick capture, and a **REST API**
- ✅ 139 automated tests, CI on Linux and Windows, correct Cyrillic handling

## Quick start

All you need is **Java 21+**. No Maven required — the wrapper (`mvnw`) is included.

```bash
./mvnw clean package        # Windows: .\mvnw.cmd clean package
```

### As a console tool

```bash
java -jar target/second-brain.jar
```

```
› need to buy plane tickets #trip
  ✓ [TASK] saved
    action word found: "need"
    tags: trip
    → sent to Notion
› :stats
  Storage: SQLite database …/data/second-brain.db
  Total notes: 1
› :quit
```

REPL commands: `:list [TYPE]`, `:stats`, `:sync`, `:help`, `:quit`.

### As a REST service

```bash
java -jar target/second-brain.jar --serve
```

Then capture a thought over HTTP:

```bash
curl -X POST http://127.0.0.1:8080/notes \
     -H "Content-Type: application/json; charset=utf-8" \
     -d '{"text":"need to pick up the parcel #home"}'
```

```json
{
  "note": { "id": "b30ed3e7…", "type": "TASK", "tags": ["home"], "synced": true },
  "classification": { "confidence": 0.8, "reason": "action word found: \"need\"" },
  "notion": { "status": "SENT", "detail": "3aedf8e5…" }
}
```

**Interactive API docs** — open <http://127.0.0.1:8080/swagger-ui.html> and try any
endpoint from the browser.

| Method | Path | What it does |
|---|---|---|
| `POST` | `/notes` | Capture a thought. `201 Created` + `Location` |
| `GET` | `/notes` | Feed, newest first. `?type=TASK&limit=20&offset=0` |
| `GET` | `/notes/{id}` | A single note |
| `GET` | `/stats` | Counts per type, storage location, queue length |
| `POST` | `/sync` | Flush the Notion retry queue by hand |

> The server binds to `127.0.0.1` only. There is no authentication yet — see
> [Known limitations](#known-limitations).

## The zero-loss guarantee

This is the property the whole design is built around, so it is worth stating precisely:

**A note is written to local storage first, and pushed to Notion second.**

The consequences are visible throughout the API:

- `POST /notes` answers **`201 Created` even when Notion is unreachable**. Returning an error
  would be a lie — the note *is* saved — and would invite the client to retry, creating a
  duplicate page. The outcome of the push is reported separately, in the `notion` block.
- Unsent notes sit in a queue defined by exactly one predicate: `notion_page_id IS NULL`.
  They are flushed on the next start, on a schedule while the server runs, or on demand
  via `POST /sync` / `:sync`.
- A background job and a console process can run at the same time, so flushing is guarded by
  a **cross-process lock** held in the database itself. Without it both could take the same
  note from the queue and create two pages for one thought.

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
[Input: console · REST API · Telegram bot (stage 5)]
              ↓
        [Java backend]
              ↓
 [Classifier: rules → Anthropic API (stage 4)]
              ↓
   [SQLite]  →  [Notion API]
```

Two interfaces carry the design: `Classifier` and `NoteRepository`. Behind them,
implementations swap without touching anything else — which is how JSON storage became
SQLite mid-project, with the application still working after every step.

```
src/main/java/com/secondbrain/
├── App.java                     entry point: REPL / --stdin / --sync / --import-json / --serve
├── SecondBrainApplication.java  Spring entry point (server mode only)
├── model/       Note, NoteType
├── classify/    Classifier, RuleBasedClassifier, Tags, Urls
├── storage/     NoteRepository, JsonNoteRepository, SqliteNoteRepository,
│                Storages, Timestamps, JsonToSqliteImporter
├── notion/      NotionClient, HttpNotionClient, NotionSyncService,
│                SyncLock, SqliteSyncLock, NotionFlushJob
├── core/        CaptureService  orchestration: classify → tags → save → push
├── cli/         ConsoleApp
├── config/      AppConfig, OpenApiConfig
└── web/         NoteController, SyncController, ApiExceptionHandler, dto/
```

**Spring lives at the edges, not in the core.** `CaptureService`, `NotionSyncService`,
`Storages` and the whole `classify` package carry no framework annotations. Wiring is
described once, in `config/AppConfig`. That is what lets the console mode assemble the same
components by hand and run without Spring at all — a capture never depends on a running server.

## Design decisions

Short notes on choices a reviewer might question.

**`JdbcClient` instead of JPA/Hibernate.** The deciding argument was not "less magic" but
portability of the repository: `JdbcClient.create(dataSource)` works *without* a Spring
context, so one repository class serves both the REST server and the console tool. An
`EntityManagerFactory` cannot be raised outside a container. Two secondary reasons: Hibernate's
SQLite dialect is community-grade and outside the tested matrix, and `flyway-database-sqlite`
does not exist on Maven Central at all. A JPA adapter over PostgreSQL is planned as a separate
stage — it can pass the very same `NoteRepositoryContractTest`.

**Tags as a JSON column, not a `note_tags` table.** Keeps `save()` a single INSERT, atomic on
its own. A separate table would need a transaction, and the console mode has no Spring context
to provide one — `@Transactional` there would silently do nothing. SQLite can already query
inside the column: `SELECT n.* FROM notes n, json_each(n.tags) t WHERE t.value = ?`.

**Timestamps stored as fixed-width text.** SQLite has no date type and `ORDER BY` here is a
string comparison, but `Instant.toString()` trims trailing zeros — so `…06Z` sorts *after*
`…06.3Z` (`'Z'` 0x5A > `'.'` 0x2E) and the feed would silently scramble. See
[`Timestamps`](src/main/java/com/secondbrain/storage/Timestamps.java).

**Synchronous Notion push inside the request.** `POST /notes` waits for Notion, so a hung
Notion means a slow request. Accepted on purpose: the response can then state `SENT` or
`QUEUED` honestly, and the zero-loss invariant stays visible in three lines of
`CaptureService`. Asynchrony would add a third state and a lot of machinery for a
single-user tool.

**Storage migration behind a switch.** JSON and SQLite implementations coexist;
`SECOND_BRAIN_STORAGE=json|sqlite` picks one. Rollback costs one environment variable rather
than restoring a backup, and both implementations are held to one
[contract test](src/test/java/com/secondbrain/storage/NoteRepositoryContractTest.java).

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

The token can also come from the `NOTION_TOKEN` environment variable, which takes precedence.
`notion.properties` is never committed.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SECOND_BRAIN_STORAGE` | `sqlite` | Storage engine: `sqlite` or `json` |
| `SECOND_BRAIN_DB` | `data/second-brain.db` | SQLite database file |
| `SECOND_BRAIN_DATA` | `data/notes.json` | JSON file (legacy storage, import source) |
| `NOTION_TOKEN` | — | Notion integration secret; overrides the file |
| `NOTION_CONFIG_FILE` | `notion.properties` | Where to read Notion settings from |

Server-side settings live in `src/main/resources/application.properties`
(port, bind address, background flush interval).

Migrating an existing JSON file into SQLite:

```bash
java -jar target/second-brain.jar --import-json
```

The import is idempotent and read-only with respect to the source file. It refuses to report
success if any note would lose its "already in Notion" mark — that mark is the only thing
standing between a re-import and duplicate pages.

## Tests

```bash
./mvnw test
```

139 tests. Beyond the obvious, they pin the decisions that are easy to break silently:

- classification accuracy stays ≥ 80% on a fixed set of real thoughts
- both storage implementations satisfy one shared contract
- string ordering of timestamps matches chronological ordering
- a capture still succeeds when Notion is unreachable, and the note stays queued
- re-running the JSON→SQLite import does not resurrect an already-published note
- two processes flushing at once create exactly one Notion page
- tests cannot reach the real Notion workspace or the real database
  ([`NotionConfigSafetyTest`](src/test/java/com/secondbrain/notion/NotionConfigSafetyTest.java))

## Known limitations

- **No authentication.** The server binds to `127.0.0.1` only. Authorisation has to be solved
  before the Telegram bot (stage 5) can talk to it from outside.
- **A narrow duplicate window remains.** If the Notion page is created but recording its id
  locally fails, the result is reported as `ORPHANED`, flushing stops and the page id is
  printed so it can be entered by hand. Closing this fully means reading the page back by the
  `ID` property that every page already carries — out of scope for stage 3.
- **Rollback to JSON has a horizon.** Notes captured after the switch to SQLite do not appear
  in the JSON file; there is no reverse export.
- **Keep `data/` off OneDrive and network drives** — SQLite locking is unreliable there.

## Roadmap

| Stage | What | Status |
|-------|------|--------|
| 1 | Console core: model, rule-based classifier, JSON storage | ✅ done |
| 2 | Notion API integration + retry queue | ✅ done |
| 3 | REST API on Spring Boot + SQLite | ✅ done |
| 3.5 | JPA adapter over PostgreSQL (same contract test) | ⬜ |
| 4 | Smart classification via the Anthropic API | ⬜ |
| 5 | Telegram bot as a mobile entry point | ⬜ |

## License

[MIT](LICENSE)
