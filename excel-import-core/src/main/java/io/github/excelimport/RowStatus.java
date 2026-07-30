package io.github.excelimport;

/** Исход обработки строки файла. Код используется для компактного хранения в byte[]. */
public enum RowStatus {

    NOT_PROCESSED((byte) 0),
    INSERTED((byte) 1),
    REJECTED((byte) 2),
    SKIPPED((byte) 3);

    private final byte code;

    RowStatus(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static RowStatus fromCode(byte code) {
        for (RowStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("неизвестный код статуса: " + code);
    }
}
