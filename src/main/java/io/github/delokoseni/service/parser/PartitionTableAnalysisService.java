package io.github.delokoseni.service.parser;

import io.github.delokoseni.model.PartitionStyle;
import io.github.delokoseni.service.disk.DiskReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PartitionTableAnalysisService {
    private static final byte[] GPT_SIGNATURE = "EFI PART".getBytes(StandardCharsets.US_ASCII);
    private final MbrParser mbrParser = new MbrParser();
    private final GptParser gptParser = new GptParser();

    public PartitionTableAnalysis analyze(DiskReader reader) throws IOException {
        MbrParseResult mbr = mbrParser.parse(reader);
        long diskSectors = reader.getDiskSize() / reader.getLogicalSectorSize();
        boolean gptMetadataPresent = hasGptSignature(reader, 1)
                || hasGptSignature(reader, diskSectors - 1);
        boolean protectiveMbrEntry = mbr.primaryPartitions().stream()
                .anyMatch(partition -> partition.mbrType() == 0xee);
        GptParseResult gpt = gptMetadataPresent || protectiveMbrEntry ? gptParser.parse(reader) : null;

        List<PartitionRecord> mbrPartitions = conventionalMbrPartitions(mbr);
        boolean conventionalMbr = mbr.signatureValid() && !mbrPartitions.isEmpty();
        List<String> diagnostics = new ArrayList<>();
        if (gpt == null) {
            diagnostics.addAll(mbr.diagnostics());
            return new PartitionTableAnalysis(mbr.signatureValid() ? PartitionStyle.MBR : PartitionStyle.RAW,
                    mbr, Optional.empty(), combine(mbr.primaryPartitions(), mbr.logicalPartitions()), diagnostics);
        }

        boolean primaryValid = gpt.primary().filter(header -> header.validAt(
                1, diskSectors - 1, diskSectors, reader.getLogicalSectorSize())).isPresent();
        boolean backupValid = gpt.backup().filter(header -> header.validAt(
                diskSectors - 1, 1, diskSectors, reader.getLogicalSectorSize())).isPresent();
        boolean hasValidGpt = primaryValid || backupValid;
        boolean copiesConflict = gpt.diagnostics().stream().anyMatch(message ->
                message.contains("GUID диска") || message.contains("Диапазоны доступных LBA")
                        || message.contains("Размеры записей") || message.contains("Записи разделов"));
        boolean hybridMbr = gpt.protectiveMbrValid() && hasNonProtectiveMbrEntry(reader);
        boolean mbrGptConflict = conventionalMbr && hasValidGpt
                && !gpt.partitions().isEmpty();

        if (hybridMbr) {
            diagnostics.addAll(gpt.diagnostics());
            diagnostics.add("Обнаружена гибридная разметка MBR/GPT; "
                    + "анализатор не выбирает записи разделов автоматически.");
            return new PartitionTableAnalysis(PartitionStyle.HYBRID, mbr, Optional.of(gpt), List.of(), diagnostics);
        }
        if (mbrGptConflict) {
            diagnostics.addAll(gpt.diagnostics());
            diagnostics.add("Действительные записи MBR и GPT одновременно описывают разделы без однозначного "
                    + "защитного MBR; разметка неоднозначна, записи не отображаются.");
            return new PartitionTableAnalysis(PartitionStyle.AMBIGUOUS, mbr, Optional.of(gpt), List.of(), diagnostics);
        }
        if (conventionalMbr && (!hasValidGpt || !gpt.protectiveMbrValid()
                && gpt.partitions().isEmpty())) {
            diagnostics.addAll(gptDiagnosticsForVerifiedMbr(gpt));
            diagnostics.addAll(gptArraysOverlappingMbrPartitions(
                    gpt, mbrPartitions, reader.getLogicalSectorSize()));
            diagnostics.add("Обнаружена действительная таблица MBR; имеющиеся GPT-метаданные не подтверждают "
                    + "действительную альтернативную таблицу и рассматриваются как остаточные или "
                    + "несогласованные. Отображаются только проверенные разделы MBR.");
            diagnostics.addAll(mbr.diagnostics());
            return new PartitionTableAnalysis(PartitionStyle.MBR, mbr, Optional.of(gpt), mbrPartitions, diagnostics);
        }

        if (copiesConflict) {
            diagnostics.addAll(gpt.diagnostics());
            diagnostics.add("Обнаружены противоречивые метаданные GPT; "
                    + "независимое сопоставление разделов неоднозначно, записи не отображаются.");
            return new PartitionTableAnalysis(PartitionStyle.AMBIGUOUS, mbr, Optional.of(gpt), List.of(), diagnostics);
        }

        if (hasValidGpt) {
            diagnostics.addAll(gpt.diagnostics());
            return new PartitionTableAnalysis(PartitionStyle.GPT, mbr, Optional.of(gpt),
                    gpt.partitions(), diagnostics);
        }

        if (conventionalMbr) {
            diagnostics.addAll(gptDiagnosticsForVerifiedMbr(gpt));
            diagnostics.add("Таблица GPT не прошла структурную проверку; используется действительная таблица MBR.");
            diagnostics.addAll(mbr.diagnostics());
            return new PartitionTableAnalysis(PartitionStyle.MBR, mbr, Optional.of(gpt), mbrPartitions, diagnostics);
        }

        diagnostics.addAll(gpt.diagnostics());
        diagnostics.add("Обнаружены признаки GPT, но ни одна копия не подтверждена CRC и геометрией диска; "
                + "записи разделов GPT не отображаются.");
        return new PartitionTableAnalysis(PartitionStyle.RAW, mbr, Optional.of(gpt), List.of(), diagnostics);
    }

    private static List<String> gptDiagnosticsForVerifiedMbr(GptParseResult gpt) {
        String prefix = "Остаточные или несогласованные GPT-метаданные рядом с независимо "
                + "проверенной таблицей MBR: ";
        return gpt.diagnostics().stream()
                .filter(message -> !message.startsWith("Защитная запись MBR"))
                .map(message -> prefix + message)
                .toList();
    }

    private static List<PartitionRecord> conventionalMbrPartitions(MbrParseResult mbr) {
        List<PartitionRecord> partitions = combine(mbr.primaryPartitions(), mbr.logicalPartitions());
        return partitions.stream().filter(partition -> partition.mbrType() != 0xee).toList();
    }

    private static boolean hasGptSignature(DiskReader reader, long lba) throws IOException {
        long offset;
        try {
            offset = Math.multiplyExact(lba, (long) reader.getLogicalSectorSize());
        } catch (ArithmeticException e) {
            return false;
        }

        if (lba < 0 || offset > reader.getDiskSize() - GPT_SIGNATURE.length) {
            return false;
        }
        return java.util.Arrays.equals(reader.read(offset, GPT_SIGNATURE.length), GPT_SIGNATURE);
    }

    private static boolean hasNonProtectiveMbrEntry(DiskReader reader) throws IOException {
        byte[] sector = reader.read(0, reader.getLogicalSectorSize());
        for (int i = 0; i < 4; i++) {
            int type = sector[446 + i * 16 + 4] & 0xff;
            if (type != 0 && type != 0xee) {
                return true;
            }
        }
        return false;
    }

    private static List<String> gptArraysOverlappingMbrPartitions(
            GptParseResult gpt, List<PartitionRecord> mbrPartitions, int sectorSize) {
        List<String> diagnostics = new ArrayList<>();
        List<GptHeaderInfo> headers = new ArrayList<>();
        gpt.primary().ifPresent(headers::add);
        gpt.backup().ifPresent(headers::add);
        for (GptHeaderInfo header : headers) {
            if (header.arrayCrcValid()) {
                continue;
            }
            try {
                long arrayBytes = Math.multiplyExact(header.entryCount(), header.entrySize());
                long arraySectors = Math.addExact(arrayBytes, sectorSize - 1L) / sectorSize;
                long arrayEnd = Math.addExact(header.entriesLba(), arraySectors);
                for (PartitionRecord partition : mbrPartitions) {
                    if (header.entriesLba() <= partition.lastLbaInclusive()
                            && arrayEnd > partition.firstLba()) {
                        diagnostics.add("Недействительный массив записей GPT на LBA "
                                + header.entriesLba() + " (" + (header.lba() == 1
                                ? "основная копия" : "резервная копия")
                                + ") перекрывает действительный MBR-раздел " + partition.source()
                                + "; массив рассматривается как остаточные или перезаписанные метаданные.");
                    }
                }
            } catch (ArithmeticException ignored) {
                diagnostics.add("Границы недействительного массива GPT невозможно безопасно сопоставить "
                        + "с MBR-разделами из-за переполнения.");
            }
        }
        return List.copyOf(diagnostics);
    }

    private static List<PartitionRecord> combine(
            List<PartitionRecord> primary, List<PartitionRecord> logical) {
        List<PartitionRecord> all = new ArrayList<>(primary);
        all.addAll(logical);
        return List.copyOf(all);
    }
}
