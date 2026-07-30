package io.github.excelimport;

/**
 * Правила сопоставления текста в файле с {@code @ExcelColumn(header = ...)}.
 *
 * @param trim                      обрезать пробелы по краям
 * @param collapseWhitespace        схлопывать подряд идущие пробелы в один
 * @param ignoreCase                сравнивать без учёта регистра
 * @param normalizeNonBreakingSpace приводить неразрывные пробелы к обычным
 */
public record HeaderMatchingPolicy(
        boolean trim, boolean collapseWhitespace, boolean ignoreCase, boolean normalizeNonBreakingSpace) {

    private static final HeaderMatchingPolicy DEFAULTS =
            new HeaderMatchingPolicy(true, true, true, true);

    public static HeaderMatchingPolicy defaults() {
        return DEFAULTS;
    }

    /** Приводит заголовок к канонической форме для сравнения. */
    public String normalize(String header) {
        if (header == null) {
            return null;
        }
        String result = header;
        if (normalizeNonBreakingSpace) {
            // \u00A0 (NBSP), \u202F (узкий NBSP), \uFEFF (BOM / ZWNBSP) -> обычный пробел
            result = result.replace('\u00A0', ' ').replace('\u202F', ' ').replace('\uFEFF', ' ');
        }
        if (collapseWhitespace) {
            result = result.replaceAll("\\s+", " ");
        }
        if (trim) {
            result = result.trim();
        }
        if (ignoreCase) {
            result = result.toLowerCase(java.util.Locale.ROOT);
        }
        return result;
    }
}
