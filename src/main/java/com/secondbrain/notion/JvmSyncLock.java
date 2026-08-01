package com.secondbrain.notion;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Замок в пределах одного процесса.
 *
 * <p>Достаточно, когда Second Brain работает только консолью: параллельных
 * исполнителей нет, а от случайного повторного входа защищает.
 *
 * <p>Когда рядом работает сервер, нужен {@link SqliteSyncLock} — этот замок
 * о других процессах не знает.
 */
public class JvmSyncLock implements SyncLock {

    private final AtomicBoolean held = new AtomicBoolean(false);

    @Override
    public boolean tryAcquire() {
        return held.compareAndSet(false, true);
    }

    @Override
    public void release() {
        held.set(false);
    }
}
