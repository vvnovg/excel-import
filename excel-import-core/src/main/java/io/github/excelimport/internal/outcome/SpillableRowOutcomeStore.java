package io.github.excelimport.internal.outcome;

import io.github.excelimport.RowOutcome;
import io.github.excelimport.RowStatus;
import io.github.excelimport.outcome.RowOutcomeStore;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Статусы — в растущем {@code byte[]} (1 МБ на миллион строк). Сообщения об ошибках —
 * в карте до порога, дальше в временный файл последовательной записью. Чтение после
 * {@link #seal()} идёт merge-join'ом, поэтому требует возрастающих {@code rowNum}.
 */
public final class SpillableRowOutcomeStore implements RowOutcomeStore {

    private static final Logger log = LoggerFactory.getLogger(SpillableRowOutcomeStore.class);
    private static final char SEPARATOR = '\t';

    private final int maxMessagesInMemory;
    private final Path spillFile;

    private byte[] statuses = new byte[1024];
    private int maxRowNum;
    private final Map<Integer, String> messages = new HashMap<>();
    private BufferedWriter spillWriter;
    private boolean sealed;

    // состояние чтения после seal()
    private BufferedReader spillReader;
    private int lastReadRowNum;
    private int pendingRowNum = -1;
    private String pendingMessage;

    public SpillableRowOutcomeStore(Path tempDir, int maxMessagesInMemory) {
        this.maxMessagesInMemory = maxMessagesInMemory;
        try {
            Files.createDirectories(tempDir);
            this.spillFile = tempDir.resolve("excel-import-outcomes-" + System.nanoTime() + ".tsv");
        } catch (IOException e) {
            throw new IllegalStateException("не удалось подготовить каталог для временных файлов", e);
        }
    }

    @Override
    public void put(int rowNum, RowOutcome outcome) {
        if (sealed) {
            throw new IllegalStateException("хранилище закрыто для записи после seal()");
        }
        if (rowNum < 1) {
            throw new IllegalArgumentException("номер строки 1-based, получено: " + rowNum);
        }
        ensureCapacity(rowNum);
        statuses[rowNum] = outcome.status().code();
        maxRowNum = Math.max(maxRowNum, rowNum);

        if (outcome.message() == null) {
            return;
        }
        if (spillWriter != null) {
            writeSpilled(rowNum, outcome.message());
            return;
        }
        if (messages.size() < maxMessagesInMemory) {
            messages.put(rowNum, outcome.message());
            return;
        }
        spillAll();
        writeSpilled(rowNum, outcome.message());
    }

    private void ensureCapacity(int rowNum) {
        if (rowNum < statuses.length) {
            return;
        }
        int newLength = statuses.length;
        while (newLength <= rowNum) {
            newLength = newLength + (newLength >> 1) + 1;
        }
        byte[] grown = new byte[newLength];
        System.arraycopy(statuses, 0, grown, 0, statuses.length);
        statuses = grown;
    }

    /** Переносит накопленные в памяти сообщения в файл и переходит в режим выгрузки. */
    private void spillAll() {
        try {
            spillWriter = Files.newBufferedWriter(spillFile, StandardCharsets.UTF_8);
            messages.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> writeSpilled(entry.getKey(), entry.getValue()));
            messages.clear();
            log.debug("сообщения об ошибках выгружены в {}", spillFile);
        } catch (IOException e) {
            throw new IllegalStateException("не удалось создать файл выгрузки " + spillFile, e);
        }
    }

    private void writeSpilled(int rowNum, String message) {
        try {
            spillWriter.write(Integer.toString(rowNum));
            spillWriter.write(SEPARATOR);
            spillWriter.write(escape(message));
            spillWriter.newLine();
        } catch (IOException e) {
            throw new IllegalStateException("не удалось записать сообщение в файл выгрузки", e);
        }
    }

    @Override
    public void seal() {
        if (sealed) {
            return;
        }
        sealed = true;
        if (spillWriter == null) {
            return;
        }
        try {
            spillWriter.close();
            spillWriter = null;
            spillReader = Files.newBufferedReader(spillFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не удалось перейти к чтению файла выгрузки", e);
        }
    }

    @Override
    public RowOutcome get(int rowNum) {
        if (!sealed) {
            throw new IllegalStateException("перед чтением нужно вызвать seal()");
        }
        RowStatus status = rowNum < statuses.length
                ? RowStatus.fromCode(statuses[rowNum])
                : RowStatus.NOT_PROCESSED;
        if (status != RowStatus.REJECTED) {
            lastReadRowNum = Math.max(lastReadRowNum, rowNum);
            return statusOnly(status);
        }
        String message = messageFor(rowNum);
        return RowOutcome.rejected(message != null ? message : "причина не сохранена");
    }

    private static RowOutcome statusOnly(RowStatus status) {
        return switch (status) {
            case INSERTED -> RowOutcome.inserted();
            case SKIPPED -> RowOutcome.skipped();
            case NOT_PROCESSED -> RowOutcome.notProcessed();
            case REJECTED -> throw new IllegalStateException("REJECTED требует сообщения");
        };
    }

    private String messageFor(int rowNum) {
        if (spillReader == null) {
            lastReadRowNum = Math.max(lastReadRowNum, rowNum);
            return messages.get(rowNum);
        }
        if (rowNum < lastReadRowNum) {
            throw new IllegalStateException(
                    "после выгрузки на диск чтение возможно только по возрастанию rowNum; "
                            + "запрошено " + rowNum + " после " + lastReadRowNum);
        }
        lastReadRowNum = rowNum;
        while (true) {
            if (pendingRowNum == rowNum) {
                String result = pendingMessage;
                pendingRowNum = -1;
                pendingMessage = null;
                return result;
            }
            if (pendingRowNum > rowNum) {
                return null; // сообщения для этой строки в файле нет
            }
            if (!advance()) {
                return null;
            }
        }
    }

    /** Читает следующую запись файла выгрузки в pending-поля. */
    private boolean advance() {
        try {
            String line = spillReader.readLine();
            if (line == null) {
                pendingRowNum = Integer.MAX_VALUE;
                pendingMessage = null;
                return false;
            }
            int separator = line.indexOf(SEPARATOR);
            pendingRowNum = Integer.parseInt(line.substring(0, separator));
            pendingMessage = unescape(line.substring(separator + 1));
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("не удалось прочитать файл выгрузки", e);
        }
    }

    @Override
    public int maxRowNum() {
        return maxRowNum;
    }

    @Override
    public void close() {
        try {
            if (spillWriter != null) {
                spillWriter.close();
            }
            if (spillReader != null) {
                spillReader.close();
            }
        } catch (IOException e) {
            log.warn("не удалось закрыть файл выгрузки: {}", e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(spillFile);
            } catch (IOException e) {
                log.warn("не удалось удалить временный файл {}: {}", spillFile, e.getMessage());
            }
        }
    }

    private static String escape(String message) {
        return message.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescape(String encoded) {
        StringBuilder result = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c != '\\' || i + 1 >= encoded.length()) {
                result.append(c);
                continue;
            }
            char next = encoded.charAt(++i);
            switch (next) {
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case '\\' -> result.append('\\');
                default -> result.append('\\').append(next);
            }
        }
        return result.toString();
    }
}
