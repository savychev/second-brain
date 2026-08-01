package com.secondbrain.classify;

/**
 * Выбор классификатора.
 *
 * <p>Есть ключ Anthropic — работает умная классификация с подстраховкой правилами.
 * Ключа нет — только правила, и приложение ведёт себя ровно как до этапа 4.
 * Отсутствие ключа не ошибка: система остаётся полностью рабочей.
 */
public final class Classifiers {

    private Classifiers() {
    }

    /** Создаёт классификатор по настройкам окружения. */
    public static Classifier fromEnvironment() {
        return create(AnthropicConfig.load());
    }

    /** @see #fromEnvironment() */
    public static Classifier create(AnthropicConfig config) {
        Classifier rules = new RuleBasedClassifier();
        if (!config.isEnabled()) {
            return rules;
        }
        return new FallbackClassifier(new AnthropicClassifier(config), rules);
    }

    /** Человекочитаемое описание того, как сейчас классифицируются мысли. */
    public static String describe(AnthropicConfig config) {
        return config.isEnabled()
                ? "модель " + config.model() + " (при сбое — правила)"
                : "правила (" + config.disabledReason() + ")";
    }
}
