package io.github.delokoseni.service.parser;

import io.github.delokoseni.model.PartitionStyle;

import java.util.List;
import java.util.Optional;

public record PartitionTableAnalysis(
        PartitionStyle style,
        MbrParseResult mbr,
        Optional<GptParseResult> gpt,
        List<PartitionRecord> partitions,
        List<String> diagnostics) {
    public PartitionTableAnalysis {
        gpt = gpt == null ? Optional.empty() : gpt;
        partitions = List.copyOf(partitions);
        diagnostics = List.copyOf(diagnostics);
    }
}
