package io.github.excelimport;

/** Как из имени поля получить имя колонки БД, если нет {@code @Column}. */
public enum NamingStrategy {

    /** {@code fullName} → {@code full_name}. */
    SNAKE_CASE {
        @Override
        public String toColumnName(String fieldName) {
            StringBuilder result = new StringBuilder(fieldName.length() + 4);
            for (int i = 0; i < fieldName.length(); i++) {
                char c = fieldName.charAt(i);
                if (Character.isUpperCase(c)) {
                    if (i > 0) {
                        result.append('_');
                    }
                    result.append(Character.toLowerCase(c));
                } else {
                    result.append(c);
                }
            }
            return result.toString();
        }
    },

    /** Имя поля как есть. */
    AS_IS {
        @Override
        public String toColumnName(String fieldName) {
            return fieldName;
        }
    };

    public abstract String toColumnName(String fieldName);
}
