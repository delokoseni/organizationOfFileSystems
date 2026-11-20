package io.github.delokoseni.service.ntfs;

import io.github.delokoseni.model.NtfsMetrics;
import io.github.delokoseni.model.NtfsVolumeData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NtfsCalculationServiceTest {
    private final NtfsCalculationService service = new NtfsCalculationService();

    @Test
    void calculatesClusterCapacitiesAndVolumeRelativeMftOffsets() {
        NtfsMetrics metrics = service.calculate(valid());

        assertEquals(8, metrics.sectorsPerCluster());
        assertEquals(409_600, metrics.totalClusterCapacityBytes());
        assertEquals(80, metrics.usedClusters());
        assertEquals(81_920, metrics.freeClusterCapacityBytes());
        assertEquals(8_192, metrics.mftStartOffsetWithinVolume());
        assertEquals(16_384, metrics.mftMirrorStartOffsetWithinVolume());
        assertEquals(1024, metrics.mftRecordSizeBytes());
        assertEquals(40_960, metrics.mftZoneSizeBytes());
    }

    @Test
    void rejectsInvalidSizesCountsAndLcnRanges() {
        assertThrows(IllegalArgumentException.class, () -> service.calculate(with(valid(), 0, 4096,
                100, 20, 5, 2, 4, 10, 20)));
        assertThrows(IllegalArgumentException.class, () -> service.calculate(with(valid(), 512, 0,
                100, 20, 5, 2, 4, 10, 20)));
        assertThrows(IllegalArgumentException.class, () -> service.calculate(with(valid(), 512, 4096,
                100, 90, 15, 2, 4, 10, 20)));
        assertThrows(IllegalArgumentException.class, () -> service.calculate(with(valid(), 512, 4096,
                100, 20, 5, 100, 4, 10, 20)));
        assertThrows(IllegalArgumentException.class, () -> service.calculate(with(valid(), 512, 4096,
                100, 20, 5, 2, 4, 21, 20)));
    }

    @Test
    void rejectsIntegerOverflowAndSectorClusterInconsistency() {
        NtfsVolumeData overflow = new NtfsVolumeData(1, Long.MAX_VALUE, Long.MAX_VALUE,
                0, 0, 512, 4096, 1024, 0, 0, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> service.calculate(overflow));

        assertThrows(IllegalArgumentException.class, () -> service.calculate(
                with(valid(), 1000, 4096, 100, 20, 5, 2, 4, 10, 20)));
    }

    @Test
    void rejectsNegativeNativeCountsAtModelBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new NtfsVolumeData(
                1, 1000, 100, -1, 0, 512, 4096, 1024, 1, 0, 1, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new NtfsVolumeData(
                1, 1000, 100, 10, 0, 512, 4096, 1024, 1, -1, 1, 1, 0, 0));
    }

    private static NtfsVolumeData valid() {
        return new NtfsVolumeData(1, 1000, 100, 20, 5,
                512, 4096, 1024, 1, 8192, 2, 4, 10, 20);
    }

    private static NtfsVolumeData with(
            NtfsVolumeData base, int bytesPerSector, int bytesPerCluster,
            long total, long free, long reserved,
            long mft, long mirror, long zoneStart, long zoneEnd) {
        return new NtfsVolumeData(base.volumeSerialNumber(), base.numberOfSectors(), total, free, reserved,
                bytesPerSector, bytesPerCluster, base.bytesPerFileRecordSegment(),
                base.clustersPerFileRecordSegment(), base.mftValidDataLength(),
                mft, mirror, zoneStart, zoneEnd);
    }
}
