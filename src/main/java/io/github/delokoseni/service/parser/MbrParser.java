package io.github.delokoseni.service.parser;

import io.github.delokoseni.service.disk.DiskReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MbrParser {
    private static final int MBR_MIN_SIZE = 512;
    private static final int MAX_LOGICAL_SECTOR_SIZE = 1024 * 1024;
    private static final int PARTITION_TABLE_OFFSET = 446;
    private static final int PARTITION_ENTRY_SIZE = 16;
    private static final int PARTITION_COUNT = 4;
    private static final int EBR_CHAIN_LIMIT = 4096;

    public MbrParseResult parse(DiskReader reader) throws IOException {
        int sectorSize = reader.getLogicalSectorSize();
        if (sectorSize < MBR_MIN_SIZE || sectorSize > MAX_LOGICAL_SECTOR_SIZE
                || reader.getDiskSize() < sectorSize || reader.getDiskSize() % sectorSize != 0) {
            throw new IOException("Недопустимая геометрия диска или размер логического сектора для разбора MBR.");
        }
        byte[] sector = reader.read(0, sectorSize);
        List<String> diagnostics = new ArrayList<>();
        List<PartitionRecord> primary = new ArrayList<>();
        List<PartitionRecord> logical = new ArrayList<>();
        boolean validSignature = u8(sector, 510) == 0x55 && u8(sector, 511) == 0xaa;
        if (!validSignature) {
            diagnostics.add("Недопустимая сигнатура MBR (ожидалось 0x55AA).");
        }

        long diskSectors = reader.getDiskSize() / sectorSize;
        List<RawEntry> extended = new ArrayList<>();
        for (int i = 0; i < PARTITION_COUNT; i++) {
            int offset = PARTITION_TABLE_OFFSET + i * PARTITION_ENTRY_SIZE;
            RawEntry entry = readEntry(sector, offset);
            if (entry.isEmpty()) {
                continue;
            }
            if (!validBootFlag(entry.bootFlag)) {
                diagnostics.add("Основная запись " + (i + 1) + " содержит недопустимый признак загрузки 0x"
                        + Integer.toHexString(entry.bootFlag) + ".");
            }
            PartitionRecord record = toMbrRecord(entry, sectorSize, diskSectors,
                    "Основной раздел " + (i + 1), diagnostics, i + 1);
            if (record != null) {
                primary.add(record);
                if (isExtended(entry.type)) {
                    extended.add(entry);
                }
            }
        }

        for (int i = 0; i < extended.size(); i++) {
            RawEntry container = extended.get(i);
            parseEbrChain(reader, container, diskSectors, sectorSize, logical, diagnostics);
        }
        validateOverlaps(primary, logical, diagnostics);
        return new MbrParseResult(validSignature, primary, logical, diagnostics);
    }

    private static void parseEbrChain(
            DiskReader reader,
            RawEntry container,
            long diskSectors,
            int sectorSize,
            List<PartitionRecord> logical,
            List<String> diagnostics) {
        long base = Integer.toUnsignedLong(container.startLba);
        long endExclusive;
        try {
            endExclusive = Math.addExact(base, Integer.toUnsignedLong(container.sectors));
        } catch (ArithmeticException e) {
            diagnostics.add("Переполнение границы расширенного раздела.");
            return;
        }
        if (container.sectors == 0 || endExclusive > diskSectors) {
            diagnostics.add("Расширенный раздел пуст или выходит за ёмкость диска.");
            return;
        }

        Set<Long> visited = new HashSet<>();
        long ebrLba = base;
        for (int depth = 0; depth < EBR_CHAIN_LIMIT; depth++) {
            if (ebrLba < base || ebrLba >= endExclusive || ebrLba >= diskSectors) {
                diagnostics.add("Ссылка EBR выходит за границы расширенного раздела или диска.");
                return;
            }
            if (!visited.add(ebrLba)) {
                diagnostics.add("Обнаружена циклическая цепочка EBR на LBA " + ebrLba + ".");
                return;
            }

            byte[] ebr;
            try {
                ebr = reader.read(Math.multiplyExact(ebrLba, (long) sectorSize), sectorSize);
            } catch (IOException | ArithmeticException e) {
                diagnostics.add("Не удалось прочитать EBR на LBA " + ebrLba + ": " + e.getMessage());
                return;
            }
            if (u8(ebr, 510) != 0x55 || u8(ebr, 511) != 0xaa) {
                diagnostics.add("Недопустимая сигнатура EBR на LBA " + ebrLba + ".");
                return;
            }

            RawEntry data = readEntry(ebr, PARTITION_TABLE_OFFSET);
            RawEntry link = readEntry(ebr, PARTITION_TABLE_OFFSET + PARTITION_ENTRY_SIZE);
            if (!data.isEmpty()) {
                if (!validBootFlag(data.bootFlag)) {
                    diagnostics.add("Логический раздел в EBR " + ebrLba
                            + " имеет недопустимый признак загрузки.");
                }
                if (data.startLba == 0) {
                    diagnostics.add("Логический раздел в EBR " + ebrLba
                            + " начинается с самого сектора EBR.");
                } else if (isExtended(data.type)) {
                    diagnostics.add("Запись данных EBR на LBA " + ebrLba
                            + " содержит тип расширенного раздела.");
                } else {
                    long first;
                    try {
                        first = Math.addExact(ebrLba, Integer.toUnsignedLong(data.startLba));
                    } catch (ArithmeticException e) {
                        diagnostics.add("Переполнение начального LBA логического раздела.");
                        return;
                    }
                    PartitionRecord part = toMbrRecord(new RawEntry(data.bootFlag, data.type,
                                    data.startLba, data.sectors, first),
                            sectorSize, diskSectors, "Логический раздел", diagnostics, ebrLba);
                    if (part != null) {
                        long partEnd = part.lastLbaInclusive() + 1;
                        if (part.firstLba() < base || partEnd > endExclusive) {
                            diagnostics.add("Логический раздел выходит за границы содержащего его расширенного раздела.");
                        } else {
                            logical.add(part);
                        }
                    }
                }
            }

            if (link.isEmpty()) {
                return;
            }
            if (!validBootFlag(link.bootFlag)) {
                diagnostics.add("Ссылка EBR на LBA " + ebrLba + " имеет недопустимый признак загрузки.");
            }
            if (!isExtended(link.type)) {
                diagnostics.add("Запись ссылки EBR не имеет типа расширенного раздела.");
                return;
            }
            try {
                long nextEbrLba = Math.addExact(base, Integer.toUnsignedLong(link.startLba));
                long linkEndLba = Math.addExact(nextEbrLba, Integer.toUnsignedLong(link.sectors));
                if (link.sectors == 0 || nextEbrLba < base || linkEndLba > endExclusive) {
                    diagnostics.add("Область ссылки EBR пуста или выходит за границы расширенного раздела.");
                    return;
                }
                ebrLba = nextEbrLba;
            } catch (ArithmeticException e) {
                diagnostics.add("Переполнение области ссылки EBR.");
                return;
            }
        }
        diagnostics.add("Цепочка EBR превысила максимальное число записей: " + EBR_CHAIN_LIMIT + ".");
    }

    private static PartitionRecord toMbrRecord(
            RawEntry entry, int sectorSize, long diskSectors, String source,
            List<String> diagnostics, long entryIndex) {
        long startLba = entry.absoluteStartLba == null
                ? Integer.toUnsignedLong(entry.startLba) : entry.absoluteStartLba;
        long sectors = Integer.toUnsignedLong(entry.sectors);
        if (sectors == 0) {
            diagnostics.add(source + " запись " + entryIndex + " содержит нулевое число секторов.");
            return null;
        }
        try {
            long endExclusive = Math.addExact(startLba, sectors);
            if (endExclusive > diskSectors) {
                diagnostics.add(source + " запись " + entryIndex + " выходит за ёмкость диска.");
                return null;
            }
            long startOffset = Math.multiplyExact(startLba, (long) sectorSize);
            long length = Math.multiplyExact(sectors, (long) sectorSize);
            return new PartitionRecord(source, startLba, endExclusive - 1, startOffset,
                    length, entry.bootFlag == 0x80, entry.type, "", "", 0, "");
        } catch (ArithmeticException e) {
            diagnostics.add(source + " запись " + entryIndex + " содержит переполняющийся диапазон.");
            return null;
        }
    }

    private static void validateOverlaps(List<PartitionRecord> primary, List<PartitionRecord> logical,
                                         List<String> diagnostics) {
        List<PartitionRecord> primaryRanges = primary.stream()
                .sorted(java.util.Comparator.comparingLong(PartitionRecord::firstLba)).toList();
        for (int i = 1; i < primaryRanges.size(); i++) {
            if (primaryRanges.get(i).firstLba() <= primaryRanges.get(i - 1).lastLbaInclusive()) {
                diagnostics.add("Обнаружены пересекающиеся диапазоны основных разделов.");
            }
        }

        List<PartitionRecord> ranges = new ArrayList<>();
        for (PartitionRecord entry : primary) {
            if (!isExtended(entry.mbrType())) {
                ranges.add(entry);
            }
        }
        ranges.addAll(logical);
        ranges.sort(java.util.Comparator.comparingLong(PartitionRecord::firstLba));
        for (int i = 1; i < ranges.size(); i++) {
            if (ranges.get(i).firstLba() <= ranges.get(i - 1).lastLbaInclusive()) {
                diagnostics.add("Обнаружены пересекающиеся диапазоны разделов.");
            }
        }
    }

    private static RawEntry readEntry(byte[] bytes, int offset) {
        int boot = u8(bytes, offset);
        int type = u8(bytes, offset + 4);
        int start = i32(bytes, offset + 8);
        int sectors = i32(bytes, offset + 12);
        return new RawEntry(boot, type, start, sectors, null);
    }

    private static boolean validBootFlag(int value) {
        return value == 0 || value == 0x80;
    }

    private static boolean isExtended(int type) {
        return type == 0x05 || type == 0x0f || type == 0x85;
    }

    static int u8(byte[] bytes, int offset) {
        return bytes[offset] & 0xff;
    }

    static int i32(byte[] bytes, int offset) {
        return u8(bytes, offset) | (u8(bytes, offset + 1) << 8)
                | (u8(bytes, offset + 2) << 16) | (u8(bytes, offset + 3) << 24);
    }

    static long u32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(i32(bytes, offset));
    }

    private record RawEntry(int bootFlag, int type, int startLba, int sectors, Long absoluteStartLba) {
        private boolean isEmpty() {
            return bootFlag == 0 && type == 0 && startLba == 0 && sectors == 0;
        }
    }
}
