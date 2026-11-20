package io.github.delokoseni.service.parser;

import java.util.List;
import java.util.Optional;

public record GptParseResult(
        boolean protectiveMbrValid,
        Optional<GptHeaderInfo> primary,
        Optional<GptHeaderInfo> backup,
        List<PartitionRecord> partitions,
        List<String> diagnostics) {
    public GptParseResult {
        primary = primary == null ? Optional.empty() : primary;
        backup = backup == null ? Optional.empty() : backup;
        partitions = List.copyOf(partitions);
        diagnostics = List.copyOf(diagnostics);
    }
}
