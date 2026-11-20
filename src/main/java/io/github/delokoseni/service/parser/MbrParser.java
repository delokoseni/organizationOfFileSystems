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
            throw new IOException("Invalid disk geometry or logical sector size for MBR parsing");
        }
        byte[] sector = reader.read(0, sectorSize);
        List<String> diagnostics = new ArrayList<>();
        List<PartitionRecord> primary = new ArrayList<>();
        List<PartitionRecord> logical = new ArrayList<>();
        boolean validSignature = u8(sector, 510) == 0x55 && u8(sector, 511) == 0xaa;
        if (!validSignature) {
            diagnostics.add("Invalid MBR signature (expected 0x55AA).");
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
                diagnostics.add("Primary entry " + (i + 1) + " has invalid boot indicator 0x"
                        + Integer.toHexString(entry.bootFlag) + ".");
            }
            PartitionRecord record = toMbrRecord(entry, sectorSize, diskSectors,
                    "Primary " + (i + 1), diagnostics, i + 1);
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
            diagnostics.add("Extended partition boundary overflows.");
            return;
        }
        if (container.sectors == 0 || endExclusive > diskSectors) {
            diagnostics.add("Extended partition is empty or outside disk capacity.");
            return;
        }

        Set<Long> visited = new HashSet<>();
        long ebrLba = base;
        for (int depth = 0; depth < EBR_CHAIN_LIMIT; depth++) {
            if (ebrLba < base || ebrLba >= endExclusive || ebrLba >= diskSectors) {
                diagnostics.add("EBR link points outside its extended partition or disk.");
                return;
            }
            if (!visited.add(ebrLba)) {
                diagnostics.add("Cyclic EBR chain detected at LBA " + ebrLba + ".");
                return;
            }

            byte[] ebr;
            try {
                ebr = reader.read(Math.multiplyExact(ebrLba, (long) sectorSize), sectorSize);
            } catch (IOException | ArithmeticException e) {
                diagnostics.add("Could not read EBR at LBA " + ebrLba + ": " + e.getMessage());
                return;
            }
            if (u8(ebr, 510) != 0x55 || u8(ebr, 511) != 0xaa) {
                diagnostics.add("Invalid EBR signature at LBA " + ebrLba + ".");
                return;
            }

            RawEntry data = readEntry(ebr, PARTITION_TABLE_OFFSET);
            RawEntry link = readEntry(ebr, PARTITION_TABLE_OFFSET + PARTITION_ENTRY_SIZE);
            if (!data.isEmpty()) {
                if (!validBootFlag(data.bootFlag)) {
                    diagnostics.add("Logical partition at EBR " + ebrLba + " has an invalid boot indicator.");
                }
                if (data.startLba == 0) {
                    diagnostics.add("Logical partition at EBR " + ebrLba
                            + " starts on the EBR sector itself.");
                } else if (isExtended(data.type)) {
                    diagnostics.add("EBR data entry at LBA " + ebrLba
                            + " has an extended-partition type.");
                } else {
                    long first;
                    try {
                        first = Math.addExact(ebrLba, Integer.toUnsignedLong(data.startLba));
                    } catch (ArithmeticException e) {
                        diagnostics.add("Logical partition start LBA overflows.");
                        return;
                    }
                    PartitionRecord part = toMbrRecord(new RawEntry(data.bootFlag, data.type,
                                    data.startLba, data.sectors, first),
                            sectorSize, diskSectors, "Logical", diagnostics, ebrLba);
                    if (part != null) {
                        long partEnd = part.lastLbaInclusive() + 1;
                        if (part.firstLba() < base || partEnd > endExclusive) {
                            diagnostics.add("Logical partition extends outside its containing extended partition.");
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
                diagnostics.add("EBR link at LBA " + ebrLba + " has an invalid boot indicator.");
            }
            if (!isExtended(link.type)) {
                diagnostics.add("EBR link entry is not an extended partition type.");
                return;
            }
            try {
                long nextEbrLba = Math.addExact(base, Integer.toUnsignedLong(link.startLba));
                long linkEndLba = Math.addExact(nextEbrLba, Integer.toUnsignedLong(link.sectors));
                if (link.sectors == 0 || nextEbrLba < base || linkEndLba > endExclusive) {
                    diagnostics.add("EBR link extent is empty or outside its extended partition.");
                    return;
                }
                ebrLba = nextEbrLba;
            } catch (ArithmeticException e) {
                diagnostics.add("EBR link extent overflows.");
                return;
            }
        }
        diagnostics.add("EBR chain exceeded the maximum of " + EBR_CHAIN_LIMIT + " records.");
    }

    private static PartitionRecord toMbrRecord(
            RawEntry entry, int sectorSize, long diskSectors, String source,
            List<String> diagnostics, long entryIndex) {
        long startLba = entry.absoluteStartLba == null
                ? Integer.toUnsignedLong(entry.startLba) : entry.absoluteStartLba;
        long sectors = Integer.toUnsignedLong(entry.sectors);
        if (sectors == 0) {
            diagnostics.add(source + " entry " + entryIndex + " has a zero sector count.");
            return null;
        }
        try {
            long endExclusive = Math.addExact(startLba, sectors);
            if (endExclusive > diskSectors) {
                diagnostics.add(source + " entry " + entryIndex + " exceeds disk capacity.");
                return null;
            }
            long startOffset = Math.multiplyExact(startLba, (long) sectorSize);
            long length = Math.multiplyExact(sectors, (long) sectorSize);
            return new PartitionRecord(source, startLba, endExclusive - 1, startOffset,
                    length, entry.bootFlag == 0x80, entry.type, "", "", 0, "");
        } catch (ArithmeticException e) {
            diagnostics.add(source + " entry " + entryIndex + " has an overflowing range.");
            return null;
        }
    }

    private static void validateOverlaps(List<PartitionRecord> primary, List<PartitionRecord> logical,
                                         List<String> diagnostics) {
        List<PartitionRecord> primaryRanges = primary.stream()
                .sorted(java.util.Comparator.comparingLong(PartitionRecord::firstLba)).toList();
        for (int i = 1; i < primaryRanges.size(); i++) {
            if (primaryRanges.get(i).firstLba() <= primaryRanges.get(i - 1).lastLbaInclusive()) {
                diagnostics.add("Overlapping primary partition ranges detected.");
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
                diagnostics.add("Overlapping partition ranges detected.");
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
