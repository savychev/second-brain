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
