package io.github.delokoseni.service.disk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiskReadRangeTest {
    @Test
    void validatesRequestedRangeWithinCapacity() {
        assertEquals(1024, DiskReadRange.validate(512, 512, 1024));
    }

    @Test
    void rejectsNegativeOutOfRangeAndOverflowingRanges() {
        assertThrows(IllegalArgumentException.class, () -> DiskReadRange.validate(-1, 1, 1024));
        assertThrows(IllegalArgumentException.class, () -> DiskReadRange.validate(0, 0, 1024));
        assertThrows(IllegalArgumentException.class, () -> DiskReadRange.validate(0, -1, 1024));
        assertThrows(IllegalArgumentException.class, () -> DiskReadRange.validate(1023, 2, 1024));
        assertThrows(IllegalArgumentException.class,
                () -> DiskReadRange.validate(Long.MAX_VALUE, 1, Long.MAX_VALUE));
    }
}
