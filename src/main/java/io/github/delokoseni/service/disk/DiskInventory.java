package io.github.delokoseni.service.disk;

import io.github.delokoseni.model.DeviceIdentification;
import io.github.delokoseni.model.VolumeInspection;
import io.github.delokoseni.service.diagnostics.DiagnosticFinding;
import io.github.delokoseni.service.volume.ExternalVolumeCandidate;

import java.util.List;
import java.util.Optional;

public record DiskInventory(
        List<DiskAnalysis> disks,
        List<VolumeInspection> volumes,
        List<DiagnosticFinding> diagnostics,
        Optional<ExternalVolumeCandidate> adataCandidate,
        String adataEligibilityMessage,
        DeviceIdentification adataIdentification) {
    public DiskInventory {
        disks = List.copyOf(disks);
        volumes = List.copyOf(volumes);
        diagnostics = List.copyOf(diagnostics);
        adataCandidate = adataCandidate == null ? Optional.empty() : adataCandidate;
        adataEligibilityMessage = adataEligibilityMessage == null ? "" : adataEligibilityMessage;
        if (adataIdentification == null) {
            adataIdentification = new DeviceIdentification(
                    DeviceIdentification.Status.TARGET_VOLUME_NOT_FOUND,
                    Optional.empty(), "D: has not been identified.");
        }
    }
}
