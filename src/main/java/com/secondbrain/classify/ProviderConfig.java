package com.secondbrain.classify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;

/**
 * Настройки умной классификации: какой моделью классифицировать и по какому ключу.
 *
 * <p>Провайдер выбирается одной настройкой, как хранилище {@code json|sqlite}
 * на этапе 3. Смысл тот же: сравнивать качество, не переписывая код, и иметь
 * путь отхода, если один из них станет недоступен или платным.
 *
 * <pre>
 *   ollama    — модель на этом же компьютере: без аккаунта, ключа и лимитов
 *   gemini    — бесплатный уровень по обычному Google-аккаунту
 *   groq      — бесплатный уровень, открытые модели, строгая схема ответа
 *   anthropic — Claude, платно, лучшее качество
 *   rules     — без модели, только правила
 * </pre>
 *
 * <p>Если провайдер не задан явно, он определяется по наличию ключей: сначала
 * бесплатные (Gemini, затем Groq), и только потом платный Anthropic — тратить
 * деньги без явной просьбы нельзя. Нет ни одного ключа — работают правила,
 * и это <b>не ошибка</b>: приложение полностью работоспособно без всякой модели.
 *
 * <p>Ollama в автоопределение не входит: у неё нет ключа, по которому можно было бы
 * понять «настроена ли она», а проверять запущенность службы пришлось бы сетевым
 * запросом на каждом старте. Её выбирают явно: {@code classifier.provider=ollama}.
 *
 * <p>Источники настроек, по возрастанию приоритета: файл {@code classifier.properties}
 * в корне проекта (не коммитится), затем переменные окружения.
 */
public final class ProviderConfig {

    public static final String CONFIG_FILE = "classifier.properties";
    public static final String CONFIG_PATH_PROPERTY = "secondbrain.classifier.config";

    public static final String RULES = "rules";
    public static final String OLLAMA = "ollama";
    public static final String GEMINI = "gemini";
    public static final String GROQ = "groq";
    public static final String ANTHROPIC = "anthropic";

    /**
     * Модель Ollama по умолчанию.
     *
     * <p>4 миллиарда параметров — компромисс: русский язык знает, на процессоре
     * отвечает за секунды, занимает 2.5 ГБ. Вариант {@code -instruct}, а не
     * {@code -thinking}: для выбора одной категории из четырёх размышление
     * только замедляет.
     */
    private static final String DEFAULT_OLLAMA_MODEL = "qwen3:4b-instruct";

    /**
     * Модель Gemini по умолчанию. Flash — быстрая и входит в бесплатный уровень;
     * для выбора одной из четырёх категорий её с запасом достаточно.
     */
    private static final String DEFAULT_GEMINI_MODEL = "gemini-3.6-flash";

    /**
     * Модель Groq по умолчанию. Из открытых моделей на бесплатном уровне
     * строгий режим схемы поддерживают gpt-oss; 20b для выбора одной из четырёх
     * категорий достаточно и отвечает быстрее старшей.
     */
    private static final String DEFAULT_GROQ_MODEL = "openai/gpt-oss-20b";

    /** Модель Anthropic по умолчанию — текущая рекомендованная. */
    private static final String DEFAULT_ANTHROPIC_MODEL = "claude-opus-5";

    /**
     * Предел ожидания ответа. Выведен из цели PRD «захват за ≤10 секунд»:
     * не ответила вовремя — классифицируем правилами и не задерживаем человека.
     * Лучше быстрый и слегка неточный ответ, чем точный и поздний.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);

    /**
     * Сколько Ollama держит модель в памяти после запроса.
     *
     * <p>Её значение по умолчанию — 5 минут, и для этой системы оно губительно.
     * Замерено на этой машине: первый запрос к выгруженной модели занимает
     * ~11 секунд (загрузка с диска), каждый следующий — ~2,4. При лимите
     * ожидания в 8 секунд холодный запрос всегда проигрывает, и мысль уходит
     * к правилам. А мысли пишут по несколько штук в день, то есть почти
     * каждая попадала бы в холодную модель — этап 4 был бы куплен, но не
     * работал.
     *
     * <p>Час выбран как рабочий день с перерывами: модель занимает около 3 ГБ,
     * держать их сутками ради заметки в неделю незачем. Значение {@code -1}
     * оставит модель в памяти навсегда, {@code 0} — выгрузит сразу.
     */
    private static final String DEFAULT_KEEP_ALIVE = "1h";

    private final String provider;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final String host;
    private final String keepAlive;

    ProviderConfig(String provider, String apiKey, String model, Duration timeout) {
        this(provider, apiKey, model, timeout, null);
    }

    ProviderConfig(String provider, String apiKey, String model, Duration timeout, String host) {
        this(provider, apiKey, model, timeout, host, DEFAULT_KEEP_ALIVE);
    }

    ProviderConfig(String provider, String apiKey, String model, Duration timeout,
                   String host, String keepAlive) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = (timeout == null) ? DEFAULT_TIMEOUT : timeout;
        this.host = host;
        this.keepAlive = (keepAlive == null || keepAlive.isBlank())
                ? DEFAULT_KEEP_ALIVE : keepAlive.trim();
    }

    /** Загружает настройки из файла и переменных окружения. */
    public static ProviderConfig load() {
        String override = System.getProperty(CONFIG_PATH_PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv("SECOND_BRAIN_CLASSIFIER_CONFIG");
        }
        Path file = (override != null && !override.isBlank())
                ? Paths.get(override.trim())
                : Paths.get(CONFIG_FILE);
        return load(file);
    }

    /** @see #load() */
    public static ProviderConfig load(Path configFile) {
        Properties props = readProperties(configFile);

        String geminiKey = value("GEMINI_API_KEY", props, "gemini.api.key");
        String groqKey = value("GROQ_API_KEY", props, "groq.api.key");
        String anthropicKey = value("ANTHROPIC_API_KEY", props, "anthropic.api.key");

        String requested = value("SECOND_BRAIN_CLASSIFIER", props, "classifier.provider");
        String provider = choose(requested, geminiKey, groqKey, anthropicKey);

        Duration timeout = parseSeconds(value("SECOND_BRAIN_CLASSIFIER_TIMEOUT", props,
                "classifier.timeout.seconds"));

        return switch (provider) {
            case OLLAMA -> new ProviderConfig(OLLAMA, null,
                    orDefault(value("OLLAMA_MODEL", props, "ollama.model"), DEFAULT_OLLAMA_MODEL),
                    timeout,
                    value("OLLAMA_HOST", props, "ollama.host"),
                    value("OLLAMA_KEEP_ALIVE", props, "ollama.keep.alive"));
            case GEMINI -> new ProviderConfig(GEMINI, geminiKey,
                    orDefault(value("GEMINI_MODEL", props, "gemini.model"), DEFAULT_GEMINI_MODEL),
                    timeout);
            case GROQ -> new ProviderConfig(GROQ, groqKey,
                    orDefault(value("GROQ_MODEL", props, "groq.model"), DEFAULT_GROQ_MODEL),
                    timeout);
            case ANTHROPIC -> new ProviderConfig(ANTHROPIC, anthropicKey,
                    orDefault(value("ANTHROPIC_MODEL", props, "anthropic.model"),
                            DEFAULT_ANTHROPIC_MODEL),
                    timeout);
            default -> new ProviderConfig(RULES, null, null, timeout);
        };
    }

    /**
     * Выбирает провайдера: явно заданный, иначе первый, для которого есть ключ.
     *
     * @throws IllegalArgumentException если запрошен неизвестный провайдер или
     *                                  запрошенный не имеет ключа — молча уйти
     *                                  на другой было бы хуже: пользователь
     *                                  решил бы, что работает не то, что работает
     */
    private static String choose(String requested, String geminiKey,
                                 String groqKey, String anthropicKey) {
        if (requested == null) {
            // Порядок намеренный: сначала бесплатные, платный — только если
            // других ключей нет. Тратить деньги без явной просьбы нельзя.
            if (geminiKey != null) {
                return GEMINI;
            }
            if (groqKey != null) {
                return GROQ;
            }
            return (anthropicKey != null) ? ANTHROPIC : RULES;
        }

        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case RULES -> RULES;
            // Ollama работает без ключа — проверять нечего.
            case OLLAMA -> OLLAMA;
            case GEMINI -> requireKey(GEMINI, geminiKey, "GEMINI_API_KEY");
            case GROQ -> requireKey(GROQ, groqKey, "GROQ_API_KEY");
            case ANTHROPIC -> requireKey(ANTHROPIC, anthropicKey, "ANTHROPIC_API_KEY");
            default -> throw new IllegalArgumentException(
                    "Неизвестный классификатор: «" + requested + "». Допустимо: "
                            + RULES + ", " + OLLAMA + ", " + GEMINI + ", " + GROQ + ", "
                            + ANTHROPIC + ".");
        };
    }

    private static String requireKey(String provider, String key, String envName) {
        if (key == null) {
            throw new IllegalArgumentException(
                    "Выбран классификатор «" + provider + "», но ключ не задан ("
                            + envName + " или в " + CONFIG_FILE + ").");
        }
        return provider;
    }

    private static Properties readProperties(Path file) {
        Properties props = new Properties();
        if (file != null && Files.isReadable(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Не удалось прочитать " + file + ": " + e.getMessage());
            }
        }
        return props;
    }

    /** Переменная окружения имеет приоритет над файлом. */
    private static String value(String envName, Properties props, String propertyName) {
        String fromEnv = System.getenv(envName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromFile = props.getProperty(propertyName);
        return (fromFile != null && !fromFile.isBlank()) ? fromFile.trim() : null;
    }

    private static String orDefault(String value, String fallback) {
        return (value == null) ? fallback : value;
    }

    private static Duration parseSeconds(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Настроена ли классификация моделью. */
    public boolean isEnabled() {
        return !RULES.equals(provider);
    }

    public String provider() {
        return provider;
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    public Duration timeout() {
        return timeout;
    }

    /** Сколько Ollama держит модель в памяти после запроса. */
    public String keepAlive() {
        return keepAlive;
    }

    /** Адрес службы Ollama; {@code null} — использовать адрес по умолчанию. */
    public String host() {
        return host;
    }

    /** Как сейчас классифицируются мысли — для показа пользователю. */
    public String describe() {
        if (!isEnabled()) {
            return "правила (ключ модели не задан — см. " + CONFIG_FILE + ")";
        }
        if (OLLAMA.equals(provider)) {
            return "ollama / " + model + " — локально (при сбое — правила)";
        }
        return provider + " / " + model + " (при сбое — правила)";
    }
}
