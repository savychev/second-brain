package com.secondbrain.classify;

/**
 * Выбор классификатора.
 *
 * <p>Здесь окупается решение, принятое на этапе 1: за интерфейсом
 * {@link Classifier} реализация меняется, не задевая остального кода.
 * Правила, Groq и Claude подставляются одной настройкой — как хранилище
 * {@code json|sqlite} на этапе 3.
 *
 * <p>Любая модель <b>всегда</b> оборачивается в {@link FallbackClassifier}.
 * Это не перестраховка, а требование «ноль потерь»: захват мысли не должен
 * зависеть от доступности внешнего сервиса.
 */
public final class Classifiers {

    private Classifiers() {
    }

    /** Создаёт классификатор по настройкам окружения. */
    public static Classifier fromEnvironment() {
        return create(ProviderConfig.load());
    }

    /** @see #fromEnvironment() */
    public static Classifier create(ProviderConfig config) {
        Classifier rules = new RuleBasedClassifier();
        if (!config.isEnabled()) {
            return rules;
        }
        return new FallbackClassifier(model(config), rules);
    }

    private static Classifier model(ProviderConfig config) {
        return switch (config.provider()) {
            case ProviderConfig.GROQ -> new GroqClassifier(config);
            case ProviderConfig.ANTHROPIC -> new AnthropicClassifier(config);
            // Сюда попасть нельзя: ProviderConfig отвергает неизвестные значения
            // при загрузке. Но молча вернуть правила было бы хуже, чем упасть.
            default -> throw new IllegalStateException(
                    "Неизвестный провайдер: " + config.provider());
        };
    }
}
