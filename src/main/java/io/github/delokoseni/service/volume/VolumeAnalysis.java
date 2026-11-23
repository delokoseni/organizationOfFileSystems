package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.NtfsMetrics;
import io.github.delokoseni.model.NtfsVolumeData;
import io.github.delokoseni.service.ntfs.NtfsBootSectorInfo;

import java.util.List;
import java.util.Optional;

public record VolumeAnalysis(
        VolumeAnalysisCandidate candidate,
        Optional<NtfsVolumeData> ntfsData,
        Optional<NtfsMetrics> ntfsMetrics,
        Optional<NtfsBootSectorInfo> ntfsBootSector,
        List<String> diagnostics) {
    public VolumeAnalysis {
        ntfsData = ntfsData == null ? Optional.empty() : ntfsData;
        ntfsMetrics = ntfsMetrics == null ? Optional.empty() : ntfsMetrics;
        ntfsBootSector = ntfsBootSector == null ? Optional.empty() : ntfsBootSector;
        diagnostics = List.copyOf(diagnostics);
    }

    public VolumeAnalysis(
            VolumeAnalysisCandidate candidate,
            Optional<NtfsVolumeData> ntfsData,
            Optional<NtfsMetrics> ntfsMetrics,
            List<String> diagnostics) {
        this(candidate, ntfsData, ntfsMetrics, Optional.empty(), diagnostics);
    }
}
