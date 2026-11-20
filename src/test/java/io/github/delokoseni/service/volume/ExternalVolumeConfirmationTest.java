package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.VolumeFileSystemInfo;
import io.github.delokoseni.model.VolumeInfo;
import io.github.delokoseni.model.VolumeInspection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalVolumeConfirmationTest {
    @Test
    void refusesNtfsQueryWithoutExplicitConfirmationBeforeAnyNativeAccess() {
        VolumeFileSystemInfo fileSystem = new VolumeFileSystemInfo("NTFS", "", 1,
                255, 0, 4096, 2048, 1024, 1, 512);
        VolumeInfo volume = new VolumeInfo("\\\\?\\Volume{d}\\", List.of("D:\\"), "NTFS",
                "", 4096, 2048, 512);
        VolumeInspection inspection = new VolumeInspection(volume, Optional.of(fileSystem),
                List.of(new DiskExtent(8, 1024, 4096)), Optional.empty(), List.of());
        ExternalVolumeCandidate candidate = new ExternalVolumeCandidate(
                inspection, 8, "ADATA SE760", "TEST-SERIAL", 8192,
                512, OptionalInt.of(4096), List.of());

        assertThrows(java.io.IOException.class,
                () -> new WindowsVolumeService().inspectVerifiedAdataD(candidate, false));
    }
}
