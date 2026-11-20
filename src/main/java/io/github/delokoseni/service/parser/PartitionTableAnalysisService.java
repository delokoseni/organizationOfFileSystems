package io.github.delokoseni.service.parser;

import io.github.delokoseni.model.PartitionStyle;
import io.github.delokoseni.service.disk.DiskReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PartitionTableAnalysisService {
    private static final byte[] GPT_SIGNATURE = "EFI PART".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private final MbrParser mbrParser = new MbrParser();
    private final GptParser gptParser = new GptParser();

    public PartitionTableAnalysis analyze(DiskReader reader) throws IOException {
        MbrParseResult mbr = mbrParser.parse(reader);
        boolean protectiveMbr = mbr.primaryPartitions().stream().anyMatch(partition -> partition.mbrType() == 0xee);
        boolean gptSignature = hasPrimaryGptSignature(reader);
        if (protectiveMbr || gptSignature) {
            GptParseResult gpt = gptParser.parse(reader);
            return new PartitionTableAnalysis(PartitionStyle.GPT, mbr, Optional.of(gpt),
                    gpt.partitions(), gpt.diagnostics());
        }
        PartitionStyle style = mbr.signatureValid() ? PartitionStyle.MBR : PartitionStyle.RAW;
        return new PartitionTableAnalysis(style, mbr, Optional.empty(),
                combine(mbr.primaryPartitions(), mbr.logicalPartitions()), mbr.diagnostics());
    }

    private static boolean hasPrimaryGptSignature(DiskReader reader) throws IOException {
        long offset = reader.getLogicalSectorSize();
        if (reader.getDiskSize() < offset + GPT_SIGNATURE.length) {
            return false;
        }
        byte[] signature = reader.read(offset, GPT_SIGNATURE.length);
        return java.util.Arrays.equals(signature, GPT_SIGNATURE);
    }

    private static List<PartitionRecord> combine(
            List<PartitionRecord> primary, List<PartitionRecord> logical) {
        List<PartitionRecord> all = new ArrayList<>(primary);
        all.addAll(logical);
        return all;
    }
}
