package io.github.excelimport;

import java.util.Objects;

/**
 * Исход строки вместе с причиной. {@code message} непуст только для {@link RowStatus#REJECTED}.
 */
public record RowOutcome(RowStatus status, String message) {

    private static final RowOutcome INSERTED = new RowOutcome(RowStatus.INSERTED, null);
    private static final RowOutcome SKIPPED = new RowOutcome(RowStatus.SKIPPED, null);
    private static final RowOutcome NOT_PROCESSED = new RowOutcome(RowStatus.NOT_PROCESSED, null);

    public RowOutcome {
        Objects.requireNonNull(status, "status");
        if (status == RowStatus.REJECTED && (message == null || message.isBlank())) {
            throw new IllegalArgumentException("для REJECTED требуется непустое сообщение");
        }
    }

    public static RowOutcome inserted() {
        return INSERTED;
    }

    public static RowOutcome rejected(String message) {
        return new RowOutcome(RowStatus.REJECTED, message);
    }

    public static RowOutcome skipped() {
        return SKIPPED;
    }

    public static RowOutcome notProcessed() {
        return NOT_PROCESSED;
    }
}
