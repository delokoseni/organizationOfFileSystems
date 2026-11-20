package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.DiskExtent;
import io.github.delokoseni.model.VolumeInfo;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VolumeDiskMappingService {
    public Map<String, Set<Integer>> mapVolumesToDisks(
            List<VolumeInfo> volumes,
            Map<String, List<DiskExtent>> extentsByVolumeGuid) {
        Map<String, Set<Integer>> mappings = new LinkedHashMap<>();
        for (VolumeInfo volume : volumes) {
            Set<Integer> diskNumbers = new LinkedHashSet<>();
            for (DiskExtent extent : extentsByVolumeGuid.getOrDefault(volume.volumeGuidPath(), List.of())) {
                diskNumbers.add(extent.diskNumber());
            }
            mappings.put(volume.volumeGuidPath(), Set.copyOf(diskNumbers));
        }
        return Map.copyOf(mappings);
    }
}
