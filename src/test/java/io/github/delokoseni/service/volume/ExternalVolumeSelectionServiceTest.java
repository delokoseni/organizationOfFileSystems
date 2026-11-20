package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.VolumeFileSystemInfo;
import io.github.delokoseni.model.VolumeInfo;
import io.github.delokoseni.model.VolumeInspection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalVolumeSelectionServiceTest {
    private final ExternalVolumeSelectionService service = new ExternalVolumeSelectionService();

    @Test
    void selectsDiskOnlyFromUniqueDVolumeExtentsNotEnumerationOrder() {
        VolumeInspection system = volume("C:\\", 0);
        VolumeInspection external = volume("D:\\", 9);

        ExternalVolumeSelection selection = service.selectDVolume(List.of(system, external));

        assertEquals(9, selection.physicalDiskNumber());
        assertEquals("D:\\", selection.volume().volume().mountPoints().getFirst());
    }

    @Test
    void neverFallsBackToSystemVolumeOrAcceptsAmbiguousMapping() {
        assertThrows(IllegalArgumentException.class,
                () -> service.selectDVolume(List.of(volume("C:\\", 0))));
        assertThrows(IllegalArgumentException.class,
                () -> service.selectDVolume(List.of(volume("D:\\", 3), volume("D:\\", 4))));
        assertThrows(IllegalArgumentException.class,
                () -> service.selectDVolume(List.of(new VolumeInspection(
                        volume("D:\\", 3).volume(), Optional.empty(), List.of(new DiskExtent(3, 0, 512)),
                        Optional.empty(), List.of()))));
    }

    @Test
    void rejectsDVolumeThatSpansMultipleDisks() {
        VolumeInspection base = volume("D:\\", 3);
        VolumeInspection d = new VolumeInspection(base.volume(), base.fileSystem(),
                List.of(new DiskExtent(3, 0, 512), new DiskExtent(4, 0, 512)),
                Optional.empty(), List.of());

        assertThrows(IllegalArgumentException.class, () -> service.selectDVolume(List.of(d)));
    }

    @Test
    void acceptsMultipleExtentsOnTheSamePhysicalDisk() {
        VolumeInspection base = volume("D:\\", 3);
        VolumeInspection d = new VolumeInspection(base.volume(), base.fileSystem(),
                List.of(new DiskExtent(3, 0, 512), new DiskExtent(3, 4096, 512)),
                Optional.empty(), List.of());

        assertEquals(3, service.selectDVolume(List.of(d)).physicalDiskNumber());
    }

    private static VolumeInspection volume(String mountPoint, int diskNumber) {
        VolumeInfo info = new VolumeInfo("\\\\?\\Volume{" + mountPoint.charAt(0) + "}\\",
                List.of(mountPoint), "NTFS", "", 4096, 1024, 512);
        VolumeFileSystemInfo fs = new VolumeFileSystemInfo("NTFS", "", 1,
                255, 0, 4096, 2048, 1024, 1, 512);
        return new VolumeInspection(info, Optional.of(fs),
                List.of(new DiskExtent(diskNumber, 0, 4096)), Optional.empty(), List.of());
    }
}
