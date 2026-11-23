package io.github.delokoseni.service.parser;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GptParserTest {
    private static final int SECTOR = 512;
    private static final int DISK_SECTORS = 256;
    private final GptParser parser = new GptParser();

    @Test
    void parsesValidPrimaryAndBackupGptAndPartitionName() throws Exception {
        byte[] disk = validGpt(128);
        GptParseResult result = parser.parse(reader(disk));

        assertTrue(result.protectiveMbrValid());
        assertTrue(result.primary().orElseThrow().structurallyValid());
        assertTrue(result.backup().orElseThrow().structurallyValid());
        assertEquals(1, result.partitions().size());
        assertEquals(40, result.partitions().getFirst().firstLba());
        assertEquals("DATA", result.partitions().getFirst().name());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void detectsInvalidHeaderAndPartitionArrayCrcs() throws Exception {
        byte[] badHeader = validGpt(128);
        badHeader[SECTOR + 16] ^= 0x01;
        GptParseResult badHeaderResult = parser.parse(reader(badHeader));
        assertTrue(badHeaderResult.diagnostics().stream()
                .anyMatch(text -> text.contains("CRC32 заголовка GPT")));
        assertEquals(1, badHeaderResult.partitions().size(),
                "A valid backup GPT should be used when the primary header CRC is invalid");

        byte[] badArray = validGpt(128);
        badArray[2 * SECTOR + 64] ^= 0x01;
        badArray[254 * SECTOR + 64] ^= 0x01;
        GptParseResult result = parser.parse(reader(badArray));
        assertTrue(result.diagnostics().stream().anyMatch(text -> text.contains("CRC32 массива разделов")));
        assertTrue(result.partitions().isEmpty(),
                "Entries with invalid primary and backup array CRCs must not be displayed");
    }

    @Test
    void diagnosesMissingBackupAndInconsistentPrimaryBackupEntries() throws Exception {
        byte[] missingBackup = validGpt(128);
        Arrays.fill(missingBackup, (DISK_SECTORS - 1) * SECTOR,
                DISK_SECTORS * SECTOR, (byte) 0);
        assertTrue(parser.parse(reader(missingBackup)).diagnostics().stream()
                .anyMatch(text -> text.contains("Резервный заголовок GPT отсутствует")));

        byte[] inconsistent = validGpt(128);
        inconsistent[254 * SECTOR + 64] ^= 0x01;
        updateArrayCrc(inconsistent, 254, 1, 128);
        updateHeaderCrc(inconsistent, 255);
        GptParseResult result = parser.parse(reader(inconsistent));
        assertTrue(result.diagnostics().stream().anyMatch(text -> text.contains("Записи разделов")));
    }

    @Test
    void detectsMissingPrimaryAndInconsistentDiskGuid() throws Exception {
        byte[] missingPrimary = validGpt(128);
        Arrays.fill(missingPrimary, SECTOR, 2 * SECTOR, (byte) 0);
        assertTrue(parser.parse(reader(missingPrimary)).diagnostics().stream()
                .anyMatch(text -> text.contains("Основной заголовок GPT отсутствует")));

        byte[] differentGuid = validGpt(128);
        differentGuid[255 * SECTOR + 56] ^= 0x01;
        updateHeaderCrc(differentGuid, 255);
        assertTrue(parser.parse(reader(differentGuid)).diagnostics().stream()
                .anyMatch(text -> text.contains("GUID диска")));
        PartitionTableAnalysis ambiguous = new PartitionTableAnalysisService().analyze(reader(differentGuid));
        assertEquals(io.github.delokoseni.model.PartitionStyle.AMBIGUOUS, ambiguous.style());
        assertTrue(ambiguous.partitions().isEmpty());
    }

    @Test
    void detectsIncorrectHeaderLocationsAndUsableRanges() throws Exception {
        byte[] wrongBackupLocation = validGpt(128);
        put64(wrongBackupLocation, 255 * SECTOR + 24, 254);
        updateHeaderCrc(wrongBackupLocation, 255);
        assertTrue(parser.parse(reader(wrongBackupLocation)).diagnostics().stream()
                .anyMatch(text -> text.contains("неверный текущий LBA")));

        byte[] invalidUsableRange = validGpt(128);
        put64(invalidUsableRange, SECTOR + 40, 230);
        put64(invalidUsableRange, 255 * SECTOR + 40, 230);
        updateHeaderCrc(invalidUsableRange, 1);
        updateHeaderCrc(invalidUsableRange, 255);
        assertTrue(parser.parse(reader(invalidUsableRange)).diagnostics().stream()
                .anyMatch(text -> text.contains("Недопустимый диапазон доступных LBA")));
    }

    @Test
    void usesEntryArrayLocationFromHeaderAndRejectsUnsignedLbaOverflow() throws Exception {
        byte[] movedArrays = validGpt(128);
        System.arraycopy(movedArrays, 2 * SECTOR, movedArrays, 3 * SECTOR, 128);
        System.arraycopy(movedArrays, 254 * SECTOR, movedArrays, 253 * SECTOR, 128);
        put64(movedArrays, SECTOR + 72, 3);
        put64(movedArrays, 255 * SECTOR + 72, 253);
        updateHeaderCrc(movedArrays, 1);
        updateHeaderCrc(movedArrays, 255);
        assertTrue(parser.parse(reader(movedArrays)).diagnostics().isEmpty());

        byte[] overflowingLba = validGpt(128);
        put64(overflowingLba, SECTOR + 24, Long.MIN_VALUE);
        updateHeaderCrc(overflowingLba, 1);
        assertTrue(parser.parse(reader(overflowingLba)).diagnostics().stream()
                .anyMatch(text -> text.contains("беззнаковое 64-разрядное")));
    }

    @Test
    void rejectsInvalidPartitionBoundariesAndUnexpectedEntrySize() throws Exception {
        byte[] invalidBounds = validGpt(128);
        put64(invalidBounds, 2 * SECTOR + 32, 30);
        put64(invalidBounds, 2 * SECTOR + 40, 20);
        copyArray(invalidBounds, 2, 254, 1, 128);
        updateArrayCrc(invalidBounds, 2, 1, 128);
        updateArrayCrc(invalidBounds, 254, 1, 128);
        updateHeaderCrc(invalidBounds, 1);
        updateHeaderCrc(invalidBounds, 255);
        assertTrue(parser.parse(reader(invalidBounds)).diagnostics().stream()
                .anyMatch(text -> text.contains("недопустимые границы LBA")));

        byte[] invalidEntrySize = validGpt(129);
        assertTrue(parser.parse(reader(invalidEntrySize)).diagnostics().stream()
                .anyMatch(text -> text.contains("Число или размер записей")));
    }

    @Test
    void reportsBadMbrAndRejectsOversizedEntryArray() throws Exception {
        byte[] noProtectiveMbr = validGpt(128);
        noProtectiveMbr[446 + 4] = 0;
        GptParseResult result = parser.parse(reader(noProtectiveMbr));
        assertFalse(result.protectiveMbrValid());

        byte[] oversized = validGpt(128);
        put32(oversized, SECTOR + 80, 1_048_577);
        updateHeaderCrc(oversized, 1);
        assertTrue(parser.parse(reader(oversized)).diagnostics().stream()
                .anyMatch(text -> text.contains("пределы анализатора")));

        byte[] oversizedProtectiveEntry = validGpt(128);
        put32(oversizedProtectiveEntry, 446 + 12, -1);
        GptParseResult oversizedProtectiveResult = parser.parse(reader(oversizedProtectiveEntry));
        assertFalse(oversizedProtectiveResult.protectiveMbrValid());
        assertTrue(oversizedProtectiveResult.diagnostics().stream().anyMatch(text ->
                text.contains("Защитная запись MBR 0xEE присутствует")
                        && text.contains("задаёт диапазон LBA [1, 4294967295]")
                        && text.contains("ожидается [1, 255]")));
        assertEquals(io.github.delokoseni.service.diagnostics.DiagnosticSeverity.WARNING,
                new io.github.delokoseni.service.diagnostics.DiagnosticService()
                        .classify("Физический диск 1", oversizedProtectiveResult.diagnostics()).stream()
                        .filter(finding -> finding.message().contains("Защитная запись MBR"))
                        .findFirst().orElseThrow().severity());
        assertEquals(1, oversizedProtectiveResult.partitions().size(),
                "A malformed protective MBR range does not invalidate CRC-verified GPT data");
    }

    @Test
    void identifiesConventionalMbrAndValidProtectiveGptFromVerifiedStructures() throws Exception {
        byte[] conventionalMbr = new byte[DISK_SECTORS * SECTOR];
        putMbrEntry(conventionalMbr, 446, 0x07, 40, 20);
        conventionalMbr[510] = 0x55;
        conventionalMbr[511] = (byte) 0xaa;
        PartitionTableAnalysis mbr = new PartitionTableAnalysisService().analyze(reader(conventionalMbr));
        assertEquals(io.github.delokoseni.model.PartitionStyle.MBR, mbr.style());
        assertEquals(1, mbr.partitions().size());
        assertTrue(mbr.diagnostics().isEmpty(),
                "A conventional MBR layout does not require a protective 0xEE entry");

        PartitionTableAnalysis gpt = new PartitionTableAnalysisService().analyze(reader(validGpt(128)));
        assertEquals(io.github.delokoseni.model.PartitionStyle.GPT, gpt.style());
        assertEquals(1, gpt.partitions().size());
        assertTrue(gpt.gpt().orElseThrow().protectiveMbrValid());
    }

    @Test
    void fallsBackToOnlyTheCrcVerifiedGptArray() throws Exception {
        byte[] invalidPrimary = validGpt(128);
        invalidPrimary[2 * SECTOR + 64] ^= 0x01;
        PartitionTableAnalysis primaryFallback = new PartitionTableAnalysisService()
                .analyze(reader(invalidPrimary));
        assertEquals(io.github.delokoseni.model.PartitionStyle.GPT, primaryFallback.style());
        assertEquals(1, primaryFallback.partitions().size());

        byte[] invalidBackup = validGpt(128);
        invalidBackup[254 * SECTOR + 64] ^= 0x01;
        PartitionTableAnalysis backupFallback = new PartitionTableAnalysisService()
                .analyze(reader(invalidBackup));
        assertEquals(io.github.delokoseni.model.PartitionStyle.GPT, backupFallback.style());
        assertEquals(1, backupFallback.partitions().size());
        assertTrue(backupFallback.diagnostics().stream()
                .anyMatch(text -> text.contains("CRC32 массива разделов GPT (Резервная копия)")));
    }

    @Test
    void retainsValidMbrWhenGptMetadataIsStaleAndReportsHybridOrInvalidLayouts() throws Exception {
        byte[] stale = validGpt(128);
        Arrays.fill(stale, 2 * SECTOR, 2 * SECTOR + 128, (byte) 0);
        updateArrayCrc(stale, 2, 1, 128);
        updateHeaderCrc(stale, 1);
        putMbrEntry(stale, 446, 0x07, 40, 215);
        stale[446 + 4] = 0x07;
        stale[510] = 0x55;
        stale[511] = (byte) 0xaa;
        stale[254 * SECTOR + 64] ^= 0x01;
        PartitionTableAnalysis mbr = new PartitionTableAnalysisService().analyze(reader(stale));
        assertEquals(io.github.delokoseni.model.PartitionStyle.MBR, mbr.style());
        assertEquals(1, mbr.partitions().size());
        assertTrue(mbr.diagnostics().stream().anyMatch(text -> text.contains("действительная таблица MBR")));
        assertTrue(mbr.diagnostics().stream().anyMatch(text ->
                text.contains("перекрывает действительный MBR-раздел")));
        assertTrue(mbr.diagnostics().stream().anyMatch(text ->
                text.contains("CRC32 массива разделов GPT (Резервная копия)")));
        assertFalse(mbr.diagnostics().stream().anyMatch(text ->
                text.startsWith("Защитный MBR отсутствует")));
        assertFalse(mbr.diagnostics().stream().anyMatch(text ->
                text.contains("Записи разделов в основной и резервной копиях GPT различаются")));
        assertTrue(new io.github.delokoseni.service.diagnostics.DiagnosticService()
                .classify("Physical disk 2", mbr.diagnostics()).stream()
                .filter(finding -> finding.message().contains("GPT"))
                .allMatch(finding -> finding.severity()
                        == io.github.delokoseni.service.diagnostics.DiagnosticSeverity.WARNING));

        byte[] hybrid = validGpt(128);
        putMbrEntry(hybrid, 462, 0x07, 80, 10);
        PartitionTableAnalysis hybridResult = new PartitionTableAnalysisService().analyze(reader(hybrid));
        assertEquals(io.github.delokoseni.model.PartitionStyle.HYBRID, hybridResult.style());
        assertTrue(hybridResult.partitions().isEmpty());
        assertTrue(hybridResult.diagnostics().stream().anyMatch(text -> text.contains("гибридная разметка")));

        byte[] ambiguous = validGpt(128);
        putMbrEntry(ambiguous, 446, 0x07, 40, 20);
        PartitionTableAnalysis ambiguousResult = new PartitionTableAnalysisService().analyze(reader(ambiguous));
        assertEquals(io.github.delokoseni.model.PartitionStyle.AMBIGUOUS, ambiguousResult.style());
        assertTrue(ambiguousResult.partitions().isEmpty());

        byte[] bothInvalid = validGpt(128);
        bothInvalid[2 * SECTOR + 64] ^= 0x01;
        bothInvalid[254 * SECTOR + 64] ^= 0x01;
        PartitionTableAnalysis invalidResult = new PartitionTableAnalysisService().analyze(reader(bothInvalid));
        assertEquals(io.github.delokoseni.model.PartitionStyle.RAW, invalidResult.style());
        assertTrue(invalidResult.partitions().isEmpty());
    }

    private static void putMbrEntry(byte[] disk, int offset, int type, long start, long count) {
        disk[offset + 4] = (byte) type;
        put32(disk, offset + 8, (int) start);
        put32(disk, offset + 12, (int) count);
    }

    private static SyntheticDiskReader reader(byte[] bytes) {
        return new SyntheticDiskReader(bytes, SECTOR);
    }

    private static byte[] validGpt(int entrySize) {
        byte[] disk = new byte[DISK_SECTORS * SECTOR];
        disk[446 + 4] = (byte) 0xee;
        disk[446 + 8] = 1;
        put32(disk, 446 + 12, DISK_SECTORS - 1);
        disk[510] = 0x55;
        disk[511] = (byte) 0xaa;

        byte[] primaryArray = new byte[entrySize];
        byte[] backupArray = new byte[entrySize];
        makePartitionEntry(primaryArray, entrySize);
        System.arraycopy(primaryArray, 0, backupArray, 0, primaryArray.length);
        System.arraycopy(primaryArray, 0, disk, 2 * SECTOR, primaryArray.length);
        System.arraycopy(backupArray, 0, disk, 254 * SECTOR, backupArray.length);
        writeHeader(disk, 1, 255, 2, entrySize, primaryArray);
        writeHeader(disk, 255, 1, 254, entrySize, backupArray);
        return disk;
    }

    private static void makePartitionEntry(byte[] entry, int entrySize) {
        byte[] typeGuid = guidBytes("11111111-2222-3333-4444-555555555555");
        byte[] uniqueGuid = guidBytes("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        System.arraycopy(typeGuid, 0, entry, 0, 16);
        System.arraycopy(uniqueGuid, 0, entry, 16, 16);
        put64(entry, 32, 40);
        put64(entry, 40, 49);
        put64(entry, 48, 0);
        byte[] name = "DATA".getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(name, 0, entry, 56, Math.min(name.length, entrySize - 56));
    }

    private static void writeHeader(byte[] disk, int lba, int backupLba, int entriesLba,
                                    int entrySize, byte[] array) {
        int offset = lba * SECTOR;
        byte[] signature = "EFI PART".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(signature, 0, disk, offset, signature.length);
        put32(disk, offset + 8, 0x00010000);
        put32(disk, offset + 12, 92);
        put64(disk, offset + 24, lba);
        put64(disk, offset + 32, backupLba);
        put64(disk, offset + 40, 34);
        put64(disk, offset + 48, 220);
        System.arraycopy(guidBytes("12345678-9abc-def0-1234-56789abcdef0"), 0, disk, offset + 56, 16);
        put64(disk, offset + 72, entriesLba);
        put32(disk, offset + 80, 1);
        put32(disk, offset + 84, entrySize);
        put32(disk, offset + 88, (int) crc(array));
        updateHeaderCrc(disk, lba);
    }

    private static void updateArrayCrc(byte[] disk, int lba, int count, int entrySize) {
        byte[] array = Arrays.copyOfRange(disk, lba * SECTOR, lba * SECTOR + count * entrySize);
        int headerOffset = lba == 2 ? SECTOR : 255 * SECTOR;
        put32(disk, headerOffset + 88, (int) crc(array));
    }

    private static void updateHeaderCrc(byte[] disk, int lba) {
        int offset = lba * SECTOR;
        put32(disk, offset + 16, 0);
        byte[] header = Arrays.copyOfRange(disk, offset, offset + 92);
        put32(disk, offset + 16, (int) crc(header));
    }

    private static void copyArray(byte[] disk, int sourceLba, int targetLba, int count, int entrySize) {
        System.arraycopy(disk, sourceLba * SECTOR, disk, targetLba * SECTOR, count * entrySize);
    }

    private static byte[] guidBytes(String text) {
        java.util.UUID uuid = java.util.UUID.fromString(text);
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt((int) (uuid.getMostSignificantBits() >>> 32));
        buffer.putShort((short) (uuid.getMostSignificantBits() >>> 16));
        buffer.putShort((short) uuid.getMostSignificantBits());
        buffer.order(ByteOrder.BIG_ENDIAN).putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    private static long crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    private static void put32(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    }

    private static void put64(byte[] bytes, int offset, long value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value);
    }
}
