package io.github.delokoseni.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumeFileSystemInfoTest {
    @Test
    void calculatesFilesystemClusterSizeAndDecodesCapabilities() {
        VolumeFileSystemInfo info = new VolumeFileSystemInfo("NTFS", "Data", 12,
                255, FileSystemCapabilities.CASE_PRESERVED_NAMES
                | FileSystemCapabilities.NAMED_STREAMS, 10_000, 2_000, 1_000, 8, 512);

        assertEquals(4096, info.clusterSizeBytes());
        assertTrue(info.capabilityNames().contains("Case-preserved names"));
        assertTrue(info.capabilityNames().contains("Named streams"));
    }

    @Test
    void rejectsNegativeFilesystemCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new VolumeFileSystemInfo(
                "NTFS", "", 0, 0, 0, -1, 0, 0, 0, 0));
    }

    @Test
    void multipliesLargestSectorGeometryExactly() {
        VolumeFileSystemInfo info = new VolumeFileSystemInfo("NTFS", "", 0, 0, 0,
                1, 1, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals((long) Integer.MAX_VALUE * Integer.MAX_VALUE, info.clusterSizeBytes());
    }
}
