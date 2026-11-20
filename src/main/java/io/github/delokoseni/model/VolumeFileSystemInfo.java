package io.github.delokoseni.model;

import java.util.List;
import java.util.Objects;

public record VolumeFileSystemInfo(
        String fileSystemName,
        String volumeLabel,
        long volumeSerialNumber,
        long maximumComponentNameLength,
        long capabilityFlags,
        long totalBytes,
        long freeBytes,
        long availableBytes,
        int sectorsPerCluster,
        int bytesPerSector) {

    public VolumeFileSystemInfo {
        fileSystemName = Objects.requireNonNullElse(fileSystemName, "");
        volumeLabel = Objects.requireNonNullElse(volumeLabel, "");
        if (maximumComponentNameLength < 0 || totalBytes < 0 || freeBytes < 0 || availableBytes < 0
                || sectorsPerCluster < 0 || bytesPerSector < 0) {
            throw new IllegalArgumentException("Filesystem sizes and capacities cannot be negative");
        }
        if (freeBytes > totalBytes || availableBytes > freeBytes) {
            throw new IllegalArgumentException("Filesystem free and available capacities are inconsistent");
        }
    }

    public long clusterSizeBytes() {
        return Math.multiplyExact((long) sectorsPerCluster, bytesPerSector);
    }

    public List<String> capabilityNames() {
        return FileSystemCapabilities.decode(capabilityFlags);
    }
}
