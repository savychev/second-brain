# Этап 3: REST API на Spring Boot + SQLite — план работ

> Составлен 31.07.2026. Три независимых архитектуры, разбор рецензентами, отдельный поиск
> рисков; версии проверены по repo1.maven.org и spring.io, а не по памяти.

---

## 1. Рекомендация

Берём **тонкий Spring Boot поверх голого SQL** (`JdbcClient` вместо JPA/Hibernate) и
**аддитивную миграцию**: ничего не удаляем, SQLite-хранилище появляется рядом с JSON-овым,
боевое хранилище переключается одной переменной окружения только после того, как импорт
доказал свою правильность.

Три дизайна разошлись по JPA: два из трёх выбрали `JdbcClient`, и рецензент JPA-варианта нашёл
в нём неразрешимую зависимость — артефакта `org.flywaydb:flyway-database-sqlite` **не
существует** на Maven Central ни в какой версии. Решающий довод: `JdbcClient.create(dataSource)`
работает **без Spring-контекста**, поэтому один и тот же класс репозитория обслуживает и
сервер, и ежедневный `sb.ps1` — а `EntityManagerFactory` вне контейнера не поднимешь.

**Честная цена решения:** в резюме не появится строчки «Spring Data JPA / Hibernate», а в
вакансиях Java-разработчика в NL/BE она стоит почти везде. Это не бесплатно. Поэтому в план
входит раздел ADR в README («почему JdbcClient, и когда я бы взял JPA»), а вопрос о JPA-адаптере
отдельным этапом вынесен в решения (раздел 8).

---

## 2. Что появится

Ни один существующий файл не удаляется. `NoteRepository` получает **два default-метода** (это
не ломает ни одну реализацию и ни один тест), всё остальное — только новые файлы.

```
src/main/java/com/secondbrain/
├── App.java                        [ИЗМЕНЁН ~20 строк] диспетчер: --serve → Spring,
│                                     всё остальное — сегодняшний путь без Spring
├── SecondBrainApplication.java     [НОВЫЙ] @SpringBootApplication + @EnableScheduling
├── config/
│   ├── AppConfig.java              [НОВЫЙ] @Bean-ы: Classifier, NoteRepository, NotionConfig,
│   │                                 NotionClient, NotionSyncService, CaptureService, DataSource
│   └── ApiProperties.java          [НОВЫЙ] @ConfigurationProperties("secondbrain")
├── model/      Note.java, NoteType.java                    [БЕЗ ИЗМЕНЕНИЙ]
├── classify/   все 5 файлов                                [БЕЗ ИЗМЕНЕНИЙ]
├── core/       CaptureService.java                          [БЕЗ ИЗМЕНЕНИЙ]
├── cli/        ConsoleApp.java                              [БЕЗ ИЗМЕНЕНИЙ]
├── storage/
│   ├── NoteRepository.java         [+2 default-метода] findPage(...), countByType()
│   ├── JsonNoteRepository.java     [БЕЗ ИЗМЕНЕНИЙ] остаётся навсегда: откат + источник импорта
│   ├── SqliteNoteRepository.java   [НОВЫЙ ~160 строк] единственный класс с SQL
│   ├── SqliteSchema.java           [НОВЫЙ ~25] выполняет schema.sql; общий для обоих режимов
│   ├── SqliteFiles.java            [НОВЫЙ ~25] DataSource: абсолютный путь + WAL + busy_timeout
│   ├── Timestamps.java             [НОВЫЙ ~15] Instant ↔ ISO-8601 ФИКСИРОВАННОЙ ширины
│   └── JsonToSqliteImporter.java   [НОВЫЙ ~60] идемпотентный перенос notes.json → SQLite
├── notion/
│   ├── NotionClient/HttpNotionClient/NotionConfig/NotionException  [БЕЗ ИЗМЕНЕНИЙ]
│   ├── NotionSyncService.java      [ИЗМЕНЁН] +конструктор с SyncLock, старый 3-аргументный
│   │                                 делегирует в него → все 5 тестов живут без правок
│   ├── SyncLock.java               [НОВЫЙ] интерфейс: tryAcquire()/release() + реализация в JVM
│   ├── SqliteSyncLock.java         [НОВЫЙ ~35] межпроцессный замок на строке в БД (аренда)
│   └── NotionFlushJob.java         [НОВЫЙ ~35] @Scheduled досылка (только в режиме --serve)
└── web/                            [НОВЫЙ ПАКЕТ]
    ├── NoteController.java         POST /notes, GET /notes, GET /notes/{id}, GET /stats
    ├── SyncController.java         POST /sync
    ├── ApiExceptionHandler.java    @RestControllerAdvice extends ResponseEntityExceptionHandler
    ├── NoteNotFoundException.java
    └── dto/  CreateNoteRequest, NoteResponse, CapturedResponse, ClassificationView,
               NotionView, StatsResponse, SyncResponse   (все — record)

src/main/resources/                 [НОВЫЙ КАТАЛОГ]
├── application.properties
└── schema.sql

src/test/java/com/secondbrain/
├── (6 существующих классов — БЕЗ ЕДИНОЙ ПРАВКИ, 32 теста)
├── storage/NoteRepositoryContractTest.java   [НОВЫЙ] абстрактный контракт интерфейса
├── storage/SqliteNoteRepositoryTest.java     [НОВЫЙ] extends контракт + SQLite-специфика
├── storage/TimestampsTest.java               [НОВЫЙ] фиксированная ширина + сортировка
├── storage/JsonToSqliteImporterTest.java     [НОВЫЙ] «без дублей в Notion»
├── notion/SqliteSyncLockTest.java            [НОВЫЙ] два DataSource на один файл
├── web/NoteControllerTest.java               [НОВЫЙ] @WebMvcTest + MockMvc
├── web/SyncControllerTest.java               [НОВЫЙ]
└── SecondBrainApplicationTests.java          [НОВЫЙ] @SpringBootTest, contextLoads

pom.xml                   [ИЗМЕНЁН] parent + стартеры, shade → spring-boot-maven-plugin
.gitignore                [ИЗМЕНЁН] +*.db, *.db-wal, *.db-shm  ← ПЕРВЫЙ коммит этапа
.github/workflows/ci.yml  [ИЗМЕНЁН] +windows-latest в матрицу
sb.ps1                    [БЕЗ ИЗМЕНЕНИЙ]
```

Итог: ~32 → ~55 тестов, `./mvnw verify` по-прежнему одна команда.

---

## 3. API

Сервер слушает **127.0.0.1:8080** (аутентификации нет — см. риски).

| Метод | Путь | Что делает |
|---|---|---|
| POST | `/notes` | Захват мысли: классификация → теги → SQLite → попытка отправки в Notion. `201` + `Location` |
| GET | `/notes` | Лента, от новых к старым; `?type=TASK&limit=20&offset=0`. Пусто → `[]`, не 404 |
| GET | `/notes/{id}` | Одна заметка. Нужен, иначе `Location` в 201 указывает в никуда |
| GET | `/stats` | Счётчики по типам + длина очереди (HTTP-двойник `:stats`) |
| POST | `/sync` | Ручная досылка очереди (HTTP-двойник `:sync` / `--sync`) |

Пример ответа `POST /notes`:

```json
{
  "note": {"id":"9f1c…","type":"TASK","tags":["поездка"],"synced":true},
  "classification": {"confidence":0.8,"reason":"найдено слово-действие: «надо»"},
  "notion": {"status":"SENT","detail":"3aedf8e5…"}
}
```

Правила, которые важнее самих путей:

- **Notion лежит → всё равно `201`**, меняется только блок `"notion":{"status":"QUEUED"}`.
  Это требование P0 №5, выраженное по-HTTP. На это есть отдельный тест: клиент Notion замокан
  на падение, ответ обязан быть 201, заметка обязана лежать в репозитории.
- `POST /sync` возвращает `200` даже при частичной досылке и при выключенной интеграции.
  Сломан внешний Notion, а не Second Brain.
- Ошибки — **RFC 9457 `ProblemDetail`** (встроен в Spring 6, своего DTO не пишем).
  `ApiExceptionHandler extends ResponseEntityExceptionHandler` и **переопределяет**
  `handleMethodArgumentNotValid`, а не объявляет второй `@ExceptionHandler` на тот же тип —
  иначе обработчик оказывается мёртвым: у автоконфигурации Boot приоритет `@Order(0)`,
  а у обычного advice — самый низкий.
- **`@Validated` на классе контроллера НЕ ставим.** С ним `@Min/@Max` на `@RequestParam`
  начинают кидать `ConstraintViolationException`, который никто не обрабатывает → 500 вместо
  400. `limit`/`offset` зажимаем в коде: `Math.clamp(limit, 1, 500)` (из Java 21).
- Валидация тела: на record-DTO `@NotBlank` + `@Size(max = 10_000)` на `text`.
- `Note` наружу **не отдаём ни в ту, ни в другую сторону**. Приняв `Note` как `@RequestBody`,
  клиент смог бы прислать `notionPageId` и отравить очередь досылки — заметка считалась бы
  отправленной, никогда не побывав в Notion.

---

## 4. Хранилище

### Схема (`src/main/resources/schema.sql`, идемпотентна, выполняется при каждом старте)

```sql
CREATE TABLE IF NOT EXISTS notes (
    id             TEXT PRIMARY KEY,           -- UUID, назначает приложение (Note.create)
    text           TEXT NOT NULL,              -- ровно как ввёл пользователь
    type           TEXT NOT NULL CHECK (type IN ('IDEA','TASK','LINK','NOTE')),
    -- ISO-8601 UTC, ВСЕГДА 9 знаков после точки.
    -- Instant.toString() так не умеет: он обрезает нули, и тогда
    -- "…06Z" и "…06.3Z" сортируются как строки НЕВЕРНО ('.'=0x2E < 'Z'=0x5A),
    -- а ORDER BY created_at у нас строковый.
    created_at     TEXT NOT NULL,
    tags           TEXT NOT NULL DEFAULT '[]', -- JSON-массив, порядок как в тексте
    source         TEXT NOT NULL DEFAULT 'unknown',
    notion_page_id TEXT                        -- NULL = ещё в очереди на отправку
);

CREATE INDEX IF NOT EXISTS idx_notes_created_at      ON notes(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notes_type_created_at ON notes(type, created_at DESC);

-- Частичный индекс: в нём лежат ТОЛЬКО неотправленные, то есть ровно под findUnsynced().
-- Ушла заметка в Notion — выпала из индекса. Индекс всегда крошечный.
CREATE INDEX IF NOT EXISTS idx_notes_pending ON notes(created_at) WHERE notion_page_id IS NULL;

-- Межпроцессный замок на досылку. У SQLite нет SELECT ... FOR UPDATE,
-- поэтому замок делаем сами: аренда на строке, взятие — условный UPDATE.
CREATE TABLE IF NOT EXISTS sync_lock (
    id         INTEGER PRIMARY KEY CHECK (id = 1),
    holder     TEXT,
    expires_at TEXT NOT NULL DEFAULT ''
);
INSERT OR IGNORE INTO sync_lock (id, holder, expires_at) VALUES (1, NULL, '');
```

**Почему теги — JSON-колонка, а не таблица `note_tags`** (здесь дизайны разошлись): тогда
`save()` остаётся **одним** INSERT и атомарен сам по себе. Отдельная таблица потребовала бы
транзакции, а в консольном режиме Spring-контекста нет — `@Transactional` там просто молча не
работает (нет прокси), и получилась бы заметка без тегов при сбое. Когда на этапе 4 понадобится
«покажи всё с тегом X», SQLite умеет и так:
`SELECT n.* FROM notes n, json_each(n.tags) t WHERE t.value = ?`.

### Как реализован `NoteRepository`

`SqliteNoteRepository implements NoteRepository`, конструктор принимает `DataSource`, внутри —
`JdbcClient.create(ds)`.

```sql
-- save(Note): ON CONFLICT делает импорт и повторный запуск безопасными
INSERT INTO notes (id, text, type, created_at, tags, source, notion_page_id)
VALUES (:id,:text,:type,:createdAt,:tags,:source,:notionPageId)
ON CONFLICT(id) DO NOTHING;

-- findAll()      : SELECT … ORDER BY created_at DESC
-- findByType(t)  : SELECT … WHERE type = :type ORDER BY created_at DESC
-- findUnsynced() : SELECT … WHERE notion_page_id IS NULL ORDER BY created_at ASC
-- count()        : SELECT COUNT(*) FROM notes
-- countByType()  : SELECT type, COUNT(*) FROM notes GROUP BY type   ← :stats одним запросом
--                  (сегодня :stats делает 5 полных проходов по данным)
-- findPage(…)    : … ORDER BY created_at DESC LIMIT :limit OFFSET :offset

-- markSynced(id, pageId): условие IS NULL — страховка от гонки,
-- уже проставленный page id перезаписать нельзя
UPDATE notes SET notion_page_id = :pageId WHERE id = :id AND notion_page_id IS NULL;
```

Две несущие детали:

```java
// Timestamps.java — фиксированная ширина, 9 знаков всегда
private static final DateTimeFormatter FIXED =
        new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
// 30 символов и для 18:59:06Z, и для 18:59:06.305123200Z; Instant.parse разбирает обратно точно
```

```java
// notionPageId бывает null → явный SQL-тип, не полагаемся на угадывание драйвером.
// Пустую строку нормализуем в NULL: Note.isSynced() считает пустую строку
// «не отправлено», а SQL-предикат IS NULL — «отправлено». Расхождение
// означало бы заметку, которая никогда не уйдёт в Notion.
.param("notionPageId", blankToNull(note.notionPageId()), Types.VARCHAR)
```

`DataSource` — **не** автоконфигурация Boot, а свой бин, потому что тот же код нужен консоли:

```java
SQLiteConfig cfg = new SQLiteConfig();
cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);  // читатели не блокируют писателя
cfg.setBusyTimeout(5000);                          // второй писатель ждёт, а не падает
SQLiteDataSource ds = new SQLiteDataSource(cfg);
ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath().normalize());
```

Пула соединений нет: для однопользовательского инструмента соединение SQLite стоит микросекунды.
Путь **абсолютный** — иначе SQLite при запуске из другого рабочего каталога молча создаст
**новую пустую базу**, и покажется, что заметки пропали. При старте логируем путь и `count()`.

### Миграция из `data/notes.json` — без дублей в Notion

На диске 2 заметки, у **обеих** проставлен `notionPageId`. Очередь досылки определяется ровно
одним предикатом: «есть ли у заметки page id». Значит вся защита от дублей — это **не потерять
поле**, а не написать хитрую логику.

```java
public static Report importAll(Path jsonFile, NoteRepository target) {
    if (!Files.exists(jsonFile)) return Report.empty();
    List<Note> source = new JsonNoteRepository(jsonFile).findAll();
    for (Note n : source) {
        target.save(n);   // ВСЕ семь полей, включая notionPageId
    }                     // НЕ через CaptureService и НЕ через Note.create()
    return new Report(...);
}
```

Три слоя защиты: (1) сохраняем `notionPageId` → `findUnsynced()` эти заметки не вернёт;
(2) `ON CONFLICT(id) DO NOTHING` → импорт можно гонять сколько угодно раз; (3) `notes.json`
не удаляется и не меняется — откат остаётся рабочим бессрочно.

**Ритуал переезда (шаг 4), выполнить руками:**

```bash
copy data\notes.json data\notes.json.pre-stage3.backup

java -jar target\second-brain.jar --import-json
#   прочитано: 2, вставлено: 2, пропущено: 0, из них уже в Notion: 2

sqlite3 data\second-brain.db "SELECT COUNT(*), COUNT(notion_page_id) FROM notes;"
#   ожидаем 2|2

# ГЛАВНАЯ ПРОВЕРКА — «а не отправится ли всё заново?»
set SECOND_BRAIN_STORAGE=sqlite
java -jar target\second-brain.jar --sync
#   ожидаем: «Очередь пуста — всё уже в Notion.»  Ни одной новой страницы.
```

Последняя команда и есть приёмка миграции. Потеряйся хоть один `notionPageId` — дубль виден
немедленно, на двух заметках, с целым `notes.json` под рукой.

---

## 5. Что будет с консолью

**Ничего не ломается. `ConsoleApp` не меняется ни на символ, `sb.ps1` не меняется ни на символ.**

`App.main` становится диспетчером:

```java
public static void main(String[] args) throws Exception {
    if (args.length > 0 && "--serve".equals(args[0])) {
        SpringApplication.run(SecondBrainApplication.class, args);
        return;                       // единственная ветка, поднимающая Spring
    }
    if (args.length > 0 && "--import-json".equals(args[0])) { /* импорт */ return; }
    // всё остальное — сегодняшний путь, только repository() выбирает json|sqlite
}
```

Режимы после этапа 3:

```
java -jar target/second-brain.jar                 # REPL — как сейчас
java -jar target/second-brain.jar --stdin         # быстрый захват, sb.ps1 — как сейчас
java -jar target/second-brain.jar --sync          # досылка — как сейчас
java -jar target/second-brain.jar "мысль"         # one-shot — как сейчас
java -jar target/second-brain.jar --import-json   # НОВОЕ: перенос в SQLite
java -jar target/second-brain.jar --serve         # НОВОЕ: REST API на 127.0.0.1:8080
```

**Почему консоль НЕ становится HTTP-клиентом:** сегодня `sb.ps1` работает с холодной машины без
предварительных условий. Сделай его `Invoke-RestMethod` — и мысль ТЕРЯЕТСЯ, если забыл запустить
сервер. Это ровно тот инвариант, ради демонстрации которого существует весь репозиторий.

**Честная плата:** два процесса (сервер и CLI) пишут в один файл SQLite. Это поддерживается:
писатели сериализуются, `busy_timeout` заставляет второго ждать вместо падения. Единственное
требование: **держать `data/` вне OneDrive и вне сетевых дисков** — там блокировки SQLite не
работают.

Скорость: консоль грузит `spring-jdbc`/`spring-core` ради `JdbcClient` (контекст Spring не
поднимается). Ожидается +100–200 мс к сегодняшним ~0.3 с. Контекст Boot (+1.2 с) в этот путь
не пускаем принципиально.

---

## 6. Порядок работ

Каждый шаг заканчивается состоянием, где проект собирается и тесты зелёные.
**Шаг 6 — первый, где появляется что-то, что можно `curl`.**

**Шаг 0. Гигиена. Никакого нового кода.**
Ветка `stage-3`. В `.gitignore` добавить `*.db`, `*.db-wal`, `*.db-shm`, `*.sqlite` — сегодня их
там нет, а файл БД норовит лечь в корень проекта, то есть **в публичный репозиторий вместе с
личными мыслями**. Проверить `git check-ignore -v data/second-brain.db`.
Отвязать тесты от боевого `notion.properties`: путь к файлу становится настройкой
(`NotionConfig.load(Path)` уже есть и публичен), в тестовом профиле — несуществующий файл.
Сегодня `NotionConfig.load()` читает файл из текущего каталога, и первый же `@SpringBootTest`
начнёт создавать страницы в настоящем Notion при каждом `./mvnw test`.

**Шаг 1. Только сборка.** Родитель `spring-boot-starter-parent`, стартеры,
`maven-shade-plugin` → `spring-boot-maven-plugin` с явным `<mainClass>com.secondbrain.App</mainClass>`,
`<finalName>second-brain</finalName>` сохранить. **Ни строчки нового Java.** Проверить руками:
в MANIFEST есть `Start-Class`; `java -jar` открывает REPL; `.\sb.ps1 тест` захватывает мысль.

**Шаг 2. SQLite, подключённый ни к чему.** `schema.sql`, `SqliteSchema`, `SqliteFiles`,
`Timestamps`, `SqliteNoteRepository`, `NoteRepositoryContractTest` (абстрактный) + оба
наследника, `TimestampsTest`. Работающее приложение не меняется вообще.

**Шаг 3. Переключатель.** `SECOND_BRAIN_STORAGE=json|sqlite`, **по умолчанию `json`**.
Ежедневный поток байт-в-байт прежний; SQLite гоняется на выброшенной базе.

**Шаг 4. Импорт на настоящих данных.** `--import-json` + `JsonToSqliteImporterTest`.
Прогнать ритуал из раздела 4, включая проверку «Очередь пуста».

**Шаг 5. Флип.** По умолчанию `sqlite`. **Пожить на нём два дня, ничего не трогая.**
Откат — одна переменная.

**Шаг 6. ← ПЕРВЫЙ CURL.** Пакет `web/` целиком + `SecondBrainApplication` + `AppConfig` +
`--serve` + `@WebMvcTest`-тесты + один `@SpringBootTest`.

**Шаг 7. Досылка и защита от дублей.** `SyncLock` + `SqliteSyncLock` + `NotionFlushJob`
(`@Scheduled(fixedDelay)`, только в режиме `--serve`) + `SqliteSyncLockTest`.

**Шаг 8. Витрина.** README (оба языка): убрать «no runtime dependencies except Jackson» — это
перестало быть правдой; обновить бейдж числа тестов; `curl`-примеры; раздел ADR. В CI добавить
`windows-latest` в матрицу. Опционально: `spring-boot-starter-actuator` (только `health`) и
`springdoc-openapi` — Swagger UI это то, на что рекрутер реально посмотрит за 10 секунд.

> Шаги 2–5 (хранилище) и шаг 6 (веб) независимы: веб-слой работает и поверх
> `JsonNoteRepository`, потому что говорит с интерфейсом. Если мотивация просядет — сделать
> шаг 6 раньше, чтобы быстрее увидеть результат.

---

## 7. Риски и как их обходим

| Риск | Что делаем |
|---|---|
| **Дубли страниц в Notion.** С сервером `@Scheduled`-досылка, HTTP-поток и `--sync` из консоли могут взять из очереди одну заметку и создать две страницы. `synchronized` не спасёт — процессы разные. | `SyncLock`: `UPDATE sync_lock SET holder=:me, expires_at=:exp WHERE id=1 AND (holder IS NULL OR expires_at < :now)`; захватил, если затронута 1 строка. Аренда истекает, упавший процесс не блокирует навсегда. Плюс `markSynced ... WHERE notion_page_id IS NULL`. Старый 3-аргументный конструктор делегирует в новый → 5 тестов без правок. |
| **Остаточная гонка:** POST в Notion прошёл, а `markSynced` упал → страница есть, заметка в очереди, следующая досылка создаст дубль. | `markSynced` в `try/catch`, при ошибке — громкий лог и прерывание цикла. Полностью закрыть можно, читая обратно свойство `ID`, которое `HttpNotionClient` уже пишет в каждую страницу, — вне объёма этапа 3. В README как известное ограничение. |
| **Два процесса на одном файле SQLite.** | WAL + `busy_timeout=5000` с шага 2, без пула. `data/` вне OneDrive/сетевых дисков. |
| **Порядок заметок «поедет».** `Instant.toString()` даёт переменное число знаков; в `notes.json` уже есть оба варианта. | `Timestamps` с `appendInstant(9)` + тест на совпадение строкового порядка с хронологическим. |
| **`@TempDir` не удаляется на Windows** (открытый .db + `-wal`/`-shm`), а CI только на ubuntu — падать будет только локально. | Закрывать `DataSource` в `@AfterEach`; `windows-latest` в матрицу CI. |
| **Нативная библиотека sqlite-jdbc не распаковывается** (антивирус/права на %TEMP%). | Выход: `-Dorg.sqlite.tmpdir=…`. В README, раздел troubleshooting. |
| **`POST /notes` ждёт ответа Notion** — таймаут 30 с. | Осознанно оставляем: ответ честно говорит `SENT`/`QUEUED`, `CaptureService` не трогаем. Если начнёт мешать — одна константа или `@Async`. |
| **Аутентификации нет.** | `server.address=127.0.0.1`. Токен НИКОГДА не кладём в `src/main/resources` — `NotionConfig` читает свой файл сам, и токен не попадает в `Environment` Spring, значит и в `/actuator/env` утечь не может. Actuator — только `health`. |
| **Кириллица в логах Spring.** | `logging.charset.console=UTF-8`; пользовательский вывод по-прежнему через явный UTF-8 `PrintStream`, а не через логгер. В README — рабочий вызов из PowerShell 5.1 через `[System.Text.Encoding]::UTF8.GetBytes(...)`. |
| **Контрактный тест не сойдётся на двух реализациях.** Повторный `save()` с тем же id: JSON добавит вторую запись, SQLite — нет. | Эти сценарии **не** входят в общий контракт — проверяются отдельно в `SqliteNoteRepositoryTest`, расхождение задокументировано в javadoc. |

Снято проверкой, не тратить время: `org.xerial:sqlite-jdbc` **управляется BOM'ом Spring Boot** —
версию в pom писать не надо. `org.flywaydb:flyway-database-sqlite` на Maven Central
**не существует** — Flyway не берём, схему держит идемпотентный `schema.sql`.

---

## 8. Принятые решения

### 1. Версия Spring Boot — **4.1.x** ✅ решено 31.07.2026

Проверено по spring.io: **вся ветка 3.x вышла из открытой поддержки 30 июня 2026**
(последний бесплатный релиз 3.5.16 от 25 июня). В открытой поддержке только 4.0
(до 31.12.2026) и **4.1 (до 31.07.2027)**. Витринный проект не должен стоять на ветке без
патчей безопасности — версия видна в `pom.xml` любому, кто откроет репозиторий.

### 2. JPA — отдельным этапом 3.5 поверх PostgreSQL ✅ решено 31.07.2026

**Не** на SQLite: диалект SQLite у Hibernate — community-уровня, вне тестируемой матрицы,
`ddl-auto=validate` на нём ломается из-за динамической типизации. `JpaNoteRepository` поверх
Postgres в `docker-compose.yml` — это второй адаптер того же порта, проходящий **тот же
контрактный тест**; вместе с ним становятся уместны Flyway и Testcontainers. Сильнее, чем
просто «использовал JPA». Делается ПОСЛЕ того, как этап 3 устоится.

### 3. Автозапуск сервера — отложено

Решается после шага 7. Рекомендация: задание в Планировщике заданий Windows,
`javaw -jar second-brain.jar --serve`, чтобы `@Scheduled`-досылка работала без участия
человека и этапу 5 было куда стучаться.

---

## 8а. Что реально приезжает с Boot 4.1.0 (проверено зондом, не по памяти)

Пробный проект с `spring-boot-starter-parent:4.1.0` собран, зависимости разрешены,
**весь существующий код проекта и все 32 теста прогнаны под ним успешно**.

| Библиотека | Версия | Замечание |
|---|---|---|
| `org.xerial:sqlite-jdbc` | **3.53.2.0** | **управляется BOM'ом** — версию в pom писать не надо |
| Jackson | **3.1.4** (`tools.jackson.core`) | не `com.fasterxml` |
| `jackson-annotations` | 2.21 (`com.fasterxml`) | приезжает как зависимость Jackson 3 — **`@JsonIgnore` работает как раньше** |
| JUnit Jupiter | 6.0.3 | пакеты те же, **тесты правок не потребовали** |
| Spring Framework | 7.0.8 | `JdbcClient` на месте |
| Tomcat embed | 11.0.22 | |
| `jakarta.validation-api` | 3.1.1 | |
| Mockito | 5.23.0 | |

**Миграция на Jackson 3 — ровно три файла** (план ранее ошибочно утверждал, что
`HttpNotionClient` не меняется):

1. `JsonNoteRepository` — импорты `com.fasterxml.jackson.databind` → `tools.jackson.databind`;
   `new ObjectMapper()...` → `JsonMapper.builder()....build()`;
   `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` → `DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS`
   (переехал в `tools.jackson.databind.cfg`);
   **зависимость `jackson-datatype-jsr310` и `registerModule(new JavaTimeModule())` удаляются** —
   поддержка `java.time` встроена в Jackson 3.
2. `HttpNotionClient` — те же импорты + `catch (IOException)` → `catch (JacksonException)`:
   в Jackson 3 ошибки разбора стали непроверяемыми, и старый `catch` перестаёт компилироваться
   («exception is never thrown»).
3. `HttpNotionClientTest` — только импорт `ObjectMapper`.

Проверено отдельным зондом (14 проверок): `record` сериализуется и читается обратно,
`Instant` пишется как ISO-строка с наносекундами и разбирается точно, `@JsonIgnore` из
старого пакета аннотаций действует, файлы формата этапа 1 (без `notionPageId`) читаются,
неизвестные поля не роняют чтение, кириллица не портится.

**Побочный эффект, о котором стоит помнить:** непроверяемые исключения Jackson 3 означают,
что повреждённый JSON-файл теперь вылетит мимо `catch (IOException)` в `JsonNoteRepository`.
При переносе обернуть разбор явно, чтобы сообщение осталось человекочитаемым.

---

## 9. Чего мы НЕ делаем на этом этапе

- **JPA / Hibernate / Spring Data** — ни в каком виде (см. решение №2).
- **Flyway / Liquibase** — артефакта Flyway под SQLite не существует; версионные миграции
  приедут вместе с Postgres. Пока — идемпотентный `schema.sql`.
- **PostgreSQL, Docker, docker-compose.**
- **Асинхронную отправку в Notion**: ни `@Async`, ни `@TransactionalEventListener`.
- **Аутентификацию, пользователей, HTTPS.** Только `127.0.0.1`.
- **Переписывание `sb.ps1` на HTTP** и превращение консоли в REST-клиент.
- **Многомодульный Maven.** Один jar, одно имя `second-brain.jar`.
- **Ни одной правки в `model/`, `classify/`, `core/`, `cli/`** и в `HttpNotionClient` /
  `NotionClient` / `NotionConfig`.
- **Нормализацию тегов в таблицу `note_tags`.**
- **Testcontainers.** SQLite — встроенная база, контейнеризовать нечего; появится вместе с
  Postgres. Это, кстати, готовый ответ на собеседовании.
- **Полнотекстовый поиск, редактирование/удаление заметок, UI, Telegram** — этапы 4 и 5.
