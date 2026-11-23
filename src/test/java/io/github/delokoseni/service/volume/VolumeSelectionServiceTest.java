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

class VolumeSelectionServiceTest {
    private final VolumeSelectionService service = new VolumeSelectionService();

    @Test
    void resolvesSelectedGuidWithoutRequiringDriveLetterOrEnumerationOrder() {
        VolumeInspection first = volume("\\\\?\\Volume{one}\\", List.of(), 0);
        VolumeInspection selected = volume("\\\\?\\Volume{two}\\", List.of("\\mount\\data\\"), 7);

        assertEquals(selected, service.resolveCurrentSelection(
                List.of(first, selected), selected.volume().volumeGuidPath()));
    }

    @Test
    void doesNotSelectAnotherVolumeWhenRequestedVolumeDisappears() {
        VolumeInspection available = volume("\\\\?\\Volume{available}\\", List.of(), 2);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.resolveCurrentSelection(
                        List.of(available), "\\\\?\\Volume{disconnected}\\"));

        assertEquals("Выбранный том больше не обнаружен; другой том не выбран.", error.getMessage());
    }

    @Test
    void reportsMultiDiskVolumeAsUnsupportedInsteadOfChoosingOneDisk() {
        VolumeInspection base = volume("\\\\?\\Volume{spanned}\\", List.of(), 3);
        VolumeInspection spanned = new VolumeInspection(base.volume(), base.fileSystem(),
                List.of(new DiskExtent(3, 0, 512), new DiskExtent(9, 0, 512)),
                Optional.empty(), List.of());

        var error = assertThrows(IllegalArgumentException.class,
                () -> service.resolveCurrentSelection(List.of(spanned), spanned.volume().volumeGuidPath()));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("несколько физических дисков"));
    }

    @Test
    void retainsSelectedVolumeWhenFilesystemMetadataIsUnavailable() {
        VolumeInspection base = volume("\\\\?\\Volume{unknown}\\", List.of(), 1);
        VolumeInspection unknown = new VolumeInspection(base.volume(), Optional.empty(),
                base.diskExtents(), Optional.empty(), List.of());

        assertEquals(unknown, service.resolveCurrentSelection(
                List.of(unknown), unknown.volume().volumeGuidPath()));
    }

    private static VolumeInspection volume(String guid, List<String> mounts, int diskNumber) {
        VolumeInfo info = new VolumeInfo(guid, mounts, "NTFS", "", 8192, 4096, 512);
        VolumeFileSystemInfo fs = new VolumeFileSystemInfo("NTFS", "", 1,
                255, 0, 8192, 4096, 4096, 1, 512);
        return new VolumeInspection(info, Optional.of(fs),
                List.of(new DiskExtent(diskNumber, 0, 8192)), Optional.empty(), List.of());
    }
}
