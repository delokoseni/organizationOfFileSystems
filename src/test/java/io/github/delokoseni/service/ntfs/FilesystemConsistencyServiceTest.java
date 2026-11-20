package io.github.delokoseni.service.ntfs;

import io.github.delokoseni.model.NtfsMetrics;
import io.github.delokoseni.model.NtfsVolumeData;
import io.github.delokoseni.model.VolumeFileSystemInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemConsistencyServiceTest {
    @Test
    void reportsCapacityDifferencesWithoutTreatingThemAsRepairConditions() {
        NtfsVolumeData ntfs = new NtfsVolumeData(1, 1000, 100, 20, 5,
                512, 4096, 1024, 1, 8192, 2, 4, 10, 20);
        NtfsMetrics metrics = new NtfsCalculationService().calculate(ntfs);
        VolumeFileSystemInfo filesystem = new VolumeFileSystemInfo("NTFS", "", 1,
                255, 0, 400_000, 10_000, 8_000, 8, 512);

        var diagnostics = new FilesystemConsistencyService()
                .validate(512_000, 500_000, filesystem, ntfs, metrics);

        assertTrue(diagnostics.stream().anyMatch(text -> text.contains("smaller than mapped extents")));
        assertTrue(diagnostics.stream().anyMatch(text -> text.contains("cluster capacity is smaller")));
    }

    @Test
    void rejectsNegativePartitionOrExtentSizes() {
        NtfsVolumeData ntfs = new NtfsVolumeData(1, 1000, 100, 20, 5,
                512, 4096, 1024, 1, 8192, 2, 4, 10, 20);
        NtfsMetrics metrics = new NtfsCalculationService().calculate(ntfs);
        VolumeFileSystemInfo filesystem = new VolumeFileSystemInfo("NTFS", "", 1,
                255, 0, 400_000, 10_000, 8_000, 8, 512);

        assertThrows(IllegalArgumentException.class, () -> new FilesystemConsistencyService()
                .validate(-1, 500_000, filesystem, ntfs, metrics));
    }
}
