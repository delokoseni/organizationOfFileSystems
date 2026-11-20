package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.VolumeInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VolumeDiskMappingServiceTest {
    private final VolumeDiskMappingService service = new VolumeDiskMappingService();

    @Test
    void mapsExtentsByDiskNumberWithoutAssumingEnumerationOrder() {
        VolumeInfo volume = volume("\\\\?\\Volume{a}\\");
        Map<String, List<DiskExtent>> extents = Map.of(volume.volumeGuidPath(), List.of(
                new DiskExtent(7, 0, 1024),
                new DiskExtent(3, 1024, 1024),
                new DiskExtent(7, 2048, 1024)));

        assertEquals(Map.of(volume.volumeGuidPath(), java.util.Set.of(3, 7)),
                service.mapVolumesToDisks(List.of(volume), extents));
    }

    @Test
    void preservesVolumesWithMissingExtentsAsUnmapped() {
        VolumeInfo volume = volume("\\\\?\\Volume{b}\\");

        assertEquals(Map.of(volume.volumeGuidPath(), java.util.Set.of()),
                service.mapVolumesToDisks(List.of(volume), Map.of()));
    }

    private static VolumeInfo volume(String path) {
        return new VolumeInfo(path, List.of("D:\\"), "NTFS", "", 0, 0, 0);
    }
}
