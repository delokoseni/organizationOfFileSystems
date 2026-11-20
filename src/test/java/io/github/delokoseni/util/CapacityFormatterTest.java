package io.github.delokoseni.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapacityFormatterTest {
    @Test
    void formatsBytesAndBinaryUnits() {
        assertEquals("0 B", CapacityFormatter.format(0));
        assertEquals("1023 B", CapacityFormatter.format(1023));
        assertEquals("1.0 KiB", CapacityFormatter.format(1024));
        assertEquals("1.5 KiB", CapacityFormatter.format(1587));
        assertEquals("1.5 MiB", CapacityFormatter.format(1_572_864));
    }

    @Test
    void formatsLargeCapacitiesWithoutOverflow() {
        assertEquals("8.0 EiB", CapacityFormatter.format(Long.MAX_VALUE));
    }

    @Test
    void rejectsNegativeCapacity() {
        assertThrows(IllegalArgumentException.class, () -> CapacityFormatter.format(-1));
    }
}
