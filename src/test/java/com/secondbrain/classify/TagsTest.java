package com.secondbrain.classify;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagsTest {

    @Test
    void extractsHashtags() {
        assertEquals(List.of("работа", "срочно"),
                Tags.extract("доделать отчёт #работа #срочно"));
    }

    @Test
    void deduplicatesAndLowercases() {
        assertEquals(List.of("идея"),
                Tags.extract("#Идея важная #идея"));
    }

    @Test
    void noTags() {
        assertTrue(Tags.extract("просто текст без тегов").isEmpty());
        assertTrue(Tags.extract("").isEmpty());
        assertTrue(Tags.extract(null).isEmpty());
    }
}
