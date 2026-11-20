package io.github.delokoseni.service.disk;

import io.github.delokoseni.model.DiskInfo;
import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.diagnostics.DiagnosticFinding;
import io.github.delokoseni.service.parser.PartitionTableAnalysis;

import java.util.List;
import java.util.Optional;

public record DiskAnalysis(
        DiskInfo disk,
        Optional<PartitionTableAnalysis> partitionTable,
        Optional<WindowsPartitionLayout> windowsLayout,
        List<VolumeInspection> volumes,
        List<DiagnosticFinding> diagnostics,
        RawDiskAccessStatus accessStatus,
        String accessMessage) {
    public DiskAnalysis {
        partitionTable = partitionTable == null ? Optional.empty() : partitionTable;
        windowsLayout = windowsLayout == null ? Optional.empty() : windowsLayout;
        volumes = List.copyOf(volumes);
        diagnostics = List.copyOf(diagnostics);
        accessMessage = accessMessage == null ? "" : accessMessage;
    }
}
