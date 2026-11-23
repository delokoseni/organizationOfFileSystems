package io.github.delokoseni.model;

import java.util.List;
import java.util.Objects;

public record VolumeInfo(
        String volumeGuidPath,
        List<String> mountPoints,
        String fileSystem,
        String label,
        long capacityBytes,
        long availableBytes,
        long clusterSizeBytes) {

    public VolumeInfo {
        volumeGuidPath = Objects.requireNonNullElse(volumeGuidPath, "");
        mountPoints = mountPoints == null ? List.of() : List.copyOf(mountPoints);
        fileSystem = Objects.requireNonNullElse(fileSystem, "");
        label = Objects.requireNonNullElse(label, "");
        if (capacityBytes < 0 || availableBytes < 0 || clusterSizeBytes < 0) {
            throw new IllegalArgumentException("Размеры и ёмкости тома не могут быть отрицательными.");
        }
        if (availableBytes > capacityBytes) {
            throw new IllegalArgumentException("Доступная ёмкость тома не может превышать общую ёмкость.");
        }
    }
}
