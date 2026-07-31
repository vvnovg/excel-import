package org.novgorodtsev.excelimport.internal.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.novgorodtsev.excelimport.RowOutcome;
import org.novgorodtsev.excelimport.RowStatus;
import org.novgorodtsev.excelimport.outcome.RowOutcomeStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpillableRowOutcomeStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void storesAndReadsStatusesWithoutSpilling() throws Exception {
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 1000)) {
            store.put(1, RowOutcome.inserted());
            store.put(2, RowOutcome.rejected("плохая строка"));
            store.put(3, RowOutcome.skipped());
            store.seal();

            assertThat(store.get(1).status()).isEqualTo(RowStatus.INSERTED);
            assertThat(store.get(2).message()).isEqualTo("плохая строка");
            assertThat(store.get(3).status()).isEqualTo(RowStatus.SKIPPED);
        }
    }

    @Test
    void unknownRowIsNotProcessed() throws Exception {
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 1000)) {
            store.put(1, RowOutcome.inserted());
            store.seal();

            assertThat(store.get(99).status()).isEqualTo(RowStatus.NOT_PROCESSED);
            assertThat(store.get(99).message()).isNull();
        }
    }

    @Test
    void maxRowNumTracksHighestSeenRow() throws Exception {
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 1000)) {
            store.put(5, RowOutcome.inserted());
            store.put(200_000, RowOutcome.inserted());
            store.seal();

            assertThat(store.maxRowNum()).isEqualTo(200_000);
        }
    }

    @Test
    void statusArrayGrowsWithoutLosingEarlierValues() throws Exception {
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 1000)) {
            for (int row = 1; row <= 100_000; row++) {
                store.put(row, row % 2 == 0 ? RowOutcome.inserted() : RowOutcome.skipped());
            }
            store.seal();

            assertThat(store.get(1).status()).isEqualTo(RowStatus.SKIPPED);
            assertThat(store.get(100_000).status()).isEqualTo(RowStatus.INSERTED);
        }
    }

    @Test
    void messagesSpillToDiskAfterThresholdAndAreStillReadable() throws Exception {
        Path spillDir = Files.createDirectory(tempDir.resolve("spill"));
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(spillDir, 10)) {
            for (int row = 1; row <= 50; row++) {
                store.put(row, RowOutcome.rejected("ошибка " + row));
            }
            store.seal();

            assertThat(store.get(1).message()).isEqualTo("ошибка 1");
            assertThat(store.get(25).message()).isEqualTo("ошибка 25");
            assertThat(store.get(50).message()).isEqualTo("ошибка 50");
        }
    }

    @Test
    void spillFileIsCreatedWhenThresholdExceeded() throws Exception {
        Path spillDir = Files.createDirectory(tempDir.resolve("spill2"));
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(spillDir, 5)) {
            for (int row = 1; row <= 20; row++) {
                store.put(row, RowOutcome.rejected("msg" + row));
            }
            store.seal();

            try (Stream<Path> files = Files.list(spillDir)) {
                assertThat(files).isNotEmpty();
            }
        }
    }

    @Test
    void spillFileIsDeletedOnClose() throws Exception {
        Path spillDir = Files.createDirectory(tempDir.resolve("spill3"));
        RowOutcomeStore store = new SpillableRowOutcomeStore(spillDir, 2);
        for (int row = 1; row <= 10; row++) {
            store.put(row, RowOutcome.rejected("msg" + row));
        }
        store.seal();
        store.close();

        try (Stream<Path> files = Files.list(spillDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void messagesWithNewlinesSurviveSpilling() throws Exception {
        Path spillDir = Files.createDirectory(tempDir.resolve("spill4"));
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(spillDir, 1)) {
            store.put(1, RowOutcome.rejected("первая строка\nвторая\tс табом"));
            store.put(2, RowOutcome.rejected("обычная"));
            store.seal();

            assertThat(store.get(1).message()).isEqualTo("первая строка\nвторая\tс табом");
            assertThat(store.get(2).message()).isEqualTo("обычная");
        }
    }

    @Test
    void readingBeforeSealIsRejected() throws Exception {
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 10)) {
            store.put(1, RowOutcome.inserted());

            assertThatThrownBy(() -> store.get(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("seal");
        }
    }

    @Test
    void writingAfterSealIsRejected() throws Exception {
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 10)) {
            store.seal();

            assertThatThrownBy(() -> store.put(1, RowOutcome.inserted()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void readsMustBeMonotonicWhenSpilled() throws Exception {
        Path spillDir = Files.createDirectory(tempDir.resolve("spill5"));
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(spillDir, 1)) {
            store.put(1, RowOutcome.rejected("a"));
            store.put(2, RowOutcome.rejected("b"));
            store.seal();

            assertThat(store.get(2).message()).isEqualTo("b");
            assertThatThrownBy(() -> store.get(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("по возрастанию");
        }
    }

    @Test
    void ioFailureIsWrappedInUncheckedException() throws IOException {
        Path notADirectory = Files.createFile(tempDir.resolve("file.txt"));

        assertThatThrownBy(() -> new SpillableRowOutcomeStore(notADirectory, 0))
                .isInstanceOf(IllegalStateException.class);
    }
}
