-- Схема хранилища Second Brain.
--
-- Файл идемпотентный (IF NOT EXISTS везде) и выполняется при каждом старте:
-- отдельный инструмент миграций для одной таблицы избыточен, а так база
-- создаётся сама при первом запуске и на пустом месте.

CREATE TABLE IF NOT EXISTS notes (
    id             TEXT PRIMARY KEY,           -- UUID, назначает приложение (Note.create)
    text           TEXT NOT NULL,              -- ровно как ввёл пользователь
    type           TEXT NOT NULL CHECK (type IN ('IDEA','TASK','LINK','NOTE')),
    -- Момент захвата: ISO-8601 UTC, ВСЕГДА с 9 знаками после точки.
    -- Instant.toString() так не умеет: он обрезает незначащие нули, и тогда
    -- "…06Z" и "…06.3Z" при строковом сравнении идут в неверном порядке
    -- (точка 0x2E меньше 'Z' 0x5A). А ORDER BY здесь строковый.
    -- Постоянную ширину обеспечивает класс Timestamps.
    created_at     TEXT NOT NULL,
    tags           TEXT NOT NULL DEFAULT '[]', -- JSON-массив, порядок как в тексте
    source         TEXT NOT NULL DEFAULT 'unknown',
    notion_page_id TEXT                        -- NULL = ещё в очереди на отправку
);

CREATE INDEX IF NOT EXISTS idx_notes_created_at      ON notes(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notes_type_created_at ON notes(type, created_at DESC);

-- Частичный индекс: в нём лежат ТОЛЬКО неотправленные заметки, то есть ровно то,
-- что запрашивает findUnsynced(). Ушла заметка в Notion — выпала из индекса,
-- поэтому индекс очереди всегда крошечный независимо от объёма архива.
CREATE INDEX IF NOT EXISTS idx_notes_pending ON notes(created_at) WHERE notion_page_id IS NULL;

-- Замок на досылку в Notion, общий для всех процессов.
--
-- Зачем: сервер досылает очередь по расписанию, а консоль может делать то же самое
-- в этот момент. Два процесса, взявшие из очереди одну заметку, создадут в Notion
-- ДВЕ страницы. Блокировка внутри программы (synchronized) тут бессильна —
-- у разных процессов разная память, общий у них только этот файл базы.
--
-- Замок берётся условным UPDATE: кто изменил строку, тот и владеет.
-- У владения есть срок (expires_at) — иначе аварийно завершившийся процесс
-- заблокировал бы досылку навсегда.
CREATE TABLE IF NOT EXISTS sync_lock (
    id         INTEGER PRIMARY KEY CHECK (id = 1),
    holder     TEXT,                       -- кто держит; NULL = свободен
    expires_at TEXT NOT NULL DEFAULT ''    -- до какого момента, ISO-8601
);
INSERT OR IGNORE INTO sync_lock (id, holder, expires_at) VALUES (1, NULL, '');
