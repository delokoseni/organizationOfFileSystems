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
            throw new IOException("Недопустимая геометрия диска для разбора GPT.");
        }
        long diskSectors = diskSize / sectorSize;
        if (diskSectors < 3) {
            throw new IOException("Диск слишком мал для размещения метаданных GPT.");
        }

        List<String> diagnostics = new ArrayList<>();
        byte[] mbr = reader.read(0, sectorSize);
        boolean protectiveMbr = isValidProtectiveMbr(mbr, diskSectors);
        if (!protectiveMbr) {
            diagnostics.add(protectiveMbrDiagnostic(mbr, diskSectors));
        }

        GptData primaryData = readHeader(reader, 1, diskSectors, sectorSize, diagnostics, "Основная копия");
        long lastLba = diskSectors - 1;
        GptData backupData = readHeader(reader, lastLba, diskSectors, sectorSize, diagnostics, "Резервная копия");

        if (primaryData == null) {
            diagnostics.add("Основной заголовок GPT отсутствует или не читается.");
        }
        if (backupData == null) {
            diagnostics.add("Резервный заголовок GPT отсутствует или не читается.");
        }
        if (primaryData != null) {
            validateHeaderLocations(primaryData.header, 1, lastLba, diskSectors, diagnostics, "Основная копия");
        }
        if (backupData != null) {
            validateHeaderLocations(backupData.header, lastLba, 1, diskSectors, diagnostics, "Резервная копия");
        }
        boolean copiesConsistent = true;
        if (primaryData != null && backupData != null) {
            compareHeaders(primaryData, backupData, diagnostics);
            copiesConsistent = headersConsistent(primaryData, backupData);
        }

        boolean primaryValid = primaryData != null && primaryData.header.validAt(
                1, lastLba, diskSectors, sectorSize);
        boolean backupValid = backupData != null && backupData.header.validAt(
                lastLba, 1, diskSectors, sectorSize);
        GptData selected = copiesConsistent && primaryValid ? primaryData
                : copiesConsistent && backupValid ? backupData : null;
        if (selected == null && !primaryValid && !backupValid) {
            diagnostics.add("Нет копии GPT с действительными CRC заголовка и массива разделов; "
                    + "проверенными расположениями и допустимой геометрией; записи разделов не отображаются.");
        } else if (selected == null) {
            diagnostics.add("Копии GPT содержат противоречивые подтверждённые сведения; "
                    + "записи разделов не отображаются.");
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
            diagnostics.add("Не удалось прочитать заголовок GPT (" + label + "): " + e.getMessage());
            return null;
        }
        if (!hasSignature(sector, 0)) {
            diagnostics.add("Сигнатура GPT (" + label + ") отсутствует на LBA " + headerLba + ".");
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
            diagnostics.add("Заголовок GPT (" + label + ") содержит неподдерживаемое беззнаковое 64-разрядное значение: "
                    + e.getMessage());
            return null;
        }

        boolean headerCrcValid = false;
        if (headerSize >= HEADER_MIN_SIZE && headerSize <= sectorSize) {
            byte[] headerBytes = Arrays.copyOf(sector, Math.toIntExact(headerSize));
            Arrays.fill(headerBytes, 16, 20, (byte) 0);
            headerCrcValid = InMemoryChecksums.crc32(headerBytes) == storedHeaderCrc;
        } else {
            diagnostics.add("Размер заголовка GPT (" + label + ") выходит за допустимые границы сектора.");
        }
        if (!headerCrcValid) {
            diagnostics.add("CRC32 заголовка GPT (" + label + ") недействительна.");
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
                diagnostics.add("Число или размер записей разделов GPT (" + label
                        + ") превышает пределы анализатора.");
            } else if (entriesLba == 0 || arrayEndLba > diskSectors
                    || entriesLba <= headerLba && headerLba < arrayEndLba) {
                diagnostics.add("Массив записей разделов GPT (" + label
                        + ") выходит за границы диска или перекрывает заголовок.");
            } else {
                if (headerLba == 1 && arrayEndLba > firstUsable
                        || headerLba == diskSectors - 1 && entriesLba <= lastUsable) {
                    diagnostics.add("Массив записей разделов GPT (" + label + ") перекрывает допустимый диапазон LBA.");
                }
                entryArray = reader.read(Math.multiplyExact(entriesLba, (long) sectorSize),
                        Math.toIntExact(arrayLength));
                arrayCrcValid = InMemoryChecksums.crc32(entryArray) == storedArrayCrc;
                if (!arrayCrcValid) {
                    diagnostics.add("CRC32 массива разделов GPT (" + label + ") недействительна.");
                }
            }
        } catch (IOException | ArithmeticException e) {
            diagnostics.add("Не удалось безопасно прочитать массив разделов GPT (" + label + "): " + e.getMessage());
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
            diagnostics.add("Заголовок GPT (" + label + ") расположен на неверном LBA или содержит неверный текущий LBA.");
        }
        if (header.backupLba() != expectedBackupLba) {
            diagnostics.add("Заголовок GPT (" + label + ") содержит неверный резервный LBA.");
        }
        if (header.backupLba() >= diskSectors) {
            diagnostics.add("Резервный LBA заголовка GPT (" + label + ") выходит за ёмкость диска.");
        }
    }

    private static void validateUsableRange(
            GptHeaderInfo header, long diskSectors, List<String> diagnostics, String label) {
        if (header.firstUsableLba() > header.lastUsableLba()
                || header.lastUsableLba() >= diskSectors
                || header.firstUsableLba() <= 1
                || header.lastUsableLba() >= diskSectors - 1) {
            diagnostics.add("Недопустимый диапазон доступных LBA в GPT (" + label + ").");
        }
    }

    private static void compareHeaders(GptData primary, GptData backup, List<String> diagnostics) {
        GptHeaderInfo p = primary.header;
        GptHeaderInfo b = backup.header;
        if (!p.headerCrcValid() || !b.headerCrcValid()) {
            return;
        }
        if (!p.diskGuid().equalsIgnoreCase(b.diskGuid())) {
            diagnostics.add("GUID диска в основной и резервной копиях GPT различаются.");
        }
        if (p.firstUsableLba() != b.firstUsableLba() || p.lastUsableLba() != b.lastUsableLba()) {
            diagnostics.add("Диапазоны доступных LBA в основной и резервной копиях GPT различаются.");
        }
        if (p.entryCount() != b.entryCount() || p.entrySize() != b.entrySize()) {
            diagnostics.add("Размеры записей разделов в основной и резервной копиях GPT различаются.");
        } else if (p.arrayCrcValid() && b.arrayCrcValid()
                && primary.entries != null && backup.entries != null
                && !Arrays.equals(primary.entries, backup.entries)) {
            diagnostics.add("Записи разделов в основной и резервной копиях GPT различаются.");
        }
    }

    private static boolean headersConsistent(GptData primary, GptData backup) {
        GptHeaderInfo p = primary.header;
        GptHeaderInfo b = backup.header;
        if (p.headerCrcValid() && b.headerCrcValid()
                && (!p.diskGuid().equalsIgnoreCase(b.diskGuid())
                || p.firstUsableLba() != b.firstUsableLba()
                || p.lastUsableLba() != b.lastUsableLba()
                || p.entryCount() != b.entryCount()
                || p.entrySize() != b.entrySize())) {
            return false;
        }
        return !(p.arrayCrcValid() && b.arrayCrcValid()
                && primary.entries != null && backup.entries != null
                && !Arrays.equals(primary.entries, backup.entries));
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
                    diagnostics.add("Запись раздела GPT " + (i + 1) + " содержит недопустимые границы LBA.");
                    continue;
                }
                long startOffset = Math.multiplyExact(first, (long) sectorSize);
                long sectors = Math.addExact(Math.subtractExact(last, first), 1);
                long length = Math.multiplyExact(sectors, (long) sectorSize);
                String name = entrySize > 56
                        ? new String(data.entries, offset + 56, entrySize - 56,
                        StandardCharsets.UTF_16LE).replace("\u0000", "").trim() : "";
                partitions.add(new PartitionRecord("Раздел GPT " + (i + 1), first, last,
                        startOffset, length, false, 0, typeGuid, uniqueGuid, attributes, name));
            } catch (ArithmeticException | IllegalArgumentException e) {
                diagnostics.add("Запись раздела GPT " + (i + 1) + " содержит недопустимые значения или переполнение.");
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
                diagnostics.add("Обнаружены пересекающиеся диапазоны разделов GPT.");
            }
        }
    }

    private static boolean isValidProtectiveMbr(byte[] mbr, long diskSectors) {
        if (!validSignature(mbr) || diskSectors < 2) {
            return false;
        }
        int protectiveCount = 0;
        for (int i = 0; i < 4; i++) {
            int offset = 446 + i * 16;
            if ((mbr[offset + 4] & 0xff) != 0xee) {
                continue;
            }
            protectiveCount++;
            long firstLba = u32(mbr, offset + 8);
            long sectors = u32(mbr, offset + 12);
            long expectedSectors = Math.min(diskSectors - 1, 0xffff_ffffL);
            boolean validSectors = sectors == expectedSectors
                    || diskSectors - 1 <= 0xffff_ffffL && sectors == 0xffff_ffffL;
            if (firstLba != 1 || !validSectors) {
                return false;
            }
        }
        return protectiveCount == 1;
    }

    private static String protectiveMbrDiagnostic(byte[] mbr, long diskSectors) {
        if (!validSignature(mbr)) {
            return "Защитная запись MBR 0xEE не проверена: подпись MBR 0x55AA отсутствует.";
        }
        int count = 0;
        int entryOffset = -1;
        for (int i = 0; i < 4; i++) {
            int offset = 446 + i * 16;
            if ((mbr[offset + 4] & 0xff) == 0xee) {
                count++;
                entryOffset = offset;
            }
        }
        if (count == 0) {
            return "Защитная запись MBR типа 0xEE отсутствует.";
        }
        if (count != 1) {
            return "Обнаружено несколько защитных записей MBR типа 0xEE (" + count + ").";
        }
        long startLba = u32(mbr, entryOffset + 8);
        long sectors = u32(mbr, entryOffset + 12);
        long expectedSectors = Math.min(diskSectors - 1, 0xffff_ffffL);
        boolean validSectors = sectors == expectedSectors
                || diskSectors - 1 <= 0xffff_ffffL && sectors == 0xffff_ffffL;
        if (startLba != 1 || !validSectors) {
            long lastLba = sectors == 0 ? startLba : startLba + sectors - 1;
            long expectedLastLba = expectedSectors;
            return "Защитная запись MBR 0xEE присутствует, но задаёт диапазон LBA ["
                    + startLba + ", " + lastLba + "]; ожидается [1, " + expectedLastLba
                    + "] для геометрии диска (" + diskSectors + " логических секторов).";
        }
        return "Защитная запись MBR 0xEE недействительна.";
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
            throw new IllegalArgumentException("Беззнаковый LBA превышает знаковый 64-разрядный диапазон.");
        }
        return value;
    }

    private static long u64Bits(byte[] bytes, int offset) {
        return (u32(bytes, offset + 4) << 32) | u32(bytes, offset);
    }

    private record GptData(GptHeaderInfo header, byte[] entries) {
    }
}
