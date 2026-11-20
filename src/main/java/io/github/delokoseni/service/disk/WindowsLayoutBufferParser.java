package io.github.delokoseni.service.disk;

import io.github.delokoseni.model.PartitionStyle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WindowsLayoutBufferParser {
    private static final int PARTITION_ARRAY_OFFSET = 48;
    private static final int PARTITION_INFO_SIZE = 144;
    private static final int MAX_PARTITIONS = 16_384;

    public WindowsPartitionLayout parse(byte[] bytes, int bytesReturned) throws IOException {
        if (bytes == null || bytesReturned < 8 || bytesReturned > bytes.length) {
            throw new IOException("Windows returned a truncated drive layout");
        }
        int styleCode = i32(bytes, 0);
        PartitionStyle style = switch (styleCode) {
            case 0 -> PartitionStyle.MBR;
            case 1 -> PartitionStyle.GPT;
            case 2 -> PartitionStyle.RAW;
            default -> PartitionStyle.UNKNOWN;
        };
        long count = u32(bytes, 4);
        if (count > MAX_PARTITIONS) {
            throw new IOException("Windows drive layout partition count exceeds parser limits");
        }
        long required = PARTITION_ARRAY_OFFSET + count * PARTITION_INFO_SIZE;
        if (required > bytesReturned) {
            throw new IOException("Windows returned an incomplete drive layout");
        }

        List<WindowsPartitionInfo> partitions = new ArrayList<>((int) count);
        for (int i = 0; i < count; i++) {
            int offset = PARTITION_ARRAY_OFFSET + i * PARTITION_INFO_SIZE;
            long startingOffset = i64(bytes, offset + 8);
            long length = i64(bytes, offset + 16);
            long partitionNumber = u32(bytes, offset + 24);
            if (startingOffset < 0 || length < 0 || partitionNumber > Integer.MAX_VALUE) {
                throw new IOException("Windows returned unsupported partition offsets or numbers");
            }
            int mbrType = style == PartitionStyle.MBR ? u8(bytes, offset + 32) : 0;
            String typeGuid = style == PartitionStyle.GPT ? guid(bytes, offset + 32) : "";
            String uniqueGuid = style == PartitionStyle.GPT ? guid(bytes, offset + 48) : "";
            partitions.add(new WindowsPartitionInfo((int) partitionNumber, startingOffset,
                    length, mbrType, typeGuid, uniqueGuid));
        }
        return new WindowsPartitionLayout(style, partitions);
    }

    private static int u8(byte[] bytes, int offset) {
        return bytes[offset] & 0xff;
    }

    private static int i32(byte[] bytes, int offset) {
        return u8(bytes, offset) | (u8(bytes, offset + 1) << 8)
                | (u8(bytes, offset + 2) << 16) | (u8(bytes, offset + 3) << 24);
    }

    private static long u32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(i32(bytes, offset));
    }

    private static long i64(byte[] bytes, int offset) {
        return ((long) i32(bytes, offset + 4) << 32) | u32(bytes, offset);
    }

    private static String guid(byte[] bytes, int offset) {
        long data1 = u32(bytes, offset);
        int data2 = u8(bytes, offset + 4) | (u8(bytes, offset + 5) << 8);
        int data3 = u8(bytes, offset + 6) | (u8(bytes, offset + 7) << 8);
        StringBuilder result = new StringBuilder(36);
        result.append(String.format(Locale.ROOT, "%08x-%04x-%04x-", data1, data2, data3));
        for (int i = 8; i < 10; i++) {
            result.append(String.format(Locale.ROOT, "%02x", u8(bytes, offset + i)));
        }
        result.append('-');
        for (int i = 10; i < 16; i++) {
            result.append(String.format(Locale.ROOT, "%02x", u8(bytes, offset + i)));
        }
        return result.toString();
    }
}
