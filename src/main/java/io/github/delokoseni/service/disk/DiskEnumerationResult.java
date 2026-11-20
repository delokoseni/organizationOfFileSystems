package io.github.delokoseni.service.disk;

import java.util.List;

public record DiskEnumerationResult(
        List<DiscoveredPhysicalDisk> disks,
        List<String> diagnostics) {
    public DiskEnumerationResult {
        disks = List.copyOf(disks);
        diagnostics = List.copyOf(diagnostics);
    }
}
