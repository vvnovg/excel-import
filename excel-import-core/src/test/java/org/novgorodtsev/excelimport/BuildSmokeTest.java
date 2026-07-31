package org.novgorodtsev.excelimport;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.Test;

class BuildSmokeTest {

    @Test
    void poiIsOnTheApiClasspath() {
        assertThat(CellType.STRING.name()).isEqualTo("STRING");
    }

    @Test
    void runsOnJava17OrLater() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(17);
    }
}
