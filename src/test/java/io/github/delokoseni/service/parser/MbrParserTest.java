package io.github.delokoseni.service.parser;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MbrParserTest {
    private final MbrParser parser = new MbrParser();

    @Test
    void parsesValidMbrAndFourPrimaryEntries() throws Exception {
        byte[] disk = mbrDisk(2048);
        for (int i = 0; i < 4; i++) {
            putMbrEntry(disk, 446 + i * 16, 0, 0x07, 10 + i * 20, 10);
        }

        MbrParseResult result = parser.parse(reader(disk));

        assertTrue(result.signatureValid());
        assertEquals(4, result.primaryPartitions().size());
        assertEquals(5120, result.primaryPartitions().getFirst().startingOffsetBytes());
        assertEquals(5120, result.primaryPartitions().getFirst().lengthBytes());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void reportsBadSignatureAndAcceptsEmptySignedMbr() throws Exception {
        byte[] empty = mbrDisk(1024);
        assertTrue(parser.parse(reader(empty)).signatureValid());
        empty[510] = 0;
        MbrParseResult invalid = parser.parse(reader(empty));
        assertFalse(invalid.signatureValid());
        assertTrue(invalid.diagnostics().stream().anyMatch(text -> text.contains("signature")));
    }

    @Test
    void parsesValidExtendedChain() throws Exception {
        byte[] disk = mbrDisk(1024);
        putMbrEntry(disk, 446, 0, 0x0f, 100, 80);
        putEbr(disk, 100, 0x07, 1, 10, 0x0f, 20, 60);
        putEbr(disk, 120, 0x07, 1, 10, 0, 0, 0);

        MbrParseResult result = parser.parse(reader(disk));

        assertEquals(2, result.logicalPartitions().size());
        assertEquals(101, result.logicalPartitions().getFirst().firstLba());
        assertEquals(121, result.logicalPartitions().getLast().firstLba());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void rejectsOutOfRangeAndCyclicEbrLinks() throws Exception {
        byte[] outside = mbrDisk(1024);
        putMbrEntry(outside, 446, 0, 0x0f, 100, 80);
        putEbr(outside, 100, 0, 0, 0, 0x0f, 100, 1);
        assertTrue(parser.parse(reader(outside)).diagnostics().stream()
                .anyMatch(text -> text.contains("outside")));

        byte[] cyclic = mbrDisk(1024);
        putMbrEntry(cyclic, 446, 0, 0x0f, 100, 80);
        putEbr(cyclic, 100, 0x07, 1, 10, 0x0f, 20, 60);
        putEbr(cyclic, 120, 0x07, 1, 10, 0x0f, 0, 80);
        assertTrue(parser.parse(reader(cyclic)).diagnostics().stream()
                .anyMatch(text -> text.contains("Cyclic")));
    }

    @Test
    void stopsOnInvalidEbrSignatureAndInvalidLinkType() throws Exception {
        byte[] badSignature = mbrDisk(256);
        putMbrEntry(badSignature, 446, 0, 0x0f, 100, 80);
        assertTrue(parser.parse(reader(badSignature)).diagnostics().stream()
                .anyMatch(text -> text.contains("Invalid EBR signature")));

        byte[] badLink = mbrDisk(256);
        putMbrEntry(badLink, 446, 0, 0x0f, 100, 80);
        putEbr(badLink, 100, 0, 0, 0, 0x07, 20, 10);
        assertTrue(parser.parse(reader(badLink)).diagnostics().stream()
                .anyMatch(text -> text.contains("not an extended")));
    }

    @Test
    void capsEbrChainLength() throws Exception {
        int sectors = 5001;
        byte[] disk = mbrDisk(sectors);
        putMbrEntry(disk, 446, 0, 0x0f, 100, 4900);
        for (int lba = 100; lba < 4196; lba++) {
            putEbr(disk, lba, 0, 0, 0, 0x0f, lba - 99, 5000 - (lba + 1));
        }

        assertTrue(parser.parse(reader(disk)).diagnostics().stream()
                .anyMatch(text -> text.contains("exceeded the maximum")));
    }

    @Test
    void reportsInvalidBootFlagAndPartitionOutsideDisk() throws Exception {
        byte[] disk = mbrDisk(64);
        putMbrEntry(disk, 446, 0x7f, 0x07, 60, 10);

        MbrParseResult result = parser.parse(reader(disk));

        assertTrue(result.diagnostics().stream().anyMatch(text -> text.contains("boot indicator")));
        assertTrue(result.diagnostics().stream().anyMatch(text -> text.contains("capacity")));
    }

    @Test
    void refusesTruncatedSectorAndRejectsOverlappingEntries() throws Exception {
        byte[] shortDisk = new byte[256];
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> parser.parse(new SyntheticDiskReader(shortDisk, 512, 512)))
                .getMessage().contains("outside disk"));

        byte[] disk = mbrDisk(100);
        putMbrEntry(disk, 446, 0, 0x07, 10, 20);
        putMbrEntry(disk, 462, 0, 0x07, 20, 20);
        assertTrue(parser.parse(reader(disk)).diagnostics().stream()
                .anyMatch(text -> text.contains("Overlapping")));
    }

    private static SyntheticDiskReader reader(byte[] bytes) {
        return new SyntheticDiskReader(bytes, 512);
    }

    private static byte[] mbrDisk(int sectors) {
        byte[] disk = new byte[sectors * 512];
        disk[510] = 0x55;
        disk[511] = (byte) 0xaa;
        return disk;
    }

    private static void putMbrEntry(byte[] disk, int offset, int boot, int type, long start, long count) {
        disk[offset] = (byte) boot;
        disk[offset + 4] = (byte) type;
        ByteBuffer.wrap(disk).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(offset + 8, (int) start)
                .putInt(offset + 12, (int) count);
    }

    private static void putEbr(byte[] disk, int lba, int firstType, int firstStart, int firstCount,
                               int linkType, int linkStart, int linkCount) {
        int base = lba * 512;
        disk[base + 510] = 0x55;
        disk[base + 511] = (byte) 0xaa;
        if (firstType != 0) {
            putMbrEntry(disk, base + 446, 0, firstType, firstStart, firstCount);
        }
        if (linkType != 0) {
            putMbrEntry(disk, base + 462, 0, linkType, linkStart, linkCount);
        }
    }
}
