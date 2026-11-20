package io.github.delokoseni.service.parser;

import java.util.List;

public record MbrParseResult(
        boolean signatureValid,
        List<PartitionRecord> primaryPartitions,
        List<PartitionRecord> logicalPartitions,
        List<String> diagnostics) {
    public MbrParseResult {
        primaryPartitions = List.copyOf(primaryPartitions);
        logicalPartitions = List.copyOf(logicalPartitions);
        diagnostics = List.copyOf(diagnostics);
    }
}
