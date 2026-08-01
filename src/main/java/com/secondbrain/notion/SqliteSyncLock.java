package com.secondbrain.notion;

import com.secondbrain.storage.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Замок на досылку, общий для всех процессов, работающих с одной базой.
 *
 * <p>У SQLite нет {@code SELECT ... FOR UPDATE}, поэтому замок сделан вручную —
 * строкой в таблице {@code sync_lock}. Захват выражен <b>условным UPDATE</b>:
 *
 * <pre>
 *   UPDATE sync_lock SET holder = я, expires_at = сейчас + срок
 *   WHERE id = 1 AND (holder IS NULL OR expires_at &lt; сейчас)
 * </pre>
 *
 * <p>Одна команда SQL и атомарна сама по себе, поэтому гонка невозможна: изменить
 * строку удастся ровно одному, остальные получат «0 строк изменено». Кто изменил —
 * тот и владеет.
 *
 * <p><b>Срок владения</b> обязателен. Без него процесс, аварийно завершившийся
 * с захваченным замком, заблокировал бы досылку навсегда — и заметки перестали бы
 * уходить в Notion молча. По истечении срока замок может забрать любой другой.
 * Срок выбран с большим запасом относительно времени отправки: лучше подождать
 * лишние минуты, чем получить дубликаты страниц.
 */
public class SqliteSyncLock implements SyncLock {

    /** Насколько берётся замок. Досылка редко длится больше секунд. */
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final JdbcClient jdbc;
    private final String holderId;

    public SqliteSyncLock(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
        // Кто мы: номер процесса помогает при разборе, случайная часть отличает
        // экземпляры внутри одного процесса.
        this.holderId = ProcessHandle.current().pid() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public boolean tryAcquire() {
        Instant now = Instant.now();
        int changed = jdbc.sql("""
                        UPDATE sync_lock
                           SET holder = :me, expires_at = :expires
                         WHERE id = 1
                           AND (holder IS NULL OR expires_at < :now)
                        """)
                .param("me", holderId)
                .param("expires", Timestamps.toText(now.plus(LEASE)))
                .param("now", Timestamps.toText(now))
                .update();
        return changed == 1;
    }

    @Override
    public void release() {
        // Условие по holder: не отпускаем чужой замок, если наш срок уже истёк
        // и его успел забрать другой процесс.
        jdbc.sql("UPDATE sync_lock SET holder = NULL, expires_at = '' WHERE id = 1 AND holder = :me")
                .param("me", holderId)
                .update();
    }
}
