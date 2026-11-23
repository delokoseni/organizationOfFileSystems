package io.github.delokoseni.service.disk;

import io.github.delokoseni.model.PartitionStyle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowsLayoutBufferParserTest {
    private static final int HEADER_SIZE = 48;
    private static final int ENTRY_SIZE = 144;

    @Test
    void parsesWindowsMbrLayoutFields() throws Exception {
        byte[] bytes = layout(0);
        put32(bytes, HEADER_SIZE + 24, 1);
        put64(bytes, HEADER_SIZE + 8, 1024);
        put64(bytes, HEADER_SIZE + 16, 4096);
        bytes[HEADER_SIZE + 32] = 0x07;

        WindowsPartitionLayout parsed = new WindowsLayoutBufferParser().parse(bytes, bytes.length);

        assertEquals(PartitionStyle.MBR, parsed.style());
        assertEquals(1, parsed.partitions().getFirst().partitionNumber());
        assertEquals(1024, parsed.partitions().getFirst().startingOffsetBytes());
        assertEquals(0x07, parsed.partitions().getFirst().mbrType());
    }

    @Test
    void ignoresUnusedMbrPartitionSlotsReturnedByWindows() throws Exception {
        byte[] bytes = new byte[HEADER_SIZE + ENTRY_SIZE * 4];
        put32(bytes, 0, 0);
        put32(bytes, 4, 4);
        put32(bytes, HEADER_SIZE + 24, 1);
        put64(bytes, HEADER_SIZE + 8, 1_048_576);
        put64(bytes, HEADER_SIZE + 16, 61_990_764_544L);
        bytes[HEADER_SIZE + 32] = 0x07;

        WindowsPartitionLayout parsed = new WindowsLayoutBufferParser().parse(bytes, bytes.length);

        assertEquals(1, parsed.partitions().size());
        assertEquals(1, parsed.partitions().getFirst().partitionNumber());
        assertEquals(61_990_764_544L, parsed.partitions().getFirst().lengthBytes());
    }

    @Test
    void preservesEntriesWithZeroGeometryButOtherPartitionMetadata() throws Exception {
        byte[] bytes = new byte[HEADER_SIZE + ENTRY_SIZE];
        put32(bytes, 0, 0);
        put32(bytes, 4, 1);
        bytes[HEADER_SIZE + 28] = 1;

        WindowsPartitionLayout parsed = new WindowsLayoutBufferParser().parse(bytes, bytes.length);

        assertEquals(1, parsed.partitions().size());
    }

    @Test
    void rejectsTruncatedAndExcessiveLayoutBuffers() {
        byte[] truncated = new byte[HEADER_SIZE + ENTRY_SIZE];
        put32(truncated, 4, 2);
        assertThrows(IOException.class,
                () -> new WindowsLayoutBufferParser().parse(truncated, truncated.length));

        byte[] excessive = new byte[HEADER_SIZE];
        put32(excessive, 4, 16_385);
        assertThrows(IOException.class, () -> new WindowsLayoutBufferParser().parse(excessive, excessive.length));
    }

    private static byte[] layout(int style) {
        byte[] bytes = new byte[HEADER_SIZE + ENTRY_SIZE];
        put32(bytes, 0, style);
        put32(bytes, 4, 1);
        return bytes;
    }

    private static void put32(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    }

    private static void put64(byte[] bytes, int offset, long value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value);
    }
}
