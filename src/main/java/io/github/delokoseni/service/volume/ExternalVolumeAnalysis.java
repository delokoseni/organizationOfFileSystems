package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.NtfsMetrics;
import io.github.delokoseni.model.NtfsVolumeData;

import java.util.List;
import java.util.Optional;

public record ExternalVolumeAnalysis(
        ExternalVolumeCandidate candidate,
        Optional<NtfsVolumeData> ntfsData,
        Optional<NtfsMetrics> ntfsMetrics,
        List<String> diagnostics) {
    public ExternalVolumeAnalysis {
        ntfsData = ntfsData == null ? Optional.empty() : ntfsData;
        ntfsMetrics = ntfsMetrics == null ? Optional.empty() : ntfsMetrics;
        diagnostics = List.copyOf(diagnostics);
    }
}
