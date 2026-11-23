package io.github.delokoseni.service.ntfs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NtfsBootSectorParserTest {
    private final NtfsBootSectorParser parser = new NtfsBootSectorParser();

    @Test
    void parsesNtfsBpbAndNegativeSubClusterFileRecordEncoding() throws Exception {
        NtfsBootSectorInfo info = parser.parse(validBootSector());

        assertEquals(512, info.bytesPerSector());
        assertEquals(8, info.sectorsPerCluster());
        assertEquals(500_084_735, info.totalSectors());
        assertEquals(786_432, info.mftStartLcn());
        assertEquals(2, info.mftMirrorStartLcn());
        assertEquals(-10, info.fileRecordSizeEncoding());
        assertEquals(1024, info.fileRecordSizeBytes());
    }

    @Test
    void rejectsInvalidSignatureAndRecordEncoding() {
        byte[] badSignature = validBootSector();
        badSignature[510] = 0;
        assertTrue(assertThrows(IOException.class, () -> parser.parse(badSignature))
                .getMessage().contains("Сигнатура"));

        byte[] zeroEncoding = validBootSector();
        zeroEncoding[64] = 0;
        assertTrue(assertThrows(IOException.class, () -> parser.parse(zeroEncoding))
                .getMessage().contains("Кодирование"));
    }

    static byte[] validBootSector() {
        byte[] bytes = new byte[512];
        System.arraycopy("NTFS    ".getBytes(StandardCharsets.US_ASCII), 0, bytes, 3, 8);
        put16(bytes, 11, 512);
        bytes[13] = 8;
        put64(bytes, 40, 500_084_735);
        put64(bytes, 48, 786_432);
        put64(bytes, 56, 2);
        bytes[64] = (byte) -10;
        bytes[510] = 0x55;
        bytes[511] = (byte) 0xaa;
        return bytes;
    }

    private static void put16(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, (short) value);
    }

    private static void put64(byte[] bytes, int offset, long value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value);
    }
}
