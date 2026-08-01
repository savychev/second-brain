package com.secondbrain.classify;

/**
 * Классификатор не смог дать ответ.
 *
 * <p>Не фатально: {@link FallbackClassifier} перехватывает это и обращается
 * к классификатору по правилам. Мысль будет захвачена в любом случае.
 */
public class ClassificationFailedException extends RuntimeException {

    public ClassificationFailedException(String message) {
        super(message);
    }

    public ClassificationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
