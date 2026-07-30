package io.github.excelimport.convert;

import java.util.Locale;
import java.util.Set;

/** Слова, распознаваемые как true и false. Сравнение регистронезависимое. */
public record BooleanWords(Set<String> trueWords, Set<String> falseWords) {

    private static final BooleanWords DEFAULTS = new BooleanWords(
            Set.of("да", "true", "1", "y", "yes", "истина", "+"),
            Set.of("нет", "false", "0", "n", "no", "ложь", "-"));

    public BooleanWords {
        trueWords = normalize(trueWords);
        falseWords = normalize(falseWords);
    }

    public static BooleanWords defaults() {
        return DEFAULTS;
    }

    private static Set<String> normalize(Set<String> words) {
        return words.stream()
                .map(word -> word.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Boolean parse(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if (trueWords.contains(normalized)) {
            return Boolean.TRUE;
        }
        if (falseWords.contains(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
