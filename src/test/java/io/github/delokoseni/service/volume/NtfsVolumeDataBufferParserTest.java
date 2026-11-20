package io.github.delokoseni.service.volume;

import io.github.delokoseni.model.NtfsVolumeData;
import io.github.delokoseni.service.ntfs.NtfsCalculationService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NtfsVolumeDataBufferParserTest {
    @Test
    void decodesNativeStructureOffsetsAndPreservesUnsignedSerialBitPattern() throws Exception {
        byte[] buffer = validBuffer();
        NtfsVolumeData data = NtfsVolumeDataBufferParser.parse(buffer, 128);

        assertEquals(Long.MIN_VALUE + 123, data.volumeSerialNumber());
        assertEquals(500_084_735, data.numberOfSectors());
        assertEquals(62_510_591, data.totalClusters());
        assertEquals(21_572_958, data.freeClusters());
        assertEquals(1_024, data.reservedClusters());
        assertEquals(512, data.bytesPerSector());
        assertEquals(4_096, data.bytesPerCluster());
        assertEquals(1_024, data.bytesPerFileRecordSegment());
        assertEquals(0, data.clustersPerFileRecordSegment());
        assertEquals(14_417_920, data.mftValidDataLength());
        assertEquals(786_432, data.mftStartLcn());
        assertEquals(2, data.mftMirrorStartLcn());
        assertEquals(789_952, data.mftZoneStartLcn());
        assertEquals(837_696, data.mftZoneEndLcn());
        assertEquals(62_510_591L * 4_096L,
                new NtfsCalculationService().calculate(data).totalClusterCapacityBytes());
    }

    @Test
    void rejectsTruncatedAndOutOfBoundsReturnedLengths() {
        byte[] buffer = validBuffer();
        IOException truncated = assertThrows(IOException.class,
                () -> NtfsVolumeDataBufferParser.parse(buffer, 95));
        assertTrue(truncated.getMessage().contains("bytesReturned=95"));
        IOException tooLarge = assertThrows(IOException.class,
                () -> NtfsVolumeDataBufferParser.parse(buffer, 129));
        assertTrue(tooLarge.getMessage().contains("exceeds output buffer size"));
    }

    @Test
    void rejectsInvalidCountsAndSectorSizesWithFieldNames() {
        byte[] invalidClusters = validBuffer();
        put64(invalidClusters, 16, -1);
        IOException countError = assertThrows(IOException.class,
                () -> NtfsVolumeDataBufferParser.parse(invalidClusters, 96));
        assertTrue(countError.getMessage().contains("TotalClusters"));

        byte[] invalidSectorSize = validBuffer();
        put32(invalidSectorSize, 40, 0);
        IOException sectorError = assertThrows(IOException.class,
                () -> NtfsVolumeDataBufferParser.parse(invalidSectorSize, 96));
        assertTrue(sectorError.getMessage().contains("BytesPerSector"));

        byte[] unsupportedUnsignedSector = validBuffer();
        put32(unsupportedUnsignedSector, 40, 0x80000000);
        IOException rangeError = assertThrows(IOException.class,
                () -> NtfsVolumeDataBufferParser.parse(unsupportedUnsignedSector, 96));
        assertTrue(rangeError.getMessage().contains("BytesPerSector"));
    }

    static byte[] validBuffer() {
        byte[] bytes = new byte[128];
        put64(bytes, 0, Long.MIN_VALUE + 123);
        put64(bytes, 8, 500_084_735);
        put64(bytes, 16, 62_510_591);
        put64(bytes, 24, 21_572_958);
        put64(bytes, 32, 1_024);
        put32(bytes, 40, 512);
        put32(bytes, 44, 4_096);
        put32(bytes, 48, 1_024);
        put32(bytes, 52, 0);
        put64(bytes, 56, 14_417_920);
        put64(bytes, 64, 786_432);
        put64(bytes, 72, 2);
        put64(bytes, 80, 789_952);
        put64(bytes, 88, 837_696);
        return bytes;
    }

    private static void put32(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    }

    private static void put64(byte[] bytes, int offset, long value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value);
    }
}
