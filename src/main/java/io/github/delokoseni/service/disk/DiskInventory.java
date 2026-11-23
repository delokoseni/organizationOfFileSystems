package io.github.delokoseni.service.disk;

import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.diagnostics.DiagnosticFinding;

import java.util.List;

public record DiskInventory(
        List<DiskAnalysis> disks,
        List<VolumeInspection> volumes,
        List<DiagnosticFinding> diagnostics) {
    public DiskInventory {
        disks = List.copyOf(disks);
        volumes = List.copyOf(volumes);
        diagnostics = List.copyOf(diagnostics);
    }
}
