package io.github.delokoseni.service.ntfs;

import io.github.delokoseni.model.NtfsMetrics;
import io.github.delokoseni.model.NtfsVolumeData;
import io.github.delokoseni.model.VolumeFileSystemInfo;

import java.util.ArrayList;
import java.util.List;

public final class FilesystemConsistencyService {
    public List<String> validate(
            long partitionLengthBytes,
            long volumeExtentBytes,
            VolumeFileSystemInfo fileSystem,
            NtfsVolumeData ntfs,
            NtfsMetrics metrics) {
        if (partitionLengthBytes < 0 || volumeExtentBytes < 0) {
            throw new IllegalArgumentException("Partition and extent lengths cannot be negative");
        }
        if (ntfs.bytesPerSector() <= 0) {
            throw new IllegalArgumentException("NTFS bytes per sector must be positive");
        }
        List<String> diagnostics = new ArrayList<>();
        if (volumeExtentBytes > partitionLengthBytes) {
            diagnostics.add("Volume extents exceed the mapped partition length.");
        }
        if (fileSystem.totalBytes() > volumeExtentBytes) {
            diagnostics.add("Filesystem capacity exceeds the sum of mapped volume extents.");
        } else if (fileSystem.totalBytes() < volumeExtentBytes) {
            diagnostics.add("Filesystem capacity is smaller than mapped extents; metadata, reserved space, "
                    + "or filesystem policy may account for the difference.");
        }
        if (ntfs.bytesPerCluster() != fileSystem.clusterSizeBytes()) {
            diagnostics.add("NTFS cluster size differs from the Windows-reported filesystem cluster size.");
        }
        if (ntfs.bytesPerSector() != fileSystem.bytesPerSector()) {
            diagnostics.add("NTFS filesystem sector size differs from GetDiskFreeSpaceW.");
        }
        if (metrics.totalClusterCapacityBytes() > volumeExtentBytes) {
            diagnostics.add("NTFS total cluster capacity exceeds the mapped volume extents.");
        } else if (metrics.totalClusterCapacityBytes() < volumeExtentBytes) {
            diagnostics.add("NTFS cluster capacity is smaller than mapped extents; filesystem overhead "
                    + "or reserved regions may account for the difference.");
        }
        if (ntfs.numberOfSectors() >= 0
                && ntfs.numberOfSectors() != volumeExtentBytes / ntfs.bytesPerSector()) {
            diagnostics.add("NTFS sector count differs from the mapped extent sector count.");
        }
        if (volumeExtentBytes % ntfs.bytesPerSector() != 0) {
            diagnostics.add("Mapped extent length is not an exact multiple of the NTFS sector size.");
        }
        return List.copyOf(diagnostics);
    }
}
