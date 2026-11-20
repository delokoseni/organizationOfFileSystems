package io.github.delokoseni.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiskInfoTest {
    @Test
    void acceptsIncompleteDeviceInformation() {
        DiskInfo disk = new DiskInfo(4, null, null, null, null,
                0, 0, 0, false, null);

        assertEquals(4, disk.diskNumber());
        assertEquals("", disk.model());
        assertEquals(PartitionStyle.UNKNOWN, disk.partitionStyle());
    }

    @Test
    void rejectsNegativeDiskNumberOrCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new DiskInfo(-1, "", "", "", "", 1, 0, 0, false, PartitionStyle.RAW));
        assertThrows(IllegalArgumentException.class,
                () -> new DiskInfo(0, "", "", "", "", -1, 0, 0, false, PartitionStyle.RAW));
    }
}
