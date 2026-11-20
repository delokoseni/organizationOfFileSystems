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
                .anyMatch(text -> text.contains("header CRC32")));
        assertEquals(1, badHeaderResult.partitions().size(),
                "A valid backup GPT should be used when the primary header CRC is invalid");

        byte[] badArray = validGpt(128);
        badArray[2 * SECTOR + 64] ^= 0x01;
        badArray[254 * SECTOR + 64] ^= 0x01;
        GptParseResult result = parser.parse(reader(badArray));
        assertTrue(result.diagnostics().stream().anyMatch(text -> text.contains("array CRC32")));
        assertTrue(result.partitions().isEmpty(),
                "Entries with invalid primary and backup array CRCs must not be displayed");
    }

    @Test
    void diagnosesMissingBackupAndInconsistentPrimaryBackupEntries() throws Exception {
        byte[] missingBackup = validGpt(128);
        Arrays.fill(missingBackup, (DISK_SECTORS - 1) * SECTOR,
                DISK_SECTORS * SECTOR, (byte) 0);
        assertTrue(parser.parse(reader(missingBackup)).diagnostics().stream()
                .anyMatch(text -> text.contains("Backup GPT header is missing")));

        byte[] inconsistent = validGpt(128);
        inconsistent[254 * SECTOR + 64] ^= 0x01;
        updateArrayCrc(inconsistent, 254, 1, 128);
        updateHeaderCrc(inconsistent, 255);
        GptParseResult result = parser.parse(reader(inconsistent));
        assertTrue(result.diagnostics().stream().anyMatch(text -> text.contains("entries differ")));
    }

    @Test
    void detectsMissingPrimaryAndInconsistentDiskGuid() throws Exception {
        byte[] missingPrimary = validGpt(128);
        Arrays.fill(missingPrimary, SECTOR, 2 * SECTOR, (byte) 0);
        assertTrue(parser.parse(reader(missingPrimary)).diagnostics().stream()
                .anyMatch(text -> text.contains("Primary GPT header is missing")));

        byte[] differentGuid = validGpt(128);
        differentGuid[255 * SECTOR + 56] ^= 0x01;
        updateHeaderCrc(differentGuid, 255);
        assertTrue(parser.parse(reader(differentGuid)).diagnostics().stream()
                .anyMatch(text -> text.contains("disk GUIDs differ")));
    }

    @Test
    void detectsIncorrectHeaderLocationsAndUsableRanges() throws Exception {
        byte[] wrongBackupLocation = validGpt(128);
        put64(wrongBackupLocation, 255 * SECTOR + 24, 254);
        updateHeaderCrc(wrongBackupLocation, 255);
        assertTrue(parser.parse(reader(wrongBackupLocation)).diagnostics().stream()
                .anyMatch(text -> text.contains("incorrect current LBA")));

        byte[] invalidUsableRange = validGpt(128);
        put64(invalidUsableRange, SECTOR + 40, 230);
        put64(invalidUsableRange, 255 * SECTOR + 40, 230);
        updateHeaderCrc(invalidUsableRange, 1);
        updateHeaderCrc(invalidUsableRange, 255);
        assertTrue(parser.parse(reader(invalidUsableRange)).diagnostics().stream()
                .anyMatch(text -> text.contains("usable LBA range is invalid")));
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
                .anyMatch(text -> text.contains("unsigned 64-bit")));
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
                .anyMatch(text -> text.contains("invalid LBA boundaries")));

        byte[] invalidEntrySize = validGpt(129);
        assertTrue(parser.parse(reader(invalidEntrySize)).diagnostics().stream()
                .anyMatch(text -> text.contains("entry count or size")));
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
                .anyMatch(text -> text.contains("parser limits")));
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
