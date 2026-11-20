package io.github.delokoseni.service.parser;

import io.github.delokoseni.service.disk.DiskReader;
import io.github.delokoseni.util.InMemoryChecksums;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class GptParser {
    private static final byte[] GPT_SIGNATURE = "EFI PART".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_MIN_SIZE = 92;
    private static final int MAX_ENTRY_COUNT = 16_384;
    private static final int MAX_ENTRY_ARRAY_BYTES = 32 * 1024 * 1024;
    private static final int ENTRY_MIN_SIZE = 128;
    private static final int MAX_LOGICAL_SECTOR_SIZE = 1024 * 1024;

    public GptParseResult parse(DiskReader reader) throws IOException {
        int sectorSize = reader.getLogicalSectorSize();
        long diskSize = reader.getDiskSize();
        if (sectorSize < 512 || sectorSize > MAX_LOGICAL_SECTOR_SIZE
                || diskSize < 0 || diskSize % sectorSize != 0) {
            throw new IOException("Invalid disk geometry for GPT parsing");
        }
        long diskSectors = diskSize / sectorSize;
        if (diskSectors < 3) {
            throw new IOException("Disk is too small to contain GPT metadata");
        }

        List<String> diagnostics = new ArrayList<>();
        byte[] mbr = reader.read(0, sectorSize);
        boolean protectiveMbr = validSignature(mbr)
                && hasProtectiveMbrEntry(mbr);
        if (!protectiveMbr) {
            diagnostics.add("Protective MBR signature or partition type 0xEE is missing.");
        }

        GptData primaryData = readHeader(reader, 1, diskSectors, sectorSize, diagnostics, "Primary");
        long lastLba = diskSectors - 1;
        GptData backupData = readHeader(reader, lastLba, diskSectors, sectorSize, diagnostics, "Backup");

        if (primaryData == null) {
            diagnostics.add("Primary GPT header is missing or unreadable.");
        }
        if (backupData == null) {
            diagnostics.add("Backup GPT header is missing or unreadable.");
        }
        if (primaryData != null) {
            validateHeaderLocations(primaryData.header, 1, lastLba, diskSectors, diagnostics, "Primary");
        }
        if (backupData != null) {
            validateHeaderLocations(backupData.header, lastLba, 1, diskSectors, diagnostics, "Backup");
        }
        if (primaryData != null && backupData != null) {
            compareHeaders(primaryData, backupData, diagnostics);
        }

        GptData selected = primaryData != null && primaryData.header.structurallyValid()
                ? primaryData
                : backupData != null && backupData.header.structurallyValid() ? backupData : null;
        if (selected == null && (primaryData != null || backupData != null)) {
            diagnostics.add("No GPT copy with valid header and partition-array CRCs is available; "
                    + "partition entries were not displayed.");
        }
        List<PartitionRecord> partitions = selected == null
                ? List.of() : decodePartitions(selected, sectorSize, diskSectors, diagnostics);
        return new GptParseResult(protectiveMbr,
                Optional.ofNullable(primaryData).map(data -> data.header),
                Optional.ofNullable(backupData).map(data -> data.header),
                partitions, diagnostics);
    }

    private static GptData readHeader(
            DiskReader reader, long headerLba, long diskSectors, int sectorSize,
            List<String> diagnostics, String label) {
        byte[] sector;
        try {
            sector = reader.read(Math.multiplyExact(headerLba, (long) sectorSize), sectorSize);
        } catch (IOException | ArithmeticException e) {
            diagnostics.add(label + " GPT header read failed: " + e.getMessage());
            return null;
        }
        if (!hasSignature(sector, 0)) {
            diagnostics.add(label + " GPT signature is missing at LBA " + headerLba + ".");
            return null;
        }

        long revision = u32(sector, 8);
        long headerSize = u32(sector, 12);
        long storedHeaderCrc = u32(sector, 16);
        long currentLba;
        long backupLba;
        long firstUsable;
        long lastUsable;
        String diskGuid;
        long entriesLba;
        long entryCount;
        long entrySize;
        long storedArrayCrc;
        try {
            currentLba = u64(sector, 24);
            backupLba = u64(sector, 32);
            firstUsable = u64(sector, 40);
            lastUsable = u64(sector, 48);
            diskGuid = guid(sector, 56);
            entriesLba = u64(sector, 72);
            entryCount = u32(sector, 80);
            entrySize = u32(sector, 84);
            storedArrayCrc = u32(sector, 88);
        } catch (IllegalArgumentException e) {
            diagnostics.add(label + " GPT header contains an unsupported unsigned 64-bit value: "
                    + e.getMessage());
            return null;
        }

        boolean headerCrcValid = false;
        if (headerSize >= HEADER_MIN_SIZE && headerSize <= sectorSize) {
            byte[] headerBytes = Arrays.copyOf(sector, Math.toIntExact(headerSize));
            Arrays.fill(headerBytes, 16, 20, (byte) 0);
            headerCrcValid = InMemoryChecksums.crc32(headerBytes) == storedHeaderCrc;
        } else {
            diagnostics.add(label + " GPT header size is outside the valid sector bounds.");
        }
        if (!headerCrcValid) {
            diagnostics.add(label + " GPT header CRC32 is invalid.");
        }

        byte[] entryArray = null;
        boolean arrayCrcValid = false;
        try {
            long arrayLength = Math.multiplyExact(entryCount, entrySize);
            long arraySectors = Math.addExact(arrayLength, sectorSize - 1L) / sectorSize;
            long arrayEndLba = Math.addExact(entriesLba, arraySectors);
            if (entryCount == 0 || entryCount > MAX_ENTRY_COUNT
                    || entrySize < ENTRY_MIN_SIZE || entrySize % 8 != 0
                    || arrayLength > MAX_ENTRY_ARRAY_BYTES || arrayLength > Integer.MAX_VALUE) {
                diagnostics.add(label + " GPT partition entry count or size exceeds parser limits.");
            } else if (entriesLba == 0 || arrayEndLba > diskSectors
                    || entriesLba <= headerLba && headerLba < arrayEndLba) {
                diagnostics.add(label + " GPT partition entry array is outside disk bounds or overlaps its header.");
            } else {
                if (headerLba == 1 && arrayEndLba > firstUsable
                        || headerLba == diskSectors - 1 && entriesLba <= lastUsable) {
                    diagnostics.add(label + " GPT partition entry array overlaps the usable LBA range.");
                }
                entryArray = reader.read(Math.multiplyExact(entriesLba, (long) sectorSize),
                        Math.toIntExact(arrayLength));
                arrayCrcValid = InMemoryChecksums.crc32(entryArray) == storedArrayCrc;
                if (!arrayCrcValid) {
                    diagnostics.add(label + " GPT partition array CRC32 is invalid.");
                }
            }
        } catch (IOException | ArithmeticException e) {
            diagnostics.add(label + " GPT partition array could not be read safely: " + e.getMessage());
        }

        GptHeaderInfo info = new GptHeaderInfo(headerLba, true, headerCrcValid, revision, headerSize,
                storedHeaderCrc, currentLba, backupLba, firstUsable, lastUsable, diskGuid,
                entriesLba, entryCount, entrySize, storedArrayCrc, arrayCrcValid);
        validateUsableRange(info, diskSectors, diagnostics, label);
        return new GptData(info, entryArray);
    }

    private static void validateHeaderLocations(
            GptHeaderInfo header, long expectedCurrentLba, long expectedBackupLba,
            long diskSectors, List<String> diagnostics, String label) {
        if (header.currentLba() != expectedCurrentLba || header.lba() != expectedCurrentLba) {
            diagnostics.add(label + " GPT header is at or reports an incorrect current LBA.");
        }
        if (header.backupLba() != expectedBackupLba) {
            diagnostics.add(label + " GPT header reports an incorrect backup LBA.");
        }
        if (header.backupLba() >= diskSectors) {
            diagnostics.add(label + " GPT backup LBA lies outside disk capacity.");
        }
    }

    private static void validateUsableRange(
            GptHeaderInfo header, long diskSectors, List<String> diagnostics, String label) {
        if (header.firstUsableLba() > header.lastUsableLba()
                || header.lastUsableLba() >= diskSectors
                || header.firstUsableLba() <= 1
                || header.lastUsableLba() >= diskSectors - 1) {
            diagnostics.add(label + " GPT usable LBA range is invalid.");
        }
    }

    private static void compareHeaders(GptData primary, GptData backup, List<String> diagnostics) {
        GptHeaderInfo p = primary.header;
        GptHeaderInfo b = backup.header;
        if (!p.diskGuid().equalsIgnoreCase(b.diskGuid())) {
            diagnostics.add("Primary and backup GPT disk GUIDs differ.");
        }
        if (p.firstUsableLba() != b.firstUsableLba() || p.lastUsableLba() != b.lastUsableLba()) {
            diagnostics.add("Primary and backup GPT usable LBA ranges differ.");
        }
        if (p.entryCount() != b.entryCount() || p.entrySize() != b.entrySize()) {
            diagnostics.add("Primary and backup GPT partition entry dimensions differ.");
        } else if (primary.entries != null && backup.entries != null
                && !Arrays.equals(primary.entries, backup.entries)) {
            diagnostics.add("Primary and backup GPT partition entries differ.");
        }
    }

    private static List<PartitionRecord> decodePartitions(
            GptData data, int sectorSize, long diskSectors, List<String> diagnostics) {
        if (data.entries == null) {
            return List.of();
        }
        List<PartitionRecord> partitions = new ArrayList<>();
        int entrySize = Math.toIntExact(data.header.entrySize());
        int count = Math.toIntExact(data.header.entryCount());
        for (int i = 0; i < count; i++) {
            int offset = Math.multiplyExact(i, entrySize);
            if (isZeroGuid(data.entries, offset)) {
                continue;
            }
            try {
                String typeGuid = guid(data.entries, offset);
                String uniqueGuid = guid(data.entries, offset + 16);
                long first = u64(data.entries, offset + 32);
                long last = u64(data.entries, offset + 40);
                long attributes = u64Bits(data.entries, offset + 48);
                if (first > last || first < data.header.firstUsableLba()
                        || last > data.header.lastUsableLba() || last >= diskSectors) {
                    diagnostics.add("GPT partition entry " + (i + 1) + " has invalid LBA boundaries.");
                    continue;
                }
                long startOffset = Math.multiplyExact(first, (long) sectorSize);
                long sectors = Math.addExact(Math.subtractExact(last, first), 1);
                long length = Math.multiplyExact(sectors, (long) sectorSize);
                String name = entrySize > 56
                        ? new String(data.entries, offset + 56, entrySize - 56,
                        StandardCharsets.UTF_16LE).replace("\u0000", "").trim() : "";
                partitions.add(new PartitionRecord("GPT " + (i + 1), first, last,
                        startOffset, length, false, 0, typeGuid, uniqueGuid, attributes, name));
            } catch (ArithmeticException | IllegalArgumentException e) {
                diagnostics.add("GPT partition entry " + (i + 1) + " contains invalid or overflowing values.");
            }
        }
        validatePartitionOverlaps(partitions, diagnostics);
        return partitions;
    }

    private static void validatePartitionOverlaps(List<PartitionRecord> partitions, List<String> diagnostics) {
        List<PartitionRecord> ordered = partitions.stream()
                .sorted(java.util.Comparator.comparingLong(PartitionRecord::firstLba)).toList();
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).firstLba() <= ordered.get(i - 1).lastLbaInclusive()) {
                diagnostics.add("Overlapping GPT partition ranges detected.");
            }
        }
    }

    private static boolean hasProtectiveMbrEntry(byte[] mbr) {
        for (int i = 0; i < 4; i++) {
            int typeOffset = 446 + i * 16 + 4;
            if ((mbr[typeOffset] & 0xff) == 0xee) {
                return true;
            }
        }
        return false;
    }

    private static boolean validSignature(byte[] bytes) {
        return bytes.length >= 512 && (bytes[510] & 0xff) == 0x55 && (bytes[511] & 0xff) == 0xaa;
    }

    private static boolean hasSignature(byte[] bytes, int offset) {
        if (bytes.length - offset < GPT_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < GPT_SIGNATURE.length; i++) {
            if (bytes[offset + i] != GPT_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isZeroGuid(byte[] bytes, int offset) {
        for (int i = 0; i < 16; i++) {
            if (bytes[offset + i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static String guid(byte[] bytes, int offset) {
        long data1 = u32(bytes, offset);
        int data2 = u16(bytes, offset + 4);
        int data3 = u16(bytes, offset + 6);
        StringBuilder result = new StringBuilder(36);
        result.append(String.format(java.util.Locale.ROOT, "%08x-%04x-%04x-", data1, data2, data3));
        for (int i = 8; i < 10; i++) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", bytes[offset + i] & 0xff));
        }
        result.append('-');
        for (int i = 10; i < 16; i++) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", bytes[offset + i] & 0xff));
        }
        return result.toString();
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong((bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24));
    }

    private static long u64(byte[] bytes, int offset) {
        long low = u32(bytes, offset);
        long high = u32(bytes, offset + 4);
        long value = (high << 32) | low;
        if (value < 0) {
            throw new IllegalArgumentException("unsigned LBA exceeds signed 64-bit range");
        }
        return value;
    }

    private static long u64Bits(byte[] bytes, int offset) {
        return (u32(bytes, offset + 4) << 32) | u32(bytes, offset);
    }

    private record GptData(GptHeaderInfo header, byte[] entries) {
    }
}
