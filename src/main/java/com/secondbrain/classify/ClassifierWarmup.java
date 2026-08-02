package com.secondbrain.classify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Прогрев классификатора при старте сервера.
 *
 * <p>Локальная модель живёт на диске и загружается в память при первом
 * обращении. Замерено на машине владельца: запрос к выгруженной модели —
 * около 11 секунд, к загруженной — около 2,4. Лимит ожидания при захвате —
 * 8 секунд, поэтому без прогрева <b>первая мысль после простоя всегда
 * достаётся правилам</b>, а не модели.
 *
 * <p>Прогрев идёт в отдельном потоке: сервер не должен ждать загрузки модели,
 * чтобы начать принимать запросы. К тому времени, когда человек напишет
 * первую мысль, модель обычно уже в памяти.
 *
 * <p>Классификаторам по правилам и по сети прогревать нечего — для них это
 * пустая операция, и отдельной проверки провайдера здесь не нужно.
 */
public class ClassifierWarmup {

    private static final Logger log = LoggerFactory.getLogger(ClassifierWarmup.class);

    /** Ниже этого прогрев был мгновенным — значит, греть было нечего. */
    private static final long WORTH_MENTIONING_MS = 500;

    private final Classifier classifier;
    private Thread worker;

    public ClassifierWarmup(Classifier classifier) {
        this.classifier = classifier;
    }

    /** Запускает прогрев в отдельном потоке. */
    public void start() {
        worker = new Thread(this::run, "classifier-warmup");
        // Демон: незавершённый прогрев не должен мешать приложению закрыться.
        worker.setDaemon(true);
        worker.start();
    }

    /** Прерывает прогрев, если он ещё идёт. */
    public void stop() {
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void run() {
        long startedAt = System.nanoTime();
        try {
            classifier.warmUp();
        } catch (RuntimeException e) {
            // Прогрев — удобство, а не условие работы. Не удался — первая мысль
            // будет классифицирована правилами, как было до этого изменения.
            log.debug("Прогрев классификатора не удался", e);
            return;
        }
        long ms = (System.nanoTime() - startedAt) / 1_000_000;
        if (ms >= WORTH_MENTIONING_MS) {
            log.info("Модель прогрета за {} мс — первая мысль не уйдёт к правилам", ms);
        }
    }
}
