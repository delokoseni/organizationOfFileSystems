package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.VolumeInspection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ExternalVolumeSelectionService {
    public ExternalVolumeSelection selectDVolume(List<VolumeInspection> volumes) {
        List<VolumeInspection> targets = volumes.stream()
                .filter(inspection -> inspection.volume().mountPoints().stream().anyMatch(
                        ExternalVolumeSelectionService::isDriveD))
                .toList();
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("Drive D: was not found; no device was selected.");
        }
        if (targets.size() != 1) {
            throw new IllegalArgumentException("Drive D: maps to multiple volumes; device analysis is disabled.");
        }

        VolumeInspection target = targets.getFirst();
        if (target.fileSystem().isEmpty()) {
            throw new IllegalArgumentException("Filesystem information for D: is unavailable.");
        }
        if (target.diskExtents().isEmpty()) {
            throw new IllegalArgumentException("D: has no readable physical disk extents.");
        }
        Set<Integer> diskNumbers = new LinkedHashSet<>();
        target.diskExtents().forEach(extent -> diskNumbers.add(extent.diskNumber()));
        if (diskNumbers.size() != 1) {
            throw new IllegalArgumentException("D: spans multiple physical disks; device analysis is disabled.");
        }
        return new ExternalVolumeSelection(target, diskNumbers.iterator().next());
    }

    private static boolean isDriveD(String mountPoint) {
        return mountPoint != null && ("D:".equalsIgnoreCase(mountPoint.trim())
                || "D:\\".equalsIgnoreCase(mountPoint.trim()));
    }
}
