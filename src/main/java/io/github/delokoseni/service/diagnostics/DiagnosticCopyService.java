package io.github.delokoseni.service.diagnostics;

import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.disk.DiskInventory;

import java.util.List;
import java.util.function.Consumer;

public final class DiagnosticCopyService {
    private final DiagnosticTextFormatter formatter;

    public DiagnosticCopyService() {
        this(new DiagnosticTextFormatter());
    }

    DiagnosticCopyService(DiagnosticTextFormatter formatter) {
        this.formatter = formatter;
    }

    public boolean copyOne(
            DiagnosticFinding finding,
            DiskInventory inventory,
            VolumeInspection selectedVolume,
            Consumer<String> clipboard) {
        if (finding == null) {
            return false;
        }
        clipboard.accept(formatter.format(finding, inventory, selectedVolume));
        return true;
    }

    public boolean copyAll(
            List<DiagnosticFinding> findings,
            DiskInventory inventory,
            VolumeInspection selectedVolume,
            Consumer<String> clipboard) {
        if (findings == null || findings.isEmpty()) {
            return false;
        }
        clipboard.accept(findings.stream()
                .map(finding -> formatter.format(finding, inventory, selectedVolume))
                .collect(java.util.stream.Collectors.joining("\n\n---\n\n")));
        return true;
    }
}
