package io.github.delokoseni.model;

import java.util.List;
import java.util.Optional;

public record VolumeInspection(
        VolumeInfo volume,
        Optional<VolumeFileSystemInfo> fileSystem,
        List<DiskExtent> diskExtents,
        Optional<NtfsVolumeData> ntfs,
        List<String> diagnostics) {
    public VolumeInspection {
        fileSystem = fileSystem == null ? Optional.empty() : fileSystem;
        diskExtents = List.copyOf(diskExtents);
        ntfs = ntfs == null ? Optional.empty() : ntfs;
        diagnostics = List.copyOf(diagnostics);
    }
}
